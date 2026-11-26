package io.joryio.sdk.placements

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Placements on Android (docs/PLACEMENTS_PLAN.md, PR 6): the manager's rules,
 * against a fake transport (the serve endpoint is mocked). The same rules as
 * the web SDK's placements.spec.ts.
 */
class PlacementManagerTest {

    private class FakeTransport(var answer: (JsonObject) -> Pair<Int, String?>) : PlacementTransport {
        val fetches: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())
        val tracks: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())
        override suspend fun fetch(json: String): Pair<Int, String?> {
            val body = JsonParser.parseString(json).asJsonObject
            fetches.add(body)
            return answer(body)
        }
        override suspend fun track(json: String) {
            tracks.add(JsonParser.parseString(json).asJsonObject)
        }
    }

    private class MemoryStore : PlacementCacheStore {
        var value: String? = null
        override fun load() = value
        override fun save(json: String?) { value = json }
    }

    private var clock = 1_700_000_000_000L
    private var consentValue: String? = "granted"
    private var user: String? = null

    private fun item(id: String = "camp-1", extra: String = "") = """
        {"id":"$id","variantId":"v2","deliveryToken":"tok-$id","priority":8,"kind":"native",
         "data":{"headline":"Free shipping today","count":3,"flag":true},
         "content":{"kind":"native","title":"Free shipping","body":"Today only","buttons":[{"id":"cta","text":"Shop","action":"url","url":"https://shop.example"}]}
         $extra}
    """.trimIndent()

    private fun answer(placements: String) = 200 to """{"success":true,"evaluatedAt":"2026-11-20T10:00:00Z","placements":{$placements}}"""

    private fun hero(items: String = item(), extra: String = "") =
        """"home_hero":{"placementKey":"home_hero","emptyBehavior":"collapse","maxItems":1,"ttlSeconds":300,"isControl":false,"controlToken":null,"items":[$items]$extra}"""

    private fun manager(transport: FakeTransport, store: PlacementCacheStore? = null) = PlacementManager(
        transport = transport,
        store = store,
        consent = { consentValue },
        userId = { user },
        anonymousId = { "anon-1" },
        sessionId = { "s-1" },
        capabilities = { listOf("placements", "content.native", "content.data") },
        locale = { "he-IL" },
        postMain = { it.run() },
        now = { clock },
    )

    private fun waitUntil(pred: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!pred() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test
    fun batchesKeysIntoOneRequestWithThePlanBody() = runBlocking {
        val t = FakeTransport { answer(hero() + ""","cart_upsell":{"placementKey":"cart_upsell","items":[]}""") }
        val m = manager(t)
        var a: Map<String, Placement>? = null
        m.getPlacements(listOf("home_hero")) { a = it }
        val b = m.getPlacements(listOf("cart_upsell", "home_hero"))
        waitUntil { a != null }
        assertEquals(1, t.fetches.size)
        val body = t.fetches[0]
        assertEquals(setOf("home_hero", "cart_upsell"), body.getAsJsonArray("placements").map { it.asString }.toSet())
        assertEquals("anon-1", body.get("anonymousId").asString)
        assertEquals("s-1", body.get("sessionId").asString)
        assertEquals("android", body.get("deviceType").asString)
        assertEquals("granted", body.get("trackingConsent").asString)
        assertEquals(listOf("placements", "content.native", "content.data"), body.getAsJsonArray("capabilities").map { it.asString })
        assertFalse("only a Show when re-check carries an event", body.has("recheckEvent"))
        val item = a!!["home_hero"]!!.items.single()
        assertEquals("camp-1", item.id)
        assertEquals("Free shipping today", item.data["headline"])
        assertEquals(3L, item.data["count"])
        assertEquals("Free shipping", item.content!!.title)
        assertTrue(b["cart_upsell"]!!.items.isEmpty())
        assertEquals("collapse", b["cart_upsell"]!!.emptyBehavior)
    }

    /** D444 Phase 2b: a recommendation item's product cards reach the app and the bridges (toJson). */
    @Test
    fun exposesProductCardsAndCarriesThemThroughToJson() = runBlocking {
        val products = ""","products":[{"id":"sku-1","title":"Mug","price":"$9","compareAtPrice":"$10","discountPercent":10,"imageUrl":"https://cdn/x.png","url":"https://shop/x"},{"title":"no id"}]"""
        val t = FakeTransport { answer(hero(item(extra = products))) }
        val r = manager(t).getPlacements(listOf("home_hero"))
        val item = r["home_hero"]!!.items.single()
        assertEquals(1, item.products.size)
        assertEquals("sku-1", item.products[0].id)
        assertEquals(10.0, item.products[0].discountPercent!!, 0.0)
        val bridged = JsonParser.parseString(r["home_hero"]!!.toJson()).asJsonObject
            .getAsJsonArray("items")[0].asJsonObject.getAsJsonArray("products")[0].asJsonObject
        assertEquals("sku-1", bridged.get("id").asString)
        assertEquals("\$10", bridged.get("compareAtPrice").asString)
    }

    @Test
    fun servesTheCacheForTtlThenAsksAgain() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        m.getPlacements(listOf("home_hero"))
        m.getPlacements(listOf("home_hero"))
        assertEquals(1, t.fetches.size)
        clock += 300_000
        m.getPlacements(listOf("home_hero"))
        assertEquals(2, t.fetches.size)
    }

    @Test
    fun refreshesAKeyAtMostOncePerMinRefreshWindow() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        m.getPlacements(listOf("home_hero"))
        m.refreshPlacements(listOf("home_hero"))
        m.refreshPlacements(listOf("home_hero"))
        Thread.sleep(200)
        assertEquals(1, t.fetches.size)
        assertEquals(30, PLACEMENT_MIN_REFRESH_SECONDS)
        assertEquals(0.5, PLACEMENT_VIEWABLE_FRACTION, 0.0)
    }

    @Test
    fun a429KeepsTheCachedPlacement() = runBlocking {
        var status = 200
        val t = FakeTransport { if (status == 200) answer(hero(extra = ""","ttlSeconds":60""")) else status to null }
        val m = manager(t)
        m.getPlacements(listOf("home_hero"))
        status = 429
        clock += 61_000
        val r = m.getPlacements(listOf("home_hero"))
        assertEquals(2, t.fetches.size)
        assertEquals(listOf("camp-1"), r["home_hero"]!!.items.map { it.id })
    }

    /**
     * 1.4.0-beta.11 test: a placements request that failed on a stale
     * connection stamped its keys for the whole 30 s window, so they read
     * empty for 30 s. A request with no answer (or a 5xx) holds them back only
     * PLACEMENT_RETRY_AFTER_FAILURE_SECONDS; a 429 keeps the window.
     */
    @Test
    fun aFailedRequestDoesNotBlockARefetchForTheWholeWindow() = runBlocking {
        assertEquals(3, PLACEMENT_RETRY_AFTER_FAILURE_SECONDS)
        var status = 0
        val t = FakeTransport { if (status == 200) answer(hero()) else status to null }
        val m = manager(t)
        assertTrue(m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.isEmpty())
        status = 200
        clock += 1_000
        m.getPlacements(listOf("home_hero"))
        assertEquals("inside the short backoff: not asked again", 1, t.fetches.size)
        clock += PLACEMENT_RETRY_AFTER_FAILURE_SECONDS * 1000L
        val r = m.getPlacements(listOf("home_hero"))
        assertEquals(2, t.fetches.size)
        assertEquals(listOf("camp-1"), r["home_hero"]!!.items.map { it.id })
        // A 5xx is a failure too.
        clock += PLACEMENT_MIN_REFRESH_SECONDS * 1000L
        status = 503
        m.refreshPlacements(listOf("home_hero"))
        waitUntil { t.fetches.size == 3 }
        Thread.sleep(100)
        clock += PLACEMENT_RETRY_AFTER_FAILURE_SECONDS * 1000L
        status = 200
        m.refreshPlacements(listOf("home_hero"))
        waitUntil { t.fetches.size == 4 }
        assertEquals(4, t.fetches.size)
    }

    @Test
    fun a429KeepsTheWholeRefreshWindow() = runBlocking {
        val t = FakeTransport { 429 to null }
        val m = manager(t)
        m.getPlacements(listOf("home_hero"))
        clock += PLACEMENT_RETRY_AFTER_FAILURE_SECONDS * 1000L + 1
        m.getPlacements(listOf("home_hero"))
        assertEquals(1, t.fetches.size)
        clock += PLACEMENT_MIN_REFRESH_SECONDS * 1000L
        m.getPlacements(listOf("home_hero"))
        assertEquals(2, t.fetches.size)
    }

    @Test
    fun withoutConsentTheRequestNamesNobodyAndNothingIsReported() = runBlocking {
        consentValue = "denied"
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        val r = m.getPlacements(listOf("home_hero"))
        val body = t.fetches.single()
        assertEquals("denied", body.get("trackingConsent").asString)
        assertFalse(body.has("anonymousId"))
        assertFalse(body.has("userId"))
        assertFalse(body.has("sessionId"))
        r["home_hero"]!!.items.single().markDisplayed()
        r["home_hero"]!!.items.single().markClicked("cta")
        Thread.sleep(100)
        assertTrue(t.tracks.isEmpty())
    }

    @Test
    fun nothingIsSentWhenSendingIsImpossible() = runBlocking {
        consentValue = null
        val t = FakeTransport { answer(hero()) }
        val r = manager(t).getPlacements(listOf("home_hero"))
        assertTrue(t.fetches.isEmpty())
        assertTrue(r["home_hero"]!!.items.isEmpty())
    }

    @Test
    fun reportsDisplayedOnceAndClickCountsDisplayedFirst() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        val item = m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.single()
        item.markClicked("cta")
        item.markDisplayed()
        item.markClicked("click") // a generic name is not an element id
        waitUntil { t.tracks.size >= 3 }
        Thread.sleep(50)
        val actions = t.tracks.map { it.get("action").asString }
        assertEquals(listOf("displayed", "clicked", "clicked"), actions.sortedBy { if (it == "displayed") 0 else 1 })
        val displayed = t.tracks.first { it.get("action").asString == "displayed" }
        assertEquals("camp-1", displayed.get("campaignId").asString)
        assertEquals("tok-camp-1", displayed.get("deliveryToken").asString)
        assertEquals("home_hero", displayed.get("placementKey").asString)
        assertEquals("v2", displayed.get("variantId").asString)
        assertEquals(1, t.tracks.count { it.get("action").asString == "clicked" && it.has("elementId") && it.get("elementId").asString == "cta" })
    }

    @Test
    fun aDismissalIsReportedAndHidesTheItemUntilTheNextAnswer() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.single().markDismissed()
        waitUntil { t.tracks.size >= 2 }
        assertEquals(setOf("displayed", "dismissed"), t.tracks.map { it.get("action").asString }.toSet())
        assertTrue(m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.isEmpty())
    }

    @Test
    fun aHeldOutPersonReportsControlViewedOncePerToken() = runBlocking {
        val t = FakeTransport { answer(""""home_hero":{"placementKey":"home_hero","isControl":true,"controlToken":"ctl-1","items":[]}""") }
        val m = manager(t)
        val p = m.getPlacements(listOf("home_hero"))["home_hero"]!!
        assertTrue(p.isControl)
        p.markDisplayed()
        p.markDisplayed()
        waitUntil { t.tracks.isNotEmpty() }
        Thread.sleep(50)
        assertEquals(1, t.tracks.size)
        val body = t.tracks.single()
        assertEquals("control_viewed", body.get("action").asString)
        assertEquals("ctl-1", body.get("controlToken").asString)
        assertEquals("home_hero", body.get("placementKey").asString)
        assertFalse(body.has("campaignId"))
    }

    @Test
    fun anAnswerFromBeforeAnIdentityChangeIsDiscarded() = runBlocking {
        lateinit var m: PlacementManager
        val t = FakeTransport { m.invalidate(); answer(hero()) }
        m = manager(t)
        val r = m.getPlacements(listOf("home_hero"))
        assertTrue(r["home_hero"]!!.items.isEmpty())
    }

    @Test
    fun chunksMoreThanTenKeys() = runBlocking {
        val t = FakeTransport { answer("") }
        manager(t).getPlacements((0 until 12).map { "k$it" })
        assertEquals(listOf(10, 2), t.fetches.map { it.getAsJsonArray("placements").size() }.sorted().reversed())
    }

    @Test
    fun eventTriggeredItemsWaitForTheirEvent() = runBlocking {
        val triggered = item(extra = ""","triggers":[{"type":"event","event":"checkout_started","attribute":"value","operator":"gt","value":50}]""")
        val t = FakeTransport { answer(hero(triggered)) }
        val m = manager(t)
        assertTrue(m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.isEmpty())
        m.onEventTracked("checkout_started", mapOf("value" to 10))
        assertTrue(m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.isEmpty())
        m.onEventTracked("checkout_started", mapOf("value" to 80))
        assertEquals(1, m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.size)
        assertEquals(1, t.fetches.size) // no round trip
        clock += PLACEMENT_DEFAULT_TRIGGER_WINDOW_SECONDS * 1000L + 1
        assertTrue(m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.isEmpty() || t.fetches.size > 1)
    }

    @Test
    fun hostsAreFilledAndTheirShownIdsAreSent() = runBlocking {
        var n = 1
        val t = FakeTransport { answer(hero(item("camp-$n"))) }
        val m = manager(t)
        val updates = ArrayList<Placement>()
        val host = object : PlacementHost {
            override val placementKey = "home_hero"
            override fun shownIds() = updates.lastOrNull()?.items?.map { it.id } ?: emptyList()
            override fun onPlacementUpdated(placement: Placement) { updates.add(placement) }
            override fun clearPlacement() {}
        }
        m.attach(host)
        waitUntil { updates.isNotEmpty() }
        assertEquals("camp-1", updates.last().items.single().id)
        n = 2
        clock += PLACEMENT_MIN_REFRESH_SECONDS * 1000L
        m.refreshPlacements(null)
        waitUntil { t.fetches.size == 2 && updates.last().items.single().id == "camp-2" }
        assertEquals(listOf("camp-1"), t.fetches[1].getAsJsonObject("shown").getAsJsonArray("home_hero").map { it.asString })
    }

    @Test
    fun theDiskCacheServesTheSamePersonOnly() = runBlocking {
        val store = MemoryStore()
        val t = FakeTransport { answer(hero()) }
        manager(t, store).getPlacements(listOf("home_hero"))
        val t2 = FakeTransport { answer(hero()) }
        assertEquals(1, manager(t2, store).getPlacements(listOf("home_hero"))["home_hero"]!!.items.size)
        assertTrue(t2.fetches.isEmpty())
        user = "user-2"
        val t3 = FakeTransport { answer(hero()) }
        manager(t3, store).getPlacements(listOf("home_hero"))
        assertEquals("user-2", t3.fetches.single().get("userId").asString)
    }

    @Test
    fun aPlacementFromBeforeAnIdentityChangeReportsNothing() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        val old = m.getPlacements(listOf("home_hero"))["home_hero"]!!
        m.invalidate()
        old.items.single().markDisplayed()
        old.items.single().markClicked("cta")
        old.items.single().markDismissed()
        old.markDisplayed()
        Thread.sleep(100)
        assertTrue(t.tracks.isEmpty())
    }

    @Test
    fun invalidateClearsHostsAtOnceAndForgetsWhatWasReported() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        val m = manager(t)
        var cleared = 0
        val host = object : PlacementHost {
            override val placementKey = "home_hero"
            override fun shownIds() = listOf("camp-1")
            override fun onPlacementUpdated(placement: Placement) {}
            override fun clearPlacement() { cleared++ }
        }
        m.attach(host)
        m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.single().markDisplayed()
        waitUntil { t.tracks.size == 1 }
        m.invalidate()
        assertEquals(1, cleared)
        // The next person's own impression of the same delivery is theirs to report.
        clock += PLACEMENT_MIN_REFRESH_SECONDS * 1000L
        m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.single().markDisplayed()
        waitUntil { t.tracks.size == 2 }
        assertEquals(2, t.tracks.size)
    }

    @Test
    fun unknownOperatorsDoNotMatch() {
        assertFalse(PlacementManager.triggerMatches(mapOf("attribute" to "a", "operator" to "regex", "value" to "x"), mapOf("a" to "x")))
        assertTrue(PlacementManager.triggerMatches(mapOf("attribute" to "a", "value" to 2), mapOf("a" to 2.0)))
        assertNull(PlacementManager.parseIso("not a date"))
    }

    // ── Show when (BACKLOG D465) ────────────────────────────────────────

    private var session = "s-1"
    private val delivered = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var deliverAnswer = true
    @Volatile private var stillQueued = setOf<String>()
    private val scheduled = Collections.synchronizedList(ArrayList<Long>())

    private fun swManager(transport: FakeTransport, screen: io.joryio.sdk.messaging.ScreenClock) = PlacementManager(
        transport = transport,
        store = null,
        consent = { consentValue },
        userId = { user },
        anonymousId = { "anon-1" },
        sessionId = { session },
        capabilities = { listOf("placements", "content.native", "content.data") },
        locale = { "he-IL" },
        postMain = { it.run() },
        now = { clock },
        screenClock = screen,
        deliverEvent = { delivered.add(it); deliverAnswer },
        isEventQueued = { it in stillQueued },
        schedule = { ms, _ -> scheduled.add(ms); {} },
    )

    private fun screenClock() = io.joryio.sdk.messaging.ScreenClock(now = { clock })

    private fun sw(json: String) = ""","showWhen":$json"""

    private fun controls(t: FakeTransport) =
        t.tracks.toList().filter { it.get("action").asString == "control_viewed" }.map { it.get("controlToken").asString }

    @Test
    fun showWhenConstantsAreTheWireValues() {
        assertEquals(listOf("load", "seconds", "scroll", "exit", "event"), SHOW_WHEN_KINDS)
        fun p(json: String?) = ShowWhenMoment.parse(json?.let { JsonParser.parseString(it) })
        assertEquals(ShowWhenMoment.Load, p(null))
        assertEquals(ShowWhenMoment.Load, p("""{"kind":"load"}"""))
        assertEquals(ShowWhenMoment.Seconds(5.0), p("""{"kind":"seconds","seconds":5}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"seconds","seconds":0}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"seconds","seconds":2.5}"""))
        assertEquals(ShowWhenMoment.Event("add_to_cart"), p("""{"kind":"event","event":" add_to_cart "}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"event","event":""}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"scroll","percent":50}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"exit"}"""))
        assertEquals(ShowWhenMoment.Never, p("""{"kind":"later"}"""))
        assertEquals(ShowWhenMoment.Never, p("\"load\""))
    }

    /** No capability: placements exist only in the 1.4.0 betas (owner decision). */
    @Test
    fun theRequestDeclaresNoShowWhenCapability() = runBlocking {
        val t = FakeTransport { answer(hero()) }
        swManager(t, screenClock()).getPlacements(listOf("home_hero"))
        assertFalse(t.fetches[0].getAsJsonArray("capabilities").any { it.asString == "show_when" })
    }

    private fun ev(name: String, props: Map<String, Any?> = emptyMap()) =
        io.joryio.sdk.models.Event(type = name, properties = props, anonymousId = "anon-1", sessionId = session)

    @Test
    fun secondsOnScreenHoldsThenRevealsPausesInTheBackgroundAndRestartsOnANewScreen() = runBlocking {
        val screen = screenClock()
        val t = FakeTransport { answer(hero(item(extra = sw("""{"kind":"seconds","seconds":10}""")))) }
        val m = swManager(t, screen)
        val updates = ArrayList<Int>()
        m.onPlacementsUpdated { updates.add(it["home_hero"]?.items?.size ?: -1) }
        val first = m.getPlacements(listOf("home_hero"))
        assertTrue("held until its moment", first["home_hero"]!!.items.isEmpty())
        assertEquals(10_050L, scheduled.last())

        clock += 4_000
        screen.pause() // background time does not count
        clock += 100_000
        screen.resume()
        m.checkShowWhen()
        assertTrue(m.toPlacement("home_hero").items.isEmpty())
        clock += 6_000
        m.checkShowWhen()
        val shown = m.toPlacement("home_hero")
        assertEquals(1, shown.items.size)
        assertEquals("onPlacementsUpdated at the moment", 1, updates.last())
        shown.items[0].markDisplayed()
        waitUntil { t.tracks.isNotEmpty() }
        assertEquals("displayed", t.tracks[0].get("action").asString)

        // A new screen: held again until 10 s on it.
        screen.enter("Cart")
        assertTrue(m.toPlacement("home_hero").items.isEmpty())
        assertEquals(0, updates.last())
        clock += 10_000
        m.checkShowWhen()
        assertEquals(1, m.toPlacement("home_hero").items.size)
        assertEquals(1, t.fetches.size)
    }

    @Test
    fun anImpressionIsNeverCountedWhileHeld() = runBlocking {
        val t = FakeTransport { answer(hero(item(extra = sw("""{"kind":"seconds","seconds":10}""")))) }
        val m = swManager(t, screenClock())
        m.getPlacements(listOf("home_hero"))
        val wire = JsonParser.parseString(item(extra = sw("""{"kind":"seconds","seconds":10}"""))).asJsonObject
        val held = PlacementItem("camp-1", "v2", "native", emptyMap(), null, emptyList(), null, null, "home_hero",
            PlacementItemWire("camp-1", "v2", "tok-camp-1", null, null, "native", emptyMap(), null, emptyList(), emptyList(), null, null, wire, ShowWhenMoment.Seconds(10.0)),
            m)
        held.markDisplayed()
        Thread.sleep(100)
        assertTrue(t.tracks.isEmpty())
    }

    @Test
    fun scrollExitAndUnknownKindsAreNeverShownInAnApp() = runBlocking {
        val items = listOf(
            item("a", sw("""{"kind":"scroll","percent":50}""")),
            item("b", sw("""{"kind":"exit"}""")),
            item("c", sw("""{"kind":"hover"}""")),
            item("d", sw("""{"kind":"seconds","seconds":99999}""")),
        ).joinToString(",")
        val t = FakeTransport { answer(""""home_hero":{"placementKey":"home_hero","maxItems":4,"items":[$items]}""") }
        val m = swManager(t, screenClock())
        m.getPlacements(listOf("home_hero"))
        clock += 4_000_000
        m.onEventTracked("anything", emptyMap())
        m.checkShowWhen()
        assertTrue(m.toPlacement("home_hero").items.isEmpty())
    }

    @Test
    fun anEventOfThisVisitRevealsAndReChecksTheKeyAtOncePastTheLimitOncePerScreen() = runBlocking {
        val screen = screenClock()
        val t = FakeTransport { answer(hero(item(extra = sw("""{"kind":"event","event":"add_to_cart"}""")), extra = ""","recheckOn":["add_to_cart","wishlist"]""")) }
        val m = swManager(t, screen)
        m.getPlacements(listOf("home_hero"))
        assertTrue(m.toPlacement("home_hero").items.isEmpty())

        val cart = ev("add_to_cart", mapOf("sku" to "A-1", "qty" to 2))
        m.onEventTracked("add_to_cart", cart.properties, cart)
        assertEquals("the event counts in this visit", 1, m.toPlacement("home_hero").items.size)
        waitUntil { t.fetches.size == 2 }
        assertEquals("the event leaves the queue before the re-check", listOf(cart.eventId), delivered.toList())
        assertEquals("re-fetched inside the 30 s limit", 2, t.fetches.size)
        val rc = t.fetches[1].getAsJsonObject("recheckEvent")
        assertEquals("add_to_cart", rc.get("name").asString)
        assertEquals(cart.eventId, rc.get("eventId").asString)
        assertEquals("A-1", rc.getAsJsonObject("properties").get("sku").asString)
        assertEquals(2, rc.getAsJsonObject("properties").get("qty").asInt)
        assertEquals(listOf("home_hero"), t.fetches[1].getAsJsonArray("placements").map { it.asString })

        m.onEventTracked("add_to_cart", emptyMap(), ev("add_to_cart"))
        Thread.sleep(300)
        assertEquals("once per (key, event) per screen", 2, t.fetches.size)

        // recheckOn alone (a campaign the person does not qualify for yet) also re-checks.
        m.onEventTracked("wishlist", emptyMap(), ev("wishlist"))
        waitUntil { t.fetches.size == 3 }
        assertEquals(3, t.fetches.size)

        // A new screen: the visit's event still counts at once; the re-check may run again.
        screen.enter("Cart")
        assertEquals(1, m.toPlacement("home_hero").items.size)
        m.onEventTracked("wishlist", emptyMap(), ev("wishlist"))
        waitUntil { t.fetches.size == 4 }
        assertEquals(4, t.fetches.size)

        // A new visit (session) starts the event set over.
        session = "s-2"
        screen.enter("Home")
        assertTrue(m.toPlacement("home_hero").items.isEmpty())
    }

    @Test
    fun anEventTrackedBeforeTheAnswerArrivedShowsTheItemAtOnce() = runBlocking {
        val t = FakeTransport { answer(hero(item(extra = sw("""{"kind":"event","event":"signup"}""")))) }
        val m = swManager(t, screenClock())
        m.onEventTracked("signup", emptyMap())
        assertEquals(1, m.getPlacements(listOf("home_hero"))["home_hero"]!!.items.size)
    }

    /**
     * The flush failed (the event is still queued): no re-check now; once - and
     * only once - after a later flush that sent it.
     */
    @Test
    fun aReCheckWhoseEventIsStillQueuedRunsOnceAfterTheNextSuccessfulFlush() = runBlocking {
        val t = FakeTransport { answer(hero(item(extra = sw("""{"kind":"event","event":"add_to_cart"}""")))) }
        val m = swManager(t, screenClock())
        m.getPlacements(listOf("home_hero"))
        deliverAnswer = false
        val cart = ev("add_to_cart")
        stillQueued = setOf(cart.eventId)
        var queuedFirst = false
        m.onEventTracked("add_to_cart", emptyMap(), cart) { queuedFirst = true }
        waitUntil { delivered.isNotEmpty() }
        Thread.sleep(200)
        assertTrue("the event's insert is awaited first", queuedFirst)
        assertEquals("never before the server has the event", 1, t.fetches.size)
        m.onEventsFlushed() // a flush that failed again: still queued
        Thread.sleep(200)
        assertEquals(1, t.fetches.size)
        stillQueued = emptySet()
        m.onEventsFlushed() // the next successful flush
        waitUntil { t.fetches.size == 2 }
        assertEquals(cart.eventId, t.fetches[1].getAsJsonObject("recheckEvent").get("eventId").asString)
        m.onEventsFlushed()
        Thread.sleep(200)
        assertEquals("once", 2, t.fetches.size)
    }

    private fun recheckHero(names: List<String> = listOf("add_to_cart")) =
        FakeTransport { answer(hero(extra = ""","recheckOn":[${names.joinToString(",") { "\"$it\"" }}]""")) }

    /** A pending re-check (its event still queued) for a placement that waits on [names]. */
    private fun pendingManager(t: FakeTransport, names: List<String> = listOf("add_to_cart")): Pair<PlacementManager, io.joryio.sdk.messaging.ScreenClock> = runBlocking {
        val screen = screenClock()
        val m = swManager(t, screen)
        m.getPlacements(listOf("home_hero"))
        deliverAnswer = false
        val ids = HashSet(stillQueued)
        for (n in names) {
            val e = ev(n)
            ids.add(e.eventId)
            stillQueued = HashSet(ids)
            m.onEventTracked(n, emptyMap(), e)
            Thread.sleep(30) // in order: "oldest" is well defined
        }
        waitUntil { m.pendingRecheckCount == minOf(names.size, PlacementManager.PENDING_RECHECK_MAX) && delivered.size >= names.size }
        Thread.sleep(100)
        m to screen
    }

    @Test
    fun aPendingReCheckIsDroppedAfterAnIdentitySwitch() {
        val t = recheckHero()
        val (m, _) = pendingManager(t)
        assertEquals(1, m.pendingRecheckCount)
        user = "someone-else"
        stillQueued = emptySet()
        m.onEventsFlushed()
        Thread.sleep(300)
        assertEquals("never runs for another person", 1, t.fetches.size)
        assertEquals(0, m.pendingRecheckCount)
    }

    @Test
    fun aPendingReCheckIsDroppedOnReset() {
        val t = recheckHero()
        val (m, _) = pendingManager(t)
        m.invalidate()
        assertEquals(0, m.pendingRecheckCount)
        stillQueued = emptySet()
        m.onEventsFlushed()
        Thread.sleep(300)
        assertEquals(1, t.fetches.size)
    }

    @Test
    fun aPendingReCheckIsDroppedWhenConsentIsWithdrawn() {
        val t = recheckHero()
        val (m, _) = pendingManager(t)
        consentValue = "denied"
        stillQueued = emptySet()
        m.onEventsFlushed()
        Thread.sleep(300)
        assertEquals(1, t.fetches.size)
        assertEquals(0, m.pendingRecheckCount)
    }

    @Test
    fun aPendingReCheckIsDroppedOnANewScreenAndAfterSixtySeconds() {
        val (m, screen) = pendingManager(recheckHero())
        screen.enter("Cart")
        assertEquals("a new screen", 0, m.pendingRecheckCount)

        val t2 = recheckHero()
        val (m2, _) = pendingManager(t2)
        assertEquals(1, m2.pendingRecheckCount)
        clock += 61_000
        stillQueued = emptySet()
        m2.onEventsFlushed()
        Thread.sleep(300)
        assertEquals("older than 60 s", 1, t2.fetches.size)
        assertEquals(0, m2.pendingRecheckCount)
        assertEquals(60_000L, PlacementManager.PENDING_RECHECK_MAX_AGE_MS)
    }

    @Test
    fun pendingReChecksAreCappedAtTwentyOldestDropped() {
        val names = (0 until 25).map { "e$it" }
        val t = recheckHero(names)
        val (m, _) = pendingManager(t, names)
        assertEquals(20, m.pendingRecheckCount)
        stillQueued = emptySet()
        m.onEventsFlushed()
        waitUntil { t.fetches.size == 21 }
        Thread.sleep(200)
        val sentNames = t.fetches.drop(1).map { it.getAsJsonObject("recheckEvent").get("name").asString }
        assertEquals("the oldest five were dropped", names.takeLast(20).toSet(), sentNames.toSet())
    }

    /**
     * 1.4.0-beta.9 test: one coroutine per event, each failed delivery returning
     * in its own time - the waiting list followed failure-completion order and
     * dropped the wrong ones past 20. Here the OLDEST deliveries fail LAST.
     */
    @Test
    fun pendingReChecksPastTwentyDropTheOldestTrackedEvenWhenFailuresReturnOutOfOrder() {
        val names = (0 until 25).map { "e$it" }
        val t = recheckHero(names)
        val events = names.map { ev(it) }
        val order = events.withIndex().associate { (i, e) -> e.eventId to i }
        val m = PlacementManager(
            transport = t,
            store = null,
            consent = { consentValue },
            userId = { user },
            anonymousId = { "anon-1" },
            sessionId = { session },
            capabilities = { listOf("placements", "content.native", "content.data") },
            locale = { "he-IL" },
            postMain = { it.run() },
            now = { clock },
            screenClock = screenClock(),
            // e0 fails last, e24 first.
            deliverEvent = { id -> kotlinx.coroutines.delay(20L * (25 - (order[id] ?: 0))); delivered.add(id); false },
            isEventQueued = { it in stillQueued },
            schedule = { ms, _ -> scheduled.add(ms); {} },
        )
        runBlocking { m.getPlacements(listOf("home_hero")) }
        stillQueued = events.map { it.eventId }.toSet()
        names.zip(events).forEach { (n, e) -> m.onEventTracked(n, emptyMap(), e) } // no pause between them
        waitUntil { delivered.size >= names.size }
        Thread.sleep(100)
        assertEquals(20, m.pendingRecheckCount)
        stillQueued = emptySet()
        m.onEventsFlushed()
        waitUntil { t.fetches.size == 21 }
        Thread.sleep(200)
        val sentNames = t.fetches.drop(1).map { it.getAsJsonObject("recheckEvent").get("name").asString }
        assertEquals("the five tracked first were dropped", names.takeLast(20).toSet(), sentNames.toSet())
    }

    /**
     * 1.4.0-beta.10 test (RN shop app): /track/batch failing, m1..m25 tracked,
     * flush, restore, flush. The deliveries waited behind the failing flush and
     * then SUCCEEDED - they never went through the capped waiting list, so all
     * 25 re-checks ran. Only the 20 tracked last may run, as on iOS and the web.
     */
    @Test
    fun deliveriesQueuedBehindAFailingFlushThatSucceedLaterStillHonourTheCapOfTwenty() {
        val names = (1..25).map { "m$it" }
        val t = recheckHero(names)
        val events = names.map { ev(it) }
        val restored = kotlinx.coroutines.CompletableDeferred<Unit>()
        val m = PlacementManager(
            transport = t,
            store = null,
            consent = { consentValue },
            userId = { user },
            anonymousId = { "anon-1" },
            sessionId = { session },
            capabilities = { listOf("placements", "content.native", "content.data") },
            locale = { "he-IL" },
            postMain = { it.run() },
            now = { clock },
            screenClock = screenClock(),
            // Each delivery waits behind the failing flush, then succeeds once it is restored.
            deliverEvent = { id -> delivered.add(id); restored.await(); true },
            isEventQueued = { it in stillQueued },
            schedule = { ms, _ -> scheduled.add(ms); {} },
        )
        runBlocking { m.getPlacements(listOf("home_hero")) }
        stillQueued = events.map { it.eventId }.toSet()
        names.zip(events).forEach { (n, e) -> m.onEventTracked(n, emptyMap(), e) }
        waitUntil { delivered.size >= names.size }
        assertEquals(20, m.pendingRecheckCount)
        assertEquals("nothing before the server has the events", 1, t.fetches.size)
        stillQueued = emptySet()
        restored.complete(Unit)
        m.onEventsFlushed() // the restored flush also reports in
        waitUntil { t.fetches.size >= 21 }
        Thread.sleep(300)
        assertEquals("20 re-checks, not 25", 21, t.fetches.size)
        val sentNames = t.fetches.drop(1).map { it.getAsJsonObject("recheckEvent").get("name").asString }
        assertEquals("m6..m25, each once", names.takeLast(20).sorted(), sentNames.sorted())
        assertEquals(0, m.pendingRecheckCount)
    }

    @Test
    fun aControlAnswerReportsControlViewedOnlyAfterItsMoment() = runBlocking {
        val t = FakeTransport { answer(""""home_hero":{"placementKey":"home_hero","isControl":true,"controlToken":"ctl-1","items":[],"showWhen":{"kind":"seconds","seconds":5}}""") }
        val m = swManager(t, screenClock())
        var heard = 0
        m.onPlacementsUpdated { heard++ }
        val p = m.getPlacements(listOf("home_hero"))["home_hero"]!!
        val before = heard
        p.markDisplayed()
        Thread.sleep(100)
        assertTrue("ignored before the moment", t.tracks.isEmpty())
        clock += 5_000
        m.checkShowWhen()
        assertEquals("onPlacementsUpdated at the moment", before + 1, heard)
        m.toPlacement("home_hero").markDisplayed()
        m.toPlacement("home_hero").markDisplayed()
        waitUntil { t.tracks.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf("ctl-1"), controls(t))
        assertEquals(1, t.tracks.size)
    }

    @Test
    fun deferredControlsAreReportedOnceAtTheirMomentOnAViewedSpot() = runBlocking {
        val screen = screenClock()
        val deferred = ""","deferredControls":[{"controlToken":"ctl-late","showWhen":{"kind":"seconds","seconds":8}},{"controlToken":"ctl-load","showWhen":{"kind":"load"}},{"controlToken":"ctl-scroll","showWhen":{"kind":"scroll","percent":50}}]"""
        val t = FakeTransport { answer(hero(extra = deferred)) }
        val m = swManager(t, screen)
        val p = m.getPlacements(listOf("home_hero"))["home_hero"]!!
        Thread.sleep(100)
        assertTrue("not before the spot was viewable", t.tracks.isEmpty())
        p.items[0].markDisplayed()
        waitUntil { controls(t).isNotEmpty() }
        assertEquals(listOf("ctl-load"), controls(t))
        clock += 8_000
        m.checkShowWhen()
        waitUntil { controls(t).size == 2 }
        assertEquals(setOf("ctl-load", "ctl-late"), controls(t).toSet())
        val body = t.tracks.first { it.get("controlToken")?.asString == "ctl-late" }
        assertEquals("home_hero", body.get("placementKey").asString)
        assertEquals("anon-1", body.get("anonymousId").asString)
        assertEquals("android", body.get("deviceType").asString)
        assertTrue(body.has("viewedAt"))
        // Once each, also on a new screen.
        screen.enter("Cart")
        clock += 8_000
        m.toPlacement("home_hero").markDisplayed()
        m.checkShowWhen()
        Thread.sleep(150)
        assertEquals(2, controls(t).size)
    }
}
