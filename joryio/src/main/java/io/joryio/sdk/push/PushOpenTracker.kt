package io.joryio.sdk.push

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle

/**
 * Records the click when the app is opened by tapping a notification the
 * SYSTEM drew.
 *
 * A campaign push carries an FCM `notification` block. While the app is in the
 * background Android draws it itself, never calls
 * [JoryioFirebaseMessagingService.onMessageReceived], and on tap starts the
 * launcher Activity with the data keys as extras - bypassing
 * [JoryioNotificationTrampolineActivity], the only place a click was recorded.
 * So the SDK only ever counted taps on notifications that arrived while the app
 * was open, which is the minority of them.
 *
 * Watching Activity creation catches the launcher start. The trampoline marks
 * its own launches with [PushPayloadKeys.CLICK_TRACKED_EXTRA], and this marks
 * every intent it handles, so a tap is reported once. The backend also
 * collapses repeats per tracking id, because a React Native or Unity app whose
 * own messaging library handles the tap reports it too.
 *
 * Limitation, stated: a `singleTop`/`singleTask` launcher that is already
 * running receives the tap in `onNewIntent`. Activity callbacks never see that
 * intent unless the app calls `setIntent(intent)` there (the usual practice) -
 * then it is picked up when the Activity resumes.
 */
internal class PushOpenTracker(
    /**
     * Records the click; false when the SDK cannot yet (still initializing).
     * The intent is then left UNMARKED, so the check the constructor makes
     * once it is ready ([handleAlreadyResumed]) still counts the tap. It used
     * to be marked first, and a resume during initialization lost it.
     */
    private val trackClick: (String) -> Boolean,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A restore after process death replays the old intent. Not a tap.
        if (savedInstanceState == null) handle(activity)
    }

    override fun onActivityResumed(activity: Activity) = handle(activity)

    /**
     * Check an Activity that was already on screen when the SDK finished
     * initializing - the launcher opened by a notification tap, when
     * initialization ran after it (initializeAsync, a React Native or Unity
     * bridge). Its onCreate/onResume happened before this tracker existed.
     */
    fun handleAlreadyResumed(activity: Activity) = handle(activity)

    private fun handle(activity: Activity) {
        // The SDK's own Activities are not the app being opened. The
        // trampoline records the tap itself; counting its intent here as well
        // reported every tap on a notification the SDK drew TWICE (the
        // trampoline's onCreate fires this callback before it marks anything).
        if (isSdkActivity(activity)) return
        val intent: Intent = activity.intent ?: return
        val extras = try {
            intent.extras
        } catch (e: Exception) {
            // A malformed parcel from another app must not crash the host.
            null
        } ?: return
        val trackingId = PushPayloadKeys.clickToTrack(
            getString = { key -> extras.getString(key) },
            alreadyTracked = extras.getBoolean(PushPayloadKeys.CLICK_TRACKED_EXTRA, false),
            launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        ) ?: return
        val taken = try {
            trackClick(trackingId)
        } catch (e: Exception) {
            // Telemetry must never break the host's launch.
            true
        }
        if (taken) intent.putExtra(PushPayloadKeys.CLICK_TRACKED_EXTRA, true)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}

/**
 * The SDK's own invisible Activities - the notification trampoline and the
 * permission prompt host. Never an app screen: not a push open, and never a
 * place to draw an in-app message (it finishes as soon as the dialog closes,
 * taking the message with it).
 */
internal fun isSdkActivity(activity: Activity): Boolean =
    activity is JoryioNotificationTrampolineActivity || activity is JoryioPermissionActivity
