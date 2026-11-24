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

    private fun refreshed(sdk: Joryio) {
        var done = false
        sdk.refreshFlags { done = true }
        assertTrue(pumpUntil { done })
    }

    private fun attrs(o: com.google.gson.JsonObject) = o.getAsJsonObject("attributes")?.keySet() ?: emptySet<String>()

    /**
     * 1.4.0-beta.10 test: identify(A) + setAttributes, then identify(B) of a
     * DIFFERENT user + setAttributes({plan:'free'}) kept A's `seats` / `ratio`
     * in every one of B's flags evaluates. As on iOS: B starts with no
     * attributes, B's own only after; the same user again keeps them; reset()
     * clears them.
     */
    @Test
    fun `identify of a different user drops the previous user's attributes before any request for them`() {
        val sdk = init()
        sdk.getBoolFlag("new_checkout", false) // flags in use
        sdk.identify("user-a")
        sdk.setAttributes(mapOf("plan" to "pro", "seats" to 10, "ratio" to 2.5))
        refreshed(sdk)
        val a = bodies("/v1/flags/evaluate").last { it["userId"]?.asString == "user-a" }
        assertTrue("A's own attributes go with A", attrs(a).containsAll(listOf("plan", "seats", "ratio")))
        sdk.getStringFlag("headline", "d") // an exposure read as A

        sdk.identify("user-b")
        sdk.setAttributes(mapOf("plan" to "free"))
        refreshed(sdk)
        assertTrue(pumpUntil { bodies("/v1/flags/exposures").any { it["userId"]?.asString == "user-a" } })
        val forB = bodies("/v1/flags/evaluate").filter { it["userId"]?.asString == "user-b" }
        assertTrue(forB.isNotEmpty())
        forB.forEach { assertTrue("none of A's attributes in B's evaluate: ${attrs(it)}", attrs(it).none { k -> k == "seats" || k == "ratio" }) }
        assertEquals("free", forB.last().getAsJsonObject("attributes")["plan"].asString)
        api.seen.filter { it.path.endsWith("/v1/in-app/sync") && it.body.contains("\"user-b\"") }.forEach {
            val o = com.google.gson.JsonParser.parseString(it.body).asJsonObject
            assertTrue("none of A's attributes in B's in-app sync", attrs(o).none { k -> k == "seats" || k == "ratio" })
        }
        bodies("/v1/flags/exposures").filter { it["userId"]?.asString == "user-a" }.forEach {
            assertEquals("A's exposures carry A's attributes", 10, it.getAsJsonObject("attributes")["seats"].asInt)
        }

        // The same user again: their attributes stay.
        sdk.identify("user-b")
        refreshed(sdk)
        assertEquals("free", bodies("/v1/flags/evaluate").last().getAsJsonObject("attributes")["plan"]?.asString)

        // reset(): cleared.
        sdk.reset()
        refreshed(sdk)
        val afterReset = bodies("/v1/flags/evaluate").last()
        assertNull(afterReset["userId"])
        assertTrue(attrs(afterReset).none { it == "plan" || it == "seats" || it == "ratio" })
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
