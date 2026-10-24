package io.joryio.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

/**
 * Event data model
 */
internal data class Event(
    @SerializedName("type")
    val type: String,

    @SerializedName("properties")
    val properties: Map<String, Any?> = emptyMap(),

    @SerializedName("timestamp")
    val timestamp: Date = Date(),

    @SerializedName("userId")
    val userId: String? = null,

    @SerializedName("anonymousId")
    val anonymousId: String,

    @SerializedName("sessionId")
    val sessionId: String,

    /**
     * Client-generated idempotency id. Persisted with the event so that a
     * retried batch carries the SAME id, letting the backend dedupe replays.
     * Mirrors the web SDK's `eventId` and the iOS SDK's event id.
     */
    @SerializedName("eventId")
    val eventId: String = UUID.randomUUID().toString()
)

/**
 * Event batch for sending to backend
 */
internal data class EventBatch(
    @SerializedName("events")
    val events: List<Event>,

    @SerializedName("sentAt")
    val sentAt: Date = Date()
)

/**
 * User identification request
 */
internal data class IdentifyRequest(
    @SerializedName("userId")
    val userId: String,

    @SerializedName("attributes")
    val attributes: Map<String, Any?> = emptyMap()
)

/**
 * Type aliases for clarity
 */
typealias EventProperties = Map<String, Any?>
typealias UserAttributes = Map<String, Any?>
