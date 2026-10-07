package io.joryio.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The Activity on screen, watched from PROCESS START - before initialize().
 *
 * Installed by [JoryioContextInitializer], which androidx.startup runs before
 * Application.onCreate(). The SDK's own lifecycle callbacks are registered by
 * its constructor, and `initializeAsync()` - the recommended path - builds the
 * SDK on a background thread while the launcher Activity is created and
 * resumed in parallel. When the Activity won that race the SDK never saw it
 * (scan 5, Android S3): no surface for the first session's in-app message
 * until the next resume (in a single-Activity app, the next time the user left
 * and came back), no launch permission prompt, and a cold-start tap on a
 * notification the system drew was never tracked. The constructor now seeds
 * itself from here, as the React Native and Unity bridges do by passing their
 * Activity.
 *
 * Weak references only; nothing else is kept.
 */
internal object ForegroundActivity : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    private var installedOn: WeakReference<Application>? = null

    fun install(application: Application) {
        synchronized(this) {
            if (installedOn?.get() === application) return
            installedOn = WeakReference(application)
        }
        application.registerActivityLifecycleCallbacks(this)
    }

    /** The resumed app Activity, or null (none yet, or it went to the background). */
    fun current(): Activity? = resumed?.get()?.takeUnless { it.isFinishing }

    override fun onActivityResumed(activity: Activity) {
        if (io.joryio.sdk.push.isSdkActivity(activity)) return
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
