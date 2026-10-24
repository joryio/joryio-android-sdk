package io.joryio.sdk.queue

import io.joryio.sdk.LogLevel
import io.joryio.sdk.core.Logger
import io.joryio.sdk.models.Event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Scan 9 P2: the construction-time count refresh races requestClear().
 * The refresh reads the row count, the clear deletes the rows and sets the
 * cached count to 0, then the refresh writes its stale count back - so
 * getQueueSize() reports events that no longer exist (DENIED / optOut at init
 * runs requestClear() right after the QueueManager is built). This is the
 * FixRound2Test "DENIED at init" pumpUntil { getQueueSize() == 0 } flake.
 */
class FixRound9QueueTest {
    private class SlowCountDao : EventDao by FakeEventDao() {
        val inner = FakeEventDao()
        val countRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var first = true
        override suspend fun insert(event: EventEntity): Long = inner.insert(event)
        override suspend fun deleteAll() = inner.deleteAll()
        override suspend fun getCount(): Int {
            val n = inner.getCount()
            if (first) {
                first = false
                countRead.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return n
        }
    }

    @Test
    fun `P2 getQueueSize is 0 after a clear requested while the startup count is in flight`() {
        val dao = SlowCountDao()
        runBlocking {
            dao.inner.insert(EventEntity.fromEvent(Event(type = "left-by-last-launch", anonymousId = "a", sessionId = "s")))
            dao.inner.insert(EventEntity.fromEvent(Event(type = "left-by-last-launch-2", anonymousId = "a", sessionId = "s")))
        }
        val q = QueueManager(eventDao = dao, logger = Logger(false, LogLevel.ERROR), flushInterval = 60_000)
        q.stopAutoFlush()
        dao.countRead.await(5, TimeUnit.SECONDS) // the startup refresh has read 2
        q.requestClear() // DENIED / optOut at init
        Thread.sleep(500) // the clear's deleteAll has run
        dao.release.countDown() // the refresh writes its stale 2
        Thread.sleep(500)
        assertEquals("rows on disk", 0, runBlocking { dao.inner.getCount() })
        assertEquals("getQueueSize() after the clear", 0, q.getQueueSize())
        q.shutdown()
    }
}
