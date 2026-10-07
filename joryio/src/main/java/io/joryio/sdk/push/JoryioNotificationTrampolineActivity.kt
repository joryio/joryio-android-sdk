package io.joryio.sdk.push

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.joryio.sdk.Joryio

/**
 * Invisible trampoline that receives a push-notification TAP, records the click,
 * then forwards to the host app's launcher (carrying the deep link / custom data).
 *
 * Why an Activity and not a BroadcastReceiver/Service:
 *  - Push click must be attributed on TAP. Tracking at notification-build time
 *    inflated CTR to ~100%.
 *  - Android 12+ (targetSdk 31+) forbids starting an Activity from a notification
 *    trampoline that is a service or broadcast receiver. Activities are allowed.
 *
 * Declared `exported="false"` with a translucent, no-history theme so it never
 * shows UI and never appears in recents.
 */
class JoryioNotificationTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent

        // 1. Track the click ON TAP. Buffered until the SDK is ready: a tap on
        //    a notification can be what STARTS the process, and with
        //    initializeAsync the SDK is still initializing here. This used to
        //    call getInstance(), catch the throw and drop the click - losing
        //    exactly the taps that reopened a closed app.
        val trackingId = incoming?.getStringExtra(PushPayloadKeys.TRACKING_ID)
        if (!trackingId.isNullOrBlank()) {
            try {
                Joryio.whenReady { it.trackPushClick(trackingId) }
            } catch (e: Exception) {
                // Telemetry must never block opening the app.
            }
        }

        // 2. Forward to the host launcher, carrying every data key under its
        //    own name (`jry_deep_link`, `trackingId`, custom data) so the host
        //    can honor the deep link - the same extras a system-drawn
        //    notification delivers. Marked as tracked so PushOpenTracker does
        //    not count this click a second time when the launcher starts.
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                incoming?.extras?.let { launchIntent.putExtras(it) }
                launchIntent.putExtra(PushPayloadKeys.CLICK_TRACKED_EXTRA, true)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            // If we can't resolve/launch the host, just finish silently.
        }

        finish()
    }
}
