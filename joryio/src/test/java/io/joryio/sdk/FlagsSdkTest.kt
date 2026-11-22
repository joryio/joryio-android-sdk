package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.flags.FlagsManagerTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Feature flags through the facade (BACKLOG D446): the wiring to identify, consent and the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlagsSdkTest {
    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        api.answers["/v1/flags/evaluate"] = { TestServer.json(FlagsManagerTest.ANSWER) }
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    private fun init(config: JoryioConfig = JoryioConfig(flushInterval = 60_000, trackSessionStart = false)): Joryio =
        Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, config)

    private fun bodies(path: String) = api.seen.filter { it.path.endsWith(path) }.map {
        com.google.gson.JsonParser.parseString(it.body).asJsonObject
    }

    @Test
    fun `fetches with the SDK key and refetches for the identified person`() {
        val sdk = init()
        assertEquals("before the first answer", false, sdk.getBoolFlag("new_checkout", false))
        var refreshed = false
        sdk.refreshFlags { refreshed = true }
        assertTrue(pumpUntil { refreshed })
        assertEquals(true, sdk.getBoolFlag("new_checkout", false))
        val first = api.seen.first { it.path.endsWith("/v1/flags/evaluate") }
        assertTrue(first.headers.names().any { it.equals("X-SDK-Key", true) || it.equals("Authorization", true) || it.equals("X-App-Key", true) })
        assertEquals(sdk.getIdentity().second, bodies("/v1/flags/evaluate")[0]["anonymousId"].asString)
        sdk.identify("user-42")
        assertTrue(pumpUntil { bodies("/v1/flags/evaluate").any { it["userId"]?.asString == "user-42" } })
        assertTrue(pumpUntil { bodies("/v1/flags/exposures").isNotEmpty() })
        assertNull("read before identify: sent as the anonymous visitor", bodies("/v1/flags/exposures")[0]["userId"])
    }

    @Test
    fun `without consent nothing is sent and the getters return the default`() {
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackSessionStart = false, trackingConsent = TrackingConsent.PENDING))
        assertEquals("d", sdk.getStringFlag("headline", "d"))
        var refreshed = false
        sdk.refreshFlags { refreshed = true }
        assertTrue(pumpUntil { refreshed })
        assertEquals("d", sdk.getStringFlag("headline", "d"))
        assertTrue(bodies("/v1/flags/evaluate").isEmpty())
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil { sdk.getStringFlag("headline", "d") == "Hello" })
        sdk.updateConsent(TrackingConsent.DENIED)
        assertEquals("d", sdk.getStringFlag("headline", "d"))
    }

    @Test
    fun `the shared-instance forwarders answer the default before initialize`() {
        assertEquals(3.0, Joryio.getNumberFlag("limit", 3.0), 0.0)
        assertTrue(Joryio.getAllFlags().isEmpty())
    }
}
