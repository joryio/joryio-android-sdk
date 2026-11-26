package io.joryio.sdk.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.joryio.sdk.TestServer
import io.joryio.sdk.core.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.4.0-beta.11 sent flags, placements and cards with OkHttp's
 * connection-failure recovery switched off, so a request written to a pooled
 * connection the server had already closed (keep-alive expiry - the ALB's
 * idle timeout in production) failed at once with "unexpected end of stream"
 * and never reached the server. The beta.11 test: a 5 s server keep-alive,
 * more than 5 s idle, then getPlacements failed 1 ms after sending, and Show
 * when load / event never showed.
 *
 * The server here closes every connection right after its answer, without
 * saying so (no `Connection: close`): the client keeps the connection in its
 * pool, and the next request goes out on a dead socket - exactly the stale
 * keep-alive case. Every call must still get its answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StaleConnectionTest {

    private lateinit var api: TestServer
    private lateinit var client: NetworkClient

    @Before
    fun setUp() {
        api = TestServer()
        val app = ApplicationProvider.getApplicationContext<Application>()
        io.joryio.sdk.allowNetwork(app)
        client = NetworkClient(context = app, sdkKey = "jry_sdk_android_test", apiHost = api.apiHost, logger = Logger(false, io.joryio.sdk.LogLevel.ERROR), maxRetries = 0)
    }

    @After
    fun tearDown() = api.shutdown()

    /** A first request leaves a pooled connection the server then closes silently. */
    private fun staleConnection() {
        api.answers["/v1/warm-up"] = {
            TestServer.json("""{"success":true}""").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        runBlocking { client.postFlags("v1/warm-up", "{}") }
        // Let the server's close land before the next request reuses the socket.
        Thread.sleep(300)
    }

    private fun count(path: String) = api.paths().count { it.endsWith(path) }

    @Test
    fun aPlacementsRequestOnAStaleConnectionStillGetsItsAnswer() = runBlocking {
        staleConnection()
        api.answers["/v1/in-app/placements"] = { TestServer.json("""{"success":true,"placements":{}}""") }
        val (status, body) = client.fetchPlacements("""{"placements":["home_hero"]}""")
        assertEquals(200, status)
        assertNotNull(body)
        assertEquals(1, count("/v1/in-app/placements"))
    }

    @Test
    fun aCardsRequestOnAStaleConnectionStillGetsItsAnswer() = runBlocking {
        staleConnection()
        api.answers["/v1/in-app/cards"] = { TestServer.json("""{"success":true,"cards":[]}""") }
        assertEquals(200, client.fetchCards("""{"anonymousId":"x"}""").first)
        assertEquals(1, count("/v1/in-app/cards"))
    }

    @Test
    fun aFlagsEvaluateOnAStaleConnectionStillGetsItsAnswer() = runBlocking {
        staleConnection()
        api.answers["/v1/flags/evaluate"] = { TestServer.json("""{"flags":{},"etag":"e","ttlSeconds":300}""") }
        assertNotNull(client.postFlags("v1/flags/evaluate", """{"anonymousId":"x"}"""))
        assertEquals(1, count("/v1/flags/evaluate"))
    }

    @Test
    fun flagExposuresOnAStaleConnectionStillGetThrough() = runBlocking {
        staleConnection()
        val body = """{"anonymousId":"x","sessionId":"s","exposures":[{"key":"k","variant":"on"}],"idempotencyKey":"7f1c2a8e-0b7e-4c41-9a3e-5d2f8c6b1a90"}""" // gitleaks:allow
        assertNotNull(client.postFlags("v1/flags/exposures", body))
        assertEquals(1, count("/v1/flags/exposures"))
    }

    /**
     * The server read the exposures, then the connection dropped before the
     * answer: OkHttp sends the same request again. Both copies carry the same
     * `idempotencyKey`, so the server (POST /v1/flags/exposures) records the
     * batch once - what 1.4.0-beta.11 got by never re-sending at all.
     */
    @Test
    fun aReSentExposuresBatchCarriesTheSameIdempotencyKey() = runBlocking {
        runBlocking { client.postFlags("v1/warm-up", "{}") }
        var first = true
        api.answers["/v1/flags/exposures"] = {
            if (first) {
                first = false
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            } else {
                TestServer.json("""{"success":true,"accepted":1,"dropped":0}""")
            }
        }
        val body = """{"anonymousId":"x","sessionId":"s","exposures":[{"key":"k","variant":"on"}],"idempotencyKey":"7f1c2a8e-0b7e-4c41-9a3e-5d2f8c6b1a90"}""" // gitleaks:allow
        client.postFlags("v1/flags/exposures", body)
        val sent = api.seen.filter { it.path.endsWith("/v1/flags/exposures") }
        assertEquals("sent, dropped, sent again by OkHttp", 2, sent.size)
        val keys = sent.map { JsonParser.parseString(it.body).asJsonObject.get("idempotencyKey").asString }.toSet()
        assertEquals(setOf("7f1c2a8e-0b7e-4c41-9a3e-5d2f8c6b1a90"), keys)
    }

    private val exposures = """{"anonymousId":"x","sessionId":"s","exposures":[{"key":"k","variant":"on"}],"idempotencyKey":"7f1c2a8e-0b7e-4c41-9a3e-5d2f8c6b1a90"}""" // gitleaks:allow

    /**
     * 1.4.0-beta.14 test: a 408 on /v1/flags/exposures was sent again by
     * OkHttp ~12 ms later (its RetryAndFollowUpInterceptor re-sends a 408 with
     * no Retry-After when retryOnConnectionFailure is on), skipping the first
     * wait. Now the 408 comes back to the FlagsManager, which hands the batch
     * to the outbox; the server's own (absent) Retry-After is what it reads.
     */
    @Test
    fun aFlagExposures408IsNotReSentByOkHttp() = runBlocking {
        api.answers["/v1/flags/exposures"] = { TestServer.json("""{"error":"timeout"}""", 408) }
        val status = client.postFlagExposures(exposures)
        assertEquals(408, status.status)
        assertEquals("the server sent no Retry-After", null, status.retryAfterMs)
        assertEquals("sent once", 1, count("/v1/flags/exposures"))
    }

    @Test
    fun aFlagExposures408WithRetryAfterZeroIsNotReSentAndKeepsItsHeader() = runBlocking {
        api.answers["/v1/flags/exposures"] = { TestServer.json("{}", 408).setHeader("Retry-After", "0") }
        val status = client.postFlagExposures(exposures)
        assertEquals(408, status.status)
        assertEquals(0L, status.retryAfterMs)
        assertEquals(1, count("/v1/flags/exposures"))
    }

    /** The 408 change leaves the beta.12 stale-connection recovery in place for exposures. */
    @Test
    fun flagExposuresThroughTheirOwnCallOnAStaleConnectionStillGetThrough() = runBlocking {
        staleConnection()
        val status = client.postFlagExposures(exposures)
        assertEquals(200, status.status)
        assertEquals(1, count("/v1/flags/exposures"))
    }
}
