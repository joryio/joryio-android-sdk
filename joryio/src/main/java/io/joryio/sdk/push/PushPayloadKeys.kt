package io.joryio.sdk.push

/**
 * The push data contract: the keys a Joryio push carries, under the names the
 * backend sends them (packages/backend/src/common/push/push-payload.contract.ts).
 *
 * The same names on every platform - iOS reads them from `userInfo`, the web
 * service worker from the push's `data` - and the backend's
 * sdk-push-payload-parity.spec greps this file for each one. Renaming a key
 * here without the backend (or the other way round) fails that test rather
 * than silently dropping a campaign's deep link, which is what happened when
 * this SDK read `deep_link` and the backend sent `deepLink`.
 */
object PushPayloadKeys {
    /** `"true"` on every Joryio push. Messages without it are not ours. */
    const val MARKER = "joryio"
    const val MARKER_VALUE = "true"

    /** Signed id for the delivery receipt and the click. */
    const val TRACKING_ID = "trackingId"

    /** Campaign id, or journey id for a journey send. Informational, for the host. */
    const val CAMPAIGN_ID = "jry_campaign_id"

    /** What a tap opens. Handed to the host app as an intent extra of this name. */
    const val DEEP_LINK = "jry_deep_link"

    /** Rich-notification image (fallback when FCM's notification image is absent). */
    const val IMAGE_URL = "jry_image_url"

    /** Silent sync nudge type: `in_app` (or the older `campaigns`). */
    const val SYNC = "jry_sync"

    /**
     * Not a payload key: set on the launcher intent once the SDK has recorded
     * the click, so the same tap is never reported twice.
     */
    const val CLICK_TRACKED_EXTRA = "jry_click_tracked"

    fun isJoryio(data: Map<String, String>): Boolean = data[MARKER] == MARKER_VALUE

    /**
     * A notification id per message. Derived from the tracking id when there
     * is one, so a redelivered push replaces itself instead of stacking.
     */
    fun notificationId(trackingId: String?): Int =
        if (!trackingId.isNullOrBlank()) trackingId.hashCode() and 0x7fffffff
        else (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

    /**
     * The tracking id to record a click for, when an Activity was opened by
     * tapping a Joryio notification - or null.
     *
     * Pure, over lookups, so it is unit-testable without an Android runtime.
     * See [PushOpenTracker] for why it exists.
     */
    fun clickToTrack(
        getString: (String) -> String?,
        alreadyTracked: Boolean,
        launchedFromHistory: Boolean,
    ): String? {
        // Reopening the app from Recents replays the ORIGINAL launch intent,
        // extras and all. That is not a tap.
        if (launchedFromHistory || alreadyTracked) return null
        if (getString(MARKER) != MARKER_VALUE) return null
        return getString(TRACKING_ID)?.takeIf { it.isNotBlank() }
    }
}
