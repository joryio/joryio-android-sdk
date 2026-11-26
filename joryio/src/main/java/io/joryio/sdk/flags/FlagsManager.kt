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

/**
 * Exposure marks kept for the current session (BACKLOG D479); past this the
 * oldest goes first. A session reads far fewer flags, so this only bounds the
 * stored list.
 */
const val FLAG_EXPOSURE_MARKS_MAX = 500

/** The silent-push `jry_sync` value that refreshes flags now. */
const val FLAG_SYNC_PUSH_VALUE = "flags"

/** Exposures per request (the server's limit). */
internal const val FLAG_EXPOSURES_PER_CALL_MAX = 100

/** One exposures request's answer: the HTTP status (0 = no answer) and the server's Retry-After. */
internal data class FlagsPostStatus(val status: Int, val retryAfterMs: Long? = null)

/**
 * Whether an exposures batch is finished with after an answer of [status]
 * (0 = no answer): a 2xx, or a 4xx other than 408 / 429 - a refusal a retry
 * would not change, as the write outbox drops one. A network failure, a 5xx,
 * a 408 or a 429 keeps the batch for a retry. The same rule, under the same
 * name, on iOS and the web.
 */
internal fun exposureBatchSettled(status: Int): Boolean = when (status) {
    in 200..299 -> true
    408, 429 -> false
    in 400..499 -> true
    else -> false
}

/**
 * The marks of an exposures request body: `<session>|<user>|<anonymous>|<flag>|<variant>`,
 * from the identity the batch carries. The same on iOS and the web.
 */
internal fun exposureMarks(json: String): List<String> = try {
    val body = JsonParser.parseString(json).asJsonObject
    fun s(k: String) = (body.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: ""
    (body.get("exposures") as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val key = (o.get("key") as? JsonPrimitive)?.asString ?: return@mapNotNull null
        "${s("sessionId")}|${s("userId")}|${s("anonymousId")}|$key|${(o.get("variant") as? JsonPrimitive)?.asString ?: ""}"
    } ?: emptyList()
} catch (e: Exception) {
    emptyList()
}

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
 *   dropped when consent is withdrawn before it goes. Each batch carries an
 *   `idempotencyKey` (a UUID): a copy re-sent after a dropped connection is
 *   recorded once by the server.
 * - The "already sent" marks are kept on disk for the current session only
 *   (`jry_flag_exposures`, at most [FLAG_EXPOSURE_MARKS_MAX]), so a relaunch
 *   that resumes the session sends no exposure again (BACKLOG D479). A new
 *   session drops them; reset(), identify() of another user and wipeData()
 *   clear them. The server counts one exposure per person, flag, variant and
 *   session anyway - this only saves the request.
 * - A mark is stored only once the server took its batch
 *   ([exposureBatchSettled]: a 2xx, or a 4xx other than 408 / 429). A batch
 *   that got no answer, a 5xx, a 408 or a 429 is handed, with the SAME body
 *   and `idempotencyKey`, to the SDK's persisted write outbox (kind
 *   `flag_exposures`), which retries it on its backoff, across relaunches,
 *   and discards it on wipeData(), an opt-out or a denied consent; its marks
 *   are stored when the outbox settles it. While it waits there it counts as
 *   already exposed (its marks are read from the outbox), so a read does not
 *   queue it twice.
 * - Without tracking consent nothing is sent and the getters return the
 *   in-code default.
 */
internal class FlagsManager(
    private val scope: CoroutineScope,
    /** POST a JSON body; the response body, or null when there is no usable answer. */
    private val post: suspend (path: String, json: String) -> String?,
    /** POST an exposures batch; the HTTP status (0 = no answer) and Retry-After. */
    private val postExposures: suspend (json: String) -> FlagsPostStatus = { json ->
        FlagsPostStatus(if (post("v1/flags/exposures", json) != null) 200 else 0)
    },
    /**
     * Hand a batch the server did not take to the persisted write outbox (same
     * body, same key), with that answer's Retry-After: its first outbox
     * attempt waits the first backoff step, or the Retry-After when longer.
     */
    private val enqueueExposures: (json: String, retryAfterMs: Long?) -> Unit = { _, _ -> },
    /** The bodies of the exposure batches waiting in the write outbox. */
    private val pendingExposures: () -> List<String> = { emptyList() },
    /** true when the SDK may send (initialized, consent granted, not opted out). */
    private val canSend: () -> Boolean,
    private val userId: () -> String?,
    private val anonymousId: () -> String,
    private val sessionId: () -> String,
    private val attributes: () -> Map<String, Any?>,
    private val context: () -> Map<String, Any?>,
    private val load: () -> String?,
    private val save: (String?) -> Unit,
    /** The stored exposure marks of the current session (JSON), or null. */
    private val loadExposures: () -> String? = { null },
    /** Store the exposure marks (JSON); null removes them. */
    private val saveExposures: (String?) -> Unit = {},
    /** Where onFlagsUpdated listeners run (the main thread in the SDK). */
    private val postMain: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val exposureFlushMs: Long = FLAG_EXPOSURE_FLUSH_MS,
) {
    private class PendingBatch(
        val body: JsonObject,
        val generation: Int,
        /** `wipes` when the batch was made: one read before wipeData() is never handed to the outbox. */
        val wipe: Int,
        /**
         * The batch's `idempotencyKey` (1.4.0-beta.12): one per request, fixed
         * before the body is built, so every copy of this request - OkHttp
         * re-sending it after a dropped connection, or our own retry - carries
         * the same key and the server records it once.
         */
        val idempotencyKey: String = java.util.UUID.randomUUID().toString(),
    ) {
        val exposures = mutableListOf<Pair<String, String>>()

        /** The batch's exposure marks, stored once the server took it. */
        val marks = mutableListOf<String>()
    }

    /**
     * [batch] as requests of at most [FLAG_EXPOSURES_PER_CALL_MAX] exposures,
     * each with its own idempotencyKey (the first keeps the batch's) and its
     * own marks: each is settled, kept and retried on its own. The same on iOS
     * and the web.
     */
    private fun splitExposureBatch(batch: PendingBatch): List<PendingBatch> {
        if (batch.exposures.size <= FLAG_EXPOSURES_PER_CALL_MAX) return listOf(batch)
        return batch.exposures.indices.step(FLAG_EXPOSURES_PER_CALL_MAX).map { i ->
            val end = minOf(i + FLAG_EXPOSURES_PER_CALL_MAX, batch.exposures.size)
            PendingBatch(
                batch.body, batch.generation, batch.wipe,
                if (i == 0) batch.idempotencyKey else java.util.UUID.randomUUID().toString(),
            ).apply {
                exposures.addAll(batch.exposures.subList(i, end))
                marks.addAll(batch.marks.subList(i, end))
            }
        }
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

    /** `<session>|<person>|<flag>|<variant>` already reported, oldest first; persisted for [exposedSession]. */
    private val exposed = LinkedHashSet<String>()

    /** The session [exposed] belongs to; null = not read from storage yet. */
    private var exposedSession: String? = null
    private var pending: PendingBatch? = null
    private var flushScheduled = false

    /** The marks of batches the server took this session - what is kept on disk, oldest first. */
    private val storedMarks = LinkedHashSet<String>()

    /** Bumped by [wipe]: a batch read before it is never handed to the outbox. */
    private var wipes = 0

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
            // No exposure read before the wipe is sent: the queued batch goes,
            // and one on the wire is not handed to the outbox (whose own
            // entries the wipe discards with its other writes).
            wipes++
            generation++
            pending = null
            flags = emptyMap()
            etag = ""
            fetchedAt = 0L
            used = false
            exposed.clear()
            storedMarks.clear()
            exposedSession = null
        }
        save(null)
        try { saveExposures(null) } catch (e: Exception) { /* storage unavailable */ }
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
        val session = sessionId()
        val mark = "$session|${subjectKey()}|$key|$variant"
        val body = requestBody()
        // Waiting in the write outbox counts as exposed: looked up on a miss
        // (once per flag and variant per session), outside the lock.
        val known = synchronized(lock) { exposedSession == session && exposed.contains(mark) }
        val waiting = !known && pendingExposures().any { exposureMarks(it).contains(mark) }
        var previous: PendingBatch? = null
        var schedule = false
        synchronized(lock) {
            useExposureSessionLocked(session)
            if (waiting) exposed.add(mark)
            if (!exposed.add(mark)) return
            while (exposed.size > FLAG_EXPOSURE_MARKS_MAX) exposed.remove(exposed.first())
            val current = pending
            if (current == null || current.generation != generation) {
                // A batch from before an identity change goes first, as its own person.
                previous = current
                pending = PendingBatch(body, generation, wipes)
            }
            pending!!.exposures.add(key to variant)
            pending!!.marks.add(mark)
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
        val chunks = splitExposureBatch(batch).map { it to requestJson(it) }
        // One request after the other, in reading order.
        scope.launch { for ((chunk, json) in chunks) postBatch(chunk, json) }
    }

    /**
     * One POST of [batch]. Its marks are stored only once the server took it
     * ([exposureBatchSettled]); otherwise the write outbox gets it, with the
     * same idempotencyKey.
     */
    private suspend fun postBatch(batch: PendingBatch, json: String) {
        val answer = try {
            postExposures(json)
        } catch (e: Exception) {
            log("Flag exposures failed: ${e.message}")
            FlagsPostStatus(0)
        }
        // Taken - or refused for good (a 4xx other than 408 / 429): never sent again.
        if (exposureBatchSettled(answer.status)) {
            storeMarks(batch.marks)
            return
        }
        // Not taken: the persisted outbox retries it, same body and key - unless
        // the data was wiped or sending stopped (opt-out, consent) meanwhile.
        // Its first outbox attempt waits (it failed once, and the server may
        // have said when to come back): never an immediate second send.
        if (synchronized(lock) { batch.wipe == wipes } && canSend()) enqueueExposures(json, answer.retryAfterMs)
    }

    /** The write outbox settled an exposures batch (taken, or refused for good): its marks are stored. */
    fun exposuresSettled(json: String) = storeMarks(exposureMarks(json))

    private fun requestJson(batch: PendingBatch): String {
        val body = batch.body.deepCopy()
        val list = JsonArray()
        batch.exposures.forEach { (k, v) ->
            list.add(JsonObject().apply {
                addProperty("key", k)
                addProperty("variant", v)
            })
        }
        body.add("exposures", list)
        body.addProperty("idempotencyKey", batch.idempotencyKey)
        return body.toString()
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

    /**
     * Point [exposed] at [session]: the first time, seeded from storage when
     * the stored marks are this session's (a relaunch that resumed it); on a
     * new session, the previous session's marks are dropped.
     */
    private fun useExposureSessionLocked(session: String) {
        if (exposedSession == session) return
        val first = exposedSession == null
        exposed.clear()
        storedMarks.clear()
        exposedSession = session
        if (!first) return
        try {
            val raw = loadExposures() ?: return
            val s = JsonParser.parseString(raw).asJsonObject
            if (s.str("sessionId") != session) return
            (s.get("marks") as? JsonArray)?.forEach { m ->
                if (m is JsonPrimitive && m.isString) storedMarks.add(m.asString)
            }
            while (storedMarks.size > FLAG_EXPOSURE_MARKS_MAX) storedMarks.remove(storedMarks.first())
            exposed.addAll(storedMarks)
        } catch (e: Exception) {
            // Unreadable: start empty.
        }
    }

    /**
     * The server took a batch (directly or through the outbox): its marks are
     * stored, as JSON `{"sessionId", "marks"}`. Only the current session's
     * and person's: a batch of the previous person (reset, another user)
     * stores none.
     */
    private fun storeMarks(marks: List<String>) {
        val subject = subjectKey()
        val current = sessionId()
        val stored = synchronized(lock) {
            // Settled by the outbox before this launch read a flag: the session's stored marks first.
            useExposureSessionLocked(current)
            val session = exposedSession ?: return
            val mine = marks.filter { it.startsWith("$session|$subject|") }
            if (mine.isEmpty()) return
            storedMarks.addAll(mine)
            // Known as sent in this process too (an outbox settle can come before any read).
            exposed.addAll(mine)
            while (storedMarks.size > FLAG_EXPOSURE_MARKS_MAX) storedMarks.remove(storedMarks.first())
            JsonObject().apply {
                addProperty("sessionId", session)
                add("marks", JsonArray().also { a -> storedMarks.forEach { a.add(it) } })
            }.toString()
        }
        try {
            saveExposures(stored)
        } catch (e: Exception) {
            // Storage unavailable: memory only.
        }
    }

    /**
     * reset() or identify() of another user: the exposure marks go, in
     * memory and on disk (the attribute-clearing rule of 1.4.0-beta.11).
     */
    fun clearExposureMarks() {
        synchronized(lock) {
            // Batches already made still go, as the person who read them;
            // their marks are not stored (storeMarks keeps the current person's only).
            exposed.clear()
            storedMarks.clear()
            exposedSession = null
        }
        try {
            saveExposures(null)
        } catch (e: Exception) {
            // Storage unavailable.
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    /** Only values JSON can carry. */
    private fun jsonSafe(map: Map<String, Any?>): JsonObject = JsonObject().apply {
        for ((k, v) in map) {
            try {
                // WireJson: a whole number goes as 3, not 3.0 - as on iOS and the web.
                add(k, io.joryio.sdk.network.WireJson.gson.toJsonTree(v))
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
