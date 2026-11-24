package io.joryio.sdk.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.TestServer
import io.joryio.sdk.core.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.4.0-beta.10 test: Android sent each flag exposure twice per relaunch where
 * iOS sent it once. The server read the POST, then the connection dropped;
 * OkHttp (`retryOnConnectionFailure`, on by default) silently sent the same
 * body again, so the server counted every exposure in it twice. The calls
 * documented "ONCE" - flags (evaluate, exposures), placements and cards - go
 * once, as on iOS and the web.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendOnceTest {

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

    /**
     * The server reads the request, then the connection drops. The request goes
     * on a kept-alive connection (a warm-up request first), as in the app: that
     * is when OkHttp re-sends a POST on its own.
     */
    private fun dropAfterRead(path: String) {
        runBlocking { client.postFlags("v1/warm-up", "{}") }
        api.answers[path] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
    }

    private fun count(path: String) = api.paths().count { it.endsWith(path) }

    @Test
    fun aFlagExposureWhoseConnectionDroppedAfterTheServerReadItIsNotSentAgain() = runBlocking {
        dropAfterRead("/v1/flags/exposures")
        val body = """{"userId":"a","anonymousId":"x","sessionId":"s","attributes":{},"context":{},"exposures":[{"key":"k","variant":"on"}]}"""
        assertNull(client.postFlags("v1/flags/exposures", body))
        assertEquals("one request, one exposure counted", 1, count("/v1/flags/exposures"))
    }

    @Test
    fun aFlagsEvaluateGoesOnce() = runBlocking {
        dropAfterRead("/v1/flags/evaluate")
        assertNull(client.postFlags("v1/flags/evaluate", """{"anonymousId":"x"}"""))
        assertEquals(1, count("/v1/flags/evaluate"))
    }

    @Test
    fun aPlacementsRequestGoesOnce() = runBlocking {
        dropAfterRead("/v1/in-app/placements")
        assertEquals(0, client.fetchPlacements("""{"placements":["home_hero"]}""").first)
        assertEquals(1, count("/v1/in-app/placements"))
    }

    @Test
    fun aCardsRequestGoesOnce() = runBlocking {
        dropAfterRead("/v1/in-app/cards")
        assertEquals(0, client.fetchCards("""{"anonymousId":"x"}""").first)
        assertEquals(1, count("/v1/in-app/cards"))
    }
}
