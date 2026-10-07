package io.joryio.sdk.core

import io.joryio.sdk.network.GenericResponse
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.network.OutboxEntry
import kotlinx.coroutines.sync.Mutex

/**
 * What the server did with one outbox write, reduced to what the outbox must
 * DO about it - the same mapping the event queue applies (shared SDK contract
 * items 3, 15, 28 and 30).
 */
internal sealed class OutboxResult {
    /** 2xx and not `success: false`: stored. Remove the entry. */
    object Done : OutboxResult()

    /**
     * Never accepted as it is: 400 / 409 / 413 / 422 and other permanent 4xx,
     * a 403 (the identity policy refused THIS write - contract item 28), or an
     * SDK-authentication 401 (any reason) for a user who is no longer the
     * current one (contract items 30 and 39). Dropped with a log line so it cannot block the
     * writes behind it.
     */
    data class Drop(val reason: String) : OutboxResult()

    /**
     * Keep it, stop here (order matters), back off.
     *
     * @param counts an ANSWERED refusal that may never heal (401, 404,
     *   `success: false`): counted against the entry's attempt cap. Offline,
     *   402, 408, 429 and 5xx are about the moment and never count - an
     *   offline device must not lose writes (contract item 3).
     * @param profileMissing a 404 - the profile the write is about does not
     *   exist yet (a subscription change before the first event reached the
     *   server). Retried after the event queue is flushed (contract item 28).
     * @param suspended a 402: the organisation's ingestion is suspended. A
     *   withdrawal is still accepted then, so the drain steps past this entry
     *   to the withdrawals behind it instead of stopping (contract item 50).
     */
    data class Retry(
        val retryAfterMs: Long? = null,
        val reason: String,
        val counts: Boolean = false,
        val profileMissing: Boolean = false,
        val suspended: Boolean = false,
    ) : OutboxResult()

    companion object {
        /**
         * @param forCurrentUser false when the write carries a userId that is
         *   no longer the signed-in one (a logout happened since).
         */
        fun from(result: NetworkResult<GenericResponse>, forCurrentUser: Boolean = true): OutboxResult = when (result) {
            is NetworkResult.Success ->
                // `success: false` is a FAILURE, never an acknowledgement:
                // the backend answers 200 `{ success: false }` when it could
                // not store the write (contract item 15).
                if (result.data.success == false) {
                    Retry(reason = result.data.message ?: "success: false", counts = true)
                } else {
                    Done
                }
            is NetworkResult.Error -> when (val code = result.code) {
                null -> Retry(result.retryAfterMs, result.message)
                401 -> {
                    // ANY SDK-authentication reason (contract item 39; scan 4,
                    // Android finding S1): no token for a user who logged out
                    // will ever arrive, whether the server said `missing`,
                    // `sub_mismatch`, `expired` or `invalid`. Only the first
                    // two used to drop, so the previous user's write with an
                    // expired token sat at the head of the outbox.
                    if (!forCurrentUser && result.authReason != null) {
                        Drop("SDK authentication refused a write for a user who is no longer signed in")
                    } else {
                        Retry(result.retryAfterMs, result.message, counts = true)
                    }
                }
                403 -> Drop("refused by the identity policy (403)")
                404 -> Retry(result.retryAfterMs, "no profile yet (404)", counts = true, profileMissing = true)
                // Never counted (contract item 50): a suspension is about the
                // account, not this write.
                402 -> Retry(result.retryAfterMs, result.message, suspended = true)
                408, 429 -> Retry(result.retryAfterMs, result.message)
                in 400..499 -> Drop("HTTP $code")
                else -> Retry(result.retryAfterMs, "HTTP $code")
            }
        }
    }
}

/**
 * Writes that are not events - identify, addAlias, attribute increments and
 * unsets, subscription changes, the opt-out marker - kept until the server
 * acknowledges them.
 *
 * They used to be fire-and-forget: a failure, offline, or a 200 that said
 * `success: false` was logged and the write was gone. An identify lost that
 * way never merged the anonymous profile; an unsubscribe lost that way kept
 * mailing someone who had said stop. Events have had a durable queue all
 * along; this gives the other writes the same treatment.
 *
 * Also replaces the in-memory list of requests held while tracking consent is
 * PENDING: held writes now live here, so they survive a restart too.
 *
 * Sent strictly in order, one at a time: an identify must reach the server
 * before the alias made after it. A write the server refuses outright
 * (400/403/413/422) is dropped, and one it keeps refusing (401, 404) is
 * dropped after [MAX_ATTEMPTS_PER_ENTRY] answers, so no write blocks the rest
 * forever.
 */
internal class WriteOutbox(
    private val storage: StorageManager?,
    private val logger: Logger,
    private val maxEntries: Int = MAX_ENTRIES,
    private val baseBackoffMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttemptsPerEntry: Int = MAX_ATTEMPTS_PER_ENTRY,
    /**
     * Entries the size bound evicts last: withdrawals (an unsubscribe, the
     * opt-out marker) are always delivered (contract item 36), so a full
     * outbox drops the oldest OTHER write first.
     */
    private val isWithdrawal: (OutboxEntry) -> Boolean = { false },
) {
    private val lock = Any()
    private val entries = ArrayDeque<OutboxEntry>()
    private val drainMutex = Mutex()
    private val gson = io.joryio.sdk.network.WireJson.gson

    @Volatile
    private var failures = 0

    /** Earliest moment an unforced drain may send again (the SDK's own backoff). */
    @Volatile
    private var nextAttemptAt = 0L

    /**
     * Earliest moment ANY drain may send again: a server `Retry-After`. A
     * forced drain skips the SDK's own backoff, never this (contract item 35;
     * scan 3, finding 6).
     */
    @Volatile
    private var serverRetryAt = 0L

    /**
     * A drain was asked for while another held the lock. The running drain
     * goes round again before it returns, so an entry added at the very end of
     * its loop is not left waiting for the next trigger (scan 3, finding 7).
     */
    @Volatile
    private var drainAgain = false

    init {
        restore()
    }

    private fun restore() {
        val json = storage?.getOutbox() ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<OutboxEntry>>() {}.type
            val restored: List<OutboxEntry>? = gson.fromJson(json, type)
            restored?.filter { it.path.isNotBlank() && it.body.isNotBlank() }?.let { synchronized(lock) { entries.addAll(it) } }
        } catch (e: Exception) {
            logger.warn("Discarding an unreadable write outbox: ${e.message}")
            storage.setOutbox(null)
        }
    }

    private fun persistLocked() {
        storage?.setOutbox(if (entries.isEmpty()) null else gson.toJson(entries.toList()))
    }

    /**
     * @param alsoReplaces a second key this write supersedes: the queued
     *   anonymous writes about the same thing that would be sent as the same
     *   identified user (contract v6 64).
     */
    fun add(entry: OutboxEntry, alsoReplaces: String? = null) {
        synchronized(lock) {
            // A newer write about the same thing (one person's subscription
            // to one channel or group) replaces a queued older one (contract
            // item 37): the older can then never land after it - not even
            // when the newer is a withdrawal sent ahead of writes held back by
            // consent (see [drain]). One already on the wire is not stopped;
            // it lands first, because drains run one at a time, in order.
            entry.key?.let { key ->
                if (entries.removeAll { it.key == key || (alsoReplaces != null && it.key == alsoReplaces) }) {
                    logger.debug("A newer ${entry.kind} write replaces a queued one")
                }
            }
            entries.addLast(entry)
            while (entries.size > maxEntries) {
                val victim = entries.firstOrNull { !isWithdrawal(it) } ?: entries.first()
                entries.remove(victim)
                logger.warn("Write outbox full; dropping the oldest (${victim.kind})")
            }
            persistLocked()
        }
    }

    /** Write the held entries to disk again (after the store was cleared under them). */
    fun persist() {
        synchronized(lock) { persistLocked() }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun snapshot(): List<OutboxEntry> = synchronized(lock) { entries.toList() }

    fun any(predicate: (OutboxEntry) -> Boolean): Boolean = synchronized(lock) { entries.any(predicate) }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            persistLocked()
        }
        failures = 0
        nextAttemptAt = 0
    }

    fun removeIf(predicate: (OutboxEntry) -> Boolean) {
        synchronized(lock) {
            entries.removeAll(predicate)
            persistLocked()
        }
    }

    /**
     * The first entry that may be sent now. Entries held back by the gate
     * (consent pending or denied, opted out) are stepped over, so a
     * withdrawal queued behind them still goes out (contract item 36); they
     * are about other subjects, or were replaced by it (see [add]).
     */
    private fun nextSendable(canSend: (OutboxEntry) -> Boolean): OutboxEntry? =
        synchronized(lock) { entries.firstOrNull(canSend) }

    private fun remove(entry: OutboxEntry) {
        synchronized(lock) {
            // By id: a clear() or reset() may have replaced the list meanwhile.
            entries.removeAll { it.id == entry.id }
            persistLocked()
        }
    }

    private fun replace(entry: OutboxEntry) {
        synchronized(lock) {
            val i = entries.indexOfFirst { it.id == entry.id }
            if (i >= 0) {
                entries[i] = entry
                persistLocked()
            }
        }
    }

    /**
     * Send what is held, in order, while [canSend] allows.
     *
     * @param canSend whether THIS entry may be sent now (withdrawals - an
     *   unsubscribe, the opt-out marker - go out while the user is opted out
     *   or consent is not granted; everything else waits).
     * @param onProfileMissing runs before a 404'd write is retried - the
     *   caller flushes the event queue, which is what creates the profile.
     * @param onDone an entry the server acknowledged.
     * @return the delay after which a retry should run, or null when there is
     *   nothing left to retry (drained, or sending is not allowed - consent
     *   pending / opted out, which have their own release triggers).
     */
    suspend fun drain(
        force: Boolean = false,
        canSend: (OutboxEntry) -> Boolean,
        onProfileMissing: suspend () -> Unit = {},
        onDone: (OutboxEntry) -> Unit = {},
        send: suspend (OutboxEntry) -> OutboxResult,
    ): Long? {
        val now = clock()
        if (now < serverRetryAt) return serverRetryAt - now
        if (!force && now < nextAttemptAt) return nextAttemptAt - now
        while (true) {
            if (!drainMutex.tryLock()) {
                drainAgain = true
                return null
            }
            val wait = try {
                drainAgain = false
                drainLocked(canSend, onProfileMissing, onDone, send)
            } finally {
                drainMutex.unlock()
            }
            if (wait != null || !drainAgain) return wait
        }
    }

    private suspend fun drainLocked(
        canSend: (OutboxEntry) -> Boolean,
        onProfileMissing: suspend () -> Unit,
        onDone: (OutboxEntry) -> Unit,
        send: suspend (OutboxEntry) -> OutboxResult,
    ): Long? {
        // Set by a 402 on a non-withdrawal (contract item 50; scan 5, Android
        // S1): the account's ingestion is suspended, which the server applies
        // to every write EXCEPT withdrawals. The rest of this drain sends only
        // the withdrawals behind it - an unsubscribe or the opt-out marker
        // queued after an identify used to wait behind that identify for as
        // long as the suspension lasted, because a 402 never counts and the
        // outbox is ordered. The skipped writes keep their place and are
        // retried after the backoff this returns.
        var suspendedWait: Long? = null
        while (true) {
            val entry = nextSendable { canSend(it) && (suspendedWait == null || isWithdrawal(it)) }
                ?: return suspendedWait
            when (val outcome = send(entry)) {
                is OutboxResult.Done -> {
                    remove(entry)
                    failures = 0
                    nextAttemptAt = 0
                    try {
                        onDone(entry)
                    } catch (e: Exception) {
                        logger.warn("Outbox acknowledgement hook failed: ${e.message}")
                    }
                }
                is OutboxResult.Drop -> {
                    remove(entry)
                    logger.error("Server refused a ${entry.kind} write (${outcome.reason}); dropped")
                }
                is OutboxResult.Retry -> {
                    if (outcome.suspended && !isWithdrawal(entry)) {
                        if (suspendedWait == null) {
                            failures++
                            val backoff = (baseBackoffMs * (1L shl failures.coerceAtMost(7))).coerceAtMost(MAX_BACKOFF_MS)
                            // A Retry-After on a 402 is about the whole account;
                            // still not applied to withdrawals.
                            val wait = maxOf(backoff, outcome.retryAfterMs ?: 0L)
                            nextAttemptAt = clock() + wait
                            suspendedWait = wait
                            logger.warn(
                                "${entry.kind} not stored (${outcome.reason}); account suspended - " +
                                    "sending only withdrawals, retrying the rest in ${wait}ms",
                            )
                        }
                        continue
                    }
                    if (outcome.counts) {
                        val attempts = entry.attempts + 1
                        val cap = if (outcome.profileMissing) MAX_NOT_FOUND_ATTEMPTS else maxAttemptsPerEntry
                        if (attempts >= cap) {
                            // Per-entry cap: one write the server keeps
                            // refusing must not hold every later write.
                            remove(entry)
                            logger.error(
                                "${entry.kind} write still refused after $attempts attempts (${outcome.reason}); dropped",
                            )
                            continue
                        }
                        replace(entry.copy(attempts = attempts))
                    }
                    if (outcome.profileMissing) {
                        try {
                            onProfileMissing()
                        } catch (e: Exception) {
                            logger.warn("Could not flush events before retrying a ${entry.kind} write: ${e.message}")
                        }
                    }
                    failures++
                    val backoff = (baseBackoffMs * (1L shl failures.coerceAtMost(7))).coerceAtMost(MAX_BACKOFF_MS)
                    val serverWait = outcome.retryAfterMs ?: 0L
                    if (serverWait > 0) serverRetryAt = clock() + serverWait
                    val wait = maxOf(backoff, serverWait)
                    nextAttemptAt = clock() + wait
                    logger.warn("${entry.kind} not stored (${outcome.reason}); keeping it, retrying in ${wait}ms")
                    return wait
                }
            }
        }
    }

    companion object {
        /** Same bound as the pre-initialization buffer. */
        const val MAX_ENTRIES = 1000
        const val MAX_BACKOFF_MS = 300_000L

        /**
         * Answered refusals one entry may collect before it is dropped - the
         * same caps on every SDK (contract item 28): 10 for any retryable
         * answer, 5 for a 404 "no profile yet". Being offline never counts.
         */
        const val MAX_ATTEMPTS_PER_ENTRY = 10
        const val MAX_NOT_FOUND_ATTEMPTS = 5
    }
}
