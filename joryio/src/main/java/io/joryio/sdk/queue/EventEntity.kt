package io.joryio.sdk.queue

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.reflect.TypeToken
import io.joryio.sdk.models.Event
import io.joryio.sdk.network.WireJson
import java.util.Date

/**
 * Room entity for persisting events.
 */
@Entity(tableName = "events")
@TypeConverters(EventConverters::class)
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val type: String,
    val properties: String, // JSON string
    val timestamp: Date,
    val userId: String?,
    val anonymousId: String,
    val sessionId: String,
    // Client-generated idempotency id, persisted so retries reuse the same id.
    val eventId: String,
    val createdAt: Date = Date(),
    // Kept in the schema for migration compatibility. Nothing deletes on it any
    // more - see QueueManager.
    val retryCount: Int = 0
) {
    /**
     * Convert to Event model.
     *
     * Through [WireJson], not a bare Gson(): Gson's default reads EVERY JSON
     * number in a map back as a Double, so an integer property queued as 2
     * reached the server as 2.0 - the queue changed the property's type.
     */
    fun toEvent(): Event {
        val propertiesMap: Map<String, Any?> = WireJson.gson.fromJson(
            properties,
            PROPERTIES_TYPE
        ) ?: emptyMap()

        return Event(
            type = type,
            properties = propertiesMap,
            timestamp = timestamp,
            userId = userId,
            anonymousId = anonymousId,
            sessionId = sessionId,
            eventId = eventId
        )
    }

    companion object {
        private val PROPERTIES_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        /**
         * Create from Event model.
         */
        fun fromEvent(event: Event): EventEntity {
            return EventEntity(
                type = event.type,
                properties = WireJson.gson.toJson(event.properties),
                timestamp = event.timestamp,
                userId = event.userId,
                anonymousId = event.anonymousId,
                sessionId = event.sessionId,
                eventId = event.eventId
            )
        }
    }
}

/**
 * Type converters for Room
 */
internal class EventConverters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}
