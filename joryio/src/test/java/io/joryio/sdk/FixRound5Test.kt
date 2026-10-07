package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
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

/** Round 5 fixes - each test fails on 44379f8e1 (the scan-5 probes, kept as regression tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound5Test {
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
        app.getSharedPreferences("joryio_decisions", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        api.shutdown()
    }

    private fun cfg(sdkAuth: Boolean = false) = JoryioConfig(flushInterval = 60_000, maxRetries = 0, enableSdkAuthentication = sdkAuth, trackSessionStart = false)
    private fun bodiesOf(p: String) = api.seen.filter { it.path.endsWith(p) }.map { it.body }

    // P1: a NaN attribute value must not throw into the host, nor poison later attribute writes.
    @Test
    fun `P1 setAttribute NaN does not throw`() {
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        var threw: Throwable? = null
        try {
            sdk.setAttribute("avg", Double.NaN)
        } catch (t: Throwable) {
            threw = t
        }
        assertEquals(null, threw?.toString())
    }

    @Test
    fun `P1b after a NaN attribute, a later attribute still reaches the server`() {
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        try { sdk.setAttribute("avg", Double.NaN) } catch (t: Throwable) {}
        try { sdk.setAttribute("plan", "pro") } catch (t: Throwable) {}
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/attributes").any { it.contains("\"plan\":\"pro\"") } })
    }

    @Test
    fun `P1c after a NaN attribute, the in-app sync still runs`() {
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        api.answers["/v1/attributes"] = { TestServer.json("""{"error":"down"}""", code = 503) }
        try { sdk.setAttribute("avg", Double.NaN) } catch (t: Throwable) {}
        pumpFor(1500)
        val before = api.seen.count { it.path.endsWith("/v1/in-app/sync") }
        sdk.syncInAppCampaigns()
        assertTrue(pumpUntil(5_000) { api.seen.count { it.path.endsWith("/v1/in-app/sync") } > before })
    }

    @Test
    fun `P1c control without NaN the sync runs`() {
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        api.answers["/v1/attributes"] = { TestServer.json("""{"error":"down"}""", code = 503) }
        try { sdk.setAttribute("avg", 1.5) } catch (t: Throwable) {}
        pumpFor(1500)
        val before = api.seen.count { it.path.endsWith("/v1/in-app/sync") }
        sdk.syncInAppCampaigns()
        assertTrue(pumpUntil(5_000) { api.seen.count { it.path.endsWith("/v1/in-app/sync") } > before })
    }

    // P2: user A's queued unsubscribe must not be replaced by user B's subscribe (identify without reset).
    @Test
    fun `P2 another user's subscribe does not supersede a queued unsubscribe`() {
        var down = true
        api.answers["/v1/subscriptions/channel"] = {
            if (down) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        sdk.identify("user-a")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        sdk.identify("user-b")
        sdk.setSubscription("email", "subscribed")
        pumpFor(300)
        down = false
        api.seen.clear()
        runBlocking { sdk.drainOutbox(force = true) }
        pumpFor(1500)
        val sent = bodiesOf("/v1/subscriptions/channel")
        assertTrue("user-a unsubscribe lost; sent=$sent", sent.any { it.contains("user-a") && it.contains("unsubscribed") })
    }

    // P3: SDK auth - user A unsubscribes, logs out, app restarts before delivery: the withdrawal is dropped.
    @Test
    fun `P3 a logged-out user's unsubscribe survives a restart under SDK auth`() {
        val key = "jry_sdk_android_test_${UUID.randomUUID()}"
        var down = true
        api.answers["/v1/subscriptions/channel"] = {
            val last = api.seen.last { it.path.endsWith("/v1/subscriptions/channel") }
            when {
                down -> TestServer.json("""{"error":"down"}""", code = 503)
                last.body.contains("user-a") && last.headers["X-Joryio-Auth"] != "token-a" ->
                    TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
                else -> TestServer.json("""{"success":true}""")
            }
        }
        var sdk = Joryio.initialize(app, key, api.apiHost, cfg(sdkAuth = true))
        sdk.setSdkAuthenticationToken("token-a")
        sdk.identify("user-a")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        sdk.reset()
        pumpFor(300)
        Joryio.resetForTesting() // process death
        down = false
        api.seen.clear()
        sdk = Joryio.initialize(app, key, api.apiHost, cfg(sdkAuth = true))
        runBlocking { sdk.drainOutbox(force = true) }
        pumpFor(1500)
        val ok = api.seen.any { it.path.endsWith("/v1/subscriptions/channel") && it.headers["X-Joryio-Auth"] == "token-a" }
        assertTrue("withdrawal of user-a dropped after restart: ${bodiesOf("/v1/subscriptions/channel")}", ok)
    }

    // P4: initializeAsync from Application.onCreate races the first Activity. An Activity that resumed
    // before init finished is never known: no in-app display surface, and a cold-start push tap is not tracked.
    @Test
    fun `P4 an activity resumed before init is known to the SDK and its push tap is tracked`() {
        // What androidx.startup does at process start, before Application.onCreate.
        io.joryio.sdk.core.JoryioContextInitializer().create(app)
        val intent = android.content.Intent(app, android.app.Activity::class.java)
            .putExtra("joryio", "true").putExtra("trackingId", "trk-cold-1")
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java, intent).setup().get()
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        pumpFor(1500)
        val clicked = api.seen.any { it.path.contains("/track/push/click/trk-cold-1") }
        val known = sdk.currentActivityForTesting() === activity
        assertTrue("activity known=$known clickTracked=$clicked", known && clicked)
    }

    // P5: contract 36 extended - withdrawals pass a billing-suspended (402) org. An identify refused with
    // 402 sits at the head of the ordered outbox (402 never counts) and the unsubscribe behind it never goes.
    @Test
    fun `P5 an unsubscribe is delivered while an earlier write is refused with 402`() {
        api.answers["/v1/identify"] = { TestServer.json("""{"code":"SDK_INGESTION_SUSPENDED"}""", code = 402) }
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        sdk.identify("user-a")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        sdk.setSubscription("email", "unsubscribed")
        for (i in 0 until 5) { runBlocking { sdk.drainOutbox(force = true) }; pumpFor(300) }
        assertTrue("identify attempts=${bodiesOf("/v1/identify").size}, unsubscribes=${bodiesOf("/v1/subscriptions/channel").size}",
            bodiesOf("/v1/subscriptions/channel").isNotEmpty())
    }

    // P6: contract 32 - a new session sends exactly one Session Start. An in-app sync (silent push nudge,
    // attribute-watch, host call) after the timeout rolls the session over silently via getSessionId().
    @Test
    fun `P6 a session rolled over by a sync still gets its Session Start`() {
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost,
            JoryioConfig(flushInterval = 60_000, maxRetries = 0, sessionTimeout = 1_000, trackSessionStart = true))
        pumpFor(500)
        val firstSession = sdk.getSessionInfo().sessionId
        Thread.sleep(1_300)
        sdk.syncInAppCampaigns()
        assertTrue(pumpUntil { api.seen.count { it.path.endsWith("/v1/in-app/sync") } >= 2 })
        sdk.track("After Idle")
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("After Idle") } })
        val all = bodiesOf("/v1/track/batch").joinToString("\n")
        val starts = Regex("\"type\":\"Session Start\"").findAll(all).count()
        assertTrue("session ${firstSession} -> ${sdk.getSessionInfo().sessionId}; Session Starts sent=$starts", starts >= 2)
    }

    // ── Round 5 additions: each fails on 44379f8e1 ─────────────────────────

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun jwt(sub: String): String {
        val enc = { j: String ->
            android.util.Base64.encodeToString(
                j.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
        }
        return enc("""{"alg":"HS256"}""") + "." + enc("""{"sub":"$sub"}""") + ".sig"
    }

    @Test
    fun `a NaN event property drops that key, the event is still tracked`() {
        // Contract 49. Fails on 44379f8e1: the whole event was dropped as
        // "cannot be serialized".
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.track("Scored", mapOf("ratio" to Double.NaN, "level" to 3, "nested" to mapOf("x" to Double.POSITIVE_INFINITY, "y" to 1)))
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/track/batch").any { it.contains("Scored") } })
        val body = bodiesOf("/v1/track/batch").first { it.contains("Scored") }
        assertTrue(body, body.contains("\"level\":3") && body.contains("\"y\":1"))
        assertTrue(body, !body.contains("ratio") && !body.contains("\"x\""))
    }

    @Test
    fun `identify of another user without reset sends the previous user's attributes under them, with their token`() {
        // Contract 53. Fails on 44379f8e1: A's unsent attributes went out
        // under B, and A's token was not retired.
        var down = true
        api.answers["/v1/attributes"] = {
            if (down) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        sdk.setSdkAuthenticationToken(jwt("user-a"))
        sdk.identify("user-a")
        sdk.setAttribute("plan", "a-plan")
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/attributes").isNotEmpty() })
        sdk.setSdkAuthenticationToken(jwt("user-b"))
        sdk.identify("user-b")
        down = false
        api.seen.clear()
        // Not runBlocking on the main thread: an attribute send in flight
        // holds the attribute lock and resumes on the main thread.
        assertTrue(pumpUntil(15_000) { api.seen.any { it.path.endsWith("/v1/attributes") && it.body.contains("a-plan") } })
        pumpFor(500)
        val sent = api.seen.filter { it.path.endsWith("/v1/attributes") && it.body.contains("a-plan") }
        assertTrue(sent.all { it.body.contains("\"userId\":\"user-a\"") && !it.body.contains("user-b") })
        assertTrue(sent.all { it.headers["X-Joryio-Auth"] == jwt("user-a") })
    }

    @Test
    fun `identify while opted out is held locally and only carried by withdrawals`() {
        // Owner decision D-b. Fails on 44379f8e1: the identify was dropped, so
        // the unsubscribe went out with only the anonymous id.
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.optOut()
        sdk.identify("user-x")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        assertTrue(bodiesOf("/v1/subscriptions/channel").first().contains("\"userId\":\"user-x\""))
        assertEquals("no identify while opted out", 0, bodiesOf("/v1/identify").size)
        assertEquals(null, sdk.getUserId())
        sdk.optIn()
        assertTrue("the real identify runs on opt-in", pumpUntil(6_000) { bodiesOf("/v1/identify").any { it.contains("user-x") } })
    }

    @Test
    fun `a full pre-init buffer drops data calls before a withdrawal`() {
        // Contract 51. Fails on 44379f8e1: the oldest call - the unsubscribe -
        // was dropped first.
        Joryio.setSubscription("email", "unsubscribed")
        repeat(Joryio.MAX_PENDING_ACTIONS) { Joryio.trackScreen("S$it") }
        Joryio.initialize(app, key(), api.apiHost, cfg())
        assertTrue(pumpUntil(10_000) { bodiesOf("/v1/subscriptions/channel").any { it.contains("unsubscribed") } })
    }

    @Test
    fun `withdrawals made before a pre-init wipe keep the erased identity`() {
        // Integration (Unity scan 5, N4). Fails before: the pre-init wipe ran
        // inside initialize() before the buffer replayed, so the unsubscribe
        // made before it went out under the NEW anonymous id - and a track()
        // buffered before the wipe survived it.
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg())
        val erased = first.getAnonymousId()
        pumpFor(1_500)
        Joryio.resetForTesting()
        api.seen.clear()

        Joryio.setSubscription("email", "unsubscribed")
        Joryio.track("Before The Wipe")
        Joryio.wipeData()
        Joryio.setSubscription("sms", "unsubscribed")
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        assertNotEquals(erased, sdk.getAnonymousId())
        assertTrue(api.seen.map { it.path + " " + it.body }.toString(), pumpUntil(10_000) { bodiesOf("/v1/subscriptions/channel").size >= 2 })
        val sent = bodiesOf("/v1/subscriptions/channel")
        assertTrue(sent.toString(), sent.first { it.contains("\"email\"") }.contains(erased))
        assertFalse(sent.toString(), sent.first { it.contains("\"sms\"") }.contains(erased))
        sdk.flush()
        pumpFor(1_500)
        assertTrue("a track() buffered before the wipe survived it", bodiesOf("/v1/track/batch").none { it.contains("Before The Wipe") })
    }

    @Test
    fun `a key minted for another platform is refused`() {
        // Contract 60. Fails on 44379f8e1: any jry_sdk_ key started the SDK.
        val sdk = Joryio.initialize(app, "jry_sdk_ios_${UUID.randomUUID()}", api.apiHost, cfg())
        sdk.track("Should Not Send")
        sdk.flush()
        pumpFor(1_000)
        assertEquals(0, bodiesOf("/v1/track/batch").size)
    }

    @Test
    fun `a null line in a purchase is skipped, the order is tracked`() {
        // Fails on 44379f8e1: a NullPointerException into the host app.
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        val items = java.util.ArrayList<io.joryio.sdk.ecommerce.EcommerceCartItem?>()
        items.add(io.joryio.sdk.ecommerce.EcommerceCartItem(productId = "p1", name = "P", price = 5.0, quantity = 2))
        items.add(null)
        @Suppress("UNCHECKED_CAST")
        val order = io.joryio.sdk.ecommerce.EcommerceOrder(
            orderId = "o1", items = items as List<io.joryio.sdk.ecommerce.EcommerceCartItem>, value = 10.0,
        )
        sdk.ecommerce().purchase(order)
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/track/batch").any { it.contains("Order Completed") } })
        val body = bodiesOf("/v1/track/batch").first { it.contains("Order Completed") }
        assertTrue(body, body.contains("\"item_count\":2") && body.contains("\"p1\""))
    }

    @Test
    fun `the opt-out marker back to false waits for consent`() {
        // NICE 1. Fails on 44379f8e1: `tracking_opted_out:false` was sent as a
        // withdrawal while consent was denied.
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.optOut()
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/attributes").any { it.contains("\"tracking_opted_out\":true") } })
        pumpFor(500)
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.optIn()
        pumpFor(1_500)
        assertEquals(0, bodiesOf("/v1/attributes").count { it.contains("\"tracking_opted_out\":false") })
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/attributes").any { it.contains("\"tracking_opted_out\":false") } })
    }

    @Test
    fun `an in-app campaign triggered off the main thread reaches the host on the main thread, unknown type as CUSTOM`() {
        // NICE 7 and 10. Fails on 44379f8e1: the callback ran on the
        // tracking thread, and an unknown type arrived as null.
        api.answers["/v1/in-app/sync"] = {
            TestServer.json(
                """{"success":true,"evaluatedAt":"x","campaigns":[{"id":"t1","name":"T","type":"carousel","frequencyCap":{"maxImpressions":5,"timeWindow":"day"},"triggers":[{"type":"event","event":"Buy"}]}]}""",
            )
        }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        val seenOn = mutableListOf<Boolean>()
        val types = mutableListOf<Any?>()
        sdk.setInAppMessageCallback({ c: io.joryio.sdk.models.InAppCampaign ->
            seenOn.add(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            types.add(c.type)
        })
        sdk.syncInAppCampaigns()
        pumpFor(800)
        val t = Thread { sdk.track("Buy") }
        t.start()
        t.join()
        assertTrue(pumpUntil(5_000) { seenOn.isNotEmpty() })
        assertEquals(listOf(true), seenOn)
        assertEquals(listOf<Any?>(io.joryio.sdk.models.MessageType.CUSTOM), types)
    }

    @Test
    fun `an attribute flush that failed is retried on its own`() {
        // NICE 3. Fails on 44379f8e1: it waited for the next setAttributes,
        // foreground or sync.
        var calls = 0
        api.answers["/v1/attributes"] = {
            calls++
            if (calls == 1) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.setAttribute("plan", "pro")
        assertTrue(pumpUntil(15_000) { calls >= 2 })
    }

    @Test
    fun `a throwing initializeAsync onReady does not crash the app`() {
        // Contract 56. Fails on 44379f8e1: the exception was rethrown on the
        // main looper.
        Joryio.initializeAsync(app, key(), api.apiHost, cfg()) { throw IllegalStateException("host bug") }
        var thrown: Throwable? = null
        try {
            pumpUntil(5_000) { Joryio.isInitialized() && false }
        } catch (t: Throwable) {
            thrown = t
        }
        assertEquals(null, thrown?.toString())
    }

    @Test
    fun `reading the session id never starts or extends a session`() {
        // Contract 54 (Unity S1). Fails on 44379f8e1: every read refreshed
        // lastActivity, so a polled session never expired.
        val sdk = Joryio.initialize(app, key(), api.apiHost,
            JoryioConfig(flushInterval = 60_000, maxRetries = 0, sessionTimeout = 1_000, trackSessionStart = false))
        val first = sdk.getSessionId()
        repeat(6) { Thread.sleep(250); sdk.getSessionId() }
        sdk.track("Later")
        assertNotEquals(first, sdk.getSessionId())
    }
}
