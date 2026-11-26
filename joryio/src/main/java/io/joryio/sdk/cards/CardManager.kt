package io.joryio.sdk.cards

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.joryio.sdk.placements.PlacementManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** The HTTP the manager needs; NetworkClient in the app, a fake in tests. */
internal interface CardTransport {
    /** POST v1/in-app/cards once (no retries): HTTP status (0 = no answer) and body. */
    suspend fun fetch(json: String): Pair<Int, String?>

    /** POST v1/in-app/track. */
    suspend fun track(json: String)
}

/** Where the feed and an anonymous visitor's read / dismissed state are kept between launches. */
internal interface CardStore {
    fun loadFeed(): String?
    fun saveFeed(json: String?)
    fun loadDeviceState(): String?
    fun saveDeviceState(json: String?)
}

/**
 * Fetches, caches and refreshes the person's card feed and reports what they
 * did with each card (docs/PLACEMENTS_PLAN.md section 11). The rules, the
 * same on every Joryio SDK:
 *
 * - the last feed is served from the cache (memory + disk, scoped to the
 *   person) for its `ttlSeconds` ([CARD_FEED_TTL_SECONDS]);
 * - at most one network refresh per [CARD_MIN_REFRESH_SECONDS]: a forced
 *   refresh inside that window is deferred to its end and coalesced; after a
 *   request that got no answer (or a 5xx) only [CARD_RETRY_AFTER_FAILURE_SECONDS];
 * - `notModified: true`, `success: false`, HTTP 429 or no answer keep the cache;
 * - an identity or consent change bumps a generation: an answer to a request
 *   made before it is discarded; identity changes also clear the anonymous
 *   visitor's device state;
 * - a report is applied to the cache at once and listeners are told;
 * - `stateScope: "device"` (an anonymous visitor): read / dismissed card ids
 *   are kept on the device and applied to every feed;
 * - without tracking consent nothing is requested and nothing reported (an
 *   inbox is personal: the server answers an unconsented device an empty feed).
 */
internal class CardManager(
    private val transport: CardTransport,
    private val store: CardStore?,
    /** null = nothing may be sent; "granted" | "pending" | "denied" otherwise. */
    private val consent: () -> String?,
    private val userId: () -> String?,
    private val anonymousId: () -> String,
    private val sessionId: () -> String?,
    private val capabilities: () -> List<String>,
    private val locale: () -> String? = { Locale.getDefault().toLanguageTag() },
    /** Runs a block on the main thread (listeners, views). */
    private val postMain: (Runnable) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val log: (String) -> Unit = {},
) {
    private class Inflight(val callbacks: MutableList<() -> Unit>)

    private val lock = Any()
    private val gson = Gson()

    // The feed as the server sent it (first page plus any pages loaded after it).
    private var wireCards: List<Card> = emptyList()
    private var serverUnread = 0
    private var nextCursor: String? = null
    private var stateScope = CardStateScope.SERVER
    private var etag: String? = null
    private var ttlSeconds = CARD_FEED_TTL_SECONDS
    private var fetchedAt: Long? = null
    private var lastNetworkAt: Long? = null

    private var inflight: Inflight? = null
    private var deferredScheduled = false
    private val listeners = LinkedHashSet<(CardFeed) -> Unit>()
    private val reported = HashSet<String>()

    /** What this device did since the last identity change, keyed by card id + createdAt (a replaced card is a new one). */
    private val overlayRead = HashSet<String>()
    private val overlayClicked = HashSet<String>()
    private val overlayDismissed = HashSet<String>()

    /** An anonymous visitor's state, by card id, on disk. Cleared on identify / reset. */
    private val deviceRead = LinkedHashSet<String>()
    private val deviceDismissed = LinkedHashSet<String>()

    /** The app reads cards (or did on an earlier launch): foreground refreshes are worth a request. */
    @Volatile
    private var used = false

    @Volatile
    private var generation = 0

    init {
        loadFeedCache()
        loadDeviceState()
    }

    // ── public surface (through Joryio) ─────────────────────────────────

    fun getCards(cursor: String?, callback: (CardFeed) -> Unit) {
        used = true
        if (cursor != null) {
            scope.launch {
                val page = loadPage(cursor)
                postMain(Runnable { callback(page) })
            }
            return
        }
        ensure { val feed = view(); postMain(Runnable { callback(feed) }) }
    }

    suspend fun getCards(cursor: String? = null): CardFeed {
        used = true
        if (cursor != null) return loadPage(cursor)
        suspendCancellableCoroutine<Unit> { cont -> ensure { if (cont.isActive) cont.resume(Unit) } }
        return view()
    }

    /** From the cache, never the network. */
    fun getUnreadCardCount(): Int {
        used = true
        return view().unreadCount
    }

    fun refreshCards() {
        used = true
        fetch(force = true) {}
    }

    fun onCardsUpdated(listener: (CardFeed) -> Unit): io.joryio.sdk.JoryioSubscription {
        used = true
        synchronized(lock) { listeners.add(listener) }
        return io.joryio.sdk.JoryioSubscription { synchronized(lock) { listeners.remove(listener) } }
    }

    fun markCardViewed(card: Card) = report(card.id, card.deliveryToken, CardActions.VIEWED)
    fun markCardViewed(cardId: String) = report(cardId, null, CardActions.VIEWED)
    fun markCardRead(card: Card) = report(card.id, card.deliveryToken, CardActions.READ)
    fun markCardRead(cardId: String) = report(cardId, null, CardActions.READ)
    fun markCardClicked(card: Card) = report(card.id, card.deliveryToken, CardActions.CLICKED)
    fun markCardClicked(cardId: String) = report(cardId, null, CardActions.CLICKED)
    fun dismissCard(card: Card) = report(card.id, card.deliveryToken, CardActions.DISMISSED)
    fun dismissCard(cardId: String) = report(cardId, null, CardActions.DISMISSED)

    /** The app came to the foreground: a feed the app reads whose TTL passed is asked for again. */
    fun onForeground() {
        val stale = synchronized(lock) { (used || fetchedAt != null) && !freshLocked() }
        if (stale) fetch(force = false) {}
    }

    /** The silent push `jry_sync: "cards"`: a card was added for this person. */
    fun onSyncPush() = refreshCards()

    /**
     * The person or the consent changed: forget the feed (and, for a new
     * person, the anonymous visitor's device state), tell the listeners, and
     * ask again if the app is showing cards.
     */
    fun invalidate(clearDeviceState: Boolean) {
        val (ls, refetch) = synchronized(lock) {
            generation++
            wireCards = emptyList()
            serverUnread = 0
            nextCursor = null
            stateScope = CardStateScope.SERVER
            etag = null
            ttlSeconds = CARD_FEED_TTL_SECONDS
            fetchedAt = null
            lastNetworkAt = null
            inflight = null
            reported.clear()
            overlayRead.clear()
            overlayClicked.clear()
            overlayDismissed.clear()
            if (clearDeviceState) {
                deviceRead.clear()
                deviceDismissed.clear()
            }
            listeners.toList() to listeners.isNotEmpty()
        }
        try {
            store?.saveFeed(null)
            if (clearDeviceState) store?.saveDeviceState(null)
        } catch (e: Exception) { /* memory only */ }
        if (ls.isNotEmpty()) {
            val feed = view()
            postMain(Runnable { ls.forEach { runCatching { it(feed) } } })
        }
        if (refetch) fetch(force = true) {}
    }

    // ── fetching ────────────────────────────────────────────────────────

    private fun freshLocked(): Boolean {
        val at = fetchedAt ?: return false
        return now() - at < ttlSeconds * 1000L
    }

    private fun ensure(done: () -> Unit) {
        val fresh = synchronized(lock) { freshLocked() }
        if (fresh) done() else fetch(force = false, done)
    }

    /**
     * Ask for the first page. A request already running is joined; inside
     * [CARD_MIN_REFRESH_SECONDS] of the last one nothing goes out - a forced
     * refresh is deferred to the end of that window, anything else gets the cache.
     */
    private fun fetch(force: Boolean, done: () -> Unit) {
        var start: Inflight? = null
        var fromCache = false
        synchronized(lock) {
            val running = inflight
            val last = lastNetworkAt
            when {
                running != null -> {
                    running.callbacks.add(done)
                    if (force) deferLocked()
                }
                last != null && now() - last < CARD_MIN_REFRESH_SECONDS * 1000L -> {
                    if (force) deferLocked()
                    fromCache = true
                }
                else -> {
                    val created = Inflight(arrayListOf(done))
                    inflight = created
                    start = created
                }
            }
        }
        if (fromCache) {
            done()
            return
        }
        val mine = start ?: return
        scope.launch {
            try {
                requestFirstPage()
            } finally {
                val callbacks = synchronized(lock) {
                    if (inflight === mine) inflight = null
                    mine.callbacks.toList()
                }
                callbacks.forEach { runCatching { it() } }
            }
        }
    }

    private fun deferLocked() {
        if (deferredScheduled) return
        deferredScheduled = true
        val wait = maxOf(0L, (lastNetworkAt ?: 0L) + CARD_MIN_REFRESH_SECONDS * 1000L - now()) + 10
        scope.launch {
            delay(wait)
            synchronized(lock) { deferredScheduled = false }
            fetch(force = false) {}
        }
    }

    private fun requestBody(cursor: String?, consentNow: String): JsonObject = JsonObject().apply {
        userId()?.takeIf { it.isNotEmpty() }?.let { addProperty("userId", it) }
        addProperty("anonymousId", anonymousId())
        sessionId()?.let { addProperty("sessionId", it) }
        addProperty("deviceType", "android")
        locale()?.let { addProperty("locale", it) }
        add("capabilities", JsonArray().apply { capabilities().forEach { add(it) } })
        addProperty("trackingConsent", consentNow)
        addProperty("limit", PAGE_SIZE)
        cursor?.let { addProperty("cursor", it) }
        if (cursor == null) synchronized(lock) { etag }?.takeIf { it.isNotEmpty() }?.let { addProperty("etag", it) }
    }

    /** One POST; null when there is nothing to use (no consent, failure, 429, success false, a stale answer). */
    private suspend fun post(cursor: String?): Pair<JsonObject, Int>? {
        val consentNow = consent() ?: return null
        // An inbox is personal: without consent the server would answer an
        // empty feed, so nothing is asked (and nobody named).
        if (consentNow != "granted") return null
        val generationAtStart = generation
        val sentAt = now()
        if (cursor == null) synchronized(lock) { lastNetworkAt = sentAt }
        val body = requestBody(cursor, consentNow)
        val (status, text) = try {
            transport.fetch(gson.toJson(body))
        } catch (e: Exception) {
            0 to null
        }
        if (status !in 200..299 || text == null) {
            log("Card feed request answered $status; keeping the cached feed")
            // No answer (status 0) or a server error: the next refresh may go after
            // CARD_RETRY_AFTER_FAILURE_SECONDS, not the whole window (1.4.0-beta.12).
            if (cursor == null && (status == 0 || status >= 500)) synchronized(lock) {
                if (lastNetworkAt == sentAt) {
                    lastNetworkAt = sentAt - (CARD_MIN_REFRESH_SECONDS - CARD_RETRY_AFTER_FAILURE_SECONDS) * 1000L
                }
            }
            return null
        }
        if (generation != generationAtStart) {
            log("Discarding a card feed answer that predates an identity or consent change")
            return null
        }
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (root.bool("success") == false) {
            log("Card feed answered success: false; keeping the cached feed")
            return null
        }
        return root to generationAtStart
    }

    private suspend fun requestFirstPage() {
        val (root, gen) = post(null) ?: return
        val changed: Boolean
        val ls: List<(CardFeed) -> Unit>
        synchronized(lock) {
            if (generation != gen) return
            val t = now()
            ttlSeconds = root.int("ttlSeconds")?.takeIf { it > 0 } ?: CARD_FEED_TTL_SECONDS
            if (root.bool("notModified") == true && fetchedAt != null) {
                // The feed we hold is still the feed: keep the cards.
                fetchedAt = t
                changed = false
            } else {
                val before = viewLocked()
                wireCards = parseCards(root)
                serverUnread = root.int("unreadCount")?.coerceAtLeast(0) ?: 0
                nextCursor = root.str("nextCursor")
                stateScope = if (root.str("stateScope") == CardStateScope.DEVICE) CardStateScope.DEVICE else CardStateScope.SERVER
                etag = root.str("etag")
                fetchedAt = t
                changed = before != viewLocked()
            }
            ls = listeners.toList()
        }
        persistFeed()
        if (changed && ls.isNotEmpty()) notify(ls)
    }

    /** The page after [cursor]: added to the cached feed; returns that page (with this device's state applied). */
    private suspend fun loadPage(cursor: String): CardFeed {
        val (root, gen) = post(cursor) ?: return view().copy(cards = emptyList())
        val ls: List<(CardFeed) -> Unit>
        val page: CardFeed
        synchronized(lock) {
            if (generation != gen) return view().copy(cards = emptyList())
            val cards = parseCards(root)
            val known = wireCards.map { it.id }.toHashSet()
            wireCards = wireCards + cards.filter { it.id !in known }
            nextCursor = root.str("nextCursor")
            root.int("unreadCount")?.let { serverUnread = it.coerceAtLeast(0) }
            val full = viewLocked()
            val ids = cards.map { it.id }.toHashSet()
            page = full.copy(cards = full.cards.filter { it.id in ids })
            ls = listeners.toList()
        }
        if (ls.isNotEmpty()) notify(ls)
        return page
    }

    private fun notify(ls: List<(CardFeed) -> Unit>) {
        val feed = view()
        postMain(Runnable { ls.forEach { runCatching { it(feed) } } })
    }

    // ── the feed with this device's state ───────────────────────────────

    private fun overlayKey(c: Card) = "${c.id}\u0000${c.createdAt}"

    internal fun view(): CardFeed = synchronized(lock) { viewLocked() }

    /**
     * The cached feed with what this device did applied: dismissed and
     * expired cards removed, read / clicked set, and the unread count
     * lowered by each card the server counted as unread that is no longer.
     */
    private fun viewLocked(): CardFeed {
        val t = now()
        var unread = serverUnread
        val out = ArrayList<Card>(wireCards.size)
        for (c in wireCards) {
            val key = overlayKey(c)
            val dismissed = key in overlayDismissed || c.id in deviceDismissed
            val expired = c.expiresAt?.let { PlacementManager.parseIso(it) }?.let { it <= t } == true
            val clicked = c.clicked || key in overlayClicked
            val read = c.read || clicked || key in overlayRead || c.id in deviceRead
            if (!c.read && (read || dismissed || expired)) unread--
            if (dismissed || expired) continue
            out.add(if (read == c.read && clicked == c.clicked) c else c.copy(read = read, clicked = clicked))
        }
        return CardFeed(out, unread.coerceAtLeast(0), nextCursor, stateScope)
    }

    // ── reporting ───────────────────────────────────────────────────────

    /**
     * A report: applied to the cache at once (listeners told), sent to
     * v1/in-app/track only with consent. Each action at most once per card;
     * read / clicked / dismissed first count the card as viewed; `read` on a
     * card already read sends nothing.
     */
    private fun report(cardId: String, token: String?, action: String) {
        val toSend = ArrayList<String>()
        val ls: List<(CardFeed) -> Unit>
        val card: Card
        var deviceChanged = false
        synchronized(lock) {
            // A card of someone who is no longer here (identify / reset since), or unknown: nothing.
            val cached = wireCards.firstOrNull { it.id == cardId }
            if (cached == null || (token != null && token != cached.deliveryToken)) {
                log("Card report ignored: $cardId is not in the current feed")
                return
            }
            card = cached
            val key = overlayKey(card)
            val alreadyRead = card.read || card.clicked || key in overlayRead || key in overlayClicked || card.id in deviceRead
            if (action == CardActions.READ && alreadyRead) {
                reported.add("${CardActions.READ}:${card.id}")
                return
            }
            if (!reported.add("$action:${card.id}")) return
            if (action != CardActions.VIEWED && reported.add("${CardActions.VIEWED}:${card.id}")) toSend.add(CardActions.VIEWED)
            toSend.add(action)
            val device = stateScope == CardStateScope.DEVICE
            when (action) {
                CardActions.READ -> {
                    overlayRead.add(key)
                    if (device) deviceChanged = deviceRead.add(card.id)
                }
                CardActions.CLICKED -> {
                    overlayClicked.add(key)
                    overlayRead.add(key)
                    reported.add("${CardActions.READ}:${card.id}")
                    if (device) deviceChanged = deviceRead.add(card.id)
                }
                CardActions.DISMISSED -> {
                    overlayDismissed.add(key)
                    if (device) deviceChanged = deviceDismissed.add(card.id)
                }
            }
            ls = if (action == CardActions.VIEWED) emptyList() else listeners.toList()
        }
        if (deviceChanged) persistDeviceState()
        if (ls.isNotEmpty()) notify(ls)
        if (consent() != "granted") return
        val session = sessionId()
        send(toSend.map { a ->
            JsonObject().apply {
                addProperty("deliveryToken", card.deliveryToken)
                addProperty("action", a)
                session?.let { addProperty("sessionId", it) }
            }
        })
    }

    /** The last report on its way: the next one waits for it. */
    private var lastReport: Job? = null

    /**
     * Reports reach the server in the order they were made - `viewed` before
     * the `read` / `dismissed` it implies, across calls too: each report's
     * coroutine waits for the one before it.
     */
    private fun send(bodies: List<JsonObject>) {
        if (bodies.isEmpty()) return
        val jsons = bodies.map { gson.toJson(it) }
        synchronized(reportLock) {
            val previous = lastReport
            lastReport = scope.launch {
                previous?.join()
                sendInOrder(jsons)
            }
        }
    }

    private val reportLock = Any()

    private suspend fun sendInOrder(jsons: List<String>) {
        for (json in jsons) {
            try {
                transport.track(json)
            } catch (e: Exception) {
                log("Card report failed: ${e.message}")
            }
        }
    }

    // ── parsing + disk ──────────────────────────────────────────────────

    private fun parseCards(root: JsonObject): List<Card> =
        (root.get("cards")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject?.let { parseCard(it) } }

    private fun parseCard(o: JsonObject): Card? {
        val id = o.str("id") ?: return null
        val token = o.str("deliveryToken") ?: return null
        return Card(
            id = id,
            campaignId = o.str("campaignId") ?: "",
            variantId = o.str("variantId"),
            title = o.str("title"),
            body = o.str("body"),
            imageUrl = o.str("imageUrl"),
            buttonText = o.str("buttonText"),
            url = o.str("url"),
            pinned = o.bool("pinned") == true,
            createdAt = o.str("createdAt") ?: "",
            expiresAt = o.str("expiresAt"),
            read = o.bool("read") == true,
            clicked = o.bool("clicked") == true,
            deliveryToken = token,
        )
    }

    private fun subjectKey() = "${userId() ?: ""}|${anonymousId()}"

    private fun persistFeed() {
        val json = synchronized(lock) {
            val cards = JsonArray()
            wireCards.forEach { c -> cards.add(c.toJsonObject().apply { addProperty("deliveryToken", c.deliveryToken) }) }
            gson.toJson(JsonObject().apply {
                addProperty("subject", subjectKey())
                addProperty("fetchedAt", fetchedAt ?: 0L)
                addProperty("etag", etag)
                addProperty("ttlSeconds", ttlSeconds)
                addProperty("unreadCount", serverUnread)
                addProperty("nextCursor", nextCursor)
                addProperty("stateScope", stateScope)
                add("cards", cards)
            })
        }
        try { store?.saveFeed(json) } catch (e: Exception) { /* memory only */ }
    }

    private fun loadFeedCache() {
        val raw = try { store?.loadFeed() } catch (e: Exception) { null } ?: return
        try {
            val root = JsonParser.parseString(raw).asJsonObject
            // Another person's feed (a reset or a login since) is not this one's.
            if (root.str("subject") != subjectKey()) return
            wireCards = parseCards(root)
            serverUnread = root.int("unreadCount") ?: 0
            nextCursor = root.str("nextCursor")
            stateScope = if (root.str("stateScope") == CardStateScope.DEVICE) CardStateScope.DEVICE else CardStateScope.SERVER
            etag = root.str("etag")
            ttlSeconds = root.int("ttlSeconds")?.takeIf { it > 0 } ?: CARD_FEED_TTL_SECONDS
            fetchedAt = root.get("fetchedAt")?.takeIf { it.isJsonPrimitive }?.asLong
        } catch (e: Exception) {
            wireCards = emptyList()
        }
    }

    private fun persistDeviceState() {
        val json = synchronized(lock) {
            while (deviceRead.size > DEVICE_STATE_MAX) deviceRead.remove(deviceRead.first())
            while (deviceDismissed.size > DEVICE_STATE_MAX) deviceDismissed.remove(deviceDismissed.first())
            gson.toJson(JsonObject().apply {
                addProperty("anonymousId", anonymousId())
                add("read", JsonArray().apply { deviceRead.forEach { add(it) } })
                add("dismissed", JsonArray().apply { deviceDismissed.forEach { add(it) } })
            })
        }
        try { store?.saveDeviceState(json) } catch (e: Exception) { /* memory only */ }
    }

    private fun loadDeviceState() {
        val raw = try { store?.loadDeviceState() } catch (e: Exception) { null } ?: return
        try {
            val root = JsonParser.parseString(raw).asJsonObject
            // Kept for one anonymous visitor; a new one starts clean.
            if (root.str("anonymousId") != anonymousId()) return
            root.get("read")?.asJsonArray?.forEach { deviceRead.add(it.asString) }
            root.get("dismissed")?.asJsonArray?.forEach { deviceDismissed.add(it.asString) }
        } catch (e: Exception) {
            deviceRead.clear()
            deviceDismissed.clear()
        }
    }

    companion object {
        /** Cards per request (the server's default page). */
        internal const val PAGE_SIZE = 20

        /** Read / dismissed ids an anonymous device keeps (oldest dropped first). */
        internal const val DEVICE_STATE_MAX = 500

        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    }
}
