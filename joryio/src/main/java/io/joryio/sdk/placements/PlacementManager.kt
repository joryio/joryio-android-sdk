package io.joryio.sdk.placements

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/** One item as the server sends it. */
internal class PlacementItemWire(
    val id: String,
    val variantId: String?,
    val deliveryToken: String?,
    val deliveryId: String?,
    val priority: Int?,
    val kind: String,
    val data: Map<String, Any?>,
    val content: PlacementContent?,
    /** Products picked for the person by a recommendation model (D444 Phase 2b). */
    val products: List<PlacementProduct>,
    /** Event triggers: `{ type, event, attribute?, operator?, value? }`. */
    val triggers: List<Map<String, Any?>>,
    val triggerWindowSeconds: Int?,
    val expiresAt: String?,
    /** The raw JSON, kept for the disk cache and for change detection. */
    val json: JsonObject,
    /** "Show when" (D465): the moment it is revealed on a screen; absent = load. */
    val showWhen: ShowWhenMoment = ShowWhenMoment.Load,
)

/** A control exposure that waits for its own Show when moment (D465). */
internal class DeferredControl(val controlToken: String, val showWhen: ShowWhenMoment)

/** One placement as the server sends it. */
internal class PlacementWire(
    val placementKey: String,
    val emptyBehavior: String,
    val maxItems: Int,
    val ttlSeconds: Int,
    val isControl: Boolean,
    val controlToken: String?,
    val items: List<PlacementItemWire>,
    val json: JsonObject,
    /**
     * A control answer's "Show when" (D465): the moment the held-out treatment
     * would have been revealed - `control_viewed` waits for it.
     */
    val showWhen: ShowWhenMoment = ShowWhenMoment.Load,
    /**
     * Control exposures of A/B-control campaigns that fell through to the drawn
     * item at a different moment: each reported once at ITS moment, once the spot was viewable.
     */
    val deferredControls: List<DeferredControl> = emptyList(),
    /** Event names that, when tracked, make the SDK re-fetch this key at once. */
    val recheckOn: List<String> = emptyList(),
)

/** The HTTP the manager needs; NetworkClient in the app, a fake in tests. */
internal interface PlacementTransport {
    /** POST v1/in-app/placements once (no retries): HTTP status (0 = no answer) and body. */
    suspend fun fetch(json: String): Pair<Int, String?>

    /** POST v1/in-app/track. */
    suspend fun track(json: String)
}

/** Where the answers are kept between launches (beside the SDK's other storage). */
internal interface PlacementCacheStore {
    fun load(): String?
    fun save(json: String?)
}

/**
 * Fetches, caches and refreshes placements, and reports what the person did
 * with them. The rules (docs/PLACEMENTS_PLAN.md 3.5-3.9), the same on every
 * Joryio SDK:
 *
 * - keys asked for within PLACEMENT_BATCH_WINDOW_MS go in one request, at most
 *   PLACEMENT_MAX_KEYS_PER_REQUEST keys each;
 * - an answer is served from the cache for its ttlSeconds (memory + disk,
 *   scoped to the person);
 * - a key goes to the network at most once per PLACEMENT_MIN_REFRESH_SECONDS;
 *   a forced refresh inside that window is deferred and coalesced; a 429 or a
 *   failure keeps the cache;
 * - an identity or consent change bumps a generation: answers to requests made
 *   before it are discarded;
 * - without consent the request names nobody and nothing is reported;
 * - "Show when" (BACKLOG D465, packages/backend/src/common/show-when.ts): an
 *   item, a control answer and each deferred control carry the moment they are
 *   revealed on a screen. The content is fetched as before and HELD - left out
 *   of every Placement - until its moment: `seconds` = foreground seconds on
 *   the current screen (the time_on_screen ScreenClock), `event` = the event
 *   was tracked in this visit (SDK session). `scroll` / `exit` / unknown are
 *   never shown. Revealed content stays for the screen; a new screen starts
 *   the moment again. An event that something waits on (or that the answer
 *   lists in `recheckOn`) re-checks the key at once, past
 *   PLACEMENT_MIN_REFRESH_SECONDS, at most once per (key, event) per screen:
 *   only after the event has left the queue (sent by the flush in flight or
 *   one more; after a failure, once after the next successful flush), in a
 *   request of its own that carries the event as
 *   `recheckEvent: { name, eventId, properties }`.
 */
internal class PlacementManager(
    private val transport: PlacementTransport,
    private val store: PlacementCacheStore?,
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
    /** The time_on_screen clock: `seconds` moments count on it. */
    private val screenClock: io.joryio.sdk.messaging.ScreenClock = io.joryio.sdk.messaging.ScreenClock(now),
    /** Sends the queue until this event (client eventId) has left it; false = still queued. */
    private val deliverEvent: suspend (String) -> Boolean = { true },
    /** Is this event still queued? (a pending re-check runs once it is not) */
    private val isEventQueued: suspend (String) -> Boolean = { false },
    /** Runs a block after a delay (ms); returns its cancel. A seam for tests. */
    private val schedule: ((Long, () -> Unit) -> (() -> Unit))? = null,
) : PlacementReporter {

    private class Entry(val placement: PlacementWire, val fetchedAt: Long)

    private val lock = Any()
    private val cache = HashMap<String, Entry>()
    private val lastNetworkAt = HashMap<String, Long>()
    private val inflight = HashMap<String, MutableList<() -> Unit>>()
    private val batchKeys = LinkedHashSet<String>()
    private var batchScheduled = false
    private val deferredKeys = LinkedHashSet<String>()
    private var deferredScheduled = false
    private val listeners = LinkedHashSet<(Map<String, Placement>) -> Unit>()
    private val hosts = LinkedHashSet<PlacementHost>()
    private val reported = HashSet<String>()
    private val triggeredAt = HashMap<String, Long>()
    private val dismissedLocally = HashSet<String>()

    // "Show when" (D465)
    private enum class MomentKind { ITEM, CONTROL, DEFERRED }
    private data class MomentId(val kind: MomentKind, val key: String, val token: String)

    /** Moments reached on the current screen (cleared by a new screen). */
    private val revealed = HashSet<MomentId>()
    /** Keys whose spot was viewable on the current screen (deferred controls wait for it). */
    private val viewedOnScreen = HashSet<String>()
    /** `key\u0000event` re-checked on the current screen. */
    private val rechecked = HashSet<String>()
    /** Event names tracked in the visit [sessionEventsId]. */
    private val sessionEvents = HashSet<String>()
    private var sessionEventsId: String? = null
    /** Re-checks whose event is still queued (a failed flush): run once after a flush sent it. */
    private val pendingRechecks = ArrayList<Recheck>()
    /** Answers apply in request order: an older request answering last never overwrites a newer one. */
    private var requestSeq = 0
    private val appliedSeq = HashMap<String, Int>()

    /**
     * [event]: `{ name, eventId, properties }`, the event as queued; null without
     * the event. [subject], [consent] and [generation]: who and under which
     * consent it was queued for - it never runs for anyone else.
     */
    private class Recheck(
        val keys: List<String>,
        val eventId: String?,
        val event: JsonObject?,
        val subject: String,
        val consent: String?,
        val generation: Int,
        val queuedAt: Long,
        /** Tracking order, stamped when the event is tracked: "oldest" past the cap means tracked first. */
        val seq: Long,
    )
    private var recheckSeq = 0L
    private var cancelMomentTimer: (() -> Unit)? = null

    @Volatile
    private var generation = 0
    private val gson = Gson()

    init {
        loadCache()
        synchronized(lock) { updateMomentsLocked() }
        screenClock.observe { change ->
            when (change) {
                io.joryio.sdk.messaging.ScreenClock.Change.SCREEN -> screenChanged()
                io.joryio.sdk.messaging.ScreenClock.Change.PAUSED -> stopMomentTimer()
                io.joryio.sdk.messaging.ScreenClock.Change.RESUMED -> scheduleMomentTimer()
            }
        }
        scheduleMomentTimer()
    }

    // ── public surface (through Joryio) ─────────────────────────────────

    fun getPlacements(keys: List<String>, callback: (Map<String, Placement>) -> Unit) {
        val wanted = cleanKeys(keys)
        ensure(wanted) {
            val out = wanted.associateWith { toPlacement(it) }
            postMain(Runnable { callback(out) })
        }
    }

    suspend fun getPlacements(keys: List<String>): Map<String, Placement> =
        suspendCancellableCoroutine { cont ->
            val wanted = cleanKeys(keys)
            ensure(wanted) { cont.resume(wanted.associateWith { toPlacement(it) }) }
        }

    fun refreshPlacements(keys: List<String>?) {
        val wanted = if (keys == null) knownKeys() else cleanKeys(keys)
        if (wanted.isNotEmpty()) fetchKeys(wanted, force = true) {}
    }

    fun onPlacementsUpdated(listener: (Map<String, Placement>) -> Unit): io.joryio.sdk.JoryioSubscription {
        synchronized(lock) { listeners.add(listener) }
        return io.joryio.sdk.JoryioSubscription { synchronized(lock) { listeners.remove(listener) } }
    }

    /** A view starts showing [host]'s placement: drawn from the cache, fetched when stale. */
    fun attach(host: PlacementHost) {
        val key = host.placementKey
        if (!validKey(key)) return
        synchronized(lock) { hosts.add(host) }
        val fresh = synchronized(lock) { freshEntry(key) != null }
        if (fresh) {
            val placement = toPlacement(key)
            postMain(Runnable { host.onPlacementUpdated(placement) })
        }
        ensure(listOf(key)) {
            if (!fresh) {
                val placement = toPlacement(key)
                postMain(Runnable { host.onPlacementUpdated(placement) })
            }
        }
    }

    fun detach(host: PlacementHost) {
        synchronized(lock) { hosts.remove(host) }
    }

    /** The app came to the foreground: placements on screen whose TTL passed are asked for again. */
    fun onForeground() {
        val stale = synchronized(lock) { hosts.map { it.placementKey }.distinct().filter { freshEntry(it) == null } }
        if (stale.isNotEmpty()) fetchKeys(stale, force = false) {}
    }

    /** The silent push `jry_sync: "placements"` (or `in_app`): ask again for what is on screen. */
    fun onSyncPush() = refreshPlacements(null)

    /** The person or the consent changed: forget every answer, ask again for what is on screen. */
    fun invalidate() {
        val keys: List<String>
        val toClear: List<PlacementHost>
        synchronized(lock) {
            generation++
            cache.clear()
            lastNetworkAt.clear()
            triggeredAt.clear()
            dismissedLocally.clear()
            reported.clear()
            revealed.clear()
            viewedOnScreen.clear()
            rechecked.clear()
            pendingRechecks.clear()
            toClear = hosts.toList()
            keys = toClear.map { it.placementKey }.distinct()
        }
        store?.save(null)
        // The previous person's content goes now, on screen or not.
        if (toClear.isNotEmpty()) postMain(Runnable { toClear.forEach { runCatching { it.clearPlacement() } } })
        if (keys.isNotEmpty()) fetchKeys(keys, force = true) {}
    }

    /**
     * An event was tracked: event-triggered items waiting on it become eligible
     * (no round trip); "Show when" `event` moments are reached, and the keys
     * waiting on it are re-checked. [event]: the event as queued - its eventId
     * gates the re-check and rides on it; [beforeRecheck] runs first (the
     * event's insert into the queue).
     */
    fun onEventTracked(
        eventName: String,
        properties: Map<String, Any?>,
        event: io.joryio.sdk.models.Event? = null,
        beforeRecheck: (suspend () -> Unit)? = null,
    ) {
        val changed = LinkedHashSet<String>()
        val recheck = ArrayList<String>()
        var seq = 0L
        synchronized(lock) {
            for ((key, entry) in cache) {
                for (item in entry.placement.items) {
                    val match = item.triggers.any {
                        it["type"] == "event" && it["event"] == eventName && triggerMatches(it, properties)
                    }
                    if (match) {
                        triggeredAt[handle(key, item)] = now()
                        changed.add(key)
                    }
                }
            }
            // Show when: the keys that wait on this event, BEFORE it counts.
            val moment = ShowWhenMoment.Event(eventName)
            for ((key, entry) in cache) {
                val p = entry.placement
                val waits = eventName in p.recheckOn ||
                    p.items.any { it.showWhen == moment && itemMoment(key, it) !in revealed } ||
                    (p.isControl && p.showWhen == moment && controlMoment(key, p) !in revealed) ||
                    p.deferredControls.any { it.showWhen == moment && MomentId(MomentKind.DEFERRED, key, it.controlToken) !in revealed }
                if (waits && rechecked.add("$key\u0000$eventName")) recheck.add(key)
            }
            visitEventsLocked()
            sessionEvents.add(eventName)
            changed.addAll(updateMomentsLocked())
            if (recheck.isNotEmpty()) seq = ++recheckSeq
        }
        if (changed.isNotEmpty()) applyChanged(changed.toList())
        reportDueDeferredControls()
        if (recheck.isNotEmpty()) {
            val rc = Recheck(recheck, event?.eventId, event?.let { recheckEventJson(it) }, subjectKey(), consent(), generation, now(), seq)
            scope.launch {
                try { beforeRecheck?.invoke() } catch (e: Exception) { log("Queueing before a re-check failed: ${e.message}") }
                startRecheck(rc)
            }
        }
    }

    /** The server must have the event before it answers again. */
    private suspend fun startRecheck(rc: Recheck) {
        val id = rc.eventId
        if (id != null && !(try { deliverEvent(id) } catch (e: Exception) { false })) {
            // Still queued (the flush failed): once, after the next flush that sends it.
            // Kept in tracking order (each delivery returns in its own time), so the
            // one dropped past the cap is the OLDEST tracked, as on iOS.
            synchronized(lock) {
                val at = pendingRechecks.indexOfFirst { it.seq > rc.seq }
                if (at < 0) pendingRechecks.add(rc) else pendingRechecks.add(at, rc)
                while (pendingRechecks.size > PENDING_RECHECK_MAX) pendingRechecks.removeAt(0)
            }
            return
        }
        runRecheck(rc)
    }

    /** The event queue finished a flush: re-checks whose event has left it run now, once. */
    fun onEventsFlushed() {
        // Too old, or queued for someone else / another consent: dropped, never run.
        val waiting = synchronized(lock) {
            pendingRechecks.removeAll { !stillValidLocked(it) }
            pendingRechecks.toList()
        }
        if (waiting.isEmpty()) return
        scope.launch {
            val due = waiting.filter { rc -> rc.eventId?.let { !(try { isEventQueued(it) } catch (e: Exception) { true }) } ?: true }
            val taken = synchronized(lock) { due.filter { pendingRechecks.remove(it) } }
            taken.forEach { runRecheck(it) }
        }
    }

    /** Re-checks waiting for their event (test seam). */
    internal val pendingRecheckCount: Int get() = synchronized(lock) { pendingRechecks.size }

    /** Lock held. The same person, consent and generation, and not older than the limit. */
    private fun stillValidLocked(rc: Recheck): Boolean =
        rc.generation == generation && now() - rc.queuedAt <= PENDING_RECHECK_MAX_AGE_MS &&
            rc.subject == subjectKey() && rc.consent == consent()

    /** A request of its own (never batched with other keys), past the refresh limit. */
    private suspend fun runRecheck(rc: Recheck) {
        if (!synchronized(lock) { stillValidLocked(rc) }) return
        rc.keys.chunked(PLACEMENT_MAX_KEYS_PER_REQUEST).forEach { request(it, rc.event) }
    }

    /** `{ name, eventId, properties }` from the event as the queue serializes it. */
    private fun recheckEventJson(event: io.joryio.sdk.models.Event): JsonObject = JsonObject().apply {
        addProperty("name", event.type)
        addProperty("eventId", event.eventId)
        // WireJson, as the queue writes the event: a whole number goes as 3, not 3.0.
        add("properties", io.joryio.sdk.network.WireJson.gson.toJsonTree(event.properties).takeIf { it.isJsonObject } ?: JsonObject())
    }

    // ── fetching ────────────────────────────────────────────────────────

    private fun validKey(key: String?): Boolean = key != null && KEY_PATTERN.matches(key)

    private fun cleanKeys(keys: List<String>): List<String> =
        keys.filter { validKey(it) }.distinct().also { kept ->
            if (kept.size != keys.size) log("Ignoring keys that are not placement keys")
        }

    private fun knownKeys(): List<String> = synchronized(lock) {
        (hosts.map { it.placementKey } + cache.keys).distinct()
    }

    private fun freshEntry(key: String): Entry? {
        val entry = cache[key] ?: return null
        return if (now() - entry.fetchedAt < entry.placement.ttlSeconds * 1000L) entry else null
    }

    private fun ensure(keys: List<String>, done: () -> Unit) {
        val stale = synchronized(lock) { keys.filter { freshEntry(it) == null } }
        if (stale.isEmpty()) done() else fetchKeys(stale, force = false, done = done)
    }

    /**
     * Fetch [keys], batched with every other key asked for in the next
     * PLACEMENT_BATCH_WINDOW_MS. A key fetched less than
     * PLACEMENT_MIN_REFRESH_SECONDS ago is not fetched again: a forced refresh
     * is deferred to when the window opens, anything else gets the cache.
     */
    private fun fetchKeys(keys: List<String>, force: Boolean, done: () -> Unit) {
        var pending = 0
        val waiters = ArrayList<String>()
        synchronized(lock) {
            val t = now()
            for (key in keys) {
                val running = inflight[key]
                if (running != null) {
                    waiters.add(key)
                    if (force) defer(key)
                    continue
                }
                val last = lastNetworkAt[key]
                if (last != null && t - last < PLACEMENT_MIN_REFRESH_SECONDS * 1000L) {
                    if (force) defer(key)
                    continue
                }
                batchKeys.add(key)
                inflight[key] = ArrayList()
                waiters.add(key)
            }
            pending = waiters.size
            if (pending > 0) {
                val countDown = object : () -> Unit {
                    var left = pending
                    override fun invoke() {
                        val fire = synchronized(lock) { left--; left == 0 }
                        if (fire) done()
                    }
                }
                waiters.forEach { inflight[it]?.add(countDown) }
            }
            if (batchKeys.isNotEmpty() && !batchScheduled) {
                batchScheduled = true
                scope.launch {
                    delay(PLACEMENT_BATCH_WINDOW_MS)
                    flushBatch()
                }
            }
        }
        if (pending == 0) done()
    }

    private fun defer(key: String) {
        deferredKeys.add(key)
        if (deferredScheduled) return
        deferredScheduled = true
        val oldest = deferredKeys.minOf { lastNetworkAt[it] ?: 0L }
        val wait = maxOf(0L, oldest + PLACEMENT_MIN_REFRESH_SECONDS * 1000L - now()) + 10
        scope.launch {
            delay(wait)
            val keys = synchronized(lock) {
                deferredScheduled = false
                deferredKeys.toList().also { deferredKeys.clear() }
            }
            if (keys.isNotEmpty()) fetchKeys(keys, force = false) {}
        }
    }

    private suspend fun flushBatch() {
        val keys = synchronized(lock) {
            batchScheduled = false
            batchKeys.toList().also { batchKeys.clear() }
        }
        try {
            keys.chunked(PLACEMENT_MAX_KEYS_PER_REQUEST).forEach { request(it) }
        } finally {
            val callbacks = synchronized(lock) { keys.flatMap { inflight.remove(it) ?: emptyList() } }
            callbacks.forEach { runCatching { it() } }
        }
    }

    private suspend fun request(keys: List<String>, recheckEvent: JsonObject? = null) {
        val consentNow = consent() ?: return
        val personal = consentNow == "granted"
        val generationAtStart = generation
        val body = JsonObject()
        val shown = JsonObject()
        val seq: Int
        synchronized(lock) {
            seq = ++requestSeq
            val t = now()
            keys.forEach { lastNetworkAt[it] = t }
            for (host in hosts) {
                if (host.placementKey in keys) {
                    val ids = runCatching { host.shownIds() }.getOrDefault(emptyList())
                    if (ids.isNotEmpty()) shown.add(host.placementKey, JsonArray().apply { ids.forEach { add(it) } })
                }
            }
        }
        if (personal) {
            userId()?.takeIf { it.isNotEmpty() }?.let { body.addProperty("userId", it) }
            body.addProperty("anonymousId", anonymousId())
            sessionId()?.let { body.addProperty("sessionId", it) }
        }
        body.addProperty("deviceType", "android")
        locale()?.let { body.addProperty("locale", it) }
        body.add("capabilities", JsonArray().apply { capabilities().forEach { add(it) } })
        body.addProperty("trackingConsent", consentNow)
        body.add("placements", JsonArray().apply { keys.forEach { add(it) } })
        body.add("shown", shown)
        body.add("context", JsonObject().apply { add("screen", null); add("url", null) })
        // A Show when re-check: the event that triggered it, counted by the server as arriving now.
        recheckEvent?.let { body.add("recheckEvent", it) }

        val (status, text) = try {
            transport.fetch(gson.toJson(body))
        } catch (e: Exception) {
            0 to null
        }
        if (status !in 200..299 || text == null) {
            log("Placements request answered $status; keeping the cached placements")
            return
        }
        if (generation != generationAtStart) {
            log("Discarding a placements answer that predates an identity or consent change")
            return
        }
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            return
        }
        if (root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) return
        val placements = root.get("placements")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val changed = ArrayList<String>()
        synchronized(lock) {
            if (generation != generationAtStart) return
            val fetchedAt = now()
            for (key in keys) {
                // An answer to an older request that arrived after a newer one: not applied.
                if ((appliedSeq[key] ?: 0) > seq) continue
                appliedSeq[key] = seq
                // A key the server left out is an empty placement, never an error.
                val json = placements.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: JsonObject().apply { addProperty("placementKey", key); add("items", JsonArray()) }
                val wire = parsePlacement(key, json)
                val before = cache[key]
                cache[key] = Entry(wire, fetchedAt)
                dismissedLocally.removeAll { it.startsWith("$key\u0000") }
                if (before == null || before.placement.json != wire.json) changed.add(key)
            }
            // A moment already reached (an event earlier in the visit, seconds already spent) shows at once.
            updateMomentsLocked().forEach { if (it !in changed) changed.add(it) }
        }
        persistCache()
        if (changed.isNotEmpty()) applyChanged(changed)
        reportDueDeferredControls()
        scheduleMomentTimer()
    }

    private fun applyChanged(keys: List<String>) {
        val update = keys.associateWith { toPlacement(it) }
        val (targets, ls) = synchronized(lock) { hosts.filter { it.placementKey in keys } to listeners.toList() }
        postMain(Runnable {
            targets.forEach { host -> update[host.placementKey]?.let { runCatching { host.onPlacementUpdated(it) } } }
            ls.forEach { runCatching { it(update) } }
        })
    }

    // ── eligibility now ─────────────────────────────────────────────────

    private fun handle(key: String, item: PlacementItemWire) = "$key\u0000${item.deliveryToken ?: item.id}"

    private fun visibleItems(key: String): List<PlacementItemWire> = synchronized(lock) {
        val entry = cache[key] ?: return emptyList()
        val t = now()
        entry.placement.items.filter { item ->
            val expires = item.expiresAt?.let { parseIso(it) }
            if (expires != null && expires <= t) return@filter false
            if (handle(key, item) in dismissedLocally) return@filter false
            // Show when: held until its moment on this screen.
            if (!itemOpenLocked(key, item)) return@filter false
            val eventTriggers = item.triggers.filter { it["type"] == "event" }
            if (eventTriggers.isNotEmpty()) {
                val at = triggeredAt[handle(key, item)] ?: return@filter false
                val window = (item.triggerWindowSeconds ?: PLACEMENT_DEFAULT_TRIGGER_WINDOW_SECONDS) * 1000L
                if (t - at > window) return@filter false
            }
            true
        }.take(entry.placement.maxItems)
    }

    internal fun toPlacement(key: String): Placement {
        val (cached, gen) = synchronized(lock) { cache[key]?.placement to generation }
        val wire = cached ?: emptyWire(key)
        val items = visibleItems(key).map { item ->
            PlacementItem(
                id = item.id,
                variantId = item.variantId,
                kind = item.kind,
                data = item.data,
                content = item.content,
                products = item.products,
                priority = item.priority,
                expiresAt = item.expiresAt,
                placementKey = key,
                wire = item,
                reporter = this,
                generation = gen,
            )
        }
        return Placement(key, items, wire.isControl, wire.emptyBehavior, wire.maxItems, wire.ttlSeconds, this, gen)
    }

    // ── reporting ───────────────────────────────────────────────────────

    /**
     * A transition to v1/in-app/track: only with consent (an unconsented
     * answer carries no delivery token). `displayed` and `dismissed` once per
     * delivery; a click or dismissal first counts the item as displayed.
     */
    override fun report(generation: Int, key: String, item: PlacementItemWire, type: String, elementId: String?) {
        // Served for someone who is no longer here (identify / reset since): nothing.
        if (generation != this.generation) return
        if (consent() != "granted") return
        if (type == "displayed") {
            // An impression counts only when the content is shown: never while held (Show when).
            if (!synchronized(lock) { itemOpenLocked(key, item) }) return
            synchronized(lock) { viewedOnScreen.add(key) }
            reportDueDeferredControls()
        }
        val once = "$type:${handle(key, item)}"
        if (type != "clicked") {
            val first = synchronized(lock) { reported.add(once) }
            if (!first) return
        }
        if (type != "displayed") report(generation, key, item, "displayed", null)
        val at = iso(now())
        val body = JsonObject().apply {
            addProperty("campaignId", item.id)
            item.variantId?.let { addProperty("variantId", it) }
            item.deliveryToken?.let { addProperty("deliveryToken", it) }
            addProperty("placementKey", key)
            subject(this)
            addProperty("deviceType", "android")
            addProperty("action", type)
            when (type) {
                "displayed" -> addProperty("displayedAt", at)
                "clicked" -> {
                    addProperty("clicked", true)
                    addProperty("clickedAt", at)
                    elementId?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in GENERIC_ELEMENT_IDS }
                        ?.let { addProperty("elementId", it.take(128)) }
                }
                "dismissed" -> addProperty("dismissedAt", at)
            }
        }
        send(body)
    }

    override fun dismiss(generation: Int, key: String, item: PlacementItemWire) {
        if (generation != this.generation) return
        report(generation, key, item, "dismissed", null)
        synchronized(lock) { dismissedLocally.add(handle(key, item)) }
        applyChanged(listOf(key))
    }

    /**
     * A held-out person's empty spot was viewable: `in_app.control_viewed`, once
     * per control token. With a Show when, not before the moment the treatment
     * would have been revealed (ignored before it; onPlacementsUpdated fires at
     * the moment). On any placement it also counts as "the spot was viewable"
     * for the answer's deferred controls.
     */
    override fun reportControl(generation: Int, key: String) {
        if (generation != this.generation) return
        synchronized(lock) { viewedOnScreen.add(key) }
        reportDueDeferredControls()
        val token = synchronized(lock) {
            cache[key]?.placement?.takeIf { it.isControl && controlOpenLocked(key, it) }?.controlToken
        } ?: return
        if (consent() != "granted") return
        if (!synchronized(lock) { reported.add("control:$token") }) return
        sendControlViewed(token, key)
    }

    /** The exact body of every `control_viewed` report. */
    private fun sendControlViewed(token: String, key: String) {
        val body = JsonObject().apply {
            addProperty("action", "control_viewed")
            addProperty("controlToken", token)
            addProperty("placementKey", key)
            subject(this)
            addProperty("deviceType", "android")
            addProperty("viewedAt", iso(now()))
        }
        send(body)
    }

    /** Deferred controls whose moment has come on a spot viewable on this screen: each once, with consent. */
    private fun reportDueDeferredControls() {
        if (consent() != "granted") return
        val due = ArrayList<Pair<String, String>>()
        synchronized(lock) {
            for (key in viewedOnScreen) {
                val p = cache[key]?.placement ?: continue
                for (d in p.deferredControls) {
                    if (!momentOpenLocked(d.showWhen, MomentId(MomentKind.DEFERRED, key, d.controlToken))) continue
                    if (reported.add("control:${d.controlToken}")) due.add(d.controlToken to key)
                }
            }
        }
        due.forEach { (token, key) -> sendControlViewed(token, key) }
    }

    // ── Show when (D465) ────────────────────────────────────────────────

    private fun itemMoment(key: String, item: PlacementItemWire) = MomentId(MomentKind.ITEM, key, item.deliveryToken ?: item.id)

    private fun controlMoment(key: String, p: PlacementWire) = MomentId(MomentKind.CONTROL, key, p.controlToken ?: "")

    /** Lock held. `load` always; otherwise once reached on this screen. */
    private fun momentOpenLocked(moment: ShowWhenMoment, id: MomentId) = moment == ShowWhenMoment.Load || id in revealed

    private fun itemOpenLocked(key: String, item: PlacementItemWire) = momentOpenLocked(item.showWhen, itemMoment(key, item))

    private fun controlOpenLocked(key: String, p: PlacementWire) = momentOpenLocked(p.showWhen, controlMoment(key, p))

    /** Lock held. The visit's event names, started over when the SDK session changed. */
    private fun visitEventsLocked() {
        val id = sessionId()
        if (id != sessionEventsId) {
            sessionEventsId = id
            sessionEvents.clear()
        }
    }

    /** Lock held. Has this moment come on the current screen? */
    private fun reachedLocked(moment: ShowWhenMoment): Boolean = when (moment) {
        ShowWhenMoment.Load -> true
        is ShowWhenMoment.Seconds -> screenClock.seconds + 0.001 >= moment.seconds
        is ShowWhenMoment.Event -> { visitEventsLocked(); moment.event in sessionEvents }
        ShowWhenMoment.Never -> false
    }

    private fun momentsOf(key: String, p: PlacementWire): List<Pair<MomentId, ShowWhenMoment>> {
        val out = ArrayList<Pair<MomentId, ShowWhenMoment>>()
        p.items.forEach { out.add(itemMoment(key, it) to it.showWhen) }
        if (p.isControl) out.add(controlMoment(key, p) to p.showWhen)
        p.deferredControls.forEach { out.add(MomentId(MomentKind.DEFERRED, key, it.controlToken) to it.showWhen) }
        return out
    }

    /**
     * Lock held. Marks every moment reached now; returns the keys whose
     * Placement changed (an item revealed, a control answer's moment came).
     */
    private fun updateMomentsLocked(): List<String> {
        val changed = ArrayList<String>()
        for ((key, entry) in cache) {
            for ((id, moment) in momentsOf(key, entry.placement)) {
                if (moment == ShowWhenMoment.Load || id in revealed || !reachedLocked(moment)) continue
                revealed.add(id)
                if (id.kind != MomentKind.DEFERRED && key !in changed) changed.add(key)
            }
        }
        return changed
    }

    /** The seconds moments' timer fired (tests call it directly). */
    internal fun checkShowWhen() {
        val changed = synchronized(lock) { updateMomentsLocked() }
        if (changed.isNotEmpty()) applyChanged(changed)
        reportDueDeferredControls()
        scheduleMomentTimer()
    }

    /**
     * A new screen: every moment starts again (content revealed by a non-load
     * moment is held again until the new screen's moment; an event of this
     * visit still counts at once).
     */
    private fun screenChanged() {
        val changed = synchronized(lock) {
            val before = HashSet(revealed)
            revealed.clear()
            viewedOnScreen.clear()
            rechecked.clear()
            pendingRechecks.clear()
            updateMomentsLocked()
            ((before - revealed) + (revealed - before)).filter { it.kind != MomentKind.DEFERRED }.map { it.key }.distinct()
        }
        if (changed.isNotEmpty()) applyChanged(changed)
        scheduleMomentTimer()
    }

    private fun stopMomentTimer() {
        val cancel = synchronized(lock) { cancelMomentTimer.also { cancelMomentTimer = null } }
        cancel?.invoke()
    }

    /** One timer for the nearest `seconds` moment not reached yet (none while in the background). */
    private fun scheduleMomentTimer() {
        stopMomentTimer()
        val waitMs = synchronized(lock) {
            if (!screenClock.isVisible) return@synchronized null
            val elapsed = screenClock.seconds
            var next: Double? = null
            for ((key, entry) in cache) {
                for ((id, moment) in momentsOf(key, entry.placement)) {
                    if (moment !is ShowWhenMoment.Seconds || id in revealed) continue
                    val w = maxOf(0.0, moment.seconds - elapsed)
                    next = minOf(next ?: w, w)
                }
            }
            next?.let { (it * 1000).toLong() + 50 }
        } ?: return
        val run = { checkShowWhen() }
        val cancel = schedule?.invoke(waitMs, run) ?: scope.launch { delay(waitMs); run() }.let { job -> { job.cancel() } }
        synchronized(lock) { cancelMomentTimer = cancel }
    }

    private fun subject(o: JsonObject) {
        userId()?.takeIf { it.isNotEmpty() }?.let { o.addProperty("userId", it) }
        o.addProperty("anonymousId", anonymousId())
        sessionId()?.let { o.addProperty("sessionId", it) }
    }

    private fun send(body: JsonObject) {
        val json = gson.toJson(body)
        scope.launch {
            try {
                transport.track(json)
            } catch (e: Exception) {
                log("Placement report failed: ${e.message}")
            }
        }
    }

    // ── parsing + disk cache ────────────────────────────────────────────

    private fun emptyWire(key: String) = PlacementWire(
        key, PlacementEmptyBehavior.COLLAPSE, 1, DEFAULT_TTL_SECONDS, false, null, emptyList(),
        JsonObject(),
    )

    private fun parsePlacement(key: String, o: JsonObject): PlacementWire {
        val items = (o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject?.let { parseItem(it) } }
        return PlacementWire(
            placementKey = key,
            emptyBehavior = if (o.str("emptyBehavior") == PlacementEmptyBehavior.KEEP_SPACE) PlacementEmptyBehavior.KEEP_SPACE else PlacementEmptyBehavior.COLLAPSE,
            maxItems = o.int("maxItems")?.takeIf { it > 0 } ?: 1,
            ttlSeconds = o.int("ttlSeconds")?.takeIf { it > 0 } ?: DEFAULT_TTL_SECONDS,
            isControl = o.bool("isControl") == true,
            controlToken = o.str("controlToken"),
            items = items,
            json = o,
            showWhen = if (o.bool("isControl") == true) ShowWhenMoment.parse(o.get("showWhen")) else ShowWhenMoment.Load,
            deferredControls = (o.get("deferredControls")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { d ->
                val dc = d.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val token = dc.str("controlToken")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DeferredControl(token, ShowWhenMoment.parse(dc.get("showWhen")))
            },
            recheckOn = (o.get("recheckOn")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
                .mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotEmpty() } },
        )
    }

    private fun parseItem(o: JsonObject): PlacementItemWire? {
        val id = o.str("id") ?: return null
        val data = LinkedHashMap<String, Any?>()
        o.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) -> data[k] = primitive(v) }
        val content = o.get("content")?.takeIf { it.isJsonObject }?.asJsonObject?.let { c ->
            PlacementContent(
                kind = c.str("kind") ?: "native",
                title = c.str("title"),
                body = c.str("body"),
                imageUrl = c.str("imageUrl"),
                buttons = (c.get("buttons")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { b ->
                    b.takeIf { it.isJsonObject }?.asJsonObject?.let {
                        PlacementButton(it.str("id") ?: "", it.str("text") ?: "", it.str("action") ?: "", it.str("url"))
                    }
                },
                closeButton = c.bool("closeButton") == true,
                html = c.str("html"),
                css = c.str("css"),
            )
        }
        val triggers = (o.get("triggers")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { t ->
            t.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (k, v) -> k to primitive(v) }
        }
        val products = (o.get("products")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { p ->
            p.takeIf { it.isJsonObject }?.asJsonObject?.let { po ->
                val pid = po.get("id")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString ?: return@let null
                PlacementProduct(
                    id = pid,
                    title = po.str("title"),
                    price = po.str("price"),
                    compareAtPrice = po.str("compareAtPrice"),
                    discountPercent = po.get("discountPercent")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble,
                    imageUrl = po.str("imageUrl"),
                    url = po.str("url"),
                )
            }
        }
        return PlacementItemWire(
            id = id,
            variantId = o.str("variantId"),
            deliveryToken = o.str("deliveryToken"),
            deliveryId = o.str("deliveryId"),
            priority = o.int("priority"),
            kind = o.str("kind") ?: if (content != null) content.kind else "data",
            data = data,
            content = content,
            products = products,
            triggers = triggers,
            triggerWindowSeconds = o.int("placementTriggerWindowSeconds"),
            expiresAt = o.str("expiresAt"),
            json = o,
            showWhen = ShowWhenMoment.parse(o.get("showWhen")),
        )
    }

    private fun subjectKey() = "${userId() ?: ""}|${anonymousId()}"

    private fun loadCache() {
        val raw = try { store?.load() } catch (e: Exception) { null } ?: return
        try {
            val root = JsonParser.parseString(raw).asJsonObject
            // Another person's answers (a reset or a login since) are not this one's.
            if (root.str("subject") != subjectKey()) return
            root.get("entries")?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                val e = value.asJsonObject
                val placement = e.get("placement")?.asJsonObject ?: return@forEach
                val fetchedAt = e.get("fetchedAt")?.asLong ?: return@forEach
                if (validKey(key)) cache[key] = Entry(parsePlacement(key, placement), fetchedAt)
            }
        } catch (e: Exception) {
            // corrupt: start empty
        }
    }

    private fun persistCache() {
        val json = synchronized(lock) {
            val entries = JsonObject()
            cache.forEach { (key, entry) ->
                entries.add(key, JsonObject().apply {
                    add("placement", entry.placement.json)
                    addProperty("fetchedAt", entry.fetchedAt)
                })
            }
            gson.toJson(JsonObject().apply {
                addProperty("subject", subjectKey())
                add("entries", entries)
            })
        }
        try { store?.save(json) } catch (e: Exception) { /* memory only */ }
    }

    companion object {
        /** Pending re-checks kept at most (oldest dropped) and for at most this long. */
        internal const val PENDING_RECHECK_MAX = 20
        internal const val PENDING_RECHECK_MAX_AGE_MS = 60_000L

        private val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9_]{0,63}$")
        private const val DEFAULT_TTL_SECONDS = 300
        private val GENERIC_ELEMENT_IDS = setOf("click", "clicked", "button", "link", "cta_click", "link_click", "button_click")

        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        private fun primitive(v: JsonElement?): Any? {
            if (v == null || v.isJsonNull || !v.isJsonPrimitive) return null
            val p = v.asJsonPrimitive
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber.toDouble().let { d -> if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < Long.MAX_VALUE) d.toLong() else d }
                else -> p.asString
            }
        }

        /** A trigger's own property condition; an unknown operator does not match (fails closed). */
        internal fun triggerMatches(trigger: Map<String, Any?>, properties: Map<String, Any?>): Boolean {
            val attribute = trigger["attribute"] as? String ?: return true
            val actual = properties[attribute]
            val expected = trigger["value"]
            fun num(v: Any?): Double = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
            fun same(a: Any?, b: Any?): Boolean =
                if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
            return when (trigger["operator"] as? String) {
                null, "equals", "eq" -> same(actual, expected)
                "not_equals", "neq" -> !same(actual, expected)
                "greater_than", "gt" -> num(actual) > num(expected)
                "less_than", "lt" -> num(actual) < num(expected)
                "contains" -> (actual?.toString() ?: "").contains(expected?.toString() ?: "")
                "exists" -> actual != null
                else -> false
            }
        }

        private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        internal fun iso(ms: Long): String = isoFormat().format(Date(ms))

        internal fun parseIso(s: String): Long? {
            // One pattern (with milliseconds); a value without them is given ".000".
            val normalized = if (Regex("""T\d{2}:\d{2}:\d{2}Z$""").containsMatchIn(s)) s.dropLast(1) + ".000Z" else s
            return try {
                isoFormat().parse(normalized)?.time
            } catch (e: Exception) {
                null
            }
        }
    }
}
