package io.joryio.sdk.flags

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A refresh asked for within this many seconds of the last one is skipped (start, identify, push and refreshFlags() always go). */
const val FLAGS_MIN_REFRESH_SECONDS = 60L

/** Exposures are sent in one request this long after the first read. */
const val FLAG_EXPOSURE_FLUSH_MS = 1000L

/** The silent-push `jry_sync` value that refreshes flags now. */
const val FLAG_SYNC_PUSH_VALUE = "flags"

/** Exposures per request (the server's limit). */
internal const val FLAG_EXPOSURES_PER_CALL_MAX = 100

/**
 * Feature flags + remote config (Joryio BACKLOG D446). The Android twin of the
 * web SDK's `core/flags.ts` - the same names, the same wire values.
 *
 * - `getFlag(key, default)` and the typed getters read the answer held on the
 *   device; they never wait for the network. Before the first answer, for a
 *   flag the server did not send, or for a value of another type, they return
 *   the in-code default.
 * - Nothing is fetched until the app uses flags (a getter, getAllFlags,
 *   refreshFlags or onFlagsUpdated on this device). From then on the answer
 *   (`POST /v1/flags/evaluate`, every active flag in one call) is kept on disk
 *   for the same person only, and refreshed at start, on foreground (at most
 *   once per [FLAGS_MIN_REFRESH_SECONDS]), on identify / reset and on a
 *   consent change, on the silent push `jry_sync: "flags"`, and on
 *   refreshFlags(). The `etag` is sent back (`notModified`).
 * - An exposure (`flag.exposure`) is logged the first time a value is read in
 *   a session: once per person, flag and variant per session, unless the
 *   answer said `logExposure: false`. getAllFlags() logs none. Each batch
 *   carries the identity it was read under: never sent as the next person,
 *   dropped when consent is withdrawn before it goes.
 * - Without tracking consent nothing is sent and the getters return the
 *   in-code default.
 */
internal class FlagsManager(
    private val scope: CoroutineScope,
    /** POST a JSON body; the response body, or null when there is no usable answer. */
    private val post: suspend (path: String, json: String) -> String?,
    /** true when the SDK may send (initialized, consent granted, not opted out). */
    private val canSend: () -> Boolean,
    private val userId: () -> String?,
    private val anonymousId: () -> String,
    private val sessionId: () -> String,
    private val attributes: () -> Map<String, Any?>,
    private val context: () -> Map<String, Any?>,
    private val load: () -> String?,
    private val save: (String?) -> Unit,
    /** Where onFlagsUpdated listeners run (the main thread in the SDK). */
    private val postMain: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val exposureFlushMs: Long = FLAG_EXPOSURE_FLUSH_MS,
) {
    private class PendingBatch(val body: JsonObject, val generation: Int) {
        val exposures = mutableListOf<Pair<String, String>>()
    }

    private val lock = Any()
    private val gson = Gson()

    /** key -> the flag as the server sent it (`value`, `variant`, `valueType`, `reason`, `version`, `logExposure`). */
    private var flags: Map<String, JsonObject> = emptyMap()
    private var etag = ""
    private var fetchedAt = 0L
    private var generation = 0
    private var used = false
    private var inflight: CompletableDeferred<Unit>? = null
    private var inflightGeneration = 0
    private val listeners = LinkedHashMap<Any, (List<String>) -> Unit>()

    /** `<session>|<person>|<flag>|<variant>` already reported. */
    private val exposed = HashSet<String>()
    private var pending: PendingBatch? = null
    private var flushScheduled = false

    init {
        loadStored()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Start: the stored answer is readable at once; refresh it now. */
    fun start() {
        if (synchronized(lock) { used }) scope.launch { refresh(force = true) }
    }

    /** Foreground: refresh, at most once per [FLAGS_MIN_REFRESH_SECONDS]. */
    fun onForeground() {
        scope.launch { refresh(force = false) }
    }

    /** The person or the consent changed: the held answer belongs to someone else. */
    fun onIdentityChanged() {
        val before = synchronized(lock) {
            generation++
            flags.keys.toList()
        }
        // The batch carries its own snapshot, so it still goes as the previous
        // person - or is dropped when consent was withdrawn.
        flush()
        synchronized(lock) {
            flags = emptyMap()
            etag = ""
            fetchedAt = 0L
        }
        persist()
        if (before.isNotEmpty()) emit(before)
        if (synchronized(lock) { used }) scope.launch { refresh(force = true) }
    }

    /** wipeData(): forget the answer and the reports, keep the listeners. */
    fun wipe() {
        synchronized(lock) {
            generation++
            pending = null
            flags = emptyMap()
            etag = ""
            fetchedAt = 0L
            used = false
            exposed.clear()
        }
        save(null)
    }

    // ── Reading ──────────────────────────────────────────────────────────

    fun getFlag(key: String, defaultValue: Any?): Any? {
        val f = read(key, null) ?: return defaultValue
        return plain(f.get("value"))
    }

    fun getBoolFlag(key: String, defaultValue: Boolean): Boolean {
        val v = read(key, "boolean")?.get("value") as? JsonPrimitive ?: return defaultValue
        return if (v.isBoolean) v.asBoolean else defaultValue
    }

    fun getStringFlag(key: String, defaultValue: String): String {
        val v = read(key, "string")?.get("value") as? JsonPrimitive ?: return defaultValue
        return if (v.isString) v.asString else defaultValue
    }

    fun getNumberFlag(key: String, defaultValue: Double): Double {
        val v = read(key, "number")?.get("value") as? JsonPrimitive ?: return defaultValue
        return if (v.isNumber) v.asDouble else defaultValue
    }

    fun getJsonFlag(key: String, defaultValue: Any?): Any? {
        val f = read(key, "json") ?: return defaultValue
        return plain(f.get("value"))
    }

    /** A JSON flag decoded into [type] with Gson; [defaultValue] when it does not decode. */
    fun <T> getJsonFlag(key: String, type: Class<T>, defaultValue: T): T {
        val f = read(key, "json") ?: return defaultValue
        return try {
            gson.fromJson(f.get("value"), type) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    /** Every flag held, key -> value. Reading them all logs no exposure. */
    fun getAllFlags(): Map<String, Any?> {
        if (!canSend()) return emptyMap()
        use()
        return synchronized(lock) { flags.mapValues { plain(it.value.get("value")) } }
    }

    /** Ask the server now (always goes, unlike the automatic refreshes). */
    suspend fun refreshFlags() {
        synchronized(lock) { used = true }
        refresh(force = true)
    }

    fun onFlagsUpdated(listener: (List<String>) -> Unit): io.joryio.sdk.JoryioSubscription {
        val token = Any()
        synchronized(lock) { listeners[token] = listener }
        use()
        return io.joryio.sdk.JoryioSubscription { synchronized(lock) { listeners.remove(token) } }
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** The first use on a device that had no stored answer fetches one (the getter itself returns at once). */
    private fun use() {
        val first = synchronized(lock) {
            if (used) false else {
                used = true
                true
            }
        }
        if (first) scope.launch { refresh(force = true) }
    }

    private fun read(key: String, type: String?): JsonObject? {
        if (!canSend()) return null
        use()
        val f = synchronized(lock) { flags[key] } ?: return null
        if (type != null && f.str("valueType") != type) return null
        noteExposure(key, f)
        return f
    }

    private fun noteExposure(key: String, f: JsonObject) {
        // `logExposure: false` = sampled out on the server; absent = log.
        val logExposure = (f.get("logExposure") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: true
        if (!logExposure) return
        val variant = f.str("variant") ?: ""
        val mark = "${sessionId()}|${subjectKey()}|$key|$variant"
        val body = requestBody()
        var previous: PendingBatch? = null
        var schedule = false
        synchronized(lock) {
            if (!exposed.add(mark)) return
            val current = pending
            if (current == null || current.generation != generation) {
                // A batch from before an identity change goes first, as its own person.
                previous = current
                pending = PendingBatch(body, generation)
            }
            pending!!.exposures.add(key to variant)
            if (!flushScheduled) {
                flushScheduled = true
                schedule = true
            }
        }
        previous?.let { send(it) }
        if (schedule) {
            scope.launch {
                delay(exposureFlushMs)
                flush()
            }
        }
    }

    /** Send the queued exposures (one request, the identity they were read under). */
    fun flush() {
        val batch = synchronized(lock) {
            flushScheduled = false
            pending.also { pending = null }
        }
        batch?.let { send(it) }
    }

    private fun send(batch: PendingBatch) {
        // Consent withdrawn since the reads: nothing is sent.
        if (batch.exposures.isEmpty() || !canSend()) return
        val body = batch.body.deepCopy()
        val list = JsonArray()
        batch.exposures.take(FLAG_EXPOSURES_PER_CALL_MAX).forEach { (k, v) ->
            list.add(JsonObject().apply {
                addProperty("key", k)
                addProperty("variant", v)
            })
        }
        body.add("exposures", list)
        val json = body.toString()
        scope.launch {
            try {
                post("v1/flags/exposures", json)
            } catch (e: Exception) {
                log("Flag exposures failed: ${e.message}")
            }
        }
    }

    private fun subjectKey(): String = "${userId() ?: ""}|${anonymousId()}"

    /** The identity, attributes and context of one request - the same on /evaluate and /exposures. */
    private fun requestBody(): JsonObject = JsonObject().apply {
        val user = userId()
        if (!user.isNullOrEmpty()) addProperty("userId", user)
        addProperty("anonymousId", anonymousId())
        addProperty("sessionId", sessionId())
        add("attributes", jsonSafe(attributes()))
        add("context", jsonSafe(context()))
    }

    internal suspend fun refresh(force: Boolean) {
        if (!canSend()) return
        var stale = false
        var mine: CompletableDeferred<Unit>? = null
        val waitFor: CompletableDeferred<Unit>? = synchronized(lock) {
            if (!used) return
            val running = inflight
            if (running != null) {
                // A request in flight for a previous person: wait for it, then ask for this one.
                stale = inflightGeneration != generation
                running
            } else {
                if (!force && fetchedAt > 0 && now() - fetchedAt < FLAGS_MIN_REFRESH_SECONDS * 1000) return
                CompletableDeferred<Unit>().also {
                    inflight = it
                    inflightGeneration = generation
                    mine = it
                }
            }
        }
        val own = mine
        if (own != null) {
            try {
                performRefresh(synchronized(lock) { inflightGeneration })
            } finally {
                synchronized(lock) { if (inflight === own) inflight = null }
                own.complete(Unit)
            }
        } else {
            waitFor?.await()
            if (stale) refresh(force = true)
        }
    }

    private suspend fun performRefresh(gen: Int) {
        val body = requestBody()
        val heldEtag = synchronized(lock) { etag }
        if (heldEtag.isNotEmpty()) body.addProperty("etag", heldEtag)
        val raw = try {
            post("v1/flags/evaluate", body.toString())
        } catch (e: Exception) {
            log("Flags refresh failed; keeping the held flags: ${e.message}")
            null
        } ?: return
        val res = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            return
        }
        val changed: List<String> = synchronized(lock) {
            if (gen != generation) return // an answer for a previous person
            fetchedAt = now()
            val notModified = (res.get("notModified") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean == true
            if (notModified) {
                emptyList()
            } else {
                val next = (res.get("flags") as? JsonObject)?.entrySet()
                    ?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                    ?.toMap() ?: return
                val diff = (flags.keys + next.keys).filter { k ->
                    flags[k]?.get("value") != next[k]?.get("value") || flags[k]?.str("variant") != next[k]?.str("variant")
                }.sorted()
                flags = next
                etag = res.str("etag") ?: ""
                diff
            }
        }
        persist()
        if (changed.isNotEmpty()) emit(changed)
    }

    private fun emit(keys: List<String>) {
        val callbacks = synchronized(lock) { listeners.values.toList() }
        if (callbacks.isEmpty()) return
        postMain(Runnable {
            for (cb in callbacks) {
                try {
                    cb(keys)
                } catch (t: Throwable) {
                    log("onFlagsUpdated listener failed: ${t.message}")
                }
            }
        })
    }

    // ── Storage (one answer, for one person) ─────────────────────────────

    private fun loadStored() {
        val raw = try { load() } catch (e: Exception) { null } ?: return
        try {
            val s = JsonParser.parseString(raw).asJsonObject
            // Another person's answer is never used.
            if (s.str("subject") != subjectKey()) return
            val stored = (s.get("flags") as? JsonObject) ?: return
            flags = stored.entrySet().mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
            etag = s.str("etag") ?: ""
            used = true
            // The stored answer is used at once, but the start refresh always goes.
            fetchedAt = 0L
        } catch (e: Exception) {
            // Unreadable: start empty.
        }
    }

    private fun persist() {
        val s = synchronized(lock) {
            JsonObject().apply {
                addProperty("subject", subjectKey())
                addProperty("etag", etag)
                addProperty("fetchedAt", fetchedAt)
                add("flags", JsonObject().also { o -> flags.forEach { (k, v) -> o.add(k, v) } })
            }
        }
        try {
            save(s.toString())
        } catch (e: Exception) {
            // Storage unavailable: memory only.
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    /** Only values JSON can carry. */
    private fun jsonSafe(map: Map<String, Any?>): JsonObject = JsonObject().apply {
        for ((k, v) in map) {
            try {
                add(k, gson.toJsonTree(v))
            } catch (e: Exception) {
                // Left out.
            }
        }
    }

    companion object {
        /** A JSON element as Kotlin values: Boolean, String, Double, Map, List; JSON null -> null. */
        internal fun plain(e: JsonElement?): Any? = when {
            e == null || e is JsonNull -> null
            e is JsonPrimitive && e.isBoolean -> e.asBoolean
            e is JsonPrimitive && e.isNumber -> e.asDouble
            e is JsonPrimitive -> e.asString
            e is JsonArray -> e.map { plain(it) }
            e is JsonObject -> e.entrySet().associate { (k, v) -> k to plain(v) }
            else -> null
        }
    }
}
