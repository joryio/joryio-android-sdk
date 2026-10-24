package io.joryio.sdk.network

import com.google.gson.annotations.SerializedName
import io.joryio.sdk.models.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Joryio API endpoints.
 *
 * Retrofit endpoint paths are RELATIVE - no leading "/".
 *
 * A leading slash makes Retrofit treat the path as absolute from the host root
 * and DISCARD the base URL's path. With an apiHost of "https://host/api" every
 * call went to https://host/v1/... and returned 404 "Cannot POST
 * /v1/in-app/sync", so the Android SDK could never reach the API even once it
 * initialized. Keep these relative.
 *
 * The one exception is the push receipt, which IS served at the host root and
 * therefore takes an absolute URL - see NetworkClient.rootUrlOf.
 */
internal interface JoryioApi {

    /** Track a batch of events. */
    @POST("v1/track/batch")
    suspend fun trackBatch(
        @Body batch: EventBatch
    ): Response<BatchResponse>

    /** Track a single event. */
    @POST("v1/track")
    suspend fun trackEvent(
        @Body event: Event
    ): Response<EventResponse>

    /** Identify a user. */
    @POST("v1/identify")
    suspend fun identify(
        @Body request: IdentifyRequest
    ): Response<IdentifyResponse>

    /**
     * Attach a labelled identifier (a loyalty number, a CRM id) to this person.
     *
     * Works for anonymous visitors too (shared SDK contract item 2): the
     * request carries the anonymous id always and the user id when there is
     * one. The unlabelled `alias(userId)` is gone from every SDK (item 1) -
     * it did nothing `identify` does not.
     */
    @POST("v1/alias/add")
    suspend fun addAlias(
        @Body request: AddAliasRequest
    ): Response<AliasResponse>

    /** Set user attributes (uses /v1/attributes endpoint to avoid $identify events). */
    @POST("v1/attributes")
    suspend fun setAttributes(
        @Body request: SetAttributesRequest
    ): Response<SetAttributesResponse>

    /**
     * Marketing subscription: set a channel's status.
     *
     * NOT tracking consent (optIn/optOut). This is whether the person may be
     * MESSAGED on a channel, and it is what an in-app preference centre writes.
     */
    @POST("v1/subscriptions/channel")
    suspend fun updateChannelSubscription(
        @Body request: ChannelSubscriptionRequest
    ): Response<SubscriptionResponse>

    /** Marketing subscription: join or leave a subscription group (list). */
    @POST("v1/subscriptions/group")
    suspend fun updateSubscriptionGroup(
        @Body request: SubscriptionGroupRequest
    ): Response<SubscriptionResponse>

    /** Sync in-app messages for current session. */
    @POST("v1/in-app/sync")
    suspend fun syncInAppMessages(
        @Body request: SessionSyncRequest
    ): Response<SessionSyncResponse>

    /** Display-time re-check for a `reevaluateBeforeDisplay` campaign. */
    @POST("v1/in-app/resolve")
    suspend fun resolveInAppCampaign(
        @Body request: ResolveRequest
    ): Response<ResolveResponse>

    /** Track in-app message impression. */
    @POST("v1/in-app/track")
    suspend fun trackImpression(
        @Body request: TrackImpressionRequest
    ): Response<ImpressionResponse>

    /** A form inside an in-app message; the same route the web SDK posts to. */
    @POST("v1/in-app/form-submit")
    suspend fun submitInAppForm(
        @Body request: io.joryio.sdk.models.InAppFormSubmitRequest
    ): Response<io.joryio.sdk.models.InAppFormSubmitResponse>

    /** Register push notification device token. */
    @POST("v1/push/register")
    suspend fun registerPushToken(
        @Body request: PushTokenRequest
    ): Response<PushTokenResponse>

    /** Unregister push notification device token. */
    @POST("v1/push/unregister")
    suspend fun unregisterPushToken(
        @Body request: PushTokenRequest
    ): Response<PushTokenResponse>

    /**
     * Report that a push actually ARRIVED on this device.
     *
     * The only positive delivery signal push can produce. FCM answers a send
     * with "accepted" and never reports what reached the handset.
     *
     * `@Url`, absolute: the route is mounted at the HOST ROOT
     * (`/track/push/delivered/:id`), shared with the web service worker, which
     * has no SDK key. It used to be a relative path under the `/api/` base URL
     * and 404ed on production for every Android push.
     */
    /** A body serialised earlier, replayed verbatim (the write outbox). */
    @POST
    suspend fun postJson(
        @Url path: String,
        @Body body: okhttp3.RequestBody,
    ): Response<GenericResponse>

    @POST
    suspend fun reportPushDelivered(
        @Url url: String
    ): Response<PushTokenResponse>
}

// API Response Models.
//
// Every field carries @SerializedName: Gson maps JSON keys to FIELD NAMES, and
// R8 renames fields in any app that minifies. Without the annotation a release
// build read every response as empty and wrote every request with keys like `a`.

/**
 * The common answer shape: `success` absent means the 2xx itself is the
 * answer; `success: false` is a failure (shared SDK contract item 15).
 */
internal data class GenericResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("message") val message: String? = null,
)

internal data class BatchResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("processed") val processed: Int = 0,
    @SerializedName("message") val message: String? = null,
    /**
     * On a partial failure (`success: false`), the positions in the batch that
     * were NOT stored. Those events are kept for retry and the rest deleted;
     * absent means "treat the whole batch as failed".
     */
    @SerializedName("failedIndices") val failedIndices: List<Int>? = null,
    /**
     * Positions the server refused FOR GOOD (property limits, an invalid
     * event) - dropped, never retried (contract item 29). `failedIndices`
     * stays "keep and retry". Absent on a backend that predates it.
     */
    @SerializedName("rejectedIndices") val rejectedIndices: List<Int>? = null,
    @SerializedName("error") val error: String? = null,
)

internal data class EventResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

internal data class IdentifyResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

internal data class AliasResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

internal data class SetAttributesResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

internal data class ImpressionResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

internal data class PushTokenResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String? = null
)

// API Request Models
internal data class IdentifyRequest(
    @SerializedName("userId") val userId: String,
    @SerializedName("anonymousId") val anonymousId: String,
    @SerializedName("attributes") val attributes: Map<String, Any?>? = null,
    @SerializedName("timestamp") val timestamp: java.util.Date = java.util.Date()
)

/**
 * Body of POST /v1/alias/add. `userId` only when the person is identified;
 * the anonymous id always, so an alias set before login lands on the
 * anonymous profile and follows it through the merge.
 */
internal data class AddAliasRequest(
    @SerializedName("anonymousId") val anonymousId: String,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("aliasLabel") val aliasLabel: String,
    @SerializedName("aliasName") val aliasName: String,
)

/**
 * Attribute-operations request for POST /v1/attributes.
 *
 * Each section maps to an atomic backend operation; Gson omits null fields, so
 * only the operation in play is sent. An increment carries a DELTA (not a
 * locally-computed absolute), so multi-device writes don't clobber:
 *   - attributes: absolute `$set`
 *   - increment:  `$inc`   (delta)
 *   - append:     `$addToSet`
 *   - remove:     `$pull`
 *   - unset:      `$unset`
 */
internal data class SetAttributesRequest(
    @SerializedName("userId") val userId: String?,
    @SerializedName("anonymousId") val anonymousId: String,
    @SerializedName("attributes") val attributes: Map<String, Any?>? = null,
    @SerializedName("increment") val increment: Map<String, Number>? = null,
    @SerializedName("append") val append: Map<String, Any?>? = null,
    @SerializedName("remove") val remove: Map<String, Any?>? = null,
    @SerializedName("unset") val unset: List<String>? = null
)

/*
 * Wire values are byte-identical to the web SDK and the backend DTO: the status
 * is camelCase `optedIn` (a NormalizeEnumCasing migration renamed the stored
 * value and the API 400s on `opted_in`), and the action is `subscribe` /
 * `unsubscribe`. One state must not become two segment rules.
 */
internal data class ChannelSubscriptionRequest(
    @SerializedName("channel") val channel: String,
    @SerializedName("status") val status: String,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("anonymousId") val anonymousId: String? = null
)

internal data class SubscriptionGroupRequest(
    @SerializedName("groupId") val groupId: String,
    @SerializedName("channel") val channel: String,
    @SerializedName("action") val action: String,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("anonymousId") val anonymousId: String? = null
)

internal data class SubscriptionResponse(
    @SerializedName("success") val success: Boolean = false
)

internal data class PushTokenRequest(
    @SerializedName("userId") val userId: String?,
    @SerializedName("anonymousId") val anonymousId: String,
    @SerializedName("token") val token: String,
    @SerializedName("platform") val platform: String = "android",

    /**
     * The stable device id, so the server attaches this token to the device row
     * that already exists rather than inserting a second one keyed only by the
     * token.
     *
     * Without it, first launch produced TWO rows seconds apart - one carrying
     * the identity with no token, one carrying the token with no identity, and
     * nothing joining them. Reported from a production integration, 2026-08-23.
     */
    @SerializedName("deviceId") val deviceId: String? = null,
    // What the device IS - model, os_version, app_version. Absent until now,
    // which left Android device rows with nothing to filter or target on.
    @SerializedName("deviceInfo") val deviceInfo: Map<String, Any>? = null,
    @SerializedName("timestamp") val timestamp: java.util.Date = java.util.Date()
)

/**
 * One write held by the outbox: where it goes and the exact JSON it sends.
 * Lives in this package so the consumer R8 rules keep its fields - it is
 * written to and read back from disk.
 */
internal data class OutboxEntry(
    @SerializedName("id") val id: String,
    /** identify, alias, attributes, subscription - for logs and reset(). */
    @SerializedName("kind") val kind: String,
    /** Relative to the API base URL, e.g. `v1/identify`. */
    @SerializedName("path") val path: String,
    @SerializedName("body") val body: String,
    /**
     * Answered refusals so far (401, 404, `success: false`). Capped per entry
     * so one write the server keeps refusing cannot block the ordered outbox
     * forever (contract item 28). Offline, 429 and 5xx do not count. Persisted
     * with the entry; absent in an outbox written by an older build = 0.
     */
    @SerializedName("attempts") val attempts: Int = 0,
    /**
     * What this write is ABOUT, for writes a newer one replaces: a
     * subscription channel or group of one person (contract item 37). A newer
     * write with the same key removes a queued older one, so the older can
     * never land after it. Null for writes that never supersede (identify,
     * alias, attribute ops, the opt-out marker) and in older outboxes.
     */
    @SerializedName("key") val key: String? = null,
)
