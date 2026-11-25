package io.joryio.sdk.core

import io.joryio.sdk.LogLevel
import io.joryio.sdk.SdkAuthErrorReason
import io.joryio.sdk.network.GenericResponse
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.network.OutboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outbox's decisions, without a device (fix round 3). */
class WriteOutboxTest {

    private var now = 1_000_000L
    private val logger = Logger(false, LogLevel.ERROR)
    private fun outbox() = WriteOutbox(storage = null, logger = logger, clock = { now })
    private fun entry(id: String, kind: String = "alias") = OutboxEntry(id, kind, "v1/$kind", """{"id":"$id"}""")

    // ── 1.4.0-beta.14: a write handed over after its own direct send failed ──

    private fun exposures(id: String) = OutboxEntry(id, "flag_exposures", "v1/flags/exposures", "{\"idempotencyKey\":\"$id\"}")

    /** Drain once (forced: the outbox's own backoff is not what holds it); the ids sent and the wait returned. */
    private fun drainOnce(box: WriteOutbox, answer: (OutboxEntry) -> OutboxResult = { OutboxResult.Done }): Pair<List<String>, Long?> = runBlocking {
        val sent = mutableListOf<String>()
        val wait = box.drain(force = true, canSend = { true }) { e -> sent.add(e.id); answer(e) }
        sent to wait
    }

    @Test
    fun `after a 503 on the direct send the first outbox attempt waits the first backoff step`() {
        // Fails before: the kick after enqueue re-sent it 2-20 ms after the failure.
        val box = outbox()
        box.addAfterFailedSend(exposures("x"))
        val (sent, wait) = drainOnce(box)
        assertEquals(emptyList<String>(), sent)
        assertEquals("the first backoff step (2 s x 2^1)", 4_000L, wait)
        now += 3_999
        assertEquals(emptyList<String>(), drainOnce(box).first)
        now += 1
        assertEquals(listOf("x"), drainOnce(box).first)
        assertEquals(0, box.size())
    }

    @Test
    fun `it counts as failed once - the backoff after its first outbox failure is the second step`() {
        val box = outbox()
        box.addAfterFailedSend(exposures("x"))
        now += 4_000
        val (sent, wait) = drainOnce(box) { OutboxResult.from(NetworkResult.Error("busy", true, code = 503)) }
        assertEquals(listOf("x"), sent)
        assertEquals(8_000L, wait)
    }

    @Test
    fun `a 429 with Retry-After 10 - the next attempt is not before 10 s`() {
        val box = outbox()
        box.addAfterFailedSend(exposures("x"), retryAfterMs = 10_000)
        assertEquals(10_000L, drainOnce(box).second)
        now += 9_999
        assertEquals(emptyList<String>(), drainOnce(box).first)
        now += 1
        assertEquals(listOf("x"), drainOnce(box).first)
    }

    @Test
    fun `a Retry-After given as an HTTP date is honoured`() {
        val box = outbox()
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(now + 30_000))
        val retryAfter = io.joryio.sdk.network.NetworkClient.parseRetryAfterMs(date, now)
        assertEquals(30_000L, retryAfter)
        box.addAfterFailedSend(exposures("x"), retryAfter)
        now += 29_999
        assertEquals(emptyList<String>(), drainOnce(box).first)
        now += 1
        assertEquals(listOf("x"), drainOnce(box).first)
    }

    @Test
    fun `a Retry-After longer than the outbox maximum is capped at it`() {
        val box = outbox()
        box.addAfterFailedSend(exposures("x"), retryAfterMs = 3_600_000)
        assertEquals(WriteOutbox.MAX_BACKOFF_MS, drainOnce(box).second)
        now += WriteOutbox.MAX_BACKOFF_MS
        assertEquals(listOf("x"), drainOnce(box).first)
    }

    @Test
    fun `only that entry waits - the writes queued before and after it go now`() {
        val box = outbox()
        box.add(entry("before", "identify"))
        box.addAfterFailedSend(exposures("x"), retryAfterMs = 10_000)
        box.add(entry("after", "alias"))
        val (sent, wait) = drainOnce(box)
        assertEquals(listOf("before", "after"), sent)
        assertEquals(10_000L, wait)
        assertEquals(1, box.size())
    }

    @Test
    fun `the first wait is stored with the entry, so a relaunch waits it out too`() {
        val gson = io.joryio.sdk.network.WireJson.gson
        val box = outbox()
        box.addAfterFailedSend(exposures("x"))
        val stored = gson.toJson(box.snapshot())
        val type = object : com.google.gson.reflect.TypeToken<List<OutboxEntry>>() {}.type
        val restored: List<OutboxEntry> = gson.fromJson(stored, type)
        assertEquals(now + 4_000, restored.single().firstAttemptNotBefore)
        assertEquals(1, restored.single().failures)
        // An outbox stored by an earlier build has neither field: no wait.
        val old: List<OutboxEntry> = gson.fromJson("""[{"id":"o","kind":"alias","path":"v1/alias","body":"{}"}]""", type)
        assertEquals(0L, old.single().firstAttemptNotBefore)
        assertEquals(0, old.single().failures)
    }

    @Test
    fun `a first wait further away than the maximum means the clock moved - it goes now`() {
        val box = outbox()
        box.add(exposures("x").copy(firstAttemptNotBefore = now + WriteOutbox.MAX_BACKOFF_MS + 1))
        assertEquals(listOf("x"), drainOnce(box).first)
    }

    @Test
    fun `a write refused with 403 is dropped and the next one goes out`() = runBlocking {
        // Scan 3 finding 2. Fails before: 403 was Retry, the head of an
        // ordered outbox, so every later write waited forever.
        val box = outbox()
        box.add(entry("refused"))
        box.add(entry("next", "identify"))
        val sent = mutableListOf<String>()
        box.drain(force = true, canSend = { true }) { e ->
            sent.add(e.id)
            if (e.id == "refused") OutboxResult.from(NetworkResult.Error("forbidden", false, code = 403)) else OutboxResult.Done
        }
        assertEquals(listOf("refused", "next"), sent)
        assertEquals(0, box.size())
    }

    @Test
    fun `a write refused over and over is dropped after the per-entry cap`() = runBlocking {
        // Contract item 28. Fails before: a 401 that never healed blocked the
        // outbox for good.
        val box = outbox()
        box.add(entry("stuck"))
        box.add(entry("next", "identify"))
        val sent = mutableListOf<String>()
        repeat(WriteOutbox.MAX_ATTEMPTS_PER_ENTRY + 1) {
            box.drain(force = true, canSend = { true }) { e ->
                sent.add(e.id)
                if (e.id == "stuck") OutboxResult.from(NetworkResult.Error("401", false, code = 401)) else OutboxResult.Done
            }
        }
        assertEquals(WriteOutbox.MAX_ATTEMPTS_PER_ENTRY, sent.count { it == "stuck" })
        assertEquals("next", sent.last())
        assertEquals(0, box.size())
    }

    @Test
    fun `offline never counts against the cap`() = runBlocking {
        val box = outbox()
        box.add(entry("w"))
        repeat(20) {
            box.drain(force = true, canSend = { true }) { OutboxResult.from(NetworkResult.Error("offline", true, code = null)) }
        }
        assertEquals(1, box.size())
    }

    @Test
    fun `a 404 flushes the events first and is retried, then dropped after the cap`() = runBlocking {
        // Scan 3 finding 1. Fails before: 404 was a permanent 4xx and the
        // subscription change was dropped on the first answer.
        val box = outbox()
        box.add(entry("sub", "subscription"))
        var flushes = 0
        var calls = 0
        box.drain(force = true, canSend = { true }, onProfileMissing = { flushes++ }) {
            calls++
            OutboxResult.from(NetworkResult.Error("no profile", false, code = 404))
        }
        assertEquals(1, flushes)
        assertEquals("kept for a retry", 1, box.size())
        box.drain(force = true, canSend = { true }, onProfileMissing = { flushes++ }) { calls++; OutboxResult.Done }
        assertEquals(0, box.size())
        assertEquals(2, calls)
    }

    @Test
    fun `a 404 is dropped after 5 answers, sooner than other refusals (same caps on every SDK)`() = runBlocking {
        val box = outbox()
        box.add(entry("sub", "subscription"))
        var calls = 0
        repeat(WriteOutbox.MAX_ATTEMPTS_PER_ENTRY) {
            box.drain(force = true, canSend = { true }, onProfileMissing = {}) {
                calls++
                OutboxResult.from(NetworkResult.Error("no profile", false, code = 404))
            }
        }
        assertEquals(WriteOutbox.MAX_NOT_FOUND_ATTEMPTS, calls)
        assertEquals(0, box.size())
    }

    @Test
    fun `sub_mismatch drops a write of a user who logged out, keeps the current user's`() {
        val r = NetworkResult.Error("401", false, code = 401, authReason = SdkAuthErrorReason.SUB_MISMATCH)
        assertTrue(OutboxResult.from(r, forCurrentUser = false) is OutboxResult.Drop)
        assertTrue(OutboxResult.from(r, forCurrentUser = true) is OutboxResult.Retry)
        assertTrue(OutboxResult.from(NetworkResult.Success(GenericResponse(success = false))) is OutboxResult.Retry)
    }

    @Test
    fun `a forced drain waits out the server's Retry-After`() = runBlocking {
        // Scan 3 finding 6. Fails before: force skipped Retry-After too.
        val box = outbox()
        box.add(entry("w"))
        var calls = 0
        box.drain(force = true, canSend = { true }) {
            calls++
            OutboxResult.from(NetworkResult.Error("busy", true, code = 503, retryAfterMs = 60_000))
        }
        val wait = box.drain(force = true, canSend = { true }) { calls++; OutboxResult.Done }
        assertEquals(1, calls)
        assertTrue(wait != null && wait > 0)
        now += 60_001
        box.drain(force = true, canSend = { true }) { calls++; OutboxResult.Done }
        assertEquals(2, calls)
    }

    @Test
    fun `a drain asked for while another is finishing is not lost`() = runBlocking {
        // Scan 3 finding 7. The running drain has just decided it may not
        // send (consent not granted yet); at that very moment sending becomes
        // allowed and a second drain is requested - which loses the lock race.
        // Fails before: the second request was dropped and the entry waited
        // for some unrelated trigger.
        val box = outbox()
        box.add(entry("w"))
        var allowed = false
        val sent = mutableListOf<String>()
        val send: suspend (OutboxEntry) -> OutboxResult = { e -> sent.add(e.id); OutboxResult.Done }
        box.drain(force = true, canSend = {
            if (!allowed) {
                allowed = true
                // The trigger that fires when sending becomes allowed.
                runBlocking { box.drain(force = true, canSend = { true }, send = send) }
                false
            } else {
                true
            }
        }, send = send)
        assertEquals(listOf("w"), sent)
        assertEquals(0, box.size())
    }
}
