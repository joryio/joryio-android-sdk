package io.joryio.sdk.cards

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Content cards on Android (docs/PLACEMENTS_PLAN.md section 11): the manager's
 * rules against a fake transport (the feed endpoint is mocked). The same rules
 * as the web SDK's and iOS's card tests.
 */
class CardManagerTest {

    private class FakeTransport(var answer: (JsonObject) -> Pair<Int, String?>) : CardTransport {
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

    private class MemoryStore : CardStore {
        var feed: String? = null
        var device: String? = null
        override fun loadFeed() = feed
        override fun saveFeed(json: String?) { feed = json }
        override fun loadDeviceState() = device
        override fun saveDeviceState(json: String?) { device = json }
    }

    private var clock = 1_700_000_000_000L
    private var consentValue: String? = "granted"
    private var user: String? = "user-1"
    private var anon = "anon-1"

    private fun card(id: String, read: Boolean = false, pinned: Boolean = false, url: String? = "https://shop.example/sale") = """
        {"id":"$id","campaignId":"camp-$id","variantId":"v1","title":"Title $id","body":"Body $id","imageUrl":null,
         "buttonText":"Open","url":${if (url == null) "null" else "\"$url\""},"pinned":$pinned,
         "createdAt":"2026-11-20T10:00:00.000Z","expiresAt":"2099-12-20T10:00:00.000Z",
         "read":$read,"clicked":false,"deliveryToken":"tok-$id"}
    """.trimIndent()

    private fun feed(cards: List<String>, unread: Int, scope: String = "server", etag: String = "e1", next: String? = null) =
        200 to """{"success":true,"evaluatedAt":"2026-11-21T10:00:00.000Z","etag":"$etag","notModified":false,
            "cards":[${cards.joinToString(",")}],"unreadCount":$unread,"nextCursor":${if (next == null) "null" else "\"$next\""},
            "ttlSeconds":300,"stateScope":"$scope"}"""

    private fun manager(t: FakeTransport, store: CardStore? = null) = CardManager(
        transport = t,
        store = store,
        consent = { consentValue },
        userId = { user },
        anonymousId = { anon },
        sessionId = { "s-1" },
        capabilities = { listOf("cards", "content.native") },
        locale = { "en-US" },
        postMain = { it.run() },
        now = { clock },
    )

    private fun waitUntil(pred: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!pred() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test
    fun constantsHaveTheSharedValues() {
        assertEquals(30, CARD_MIN_REFRESH_SECONDS)
        assertEquals(300, CARD_FEED_TTL_SECONDS)
        assertEquals("cards", CARD_SDK_CAPABILITY)
        assertEquals(listOf("viewed", "read", "clicked", "dismissed"), listOf(CardActions.VIEWED, CardActions.READ, CardActions.CLICKED, CardActions.DISMISSED))
    }

    @Test
    fun readsTheFeedWithTheContractBody() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a", pinned = true), card("b", read = true)), unread = 1, next = "c1") }
        val m = manager(t)
        val f = m.getCards()
        val body = t.fetches.single()
        assertEquals("user-1", body.get("userId").asString)
        assertEquals("anon-1", body.get("anonymousId").asString)
        assertEquals("s-1", body.get("sessionId").asString)
        assertEquals("granted", body.get("trackingConsent").asString)
        assertEquals(20, body.get("limit").asInt)
        assertTrue(body.getAsJsonArray("capabilities").map { it.asString }.contains("cards"))
        assertFalse(body.has("cursor"))
        assertFalse(body.has("etag"))
        assertEquals(listOf("a", "b"), f.cards.map { it.id })
        val a = f.cards[0]
        assertEquals("camp-a", a.campaignId)
        assertEquals("v1", a.variantId)
        assertEquals("Title a", a.title)
        assertEquals("Open", a.buttonText)
        assertEquals("https://shop.example/sale", a.url)
        assertTrue(a.pinned)
        assertFalse(a.read)
        assertEquals(1, f.unreadCount)
        assertEquals("c1", f.nextCursor)
        assertEquals("server", f.stateScope)
        assertEquals(1, m.getUnreadCardCount())
    }

    @Test
    fun notModifiedKeepsTheCachedCardsAndSendsTheEtag() = runBlocking {
        var notModified = false
        val t = FakeTransport {
            if (notModified) 200 to """{"success":true,"etag":"e1","notModified":true,"cards":[],"unreadCount":0,"nextCursor":null,"ttlSeconds":300,"stateScope":"server"}"""
            else feed(listOf(card("a")), unread = 1)
        }
        val m = manager(t)
        m.getCards()
        notModified = true
        clock += 301_000
        val f = m.getCards()
        assertEquals(2, t.fetches.size)
        assertEquals("e1", t.fetches[1].get("etag").asString)
        assertEquals(listOf("a"), f.cards.map { it.id })
        assertEquals(1, f.unreadCount)
    }

    @Test
    fun a429AndSuccessFalseKeepTheCache() = runBlocking {
        var status = 0
        val t = FakeTransport {
            when (status) {
                0 -> feed(listOf(card("a")), unread = 1)
                429 -> 429 to null
                else -> 200 to """{"success":false,"reason":"evaluation_error","etag":"","cards":[],"unreadCount":0,"nextCursor":null,"ttlSeconds":300,"stateScope":"device"}"""
            }
        }
        val m = manager(t)
        m.getCards()
        status = 429
        clock += 301_000
        assertEquals(listOf("a"), m.getCards().cards.map { it.id })
        status = 200
        clock += 31_000
        val f = m.getCards()
        assertEquals(3, t.fetches.size)
        assertEquals(listOf("a"), f.cards.map { it.id })
        assertEquals("server", f.stateScope)
    }

    /** 1.4.0-beta.11 test: a failed feed request blocked the next one for the whole 30 s window. */
    @Test
    fun aFailedRequestDoesNotBlockARefetchForTheWholeWindow() = runBlocking {
        assertEquals(3, CARD_RETRY_AFTER_FAILURE_SECONDS)
        var status = 0
        val t = FakeTransport { if (status == 200) feed(listOf(card("a")), unread = 1) else status to null }
        val m = manager(t)
        assertTrue(m.getCards().cards.isEmpty())
        status = 200
        clock += 1_000
        m.getCards()
        assertEquals("inside the short backoff: not asked again", 1, t.fetches.size)
        clock += CARD_RETRY_AFTER_FAILURE_SECONDS * 1000L
        assertEquals(listOf("a"), m.getCards().cards.map { it.id })
        assertEquals(2, t.fetches.size)
        // A 429 keeps the whole window.
        status = 429
        clock += 301_000
        m.getCards()
        clock += CARD_RETRY_AFTER_FAILURE_SECONDS * 1000L + 1
        m.getCards()
        assertEquals(3, t.fetches.size)
    }

    @Test
    fun servesTheCacheForTheTtlAndCoalescesRefreshes() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a")), unread = 1) }
        val m = manager(t)
        m.getCards()
        m.getCards()
        assertEquals(1, t.fetches.size)
        m.refreshCards()
        m.refreshCards()
        Thread.sleep(150)
        // Inside CARD_MIN_REFRESH_SECONDS: deferred, not sent now.
        assertEquals(1, t.fetches.size)
    }

    @Test
    fun reportsCarryTheTokenTheActionAndTheSessionOnceEach() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a"), card("b")), unread = 2) }
        val m = manager(t)
        val f = m.getCards()
        val a = f.cards[0]
        m.markCardViewed(a)
        m.markCardViewed(a)
        m.markCardRead(a)
        m.markCardRead("a")
        m.markCardClicked(a)
        m.dismissCard("b")
        waitUntil { t.tracks.size >= 5 }
        Thread.sleep(50)
        val sent = t.tracks.map { it.get("deliveryToken").asString to it.get("action").asString }
        // In the order the reports were made (viewed before the action that implies it).
        assertEquals(
            listOf("tok-a" to "viewed", "tok-a" to "read", "tok-a" to "clicked", "tok-b" to "viewed", "tok-b" to "dismissed"),
            sent,
        )
        for (body in t.tracks) {
            assertEquals(setOf("deliveryToken", "action", "sessionId"), body.keySet())
            assertEquals("s-1", body.get("sessionId").asString)
        }
    }

    @Test
    fun reportsApplyToTheCacheAtOnceAndTellTheListeners() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a"), card("b"), card("c")), unread = 3) }
        val m = manager(t)
        m.getCards()
        val updates = ArrayList<CardFeed>()
        m.onCardsUpdated { updates.add(it) }
        m.markCardRead("a")
        assertEquals(2, m.getUnreadCardCount())
        assertTrue(m.view().cards.first { it.id == "a" }.read)
        m.markCardClicked("b")
        val b = m.view().cards.first { it.id == "b" }
        assertTrue(b.clicked)
        assertTrue(b.read)
        assertEquals(1, m.getUnreadCardCount())
        m.dismissCard("c")
        assertEquals(listOf("a", "b"), m.view().cards.map { it.id })
        assertEquals(0, m.getUnreadCardCount())
        assertEquals(3, updates.size)
        assertEquals(0, updates.last().unreadCount)
    }

    @Test
    fun onCardsUpdatedAnswersAJoryioSubscriptionWhoseCancelStopsTheUpdates() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a"), card("b")), unread = 2) }
        val m = manager(t)
        m.getCards()
        val updates = ArrayList<CardFeed>()
        val sub: io.joryio.sdk.JoryioSubscription = m.onCardsUpdated { updates.add(it) }
        m.markCardRead("a")
        assertEquals(1, updates.size)
        sub.cancel()
        sub.cancel() // twice is harmless
        m.markCardRead("b")
        assertEquals(1, updates.size)
        // close() is the same (java.io.Closeable, for use { }).
        val other = m.onCardsUpdated { updates.add(it) }
        other.use { m.dismissCard("a") }
        m.dismissCard("b")
        assertEquals(2, updates.size)
    }

    @Test
    fun anAnonymousVisitorsReadAndDismissedStayOnTheDeviceUntilReset() = runBlocking {
        user = null
        val store = MemoryStore()
        val t = FakeTransport { feed(listOf(card("a"), card("b"), card("c")), unread = 3, scope = "device") }
        val m = manager(t, store)
        m.getCards()
        m.markCardRead("a")
        m.dismissCard("b")
        assertNotNull(store.device)

        // A new launch: the server knows nothing of it, the device does.
        clock += 301_000
        val again = manager(t, store)
        store.feed = null
        val f = again.getCards()
        assertEquals(listOf("a", "c"), f.cards.map { it.id })
        assertTrue(f.cards.first { it.id == "a" }.read)
        assertEquals(1, f.unreadCount)
        assertEquals("device", f.stateScope)

        // A reset (or identify) clears the device's state.
        again.invalidate(clearDeviceState = true)
        assertNull(store.device)
        clock += 31_000
        val fresh = again.getCards()
        assertEquals(listOf("a", "b", "c"), fresh.cards.map { it.id })
        assertEquals(3, fresh.unreadCount)
    }

    @Test
    fun theSilentPushRefreshesTheFeed() = runBlocking {
        val t = FakeTransport { feed(listOf(card("a")), unread = 1) }
        val m = manager(t)
        m.onSyncPush()
        waitUntil { t.fetches.size == 1 }
        assertEquals(1, t.fetches.size)
        waitUntil { m.view().cards.isNotEmpty() }
        assertEquals(listOf("a"), m.view().cards.map { it.id })
        assertEquals("cards", CARD_SYNC_TYPE)
    }

    @Test
    fun anAnswerFromBeforeAnIdentityChangeIsDiscarded() {
        val release = CountDownLatch(1)
        val t = FakeTransport {
            release.await(3, TimeUnit.SECONDS)
            feed(listOf(card("old-person")), unread = 1)
        }
        val m = manager(t)
        m.refreshCards()
        waitUntil { t.fetches.size == 1 }
        user = "user-2"
        m.invalidate(clearDeviceState = true)
        release.countDown()
        Thread.sleep(200)
        assertTrue(m.view().cards.none { it.id == "old-person" })
        // A report for the previous person's card goes nowhere.
        m.markCardRead("old-person")
        Thread.sleep(50)
        assertTrue(t.tracks.isEmpty())
    }

    @Test
    fun withoutConsentNothingIsAskedAndNothingReported() = runBlocking {
        consentValue = "denied"
        val t = FakeTransport { feed(listOf(card("a")), unread = 1) }
        val m = manager(t)
        val f = m.getCards()
        assertTrue(t.fetches.isEmpty())
        assertTrue(f.cards.isEmpty())
        assertEquals(0, f.unreadCount)
    }

    @Test
    fun theNextPageIsAddedToTheFeed() = runBlocking {
        val t = FakeTransport { body ->
            if (body.has("cursor")) feed(listOf(card("c")), unread = 3) else feed(listOf(card("a"), card("b")), unread = 3, next = "c1")
        }
        val m = manager(t)
        m.getCards()
        val page = m.getCards("c1")
        assertEquals("c1", t.fetches[1].get("cursor").asString)
        assertFalse(t.fetches[1].has("etag"))
        assertEquals(listOf("c"), page.cards.map { it.id })
        assertNull(page.nextCursor)
        assertEquals(listOf("a", "b", "c"), m.view().cards.map { it.id })
    }

    @Test
    fun theFeedCacheSurvivesALaunchForTheSamePersonOnly() = runBlocking {
        val store = MemoryStore()
        val t = FakeTransport { feed(listOf(card("a")), unread = 1) }
        manager(t, store).getCards()
        val again = manager(t, store)
        assertEquals(listOf("a"), again.view().cards.map { it.id })
        assertEquals(1, again.getUnreadCardCount())
        user = "someone-else"
        assertTrue(manager(t, store).view().cards.isEmpty())
    }
}
