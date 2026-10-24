package io.joryio.sdk.queue

import androidx.room.*

/**
 * Data Access Object for events.
 *
 * There is deliberately no "delete rows retried too often" query any more: the
 * queue used to drop an event after three failed sends, which is every event
 * tracked during a short stretch offline. See QueueManager.
 */
@Dao
internal interface EventDao {

    /** Insert a new event. */
    @Insert
    suspend fun insert(event: EventEntity): Long

    /** Insert multiple events. */
    @Insert
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    /** Get events for batching (oldest first; id breaks createdAt ties). */
    @Query("SELECT * FROM events ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getEvents(limit: Int): List<EventEntity>

    /** Get all events. */
    @Query("SELECT * FROM events ORDER BY createdAt ASC, id ASC")
    suspend fun getAllEvents(): List<EventEntity>

    /** Delete events by IDs. */
    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Delete all events. */
    @Query("DELETE FROM events")
    suspend fun deleteAll()

    /** The users with events still queued (retired SDK-auth tokens are kept for them). */
    @Query("SELECT DISTINCT userId FROM events WHERE userId IS NOT NULL")
    suspend fun queuedUserIds(): List<String>

    /** Get queue size. */
    @Query("SELECT COUNT(*) FROM events")
    suspend fun getCount(): Int

    /** Delete the oldest events to maintain the size limit. */
    @Query("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY createdAt ASC, id ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}
