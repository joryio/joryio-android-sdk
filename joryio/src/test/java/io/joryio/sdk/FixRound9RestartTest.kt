package io.joryio.sdk

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.SdkAuthenticator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Scan 9 cross-SDK lead (web B1): adopted standby token, restart, wipeData. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound9RestartTest {
    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
        PreInitStore.appContext = app
        app.getSharedPreferences("joryio_decisions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        api.shutdown()
    }

    private fun cfg() = JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false, enableSdkAuthentication = true)
    private fun jwt(sub: String): String {
        fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        return "${b64("""{"alg":"HS256"}""")}.${b64("""{"sub":"$sub"}""")}.sig"
    }
    private fun authOf(sdk: Joryio): SdkAuthenticator {
        val client = Joryio::class.java.getDeclaredField("networkClient").apply { isAccessible = true }.get(sdk) as NetworkClient
        return client.javaClass.getDeclaredField("sdkAuthenticator").apply { isAccessible = true }.get(client) as SdkAuthenticator
    }

    @Test
    fun `R1 adopted standby token, restart, token set again, wipeData keeps it for the erased user's unsubscribe`() {
        api.answers["/v1/subscriptions/channel"] = { TestServer.json("""{"error":"down"}""", code = 503) }
        val k = "jry_sdk_android_test_${UUID.randomUUID()}"
        val first = Joryio.initialize(app, k, api.apiHost, cfg())
        first.setSdkAuthenticationToken(jwt("user-b")) // next user's, while anonymous
        first.reset() // standby for user-b
        first.identify("user-b") // adopts it
        assertEquals(jwt("user-b"), authOf(first).currentToken())
        first.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/subscriptions/channel") } })
        Joryio.resetForTesting() // restart
        val second = Joryio.initialize(app, k, api.apiHost, cfg())
        second.setSdkAuthenticationToken(jwt("user-b")) // the app sets it each launch
        second.wipeData()
        val kept = authOf(second).retiredUserIds()
        assertTrue("user-b's token survives the wipe: $kept", "user-b" in kept)
        assertEquals(jwt("user-b"), authOf(second).tokenFor("user-b", null))
    }

    @Test
    fun `R2 Session Start delivered then denial - next allowed launch announces a NEW session, never the same one twice`() {
        val k = "jry_sdk_android_test_${UUID.randomUUID()}"
        val c = JoryioConfig(flushInterval = 60_000, maxRetries = 0)
        val first = Joryio.initialize(app, k, api.apiHost, c)
        first.flush()
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/track/batch") && it.body.contains("Session Start") } })
        first.updateConsent(TrackingConsent.DENIED)
        pumpFor(300)
        Joryio.resetForTesting()
        val second = Joryio.initialize(app, k, api.apiHost, JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackingConsent = TrackingConsent.GRANTED))
        second.flush()
        pumpUntil(5_000) { api.seen.count { it.path.endsWith("/v1/track/batch") && it.body.contains("Session Start") } >= 2 }
        val starts = api.seen.filter { it.path.endsWith("/v1/track/batch") }.flatMap { b ->
            val o = com.google.gson.JsonParser.parseString(b.body).asJsonObject
            o.getAsJsonArray("events").map { it.asJsonObject }
                .filter { e -> (e.get("type") ?: e.get("event") ?: e.get("name"))?.asString == "Session Start" }
                .map { e -> e.get("sessionId")?.asString ?: "?" }
        }
        assertEquals("one Session Start per session: $starts", starts.size, starts.toSet().size)
    }
}
