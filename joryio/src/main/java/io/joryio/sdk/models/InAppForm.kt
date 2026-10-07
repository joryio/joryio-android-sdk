package io.joryio.sdk.models

import com.google.gson.annotations.SerializedName

// ── Forms inside messages (landing pages phase C, 2026-09-26) ──────────────

/**
 * What the SDK posts when a form inside a message is submitted: the values
 * as the message serialised them (strings, or lists for checkbox groups),
 * with THIS SDK's identity so the backend writes them to the right person.
 */
internal data class InAppFormSubmitRequest(
    @SerializedName("campaignId")
    val campaignId: String,

    @SerializedName("userId")
    val userId: String?,

    @SerializedName("anonymousId")
    val anonymousId: String,

    @SerializedName("values")
    val values: Map<String, Any?>,
)

/** The server's verdict, handed back to the message runtime as it is. */
internal data class InAppFormSubmitResponse(
    @SerializedName("ok")
    val ok: Boolean,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("redirect")
    val redirect: String? = null,

    @SerializedName("errors")
    val errors: List<String>? = null,
)
