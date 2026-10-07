package io.joryio.sdk.push

import android.content.Context

/**
 * Push settings the messaging service needs BEFORE the SDK is initialized.
 *
 * FirebaseMessagingService can start in a fresh process (a push arriving while
 * the app is dead), where onCreate creates the notification channel before the
 * host app's Application.onCreate has necessarily initialized the SDK - or with
 * initializeAsync, before it has finished. So the configured channel name is
 * persisted at init and read here.
 *
 * Plain SharedPreferences: a channel label is neither personal nor secret, and
 * the keystore-backed store would add its setup cost to every push.
 */
internal object PushSettings {
    private const val PREFS = "joryio_push_settings"
    private const val KEY_CHANNEL_NAME = "channel_name"

    /** The English default; see JoryioConfig.notificationChannelName. */
    const val DEFAULT_CHANNEL_NAME = "Notifications"

    fun saveChannelName(context: Context, name: String) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CHANNEL_NAME, name).apply()
        } catch (t: Throwable) {
            // The default name still works; nothing worth failing init over.
        }
    }

    fun channelName(context: Context): String = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CHANNEL_NAME
    } catch (t: Throwable) {
        DEFAULT_CHANNEL_NAME
    }
}
