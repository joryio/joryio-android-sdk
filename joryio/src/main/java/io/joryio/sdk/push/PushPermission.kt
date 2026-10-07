package io.joryio.sdk.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Where the user stands on receiving notifications.
 *
 * [NOT_DETERMINED] is the one that matters commercially: it is the ONLY state in
 * which a prompt can still appear. Push permission is one-shot on Android 13+ -
 * a denial cannot be re-requested, only fixed in system Settings - so a primer
 * shown to a user who already denied spends their patience on a prompt that can
 * never be displayed. Check this before showing one.
 */
enum class PushPermissionStatus {
    /** Notifications will be delivered. */
    GRANTED,

    /** Refused, or switched off in Settings. A prompt will NOT appear again. */
    DENIED,

    /** Never asked. A prompt can still be shown - this is the moment to use it. */
    NOT_DETERMINED,

    /** No notification support on this device/OS. */
    UNAVAILABLE;

    /**
     * The cross-platform name: `granted` | `denied` | `notDetermined` |
     * `unavailable` - what iOS, web, React Native and Unity return (shared SDK
     * contract item 8). Bridges read this instead of re-spelling the enum.
     * NOT the attribute wire value: the `push_permission` attribute stays
     * `not_determined`.
     */
    val publicName: String
        get() = when (this) {
            GRANTED -> "granted"
            DENIED -> "denied"
            NOT_DETERMINED -> "notDetermined"
            UNAVAILABLE -> "unavailable"
        }
}

/**
 * Runtime notification permission, for Android 13+ (`POST_NOTIFICATIONS`).
 *
 * The SDK deliberately never requests this on its own. The prompt can only be
 * shown once, and neither an SDK's `initialize()` nor a dashboard toggle can know
 * when a given app has earned the right to ask. The host app - or an in-app
 * primer campaign - decides the moment; this object only makes the ask possible.
 */
internal object PushPermission {

    private const val PREFS = "joryio_push_permission"
    private const val KEY_REQUESTED = "requested_at_least_once"

    /**
     * Below API 33 there is no runtime permission, but a user can still switch
     * notifications off in Settings - so this reports the REAL delivery state
     * rather than assuming "granted" because the OS is old. That distinction is
     * the difference between a channel that looks healthy and one that works.
     */
    fun status(context: Context): PushPermissionStatus {
        val enabled = try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (t: Throwable) {
            return PushPermissionStatus.UNAVAILABLE
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) PushPermissionStatus.GRANTED else PushPermissionStatus.DENIED
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        // Granted but notifications off = switched off in Settings afterwards.
        // That is DENIED for every practical purpose: nothing will be delivered.
        if (granted) {
            return if (enabled) PushPermissionStatus.GRANTED else PushPermissionStatus.DENIED
        }

        // Not granted. Android gives no first-class "never asked" signal, so we
        // record our own: if we have never launched the prompt, it can still be
        // shown. (shouldShowRequestPermissionRationale alone cannot distinguish
        // "never asked" from "denied twice" - it is false in BOTH cases.)
        return if (hasRequested(context)) {
            PushPermissionStatus.DENIED
        } else {
            PushPermissionStatus.NOT_DETERMINED
        }
    }

    /**
     * Show the system prompt, reporting the outcome to [callback] on the main thread.
     *
     * Returns immediately when there is nothing to ask for (already granted, or
     * pre-API-33) so a caller can treat this as "make it so, then tell me".
     */
    fun request(activity: Activity, callback: (PushPermissionStatus) -> Unit) {
        // MUST be on the main thread. ActivityResultRegistry.register/launch and
        // ActivityCompat.requestPermissions all drive Activity state, and calling
        // them from another thread wedges the UI - the React Native bridge invokes
        // this from the native-modules thread, which ANR'd the app on the first
        // real test. status() is safe anywhere, but the ask is not.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { request(activity, callback) }
            return
        }

        val current = status(activity)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            current == PushPermissionStatus.GRANTED ||
            current == PushPermissionStatus.UNAVAILABLE
        ) {
            callback(current)
            return
        }

        markRequested(activity)

        // ActivityResultRegistry.register(key, contract, callback) - the overload
        // WITHOUT a LifecycleOwner - may be called at any point in the lifecycle,
        // which is what lets a library request a permission it does not own the
        // Activity for. The LifecycleOwner overload would have to run before the
        // Activity is STARTED and is therefore unusable from here.
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            // A plain android.app.Activity - Unity's UnityPlayerActivity is
            // one. It has no ActivityResultRegistry, and its
            // onRequestPermissionsResult belongs to the host, so the SDK asks
            // from its OWN transparent ComponentActivity and reports the
            // answer when the dialog closes.
            //
            // This used to call ActivityCompat.requestPermissions and invoke
            // the callback IMMEDIATELY, while the dialog was still on screen:
            // the status then read DENIED (asked, not yet granted), so every
            // Unity game was told "denied" for a user who tapped Allow.
            JoryioPermissionActivity.launch(activity) { granted ->
                callback(
                    if (granted) PushPermissionStatus.GRANTED else status(activity.applicationContext),
                )
            }
            return
        }

        val key = "joryio_push_permission_${System.identityHashCode(callback)}"
        var launcher: ActivityResultLauncher<String>? = null
        launcher = componentActivity.activityResultRegistry.register(
            key,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            // Unregister so a repeated ask does not accumulate launchers.
            launcher?.unregister()
            callback(
                if (granted) PushPermissionStatus.GRANTED else PushPermissionStatus.DENIED,
            )
        }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private const val REQUEST_CODE = 8613

    /**
     * Plain SharedPreferences, deliberately - NOT the encrypted store.
     *
     * This is a boolean "have we ever shown the prompt", which is neither
     * personal nor secret. Putting it in the keystore-backed store would add a
     * full EncryptedSharedPreferences setup (~160ms measured for a first access)
     * to a status check that now runs on the MAIN thread. Encryption you do not
     * need is not free.
     */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hasRequested(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_REQUESTED, false)
    } catch (t: Throwable) {
        false
    }

    private fun markRequested(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
        } catch (t: Throwable) {
            // A missing flag only costs us the NOT_DETERMINED/DENIED distinction;
            // it must never stop the prompt from being shown.
        }
    }
}
