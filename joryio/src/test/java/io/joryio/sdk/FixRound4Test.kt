package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.ecommerce.EcommerceCartItem
import io.joryio.sdk.ecommerce.EcommerceOrder
import io.joryio.sdk.ecommerce.EcommerceTracker
import io.joryio.sdk.models.InAppCampaign
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SDK 1.3.0 fix round 4: scan 4 Android findings S1, S2, M1-M6 and contract
 * addendum v4 (items 36-46). Each test names what it pins and fails on the
 * code before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound4Test {

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
        clearStoredDecisions()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        clearStoredDecisions()
        api.shutdown()
    }

    private fun clearStoredDecisions() {
        app.getSharedPreferences("joryio_decisions", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun config(
        consent: TrackingConsent? = null,
        sdkAuth: Boolean = false,
        optOut: Boolean = false,
    ) = JoryioConfig(
        flushInterval = 60_000,
        maxRetries = 0,
        trackingConsent = consent,
        enableSdkAuthentication = sdkAuth,
        optOut = optOut,
    )

    private fun init(k: String = key(), config: JoryioConfig = config()): Joryio =
        Joryio.initialize(app, k, api.apiHost, config)

    private fun restart() = Joryio.resetForTesting()

    private fun bodiesOf(pathSuffix: String) = api.seen.filter { it.path.endsWith(pathSuffix) }.map { it.body }

    private fun authError(reason: String) =
        TestServer.json("""{"error":"sdk_authentication_error","reason":"$reason"}""", code = 401)

    private fun lastBody(pathSuffix: String) = api.seen.last { it.path.endsWith(pathSuffix) }.body

    // ── S1 / contract 39: a previous user's leftovers, any 401 reason ──

    @Test
    fun `a logged-out user's batch refused as expired is dropped and the next user's events go out`() {
        // Fails before: only sub_mismatch / missing dropped; `expired` kept
        // A's batch at the head of the queue and B's events never left.
        api.answers["/v1/track/batch"] = {
            if (lastBody("/v1/track/batch").contains("\"userId\":\"user-a\"")) authError("expired")
            else TestServer.json("""{"success":true,"processed":1}""")
        }
        val sdk = init(config = config(sdkAuth = true))
        sdk.identify("user-a")
        sdk.track("A Event")
        sdk.reset()
        sdk.identify("user-b")
        sdk.track("B Event")
        assertTrue(pumpUntil { sdk.getQueueSize() >= 3 })
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("B Event") } })
    }

    @Test
    fun `a logged-out user's write refused as invalid is dropped and does not block the outbox`() {
        // Fails before: the alias of user-a was retried at the head of the
        // ordered outbox (401 `invalid` counted, but up to ten times).
        api.answers["/v1/alias/add"] = { authError("invalid") }
        val sdk = init(config = config(sdkAuth = true))
        sdk.identify("user-a")
        sdk.addAlias("loyalty_id", "L-1")
        assertTrue(pumpUntil { bodiesOf("/v1/alias/add").isNotEmpty() })
        sdk.reset()
        sdk.identify("user-b")
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { bodiesOf("/v1/identify").any { it.contains("user-b") } })
    }

    @Test
    fun `reset clears the SDK authentication token`() {
        // Fails before: the outgoing user's JWT went out on the next
        // person's requests.
        val sdk = init(config = config(sdkAuth = true))
        sdk.setSdkAuthenticationToken("token-of-a")
        sdk.identify("user-a")
        assertTrue(pumpUntil { api.seen.any { it.headers["X-Joryio-Auth"] == "token-of-a" } })
        sdk.reset()
        api.seen.clear()
        sdk.track("After Logout")
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("After Logout") } })
        // Only user-a's own leftovers may still carry user-a's token.
        assertTrue(api.seen.filter { !it.body.contains("\"user-a\"") }.none { it.headers["X-Joryio-Auth"] != null })
    }

    @Test
    fun `a logout sends the outgoing user's last events with their own token`() {
        // Round 4 integration. Fails before: reset() cleared the token, so
        // user-a's still-queued batch went out with none, got a 401 and was
        // dropped - every normal logout under SDK Authentication lost the
        // user's last events.
        api.answers["/v1/track/batch"] = {
            val last = api.seen.last { it.path.endsWith("/v1/track/batch") }
            if (last.body.contains("\"userId\":\"user-a\"") && last.headers["X-Joryio-Auth"] != "token-of-a") {
                authError("missing")
            } else {
                TestServer.json("""{"success":true,"processed":1}""")
            }
        }
        val sdk = init(config = config(sdkAuth = true))
        sdk.setSdkAuthenticationToken("token-of-a")
        sdk.identify("user-a")
        sdk.track("A Last Purchase")
        sdk.reset()
        sdk.track("Anonymous Browsing")
        assertTrue(pumpUntil { sdk.getQueueSize() >= 2 })
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Anonymous Browsing") } })
        sdk.flush()
        assertTrue(pumpUntil { api.seen.any { it.body.contains("A Last Purchase") && it.headers["X-Joryio-Auth"] == "token-of-a" } })
        val anonymous = api.seen.first { it.body.contains("Anonymous Browsing") }
        assertNull(anonymous.headers["X-Joryio-Auth"])
        assertTrue(pumpUntil { sdk.getQueueSize() == 0 })
    }

    @Test
    fun `the request subject is read from the same fields the server binds the token to`() {
        val of = io.joryio.sdk.network.NetworkClient.Companion::subjectUserIdOf
        assertEquals("a", of("""{"userId":"a","anonymousId":"x"}"""))
        assertEquals("a", of("""{"externalId":"a"}"""))
        assertEquals("a", of("""{"identify":{"userId":"a"},"events":[]}"""))
        assertEquals("a", of("""{"events":[{"anonymousId":"x"},{"userId":"a"}]}"""))
        assertNull(of("""{"events":[{"anonymousId":"x"}]}"""))
        assertNull(of("""{"userId":""}"""))
    }

    // ── M5 / contract 39: the handler hears the current user only ──────

    @Test
    fun `the auth error handler is not called for a logged-out user's request`() {
        // Fails before: user-a's leftover batch made the handler fire after
        // user-b signed in - a host that signs out on auth errors signed out
        // the wrong person.
        api.answers["/v1/track/batch"] = {
            if (lastBody("/v1/track/batch").contains("\"userId\":\"user-a\"")) authError("expired")
            else TestServer.json("""{"success":true,"processed":1}""")
        }
        val sdk = init(config = config(sdkAuth = true))
        val heard = CopyOnWriteArrayList<SdkAuthError>()
        sdk.setSdkAuthenticationErrorHandler { heard.add(it) }
        sdk.identify("user-a")
        sdk.track("A Event")
        sdk.reset()
        sdk.identify("user-b")
        sdk.track("B Event")
        assertTrue(pumpUntil { sdk.getQueueSize() >= 3 })
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("B Event") } })
        assertEquals(emptyList<SdkAuthError>(), heard)
    }

    // ── S2 / contract 46: attribute flush vs a later op of the same key ─

    @Test
    fun `an unset made while a set of the same key is on the wire lands after it`() {
        // Fails before: the outbox sent the unset while the pending-set flush
        // was still in flight, so the set could land last and bring the
        // value back.
        val firstSetDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val unsetSawSetDone = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val sets = java.util.concurrent.atomic.AtomicInteger(0)
        api.answers["/v1/attributes"] = {
            // Matched on the request this answer is for: the dispatcher runs
            // one thread per connection, so "the last seen" may be another.
            val body = api.seen.last { it.path.endsWith("/v1/attributes") }.body
            when {
                body.contains("\"plan\":\"pro\"") && sets.incrementAndGet() == 1 -> {
                    Thread.sleep(1_500)
                    firstSetDone.set(true)
                    TestServer.json("""{"success":true}""")
                }
                body.contains("\"unset\"") -> {
                    unsetSawSetDone.compareAndSet(null, firstSetDone.get())
                    TestServer.json("""{"success":true}""")
                }
                else -> TestServer.json("""{"success":true}""")
            }
        }
        val sdk = init(config = config().copy(trackSessionStart = false))
        sdk.setAttribute("plan", "pro")
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"plan\":\"pro\"") } })
        sdk.unsetAttribute("plan")
        assertTrue(pumpUntil { unsetSawSetDone.get() != null })
        assertEquals(true, unsetSawSetDone.get())
    }

    // ── Contract 36: withdrawals are always delivered ──────────────────

    @Test
    fun `an unsubscribe queued before optOut is still delivered`() {
        // Fails before: optOut() discarded every outbox write but the marker.
        var calls = 0
        api.answers["/v1/subscriptions/channel"] = {
            if (++calls == 1) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = init()
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { calls == 1 })
        sdk.optOut()
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
        assertTrue(bodiesOf("/v1/subscriptions/channel").last().contains("unsubscribed"))
    }

    @Test
    fun `unsubscribes are accepted while opted out or denied, subscribes are not`() {
        // Fails before: setSubscription / removeFromSubscriptionGroup returned
        // at blocked() - an opted-out person could not unsubscribe.
        val sdk = init()
        sdk.optOut()
        sdk.setSubscription("email", "unsubscribed")
        sdk.setSubscription("sms", "subscribed")
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.removeFromSubscriptionGroup("weekly", "email")
        sdk.addToSubscriptionGroup("daily", "email")
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/group").isNotEmpty() })
        pumpFor(400)
        assertEquals(1, bodiesOf("/v1/subscriptions/channel").size)
        assertTrue(bodiesOf("/v1/subscriptions/channel").single().contains("\"email\""))
        assertEquals(1, bodiesOf("/v1/subscriptions/group").size)
        assertTrue(bodiesOf("/v1/subscriptions/group").single().contains("\"unsubscribe\""))
    }

    @Test
    fun `wipeData keeps an undelivered unsubscribe`() {
        // Fails before: wipeData() cleared the whole outbox.
        var calls = 0
        api.answers["/v1/subscriptions/group"] = {
            if (++calls == 1) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = init()
        sdk.removeFromSubscriptionGroup("weekly", "email")
        assertTrue(pumpUntil { calls == 1 })
        sdk.wipeData()
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
    }

    @Test
    fun `the opt-out marker is delivered while consent is pending`() {
        // Fails before: the marker waited for consent GRANTED.
        val sdk = init(config = config(consent = TrackingConsent.PENDING))
        sdk.optOut()
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"tracking_opted_out\":true") } })
    }

    // ── Contract 37: a newer write supersedes a held older one ─────────

    @Test
    fun `a subscribe held by pending consent never lands after a later unsubscribe`() {
        // The unsubscribe goes out while consent is pending (a withdrawal);
        // the older subscribe it replaced must not follow it on grant.
        val sdk = init(config = config(consent = TrackingConsent.PENDING))
        sdk.setSubscription("email", "subscribed")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        sdk.updateConsent(TrackingConsent.GRANTED)
        pumpFor(800)
        assertEquals(listOf(true), bodiesOf("/v1/subscriptions/channel").map { it.contains("unsubscribed") })
    }

    // ── Contract 38: ids resolved at send time ─────────────────────────

    @Test
    fun `a subscription captured before identify carries the userId when sent after it`() {
        // Fails before: the body kept only the anonymous id, which the server
        // refuses (403 SDK_ANONYMOUS_ID_IDENTIFIED) once the profile is
        // identified under SDK authentication - and the 403 dropped it.
        var calls = 0
        api.answers["/v1/subscriptions/channel"] = {
            if (++calls == 1) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = init()
        sdk.setSubscription("email", "subscribed")
        assertTrue(pumpUntil { calls == 1 })
        assertFalse(bodiesOf("/v1/subscriptions/channel").first().contains("userId"))
        sdk.identify("signed-up")
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
        assertTrue(bodiesOf("/v1/subscriptions/channel").last().contains("\"userId\":\"signed-up\""))
    }

    @Test
    fun `a write of a previous identity keeps its own ids`() {
        var calls = 0
        api.answers["/v1/subscriptions/channel"] = {
            if (++calls == 1) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        val sdk = init()
        sdk.setSubscription("email", "subscribed")
        assertTrue(pumpUntil { calls == 1 })
        sdk.reset()
        sdk.identify("someone-else")
        runBlocking { sdk.drainOutbox(force = true) }
        assertTrue(pumpUntil { calls == 2 })
        assertFalse(bodiesOf("/v1/subscriptions/channel").last().contains("someone-else"))
    }

    // ── Contract 40: pending attribute refusals are capped ─────────────

    @Test
    fun `attributes the server keeps refusing are dropped after the shared cap`() {
        // Fails before: a 401 / success:false on the pending-set path was
        // retried on every trigger, for the life of the install.
        api.answers["/v1/attributes"] = { TestServer.json("""{"success":false,"message":"nope"}""") }
        val sdk = init(config = config().copy(trackSessionStart = false))
        sdk.setAttribute("tier", "gold")
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("tier") } })
        repeat(14) { runBlocking { sdk.flushPendingAttributes() } }
        pumpFor(300)
        assertEquals(10, bodiesOf("/v1/attributes").count { it.contains("tier") })
    }

    // ── Contract 41 / M2: sizes measured like the backend ──────────────

    @Test
    fun `characters gson would escape do not inflate the size check`() {
        // 9,000 '<' are 9,000 characters for the backend, 54,000 with Gson's
        // HTML escaping - which dropped this event as "larger than 50 KB".
        val sdk = init(config = config().copy(trackSessionStart = false))
        sdk.track("Markup", mapOf("html" to "<".repeat(9_000)))
        sdk.track("   ")
        assertTrue(pumpUntil { sdk.getQueueSize() == 2 })
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("<<<<") } })
        assertTrue("sent unescaped", bodiesOf("/v1/track/batch").none { it.contains("\\u003c") })
        assertTrue("a whitespace-only name is a name", bodiesOf("/v1/track/batch").any { it.contains("\"type\":\"   \"") })
    }

    // ── Contract 42 / M6: e-commerce guards ────────────────────────────

    @Test
    fun `a purchase without an order id or a finite value sends nothing`() {
        val sdk = init(config = config().copy(trackSessionStart = false))
        val shop = sdk.ecommerce()
        val item = EcommerceCartItem(productId = "p1", name = "Shirt", price = 10.0, quantity = 1)
        shop.purchase(EcommerceOrder(orderId = "", items = listOf(item), value = 10.0))
        shop.purchase(EcommerceOrder(orderId = "o-1", items = listOf(item), value = Double.NaN))
        shop.purchase(EcommerceOrder(orderId = "o-2", items = listOf(item.copy(productId = " ")), value = 10.0))
        shop.purchase(EcommerceOrder(orderId = "o-3", items = listOf(item), value = 10.0))
        assertTrue(pumpUntil { sdk.getQueueSize() == 1 })
        pumpFor(300)
        assertEquals(1, sdk.getQueueSize())
    }

    @Test
    fun `one e-commerce guard set - every incomplete call is refused, complete ones are sent`() {
        // Round 4 integration: the same table on every SDK. Fails before: a
        // NaN price, a NaN shipping, refundAmount or discountAmount was
        // dropped from the event (or passed through) and the event SENT.
        val sdk = init(config = config().copy(trackSessionStart = false))
        val shop = EcommerceTracker.create()
        val p = io.joryio.sdk.ecommerce.EcommerceProduct(productId = "p1", name = "Buds", price = 10.0)
        val line = EcommerceCartItem(productId = "p1", name = "Buds", price = 10.0, quantity = 1)
        shop.viewProduct(p.copy(price = Double.NaN))
        shop.viewProduct(p.copy(productId = "   "))
        shop.addToCart(line.copy(price = Double.POSITIVE_INFINITY))
        shop.addToCart(line, Double.NaN)
        shop.shareProduct(p, " ")
        shop.viewCategory("c1", " ")
        shop.search("  ")
        shop.cartViewed(listOf(line.copy(productId = "")), 10.0)
        shop.startCheckout(listOf(line.copy(price = Double.NaN)), 10.0)
        shop.purchase(EcommerceOrder(orderId = "o1", items = listOf(line), value = 10.0, shipping = Double.NaN))
        shop.purchase(EcommerceOrder(orderId = " ", items = emptyList(), value = 10.0))
        shop.orderRefunded("o1", Double.NaN)
        shop.applyCoupon("SAVE", Double.NaN)
        shop.removeCoupon(" ")
        pumpFor(300)
        assertEquals(0, sdk.getQueueSize())

        shop.viewProduct(p)
        shop.cartViewed(listOf(line), 10.0)
        shop.purchase(EcommerceOrder(orderId = "o1", items = emptyList(), value = 10.0))
        shop.orderRefunded("o1")
        assertTrue(pumpUntil { sdk.getQueueSize() == 4 })
    }

    @Test
    fun `EcommerceTracker create before initialize does not throw and its events arrive`() {
        // Fails before: create() called Joryio.getInstance(), which throws
        // before initialize().
        val shop = EcommerceTracker.create()
        shop.orderDelivered("o-9")
        val sdk = init()
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Order Delivered") } })
    }

    // ── Contract 43 / M3: in-app gated on sending ──────────────────────

    private fun eventCampaign() {
        api.answers["/v1/in-app/sync"] = {
            TestServer.json(
                """{"success":true,"evaluatedAt":"x","campaigns":[{"id":"t1","name":"T","type":"modal","frequencyCap":{"maxImpressions":5,"timeWindow":"day"},"triggers":[{"type":"event","event":"Buy"}]}]}""",
            )
        }
    }

    @Test
    fun `no message is displayed while consent is pending at runtime`() {
        // Fails before: the campaign synced while granted displayed on an
        // event tracked after updateConsent(PENDING).
        eventCampaign()
        val sdk = init()
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        sdk.syncInAppCampaigns()
        pumpFor(800)
        sdk.updateConsent(TrackingConsent.PENDING)
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(emptyList<String>(), handed)
    }

    @Test
    fun `a dismissal while sending is blocked still frees the display claim`() {
        // Fails before: the dismissal returned at the send gate and the
        // campaign stayed claimed.
        eventCampaign()
        val sdk = init()
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        sdk.syncInAppCampaigns()
        pumpFor(800)
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(listOf("t1"), handed)
        // Closed while opted out: the report may not be sent, the claim
        // must still go.
        sdk.optOut()
        sdk.trackInAppImpression("t1", "dismissed")
        pumpFor(300)
        sdk.optIn()
        pumpFor(800)
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(listOf("t1", "t1"), handed)

        // The same while consent is pending (the report waits in a
        // coroutine that runs while sending is still blocked).
        sdk.updateConsent(TrackingConsent.PENDING)
        sdk.trackInAppImpression("t1", "dismissed")
        pumpFor(300)
        sdk.updateConsent(TrackingConsent.GRANTED)
        pumpFor(800)
        sdk.track("Buy")
        pumpFor(300)
        assertEquals(listOf("t1", "t1", "t1"), handed)
    }

    // ── Contract 44 / M4: getters before init read stored decisions ────

    @Test
    fun `isUserOptedOut and getTrackingConsent before init answer the stored decisions`() {
        // Fails before: false / null - the decisions live in a store keyed
        // by the SDK key, which is unknown before initialize().
        val sdk = init()
        sdk.optOut()
        sdk.updateConsent(TrackingConsent.DENIED)
        restart()
        assertTrue(Joryio.isUserOptedOut())
        assertEquals(TrackingConsent.DENIED, Joryio.getTrackingConsent())
    }

    // ── Contract 45: pre-init consent sequences ────────────────────────

    @Test
    fun `pre-init PENDING is not persisted`() {
        Joryio.updateConsent(TrackingConsent.PENDING)
        assertEquals(TrackingConsent.PENDING, Joryio.getTrackingConsent())
        restart() // the process dies before initialize()
        // Nothing decided or stored: GRANTED, as on iOS (contract v6 65; it read null here).
        assertEquals(TrackingConsent.GRANTED, Joryio.getTrackingConsent())
        assertEquals(TrackingConsent.GRANTED, init().getTrackingConsent())
    }

    @Test
    fun `pre-init PENDING never reopens a denial stored by an earlier launch`() {
        val k = key()
        init(k).updateConsent(TrackingConsent.DENIED)
        restart()
        Joryio.updateConsent(TrackingConsent.PENDING)
        assertEquals(TrackingConsent.DENIED, Joryio.getTrackingConsent())
        assertEquals(TrackingConsent.DENIED, init(k).getTrackingConsent())
    }

    @Test
    fun `an explicit pre-init optIn wins over optOut in the config`() {
        Joryio.optIn()
        assertFalse(init(config = config(optOut = true)).isUserOptedOut())
    }

    // ── M6: push_permission remembered on acknowledgement ──────────────

    @Test
    fun `a push_permission report that was never delivered is sent again`() {
        // Fails before: the value was remembered as reported when it was
        // queued; the optOut() that discarded it left it "reported" for good.
        var permissionCalls = 0
        api.answers["/v1/attributes"] = {
            if (lastBody("/v1/attributes").contains("push_permission") && ++permissionCalls == 1) {
                TestServer.json("""{"error":"down"}""", code = 503)
            } else {
                TestServer.json("""{"success":true}""")
            }
        }
        val sdk = init()
        assertTrue(pumpUntil { permissionCalls == 1 })
        sdk.optOut()
        sdk.optIn()
        assertTrue(pumpUntil { permissionCalls == 2 })
    }
}
