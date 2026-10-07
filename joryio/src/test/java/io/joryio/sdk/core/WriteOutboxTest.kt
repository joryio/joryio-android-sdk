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
