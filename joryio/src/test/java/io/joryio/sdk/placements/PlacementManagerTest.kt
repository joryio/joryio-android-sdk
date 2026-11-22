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
}
