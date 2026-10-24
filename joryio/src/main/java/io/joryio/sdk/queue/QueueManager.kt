package io.joryio.sdk.queue

import android.content.Context
import io.joryio.sdk.core.Logger
import io.joryio.sdk.models.EventBatch
import io.joryio.sdk.models.Event
import io.joryio.sdk.network.BatchResponse
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.network.WireJson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

/**
 * What the server did with one batch, reduced to what the queue must DO about
 * it. Shared SDK contract item 3 - every SDK maps responses the same way.
 */
internal sealed class BatchSendResult {
    /** 2xx and `success: true`: every event stored. Delete the batch. */
    object Accepted : BatchSendResult()

    /**
     * 2xx and `success: false`: some events were not stored. Keep the ones at
     * [failedIndices] (the whole batch when null), DROP the ones at
     * [rejectedIndices] - refused for good, never retried (contract item 29)
     * - and delete the rest.
     */
    data class Partial(val failedIndices: List<Int>?, val rejectedIndices: List<Int>? = null) : BatchSendResult()

    /**
     * 401 with the SDK-Authentication reason the server gave (null for a 401
     * that is not an SDK-Authentication refusal). Retried like any 401 -
     * unless the batch belongs to a user who is no longer the current one and
     * SDK Authentication refused it, for any reason: then it can never be
     * accepted and is dropped (contract items 30 and 39).
     */
    data class AuthRefused(val reason: io.joryio.sdk.SdkAuthErrorReason?, val retryAfterMs: Long? = null) : BatchSendResult()

    /**
     * 400 / 413 / 422: the server will never take this batch as it is. Split
     * it and retry the halves, so one bad event cannot block the queue; a
     * single event still rejected is dropped.
     */
    object Rejected : BatchSendResult()

    /** 402 (ingestion suspended) or 403: stop sending for this session. */
    object Suspended : BatchSendResult()

    /** 401 / 429 / 5xx / offline / timeout: keep everything, back off. */
    data class Retry(val retryAfterMs: Long? = null) : BatchSendResult()

    companion object {
        fun from(result: NetworkResult<BatchResponse>): BatchSendResult = when (result) {
            is NetworkResult.Success ->
                if (result.data.success && result.data.rejectedIndices.isNullOrEmpty()) {
                    Accepted
                } else if (result.data.success) {
                    // Stored everything it did not refuse for good.
                    Partial(failedIndices = emptyList(), rejectedIndices = result.data.rejectedIndices)
                } else {
                    Partial(result.data.failedIndices, result.data.rejectedIndices)
                }
            is NetworkResult.Error -> when (result.code) {
                400, 413, 422 -> Rejected
                401 -> AuthRefused(result.authReason, result.retryAfterMs)
                402, 403 -> Suspended
                else -> Retry(result.retryAfterMs)
            }
        }
    }
}

/**
 * Manages event queue with SQLite persistence.
 *
 * THE QUEUE NEVER DELETES AN EVENT BECAUSE THE NETWORK FAILED. It used to:
 * every failed flush bumped a per-row retry count and `deleteExpiredEvents`
 * removed rows at maxRetries (3). An app opened three times on a train lost
 * everything it had tracked - the "offline queue" was a three-strike queue.
 * Now the only things that remove an unsent event are the server explicitly
 * refusing that single event, and the maxQueueSize bound (oldest first).
 */
internal class QueueManager(
    private val eventDao: EventDao,
    private val logger: Logger,
    batchSize: Int = 50,
    flushInterval: Long = 5000, // 5 seconds
    private val maxQueueSize: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        sdkKey: String,
        logger: Logger,
        batchSize: Int = 50,
        flushInterval: Long = 5000,
        maxQueueSize: Int = 1000,
    ) : this(
        eventDao = JoryioDatabase.getInstance(context, sdkKey).eventDao(),
        logger = logger,
        batchSize = batchSize,
        flushInterval = flushInterval,
        maxQueueSize = maxQueueSize,
    )

    internal companion object {
        const val MAX_AUTO_FLUSH_DELAY_MS = 300_000L // 5 minutes

        /**
         * Floor for flushInterval (contract item 35). Zero or a negative value
         * made the auto-flush loop spin without waiting (scan 3, finding 12).
         */
        const val MIN_FLUSH_INTERVAL_MS = 1_000L

        /** The backend's `@ArrayMaxSize(500)`: a bigger batch is a 400, every time. */
        const val MAX_BATCH_EVENTS = 500

        /** Stay under the backend's body limit with headroom for the envelope. */
        const val MAX_REQUEST_BYTES = 900_000

        /** Keys, quotes and the timestamp around each event's own strings. */
        const val ENVELOPE_BYTES = 200

        /** UTF-8 length without allocating the encoded bytes. */
        fun utf8Length(s: String?): Int {
            if (s == null) return 0
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return bytes
        }

        /** Bound one flush call, so a deep queue cannot monopolise the sender. */
        const val MAX_BATCHES_PER_FLUSH = 20
    }

    /** The configured interval, never below [MIN_FLUSH_INTERVAL_MS]. */
    private val flushInterval: Long = flushInterval.coerceAtLeast(MIN_FLUSH_INTERVAL_MS)

    /** The interval the auto-flush loop actually waits. */
    internal val effectiveFlushIntervalMs: Long get() = flushInterval

    /** The configured batch size, capped at what the server accepts. */
    private val batchSize: Int = batchSize.coerceIn(1, MAX_BATCH_EVENTS)
    private val maxQueue: Int = maxQueueSize.coerceAtLeast(1)

    private var flushJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Ensures only one flush runs at a time. Concurrent triggers (batch-size on
    // enqueue, auto-flush timer, ON_STOP, manual flush) would otherwise read the
    // same rows before either deletes them and double-POST the batch.
    private val flushMutex = Mutex()

    // Inserts in the order track() was called. Two enqueues used to interleave
    // at their suspension points (count, trim, insert), so two events tracked
    // back to back could take each other's places in the queue - a Session
    // Start landing after the event it opened. Mutex is fair: first come,
    // first inserted.
    private val enqueueMutex = Mutex()

    // Consecutive failed flushes; drives the backoff. Reset on a success.
    @Volatile
    private var consecutiveFailures = 0

    /** Earliest moment an unforced flush may send again (backoff / Retry-After). */
    @Volatile
    private var nextAttemptAt = 0L

    /**
     * Earliest moment ANY flush may send again: the server's `Retry-After`.
     * A forced flush skips the SDK's own backoff, never this (contract item
     * 35; scan 3, finding 6 - a forced flush used to ignore it).
     */
    @Volatile
    private var serverRetryAt = 0L

    /**
     * The signed-in user right now. A batch for anyone else that SDK
     * Authentication refuses is dropped (contract items 30 and 39). Wired by
     * the facade.
     */
    var currentUserId: () -> String? = { null }

    /**
     * Sending is held while tracking consent is PENDING or the user opted out:
     * events still queue locally (pending) but nothing leaves the device.
     */
    @Volatile
    var paused: Boolean = false

    /** Set by a 402/403; cleared only by a new process. */
    @Volatile
    private var suspendedForSession = false

    /** Sends one batch and reports what the server said. */
    var onFlush: (suspend (EventBatch) -> BatchSendResult)? = null

    /**
     * Row count of the events table, refreshed after every write.
     *
     * Exists so [getQueueSize] can be a PLAIN function, like iOS and web, rather
     * than a suspend one. Seeded from the table at construction so a queue that
     * persisted across launches reports its real size before the first write.
     */
    @Volatile
    private var cachedCount: Int = 0

    init {
        logger.debug("QueueManager initialized (batchSize=${this.batchSize}, flushInterval=${flushInterval}ms)")
        scope.launch { refreshCount() }
        startAutoFlush()
    }

    private suspend fun refreshCount() {
        try {
            // A count read before a requestClear() is stale once it returns
            // (scan 9, Android N1): the construction-time refresh read the
            // rows the last launch left, a DENIED/opted-out init cleared them,
            // and the refresh then wrote the old count back - getQueueSize()
            // reported deleted events until the next write. Written only when
            // nothing else wrote the count (an insert, a clear) or requested a
            // clear meanwhile.
            val before = countVersion
            val n = eventDao.getCount()
            synchronized(countLock) {
                if (before == countVersion && !clearRequested) cachedCount = n
            }
        } catch (e: Exception) {
            logger.error("Failed to count queue: ${e.message}", e)
        }
    }

    /** Bumped by every authoritative write of [cachedCount] - see [refreshCount]. */
    @Volatile
    private var countVersion = 0L
    private val countLock = Any()

    private fun writeCount(n: Int) = synchronized(countLock) {
        countVersion++
        cachedCount = n
    }

    /**
     * Add event to queue.
     */
    suspend fun enqueue(event: Event, generation: Int = this.generation) {
        // The flush runs OUTSIDE the lock: a slow network must not hold up
        // the next insert.
        if (insertInOrder(event, generation)) {
            logger.debug("Batch size reached, flushing")
            flush()
        }
    }

    /** True when the batch size is reached. */
    private suspend fun insertInOrder(event: Event, generation: Int): Boolean = enqueueMutex.withLock {
        try {
            // Tracked before an opt-out whose clear has since been requested:
            // the opt-out covers it, even though its insert runs after.
            if (generation != this.generation) {
                logger.debug("Event ${event.type} discarded: tracked before the queue was cleared")
                return false
            }
            clearIfRequested()
            // Bound the queue, oldest first - the ONE way an unsent event may
            // be dropped without the server refusing it.
            val currentSize = eventDao.getCount()
            if (currentSize >= maxQueue) {
                val overflow = currentSize - maxQueue + 1
                logger.warn("Queue full ($currentSize), dropping the $overflow oldest event(s)")
                eventDao.deleteOldest(overflow)
            }

            val id = eventDao.insert(EventEntity.fromEvent(event))
            logger.debug("Event enqueued: ${event.type} (id=$id)")

            val queueSize = eventDao.getCount()
            writeCount(queueSize)
            queueSize >= batchSize
        } catch (e: Exception) {
            logger.error("Failed to enqueue event: ${e.message}", e)
            false
        }
    }

    /**
     * Send what is queued.
     *
     * @param force skip the backoff window (an explicit `Joryio.flush()` or the
     *   background flush). Never overrides [paused] or a 402/403 suspension.
     */
    suspend fun flush(force: Boolean = false) {
        // An explicit flush covers what was tracked before it: wait for the
        // inserts already in line (the lock is fair), or flush() right after
        // track() could read the table before the event reached it.
        if (force) enqueueMutex.withLock { }
        if (paused) {
            logger.debug("Sending is paused (consent pending or opted out); events stay queued")
            return
        }
        if (suspendedForSession) return
        if (clock() < serverRetryAt) {
            logger.debug("The server asked to wait; next send attempt in ${serverRetryAt - clock()}ms")
            return
        }
        if (!force && clock() < nextAttemptAt) {
            logger.debug("In backoff; next send attempt in ${nextAttemptAt - clock()}ms")
            return
        }
        // Only one flush at a time. If another flush already holds the lock, this
        // trigger no-ops rather than racing to read/send the same rows.
        if (!flushMutex.tryLock()) {
            logger.debug("Flush already in progress, skipping this trigger")
            return
        }
        try {
            clearIfRequested()
            var rounds = 0
            while (rounds++ < MAX_BATCHES_PER_FLUSH && !paused && !suspendedForSession) {
                val entities = eventDao.getEvents(batchSize)
                if (entities.isEmpty()) break
                if (!sendChunk(withinByteLimit(sameUser(entities)))) break
            }
            refreshCount()
        } catch (e: Exception) {
            logger.error("Failed to flush queue: ${e.message}", e)
        } finally {
            flushMutex.unlock()
        }
    }

    /**
     * The leading events that belong to ONE user (contract item 30). A batch
     * that mixed the person who logged out with the next one was refused as a
     * whole under SDK Authentication (`sub_mismatch`), and retried at the head
     * of the queue forever - blocking the new user's events behind it.
     */
    private fun sameUser(entities: List<EventEntity>): List<EventEntity> {
        val first = entities.firstOrNull() ?: return entities
        val n = entities.indexOfFirst { it.userId != first.userId }
        return if (n < 0) entities else entities.subList(0, n)
    }

    /**
     * The longest prefix of [entities] whose JSON stays under
     * [MAX_REQUEST_BYTES] - at least one, so an oversized event is still
     * offered (and dropped if the server answers 413).
     */
    private fun withinByteLimit(entities: List<EventEntity>): List<EventEntity> {
        var total = 0
        var count = 0
        for (e in entities) {
            val size = wireBytes(e)
            if (count > 0 && total + size > MAX_REQUEST_BYTES) break
            total += size
            count++
        }
        return entities.subList(0, count)
    }

    /**
     * UTF-8 BYTES this event adds to the request, not characters.
     *
     * `String.length` counts UTF-16 units: Hebrew, Greek or Arabic text is two
     * bytes per character on the wire and an emoji four, so a batch "under
     * 900 KB" by length could be 1.8 MB of body - a 413 that the split-and-
     * retry path then had to recover from, one halving at a time.
     */
    internal fun wireBytes(e: EventEntity): Int =
        utf8Length(e.properties) + utf8Length(e.type) + utf8Length(e.userId) +
            utf8Length(e.anonymousId) + utf8Length(e.sessionId) + utf8Length(e.eventId) + ENVELOPE_BYTES

    /**
     * Send one chunk and apply the outcome. True when the queue may continue
     * with the next chunk.
     */
    private suspend fun sendChunk(entities: List<EventEntity>): Boolean {
        val sender = onFlush ?: return false

        // A row that cannot be decoded can never be sent; it must not block
        // everything queued behind it.
        val decoded = entities.mapNotNull { entity ->
            runCatching { entity to entity.toEvent() }.getOrElse {
                logger.error("Dropping an unreadable queued event (id=${entity.id}): ${it.message}")
                eventDao.deleteByIds(listOf(entity.id))
                null
            }
        }
        if (decoded.isEmpty()) return true

        val ids = decoded.map { it.first.id }
        logger.debug("Sending batch of ${decoded.size} events")
        val outcome = sender(EventBatch(events = decoded.map { it.second }, sentAt = Date()))

        return when (outcome) {
            is BatchSendResult.Accepted -> {
                eventDao.deleteByIds(ids)
                onSuccess()
                logger.info("Successfully flushed ${decoded.size} events")
                true
            }
            is BatchSendResult.Partial -> {
                val rejected = outcome.rejectedIndices
                    ?.filter { it in ids.indices }
                    ?.toSet()
                    ?: emptySet()
                val failed = (
                    outcome.failedIndices
                        ?.filter { it in ids.indices }
                        ?.toSet()
                        ?: ids.indices.toSet()
                    ) - rejected
                // Stored AND refused-for-good both leave the queue; only the
                // ones the server could not store right now stay.
                val gone = ids.filterIndexed { i, _ -> i !in failed }
                if (gone.isNotEmpty()) eventDao.deleteByIds(gone)
                rejected.forEach { i ->
                    logger.error(
                        "Server refused event '${decoded[i].second.type}' for good " +
                            "(eventId=${decoded[i].second.eventId}); dropped",
                    )
                }
                if (failed.isEmpty()) {
                    onSuccess()
                    true
                } else {
                    logger.warn("Server stored ${ids.size - failed.size - rejected.size}/${ids.size} events; keeping ${failed.size} for retry")
                    onFailure(null)
                    false
                }
            }
            is BatchSendResult.AuthRefused -> {
                val batchUser = decoded.first().second.userId
                // ANY SDK-authentication reason (contract item 39; scan 4,
                // Android finding S1). Only `sub_mismatch` / `missing` used to
                // drop: the logged-out user's batch refused as `expired` or
                // `invalid` stayed at the head of the queue for good, and the
                // next user's events never left the device.
                val gone = batchUser != null && batchUser != currentUserId() && outcome.reason != null
                if (gone) {
                    // The user these events belong to has logged out; no token
                    // for them will ever arrive. Dropping them is what lets
                    // the next user's events through.
                    eventDao.deleteByIds(ids)
                    logger.error(
                        "SDK authentication refused ${ids.size} event(s) of a user who is no longer signed in; dropped",
                    )
                    true
                } else {
                    onFailure(outcome.retryAfterMs)
                    false
                }
            }
            is BatchSendResult.Rejected -> {
                if (decoded.size == 1) {
                    eventDao.deleteByIds(ids)
                    logger.error(
                        "Server rejected event '${decoded[0].second.type}' (eventId=${decoded[0].second.eventId}); dropped",
                    )
                    true
                } else {
                    // Halve and retry each half, so one malformed event costs
                    // itself and not the batch around it.
                    val mid = decoded.size / 2
                    val first = decoded.subList(0, mid).map { it.first }
                    val second = decoded.subList(mid, decoded.size).map { it.first }
                    sendChunk(first) && sendChunk(second)
                }
            }
            is BatchSendResult.Suspended -> {
                if (!suspendedForSession) {
                    logger.error(
                        "Event ingestion refused (402/403) - sending stops for this session; " +
                            "events stay queued (up to maxQueueSize)",
                    )
                }
                suspendedForSession = true
                false
            }
            is BatchSendResult.Retry -> {
                onFailure(outcome.retryAfterMs)
                false
            }
        }
    }

    private fun onSuccess() {
        consecutiveFailures = 0
        nextAttemptAt = 0
    }

    /** Exponential backoff off flushInterval, capped; Retry-After wins when longer. */
    private fun onFailure(retryAfterMs: Long?) {
        consecutiveFailures++
        val factor = 1L shl consecutiveFailures.coerceAtMost(6)
        val backoff = (flushInterval * factor).coerceAtMost(MAX_AUTO_FLUSH_DELAY_MS)
        val wait = maxOf(backoff, retryAfterMs ?: 0L)
        if (retryAfterMs != null && retryAfterMs > 0) serverRetryAt = clock() + retryAfterMs
        nextAttemptAt = clock() + wait
        logger.warn("Failed to send events; keeping them and retrying in ${wait}ms")
    }

    /**
     * Start automatic flush timer.
     *
     * Internal so the SDK can stop it when the app goes to the background and
     * start it again on return. Safe to call repeatedly: it cancels any
     * existing job first, so a double ON_START cannot leave two loops running.
     */
    internal fun startAutoFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(flushInterval)
                try {
                    flush()
                } catch (e: Exception) {
                    logger.error("Auto-flush failed: ${e.message}", e)
                }
            }
        }
        logger.debug("Auto-flush started (interval=${flushInterval}ms)")
    }

    /** Stop automatic flush timer. */
    fun stopAutoFlush() {
        flushJob?.cancel()
        flushJob = null
        logger.debug("Auto-flush stopped")
    }

    /**
     * How many events are waiting to be sent. A plain call, like iOS and web -
     * see [cachedCount] for why it does not touch the database.
     */
    fun getQueueSize(): Int = cachedCount

    /** The users with events still queued - null when the queue cannot be read. */
    suspend fun queuedUserIds(): Set<String>? = try {
        eventDao.queuedUserIds().toSet()
    } catch (t: Throwable) {
        null
    }

    /**
     * Bumped by every [requestClear]. A caller captures it when an event is
     * TRACKED and passes it to [enqueue], so an event tracked before a clear
     * but inserted after it is still discarded.
     */
    @Volatile
    var generation: Int = 0
        private set

    @Volatile
    private var clearRequested = false
    private val clearMutex = Mutex()

    /**
     * Discard everything queued, effective IMMEDIATELY: the deletion itself
     * runs on the database thread, but no flush or enqueue after this call can
     * see the old events - each finishes the pending clear first.
     *
     * optOut() used to launch the delete and return, so an optIn() right after
     * it (a consent banner toggled twice) could flush the events the opt-out
     * was supposed to discard before the delete ran.
     */
    fun requestClear() {
        generation++
        clearRequested = true
        writeCount(0)
        scope.launch { clearIfRequested() }
    }

    private suspend fun clearIfRequested() {
        if (!clearRequested) return
        clearMutex.lock()
        try {
            if (clearRequested) {
                clear()
                clearRequested = false
            }
        } finally {
            clearMutex.unlock()
        }
    }

    /** Clear all events from queue. */
    suspend fun clear() {
        try {
            eventDao.deleteAll()
            writeCount(0)
            logger.info("Queue cleared")
        } catch (e: Exception) {
            logger.error("Failed to clear queue: ${e.message}", e)
        }
    }

    /** Shutdown queue manager. */
    fun shutdown() {
        stopAutoFlush()
        scope.cancel()
        logger.debug("QueueManager shutdown")
    }
}
