package io.joryio.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Session information. Persisted as JSON, so the keys are pinned: a minified
 * app renames fields between its own releases, and an unpinned key would read
 * back as missing after every app update.
 */
data class SessionInfo(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("sessionStart") val sessionStart: Date,
    @SerializedName("lastActivity") var lastActivity: Date
)
