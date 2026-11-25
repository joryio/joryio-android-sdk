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

    // ── 1.4.0-beta.13: a batch the server did not take goes to the persisted write outbox ──

    @Volatile private var exposuresFail = true

    private fun failExposures() {
        exposuresFail = true
        api.answers["/v1/flags/exposures"] = {
            if (exposuresFail) TestServer.json("{}", 503) else TestServer.json("""{"success":true,"accepted":1,"dropped":0}""")
        }
    }

    /** The outbox as stored on the device. */
    private fun outboxOnDisk(sdk: Joryio): String {
        val f = Joryio::class.java.getDeclaredField("storage")
        f.isAccessible = true
        return (f.get(sdk) as io.joryio.sdk.core.StorageManager).getOutbox() ?: ""
    }

    /** The process dies: nothing of this instance runs any more (resetForTesting alone leaves its coroutines). */
    private fun kill(sdk: Joryio) {
        val f = Joryio::class.java.getDeclaredField("scope")
        f.isAccessible = true
        (f.get(sdk) as kotlinx.coroutines.CoroutineScope).coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Joryio.resetForTesting()
    }

    private fun storedExposures(sdk: Joryio): String? {
        val f = Joryio::class.java.getDeclaredField("storage")
        f.isAccessible = true
        return (f.get(sdk) as io.joryio.sdk.core.StorageManager).getFlagExposures()
    }

    private fun exposureCalls() = api.seen.filter { it.path.endsWith("/v1/flags/exposures") }

    @Test
    fun `a failed send survives a relaunch - the outbox sends it once with the same key, and a read meanwhile adds no copy`() {
        failExposures()
        val key = "jry_sdk_android_test_${UUID.randomUUID()}"
        val cfg = JoryioConfig(flushInterval = 60_000, trackSessionStart = false)
        val first = Joryio.initialize(app, key, api.apiHost, cfg)
        refreshed(first)
        first.getBoolFlag("new_checkout", false)
        assertTrue(pumpUntil { outboxOnDisk(first).contains("flag_exposures") })
        val idempotencyKey = bodies("/v1/flags/exposures")[0]["idempotencyKey"].asString

        kill(first) // the app is killed before the outbox got it through
        val second = Joryio.initialize(app, key, api.apiHost, cfg)
        refreshed(second)
        second.getBoolFlag("new_checkout", false) // read again while it still waits
        pumpUntil(1_500) { false }
        exposuresFail = false
        kotlinx.coroutines.runBlocking { second.drainOutbox(force = true) }
        assertTrue(pumpUntil { !outboxOnDisk(second).contains("flag_exposures") })
        assertEquals("one batch, one key", setOf(idempotencyKey), bodies("/v1/flags/exposures").map { it["idempotencyKey"].asString }.toSet())
        val sentAfterSuccess = exposureCalls().size

        kill(second) // and a third launch in the session reads it as sent
        val third = Joryio.initialize(app, key, api.apiHost, cfg)
        refreshed(third)
        third.getBoolFlag("new_checkout", false)
        pumpUntil(1_500) { false }
        assertEquals(
            "sessions ${bodies("/v1/flags/exposures").map { it["sessionId"].asString + "/" + it["idempotencyKey"].asString.take(8) }} store ${storedExposures(third)}",
            sentAfterSuccess, exposureCalls().size,
        )
    }

    @Test
    fun `after wipeData a failed batch is never sent and nothing is left on disk`() {
        failExposures()
        val sdk = init()
        refreshed(sdk)
        sdk.getBoolFlag("new_checkout", false)
        // The direct attempt and the outbox's own first one (both 503) are through.
        assertTrue(pumpUntil { outboxOnDisk(sdk).contains("flag_exposures") && exposureCalls().size >= 2 })
        pumpUntil(300) { false }
        val before = exposureCalls().size
        sdk.wipeData()
        assertTrue(!outboxOnDisk(sdk).contains("flag_exposures"))
        exposuresFail = false
        kotlinx.coroutines.runBlocking { sdk.drainOutbox(force = true) }
        pumpUntil(1_500) { false }
        assertEquals(before, exposureCalls().size)
    }

    @Test
    fun `optOut purges it from the outbox - an optIn later does not send it`() {
        failExposures()
        val sdk = init()
        refreshed(sdk)
        sdk.getBoolFlag("new_checkout", false)
        // The direct attempt and the outbox's own first one (both 503) are through.
        assertTrue(pumpUntil { outboxOnDisk(sdk).contains("flag_exposures") && exposureCalls().size >= 2 })
        pumpUntil(300) { false }
        val before = exposureCalls().size
        sdk.optOut()
        assertTrue(!outboxOnDisk(sdk).contains("flag_exposures"))
        sdk.optIn()
        exposuresFail = false
        kotlinx.coroutines.runBlocking { sdk.drainOutbox(force = true) }
        pumpUntil(1_500) { false }
        assertEquals(before, exposureCalls().size)
    }

    /**
     * Rolling back from 1.4.0: the outbox is a list of `OutboxEntry` whose
     * `kind` is a plain string (unchanged since 1.3.0), restored by path and
     * body and sent by path - so an SDK before 1.4.0-beta.13 restores a
     * `flag_exposures` entry like any other write (and simply sends it), and
     * every other write with it. Here: the stored list read back after a
     * relaunch, with an entry of a kind no build knows next to them.
     */
    @Test
    fun `a stored outbox with a flag_exposures entry and an unknown kind restores every entry`() {
        val key = "jry_sdk_android_test_${UUID.randomUUID()}"
        val cfg = JoryioConfig(flushInterval = 60_000, trackSessionStart = false, trackingConsent = TrackingConsent.PENDING)
        val first = Joryio.initialize(app, key, api.apiHost, cfg)
        val f = Joryio::class.java.getDeclaredField("storage")
        f.isAccessible = true
        val storage = f.get(first) as io.joryio.sdk.core.StorageManager
        storage.setOutbox(
            """[{"id":"a","kind":"alias","path":"v1/alias/add","body":"{\"anonymousId\":\"anon-1\"}"},""" +
                """{"id":"e","kind":"flag_exposures","path":"v1/flags/exposures","body":"{\"anonymousId\":\"anon-1\",\"exposures\":[]}"},""" +
                """{"id":"x","kind":"kind_from_a_newer_build","path":"v1/whatever","body":"{\"anonymousId\":\"anon-1\"}"},""" +
                """{"id":"s","kind":"subscription","path":"v1/subscriptions/channel","body":"{\"anonymousId\":\"anon-1\"}"}]""",
        )
        kill(first)
        val second = Joryio.initialize(app, key, api.apiHost, cfg)
        val o = Joryio::class.java.getDeclaredField("outbox")
        o.isAccessible = true
        val restored = (o.get(second) as io.joryio.sdk.core.WriteOutbox).snapshot().map { it.id }
        assertEquals(listOf("a", "e", "x", "s"), restored)
    }

    @Test
    fun `the shared-instance forwarders answer the default before initialize`() {
        assertEquals(3.0, Joryio.getNumberFlag("limit", 3.0), 0.0)
        assertTrue(Joryio.getAllFlags().isEmpty())
    }
}
