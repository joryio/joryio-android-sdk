package io.joryio.sdk.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.joryio.sdk.Joryio

/**
 * Firebase Cloud Messaging service for receiving push notifications
 *
 * To use this service, add the following to your AndroidManifest.xml:
 *
 * <service
 *     android:name="io.joryio.sdk.push.JoryioFirebaseMessagingService"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *     </intent-filter>
 * </service>
 */
class JoryioFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "joryio_push"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Called when a new FCM token is generated
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Register token with Joryio. isInitialized(), not a caught
        // getInstance(): getInstance() throws before init, and this service
        // can run in a process where init has not finished (initializeAsync).
        try {
            if (Joryio.isInitialized()) {
                Joryio.getInstance().pushManager?.registerToken(token)
            } else {
                // Persist the token so initialize() can recover and register it
                // (see PushNotificationManager.bootstrapToken).
                PendingTokenStore.save(applicationContext, token)
            }
        } catch (t: Throwable) {
            // Best effort; a token is never worth crashing the host app.
        }
    }

    /**
     * Called when a message is received
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Only Joryio pushes. The backend stamps the marker on EVERY push it
        // sends (campaign, journey, test, sync nudge) - it used to stamp only
        // the nudge, so this check dropped every campaign push that arrived
        // while the app was in the foreground.
        if (!PushPayloadKeys.isJoryio(remoteMessage.data)) {
            return
        }

        // A silent SYNC nudge, not a message to show. The backend sends one after
        // queueing an in-app test so the SDK pulls immediately instead of
        // waiting for the next app open. iOS had this branch (logging, never
        // syncing); Android had none at all, so a nudge arrived and was
        // rendered as an empty notification with no title or body.
        val syncType = remoteMessage.data[PushPayloadKeys.SYNC]
        if (!syncType.isNullOrEmpty()) {
            when (syncType) {
                "campaigns", "in_app" -> {
                    // whenReady, never getInstance(): a nudge that wakes a dead
                    // app arrives while initializeAsync is still running, and
                    // getInstance() THREW here - a crash in the host app,
                    // triggered remotely by our own push.
                    Joryio.whenReady { it.syncInAppCampaigns() }
                }
                else -> {
                    // Named rather than silently ignored: an unknown type means
                    // the backend and the SDK disagree, which is worth seeing.
                    android.util.Log.d("JoryioFCM", "Unknown jry_sync type: $syncType")
                }
            }
            return
        }

        // Extract notification data
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: ""
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        // BOTH sources, deliberately. The backend sends the image as FCM's
        // `notification.imageUrl` (see fcm-provider.ts), which is what Firebase
        // itself renders for a background notification, and as the contract's
        // `jry_image_url` data key, which is the fallback when OUR service does
        // the rendering of a data-only send.
        val imageUrl = remoteMessage.notification?.imageUrl?.toString()
            ?: remoteMessage.data[PushPayloadKeys.IMAGE_URL]
        // Contract names (push-payload.contract.ts in the backend). The deep
        // link is not read here: it rides to the host as the
        // PushPayloadKeys.DEEP_LINK extra (see createNotificationIntent). This
        // used to read `deep_link` while the backend sent `deepLink`, so no
        // tap target ever reached an app.
        val trackingId = remoteMessage.data[PushPayloadKeys.TRACKING_ID]

        // Report that it ARRIVED, before drawing anything.
        //
        // This is the only positive delivery signal push can produce: FCM
        // answers a send with "accepted" and never says what reached the
        // handset. The backend used to emit a `delivered` event next to `sent`
        // regardless, which made every campaign read 100% delivered - including
        // sends to devices that had uninstalled.
        //
        // Before display on purpose. Delivery is "the device received it", not
        // "the user saw it" - a notification the OS suppresses, or that fails
        // to render, still arrived. Reporting after would quietly redefine the
        // metric as something narrower.
        reportDelivered(trackingId)

        // Display notification
        displayNotification(
            title = title,
            body = body,
            imageUrl = imageUrl,
            trackingId = trackingId,
            payload = remoteMessage.data
        )
    }

    /**
     * Fire-and-forget delivery receipt.
     *
     * Never blocks or fails the notification: this method's job is to show a
     * message, and telemetry must not be able to stop it. An exception here
     * would surface as a crash in the HOST app, triggered remotely by us
     * sending a push - the same reasoning as the NotificationManager guard
     * below.
     *
     * The backend deduplicates by tracking id, so a redelivery from FCM or an
     * OS replay after a restart collapses to one event rather than pushing
     * delivery past 100% of sends.
     */
    private fun reportDelivered(trackingId: String?) {
        if (trackingId.isNullOrBlank()) return
        try {
            // Buffered until the SDK is ready rather than skipped: a push that
            // starts the process arrives before initializeAsync completes, and
            // returning early here lost exactly those receipts - the pushes
            // that woke a closed app, i.e. most of them.
            Joryio.whenReady { it.reportPushDelivered(trackingId) }
        } catch (e: Exception) {
            android.util.Log.w("Joryio", "Delivery receipt not sent: ${e.message}")
        }
    }

    /**
     * Display notification
     */
    private fun displayNotification(
        title: String,
        body: String,
        imageUrl: String?,
        trackingId: String?,
        payload: Map<String, String>
    ) {
        // `as?`: getSystemService can return null, and the unchecked cast threw
        // a NullPointerException out of onMessageReceived — i.e. a crash in the
        // HOST APP triggered remotely, by us sending a push. Failing to show one
        // notification is the correct cost.
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager == null) {
            android.util.Log.w("Joryio", "NotificationManager unavailable; cannot show notification")
            return
        }

        // One id per message. A single fixed id made every push REPLACE the
        // previous one, and request code 0 with FLAG_UPDATE_CURRENT made every
        // notification share one PendingIntent - so tapping an older one
        // opened, and tracked a click for, the newest.
        val notificationId = PushPayloadKeys.notificationId(trackingId)

        // Create intent
        val intent = createNotificationIntent(payload)
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getNotificationIcon())
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        // Rich image, when the campaign carries one. onMessageReceived already
        // runs on a FirebaseMessagingService worker thread, so the bounded fetch
        // below is safe here - it must never be moved onto the main thread.
        //
        // A failed or oversized download degrades to the plain notification
        // rather than dropping the message: no picture is a far better outcome
        // than no notification.
        if (!imageUrl.isNullOrBlank()) {
            val bitmap = NotificationImageLoader.fetch(imageUrl)
            if (bitmap != null) {
                notificationBuilder
                    .setLargeIcon(bitmap)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            // Collapsed view shows the small icon, not a duplicate
                            // of the big picture.
                            .bigLargeIcon(null as android.graphics.Bitmap?),
                    )
            }
        }

        // Show notification
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Build the intent fired when the notification is TAPPED.
     *
     * The tap is routed through [JoryioNotificationTrampolineActivity] rather than
     * straight to the host launcher. The click must be tracked ON TAP, not when the
     * notification is built (that inflated CTR to ~100%). A trampoline Activity — not
     * a BroadcastReceiver/Service — is required because Android 12+ bans indirect
     * notification activity starts ("notification trampolines") from receivers/services.
     *
     * Every data key travels under its OWN name - `jry_deep_link`, `trackingId`,
     * custom keys - which is exactly how Android hands them to the launcher when
     * the SYSTEM draws a backgrounded notification. So the host reads one name
     * whichever process drew the notification. (They used to be renamed to
     * `joryio_<key>` here, which only matched the foreground case.)
     */
    private fun createNotificationIntent(payload: Map<String, String>): Intent {
        return Intent(this, JoryioNotificationTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            // `payload`, not `data`: inside apply{} that name is Intent.data.
            payload.forEach { (key, value) -> putExtra(key, value) }
        }
    }

    /**
     * Get notification icon from app resources
     */
    private fun getNotificationIcon(): Int {
        // Resource reflection, deliberately. It is how a host app supplies its own
        // notification icon by simply adding a drawable named `ic_notification`,
        // with nothing to register and no API to call - the convention every push
        // SDK uses. Lint discourages it because R8 resource shrinking can remove a
        // drawable that nothing references by name, and that is exactly why the
        // zero-check below exists: a stripped icon degrades to the app icon rather
        // than failing to post the notification.
        @Suppress("DiscouragedApi")
        val resourceId = resources.getIdentifier(
            "ic_notification",
            "drawable",
            packageName
        )

        // Fallback to app icon
        return if (resourceId != 0) {
            resourceId
        } else {
            applicationInfo.icon
        }
    }

    /** Notification channels exist from API 26; minSdk is 23, so this stays guarded. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // The name the USER sees in system Settings -> Notifications. It was a
        // hard-coded "Joryio Notifications": our name, in English, on every
        // customer's app. Now JoryioConfig.notificationChannelName (persisted at
        // init, so a push that starts a cold process still has it), default
        // "Notifications". Re-creating a channel with a new name renames it.
        val channel = NotificationChannel(
            CHANNEL_ID,
            PushSettings.channelName(this),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
    }
}
