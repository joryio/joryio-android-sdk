package io.joryio.sdk

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.SecurePrefs
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.models.InAppCampaign
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.push.JoryioNotificationTrampolineActivity
import io.joryio.sdk.push.JoryioPermissionActivity
import io.joryio.sdk.push.PushPayloadKeys
import io.joryio.sdk.push.PushRegistrationResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * SDK 1.3.0 fix round 2 (scan 2 Android findings + contract addendum 13-23).
 * Each test names the finding and fails on the code before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound2Test {

    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun init(k: String = key(), config: JoryioConfig = JoryioConfig(flushInterval = 60_000)): Joryio =
        Joryio.initialize(app, k, api.apiHost, config)

    /** A process restart, as far as the SDK can tell: no instance, same files on disk. */
    private fun restart() = Joryio.resetForTesting()

    private fun bodiesOf(pathSuffix: String) = api.seen.filter { it.path.endsWith(pathSuffix) }.map { it.body }

    // ── Finding 1: consent decided before initialize() ──────────────────

    @Test
    fun `an optOut made before init is applied by init itself - no in-app sync, no Session Start`() {
        // Fails before: the static optOut() was buffered and replayed AFTER
        // init. Initialised off the main thread (as the React Native bridge
        // does), the in-app sync init launched ran before the replay, and the
        // opted-out user was synced anyway.
        Joryio.optOut()
        val k = key()
        var sdk: Joryio? = null
        val t = Thread { sdk = init(k) }
        t.start()
        t.join()
        pumpFor(800)
        assertTrue(sdk!!.isUserOptedOut())
        // Only the opt-out record (contract item 33) - no sync, no Session Start.
        assertTrue(api.paths().toString(), api.seen.all { it.path.endsWith("/v1/attributes") && it.body.contains("tracking_opted_out") })

        // ... and persisted from there on.
        restart()
        assertTrue(init(k).isUserOptedOut())
    }

    @Test
    fun `updateConsent PENDING before init holds everything from the first moment`() {
        Joryio.updateConsent(TrackingConsent.PENDING)
        val sdk = init()
        sdk.track("Held")
        pumpFor(800)
        assertEquals(emptyList<String>(), api.paths())
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(api.paths().toString(), pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Held") } })
    }

    // ── Finding 5: DENIED / optOut at init ──────────────────────────────

    @Test
    fun `DENIED at init clears what an earlier launch left queued and persists the denial`() {
        // Fails before: DENIED held for that launch only - the Room queue and
        // the pending attribute writes from the launch before stayed on the
        // device, and the next default launch tracked again. Consent model A
        // (contract item 24): the DENIAL is persisted - as consent, not as an
        // opt-out.
        val k = key()
        val first = init(k, JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.PENDING))
        first.track("Before")
        first.setAttribute("plan", "pro")
        assertTrue(pumpUntil { first.getQueueSize() >= 1 })
        restart()

        val denied = init(k, JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.DENIED))
        assertFalse("consent is not the opt-out switch", denied.isUserOptedOut())
        assertEquals(TrackingConsent.DENIED, denied.getTrackingConsent())
        assertTrue(pumpUntil { denied.getQueueSize() == 0 })
        restart()

        val later = init(k)
        assertEquals("the denial outlived the launch that set it", TrackingConsent.DENIED, later.getTrackingConsent())
        later.track("After")
        pumpFor(500)
        assertEquals(0, later.getQueueSize())
        assertTrue(bodiesOf("/v1/attributes").none { it.contains("plan") })
    }

    @Test
    fun `null config values mean the defaults`() {
        // Contract item 19: a Java caller passing null got a crash inside the
        // JoryioConfig constructor.
        val sdk = init(config = JoryioConfig(trackingConsent = null, notificationChannelName = null))
        assertFalse(sdk.isUserOptedOut())
        sdk.track("Works")
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Works") } })
    }

    // ── Finding 2 / contract 21: wipeData ──────────────────────────────

    @Test
    fun `wipeData keeps the opt-out`() {
        // Fails before: StorageManager.clearAllData() erased opted_out, so a
        // user who opted out and asked for erasure came back opted IN.
        val k = key()
        val sdk = init(k)
        sdk.optOut()
        sdk.wipeData()
        assertTrue(sdk.isUserOptedOut())
        restart()
        assertTrue(init(k).isUserOptedOut())
    }

    @Test
    fun `wipeData erases identity, session, device id and token, and unregisters the token on the server`() {
        val sdk = init()
        var registered: PushRegistrationResult? = null
        sdk.registerPushToken("tok-wipe") { registered = it }
        assertTrue(pumpUntil { registered != null })
        assertEquals(true, registered!!.success)
        val anon = sdk.getAnonymousId()
        val session = sdk.getSessionId()

        sdk.wipeData()
        assertTrue(pumpUntil { bodiesOf("/v1/push/unregister").any { it.contains("tok-wipe") } })
        val unregister = bodiesOf("/v1/push/unregister").first()
        assertTrue("sent under the erased identity", unregister.contains(anon))
        assertNull(sdk.getDeviceToken())
        assertNotEquals(anon, sdk.getAnonymousId())
        assertNotEquals(session, sdk.getSessionId())

        // Same on every SDK: after an erasure the device stays off push until
        // the app asks again. FCM handing over a token is not the app asking.
        val registersBefore = bodiesOf("/v1/push/register").size
        var auto: PushRegistrationResult? = null
        sdk.pushManager!!.registerToken("tok-after-wipe") { auto = it }
        assertTrue(pumpUntil { auto != null })
        assertEquals(false, auto!!.success)
        assertEquals(registersBefore, bodiesOf("/v1/push/register").size)
    }

    // ── Finding 4: foreground session timeout ──────────────────────────

    @Test
    fun `a session that times out in the foreground starts again with a Session Start`() {
        // Fails before: track() rolled the session id over silently; only
        // ON_START emitted Session Start, so a long foreground idle made a new
        // session with no start.
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, sessionTimeout = 1_500))
        sdk.track("A")
        Thread.sleep(1_700)
        sdk.track("B")
        assertTrue(pumpUntil { sdk.getQueueSize() >= 4 })
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("\"B\"") })
        val all = bodiesOf("/v1/track/batch").joinToString()
        assertEquals(2, Regex("Session Start").findAll(all).count())
        assertTrue("the new Session Start precedes B", all.lastIndexOf("Session Start") < all.indexOf("\"B\""))
    }

    // ── Contract 15: success:false is a failure for every write ────────

    @Test
    fun `identify answered 200 success false is kept and retried`() {
        // Fails before: identify was fire-and-forget - any failure, including
        // a 200 that said success:false, lost it for good.
        var calls = 0
        api.answers["/v1/identify"] = {
            calls++
            if (calls == 1) TestServer.json("""{"success":false,"message":"db"}""") else TestServer.json("""{"success":true}""")
        }
        val sdk = init()
        sdk.identify("user-1")
        assertTrue(pumpUntil { calls == 1 })
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
        assertEquals(2, bodiesOf("/v1/identify").count { it.contains("user-1") })
    }

    @Test
    fun `addAlias, increments and subscriptions survive a failure and a restart`() {
        api.answers["/v1/alias/add"] = { TestServer.json("""{"error":"down"}""", code = 500) }
        val k = key()
        val sdk = init(k, JoryioConfig(flushInterval = 60_000, maxRetries = 0))
        sdk.addAlias("loyalty_id", "L-9")
        sdk.incrementAttribute("logins", 1)
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { bodiesOf("/v1/alias/add").isNotEmpty() })
        pumpFor(300)
        restart()

        api.answers.remove("/v1/alias/add")
        init(k)
        assertTrue(pumpUntil { bodiesOf("/v1/alias/add").size >= 2 })
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"increment\":{\"logins\":1}") } })
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").any { it.contains("unsubscribed") } })
        // In the order they were made.
        val order = api.paths().filter { it.endsWith("/v1/alias/add") || it.endsWith("/v1/subscriptions/channel") }
        assertTrue(order.lastIndexOf("/api/v1/alias/add") < order.lastIndexOf("/api/v1/subscriptions/channel"))
    }

    @Test
    fun `a write the server refuses outright is dropped and does not block the rest`() {
        api.answers["/v1/alias/add"] = { TestServer.json("""{"error":"bad"}""", code = 400) }
        val sdk = init()
        sdk.addAlias("Bad Label", "x")
        sdk.identify("user-2")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
    }

    // ── Contract 14: the opt-out marker key ────────────────────────────

    @Test
    fun `the opt-out record uses tracking_opted_out with no dollar prefix`() {
        // Fails before: "$tracking_opted_out" - the backend drops $-keys, so
        // the record never reached the profile.
        val sdk = init()
        sdk.optOut()
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("tracking_opted_out") } })
        val body = bodiesOf("/v1/attributes").first { it.contains("tracking_opted_out") }
        assertTrue(body, body.contains("\"tracking_opted_out\":true"))
        assertFalse(body, body.contains("\$tracking_opted_out"))
        sdk.optIn()
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"tracking_opted_out\":false") } })
    }

    // ── Contract 19: a null attribute value is an unset ────────────────

    @Test
    fun `setAttribute with null unsets the attribute`() {
        // Fails before: the null was dropped by the JSON encoder and the call
        // sent nothing that removed anything.
        val sdk = init()
        sdk.setAttribute("plan", null)
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"unset\":[\"plan\"]") } })
    }

    @Test
    fun `ecommerce calls tolerate nulls from Java callers`() {
        val sdk = init()
        val shop = sdk.ecommerce()
        shop.purchase(null)
        shop.viewProduct(null)
        shop.orderRefunded(null)
        shop.search(null)
        pumpFor(200)
    }

    // ── Contract 16 (web finding 2, checked on Android) ────────────────

    @Test
    fun `an identify right after reset uses the new anonymous id`() {
        val sdk = init()
        sdk.identify("A")
        sdk.reset()
        val fresh = sdk.getAnonymousId()
        sdk.identify("B")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").any { it.contains("\"userId\":\"B\"") } })
        val b = bodiesOf("/v1/identify").first { it.contains("\"userId\":\"B\"") }
        assertTrue(b, b.contains("\"anonymousId\":\"$fresh\""))
        assertEquals("B", sdk.getUserId())
    }

    // ── Finding 11: attribute flush vs reset ───────────────────────────

    @Test
    fun `an attribute ack from before reset does not swallow the next person's write`() {
        // Fails before: the in-flight flush for person A was acknowledged
        // against the pending set AFTER reset, and B's identical value was
        // cleared without ever being sent under B's anonymous id.
        api.answers["/v1/attributes"] = {
            TestServer.json("""{"success":true}""").setBodyDelay(1_500, TimeUnit.MILLISECONDS)
        }
        val sdk = init()
        sdk.setAttribute("plan", "pro")
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").isNotEmpty() })
        sdk.reset()
        val b = sdk.getAnonymousId()
        sdk.setAttribute("plan", "pro")
        assertTrue(pumpUntil(10_000) { bodiesOf("/v1/attributes").any { it.contains("\"plan\":\"pro\"") && it.contains(b) } })
    }

    // ── Finding 6 / contract 22: durable unregisterPush ────────────────

    @Test
    fun `unregisterPush survives a failure and a restart, and automatic paths do not re-register`() {
        // Fails before: one attempt; on failure the token and its
        // "registered" marker stayed, and the next launch never unregistered.
        val k = key()
        val sdk = init(k)
        var r: PushRegistrationResult? = null
        sdk.registerPushToken("tok-1") { r = it }
        assertTrue(pumpUntil { r != null })
        api.answers["/v1/push/unregister"] = { TestServer.json("""{"error":"x"}""", code = 503) }
        sdk.unregisterPush()
        assertTrue(pumpUntil(15_000) { bodiesOf("/v1/push/unregister").isNotEmpty() })
        assertFalse(sdk.isPushEnabled())
        pumpFor(300)
        restart()

        api.answers.remove("/v1/push/unregister")
        val before = bodiesOf("/v1/push/unregister").size
        val again = init(k)
        assertTrue(pumpUntil { bodiesOf("/v1/push/unregister").size > before })
        // FCM handing the SDK a token (onNewToken) is NOT the app turning push back on.
        var auto: PushRegistrationResult? = null
        again.pushManager!!.registerToken("tok-1") { auto = it }
        assertTrue(pumpUntil { auto != null })
        assertEquals(false, auto!!.success)
        assertEquals(1, bodiesOf("/v1/push/register").size)

        // The app turning it back on does.
        var explicit: PushRegistrationResult? = null
        again.registerPushToken("tok-1") { explicit = it }
        assertTrue(pumpUntil { explicit != null })
        assertEquals(true, explicit!!.success)
        assertEquals(2, bodiesOf("/v1/push/register").size)
    }

    // ── Finding 7: a tap on an SDK-drawn notification ──────────────────

    @Test
    fun `a tap through the trampoline is reported once`() {
        // Fails before: PushOpenTracker counted the trampoline's own launch,
        // and the trampoline reported the same tap again.
        init()
        val intent = Intent(app, JoryioNotificationTrampolineActivity::class.java)
            .putExtra(PushPayloadKeys.TRACKING_ID, "tap-1")
            .putExtra(PushPayloadKeys.MARKER, PushPayloadKeys.MARKER_VALUE)
        Robolectric.buildActivity(JoryioNotificationTrampolineActivity::class.java, intent).create()
        assertTrue(pumpUntil { api.paths().contains("/track/push/click/tap-1") })
        pumpFor(500)
        assertEquals(1, api.paths().count { it == "/track/push/click/tap-1" })
    }

    // ── Finding 8: the permission Activity is not an in-app host ───────

    @Test
    fun `the SDK's permission Activity never becomes the in-app host`() {
        // Fails before: it resumed on top of the app, became currentActivity,
        // and a message drawn on it vanished when it finished.
        val sdk = init()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(host, sdk.currentActivityForTesting())
        Robolectric.buildActivity(JoryioPermissionActivity::class.java).create().start().resume()
        assertTrue(sdk.currentActivityForTesting() !is JoryioPermissionActivity)
    }

    // ── Contract 17: budget spent only by "displayed" ──────────────────

    @Test
    fun `handing a message to the host spends no budget - only a displayed report does`() {
        // Fails before: the handoff stamped the cross-campaign gap, so a
        // message the host declined still suppressed the next one.
        api.answers["/v1/in-app/sync"] = {
            TestServer.json(
                """{"success":true,"delayBetweenCampaigns":3600,"evaluatedAt":"x","campaigns":[
                {"id":"c1","name":"One","type":"modal","priority":2,"frequencyCap":{"maxImpressions":5,"timeWindow":"day"}},
                {"id":"c2","name":"Two","type":"modal","priority":1}]}""",
            )
        }
        val sdk = init(config = JoryioConfig(flushInterval = 60_000, enableDebug = true))
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        // The sync initialize() started hands the first message over.
        assertTrue(pumpUntil { handed.isNotEmpty() })
        pumpFor(500)
        val afterSync = handed.size
        sdk.evaluateInAppCampaigns()
        assertTrue("not reported, so not spent", pumpUntil { handed.size == afterSync + 1 })

        sdk.trackInAppImpression(handed.last(), "displayed")
        pumpFor(500)
        sdk.evaluateInAppCampaigns()
        pumpFor(300)
        assertEquals("a reported display spends the gap", afterSync + 1, handed.size)
    }

    // ── Contract 18: clearInAppMessageCallback ─────────────────────────

    @Test
    fun `clearInAppMessageCallback hands rendering back to the SDK presenter`() {
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[{"id":"c9","name":"Nine","type":"modal"}]}""")
        }
        val sdk = init()
        val drawn = mutableListOf<String>()
        sdk.setInAppPresenter(object : InAppMessagePresenter {
            override fun present(activity: Activity, campaign: InAppCampaign, onEvent: (String, Map<String, Any?>) -> Unit): Boolean {
                drawn.add(campaign.id)
                return true
            }
        })
        Robolectric.buildActivity(Activity::class.java).setup()
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        sdk.clearInAppMessageCallback()
        sdk.syncInAppCampaigns()
        assertTrue(pumpUntil { drawn.contains("c9") })
        assertTrue(handed.isEmpty())
    }

    // ── Contract 20: pre-init buffer cap ───────────────────────────────

    @Test
    fun `the pre-init buffer keeps the newest 1000 calls`() {
        // Was 200 on Android while JS and iOS kept 1000.
        val replayed = mutableListOf<Int>()
        repeat(1_005) { i -> Joryio.whenReady { replayed.add(i) } }
        init()
        assertTrue(pumpUntil { replayed.size == 1_000 })
        pumpFor(200)
        assertEquals(1_000, replayed.size)
        assertEquals("the oldest are the ones dropped", 5, replayed.first())
    }

    // ── Finding 9: Retry-After ─────────────────────────────────────────

    @Test
    fun `Retry-After is read in both forms and on any status, without the 30 second cap`() {
        assertEquals(120_000L, NetworkClient.parseRetryAfterMs("120"))
        val now = 1_700_000_000_000L
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(now + 300_000))
        assertEquals(300_000L, NetworkClient.parseRetryAfterMs(date, now))
        assertNull(NetworkClient.parseRetryAfterMs("soon"))
    }

    @Test
    fun `a 503 asking for two minutes is handed to the queue, not slept or retried at 30s`() = runBlocking {
        // Fails before: only 429 was read, and the wait was clamped to 30 s.
        api.answers["/v1/track/batch"] = {
            MockResponse().setResponseCode(503).setHeader("Retry-After", "120").setBody("{}")
        }
        val client = NetworkClient(context = app, sdkKey = key(), apiHost = api.apiHost, logger = Logger(false, LogLevel.ERROR), maxRetries = 3)
        val result = client.trackBatch(io.joryio.sdk.models.EventBatch(events = emptyList(), sentAt = java.util.Date()))
        assertTrue(result is NetworkResult.Error)
        assertEquals(120_000L, (result as NetworkResult.Error).retryAfterMs)
        assertEquals(1, api.seen.count { it.path.endsWith("/v1/track/batch") })
    }

    // ── Finding 13: the opt-out survives losing the encrypted store ────

    @Test
    fun `the opt-out survives the encrypted store coming back empty`() {
        // Fails before: the flag lived only in the encrypted file; a keystore
        // fallback or a discarded undecryptable file read as "not opted out".
        val k = key()
        val logger = Logger(false, LogLevel.ERROR)
        StorageManager(app, k, logger).setOptedOut(true)
        SecurePrefs.get(app, "joryio_${k.hashCode()}", logger).edit().clear().commit()
        assertTrue(StorageManager(app, k, logger).getOptedOut())
    }
}
