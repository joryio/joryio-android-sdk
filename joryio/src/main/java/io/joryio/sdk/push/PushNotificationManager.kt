package io.joryio.sdk.push

import android.content.Context
import io.joryio.sdk.core.DeviceInfo
import com.google.firebase.messaging.FirebaseMessaging
import io.joryio.sdk.core.IdentityManager
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.SecurePrefs
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.network.PushTokenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists an FCM token that arrived via [JoryioFirebaseMessagingService.onNewToken]
 * BEFORE the SDK was initialized. Uses a fixed, sdk-key-independent SharedPreferences
 * file because the messaging service has no access to the configured SDK key.
 */
internal object PendingTokenStore {
    private const val PREFS = "joryio_pending"
    private const val KEY = "pending_push_token"

    // The FCM token is sensitive; store it encrypted at rest (EncryptedSharedPreferences).
    // SecurePrefs migrates any pre-existing plaintext "joryio_pending" file on first access
    // and falls back to plaintext only on API < 23 / keystore failure.
    private fun prefs(context: Context) = SecurePrefs.get(context, PREFS)

    /**
     * Start building this store on a background thread.
     *
     * Called at the top of SDK init so its keystore setup overlaps with the main
     * store's instead of running after it. [bootstrapToken] reads this file on
     * the caller's thread, so without the overlap the app pays two serial
     * keystore initialisations during initialize().
     */
    fun prewarm(context: Context) = SecurePrefs.prewarm(context, PREFS)

    fun save(context: Context, token: String) {
        prefs(context).edit().putString(KEY, token).apply()
    }

    /** wipeData(): a token captured before init is device data too. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /** Returns the pending token (if any) and clears it. */
    fun take(context: Context): String? {
        val prefs = prefs(context)
        val token = prefs.getString(KEY, null)
        if (token != null) {
            prefs.edit().remove(KEY).apply()
        }
        return token
    }
}

/**
 * Manages push notifications with Firebase Cloud Messaging
 */
/**
 * The outcome of a push-token registration.
 *
 * registerPushToken used to be pure fire-and-forget: its only reaction to a
 * rejection was logger.error, which is gated behind enableDebug. A store build
 * with debug off could therefore fail to register on every single launch with
 * nothing, anywhere, saying so - the device was simply never reachable.
 *
 * An integrator worked around it by polling getDiagnostics() a few seconds
 * later and comparing timestamps against a global lastError. That is a lot of
 * machinery to learn something this function already knew and threw away.
 */
data class PushRegistrationResult(
    val success: Boolean,
    /** Provider or transport message on failure; null on success. */
    val message: String? = null,
    /** A bad or missing SDK key - retrying will not fix it. */
    val isUnauthorized: Boolean = false,
    /** Transient (network, 5xx): the SDK will try again on next launch. */
    val isRetryable: Boolean = false,
)

internal class PushNotificationManager(
    context: Context,
    private val networkClient: NetworkClient,
    private val identityManager: IdentityManager,
    private val storage: StorageManager,
    private val logger: Logger
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Register and unregister run one at a time, in the order they were asked for. */
    private val opMutex = Mutex()
    private val gson = io.joryio.sdk.network.WireJson.gson

    /**
     * Whether a registration may be sent: false while consent is pending or
     * the user opted out (shared SDK contract items 4 and 5). A token that
     * arrives then is stored, and registered when sending is allowed again.
     */
    var canSend: () -> Boolean = { true }

    /**
     * The user a push UNREGISTER is sent for - one an identify() held while
     * tracking was refused (D-b), else the identified user (contract v6 63).
     * Wired by the facade.
     */
    var withdrawalUserId: () -> String? = { identityManager.getUserId() }

    init {
        logger.debug("PushNotificationManager initialized")
    }

    /**
     * Called once at SDK init. Recovers a token captured before init (cheap safety
     * net) AND proactively fetches the current FCM token (robust path), so a token
     * delivered while the SDK was uninitialized is never permanently lost.
     */
    fun bootstrapToken() {
        // (a) Cheap safety net: a token onNewToken persisted before init.
        try {
            PendingTokenStore.take(appContext)?.let { pending ->
                logger.debug("Registering push token captured before init")
                registerToken(pending)
            }
        } catch (e: Exception) {
            logger.warn("Failed to read pending push token: ${e.message}")
        }

        // (b) Robust path: fetch the current token straight from FCM.
        //
        // MUST catch Throwable, not Exception. firebase-messaging is `compileOnly`
        // (see build.gradle.kts) — deliberately optional, supplied by the host app
        // only if it wants push. When it is absent, touching FirebaseMessaging
        // throws NoClassDefFoundError, which is an ERROR, not an Exception: the
        // `catch (e: Exception)` this replaces never fired, so a missing optional
        // dependency propagated out of bootstrapToken and aborted the WHOLE of
        // Joryio.initialize() — killing analytics and in-app messaging in any app
        // that had not set up push.
        if (!isFcmOnClasspath()) {
            logger.debug("firebase-messaging not on the classpath; push disabled (analytics unaffected)")
            return
        }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    task.result?.let { token -> registerToken(token) }
                } else {
                    logger.warn("Failed to fetch FCM token: ${task.exception?.message}")
                }
            }
        } catch (t: Throwable) {
            // Reached when firebase-messaging IS on the classpath but unusable —
            // typically no google-services.json, so FirebaseApp was never initialized.
            logger.warn("FirebaseMessaging unavailable, cannot auto-fetch token: ${t.message}")
        }
    }

    /**
     * Whether the optional `firebase-messaging` artifact is actually present.
     *
     * Asking the classloader turns a linkage failure into a boolean, so the
     * "is push configured?" decision is made explicitly rather than by catching an
     * Error after the fact.
     */
    private fun isFcmOnClasspath(): Boolean = try {
        Class.forName("com.google.firebase.messaging.FirebaseMessaging", false, javaClass.classLoader)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Re-send the stored token so it is associated with the CURRENT identity.
     *
     * Called from reset(): the device row on the backend is keyed by userId, so
     * after a logout it still points at the person who just logged out. Without
     * this, the next user of that phone inherits the previous user's push
     * targeting - a privacy problem, not merely a delivery one.
     *
     * iOS has done this since the identity/logout hardening; Android did not,
     * which is the kind of one-sided fix the parity rule exists to catch.
     */
    fun reregisterToken() {
        if (!canSend()) return
        val token = storage.getDeviceToken()
        if (token == null) {
            logger.debug("No stored device token to re-register")
            return
        }
        registerToken(token, force = true)
    }

    /**
     * Retry a registration the backend never confirmed.
     *
     * Called on foreground. Transient failures are already retried inside the
     * request, and a sustained outage is retried at the next init - but an app
     * that STAYS OPEN after one would otherwise remain unreachable by push until
     * it was relaunched, which for a long-lived session can be days. This turns
     * that window into "until the user next brings the app forward".
     *
     * Cheap by construction: with a confirmed registration the guard inside
     * registerToken returns immediately without touching the network.
     */
    fun retryRegistrationIfUnconfirmed() {
        val token = storage.getDeviceToken() ?: return
        registerToken(token)
    }

    /**
     * Register a device token with the backend under the current identity.
     *
     * @param explicit the HOST asked for this (registerPushToken, a permission
     *   grant). Only an explicit registration lifts an earlier unregisterPush()
     *   - the SDK's own automatic paths (the token fetched at init, onNewToken,
     *   the foreground retry, reset()) leave an unregistered device alone, or
     *   unregisterPush() lasted until the next launch (shared SDK contract
     *   item 22).
     */
    @JvmOverloads
    fun registerToken(
        token: String,
        force: Boolean = false,
        explicit: Boolean = false,
        onResult: ((PushRegistrationResult) -> Unit)? = null,
    ) {
        scope.launch { opMutex.withLock {
            try {
                if (explicit) {
                    // Turning push back on. An unregister of THIS token still
                    // waiting to be sent is cancelled rather than sent after
                    // the registration, which would undo it.
                    storage.setPushUnregistered(false)
                    pendingUnregister()?.takeIf { it.token == token }?.let { storage.setPendingUnregister(null) }
                } else if (storage.isPushUnregistered()) {
                    logger.debug("Push was unregistered by the app; not re-registering automatically")
                    onResult?.invoke(PushRegistrationResult(success = false, message = "Push was unregistered; call registerPushToken() to turn it back on"))
                    return@withLock
                }
                // An unregister the server has not confirmed goes first: sent
                // after this registration it would switch the device off again.
                if (!sendPendingUnregister()) {
                    onResult?.invoke(PushRegistrationResult(success = false, message = "An earlier unregister is still pending", isRetryable = true))
                    return@withLock
                }
                // Skip a registration the backend already has. bootstrapToken()
                // asks FCM for the current token on EVERY init, so without this
                // every app launch spent a request restating an unchanged token.
                //
                // Keyed by token+identity: the same token under a new user is a
                // different registration, which is what makes reset() work.
                val key = "$token|${identityManager.getUserId() ?: identityManager.getAnonymousId()}"
                // An EXPLICIT registration always reaches the server: the mark
                // can be stale (see below), and the host asked for it.
                if (!force && !explicit && storage.getRegisteredPushKey() == key) {
                    logger.debug("Push token already registered for this identity; skipping")
                    // Already registered IS success. A caller that heard
                    // nothing here would read a no-op as a silent failure -
                    // the very ambiguity this callback removes.
                    onResult?.invoke(PushRegistrationResult(success = true))
                    return@withLock
                }

                // Store token locally - even when sending is not allowed, so
                // the token is registered the moment it is.
                storage.setDeviceToken(token)

                if (!canSend()) {
                    logger.debug("Push token stored, not registered: consent pending or opted out")
                    onResult?.invoke(
                        PushRegistrationResult(success = false, message = "Tracking consent not granted or user opted out"),
                    )
                    return@withLock
                }

                logger.debug("Registering push token: ${token.take(20)}...")

                // Send to backend
                val request = PushTokenRequest(
                    userId = identityManager.getUserId(),
                    anonymousId = identityManager.getAnonymousId(),
                    token = token,
                    platform = "android",
                    // Same identity the track path sends, so the server can
                    // join this token to the existing device row.
                    deviceId = storage.getDeviceId(),
                    // Send what the device IS, not just its token. Without this
                    // an Android device row carried no model, os_version or
                    // app_version, so segment filters over those fields matched
                    // zero Android users - silently, because a missing version
                    // loses a comparison rather than raising one. iOS has always
                    // sent this dict; the key names here match it deliberately.
                    deviceInfo = runCatching { DeviceInfo.getDeviceInfo(appContext) }.getOrNull()
                )

                when (val result = networkClient.registerPushToken(request)) {
                    // A 200 is not a confirmation: the endpoint answers
                    // `{ success: false, message }` for a registration it did
                    // not store, and that used to be remembered as done - the
                    // device then never retried and stayed unreachable.
                    is NetworkResult.Success -> if (result.data.success) {
                        // Mark AFTER the backend confirms. Marking before would
                        // remember a FAILED registration as done and leave the
                        // device permanently unreachable by push.
                        // Not when unregisterPush() ran while this request was in
                        // flight: the unregister is sent after it (opMutex) and
                        // wins on the server, so a mark written now would make
                        // the next registerPushToken() with this token look
                        // "already registered" and skip it - the device would
                        // stay off with the app believing it is on.
                        if (!storage.isPushUnregistered()) storage.setRegisteredPushKey(key)
                        logger.info("Push token registered successfully")
                        onResult?.invoke(PushRegistrationResult(success = true))
                    } else {
                        val message = result.data.message ?: "Registration not accepted"
                        logger.error("Push token not registered: $message")
                        onResult?.invoke(
                            PushRegistrationResult(success = false, message = message, isRetryable = true),
                        )
                    }
                    is NetworkResult.Error -> {
                        logger.error("Failed to register push token: ${result.message}")
                        onResult?.invoke(
                            PushRegistrationResult(
                                success = false,
                                message = result.message,
                                isRetryable = result.isRetryable,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to register push token: ${e.message}", e)
                onResult?.invoke(
                    PushRegistrationResult(success = false, message = e.message)
                )
            }
        } }
    }

    /**
     * Turn push off for this device, DURABLY (shared SDK contract item 22).
     *
     * The intent is recorded before anything is sent: the token is forgotten
     * locally, the automatic registration paths stand down, and the request
     * to the server is kept until it is confirmed. It used to be one attempt
     * that kept the token on failure - and the next launch fetched the token
     * from FCM and registered it again, so "unregister" lasted until the app
     * restarted.
     */
    fun unregisterToken() {
        storage.setPushUnregistered(true)
        val token = storage.getDeviceToken()
        if (token == null) {
            logger.debug("No push token to unregister")
            return
        }
        queueUnregister(
            PushTokenRequest(
                userId = withdrawalUserId(),
                anonymousId = identityManager.getAnonymousId(),
                token = token,
                platform = "android",
            ),
        )
        storage.clearDeviceToken()
        // Forget the confirmation too, so a later registerPushToken() with the
        // same token is sent rather than matched as "already registered".
        storage.clearRegisteredPushKey()
        retryPendingUnregister()
    }

    /**
     * For wipeData(): the request that unregisters the token under the
     * identity being erased, built BEFORE the erase. Re-queued with
     * [queueUnregister] after the store is cleared, so the server still hears
     * about it.
     */
    fun unregisterRequestForWipe(): PushTokenRequest? {
        val token = storage.getDeviceToken() ?: pendingUnregister()?.token ?: return null
        return PushTokenRequest(
            userId = withdrawalUserId(),
            anonymousId = identityManager.getAnonymousId(),
            token = token,
            platform = "android",
        )
    }

    fun queueUnregister(request: PushTokenRequest) {
        storage.setPendingUnregister(gson.toJson(request))
    }

    /** Send an unconfirmed unregister, if any. Called at init and on foreground. */
    fun retryPendingUnregister() {
        if (storage.getPendingUnregister() == null) return
        scope.launch { opMutex.withLock { sendPendingUnregister() } }
    }

    private fun pendingUnregister(): PushTokenRequest? = try {
        storage.getPendingUnregister()?.let { gson.fromJson(it, PushTokenRequest::class.java) }
    } catch (e: Exception) {
        storage.setPendingUnregister(null)
        null
    }

    /**
     * True when nothing is pending any more. Not gated on consent: switching
     * a device OFF is never tracking, and an opted-out user is exactly the
     * one who must stop receiving pushes.
     */
    private suspend fun sendPendingUnregister(): Boolean {
        val request = pendingUnregister() ?: return true
        return when (val result = networkClient.unregisterPushToken(request)) {
            is NetworkResult.Success -> if (result.data.success) {
                storage.setPendingUnregister(null)
                logger.info("Push token unregistered successfully")
                true
            } else {
                logger.warn("Push unregister not confirmed (${result.data.message}); will retry")
                false
            }
            is NetworkResult.Error -> {
                val code = result.code
                if (code != null && code in 400..499 && code !in RETRYABLE_4XX) {
                    // Refused outright (an unknown token): nothing to undo.
                    storage.setPendingUnregister(null)
                    logger.warn("Push unregister refused (${result.message}); dropped")
                    true
                } else {
                    logger.warn("Failed to unregister push token, will retry: ${result.message}")
                    false
                }
            }
        }
    }

    /**
     * Get current device token
     */
    fun getToken(): String? {
        return storage.getDeviceToken()
    }

    /**
     * Check if push is enabled
     */
    fun isPushEnabled(): Boolean {
        return storage.getDeviceToken() != null
    }

    private companion object {
        /**
         * 4xx answers that are about the moment, not the request. 403 is NOT
         * one: the identity policy refused this unregister and will refuse it
         * every time, and a pending unregister holds every registration
         * behind it (contract item 28).
         */
        val RETRYABLE_4XX = setOf(401, 402, 408, 429)
    }
}
