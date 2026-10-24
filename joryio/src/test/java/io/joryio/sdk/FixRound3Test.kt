package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.models.InAppCampaign
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * SDK 1.3.0 fix round 3: scan 3 Android findings 1-13 and contract addendum
 * v3 (items 24-35). Each test names what it pins and fails on the code
 * before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound3Test {

    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
        // androidx.startup does this in an app; Robolectric runs no providers.
        PreInitStore.appContext = app
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        api.shutdown()
    }

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun init(k: String = key(), config: JoryioConfig = JoryioConfig(flushInterval = 60_000)): Joryio =
        Joryio.initialize(app, k, api.apiHost, config)

    private fun restart() = Joryio.resetForTesting()

    private fun bodiesOf(pathSuffix: String) = api.seen.filter { it.path.endsWith(pathSuffix) }.map { it.body }

    private fun markerBodies() = bodiesOf("/v1/attributes").filter { it.contains("tracking_opted_out") }

    // ── Finding 4 / contract 24: consent model A ───────────────────────

    @Test
    fun `updateConsent DENIED is not an opt-out, is persisted, and only an explicit GRANTED lifts it`() {
        // Fails before: DENIED was mapped to optOut() - isUserOptedOut() said
        // true, and GRANTED (= optIn) was the only way back.
        val k = key()
        val sdk = init(k)
        sdk.updateConsent(TrackingConsent.DENIED)
        assertFalse(sdk.isUserOptedOut())
        assertEquals(TrackingConsent.DENIED, sdk.getTrackingConsent())
        sdk.track("While Denied")
        restart()

        // Default config = consent not set: the stored denial stays.
        val second = init(k)
        assertEquals(TrackingConsent.DENIED, second.getTrackingConsent())
        restart()

        // PENDING never overrides a stored denial.
        val third = init(k, JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.PENDING))
        assertEquals(TrackingConsent.DENIED, third.getTrackingConsent())
        restart()

        // An explicit GRANTED in the config does.
        val fourth = init(k, JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.GRANTED))
        assertEquals(TrackingConsent.GRANTED, fourth.getTrackingConsent())
        fourth.track("Granted")
        fourth.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Granted") } })
        assertTrue(bodiesOf("/v1/track/batch").none { it.contains("While Denied") })
    }

    @Test
    fun `consent GRANTED never lifts an opt-out, and optOut never changes consent`() {
        // Fails before: updateConsent(GRANTED) was optIn().
        val sdk = init()
        sdk.optOut()
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(sdk.isUserOptedOut())
        assertEquals(TrackingConsent.GRANTED, sdk.getTrackingConsent())
        sdk.track("Still Out")
        sdk.flush()
        pumpFor(600)
        assertTrue(bodiesOf("/v1/track/batch").none { it.contains("Still Out") })

        sdk.optIn()
        sdk.track("Back In")
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Back In") } })
    }

    @Test
    fun `optIn does not grant consent`() {
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.DENIED))
        sdk.optIn()
        assertEquals(TrackingConsent.DENIED, sdk.getTrackingConsent())
        sdk.track("No")
        sdk.flush()
        pumpFor(600)
        assertTrue(bodiesOf("/v1/track/batch").isEmpty())
    }

    // ── Findings 8 and 10 / contract 33: the opt-out marker on change ──

    @Test
    fun `an opt-out decided at init is recorded on the profile`() {
        // Fails before: only a runtime optOut() sent tracking_opted_out.
        init(config = JoryioConfig(flushInterval = 60_000, optOut = true))
        assertTrue(pumpUntil { markerBodies().any { it.contains("\"tracking_opted_out\":true") } })
    }

    @Test
    fun `the marker is sent only when the switch changes`() {
        // Fails before: optIn() queued "false" on every call, including for a
        // user who had never opted out.
        val k = key()
        val sdk = init(k)
        sdk.optIn()
        sdk.optIn()
        pumpFor(800)
        assertEquals(emptyList<String>(), markerBodies())

        sdk.optOut()
        assertTrue(pumpUntil { markerBodies().size == 1 })
        sdk.optOut()
        pumpFor(500)
        restart()
        init(k, JoryioConfig(flushInterval = 60_000, optOut = true))
        pumpFor(800)
        assertEquals("unchanged across a restart: not re-sent", 1, markerBodies().size)
    }

    // ── Finding 11 / contract 25: decisions before initialize() ────────

    @Test
    fun `an optOut before initialize survives the process dying before init`() {
        // Fails before: the decision lived in memory only.
        Joryio.optOut()
        assertTrue("reflected before init", Joryio.isUserOptedOut())
        // The process dies: memory is gone, the disk copy is not.
        restart()
        val sdk = init()
        assertTrue(sdk.isUserOptedOut())
    }

    @Test
    fun `wipeData before initialize erases before the Session Start and the sync`() {
        // Fails before: there was no pre-init wipeData; the bridges replayed
        // it after init had already sent a Session Start and synced under the
        // identity being erased.
        val k = key()
        val first = init(k)
        val erased = first.getAnonymousId()
        // Let the first launch's own requests finish before counting.
        pumpFor(1_500)
        restart()
        api.seen.clear()

        Joryio.wipeData()
        val sdk = init(k)
        assertNotEquals(erased, sdk.getAnonymousId())
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Session Start") } })
        assertTrue(api.seen.filter { it.body.contains(erased) }.map { it.path }.toString(), api.seen.none { it.body.contains(erased) })
    }

    // ── Contract 32: no Session Start for a resumed session ────────────

    @Test
    fun `a cold start that resumes an unexpired session sends no Session Start`() {
        // Fails before: every launch inside the timeout sent another one.
        val k = key()
        val first = init(k)
        first.track("One")
        first.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("One") } })
        val session = first.getSessionId()
        restart()

        val second = init(k)
        assertEquals(session, second.getSessionId())
        second.track("Two")
        second.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Two") } })
        assertEquals(1, Regex("Session Start").findAll(bodiesOf("/v1/track/batch").joinToString()).count())
    }

    @Test
    fun `while consent is pending the Session Start stays ahead of its events`() {
        // Fails before: the Session Start was held back and tracked on
        // release, AFTER the events of its session.
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.PENDING))
        sdk.track("Held")
        assertTrue(pumpUntil { sdk.getQueueSize() >= 2 })
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("Held") })
        val all = bodiesOf("/v1/track/batch").joinToString()
        assertTrue(all, all.indexOf("Session Start") in 0 until all.indexOf("\"Held\""))
    }

    // ── Finding 1 / contract 28: subscription 404 ──────────────────────

    @Test
    fun `a subscription answered 404 flushes the events and is retried`() {
        // Fails before: 404 was a permanent refusal and the unsubscribe was
        // dropped on the first answer.
        var calls = 0
        api.answers["/v1/subscriptions/channel"] = {
            calls++
            if (calls == 1) TestServer.json("""{"message":"no profile"}""", code = 404) else TestServer.json("""{"success":true}""")
        }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, maxRetries = 0))
        sdk.track("First Event")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { calls == 1 })
        assertTrue("the events went first", pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("First Event") } })
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
    }

    // ── Finding 2 / contract 28: one refused write blocks nothing ──────

    @Test
    fun `a 403 write is dropped and the writes behind it go out`() {
        // Fails before: 403 was retried at the head of the ordered outbox
        // forever (strict identity), so the identify behind it never left.
        api.answers["/v1/alias/add"] = { TestServer.json("""{"error":"forbidden"}""", code = 403) }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, maxRetries = 0))
        sdk.addAlias("loyalty_id", "L-1")
        sdk.identify("user-403")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").any { it.contains("user-403") } })
    }

    // ── Finding 3 / contract 30: auth handler with no token ────────────

    @Test
    fun `the SDK-auth handler hears a 401 even when no token was ever set`() {
        // Fails before: with no token the interceptor was a pass-through and
        // the handler was never called.
        api.answers["/v1/identify"] = {
            TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
        }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, enableSdkAuthentication = true, maxRetries = 0))
        val heard = java.util.concurrent.CopyOnWriteArrayList<SdkAuthError>()
        sdk.setSdkAuthenticationErrorHandler { heard.add(it) }
        sdk.identify("needs-a-token")
        assertTrue(pumpUntil { heard.isNotEmpty() })
        assertEquals(SdkAuthErrorReason.MISSING, heard.first().reason)
        assertTrue(pumpUntil { heard.any { it.refreshExhausted } })
    }

    // ── Finding 5 / contract 31: per-key order ─────────────────────────

    @Test
    fun `a set made after an unset never lands before it`() {
        // Fails before: the unset waited in the outbox (failed, backing off)
        // while the later set went out on its own path - then the unset
        // landed last and deleted the new value.
        var unsetCalls = 0
        api.answers["/v1/attributes"] = {
            // The dispatcher records a request before answering it.
            val current = api.seen.last { it.path.endsWith("/v1/attributes") }
            if (current.body.contains("\"unset\"") && ++unsetCalls == 1) {
                TestServer.json("""{"error":"down"}""", code = 503)
            } else {
                TestServer.json("""{"success":true}""")
            }
        }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, maxRetries = 0))
        sdk.unsetAttribute("plan")
        assertTrue(pumpUntil { unsetCalls == 1 })
        sdk.setAttribute("plan", "pro")
        pumpFor(1_500)
        assertTrue("the set waits for the older unset", bodiesOf("/v1/attributes").none { it.contains("\"plan\":\"pro\"") })

        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"plan\":\"pro\"") } })
        val bodies = bodiesOf("/v1/attributes")
        assertTrue(bodies.toString(), bodies.lastIndexOf(bodies.last { it.contains("\"unset\"") }) < bodies.indexOfFirst { it.contains("\"plan\":\"pro\"") })
    }

    @Test
    fun `a set made before an increment is sent before it`() {
        val sdk = init()
        sdk.setAttribute("score", 5)
        sdk.incrementAttribute("score", 1)
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("increment") } })
        val bodies = bodiesOf("/v1/attributes")
        val set = bodies.indexOfFirst { it.contains("\"score\":5") }
        val inc = bodies.indexOfFirst { it.contains("increment") }
        assertTrue(bodies.toString(), set in 0 until inc)
    }

    // ── Finding 9: /v1/attributes 400 ──────────────────────────────────

    @Test
    fun `attributes the server refuses with 400 are dropped, not retried forever`() {
        var calls = 0
        api.answers["/v1/attributes"] = {
            calls++
            if (calls == 1) TestServer.json("""{"message":"invalid"}""", code = 400) else TestServer.json("""{"success":true}""")
        }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, maxRetries = 0))
        sdk.setAttribute("bad", "x")
        assertTrue(pumpUntil { calls == 1 })
        sdk.setAttribute("good", "y")
        assertTrue(pumpUntil { calls == 2 })
        val second = bodiesOf("/v1/attributes")[1]
        assertTrue(second, second.contains("\"good\":\"y\""))
        assertFalse("the refused value is not re-sent", second.contains("\"bad\""))
    }

    // ── Contract 29: pre-validation at track() ─────────────────────────

    @Test
    fun `events over the backend limits are dropped at track`() {
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, trackSessionStart = false))
        sdk.track("x".repeat(256))
        sdk.track("Too Many Keys", (0..200).associate { "k$it" to it })
        sdk.track("Too Deep", mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to mapOf("f" to 1)))))))
        sdk.track("Too Big", mapOf("blob" to "x".repeat(51_300)))
        sdk.track("Fine", mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to 1)))))
        assertTrue(pumpUntil { sdk.getQueueSize() == 1 })
        pumpFor(300)
        assertEquals(1, sdk.getQueueSize())
    }

    // ── Contract 26: stale sync answers ────────────────────────────────

    @Test
    fun `a sync answer that started before identify is discarded and a fresh sync follows`() {
        // Fails before: identify did not invalidate the in-flight sync, so the
        // previous identity's campaigns were shown to the new one.
        var syncs = 0
        api.answers["/v1/in-app/sync"] = {
            syncs++
            if (syncs == 1) {
                TestServer.json(
                    """{"success":true,"evaluatedAt":"x","campaigns":[{"id":"old","name":"Old","type":"modal"}]}""",
                ).setBodyDelay(1_000, TimeUnit.MILLISECONDS)
            } else {
                TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[]}""")
            }
        }
        val sdk = init()
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        assertTrue(pumpUntil { syncs == 1 })
        sdk.identify("someone-else")
        assertTrue("a follow-up sync runs", pumpUntil { syncs == 2 })
        pumpFor(500)
        assertEquals(emptyList<String>(), handed)
    }

    // ── Contract 27: one display per trigger burst ─────────────────────

    @Test
    fun `two triggers in one burst display the campaign once`() {
        // Fails before: the second trigger checked the impression history,
        // which is written only once a display is reported.
        api.answers["/v1/in-app/sync"] = {
            TestServer.json(
                """{"success":true,"evaluatedAt":"x","campaigns":[{"id":"t1","name":"T","type":"modal","frequencyCap":{"maxImpressions":5,"timeWindow":"day"},"triggers":[{"type":"event","event":"Buy"}]}]}""",
            )
        }
        val sdk = init()
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        sdk.syncInAppCampaigns()
        pumpFor(800)
        sdk.track("Buy")
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(listOf("t1"), handed)

        // Dismissed: the next trigger may show it again.
        sdk.trackInAppImpression("t1", "dismissed")
        pumpFor(300)
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(listOf("t1", "t1"), handed)
    }

    // ── Contract 34: host callbacks are wrapped ────────────────────────

    @Test
    fun `a throwing host callback never stops the SDK`() {
        val sdk = init()
        sdk.registerPushToken("tok") { throw IllegalStateException("host bug") }
        sdk.requestPushPermission(null) { throw IllegalStateException("host bug") }
        sdk.submitInAppForm(null, null) { throw IllegalStateException("host bug") }
        pumpFor(500)
        sdk.track("Still Working")
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Still Working") } })
    }
}
