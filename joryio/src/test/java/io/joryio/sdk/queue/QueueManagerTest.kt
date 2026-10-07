package io.joryio.sdk.queue

import io.joryio.sdk.LogLevel
import io.joryio.sdk.core.Logger
import io.joryio.sdk.models.Event
import io.joryio.sdk.models.EventBatch
import io.joryio.sdk.network.BatchResponse
import io.joryio.sdk.network.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory EventDao, so the queue's decisions are tested without a device. */
internal class FakeEventDao : EventDao {
    val rows = mutableListOf<EventEntity>()
    private var nextId = 1L

    override suspend fun insert(event: EventEntity): Long = synchronized(rows) {
        val id = nextId++
        rows.add(event.copy(id = id, createdAt = java.util.Date(id)))
        id
    }
    override suspend fun insertAll(events: List<EventEntity>): List<Long> = events.map { insert(it) }
    override suspend fun getEvents(limit: Int): List<EventEntity> = synchronized(rows) { rows.sortedBy { it.id }.take(limit) }
    override suspend fun getAllEvents(): List<EventEntity> = synchronized(rows) { rows.sortedBy { it.id } }
    override suspend fun deleteByIds(ids: List<Long>) { synchronized(rows) { rows.removeAll { it.id in ids } } }
    override suspend fun deleteAll() { synchronized(rows) { rows.clear() } }
    override suspend fun getCount(): Int = synchronized(rows) { rows.size }
    override suspend fun queuedUserIds(): List<String> = synchronized(rows) { rows.mapNotNull { it.userId }.distinct() }
    override suspend fun deleteOldest(count: Int) {
        synchronized(rows) { rows.sortedBy { it.id }.take(count).map { it.id }.toSet().let { d -> rows.removeAll { it.id in d } } }
    }
}

class QueueManagerTest {

    private val dao = FakeEventDao()
    private var now = 1_000_000L
    private val sent = mutableListOf<List<String>>()
    private var queue: QueueManager? = null

    private fun queue(batchSize: Int = 50, maxQueueSize: Int = 1000, answer: (EventBatch) -> BatchSendResult): QueueManager {
        val q = QueueManager(
            eventDao = dao,
            logger = Logger(false, LogLevel.ERROR),
            batchSize = batchSize,
            flushInterval = 60_000,
            maxQueueSize = maxQueueSize,
            clock = { now },
        )
        q.stopAutoFlush()
        q.onFlush = { batch ->
            sent.add(batch.events.map { it.type })
            answer(batch)
        }
        queue = q
        return q
    }

    @After
    fun tearDown() {
        queue?.shutdown()
    }

    private fun event(name: String) = Event(type = name, anonymousId = "a", sessionId = "s")

    private fun queued() = dao.rows.sortedBy { it.id }.map { it.type }

    @Test
    fun `the request size limit counts UTF-8 bytes, not characters`() = runBlocking {
        // Fails before fix round 2: 300k Hebrew characters are 600 KB on the
        // wire, but counted as 300k, so two such events went out together as
        // a ~1.2 MB request.
        val hebrew = "ש".repeat(300_000)
        val q = queue(batchSize = 100) { BatchSendResult.Accepted }
        q.enqueue(Event(type = "h1", properties = mapOf("t" to hebrew), anonymousId = "a", sessionId = "s"))
        q.enqueue(Event(type = "h2", properties = mapOf("t" to hebrew), anonymousId = "a", sessionId = "s"))
        q.flush(force = true)
        assertEquals(listOf(listOf("h1"), listOf("h2")), sent)
        assertEquals(4, QueueManager.utf8Length("😀"))
        assertEquals(2, QueueManager.utf8Length("ש"))
    }

    @Test
    fun `offline never deletes events, however many attempts fail`() = runBlocking {
        // Fails on the old queue: retryCount reached maxRetries (3) and
        // deleteExpiredEvents removed the events.
        val q = queue(batchSize = 100) { BatchSendResult.Retry() }
        repeat(3) { q.enqueue(event("e$it")) }
        repeat(10) { q.flush(force = true) }
        assertEquals(listOf("e0", "e1", "e2"), queued())
    }

    @Test
    fun `a failed send backs off instead of retrying on every trigger`() = runBlocking {
        val q = queue(batchSize = 100) { BatchSendResult.Retry(retryAfterMs = 120_000) }
        q.enqueue(event("a"))
        q.flush()
        q.flush()
        q.flush()
        assertEquals(1, sent.size)
        now += 120_001
        q.flush()
        assertEquals(2, sent.size)
    }

    @Test
    fun `200 success false keeps only the failed indices`() = runBlocking {
        // Fails on the old queue: any 2xx deleted the whole batch.
        val q = queue(batchSize = 100) { BatchSendResult.Partial(failedIndices = listOf(1)) }
        listOf("a", "b", "c").forEach { q.enqueue(event(it)) }
        q.flush(force = true)
        assertEquals(listOf("b"), queued())
    }

    @Test
    fun `200 success false without indices keeps the whole batch`() = runBlocking {
        val q = queue(batchSize = 100) { BatchSendResult.Partial(failedIndices = null) }
        listOf("a", "b").forEach { q.enqueue(event(it)) }
        q.flush(force = true)
        assertEquals(listOf("a", "b"), queued())
    }

    @Test
    fun `a rejected batch is split so one bad event does not block the queue`() = runBlocking {
        val q = queue(batchSize = 100) { batch ->
            if (batch.events.any { it.type == "bad" }) BatchSendResult.Rejected else BatchSendResult.Accepted
        }
        listOf("a", "b", "bad", "c").forEach { q.enqueue(event(it)) }
        q.flush(force = true)
        assertEquals(emptyList<String>(), queued())
        // Every good event was delivered; only "bad" was dropped.
        val delivered = sent.filter { it.none { n -> n == "bad" } }.flatten().toSet()
        assertEquals(setOf("a", "b", "c"), delivered)
    }

    @Test
    fun `402 or 403 stops sending for the session and keeps the events`() = runBlocking {
        val q = queue(batchSize = 100) { BatchSendResult.Suspended }
        q.enqueue(event("a"))
        q.flush(force = true)
        q.flush(force = true)
        assertEquals(1, sent.size)
        assertEquals(listOf("a"), queued())
    }

    @Test
    fun `paused (consent pending) sends nothing and keeps everything`() = runBlocking {
        val q = queue(batchSize = 1) { BatchSendResult.Accepted }
        q.paused = true
        q.enqueue(event("a"))
        q.flush(force = true)
        assertTrue(sent.isEmpty())
        q.paused = false
        q.flush(force = true)
        assertEquals(listOf(listOf("a")), sent)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `maxQueueSize drops the oldest, and only the overflow`() = runBlocking {
        val q = queue(batchSize = 1000, maxQueueSize = 3) { BatchSendResult.Retry() }
        listOf("a", "b", "c", "d").forEach { q.enqueue(event(it)) }
        assertEquals(listOf("b", "c", "d"), queued())
    }

    @Test
    fun `a batch never exceeds the server's 500 events`() = runBlocking {
        val q = queue(batchSize = 5000, maxQueueSize = 5000) { BatchSendResult.Accepted }
        q.paused = true
        repeat(1200) { q.enqueue(event("e$it")) }
        q.paused = false
        q.flush(force = true)
        assertEquals(listOf(500, 500, 200), sent.map { it.size })
    }

    @Test
    fun `a clear is effective immediately, even for an event tracked before it and inserted after`() = runBlocking {
        val q = queue(batchSize = 100) { BatchSendResult.Accepted }
        q.enqueue(event("old"))
        val trackedBeforeClear = q.generation
        q.requestClear()
        q.enqueue(event("late"), trackedBeforeClear)
        q.enqueue(event("new"))
        q.flush(force = true)
        assertEquals(listOf(listOf("new")), sent)
    }

    @Test
    fun `responses map onto the contract`() {
        assertEquals(BatchSendResult.Accepted, BatchSendResult.from(NetworkResult.Success(BatchResponse(success = true, processed = 1))))
        assertEquals(
            BatchSendResult.Partial(listOf(0)),
            BatchSendResult.from(NetworkResult.Success(BatchResponse(success = false, failedIndices = listOf(0)))),
        )
        for (code in listOf(400, 413, 422)) {
            assertEquals(BatchSendResult.Rejected, BatchSendResult.from(NetworkResult.Error("x", false, code = code)))
        }
        for (code in listOf(402, 403)) {
            assertEquals(BatchSendResult.Suspended, BatchSendResult.from(NetworkResult.Error("x", false, code = code)))
        }
        assertTrue(BatchSendResult.from(NetworkResult.Error("x", false, code = 401)) is BatchSendResult.AuthRefused)
        for (code in listOf(null, 429, 500, 503)) {
            assertTrue(BatchSendResult.from(NetworkResult.Error("x", true, code = code)) is BatchSendResult.Retry)
        }
    }

    // ── Fix round 3 (scan 3 + contract addendum v3) ───────────────────

    private fun eventOf(name: String, userId: String?) = Event(type = name, anonymousId = "a", sessionId = "s", userId = userId)

    @Test
    fun `events the server refuses for good are dropped, the failed ones kept`() = runBlocking {
        // Contract item 29. Fails before: rejectedIndices was not read, so a
        // permanently refused event (failedIndices) was re-sent forever.
        val q = queue(batchSize = 100) { BatchSendResult.Partial(failedIndices = listOf(1), rejectedIndices = listOf(0)) }
        listOf("refused", "retry", "stored").forEach { q.enqueue(event(it)) }
        q.flush(force = true)
        assertEquals(listOf("retry"), queued())
        assertEquals(
            BatchSendResult.Partial(listOf(1), listOf(0)),
            BatchSendResult.from(NetworkResult.Success(BatchResponse(success = false, failedIndices = listOf(1), rejectedIndices = listOf(0)))),
        )
    }

    @Test
    fun `a forced flush skips the SDK's backoff but never the server's Retry-After`() = runBlocking {
        // Scan 3 finding 6 / contract item 35. Fails before: force skipped both.
        var answer: BatchSendResult = BatchSendResult.Retry(retryAfterMs = 120_000)
        val q = queue(batchSize = 100) { answer }
        q.enqueue(event("a"))
        q.flush(force = true)
        answer = BatchSendResult.Accepted
        q.flush(force = true)
        assertEquals("Retry-After is honoured by a forced flush", 1, sent.size)
        now += 120_001
        q.flush(force = true)
        assertEquals(2, sent.size)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a batch never mixes users`() = runBlocking {
        // Contract item 30. Fails before: one batch carried the user who
        // logged out and the next one.
        val q = queue(batchSize = 100) { BatchSendResult.Accepted }
        q.enqueue(eventOf("a1", "A"))
        q.enqueue(eventOf("a2", "A"))
        q.enqueue(eventOf("b1", "B"))
        q.flush(force = true)
        assertEquals(listOf(listOf("a1", "a2"), listOf("b1")), sent)
    }

    @Test
    fun `sub_mismatch for a user who logged out drops their events and lets the next user through`() = runBlocking {
        // Contract item 30. Fails before: the 401 was retried at the head of
        // the queue forever and B's events never left.
        val q = queue(batchSize = 100) { batch ->
            if (batch.events.first().userId == "A") {
                BatchSendResult.AuthRefused(io.joryio.sdk.SdkAuthErrorReason.SUB_MISMATCH)
            } else {
                BatchSendResult.Accepted
            }
        }
        q.currentUserId = { "B" }
        q.enqueue(eventOf("a1", "A"))
        q.enqueue(eventOf("b1", "B"))
        q.flush(force = true)
        assertTrue(queued().isEmpty())
        assertEquals(listOf(listOf("a1"), listOf("b1")), sent)
    }

    @Test
    fun `sub_mismatch for the CURRENT user keeps the events for a token refresh`() = runBlocking {
        val q = queue(batchSize = 100) { BatchSendResult.AuthRefused(io.joryio.sdk.SdkAuthErrorReason.SUB_MISMATCH) }
        q.currentUserId = { "A" }
        q.enqueue(eventOf("a1", "A"))
        q.flush(force = true)
        assertEquals(listOf("a1"), queued())
    }

    @Test
    fun `flushInterval has a floor`() {
        // Scan 3 finding 12 / contract item 35. Fails before: 0 made the
        // auto-flush loop spin without waiting.
        val q = QueueManager(eventDao = dao, logger = Logger(false, LogLevel.ERROR), flushInterval = 0)
        q.stopAutoFlush()
        queue = q
        assertEquals(QueueManager.MIN_FLUSH_INTERVAL_MS, q.effectiveFlushIntervalMs)
    }
}
