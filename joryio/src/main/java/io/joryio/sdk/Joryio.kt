package io.joryio.sdk

import android.app.Application
import io.joryio.sdk.push.PushRegistrationResult
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.joryio.sdk.core.*
import io.joryio.sdk.messaging.InAppMessagingManager
import io.joryio.sdk.models.*
import io.joryio.sdk.network.ChannelSubscriptionRequest
import io.joryio.sdk.network.SubscriptionGroupRequest
import io.joryio.sdk.network.IdentifyRequest
import io.joryio.sdk.network.JoryioTransportError
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.NetworkResult
import io.joryio.sdk.network.SetAttributesRequest
import io.joryio.sdk.push.PendingTokenStore
import io.joryio.sdk.push.PushPermission
import io.joryio.sdk.push.PushPermissionStatus
import io.joryio.sdk.push.PushNotificationManager
import io.joryio.sdk.queue.QueueManager
import io.joryio.sdk.ecommerce.EcommerceConfig
import io.joryio.sdk.ecommerce.EcommerceTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Date

/**
 * Main Joryio SDK class
 */
class Joryio private constructor(
    context: Context,
    internal val sdkKey: String,
    /**
     * The host this SDK is ACTUALLY sending to.
     *
     * A property rather than a bare constructor parameter so the companion can
     * compare it on a repeat initialize(), and so a host app can display the
     * live value instead of the config it believes it set. Those two diverge
     * the moment initialize() runs twice, and a console showing intent rather
     * than reality turns a one-line misconfiguration into a long debugging
     * session.
     */
    val apiHost: String,
    config: JoryioConfig,
    /**
     * The Activity initialize() was called with, if it was called with one
     * (Unity passes its player Activity; a React Native bridge can pass the
     * current Activity). Seeds [currentActivity]: an SDK initialized AFTER the
     * first Activity resumed never saw that resume, so it had no Activity to
     * display an in-app message or the permission prompt on until the user
     * navigated somewhere else. Not stored strongly.
     */
    seedActivity: android.app.Activity? = null,
) {
    private val appContext: Context = context.applicationContext

    /**
     * The Activity currently in the foreground, or null.
     *
     * The SDK cannot display an in-app message without one - a Dialog needs an
     * Activity, not an application Context. The renderer shipped unwired
     * because this was missing, so in-app messages only appeared in apps that
     * implemented their own host via setInAppMessageCallback.
     *
     * Weakly held: a strong reference here would leak every Activity the user
     * ever visited.
     */
    private var currentActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * How messages get drawn. Base names no view class - that is what lets the
     * rendering layer ship as a separate artifact an app can omit.
     *
     * Discovered reflectively from `joryio-android-ui` if it is on the
     * classpath, so adding that dependency is the whole integration step and
     * there is no register() call to forget. Null means no UI artifact and no
     * host callback: messages are then simply not displayed, which is the
     * correct outcome for an analytics-and-push-only integration.
     */
    /**
     * Captured from the constructor's `config`, which is a parameter rather
     * than a property and so is not visible from member functions. The old
     * `by lazy` renderer read it directly because property initialisers DO run
     * in constructor scope.
     */
    private val allowHtmlJsInAppMessages: Boolean = config.allowHtmlJsInAppMessages
    private val debugEnabled: Boolean = config.enableDebug
    /** Captured at init like [debugEnabled]: `config` is a constructor parameter, not a property. */
    private val sendImmediately: Boolean = config.sendImmediately
    private val requestPushPermissionAtLaunch: Boolean = config.requestPushPermissionAtLaunch
    /** One attempt per process; a refused prompt must not be re-fired every foreground. */
    private var launchPromptAttempted = false

    private var inAppPresenter: InAppMessagePresenter? = null

    /**
     * Supply a presenter explicitly.
     *
     * Rarely needed - the UI artifact is found on its own. Useful for tests, or
     * for an app that wants the SDK's display lifecycle but its own drawing.
     */
    fun setInAppPresenter(presenter: InAppMessagePresenter?) {
        inAppPresenter = presenter
    }

    /**
     * Look for the optional UI artifact.
     *
     * Reflection rather than a compile-time reference, because a compile-time
     * reference is exactly the coupling the split removes. Failure is expected
     * and silent at debug level: "no UI artifact" is a valid, deliberate
     * integration, not an error.
     */
    private fun discoverInAppPresenter(): InAppMessagePresenter? = try {
        val cls = Class.forName("io.joryio.sdk.ui.DefaultInAppMessagePresenter")
        cls.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance(allowHtmlJsInAppMessages, debugEnabled) as InAppMessagePresenter
    } catch (e: ClassNotFoundException) {
        logger.debug("joryio-android-ui not on the classpath; in-app messages will not be displayed by the SDK")
        null
    } catch (e: Throwable) {
        logger.warn("Found joryio-android-ui but could not initialise it: ${e.message}")
        null
    }
    private val logger: Logger
    private val storage: StorageManager
    private val identityManager: IdentityManager
    private val sessionManager: SessionManager
    private val networkClient: NetworkClient
    private val queueManager: QueueManager
    private val inAppMessagingManager: InAppMessagingManager
    internal val pushManager: PushNotificationManager?

    /**
     * Every coroutine the SDK launches runs here.
     *
     * The CoroutineExceptionHandler is not optional politeness: without one, an
     * uncaught throwable inside any `scope.launch { }` reaches the thread's default
     * handler and CRASHES THE HOST APP. An analytics SDK must never do that — a
     * failed in-app sync or a malformed response is our problem, not the app's.
     * SupervisorJob alone does not give this: it stops siblings being cancelled, it
     * does not stop the crash.
     */
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                // android.util.Log, not `logger`: this property initializer runs
                // BEFORE logger is assigned (the compiler rejects capturing it), and
                // a last-resort safety net must not depend on SDK state that may not
                // exist yet — the moment it is needed most is a half-built SDK.
                android.util.Log.e("Joryio", "Unhandled error in a Joryio coroutine (contained)", t)
            }
    )
    /** Coalescing window for attribute writes; see scheduleAttributeFlush(). */
    private var attributeFlushJob: kotlinx.coroutines.Job? = null
    /**
     * Held for the whole of every attribute request - the pending-set flush
     * AND an attribute op (unset, increment, sealed sets) sent from the
     * outbox (contract item 46; scan 4, Android finding S2). Without it an
     * unsetAttribute() made while a flush of the same key was on the wire
     * (or being retried inside the client) could reach the server FIRST, and
     * the older set then landed after it and brought the value back.
     */
    private val attributeSendMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Answered refusals of the pending-set flush in a row (401 for the
     * current user, `success: false`) - capped like an outbox write
     * (contract item 40), so values the server keeps refusing are dropped
     * instead of re-sent on every trigger for the life of the install.
     */
    @Volatile
    private var attributeRefusals = 0

    /** Serializes attribute flushes; see flushPendingAttributes(). */
    private val flushLock = Any()
    private var isFlushingAttributes = false
    private var flushAgainRequested = false

    @Volatile
    private var isOptedOut = false
    @Volatile
    private var isInitialized = false

    /**
     * Tracking consent - the app's banner (shared SDK contract items 4 and
     * 24, consent model A). INDEPENDENT of [isOptedOut], the person's own
     * switch: collection needs consent GRANTED and no opt-out. DENIED is
     * persisted; PENDING keeps everything on the device and sends nothing
     * until updateConsent(GRANTED). Resolved in init; see [TrackingConsent].
     */
    @Volatile
    private var consent: TrackingConsent = TrackingConsent.GRANTED

    private val trackSessionStartEnabled: Boolean = config.trackSessionStart

    /**
     * A Session Start that was due while collection was blocked (opted out or
     * consent denied); a new session with its Session Start begins when the
     * block lifts. While consent is merely PENDING the Session Start is
     * queued on the device like any event, so it stays ahead of the events of
     * its session (contract item 32).
     */
    @Volatile
    private var sessionStartDeferred = false
        set(value) {
            field = value
            // Persisted (contract v8 85; scan 8, web S2 - the same gap here):
            // in memory only, a launch blocked by a denial or an opt-out
            // started a session whose Session Start was deferred, and the
            // next launch - allowed now, inside the session timeout - RESUMED
            // that session and announced it with no Session Start at all.
            try {
                storage.setSessionStartDue(value)
            } catch (t: Throwable) {
                // Kept in memory for this process.
            }
        }

    /**
     * Identify, addAlias, attribute increments/unsets and subscription
     * changes, kept until the server acknowledges them - also while consent
     * is PENDING (released on optIn(), dropped on optOut()). Events and
     * set-attributes have their own durable queues; these are the rest. See
     * [WriteOutbox].
     */
    private val outbox: WriteOutbox

    /** The retry a failed outbox send scheduled, if any. */
    private var outboxRetryJob: kotlinx.coroutines.Job? = null

    /**
     * Guards an identity CHANGE (reset, wipeData) against a write that reads
     * the identity and the pending attributes in two steps. Without it an
     * attribute flush could snapshot person A's pending values and then pick
     * up person B's anonymous id - one person's traits on the next person's
     * profile.
     */
    private val identityLock = Any()

    /** Bumped by every identity change; an ack from before it is ignored. */
    @Volatile
    private var identityGeneration = 0

    /** The push-click tracker, kept so a late init can check the current Activity. */
    private val pushOpenTracker = io.joryio.sdk.push.PushOpenTracker { trackingId ->
        // Not ready yet: left for the check the constructor makes when it is.
        if (!isInitialized) {
            false
        } else {
            trackPushClick(trackingId)
            true
        }
    }

    /** An in-app message that was ready when no Activity was; shown on the next resume. */
    @Volatile
    private var pendingCampaign: InAppCampaign? = null

    /** Sending allowed: initialized, not opted out, consent granted. */
    private fun canSend(): Boolean =
        isInitialized && !isOptedOut && consent == TrackingConsent.GRANTED

    /**
     * Collection refused outright: the person opted out, or consent is
     * DENIED. Any "no" wins (contract item 24). PENDING is not blocked - it
     * collects on the device and sends nothing.
     */
    private fun blocked(): Boolean = isOptedOut || consent == TrackingConsent.DENIED

    /**
     * Whether one outbox entry may be sent now. A WITHDRAWAL - an
     * unsubscribe, the opt-out marker - is the person saying stop, not
     * tracking: it is delivered while they are opted out and whatever the
     * consent (contract item 36, owner decision 2026-10-06). The marker used
     * to wait for consent GRANTED, and an unsubscribe was refused outright
     * while opted out or denied - the one message that must always get
     * through.
     */
    private fun canSendEntry(entry: io.joryio.sdk.network.OutboxEntry): Boolean =
        if (isWithdrawal(entry)) isInitialized else canSend()

    /**
     * A withdrawal: an unsubscribe, or the opt-out marker set to TRUE. The
     * marker set back to `false` (optIn()) is not one - it says "track me
     * again", which waits for consent like any write, and the backend refuses
     * it from a suspended account (scan 5, Android NICE 1). It used to go out
     * by kind while consent was still denied or pending.
     */
    private fun isWithdrawal(entry: io.joryio.sdk.network.OutboxEntry): Boolean =
        entry.kind == KIND_UNSUBSCRIBE ||
            (entry.kind == KIND_OPT_OUT_MARKER && entry.body.contains("\"$TRACKING_OPTED_OUT_ATTRIBUTE\":true"))

    private fun isWithdrawalKind(kind: String): Boolean = kind == KIND_OPT_OUT_MARKER || kind == KIND_UNSUBSCRIBE

    /** Push the consent / opt-out state into the subsystems that send on their own. */
    private fun applySendGate() {
        queueManager.paused = isOptedOut || consent != TrackingConsent.GRANTED
    }

    /**
     * Hold a write in the outbox and send it when allowed. The request is
     * serialised NOW, on the caller's thread, so a replay - after consent, a
     * retry, a restart - carries the identity it was made under (a userId
     * that arrived since is added at send time, see [withCurrentUserId]).
     * Opted out or consent denied: nothing (callers check first) - except a
     * withdrawal, which is always kept and delivered (contract item 36).
     *
     * @param key what the write is about, when a newer write for the same
     *   key replaces a queued older one (contract item 37).
     */
    private fun enqueueWrite(
        kind: String,
        path: String,
        request: Any,
        key: String? = null,
        alsoReplaces: String? = null,
    ) {
        if (blocked() && !isWithdrawalKind(kind)) return
        // Per-key call order across set / unset / increment (contract item
        // 31; scan 3, finding 5): attribute SETS wait in the pending set and
        // were sent on their own path, so an unset or increment queued here
        // could land after a LATER set of the same key - or a set could
        // overtake an older unset. Every set made before this op is moved
        // into the outbox ahead of it, and the pending path sends nothing
        // while attribute ops wait here (see flushPendingAttributes).
        if (kind == KIND_ATTRIBUTES) sealPendingAttributes()
        addToOutbox(kind, path, request, key, alsoReplaces)
        if (holdOutboxDrain) return
        // A withdrawal does not wait out the SDK's own backoff (a 402
        // suspension's can reach minutes): the forced drain sends it now,
        // still behind a server Retry-After (scan 6, Android NICE 1).
        if (isWithdrawalKind(kind) && isInitialized) {
            scope.launch { drainOutbox(force = true) }
        } else if (canSend()) {
            scope.launch { drainOutbox() }
        }
    }

    /**
     * True while initialize() records the withdrawals made before a pre-init
     * wipe: they are only queued then (under the identity being erased), and
     * init's own outbox drain sends them once the SDK is fully built.
     */
    private var holdOutboxDrain = false

    // Declared ahead of init {}: initialize() runs the withdrawals made before
    // a pre-init wipe from inside it, and a property declared below init {} is
    // still null there.
    private val SUBSCRIPTION_CHANNELS = listOf("email", "sms", "whatsapp", "push", "viber")
    private val SUBSCRIPTION_STATUSES = listOf("optedIn", "subscribed", "unsubscribed")

    /**
     * Push clicks made while consent was PENDING (this launch only, like the
     * Session Start's hold). Above init {}: init can discard collected data
     * and take a launch tap.
     */
    private val heldPushClicks = ArrayDeque<String>()

    private fun addToOutbox(kind: String, path: String, request: Any, key: String? = null, alsoReplaces: String? = null) {
        val body = try {
            io.joryio.sdk.network.WireJson.gson.toJson(request)
        } catch (t: Throwable) {
            logger.error("Could not encode a $kind request: ${t.message}")
            return
        }
        outbox.add(
            io.joryio.sdk.network.OutboxEntry(java.util.UUID.randomUUID().toString(), kind, path, body, key = key),
            alsoReplaces = alsoReplaces,
        )
    }

    /**
     * Ids resolved at SEND time (contract item 38; scan 4, web finding 3):
     * a write captured with only the anonymous id - a newsletter signup
     * before login - that is sent after identify() carries the userId too,
     * as long as its anonymous id is still the current one. Under SDK
     * Authentication the server refuses an anonymous write to a profile that
     * has since been identified (403 SDK_ANONYMOUS_ID_IDENTIFIED), and that
     * 403 dropped the write. A write of a previous identity (reset() since)
     * keeps its own ids.
     */
    private fun withCurrentUserId(body: String): String {
        val (userId, anonymousId) = synchronized(identityLock) {
            identityManager.getUserId() to identityManager.getAnonymousId()
        }
        if (userId == null) return body
        return try {
            val json = com.google.gson.JsonParser.parseString(body) as? com.google.gson.JsonObject ?: return body
            val written = json.get("userId")
            val hasUser = written != null && written.isJsonPrimitive && written.asString.isNotBlank()
            val anon = json.get("anonymousId")?.takeIf { it.isJsonPrimitive }?.asString
            if (hasUser || anon != anonymousId) return body
            json.addProperty("userId", userId)
            io.joryio.sdk.network.WireJson.gson.toJson(json)
        } catch (e: Exception) {
            body
        }
    }

    /** Move the not-yet-sent attribute sets into the outbox, in front of whatever comes next. */
    private fun sealPendingAttributes() {
        synchronized(identityLock) { sealPendingAttributesLocked() }
    }

    /** [sealPendingAttributes] for a caller already holding [identityLock]. */
    private fun sealPendingAttributesLocked() {
        run {
            val values = identityManager.pending.pending()
            if (values.isEmpty()) return
            addToOutbox(
                KIND_ATTRIBUTES,
                "v1/attributes",
                SetAttributesRequest(
                    userId = identityManager.getUserId(),
                    anonymousId = identityManager.getAnonymousId(),
                    attributes = values,
                ),
            )
            // By value: a write that landed meanwhile stays pending.
            identityManager.pending.acknowledge(values)
        }
    }

    /**
     * Forget the retired SDK-auth tokens whose user has nothing left queued -
     * no outbox write, no event, no push unregister (owner decision D-a: a
     * token lives exactly as long as its user's leftovers).
     */
    private suspend fun pruneRetiredTokens() {
        try {
            if (networkClient.retiredTokenUserIds().isEmpty()) return
            val queued = queueManager.queuedUserIds() ?: return
            val active = HashSet<String>(queued)
            // The signed-in and the held (D-b) user's kept token is never
            // "nothing left": they are the ones who may still need it.
            identityManager.getUserId()?.let { active += it }
            storage.getHeldUserId()?.let { active += it }
            outbox.snapshot().forEach { entry -> userIdOf(entry.body)?.let { active += it } }
            storage.getPendingUnregister()?.let { json ->
                Regex("\"userId\"\\s*:\\s*\"([^\"]+)\"").findAll(json).forEach { active += it.groupValues[1] }
            }
            networkClient.pruneRetiredTokens(active)
        } catch (t: Throwable) {
            logger.debug("Could not prune retired SDK-auth tokens: ${t.message}")
        }
    }

    /**
     * wipeData(): the users whose kept token must survive the erasure - the
     * signed-in and held (D-b) users and anyone with a withdrawal still
     * queued (the outbox holds only withdrawals by then; the push unregister).
     */
    private fun usersWithWithdrawalsKept(): Set<String> {
        val keep = HashSet<String>()
        try {
            identityManager.getUserId()?.let { keep += it }
            storage.getHeldUserId()?.let { keep += it }
            outbox.snapshot().forEach { entry -> userIdOf(entry.body)?.let { keep += it } }
            storage.getPendingUnregister()?.let { json ->
                Regex("\"userId\"\\s*:\\s*\"([^\"]+)\"").findAll(json).forEach { keep += it.groupValues[1] }
            }
        } catch (t: Throwable) {
            logger.debug("Could not list the users with queued withdrawals: ${t.message}")
        }
        return keep
    }

    /** The userId a serialised outbox body carries, or null for an anonymous write. */
    private fun userIdOf(body: String): String? = try {
        (com.google.gson.JsonParser.parseString(body) as? com.google.gson.JsonObject)
            ?.get("userId")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
    } catch (e: Exception) {
        null
    }

    /** Send what the outbox holds, in order; schedule a retry when a send fails. */
    internal suspend fun drainOutbox(force: Boolean = false) {
        val retryIn = try {
            outbox.drain(
                force,
                canSend = { canSendEntry(it) },
                // A subscription change for a profile the server does not
                // have yet: the queued events are what create it.
                onProfileMissing = { queueManager.flush(force = true) },
                onDone = { entry -> onOutboxEntryDone(entry) },
            ) { entry ->
                val body = withCurrentUserId(entry.body)
                val writer = userIdOf(body)
                val result = if (entry.kind == KIND_ATTRIBUTES) {
                    // One attribute request on the wire at a time: see
                    // attributeSendMutex.
                    attributeSendMutex.withLock { networkClient.postJson(entry.path, body) }
                } else {
                    networkClient.postJson(entry.path, body)
                }
                io.joryio.sdk.core.OutboxResult.from(
                    result,
                    forCurrentUser = writer == null || isCurrentUser(writer),
                )
            }
        } catch (e: Exception) {
            logger.error("Write outbox failed: ${e.message}", e)
            null
        }
        pruneRetiredTokens()
        if (retryIn == null) return
        outboxRetryJob?.cancel()
        outboxRetryJob = scope.launch {
            kotlinx.coroutines.delay(retryIn)
            drainOutbox()
        }
    }

    private fun onOutboxEntryDone(entry: io.joryio.sdk.network.OutboxEntry) {
        when (entry.kind) {
            KIND_OPT_OUT_MARKER -> {
                // What the server holds for the person on the device now. A
                // logged-out user's marker reached THEIR profile, which says
                // nothing about this one's (contract v7 74).
                if (isCurrentMarkerSubject(entry.body, currentMarkerSubject())) {
                    storage.setOptOutMarkerAcked(entry.body.contains("\"$TRACKING_OPTED_OUT_ATTRIBUTE\":true"))
                }
                // The switch may have moved while this was on the wire.
                syncOptOutMarker()
            }
            KIND_ATTRIBUTES -> {
                try {
                    val attrs = (com.google.gson.JsonParser.parseString(entry.body) as? com.google.gson.JsonObject)
                        ?.getAsJsonObject("attributes")
                    attrs?.get(PUSH_PERMISSION_ATTRIBUTE)?.takeIf { it.isJsonPrimitive }?.asString?.let {
                        noteAttributesStored(mapOf(PUSH_PERMISSION_ATTRIBUTE to it))
                    }
                } catch (e: Exception) {
                    // Only the push_permission bookkeeping; the write is through.
                }
                // The last attribute op is through: the sets made after it may go.
                if (!outbox.any { it.kind == KIND_ATTRIBUTES } && !identityManager.pending.isEmpty()) {
                    scope.launch { flushPendingAttributes() }
                }
            }
        }
    }

    /**
     * Attributes the server confirmed. `push_permission` is remembered as
     * reported only HERE (scan 4, Android finding M6): it used to be stored
     * when the write was queued, so a write lost before delivery (an opt-out
     * discards pending writes, a 400 drops them) was remembered as sent and
     * the state was never reported again.
     */
    private fun noteAttributesStored(values: Map<String, Any?>) {
        (values[PUSH_PERMISSION_ATTRIBUTE] as? String)?.let { storage.setReportedPushPermission(it) }
    }

    /**
     * Keep the `tracking_opted_out` profile marker in step with the opt-out
     * switch (contract item 33; scan 3, findings 8 and 10): queued when the
     * switch differs from what the server last acknowledged - including a
     * decision made at init or before it - retried through the outbox until
     * acknowledged, and NOT sent when nothing changed. optIn() used to queue
     * a `false` on every call, and an opt-out decided at init was never
     * recorded at all.
     */
    private fun syncOptOutMarker() {
        val wanted = isOptedOut
        // Only the CURRENT person's unsent marker is superseded by the current
        // state (contract v7 74; scan 7, Android S2). A marker queued for
        // someone else - an opt-out made by a user who has logged out since -
        // is theirs and keeps its ids: it used to be removed here and queued
        // again under the CURRENT ids, at every launch, so the profile of the
        // person who opted out never got the record.
        val me = currentMarkerSubject()
        val mine = outbox.snapshot().filter { it.kind == KIND_OPT_OUT_MARKER && isCurrentMarkerSubject(it.body, me) }
        if (mine.lastOrNull()?.body?.contains("\"$TRACKING_OPTED_OUT_ATTRIBUTE\":$wanted") == true) {
            // Already on its way (the same guard as iOS).
            if (isInitialized) scope.launch { drainOutbox(force = wanted) }
            return
        }
        if (mine.isNotEmpty()) outbox.removeIf { it.kind == KIND_OPT_OUT_MARKER && isCurrentMarkerSubject(it.body, me) }
        if (wanted == storage.getOptOutMarkerAcked()) return
        addToOutbox(
            KIND_OPT_OUT_MARKER,
            "v1/attributes",
            SetAttributesRequest(
                // A withdrawal: the user an identify() held while tracking
                // was refused (D-b) - the same rule as an unsubscribe and a
                // push unregister on every SDK (contract v6 63). It used to go
                // to the anonymous profile only.
                userId = withdrawalUserId(),
                anonymousId = identityManager.getAnonymousId(),
                attributes = if (wanted) {
                    mapOf(TRACKING_OPTED_OUT_ATTRIBUTE to true)
                } else {
                    mapOf(TRACKING_OPTED_OUT_ATTRIBUTE to false)
                },
            ),
        )
        // The marker set to true is a withdrawal: it does not wait out the
        // SDK's own backoff (a 402 suspension's reaches minutes), like one
        // queued through enqueueWrite; a server Retry-After still holds
        // (contract v7 74; scan 7, Android NICE 1).
        if (isInitialized) scope.launch { drainOutbox(force = wanted) }
    }

    /**
     * Whether a queued opt-out marker is about the person on the device now:
     * the current anonymous id, and no user id or the user withdrawals go
     * out for (an anonymous marker is sent as the user identified since, see
     * [withCurrentUserId]). After reset() or identify() of another user the
     * old markers are someone else's.
     */
    private fun isCurrentMarkerSubject(body: String, current: MarkerSubject): Boolean = try {
        val json = com.google.gson.JsonParser.parseString(body) as? com.google.gson.JsonObject
        val anon = json?.get("anonymousId")?.takeIf { it.isJsonPrimitive }?.asString
        val user = json?.get("userId")?.takeIf { it.isJsonPrimitive }?.asString
        anon == current.anonymousId && (user.isNullOrBlank() || user in current.userIds)
    } catch (e: Exception) {
        true
    }

    private class MarkerSubject(val anonymousId: String, val userIds: Set<String>)

    /** Read before any outbox lock is taken: the identity lock is never taken inside it. */
    private fun currentMarkerSubject(): MarkerSubject = synchronized(identityLock) {
        MarkerSubject(
            identityManager.getAnonymousId(),
            setOfNotNull(withdrawalUserId(), identityManager.getUserId()),
        )
    }

    /**
     * The user a WITHDRAWAL is sent for (owner decision D-b; contract v6 63):
     * the one an identify() held while tracking was refused, else the
     * identified user. Used by every withdrawal - unsubscribes, group leaves,
     * the opt-out marker, the push unregister - and as the "current" user for
     * SDK-Authentication refusals, so a 401 on the held user's withdrawal asks
     * the app for a token instead of dropping it as a logged-out user's.
     */
    private fun withdrawalUserId(): String? = storage.getHeldUserId() ?: identityManager.getUserId()

    /** Whether [userId] is the person on this device now: identified, or held (D-b). */
    private fun isCurrentUser(userId: String): Boolean =
        userId == identityManager.getUserId() || userId == storage.getHeldUserId()

    init {
        // Initialize logger
        logger = Logger(
            enabled = config.enableDebug,
            // effectiveLogLevel, not logLevel: an unset level must follow
            // enableDebug, or turning debugging on logs nothing. See JoryioConfig.
            logLevel = config.effectiveLogLevel
        )

        logger.info("Initializing Joryio SDK v${Logger.SDK_VERSION}")

        // Kick off the push store's keystore setup NOW so it runs alongside the
        // main store's rather than after it. Android Keystore initialisation is
        // by far the most expensive thing in init (~163ms measured for the first
        // store), and bootstrapToken() reads this second file on the caller's
        // thread later in this same constructor.
        PendingTokenStore.prewarm(appContext)

        // Initialize storage
        storage = StorageManager(
            context = context,
            sdkKey = sdkKey,
            logger = logger
        )

        // Consent, as early as storage allows - before any collection path can
        // run. The PERSISTED flag wins on its own: a user who called optOut()
        // last run must stay opted out even when the host app passes its
        // default config, which is the common case.
        //
        // An optOut() / optIn() / updateConsent() made BEFORE initialize()
        // (through the static methods) is applied HERE, not replayed after
        // init: replayed, it arrived after init had already started an in-app
        // sync and a push registration for a user who had said no.
        val preInit = takePreInitConsent(appContext)

        // The OPT-OUT switch (the person's own). `optOut = true` is PERSISTED
        // like an optOut() call; an optIn() / optOut() made before init wins
        // over the config.
        when {
            preInit.optOut != null -> storage.setOptedOut(preInit.optOut)
            config.optOut -> storage.setOptedOut(true)
        }
        isOptedOut = storage.getOptedOut()
        if (isOptedOut) logger.warn("User has opted out of tracking")

        // The CONSENT switch (the app's banner) - consent model A, contract
        // item 24. Independent of the opt-out: DENIED no longer turns into
        // an opt-out (scan 3, finding 4 - Android alone did that, so the same
        // banner answer meant different things on different platforms).
        consent = resolveConsentAtInit(preInit.consent ?: config.trackingConsent)
        when (consent) {
            TrackingConsent.DENIED -> logger.warn("Tracking consent denied: collecting nothing")
            TrackingConsent.PENDING -> logger.info(
                "Tracking consent pending: collecting on the device, sending nothing until updateConsent(GRANTED)",
            )
            TrackingConsent.GRANTED -> {}
        }

        // The channel name the messaging service shows; persisted because the
        // service can run before initialize() in a cold process.
        io.joryio.sdk.push.PushSettings.saveChannelName(
            appContext,
            config.notificationChannelName?.takeIf { it.isNotBlank() } ?: io.joryio.sdk.push.PushSettings.DEFAULT_CHANNEL_NAME,
        )

        // Initialize identity manager
        identityManager = IdentityManager(
            storage = storage,
            logger = logger,
            initialUserId = config.userId,
            // Was never passed, so a caller-supplied anonymous id did nothing (A17).
            initialAnonymousId = config.anonymousId
        )
        // An explicit init userId wins over an identify() held by an earlier
        // launch (contract v6 63).
        if (!config.userId.isNullOrBlank() && storage.getHeldUserId() != null) storage.setHeldUserId(null)

        // Initialize session manager
        sessionManager = SessionManager(
            storage = storage,
            logger = logger,
            sessionTimeout = config.sessionTimeout
        )

        // Initialize network client
        networkClient = NetworkClient(
            context = context,
            sdkKey = sdkKey,
            apiHost = apiHost,
            logger = logger,
            maxRetries = config.maxRetries,
            retryBackoffMs = config.retryBackoffMs,
            enableDebug = config.enableDebug,
            enableSdkAuthentication = config.enableSdkAuthentication,
            sdkAuthenticationToken = config.sdkAuthenticationToken,
            // Was never passed; OkHttp sat on a hard-coded 30 s (A17).
            requestTimeoutMs = config.requestTimeout
        )

        // Logged-out users' SDK-auth tokens, kept with their leftovers across
        // a restart (owner decision D-a; scan 5, Android S5).
        networkClient.attachRetiredTokenStore(object : io.joryio.sdk.network.SdkAuthenticator.Store {
            override fun load() = storage.getRetiredAuthTokens()
            override fun save(tokens: Map<String, io.joryio.sdk.network.SdkAuthenticator.Retired>) =
                storage.setRetiredAuthTokens(tokens)
        })

        // Initialize queue manager
        queueManager = QueueManager(
            context = context,
            sdkKey = sdkKey,
            logger = logger,
            batchSize = config.batchSize,
            flushInterval = config.flushInterval,
            // Was never passed, so QueueManager's own 10,000 applied against a documented 1,000.
            maxQueueSize = config.maxQueueSize,
        )

        outbox = WriteOutbox(storage = storage, logger = logger, isWithdrawal = { isWithdrawal(it) })

        // What the server said, reduced to what the queue must do about it
        // (shared SDK contract item 3). It used to be Success -> delete, so a
        // 200 `success: false` deleted events the server had NOT stored.
        queueManager.onFlush = { batch ->
            io.joryio.sdk.queue.BatchSendResult.from(networkClient.trackBatch(batch))
        }

        // Initialize in-app messaging
        inAppMessagingManager = InAppMessagingManager(
            networkClient = networkClient,
            identityManager = identityManager,
            sessionManager = sessionManager,
            storage = storage,
            logger = logger,
            allowHtmlJsInAppMessages = allowHtmlJsInAppMessages
        )
        inAppMessagingManager.canSend = { canSend() }

        // In-app display. Registered here so an integrating app gets working
        // in-app messaging without writing its own host - a setInAppMessageCallback
        // later overrides this rather than duplicating it.
        //
        // MUST come after `inAppMessagingManager` is constructed: this assigns
        // its `onMessageReady`. It used to run ~60 lines earlier, before the
        // manager existed, so initialize() died with a NullPointerException on
        // EVERY launch - which the RN bridge turned into a rejected promise and
        // in-app messaging never worked on Android at all.
        setupDefaultInAppDisplay(context)

        // Clicks on notifications the SYSTEM drew (app in the background):
        // they open the launcher directly, never the trampoline. See
        // PushOpenTracker.
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(pushOpenTracker)

        // Initialize push notifications (if FCM is available).
        //
        // Throwable, not Exception: firebase-messaging is compileOnly, so "not
        // available" arrives as NoClassDefFoundError — an Error. See
        // PushNotificationManager.bootstrapToken for the full note.
        pushManager = try {
            PushNotificationManager(
                context = appContext,
                networkClient = networkClient,
                identityManager = identityManager,
                storage = storage,
                logger = logger
            ).also { it.canSend = { canSend() } }
        } catch (t: Throwable) {
            logger.warn("FCM not available, push notifications disabled")
            null
        }

        applySendGate()
        // The held user (D-b) counts as current: their withdrawals carry
        // their token, and a refusal reaches the auth handler (contract v6 63).
        queueManager.currentUserId = { withdrawalUserId() }
        networkClient.currentUserId = { withdrawalUserId() }
        networkClient.isCurrentUser = { isCurrentUser(it) }
        pushManager?.withdrawalUserId = { withdrawalUserId() }

        // An SDK-auth token and handler set before initialize() apply HERE,
        // before the pre-init wipe, the in-app sync and the outbox drain below
        // (RN scan 7, N1; as iOS's pendingAuthToken): replayed from the
        // buffer, they landed after those first requests had gone out with no
        // token. Only a token set before any pre-init identify(), reset() or
        // wipeData() is taken early - one set after them keeps its place in
        // the replay, so a logout replayed later never retires the next
        // user's token. The buffered call still replays (the same token: no
        // change).
        preInit.authHandler?.let { setSdkAuthenticationErrorHandler(it) }
        preInit.authToken?.let { setSdkAuthenticationToken(it) }

        // wipeData() called before initialize(): erased HERE, before the
        // Session Start, the in-app sync and the push registration below
        // could run against the data being erased (contract item 25).
        if (preInit.wipe) {
            // The withdrawals made before that wipe go first, under the
            // identity it erases. The public methods refuse to run before the
            // SDK is marked ready, so it is marked for just these calls (this
            // instance is not published yet: nothing else can see it). With a
            // key for another platform nothing is ever sent, so they are not run.
            if (sdkKey.startsWith(SDK_KEY_PREFIX)) {
                isInitialized = true
                holdOutboxDrain = true
                try {
                    runActionsMadeBeforeTheWipe(this)
                } finally {
                    holdOutboxDrain = false
                    isInitialized = false
                }
            }
            wipeData(keepingToken = networkClient.currentSdkAuthenticationToken())
        }

        // Blocked at launch (opted out, or consent denied): nothing collected
        // before is kept either - the queue, the attribute writes and the
        // outbox a previous launch left behind.
        if (blocked()) discardCollectedData()

        // The opt-out marker follows the switch, init-time decisions included.
        syncOptOutMarker()

        // Validate the SDK-key format. A wrong/legacy key silently 401s every
        // request, so match the severity of the web SDK (which throws) WITHOUT
        // crashing the host app: log a prominent error and stay in a no-send state
        // (isInitialized stays false → every tracking call warn-drops).
        if (!sdkKey.startsWith(SDK_KEY_PREFIX)) {
            android.util.Log.e(
                "Joryio",
                "Invalid SDK key: an Android SDK key starts with '$SDK_KEY_PREFIX'" +
                    (if (sdkKey.startsWith("jry_sdk_")) " - this is a key for another platform (iOS, web or a store)" else "") +
                    ". The SDK will NOT send any data. Copy the Android app's SDK key from Settings -> Apps in the Joryio dashboard."
            )
            logger.error("Invalid SDK key format; SDK running in no-send mode")
        } else {
            // Resolve the session BEFORE the lifecycle observer can fire
            // ON_START: with no stored session, checkAndRenew() there would see
            // none, start one and emit a second Session Start beside the one
            // below.
            //
            // A cold start that RESUMES an unexpired session sends no Session
            // Start (contract item 32): that session already has one. Every
            // launch inside the timeout used to send another.
            val resumedSession = sessionManager.resumedAtLaunch
            // The ONE place init starts a session (reads never do - contract
            // item 54): extends a resumed one, otherwise starts the new one
            // whose Session Start is sent below.
            sessionManager.checkAndRenew()

            // Setup lifecycle observer
            setupLifecycleObserver(context)

            // Mark ready BEFORE emitting the first event so trackSessionStart() is
            // not dropped by the isInitialized guard.
            isInitialized = true

            // An Activity that was already on screen when init ran (a late
            // init, a bridge that initializes from its Activity): no resume
            // callback is coming for it, so take it now.
            // initializeAsync() / a late init: the Activity that resumed while
            // the SDK was being built, seen from process start (scan 5, S3).
            val seed = seedActivity ?: ForegroundActivity.current()
            seed?.let { activity ->
                currentActivity = java.lang.ref.WeakReference(activity)
                try {
                    pushOpenTracker.handleAlreadyResumed(activity)
                } catch (t: Throwable) {
                    logger.debug("Could not check the launch intent: ${t.message}")
                }
            }

            // An identify() held while tracking was refused (D-b) runs now
            // when this launch may track - a config or pre-init grant / optIn
            // lifted the block. Before the Session Start, and before the calls
            // buffered before initialize() (replayed after this constructor),
            // which came later (contract v6 63; scan 6, Android S1). It used
            // to wait for a runtime updateConsent(GRANTED) / optIn() that never
            // came, and the stale held id then took over a later user's
            // withdrawals.
            releaseHeldIdentifyIfAllowed()

            // Track session start if enabled - for a NEW session only, or
            // for a resumed one that an earlier, blocked launch never
            // announced (its Session Start still owed - contract v8 85).
            val sessionStartOwed = storage.isSessionStartDue()
            if (sessionStartOwed) sessionStartDeferred = false
            if (trackSessionStartEnabled && (!resumedSession || sessionStartOwed)) {
                // An owed start opens a NEW session when it can be sent now;
                // while still blocked it stays owed, and the release starts one.
                if (resumedSession && !blocked()) sessionManager.startNewSession()
                trackSessionStartOrDefer()
            }

            // Sync in-app messages (a no-op while consent is pending)
            scope.launch {
                inAppMessagingManager.sync()
            }

            // Recover any FCM token captured before init and proactively register
            // the current token so onNewToken-before-init is never lost.
            //
            // Guarded because push is an OPTIONAL subsystem and this is the last
            // statement of initialize(): anything it throws would abort init after
            // isInitialized was already set, leaving a half-built SDK. This exact
            // call did that (NoClassDefFoundError from the compileOnly Firebase
            // artifact) in every app without push. No optional subsystem gets to
            // take down initialization.
            try {
                // A pre-init unregisterPush() waits in the buffer: push is
                // off from now, before the automatic token fetch below can
                // register (contract v6 69).
                if (hasBufferedUnregisterPush()) storage.setPushUnregistered(true)
                pushManager?.retryPendingUnregister()
                pushManager?.bootstrapToken()
            } catch (t: Throwable) {
                logger.warn("Push bootstrap failed, continuing without push: ${t.message}")
            }

            // Writes a previous launch could not deliver.
            if (outbox.size() > 0) scope.launch { drainOutbox(force = true) }

            // Report the permission state once the SDK is live. Cheap: only
            // sends when it differs from the last reported value.
            reportPushPermissionIfChanged()

            // The seeded Activity can host the opt-in launch prompt now.
            if (seed != null) postToMain { maybeRequestPushPermissionAtLaunch() }

            logger.info("Joryio SDK initialized successfully")
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else Handler(Looper.getMainLooper()).post(action)
    }

    /**
     * Setup app lifecycle observer
     */
    private fun setupLifecycleObserver(context: Context) {
        if (context !is Application) return

        // ProcessLifecycleOwner requires the MAIN thread. initialize() is not
        // guaranteed to run there - the React Native bridge calls it from a
        // background thread - and the resulting IllegalStateException aborted
        // initialize() entirely, so the SDK never started on RN Android.
        // Hop to the main looper rather than demanding callers know this.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { setupLifecycleObserver(context) }
            return
        }

        run {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            logger.debug("App came to foreground")
                            // A new session when the old one timed out while we
                            // were away - with its Session Start, as on iOS.
                            // This was updateActivity(), which refreshed the
                            // EXPIRED session instead: a session never ended
                            // while the process lived, however long the app
                            // sat in the background.
                            if (sessionManager.checkAndRenew() && trackSessionStartEnabled) {
                                trackSessionStartOrDefer()
                            }
                            // Resume periodic flushing (stopped on ON_STOP).
                            queueManager.startAutoFlush()
                            // A user can revoke notifications in system Settings
                            // while we are backgrounded; foreground is the only
                            // moment we can notice.
                            reportPushPermissionIfChanged()
                            maybeRequestPushPermissionAtLaunch()
                            // No-op when the backend already confirmed this
                            // token; retries only a registration that failed.
                            pushManager?.retryPendingUnregister()
                            pushManager?.retryRegistrationIfUnconfirmed()
                            scope.launch {
                                drainOutbox(force = true)
                                // Retry attributes BEFORE syncing: anything set
                                // while offline should be in the profile by the
                                // time targeting is evaluated, otherwise the
                                // sync decides on data we know to be stale and
                                // the message appears one foreground late.
                                flushPendingAttributes()
                                inAppMessagingManager.sync()
                            }
                        }
                        Lifecycle.Event.ON_STOP -> {
                            logger.debug("App went to background")
                            scope.launch {
                                // Send what we have, THEN stop the periodic loop.
                                //
                                // The loop is `while (isActive) { delay(5s); flush() }`
                                // and nothing used to stop it, so a backgrounded app
                                // kept waking every 5 seconds for as long as the
                                // process lived — each tick running a SQLite
                                // getCount() against an empty queue. That is roughly
                                // 17k disk reads a day spent to discover there is
                                // nothing to do, on a device the user is not even
                                // using. Events tracked while backgrounded still
                                // persist to Room and go out on the next foreground
                                // (or immediately, when a batch fills).
                                queueManager.flush(force = true)
                                queueManager.stopAutoFlush()
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    }

    /**
     * Track session start event
     */
    private fun trackSessionStart() {
        val deviceId = storage.getDeviceId()
        val deviceProperties = DeviceInfo.getTrackingProperties(appContext, deviceId)
        val properties = deviceProperties.toMutableMap()
        properties["session_id"] = sessionManager.getSessionId()
        track(SESSION_START_EVENT, properties)
    }

    /**
     * Session Start now - queued on the device while consent is PENDING, so
     * it stays ahead of the events of its session when they are released
     * (contract item 32; it used to be held back and sent AFTER them) - or,
     * while collection is blocked, when the block lifts.
     */
    private fun trackSessionStartOrDefer() {
        if (blocked()) {
            sessionStartDeferred = true
            return
        }
        trackSessionStart()
    }

    /**
     * Consent at launch (contract item 24): an explicit GRANTED lifts a stored
     * denial, an explicit DENIED is stored, PENDING holds this launch only and
     * never overrides a stored denial, and "not set" (null) keeps a stored
     * denial or otherwise behaves as granted.
     */
    private fun resolveConsentAtInit(explicit: TrackingConsent?): TrackingConsent = when (explicit) {
        TrackingConsent.GRANTED -> {
            storage.setConsentDenied(false)
            TrackingConsent.GRANTED
        }
        TrackingConsent.DENIED -> {
            storage.setConsentDenied(true)
            TrackingConsent.DENIED
        }
        TrackingConsent.PENDING ->
            if (storage.getConsentDenied()) TrackingConsent.DENIED else TrackingConsent.PENDING
        null ->
            if (storage.getConsentDenied()) TrackingConsent.DENIED else TrackingConsent.GRANTED
    }

    // MARK: - Event Tracking

    /**
     * Track an event.
     *
     * Parameters are nullable for Java callers (the Unity bridge passes null
     * for "no properties"): a null from Java used to throw a
     * NullPointerException inside Kotlin's parameter check - a crash in the
     * host app from an analytics call.
     */
    @JvmOverloads
    fun track(eventName: String?, properties: Map<String, Any?>? = emptyMap()) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot track event")
            return
        }
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping track")
            return
        }
        // Empty only, not blank: the backend's name rule is 1-255 characters
        // and nothing else (isValidSdkEventName; contract item 41), so a
        // whitespace-only name is a name it stores - the same on every SDK.
        if (eventName.isNullOrEmpty()) {
            logger.warn("track() needs an event name; ignored")
            return
        }
        // NaN / Infinity: that property dropped with a warning, the event
        // still tracked (contract item 49). The whole event used to be
        // dropped as "cannot be serialized", where web sent the rest.
        val props = NonFinite.sanitize(properties ?: emptyMap(), logger, "track('${eventName.take(64)}')")

        // The backend's limits, checked HERE (contract item 29): an event the
        // server refuses for good used to be queued, refused, kept for retry
        // and sent again forever.
        io.joryio.sdk.core.EventLimits.violation(eventName, props)?.let { why ->
            logger.error("Event '${eventName.take(64)}' not tracked: $why")
            return
        }

        // A session that timed out while the app stayed in the FOREGROUND
        // (no ON_START to notice it): start the new one with its Session
        // Start, before this event, as iOS does. getSessionId() below used to
        // roll the session over silently, so a long foreground idle produced a
        // new session id with no Session Start at all.
        if (eventName != SESSION_START_EVENT && sessionManager.checkAndRenew() && trackSessionStartEnabled) {
            trackSessionStartOrDefer()
        }

        // Built NOW, on the caller's thread: the identity an event carries is
        // the one in force when it was tracked. Built inside the coroutine it
        // could pick up the NEXT identity when reset() or identify() landed
        // between the call and the coroutine running.
        val event = Event(
            type = eventName,
            properties = props,
            timestamp = Date(),
            userId = identityManager.getUserId(),
            anonymousId = identityManager.getAnonymousId(),
            sessionId = sessionManager.getSessionId()
        )
        // Captured now for the same reason: an optOut() between this call and
        // the insert must still discard this event.
        val queueGeneration = queueManager.generation

        scope.launch {
            try {
                queueManager.enqueue(event, queueGeneration)
                sessionManager.updateActivity()

                // Send immediately if configured. `sendImmediately` was stored and
                // never read here (audit 2026-09-26, A17); web and iOS flush after
                // each track when it is on, so Android now does the same.
                if (sendImmediately) {
                    queueManager.flush()
                }
            } catch (e: Exception) {
                logger.error("Failed to track event: ${e.message}", e)
            }
        }

        // Show anything waiting on this event - locally, no round trip. AFTER
        // the event is queued: the event is data and must be recorded whether
        // or not it triggers a message.
        try {
            inAppMessagingManager.onEventTracked(eventName, props)
        } catch (e: Exception) {
            logger.error("In-app trigger evaluation failed: ${e.message}", e)
        }
    }

    /**
     * Track screen view
     */
    @JvmOverloads
    fun trackScreen(screenName: String?, properties: Map<String, Any?>? = emptyMap()) {
        if (screenName.isNullOrBlank()) {
            logger.warn("trackScreen() needs a screen name; ignored")
            return
        }
        val enrichedProperties = (properties ?: emptyMap()).toMutableMap()
        enrichedProperties["screen_name"] = screenName

        track("Screen Viewed", enrichedProperties)
    }

    // MARK: - User Identity

    /**
     * Identify a user
     */
    fun identify(userId: String?) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot identify user")
            return
        }
        if (userId.isNullOrBlank()) {
            logger.warn("identify() needs a user id; ignored")
            return
        }
        if (blocked()) {
            // Owner decision D-b: kept on the device only - no request, no
            // profile write, no tracking - so a withdrawal made meanwhile (an
            // unsubscribe) carries the right userId. The real identify runs
            // when tracking is allowed again (releaseHeldWork).
            storage.setHeldUserId(userId)
            // A token kept on standby for them is theirs now (contract v9 89):
            // no longer "the next user's", so no wipe drops it.
            networkClient.unflagStandbySdkAuthenticationToken(userId)
            logger.debug("Opted out or consent denied: identify held on the device for withdrawals only")
            return
        }
        // A real identify replaces any held one (contract v6 63): left in
        // place, a stale held id took over this user's withdrawals.
        if (storage.getHeldUserId() != null) storage.setHeldUserId(null)

        // Under the identity lock: an identify right after reset() must merge
        // with the NEW anonymous id, never the one reset() replaced.
        val (request, changed) = synchronized(identityLock) {
            val before = identityManager.getUserId()
            if (before != null && before != userId) {
                // Another user WITHOUT reset() (contract item 53): treated as
                // reset-then-identify for what belongs to the outgoing user.
                // Their attribute writes not yet sent go out under THEIR ids
                // (sealed into the outbox now, ids captured now), and the
                // session's SDK-auth token goes with their leftovers - unless
                // the app already set the incoming user's. Both used to go out
                // under the new user: A's traits on B's profile, and A's
                // queued writes with B's token, refused and dropped.
                sealPendingAttributesLocked()
                identityGeneration++
                networkClient.retireSdkAuthenticationTokenOnSwitch(before, userId)
            }
            identityManager.identify(userId)
            networkClient.adoptRetiredSdkAuthenticationToken(userId)
            IdentifyRequest(
                userId = userId,
                anonymousId = identityManager.getAnonymousId(),
                attributes = emptyMap()
            ) to (before != userId)
        }
        sessionManager.updateActivity()
        enqueueWrite("identify", "v1/identify", request)
        // A sync in flight was asked for the previous identity: its answer is
        // discarded and a fresh sync follows it (contract item 26).
        if (changed) inAppMessagingManager.invalidate()
    }

    /**
     * Attach a labelled identifier to this person - a loyalty number, a CRM id.
     *
     * Works BEFORE identify() too (shared SDK contract item 2): the alias is
     * sent with the anonymous id (and the user id once there is one), so it
     * lands on the anonymous profile and follows it through the merge at
     * login. It used to refuse with "user is not identified".
     *
     * @param label lowercase letters, digits and underscores, e.g. "loyalty_id"
     * @param name the identifier itself
     */
    fun addAlias(label: String?, name: String?) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot add alias")
            return
        }
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping addAlias")
            return
        }
        if (label.isNullOrBlank() || name.isNullOrBlank()) {
            logger.warn("addAlias() needs a label and a name; ignored")
            return
        }
        val request = io.joryio.sdk.network.AddAliasRequest(
            anonymousId = identityManager.getAnonymousId(),
            userId = identityManager.getUserId(),
            aliasLabel = label,
            aliasName = name,
        )
        enqueueWrite("alias", "v1/alias/add", request)
    }

    /**
     * Logout (shared SDK contract item 6).
     *
     * Starts a NEW anonymous identity and forgets everything held for the
     * outgoing person: user id, unsent attribute writes and their timer,
     * requests waiting on consent, held and delayed in-app messages. Then
     * re-registers the push token under the new identity (unless opted out)
     * and re-syncs in-app messages for it.
     *
     * Synchronous, so a track() on the next line is already recorded under
     * the new identity. It used to run inside a coroutine, and the anonymous
     * id survived it.
     */
    fun reset() {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot reset")
            return
        }

        try {
            // Identity and session FIRST, synchronously (shared SDK contract
            // item 16): an identify() on the next line must use the NEW
            // anonymous id. Anything already queued carries its own ids and is
            // flushed afterwards, never re-labelled.
            attributeFlushJob?.cancel()
            // The SDK-Authentication token is the outgoing user's (contract
            // item 39): kept as the session's, it went out on the next
            // person's requests; cleared outright, the outgoing user's own
            // last events got a 401 and were dropped. It stays bound to them.
            // Never standby when it is the signed-in user's own (contract v9 89).
            networkClient.retireSdkAuthenticationToken(withdrawalUserId(), identityManager.getUserId())
            synchronized(identityLock) {
                identityGeneration++
                identityManager.reset()
            }
            storage.setHeldUserId(null)
            // Attribute ops of the outgoing person are dropped with their
            // pending writes (contract item 6). Identify, aliases and
            // subscription changes stay: they carry the outgoing ids and are
            // still true.
            outbox.removeIf { it.kind == KIND_ATTRIBUTES }
            attributeRefusals = 0
            sessionManager.startNewSession()
            inAppMessagingManager.clear()
            pendingCampaign = null

            // Re-associate the device token with the fresh anonymous
            // identity so a new user does not inherit the previous user's
            // token association. The backend keys devices by userId, so
            // skipping this leaves the row pointing at the person who just
            // logged out - the next user of the phone would receive their
            // notifications. Skipped (inside) while opted out or pending.
            pushManager?.reregisterToken()

            // A new person, a new session - with its Session Start, as on web.
            if (trackSessionStartEnabled) trackSessionStartOrDefer()

            scope.launch {
                drainOutbox(force = true)
                queueManager.flush(force = true)
                pruneRetiredTokens()
                inAppMessagingManager.sync()
            }

            logger.info("User identity reset")
        } catch (e: Exception) {
            logger.error("Failed to reset user: ${e.message}", e)
        }
    }

    // MARK: - User Attributes

    /**
     * Set user attributes
     */
    fun setAttributes(attributes: UserAttributes?) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot set attributes")
            return
        }
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping setAttributes")
            return
        }
        if (attributes.isNullOrEmpty()) return
        // NaN / Infinity: that key dropped with a warning, the rest set
        // (contract item 49; scan 5, B1 - it threw into the host and then
        // poisoned every later attribute flush and in-app sync).
        @Suppress("NAME_SHADOWING")
        val attributes = NonFinite.sanitize(attributes, logger, "setAttributes")

        // A null value means "remove this attribute" (shared SDK contract
        // item 19), the same on every platform. Sent as a null it was dropped
        // by the JSON encoder - the call did nothing at all.
        val nullKeys = attributes.filter { it.value == null }.keys.filter { it.isNotBlank() }
        if (nullKeys.isNotEmpty()) unsetAttributes(nullKeys.toList())
        val toSet = attributes.filter { it.value != null && it.key.isNotBlank() }
        if (toSet.isEmpty()) return
        setAttributesInternal(toSet)
    }

    private fun setAttributesInternal(attributes: Map<String, Any?>) {
        // Save locally
        identityManager.setAttributes(attributes)

        // A differential-sync campaign may be waiting on one of these. Cheap:
        // returns immediately unless a campaign actually watches the key.
        inAppMessagingManager.attributesDidChange(attributes.keys)

        // Not acknowledged until the server says so. Recorded BEFORE the
        // attempt so a failure needs no special handling - the value is already
        // queued, and the flush below either clears it or leaves it for the
        // next trigger. While consent is pending it simply waits here.
        identityManager.pending.mark(attributes)

        scheduleAttributeFlush()
    }

    /**
     * Send everything the server has not acknowledged, and clear what it took.
     *
     * Retried rather than dropped: called after every [setAttributes], on
     * foreground, and before an in-app sync. A failure leaves the values
     * queued, so the next trigger picks them up - which is what makes an
     * attribute set while offline survive.
     *
     * Sends the whole pending set, not just the newest call: if three writes
     * failed while offline, reconnecting must deliver all three.
     */
    /**
     * Coalesce a burst of writes into one request - see the iOS twin.
     *
     * A debounce, not a fixed interval: a single write still goes out promptly
     * and only a burst is held. The foreground and pre-sync paths call
     * [flushPendingAttributes] directly, bypassing this, so nothing set while
     * offline waits on a timer.
     */
    private fun scheduleAttributeFlush() {
        attributeFlushJob?.cancel()
        attributeFlushJob = scope.launch {
            kotlinx.coroutines.delay(ATTRIBUTE_FLUSH_DEBOUNCE_MS)
            // The flush itself is launched OUTSIDE this job, so a newer write
            // cancels only the wait, never a request already on the wire -
            // cancelling one mid-flight lost its acknowledgement and sent the
            // same values again.
            scope.launch { flushPendingAttributes() }
        }
    }

    internal suspend fun flushPendingAttributes() {
        // Nothing leaves the device while consent is pending or opted out; the
        // values wait in the pending set (opt-out clears it).
        if (!canSend()) return

        // Attribute ops (unset / increment, and the sets sealed ahead of them)
        // still waiting in the outbox are OLDER than everything here: they go
        // first, or a set could overtake an unset of the same key (contract
        // item 31). The outbox calls back when its last attribute op is
        // through (onOutboxEntryDone).
        if (outbox.any { it.kind == KIND_ATTRIBUTES }) {
            drainOutbox()
            if (outbox.any { it.kind == KIND_ATTRIBUTES }) return
        }

        // Serialize + coalesce - see the iOS twin. A burst of setAttributes
        // must not become a burst of overlapping requests.
        synchronized(flushLock) {
            if (isFlushingAttributes) {
                flushAgainRequested = true
                return
            }
            isFlushingAttributes = true
        }

        try {
            // Snapshot AND send inside the attribute lock (contract item 46):
            // an attribute op queued in the outbox meanwhile is older than
            // nothing read here, and one queued while this is on the wire
            // waits for it - so the newer op always lands last.
            attributeSendMutex.withLock {
                // Re-checked under the lock: an op that sealed these values
                // into the outbox since the check above goes first.
                if (outbox.any { it.kind == KIND_ATTRIBUTES }) return@withLock
                // Values, ids and generation read together: see identityLock.
                val (toSend, request, generation) = synchronized(identityLock) {
                    val values = identityManager.pending.pending()
                    Triple(
                        values,
                        SetAttributesRequest(
                            userId = identityManager.getUserId(),
                            anonymousId = identityManager.getAnonymousId(),
                            attributes = values
                        ),
                        identityGeneration,
                    )
                }
                if (toSend.isEmpty()) return@withLock
                sendPendingAttributes(toSend, request, generation)
            }
        } finally {
            val again = synchronized(flushLock) {
                isFlushingAttributes = false
                val a = flushAgainRequested
                flushAgainRequested = false
                a
            }
            if (again) flushPendingAttributes()
        }
    }

    private suspend fun sendPendingAttributes(
        toSend: Map<String, Any?>,
        request: SetAttributesRequest,
        generation: Int,
    ) {
        // Dropped after the same number of answered refusals as an outbox
        // write (contract item 40); offline, 429 and 5xx never count.
        fun refused(why: String) {
            if (++attributeRefusals >= io.joryio.sdk.core.WriteOutbox.MAX_ATTEMPTS_PER_ENTRY) {
                attributeRefusals = 0
                if (generation == identityGeneration) identityManager.pending.acknowledge(toSend)
                logger.error("Attributes ${toSend.keys} still refused after repeated attempts ($why); dropped")
            } else {
                logger.warn("Server did not store attributes; will retry: $why")
                scheduleAttributeRetry()
            }
        }
        try {
            // Acknowledged ONLY on a confirmed success. setAttributes()
            // reports failure as a value, not an exception, and the ack used
            // to run regardless - so an attribute set offline was
            // "acknowledged" by the failed request and never sent, the exact
            // loss this pending set exists to prevent.
            when (val result = networkClient.setAttributes(request)) {
                is NetworkResult.Success -> if (result.data.success) {
                    attributeRefusals = 0
                    attributeRetryFailures = 0
                    // Clears only keys whose value is still the one we sent, so a
                    // write that landed mid-flight stays pending, not acked away.
                    // Not at all after a reset: those are the next person's.
                    if (generation == identityGeneration) {
                        identityManager.pending.acknowledge(toSend)
                        noteAttributesStored(toSend)
                    }
                    logger.info("Attributes sent to server (${toSend.size})")
                } else {
                    refused(result.data.message ?: "success: false")
                }
                is NetworkResult.Error -> when (result.code) {
                    // Refused as sent (validation, too large) or by the
                    // identity policy: it will never be accepted. Dropped -
                    // it was retried on every trigger for the life of the
                    // install (scan 3, finding 9; contract item 28).
                    400, 403, 413, 422 -> {
                        if (generation == identityGeneration) identityManager.pending.acknowledge(toSend)
                        logger.error(
                            "Server refused attributes ${toSend.keys} (${result.message}); dropped",
                        )
                    }
                    401, 404 -> refused(result.message)
                    else -> {
                        logger.warn("Failed to send attributes, will retry: ${result.message}")
                        scheduleAttributeRetry(result.retryAfterMs)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to send attributes, will retry: ${e.message}", e)
            scheduleAttributeRetry()
        }
    }

    /** Failed pending-set flushes in a row; the retry backoff grows with it. */
    @Volatile
    private var attributeRetryFailures = 0
    private var attributeRetryJob: kotlinx.coroutines.Job? = null

    /**
     * Retry a failed pending-set flush on a backoff (scan 5, Android NICE 3;
     * contract item 3). It used to wait for the next setAttributes(),
     * foreground or sync - so attributes set while offline could sit unsent
     * for as long as the app stayed open. Offline and 5xx never count toward
     * dropping; this only decides WHEN to try again.
     */
    private fun scheduleAttributeRetry(retryAfterMs: Long? = null) {
        attributeRetryFailures++
        val backoff = (ATTRIBUTE_RETRY_BASE_MS * (1L shl attributeRetryFailures.coerceAtMost(7)))
            .coerceAtMost(io.joryio.sdk.core.WriteOutbox.MAX_BACKOFF_MS)
        val wait = maxOf(backoff, retryAfterMs ?: 0L)
        attributeRetryJob?.cancel()
        attributeRetryJob = scope.launch {
            kotlinx.coroutines.delay(wait)
            flushPendingAttributes()
        }
    }

    // MARK: - Tracking consent and opt-out
    //
    // Consent model A (shared SDK contract item 24), the same on every SDK.
    // Two independent switches:
    //   consent  updateConsent(GRANTED / DENIED / PENDING) - the app's banner
    //   opt-out  optOut() / optIn()                         - the person's own
    // The SDK collects only when consent is GRANTED and the person has not
    // opted out; any "no" wins. A consent value never lifts an opt-out, and
    // the opt-out never changes stored consent. Android used to map
    // updateConsent(DENIED) to optOut() and GRANTED to optIn().

    /**
     * Record the app's tracking-consent decision (its consent banner).
     *
     * - [TrackingConsent.GRANTED]: collect and send - releasing what was held
     *   while consent was pending. Lifts a stored denial. Does NOT lift an
     *   opt-out: only [optIn] does.
     * - [TrackingConsent.DENIED]: collect nothing and discard what is held.
     *   PERSISTED until updateConsent(GRANTED). Does not opt the person out.
     * - [TrackingConsent.PENDING]: keep events and attributes on the device and
     *   send nothing until a decision. This launch only; ignored while consent
     *   is DENIED.
     */
    fun updateConsent(consent: TrackingConsent?) {
        when (consent) {
            TrackingConsent.GRANTED -> {
                val was = this.consent
                storage.setConsentDenied(false)
                this.consent = TrackingConsent.GRANTED
                applySendGate()
                logger.info("Tracking consent granted")
                if (!isInitialized) return
                if (was == TrackingConsent.GRANTED) {
                    // Nothing was held by consent - but an identify held by an
                    // earlier launch's denial still runs (contract v6 63).
                    releaseHeldIdentifyIfAllowed()
                    return
                }
                inAppMessagingManager.invalidate()
                if (isOptedOut) {
                    // Nothing to release; the opt-out marker may go out now.
                    scope.launch { drainOutbox(force = true) }
                } else {
                    scope.launch { releaseHeldWork() }
                }
            }
            TrackingConsent.DENIED -> {
                storage.setConsentDenied(true)
                this.consent = TrackingConsent.DENIED
                applySendGate()
                discardCollectedData()
                inAppMessagingManager.invalidate()
                logger.info("Tracking consent denied: collecting nothing")
            }
            TrackingConsent.PENDING -> {
                if (this.consent == TrackingConsent.DENIED || storage.getConsentDenied()) {
                    // "Undecided" does not reopen a denial (contract item 24).
                    logger.info("updateConsent(PENDING) ignored: consent is denied; updateConsent(GRANTED) lifts it")
                    return
                }
                this.consent = TrackingConsent.PENDING
                applySendGate()
                inAppMessagingManager.invalidate()
                logger.info("Tracking consent pending: holding data on the device")
            }
            null -> logger.warn("updateConsent() needs a value; ignored")
        }
    }

    /** The current tracking consent (the banner switch); see [isUserOptedOut] for the opt-out. */
    fun getTrackingConsent(): TrackingConsent = consent

    /**
     * The person's own opt-out. PERSISTED, so it survives a restart; only
     * [optIn] lifts it - a consent value never does.
     *
     * Everything collected and not yet sent is DROPPED, not delivered later:
     * queued events, pending attribute writes, held requests, a Session Start
     * waiting on consent - except withdrawals (an unsubscribe not yet
     * delivered, a push unregister), which are still sent. Then the opt-out
     * is recorded on the profile as `tracking_opted_out: true` - a consent
     * RECORD, not tracking - sent whatever the
     * consent, and retried until the server has it (contract item 36).
     */
    fun optOut() {
        isOptedOut = true
        storage.setOptedOut(true)
        applySendGate()
        discardCollectedData()
        inAppMessagingManager.invalidate()
        syncOptOutMarker()
        logger.info("User opted out of tracking")
    }

    /**
     * Everything collected and not yet sent: dropped, never delivered later.
     * Withdrawals stay - the opt-out marker (the record of this very
     * decision) and unsubscribes (contract item 36: a person who opts out
     * right after unsubscribing must still be unsubscribed) - and their
     * retries continue.
     */
    private fun discardCollectedData() {
        attributeFlushJob?.cancel()
        attributeRetryJob?.cancel()
        identityManager.pending.clear()
        attributeRefusals = 0
        outbox.removeIf { !isWithdrawal(it) }
        if (!outbox.any { isWithdrawal(it) }) outboxRetryJob?.cancel()
        // The session's Session Start goes with it; a new session starts with
        // its own when collection is allowed again.
        sessionStartDeferred = trackSessionStartEnabled
        pendingCampaign?.let { inAppMessagingManager.releaseClaim(it.id) }
        pendingCampaign = null
        queueManager.requestClear()
        synchronized(heldPushClicks) { heldPushClicks.clear() }
    }

    /**
     * Lift the person's opt-out - the only call that does. Collection resumes
     * when consent allows it too (an opted-in user whose consent is DENIED
     * still sends nothing). The profile marker is set back to false, once.
     */
    fun optIn() {
        val wasOptedOut = isOptedOut
        isOptedOut = false
        storage.setOptedOut(false)
        applySendGate()
        syncOptOutMarker()
        logger.info("User opted in to tracking")
        if (!isInitialized || !wasOptedOut) return
        inAppMessagingManager.invalidate()
        if (!blocked()) scope.launch { releaseHeldWork() }
    }

    /**
     * What a block or consent PENDING held back, in the order it was
     * produced: held writes, a Session Start that was due while blocked (in a
     * new session), attribute writes, queued events, then the in-app sync and
     * the push registration that were waiting.
     */
    private suspend fun releaseHeldWork() {
        // The marker back to `false` waits for consent like any write and is
        // discarded with them on a denial (it is not a withdrawal); queued
        // again here if the profile still says opted out.
        syncOptOutMarker()
        // An identify made while tracking was refused (D-b) runs for real
        // now - before the Session Start below (contract v6 63).
        releaseHeldIdentifyIfAllowed()
        drainOutbox(force = true)
        if (sessionStartDeferred && !blocked()) {
            sessionStartDeferred = false
            sessionManager.startNewSession()
            if (trackSessionStartEnabled) trackSessionStart()
        }
        flushPendingAttributes()
        queueManager.flush(force = true)
        releaseHeldPushClicks()
        inAppMessagingManager.sync()
        try {
            pushManager?.bootstrapToken()
            pushManager?.retryRegistrationIfUnconfirmed()
        } catch (t: Throwable) {
            logger.warn("Push registration after consent failed: ${t.message}")
        }
        reportPushPermissionIfChanged()
    }

    /**
     * An identify() held while tracking was refused (owner decision D-b):
     * once tracking is allowed, the normal identify runs and the held id is
     * forgotten. Called at init, on a runtime grant and on optIn() - the same
     * points as iOS (releaseHeldIdentifyIfAllowed) and web.
     */
    private fun releaseHeldIdentifyIfAllowed() {
        if (!isInitialized || blocked()) return
        val held = storage.getHeldUserId() ?: return
        storage.setHeldUserId(null)
        if (held != identityManager.getUserId()) identify(held)
    }

    /** The person's own opt-out switch only - consent is [getTrackingConsent]. */
    fun isUserOptedOut(): Boolean = isOptedOut

    /*
     * Mirrors the backend's SUBSCRIPTION_CHANNELS and the web SDK's list. Five,
     * including viber - it was added to the backend and to no SDK, so a
     * preference centre could not offer a channel the API accepts.
     */

    // ─── Marketing subscription ────────────────────────────────────────────
    //
    // A DIFFERENT consent from optIn/optOut above. That one is whether we may
    // OBSERVE this person; this is whether we may MESSAGE them. Neither implies
    // the other - somebody can decline tracking and still want the newsletter.
    //
    // This existed on web only, so a mobile app could not offer an in-app
    // preference centre at all: an Android user had to find an email and click
    // its unsubscribe link. Unsubscribing being harder on mobile than on the
    // web is the wrong way round for the one action that must always be easy.
    //
    // A subscribe is a write like any other: refused while opted out or
    // consent is denied, held while consent is pending (shared SDK contract
    // items 4 and 5). An UNSUBSCRIBE is a withdrawal: always accepted and
    // delivered, kept through optOut(), a denial and wipeData() (item 36).

    /**
     * Set the marketing subscription status for one channel.
     *
     * @param channel one of email, sms, whatsapp, push, viber
     * @param status  one of optedIn, subscribed, unsubscribed
     */
    fun setSubscription(channel: String?, status: String?) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot update subscription")
            return
        }
        if (channel == null || !SUBSCRIPTION_CHANNELS.contains(channel)) {
            logger.error("Invalid channel: $channel. Must be one of: ${SUBSCRIPTION_CHANNELS.joinToString(", ")}")
            return
        }
        if (status == null || !SUBSCRIPTION_STATUSES.contains(status)) {
            logger.error("Invalid status: $status. Must be one of: ${SUBSCRIPTION_STATUSES.joinToString(", ")}")
            return
        }
        // An unsubscribe is a withdrawal: accepted and delivered while opted
        // out or consent is denied or pending (contract item 36). Only a
        // subscribe / opt-in is refused then.
        val withdrawal = status == "unsubscribed"
        if (blocked() && !withdrawal) {
            logger.debug("User opted out or consent denied, skipping setSubscription")
            return
        }
        val userId = subjectUserId(withdrawal)
        val anonymousId = identityManager.getAnonymousId()
        val request = ChannelSubscriptionRequest(
            channel = channel,
            status = status,
            // One identifier, never both - same rule as the web SDK.
            userId = userId,
            anonymousId = if (userId == null) anonymousId else null
        )
        enqueueWrite(
            if (withdrawal) KIND_UNSUBSCRIBE else KIND_SUBSCRIPTION,
            "v1/subscriptions/channel",
            request,
            // A newer change of this person's channel replaces a queued older
            // one - of the SAME identity only (contract item 52; scan 5,
            // Android S2): keyed by the id the write is sent under. Keyed by
            // the anonymous id alone, which identify() keeps, another user's
            // subscribe after an account switch without reset() replaced the
            // previous user's queued unsubscribe, and the withdrawal was lost.
            key = "channel|$channel|${supersedeSubject(userId, anonymousId)}",
            alsoReplaces = resolvedAnonymousKey(userId, anonymousId)?.let { "channel|$channel|$it" },
        )
    }

    /**
     * The userId a subscription write is sent under: the identified user, or
     * - for a withdrawal made while tracking is refused - the userId an
     * identify() held on the device then (owner decision D-b).
     */
    private fun subjectUserId(withdrawal: Boolean): String? =
        if (withdrawal) withdrawalUserId() else identityManager.getUserId()

    /** The identity in a supersede key: the userId, or the anonymous id when anonymous. */
    private fun supersedeSubject(userId: String?, anonymousId: String): String =
        if (userId != null) "user:$userId" else "anon:$anonymousId"

    /**
     * The subject of the queued ANONYMOUS writes this write for [userId] also
     * replaces (contract v6 64; iOS scan 6, B1): a write captured under the
     * current anonymous id with no userId is sent as the identified user
     * (ids resolved at send time, [withCurrentUserId]), so it is the same
     * person's. Keyed apart, a newsletter subscribe held by consent PENDING
     * before identify() was released AFTER the user's later unsubscribe - a
     * withdrawal goes out at once - and resubscribed them. Only for the
     * identified user: a held (D-b) user's write is not what those resolve to.
     */
    private fun resolvedAnonymousKey(userId: String?, anonymousId: String): String? =
        if (userId != null && userId == identityManager.getUserId()) "anon:$anonymousId" else null

    /** Join a subscription group (list) on a channel. */
    fun addToSubscriptionGroup(groupId: String?, channel: String?) =
        updateSubscriptionGroup(groupId, channel, "subscribe")

    /** Leave a subscription group (list) on a channel. */
    fun removeFromSubscriptionGroup(groupId: String?, channel: String?) =
        updateSubscriptionGroup(groupId, channel, "unsubscribe")

    private fun updateSubscriptionGroup(groupId: String?, channel: String?, action: String) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot update subscription group")
            return
        }
        if (groupId.isNullOrBlank()) {
            logger.error("Cannot update subscription group: groupId is required")
            return
        }
        if (channel == null || !SUBSCRIPTION_CHANNELS.contains(channel)) {
            logger.error("Invalid channel: $channel. Must be one of: ${SUBSCRIPTION_CHANNELS.joinToString(", ")}")
            return
        }
        // Leaving a group is a withdrawal (contract item 36): see setSubscription.
        val withdrawal = action == "unsubscribe"
        if (blocked() && !withdrawal) {
            logger.debug("User opted out or consent denied, skipping subscription group update")
            return
        }
        val userId = subjectUserId(withdrawal)
        val anonymousId = identityManager.getAnonymousId()
        val request = SubscriptionGroupRequest(
            groupId = groupId,
            channel = channel,
            action = action,
            userId = userId,
            anonymousId = if (userId == null) anonymousId else null
        )
        enqueueWrite(
            if (withdrawal) KIND_UNSUBSCRIBE else KIND_SUBSCRIPTION,
            "v1/subscriptions/group",
            request,
            key = "group|$groupId|$channel|${supersedeSubject(userId, anonymousId)}",
            alsoReplaces = resolvedAnonymousKey(userId, anonymousId)?.let { "group|$groupId|$channel|$it" },
        )
    }

    /**
     * Delete everything this SDK stored on the device.
     *
     * Deliberately SEPARATE from [optOut]. "Stop collecting" and "delete what
     * you have" are different requests - kept apart on purpose - and
     * conflating them means neither can be done precisely. This is the one an
     * erasure request needs. Does not opt the user out - and KEEPS an
     * existing opt-out (shared SDK contract item 21): deleting data is not
     * consent, and clearing the whole store used to opt the user back in.
     *
     * Erases the anonymous and user id, the event queue, pending writes and
     * the outbox, in-app state and impression history, the session, the
     * device id and the push token - and unregisters that token on the
     * server, under the identity being erased. Push then stays off until
     * registerPushToken() or a permission grant. Withdrawals not yet
     * delivered (an unsubscribe, the opt-out marker) are kept and still sent.
     */
    fun wipeData() = wipeData(keepingToken = null)

    /**
     * [keepingToken]: the pre-init wipe at init keeps the token supplied for
     * THIS launch - the config's or one set before initialize() (contract 84;
     * iOS scan 9 S1, Android identical). It was seeded before the wipe, which
     * filed it as standby (its `sub` is not the erased user) and dropped it:
     * the next identify() found nothing to adopt and went out with no token.
     * It stays on standby for its user and is adopted at their identify().
     */
    private fun wipeData(keepingToken: String?) {
        // Built BEFORE the erase: the token and the identity it is registered
        // under are part of what is being erased.
        val unregister = try {
            pushManager?.unregisterRequestForWipe()
        } catch (t: Throwable) {
            null
        }
        attributeFlushJob?.cancel()
        // Withdrawals survive the erasure (contract item 36): they carry the
        // ids they were made under, and erasing an unsubscribe before the
        // server had it would keep messaging the person who asked to stop.
        outbox.removeIf { !isWithdrawal(it) }
        if (!outbox.any { isWithdrawal(it) }) outboxRetryJob?.cancel()
        identityManager.pending.clear()
        attributeRefusals = 0
        queueManager.requestClear()
        // Bound to the erased user's withdrawals still in the outbox - and
        // never standby when its `sub` is the signed-in user, also with a
        // different user held (contract v9 89; scan 9, Android B1).
        networkClient.retireSdkAuthenticationToken(withdrawalUserId(), identityManager.getUserId())
        // A next user's token on standby belongs to nobody being erased. The
        // signed-in and held users' entries, and those of anyone with a
        // withdrawal still queued, stay (contract v9 89; scan 9, Android B1b).
        networkClient.dropStandbySdkAuthenticationTokens(usersWithWithdrawalsKept(), keepingToken)
        synchronized(identityLock) {
            identityGeneration++
            identityManager.reset()
            // Everything in the store EXCEPT the opt-out (contract item 21):
            // anonymous and user id, session, device id, push token and its
            // confirmation, impression history, pending writes.
            storage.clearAllData()
            identityManager.onDataWiped()
        }
        // The store was cleared under the withdrawals kept above: write them back.
        outbox.persist()
        // After an erasure the device stays off push until the app asks again
        // (registerPushToken() or a permission grant) - the same on every
        // Joryio SDK. Without this the next launch re-registered the token
        // automatically under the new anonymous id.
        storage.setPushUnregistered(true)
        io.joryio.sdk.push.PendingTokenStore.clear(appContext)
        inAppMessagingManager.clear()
        inAppMessagingManager.resetDisplayedCampaigns()
        pendingCampaign = null
        sessionStartDeferred = false
        // A fresh session; the next event opens it with a Session Start.
        sessionManager.endSession()
        // The server still holds the token under the erased identity:
        // unregister it there too. Kept until confirmed, and sent even when
        // opted out - switching a device off is not tracking.
        unregister?.let { pushManager?.queueUnregister(it); pushManager?.retryPendingUnregister() }
        if (isInitialized && outbox.size() > 0) scope.launch { drainOutbox(force = true) }
        logger.info("Local SDK data wiped (opt-out kept)")
    }

    fun setAttribute(key: String?, value: Any?) {
        if (key.isNullOrBlank()) {
            logger.warn("setAttribute() needs a key; ignored")
            return
        }
        setAttributes(mapOf(key to value))
    }

    /**
     * Increment a numeric user attribute
     */
    @JvmOverloads
    fun incrementAttribute(key: String?, by: Number? = 1) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot increment attribute")
            return
        }
        // Opt-out covers EVERY write path (shared SDK contract item 5); this
        // one used to send regardless.
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping incrementAttribute")
            return
        }
        if (key.isNullOrBlank()) {
            logger.warn("incrementAttribute() needs a key; ignored")
            return
        }
        val delta: Number = by ?: 1
        if (NonFinite.isNonFinite(delta)) {
            logger.warn("incrementAttribute('$key') needs a finite number (got $delta); ignored")
            return
        }

        // In-memory optimistic echo only (for in-session read-back).
        identityManager.incrementAttribute(key, delta)

        // Send the DELTA as an atomic `$inc` op — never a locally-computed
        // absolute — so concurrent devices don't clobber the counter.
        val request = SetAttributesRequest(
            userId = identityManager.getUserId(),
            anonymousId = identityManager.getAnonymousId(),
            increment = mapOf(key to delta)
        )
        enqueueWrite(KIND_ATTRIBUTES, "v1/attributes", request)
    }

    /**
     * Unset a user attribute
     */
    fun unsetAttribute(key: String?) {
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot unset attribute")
            return
        }
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping unsetAttribute")
            return
        }
        if (key.isNullOrBlank()) {
            logger.warn("unsetAttribute() needs a key; ignored")
            return
        }
        unsetAttributes(listOf(key))
    }

    /** One unset op for every key - also what a null value in setAttributes() becomes. */
    private fun unsetAttributes(keys: List<String>) {
        keys.forEach { identityManager.unsetAttribute(it) }
        // A pending SET of the same key must not land after the unset.
        identityManager.pending.drop(keys)

        // Send an atomic `$unset` op.
        val request = SetAttributesRequest(
            userId = identityManager.getUserId(),
            anonymousId = identityManager.getAnonymousId(),
            unset = keys
        )
        enqueueWrite(KIND_ATTRIBUTES, "v1/attributes", request)
    }

    // MARK: - In-App Messaging

    /**
     * Set callback for in-app messages
     */
    /**
     * Take over in-app display.
     *
     * Setting a callback OVERRIDES the SDK's own rendering - the SDK will not
     * also display the message, so a host app that renders its own UI does not
     * get two copies. Leave it unset and the SDK displays messages itself.
     *
     * YOU MUST REPORT WHAT HAPPENED via [trackInAppImpression], with
     * `displayed`, `clicked` or `dismissed`. The SDK cannot see a message it did
     * not draw: only your `displayed` report emits `in_app.displayed` and
     * spends the frequency budget (the per-campaign cap and the gap before the
     * next campaign). A message you never report reads as delivered to nobody,
     * and revenue attribution joins on that exact event. Report `displayed`
     * once per message - the SDK does not report it for you.
     *
     * Matches the same warning on iOS `setInAppMessageCallback`.
     */
    /**
     * @param capabilities what YOUR renderer can draw, e.g.
     *   `listOf("content.native")` for a game canvas, adding `"content.html"`
     *   only if you host a WebView. Null keeps the SDK describing its OWN
     *   renderer, which is what every existing caller gets and is wrong only in
     *   the sense that it describes views that will not run. Declaring is
     *   strictly better: the server targets campaigns on this, and its rollup
     *   resolves an absent capability to "no" rather than to "unknown".
     */
    @JvmOverloads
    fun setInAppMessageCallback(
        callback: ((InAppCampaign) -> Unit)?,
        capabilities: List<String>? = null,
    ) {
        if (callback == null) {
            clearInAppMessageCallback()
            return
        }
        inAppMessagingManager.hostCapabilities = capabilities
        // Wrapped so the cross-campaign gap is stamped on handoff. The host owns
        // rendering from here, so handing the message over IS the display as far
        // as the SDK can tell. Without this wrapper the gap would never be
        // stamped for host-rendered apps (React Native included) and every
        // eligible campaign would fire back-to-back.
        inAppMessagingManager.onMessageReady = handoff@{ campaign ->
            // The frequency budget is NOT spent here (shared SDK contract
            // item 17): only a reported "displayed" spends it. Handing a
            // message over is not showing it - a host mid-cutscene may decline.
            // The claim it holds is released only by a reported dismissal
            // (or its expiry), so a host-rendered message keeps the slot
            // while it is on screen (contract item 43).
            if (!canSend()) {
                inAppMessagingManager.releaseClaim(campaign.id)
                return@handoff
            }
            inAppHandedOff++
            try {
                callback(campaign)
            } catch (t: Throwable) {
                // Nothing was shown: the campaign may be offered again.
                inAppMessagingManager.releaseClaim(campaign.id)
                logger.error("Your in-app message callback threw: ${t.message}", t)
            }
            warnIfInAppNeverReported()
        }
        hostHandlesDisplay = true
    }

    /**
     * Hand in-app display back to the SDK's own renderer: drops the callback
     * and the host's declared capabilities. A React Native screen that
     * unmounts its message host calls this, so messages are not handed to a
     * callback nobody listens to.
     */
    fun clearInAppMessageCallback() {
        inAppMessagingManager.hostCapabilities = null
        hostHandlesDisplay = false
        inAppMessagingManager.onMessageReady = { campaign -> displayWithPresenter(campaign) }
    }

    /** Test seam: the Activity in-app messages would be drawn on. */
    @androidx.annotation.VisibleForTesting
    internal fun currentActivityForTesting(): android.app.Activity? = currentActivity?.get()

    /** True once the host app has claimed display via setInAppMessageCallback. */
    @Volatile
    private var hostHandlesDisplay = false

    // Host-rendered in-app messages report nothing unless the host calls
    // trackInAppImpression, and the failure is silent: the campaign spends its
    // frequency budget, emits no in_app.displayed, and reads as delivered to
    // nobody. Nothing else in the system can notice - the server sees an SDK
    // that synced campaigns and never showed one, which is indistinguishable
    // from an audience nobody matched. So the SDK says it, once, where the
    // developer is already looking.
    private var inAppHandedOff = 0
    private var inAppReported = 0
    private var warnedAboutUnreportedInApp = false

    /** Cached e-commerce tracker, replaced when a caller passes a fresh config. */
    private var ecommerceTracker: EcommerceTracker? = null

    /**
     * Display in-app messages using the SDK's own renderer.
     *
     * Registered at init so an integrating app gets working in-app messaging
     * without writing a host, matching iOS - which has always rendered its own.
     * Android previously handed every message to the app and displayed nothing
     * on its own, so the same campaign worked on one platform and silently did
     * nothing on the other.
     */
    private fun setupDefaultInAppDisplay(context: Context) {
        if (inAppPresenter == null) inAppPresenter = discoverInAppPresenter()

        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    // The SDK's own invisible Activities (the permission
                    // prompt, the push trampoline) finish within moments; a
                    // message drawn on one vanished with it, unseen, after
                    // spending the budget.
                    if (io.joryio.sdk.push.isSdkActivity(activity)) return
                    currentActivity = java.lang.ref.WeakReference(activity)
                    onActivityAvailable()
                }
                override fun onActivityPaused(activity: android.app.Activity) {
                    if (currentActivity?.get() === activity) currentActivity = null
                }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            },
        )

        inAppMessagingManager.onMessageReady = { campaign -> displayWithPresenter(campaign) }
    }

    /**
     * An Activity just resumed. The process-level ON_START fires BEFORE the
     * first Activity's onResume, so the launch permission prompt asked there
     * found no Activity and was skipped for the whole process; a message that
     * was ready with no Activity was dropped until the next sync. Both are
     * retried here, where an Activity is guaranteed.
     */
    private fun onActivityAvailable() {
        if (!isInitialized) return
        try {
            maybeRequestPushPermissionAtLaunch()
        } catch (t: Throwable) {
            logger.warn("Launch permission prompt failed: ${t.message}")
        }
        val held = pendingCampaign ?: return
        pendingCampaign = null
        if (!hostHandlesDisplay && canSend()) displayWithPresenter(held)
        else inAppMessagingManager.releaseClaim(held.id)
    }

    private fun displayWithPresenter(campaign: InAppCampaign) {
        // A host callback wins; the SDK must not double-render.
        if (hostHandlesDisplay) return
        // Sending stopped since the message was chosen (contract item 43).
        if (!canSend()) {
            inAppMessagingManager.releaseClaim(campaign.id)
            return
        }
        val presenter = inAppPresenter
        val activity = currentActivity?.get()
        when {
            presenter == null -> {
                inAppMessagingManager.releaseClaim(campaign.id)
                logger.debug("No in-app presenter; not displaying ${campaign.id}")
            }
            activity == null || activity.isFinishing -> {
                // Common during a cold start: sync finishes before the first
                // Activity is resumed. HELD for the next resume instead of
                // dropped. Deliberately does NOT stamp the cross-campaign gap
                // (see noteDisplayed) - a message nobody saw must not spend the
                // budget.
                logger.debug("No foreground Activity; holding ${campaign.id} for the next one")
                pendingCampaign?.takeIf { it.id != campaign.id }?.let { inAppMessagingManager.releaseClaim(it.id) }
                pendingCampaign = campaign
            }
            else -> activity.runOnUiThread {
                // Stamp the gap only AFTER the presenter says it drew.
                //
                // This used to call noteDisplayed() first and discard
                // present()'s Boolean. Both DefaultInAppMessagePresenter and the
                // renderer return false when they decline (null content, type ==
                // CUSTOM, HTML without opt-in, a Throwable).
                //
                // Reported from a production integration, 2026-08-23.
                val drew = try {
                    presenter.present(activity, campaign) { action, meta ->
                        handleInAppMessageEvent(activity, campaign, action, meta)
                    }
                } catch (t: Throwable) {
                    logger.error("In-app presenter failed: ${t.message}", t)
                    false
                }
                if (drew) {
                    inAppMessagingManager.noteDisplayed()
                } else {
                    inAppMessagingManager.releaseClaim(campaign.id)
                    // Deliberately leaves the gap alone: the next sync retries.
                    logger.debug("Presenter declined to draw ${campaign.id}; gap not stamped")
                }
            }
        }
    }

    /**
     * Pull in-app campaigns now.
     *
     * Public for parity with iOS's `syncInAppCampaigns()`, and because a silent
     * push nudge needs a way to say "sync now" from outside the SDK. Without it
     * the only sync points were init and foreground, so a queued message could
     * not appear until the app was reopened.
     */
    fun syncInAppCampaigns() {
        scope.launch {
            inAppMessagingManager.sync()
        }
    }

    /**
     * Track in-app message impression
     */
    /**
     * What a displayed message reports back (BACKLOG C23, 2026-09-26). The
     * impression actions (displayed / clicked / dismissed / converted) go to
     * the tracker as before; the rest are the bridge methods a message script
     * calls, which used to be silent no-ops on Android:
     * `joryioBridge.logCustomEvent`, `setCustomUserAttribute`, `changeUser`,
     * `requestPushPermission`, `logConversion` and `submitForm`. Kept in the
     * base SDK so joryio-ui stays free of a compile-time dependency on it.
     */
    private fun handleInAppMessageEvent(
        activity: android.app.Activity,
        campaign: io.joryio.sdk.models.InAppCampaign,
        action: String,
        meta: Map<String, Any?>,
    ) {
        when (action) {
            "custom_event" -> {
                val name = meta["name"] as? String ?: return
                @Suppress("UNCHECKED_CAST")
                track(name, (meta["properties"] as? Map<String, Any?>) ?: emptyMap())
            }
            "set_attribute" -> {
                val key = meta["key"] as? String ?: return
                val value = meta["value"] ?: return
                setAttribute(key, value)
            }
            "change_user" -> {
                val userId = meta["userId"] as? String ?: return
                if (userId.isNotBlank()) identify(userId)
            }
            "push_permission" -> requestPushPermission(activity)
            "form_submit" -> {
                @Suppress("UNCHECKED_CAST")
                val values = (meta["values"] as? Map<String, Any?>) ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val respond = meta["respond"] as? (String) -> Unit
                submitInAppForm(campaign.id, values) { json -> respond?.invoke(json) }
            }
            // A click keeps the name the message gave it (data-action, logClick, a
            // native button id); it used to be dropped here with the whole meta map.
            "clicked" -> trackInAppImpression(campaign.id, action, meta["action"] as? String)
            else -> trackInAppImpression(campaign.id, action)
        }
    }

    /**
     * Submit a form shown inside an in-app message. The backend validates
     * against the form the campaign carries and writes the person; the JSON
     * answer is what the message runtime shows. Never fails loudly: an error -
     * or an opted-out / not-yet-consented user, for whom this write is refused -
     * becomes an `ok: false` answer.
     */
    fun submitInAppForm(campaignId: String?, values: Map<String, Any?>?, onResult: ((String) -> Unit)?) {
        val gson = io.joryio.sdk.network.WireJson.gson
        val fallback = gson.toJson(io.joryio.sdk.models.InAppFormSubmitResponse(ok = false, message = "Something went wrong. Please try again."))
        if (campaignId.isNullOrBlank() || !canSend()) {
            try {
                onResult?.invoke(fallback)
            } catch (t: Throwable) {
                logger.error("Form result callback threw: ${t.message}", t)
            }
            return
        }
        // Identity captured at call time, like every other write.
        val request = io.joryio.sdk.models.InAppFormSubmitRequest(
            campaignId = campaignId,
            userId = identityManager.getUserId(),
            anonymousId = identityManager.getAnonymousId(),
            values = values ?: emptyMap(),
        )
        scope.launch {
            val json = when (val result = networkClient.submitInAppForm(request)) {
                is NetworkResult.Success -> gson.toJson(result.data)
                is NetworkResult.Error -> {
                    logger.warn("In-app form submit failed: ${result.message}")
                    fallback
                }
            }
            try {
                onResult?.invoke(json)
            } catch (t: Throwable) {
                logger.error("Form result callback threw: ${t.message}", t)
            }
        }
    }

    /**
     * Report what happened to a message the HOST rendered.
     *
     * @param action displayed, clicked, dismissed or converted (the backend's
     *   vocabulary; synonyms such as `impression` or `click` are accepted and
     *   sent as the canonical word). Only `displayed` counts toward frequency
     *   caps.
     * @param elementId for a click, which button or link (wire field `elementId`, max 128).
     */
    // @JvmOverloads keeps the two-argument form for Java callers (the Unity
    // bridge is Java); the click NAME was added 2026-09-26 with a Kotlin
    // default, which Java cannot see.
    @JvmOverloads
    fun trackInAppImpression(campaignId: String?, action: String?, elementId: String? = null) {
        // A dismissed message frees its display claim even when nothing may
        // be SENT (contract item 43; scan 4, M3). The release sat behind the
        // send gate, so a message closed while consent was pending or after
        // an opt-out kept its campaign claimed and it could not show again.
        if (!campaignId.isNullOrBlank() && io.joryio.sdk.messaging.canonicalInAppAction(action) == "dismissed") {
            inAppMessagingManager.releaseClaim(campaignId)
        }
        if (!isInitialized) {
            logger.warn("SDK not initialized, cannot track in-app impression")
            return
        }
        // A write path like any other (shared SDK contract item 5).
        if (blocked()) {
            logger.debug("User opted out or consent denied, skipping trackInAppImpression")
            return
        }
        if (campaignId.isNullOrBlank() || action.isNullOrBlank()) {
            logger.warn("trackInAppImpression() needs a campaign id and an action; ignored")
            return
        }
        inAppReported++
        scope.launch {
            inAppMessagingManager.trackImpression(campaignId, action, elementId)
        }
    }

    /**
     * Warn once when a host takes in-app messages and reports none of them.
     *
     * Threshold of 3 rather than 1: a host may legitimately decline to show a
     * particular message (mid-cutscene, mid-transaction), and one unreported
     * handoff proves nothing. Three in a row is an integration that has not
     * wired reporting at all.
     *
     * Logged rather than thrown, and once rather than per message, because this
     * is a correctness hint for a developer, not a runtime fault - a message
     * that failed to report is still better delivered than not delivered.
     */
    private fun warnIfInAppNeverReported() {
        if (warnedAboutUnreportedInApp || inAppReported > 0) return
        if (inAppHandedOff < 3) return
        warnedAboutUnreportedInApp = true
        logger.warn(
            "$inAppHandedOff in-app messages were handed to your callback and none were reported. " +
                "Call Joryio.trackInAppImpression(campaignId, \"displayed\"|\"clicked\"|\"dismissed\") " +
                "when you render one, or campaign analytics will show zero displays while these " +
                "messages still consume the frequency budget.",
        )
    }

    /**
     * Forget which in-app campaigns have already been shown.
     *
     * A TESTING affordance, matching iOS: frequency state is held locally (that
     * is what lets a trigger fire instantly and offline), so without this the
     * only way to re-test a campaign on Android was to uninstall the app.
     * Changing the campaign server-side does not help, because the client half
     * of the state still suppresses the repeat.
     */
    fun resetDisplayedCampaigns() {
        if (!requireDebug("resetDisplayedCampaigns")) return
        inAppMessagingManager.resetDisplayedCampaigns()
    }

    /**
     * Re-evaluate the campaigns already synced, without going to the network.
     *
     * Matches iOS. Useful after changing attributes locally, when you want the
     * decision re-made against what the SDK already holds rather than waiting
     * for the next sync.
     */
    fun evaluateInAppCampaigns() {
        if (!requireDebug("evaluateInAppCampaigns")) return
        inAppMessagingManager.evaluateNow()
    }

    /**
     * Gate for the QA-only calls that CHANGE behaviour.
     *
     * Only the mutating ones are gated. `resetDisplayedCampaigns` wipes real
     * frequency caps and `evaluateInAppCampaigns` can force a message on screen -
     * called in production (a stray line, or a snippet copied from a QA guide)
     * they corrupt capping and reporting for real users.
     *
     * The read-only diagnostics (getQueueSize, getSessionInfo, getIdentity,
     * getDeviceToken, currentApiEndpoint, lastTransportError) are deliberately
     * NOT gated: they cannot damage anything, and a support screen wants them
     * most in production - where gating would also force verbose logging on just
     * to read a session id.
     */
    private fun requireDebug(method: String): Boolean {
        if (debugEnabled) return true
        logger.warn(
            "$method is a testing API and is ignored unless enableDebug is on. " +
                "It changes live state (frequency caps / message display), so it must not run " +
                "in a production build.",
        )
        return false
    }

    /**
     * The push token this device is registered with, or null if none yet.
     *
     * Matches iOS's getDeviceToken(). Diagnostic: it answers "is this device
     * actually reachable by push?" without needing the dashboard.
     */
    fun getDeviceToken(): String? = storage.getDeviceToken()

    /** Current session id, when it started, and how long it has been running. */
    fun getSessionInfo(): SessionInfo = sessionManager.getSession()

    /** The identifiers this device is currently reporting under. */
    fun getIdentity(): Pair<String?, String> =
        identityManager.getUserId() to identityManager.getAnonymousId()

    /**
     * How many events are waiting to be sent.
     *
     * A plain call, matching iOS and web. The queue is a Room table, so this
     * reads a count the queue keeps up to date after every write rather than
     * running a query on the caller's thread - it used to be `suspend` for that
     * reason, while every doc showed a plain call (audit 2026-09-26, A21).
     */
    fun getQueueSize(): Int = queueManager.getQueueSize()

    // MARK: - Push Notifications

    /**
     * Register push notification token.
     *
     * Also refreshes `push_permission`, because ARRIVING HERE IS EVIDENCE. The
     * OS does not hand out a token without permission, so a token in hand
     * settles the question no matter what was last reported.
     *
     * Why that matters: the attribute is otherwise refreshed on init and on
     * foreground, and a host app that grants permission after init and
     * registers its own token - every React Native integration using Firebase
     * Messaging, for instance - could sit on a stale `not_determined` until the
     * next foreground. A production integration reported exactly that: token
     * stored correctly, attribute still not_determined.
     *
     * That is the common path, not an edge case: the OS prompt is deliberately
     * deferred until after sign-up in most apps, and init always runs first. A
     * segment filtering on push_permission = granted then excludes real,
     * reachable users - it shrinks the audience silently, which is the failure
     * mode nobody notices.
     */
    /**
     * @param onResult called once with the registration outcome. Optional, and
     *   omitting it is exactly the previous behaviour.
     *
     *   Without it this was fire-and-forget: the only sign of a rejection was
     *   a logger.error gated behind enableDebug, so a release build could fail
     *   on every launch and never say so. Matches iOS's
     *   `registerPushToken(_:onResult:)`.
     *
     *   Invoked on a background coroutine, not the main thread - post to the
     *   main looper before touching UI.
     */
    @JvmOverloads
    fun registerPushToken(
        token: String?,
        onResult: ((PushRegistrationResult) -> Unit)? = null,
    ) {
        // Host code: a throwing callback must not stop the SDK's own
        // bookkeeping around it (contract item 34).
        val safeResult: ((PushRegistrationResult) -> Unit)? = onResult?.let { cb ->
            { r: PushRegistrationResult ->
                try {
                    cb(r)
                } catch (t: Throwable) {
                    logger.error("Your registerPushToken callback threw: ${t.message}", t)
                }
            }
        }
        // Stored and sent trimmed (contract v6 69): a token with a trailing
        // newline from a bridge or a copy was registered as a different token.
        val trimmed = token?.trim()
        if (trimmed.isNullOrEmpty()) {
            safeResult?.invoke(PushRegistrationResult(success = false, message = "No token"))
            return
        }
        // Opt-out covers push registration too (shared SDK contract item 5).
        // The token is still kept, so opting back in registers it.
        if (blocked()) {
            storage.setDeviceToken(trimmed)
            storage.setPushUnregistered(false)
            safeResult?.invoke(PushRegistrationResult(success = false, message = "User opted out of tracking or consent denied"))
            return
        }
        pushManager?.registerToken(trimmed, onResult = safeResult, explicit = true)
            ?: safeResult?.invoke(
                // No push manager: firebase-messaging is not on the classpath.
                // Silence here would look identical to a successful registration.
                PushRegistrationResult(success = false, message = "Push is not available (firebase-messaging missing)")
            )
        reportPushPermissionIfChanged()
    }

    /**
     * Turn push off for this device. Durable: the request is kept until the
     * server confirms it, and the SDK does not register a token on its own
     * again - not at the next launch, not when FCM rotates the token - until
     * [registerPushToken] is called or the user grants permission through
     * [requestPushPermission].
     */
    fun unregisterPush() {
        pushManager?.unregisterToken()
    }

    /**
     * Report that a push arrived on this device.
     *
     * Called by JoryioFirebaseMessagingService the moment a Joryio message is
     * received. Public because a host app running its OWN messaging service -
     * which most apps with an existing push stack do - never routes through
     * ours, and would otherwise report no deliveries at all. Those apps already
     * call registerPushToken(); this is the matching half.
     *
     * @param trackingId the `trackingId` from the message's data payload.
     */
    fun reportPushDelivered(trackingId: String?) {
        if (trackingId.isNullOrBlank()) return
        if (!canSend()) return
        scope.launch {
            try {
                networkClient.reportPushDelivered(trackingId)
            } catch (e: Exception) {
                // Telemetry never surfaces to the host app: the notification
                // has already been shown, and a receipt is not worth a crash.
                logger.debug("Delivery receipt failed: ${e.message}")
            }
        }
    }

    /**
     * Check if push is enabled
     */
    fun isPushEnabled(): Boolean {
        return pushManager?.isPushEnabled() ?: false
    }

    /**
     * Where the user stands on notifications, without asking them anything.
     *
     * Check this BEFORE showing a primer. `NOT_DETERMINED` is the only state in
     * which the system prompt can still appear - on Android 13+ a denial is
     * permanent short of a trip to Settings - so a primer shown to an already
     * denied user spends their goodwill on a prompt that can never be displayed.
     */
    fun getPushPermissionStatus(): PushPermissionStatus = PushPermission.status(appContext)

    /**
     * Report the notification-permission state as a user attribute, but ONLY when
     * it has changed since we last reported it.
     *
     * This is what makes a push primer measurable: without it a marketer cannot
     * target "has never been asked" (the only audience for whom a primer is even
     * possible), nor tell whether primers are moving opt-in. Reserved key
     * `push_permission`, values matching PushPermissionStatus lowercased.
     *
     * No `$` prefix: contact attributes are written to Mongo as
     * `attributes.<key>` dotted paths, and a key starting with `$` breaks there.
     *
     * Change-detection is not an optimisation. Attributes are not deduplicated
     * server-side, so reporting unconditionally would spend a request on every
     * launch and every foreground to restate a value that changes maybe twice in
     * the lifetime of an install.
     */
    /**
     * Opt-in auto-prompt (JoryioConfig.requestPushPermissionAtLaunch).
     *
     * Runs at the first foreground because the system prompt needs an Activity,
     * and only ONCE per process: re-firing it every foreground would do nothing
     * on Android (the prompt is spent) while looking like it might.
     */
    private fun maybeRequestPushPermissionAtLaunch() {
        if (!requestPushPermissionAtLaunch || launchPromptAttempted) return
        val activity = currentActivity?.get() ?: return
        launchPromptAttempted = true
        logger.info("requestPushPermissionAtLaunch is on; asking now")
        requestPushPermission(activity)
    }

    private fun reportPushPermissionIfChanged() {
        if (!isInitialized || blocked()) return
        try {
            val current = PushPermission.status(appContext).name.lowercase()
            if (storage.getReportedPushPermission() == current) return
            // Already on its way: not queued twice.
            if (identityManager.pending.pending()[PUSH_PERMISSION_ATTRIBUTE] == current) return
            // Remembered as reported only when the server confirms it
            // (noteAttributesStored), not here (scan 4, M6).
            logger.debug("Push permission changed to $current; reporting")
            setAttribute(PUSH_PERMISSION_ATTRIBUTE, current)
        } catch (t: Throwable) {
            logger.warn("Could not report push permission: ${t.message}")
        }
    }

    /**
     * Show the system notification prompt, reporting the outcome on the main thread.
     *
     * The SDK never calls this for you. The prompt is one-shot, and neither
     * `initialize()` nor a dashboard toggle can know when your app has earned the
     * right to ask - so the moment is yours to choose. Ask after a booking, a
     * purchase, or from a notifications settings screen; not on first launch.
     *
     * On a grant, the FCM token is registered automatically, so a caller does not
     * have to remember to follow up with [registerPushToken].
     *
     * Below API 33 there is no runtime permission: the callback reports whether
     * notifications are enabled in Settings, without showing anything.
     */
    @JvmOverloads
    fun requestPushPermission(
        activity: android.app.Activity?,
        callback: ((PushPermissionStatus) -> Unit)? = null,
    ) {
        // Null (Unity before its Activity exists) or a finishing Activity
        // cannot host a dialog: answer with the current state, never crash.
        val host = activity?.takeUnless { it.isFinishing } ?: currentActivity?.get()
        if (host == null) {
            logger.warn("requestPushPermission() needs a foreground Activity; reporting the current state")
            val status = PushPermission.status(appContext)
            postToMain {
                try {
                    callback?.invoke(status)
                } catch (t: Throwable) {
                    logger.error("Push permission callback threw: ${t.message}", t)
                }
            }
            return
        }
        PushPermission.request(host) { status ->
            logger.info("Push permission result: $status")
            reportPushPermissionIfChanged()
            if (status == PushPermissionStatus.GRANTED) {
                // The token may have been withheld while permission was missing.
                try {
                    // The user just said yes to notifications: that lifts an
                    // earlier unregisterPush(), like registerPushToken() does.
                    storage.setPushUnregistered(false)
                    pushManager?.bootstrapToken()
                } catch (t: Throwable) {
                    logger.warn("Token bootstrap after permission grant failed: ${t.message}")
                }
            }
            try {
                callback?.invoke(status)
            } catch (t: Throwable) {
                logger.error("Push permission callback threw: ${t.message}", t)
            }
        }
    }

    /**
     * Track push notification click
     */
    fun trackPushClick(trackingId: String?) {
        if (trackingId.isNullOrBlank()) return
        if (!isInitialized || blocked()) return
        if (!canSend()) {
            // Consent PENDING: held like an event, sent on GRANTED, dropped
            // on DENIED or an opt-out (scan 6, Android NICE 5). The tap is
            // already marked as handled on its Intent, so returning here lost
            // it for good.
            synchronized(heldPushClicks) {
                if (heldPushClicks.size >= MAX_HELD_PUSH_CLICKS) heldPushClicks.removeFirst()
                heldPushClicks.addLast(trackingId)
            }
            logger.debug("Consent pending: push click held until a decision")
            return
        }
        sendPushClick(trackingId)
    }

    private fun releaseHeldPushClicks() {
        val clicks = synchronized(heldPushClicks) { heldPushClicks.toList().also { heldPushClicks.clear() } }
        clicks.forEach { sendPushClick(it) }
    }

    private fun sendPushClick(trackingId: String) {
        scope.launch {
            try {
                // The answer counts: "tracked successfully" used to be logged
                // for an HTTP error too.
                when (val result = networkClient.trackPushClick(trackingId)) {
                    is io.joryio.sdk.network.NetworkResult.Success -> logger.info("Push click tracked successfully")
                    is io.joryio.sdk.network.NetworkResult.Error -> logger.error("Push click not tracked: ${result.message}")
                }

                // Show anything triggered by the tap. Waits for a sync that
                // starts AFTER the tap - a sync already in flight is followed
                // by a fresh one rather than counted (it used to be, and the
                // tap then evaluated a campaign list from before it).
                inAppMessagingManager.onPushTapped()
                logger.debug("Triggered in-app sync after push click")
            } catch (e: Exception) {
                logger.error("Failed to track push click: ${e.message}", e)
            }
        }
    }

    // MARK: - SDK Authentication

    /**
     * Set/replace the customer-minted JWT used for SDK Authentication. Attached as
     * `X-Joryio-Auth` on every subsequent ingest request. Thread-safe; typically
     * called after login and from within the auth-error handler to supply a fresh
     * token. Supplying a token opts the feature in even if it was left disabled in
     * [JoryioConfig].
     */
    fun setSdkAuthenticationToken(token: String?) {
        if (token.isNullOrBlank()) {
            logger.warn("setSdkAuthenticationToken() needs a token; ignored")
            return
        }
        networkClient.setSdkAuthenticationToken(token)
    }

    /**
     * Register a handler invoked when an ingest request is rejected with HTTP 401
     * `sdk_authentication_error`. Mint a fresh JWT and call
     * [setSdkAuthenticationToken] from the handler; doing so synchronously lets the
     * SDK retry the rejected request once. The handler runs on the SDK's network
     * thread — dispatch any heavy work elsewhere.
     */
    fun setSdkAuthenticationErrorHandler(handler: ((SdkAuthError) -> Unit)?) {
        networkClient.setSdkAuthenticationErrorHandler { error ->
            try {
                handler?.invoke(error)
            } catch (t: Throwable) {
                logger.error("SDK authentication error handler threw: ${t.message}", t)
            }
        }
    }

    // MARK: - Utilities

    /**
     * E-commerce tracking helper - the standard product / cart / checkout /
     * purchase events, with a default currency applied to each.
     *
     * This accessor is what the e-commerce documentation has always shown
     * (`Joryio.getInstance().ecommerce(...)`), but it did not exist:
     * [EcommerceTracker] was public and constructible, and nothing on this class
     * returned one, so every documented Kotlin snippet failed to compile.
     *
     * Pass [config] to change the currency; omit it to
     * reuse the existing tracker.
     *
     * ```kotlin
     * val ecommerce = Joryio.getInstance().ecommerce(EcommerceConfig(currency = "USD"))
     * ecommerce.viewProduct(EcommerceProduct(productId = "SKU123", name = "Blue Shirt", price = 29.99))
     * ```
     */
    @JvmOverloads
    fun ecommerce(config: EcommerceConfig? = null): EcommerceTracker {
        // Deliberately usable before initialize(): the tracker only forwards to
        // track(), which already queues and warns when uninitialised. Failing
        // here would punish an app that builds its tracker in a field
        // initialiser - a very ordinary thing to do.
        config?.let {
            val tracker = EcommerceTracker(this, it)
            ecommerceTracker = tracker
            return tracker
        }
        return ecommerceTracker ?: EcommerceTracker(this).also { ecommerceTracker = it }
    }

    /**
     * Flush event queue immediately
     */
    fun flush() {
        scope.launch {
            // Explicit: skips the backoff wait. Still sends nothing while
            // consent is pending or the user opted out.
            queueManager.flush(force = true)
        }
    }

    /**
     * Get anonymous ID
     */
    fun getAnonymousId(): String {
        return identityManager.getAnonymousId()
    }

    /**
     * Get user ID
     */
    fun getUserId(): String? {
        return identityManager.getUserId()
    }

    /**
     * Get session ID
     */
    fun getSessionId(): String {
        return sessionManager.getSessionId()
    }

    /** The API base URL actually in use. Diagnostics only. */
    val currentApiEndpoint: String
        get() = networkClient.currentApiEndpoint

    /**
     * The most recent transport failure, or null if the last call succeeded.
     *
     * "Initialized" and "the server accepts us" are different claims. Without
     * this, a wrong SDK key looks identical to a healthy integration: the SDK
     * reports itself up while every request is rejected 401. Mirrors iOS.
     */
    val lastTransportError: JoryioTransportError?
        get() = networkClient.lastTransportError

    // MARK: - Static Methods

    companion object {
        /** Coalescing window for attribute writes; see scheduleAttributeFlush(). */
        private const val ATTRIBUTE_FLUSH_DEBOUNCE_MS = 800L
        private const val ATTRIBUTE_RETRY_BASE_MS = 2_000L

        /** The opt-out record on the profile. NO `$` prefix: the backend drops `$` keys (contract item 14). */
        const val TRACKING_OPTED_OUT_ATTRIBUTE = "tracking_opted_out"

        private const val SESSION_START_EVENT = "Session Start"

        /** Outbox kind of attribute ops; reset() drops these with the pending writes. */
        private const val KIND_ATTRIBUTES = "attributes"

        /**
         * Outbox kind of the `tracking_opted_out` marker: kept through an
         * opt-out's discard and a reset(), sent while opted out.
         */
        private const val KIND_OPT_OUT_MARKER = "opt_out_marker"

        /** A subscribe / opt-in: held and discarded with the other writes. */
        private const val KIND_SUBSCRIPTION = "subscription"

        /**
         * A channel `unsubscribed` or a group `unsubscribe`: a withdrawal,
         * always kept and delivered, like the opt-out marker (contract item 36).
         */
        private const val KIND_UNSUBSCRIBE = "unsubscribe"

        /** Reserved attribute carrying the notification-permission state. */
        const val PUSH_PERMISSION_ATTRIBUTE = "push_permission"

        /**
         * This SDK's version - the same constant the User-Agent and the
         * `X-Joryio-SDK-Version` header carry, pinned by the backend's
         * sdk-version-parity spec.
         */
        const val SDK_VERSION: String = Logger.SDK_VERSION

        /**
         * An ANDROID app's key (contract item 60; scan 5, Unity S2). The
         * backend mints `jry_sdk_<platform>_<random>` (app.entity.ts), and
         * this used to accept any `jry_sdk_` key: an app that shipped its iOS
         * or web key in the Android build ran, filed every Android profile,
         * event and FCM token under the other app, and push then failed
         * against that app's credentials in silence. iOS already refused any
         * key but `jry_sdk_ios_`.
         */
        private const val SDK_KEY_PREFIX = "jry_sdk_android_"

        /** Push clicks held while consent is PENDING; the oldest goes first. */
        private const val MAX_HELD_PUSH_CLICKS = 50

        @Volatile
        private var instance: Joryio? = null

        /**
         * Initialize the SDK.
         *
         * [context] may be an Activity - pass the current one when initializing
         * after the app's first screen is already showing (a bridge, a late
         * init): the SDK then has an Activity for in-app messages and the
         * permission prompt immediately. Only the application context is kept.
         *
         * Buffered calls made before this (through the static methods or
         * [whenReady]) run here, in the order they were made, before any call
         * made after it.
         *
         * A null or empty [sdkKey] / [apiHost] from a Java caller does not
         * throw: the SDK starts in no-send mode and says why.
         */
        @JvmStatic
        @JvmOverloads
        fun initialize(
            context: Context,
            sdkKey: String?,
            apiHost: String?,
            config: JoryioConfig? = JoryioConfig()
        ): Joryio {
            val key = sdkKey?.trim().orEmpty()
            val host = apiHost?.trim().takeUnless { it.isNullOrEmpty() } ?: run {
                android.util.Log.e("Joryio", "initialize() needs an apiHost; the SDK will NOT send any data.")
                NO_HOST
            }
            // Re-initialisation is NOT supported: managers, timers, the queue
            // and the session are all built here, and swapping the endpoint
            // underneath them would leave half the SDK talking to the old host.
            //
            // But say so LOUDLY when the caller asked for something different.
            // This used to return the existing instance in SILENCE - not even
            // a log line - so an app that changed its API host kept sending to
            // the previous one with no explanation anywhere. iOS had the same
            // bug and at least printed.
            instance?.let { existing ->
                if (existing.apiHost != host || existing.sdkKey != key) {
                    android.util.Log.e(
                        "Joryio",
                        "initialize() called again with a DIFFERENT configuration, which is " +
                            "ignored: still sending to ${existing.apiHost}, not $host. " +
                            "Restart the app to apply new settings.",
                    )
                }
                return existing
            }

            val sdk = synchronized(this) {
                instance ?: Joryio(
                    context = context.applicationContext,
                    sdkKey = if (host == NO_HOST) "" else key,
                    apiHost = host,
                    config = config ?: JoryioConfig(),
                    seedActivity = context as? android.app.Activity,
                ).also { instance = it }
            }
            // Calls buffered before readiness run NOW - with synchronous
            // initialize() too. They used to be replayed only by
            // initializeAsync(), so a track() made before a plain initialize()
            // sat in the buffer for the life of the process.
            drainPending(sdk)
            return sdk
        }

        /** Placeholder host for a missing apiHost: valid for Retrofit, never contacted. */
        private const val NO_HOST = "https://invalid.invalid"

        /**
         * Get SDK instance
         */
        @JvmStatic
        fun getInstance(): Joryio {
            return instance ?: throw IllegalStateException(
                "Joryio SDK not initialized. Call Joryio.initialize() first."
            )
        }

        /**
         * Check if SDK is initialized
         */
        @JvmStatic
        fun isInitialized(): Boolean {
            return instance != null
        }

        // ── Static forwarders the React Native bridge calls ─────────────
        // JoryioModule.kt calls these on the Joryio TYPE. They were
        // instance-only here, so the bridge did not compile without a local
        // patch on the consumer's side - five pulls in a row (Teamder,
        // 2026-09-28). The parity tripwire now parses this companion object
        // for every name the bridge calls statically; a grep of the sources
        // cannot tell an instance method from a static one, which is how the
        // gap survived. The void calls queue until initialize() like the rest
        // of the pre-initialization API; the query answers false before then.
        //
        // Deliberately NOT @JvmStatic: that would emit a static method on the
        // outer class with the same JVM signature as the instance method and
        // the compiler rejects the clash. Kotlin callers reach these as
        // Joryio.optOut(); Java callers use Joryio.Companion.optOut() or the
        // instance.

        // Before initialize() these four are RECORDED - in memory and, when
        // androidx.startup gave the SDK a Context, on disk (PreInitStore) -
        // and applied by the constructor before any init side effect
        // (contract item 25). The two switches stay independent (item 24).

        fun optOut() {
            if (recordPreInit { preInitOptOut = true }) {
                purgeBufferedDataCalls()
                return
            }
            withSdk { it.optOut() }
        }

        fun optIn() {
            if (recordPreInit { preInitOptOut = false }) return
            withSdk { it.optIn() }
        }

        fun updateConsent(consent: TrackingConsent?) {
            if (consent == null) {
                android.util.Log.w("Joryio", "updateConsent() needs a value; ignored")
                return
            }
            // "Undecided" does not reopen a denial made before it - in this
            // process, or stored by an earlier launch (contract item 45).
            if (recordPreInit {
                    val denied = preInitConsentValue == TrackingConsent.DENIED ||
                        (preInitConsentValue == null && storedConsentDenied())
                    if (!(consent == TrackingConsent.PENDING && denied)) {
                        preInitConsentValue = consent
                    }
                }
            ) {
                if (consent == TrackingConsent.DENIED) purgeBufferedDataCalls()
                return
            }
            withSdk { it.updateConsent(consent) }
        }

        /**
         * Erase this SDK's data on the device (see the instance method).
         * Before initialize() the erasure happens inside init, before the
         * Session Start, in-app sync and push registration would run.
         */
        fun wipeData() {
            if (recordPreInit { preInitWipe = true; preInitIdentityCalled = true }) {
                // What was buffered before the wipe is erased with everything
                // else - except its withdrawals, run before the wipe at init.
                // The SDK-auth token and handler are configuration, not data:
                // kept (scan 6, Android NICE 4), so the withdrawals replayed
                // before the wipe go out with the token set for them.
                synchronized(pendingActions) {
                    pendingActions.removeAll { !it.withdrawal && !it.keepThroughWipe }
                    preWipeActionCount = pendingActions.size
                }
                return
            }
            withSdk { it.wipeData() }
        }

        fun clearInAppMessageCallback() = withSdk { it.clearInAppMessageCallback() }

        /**
         * The opt-out switch. Before initialize() it reflects an optOut() /
         * optIn() already made, and otherwise the opt-out an earlier launch
         * stored (contract item 44) - never a default `false` over a stored
         * decision.
         */
        fun isUserOptedOut(): Boolean =
            instance?.isUserOptedOut()
                ?: synchronized(preInitLock) { preInitOptOut }
                ?: PreInitStore.load()?.optOut
                ?: StoredDecisions.get(PreInitStore.appContext, StoredDecisions.OPTED_OUT)
                ?: false

        /**
         * The consent switch. Before initialize(): the consent decided so far
         * - a call made before init, or a denial an earlier launch stored
         * (contract item 44) - and GRANTED when nothing is decided or stored,
         * the state an unset `trackingConsent` behaves as (contract item 24).
         * The same answer as iOS (contract v6 65): this returned null there,
         * so the same JavaScript call read `null` on Android and `granted` on
         * iOS. Never null; the nullable type is kept for existing callers.
         */
        fun getTrackingConsent(): TrackingConsent? {
            instance?.let { return it.getTrackingConsent() }
            val decided = synchronized(preInitLock) { preInitConsentValue } ?: PreInitStore.load()?.consent
            // PENDING never reopens a stored denial (contract items 24, 45).
            return when (decided) {
                TrackingConsent.GRANTED, TrackingConsent.DENIED -> decided
                TrackingConsent.PENDING -> if (storedConsentDenied()) TrackingConsent.DENIED else TrackingConsent.PENDING
                null -> if (storedConsentDenied()) TrackingConsent.DENIED else TrackingConsent.GRANTED
            }
        }

        private fun storedConsentDenied(): Boolean =
            StoredDecisions.get(PreInitStore.appContext, StoredDecisions.CONSENT_DENIED) == true

        fun syncInAppCampaigns() = withSdk { it.syncInAppCampaigns() }

        fun resetDisplayedCampaigns() = withSdk { it.resetDisplayedCampaigns() }

        /**
         * Initialize WITHOUT blocking the calling thread.
         *
         * [initialize] builds the whole SDK inline, and the expensive part is
         * Android Keystore setup for the encrypted stores — measured at ~200ms
         * on a healthy emulator, and more on a first run, where the master key
         * must actually be generated. A native app calls initialize() from
         * Application.onCreate(), i.e. on the MAIN thread, so that cost is added
         * directly to app startup before the first frame.
         *
         * This variant does the same work on a background thread and hands the
         * instance back on the main thread. Nothing is dropped in the meantime:
         * calls made through the convenience methods before the SDK is ready are
         * buffered and replayed in order (see [withSdk]).
         *
         * Prefer this from Application.onCreate(). [initialize] remains for
         * callers that genuinely need the instance synchronously.
         */
        @JvmStatic
        @JvmOverloads
        fun initializeAsync(
            context: Context,
            sdkKey: String?,
            apiHost: String?,
            config: JoryioConfig? = JoryioConfig(),
            onReady: ((Joryio) -> Unit)? = null,
        ) {
            // Host code (contract item 56): a throwing onReady is logged, never
            // rethrown on the main looper, where it crashed the app.
            val ready: ((Joryio) -> Unit)? = onReady?.let { cb ->
                { sdk: Joryio ->
                    try {
                        cb(sdk)
                    } catch (t: Throwable) {
                        android.util.Log.e("Joryio", "Your initializeAsync onReady callback threw", t)
                    }
                }
            }
            instance?.let { existing ->
                ready?.let { cb -> mainHandler.post { cb(existing) } }
                return
            }
            // The Activity, if one was passed, is handed to the init thread
            // only as a weak reference.
            val activityRef = (context as? android.app.Activity)?.let { java.lang.ref.WeakReference(it) }
            val appContext = context.applicationContext
            Thread({
                val sdk = try {
                    initialize(activityRef?.get() ?: appContext, sdkKey, apiHost, config)
                } catch (t: Throwable) {
                    // Never surface an init failure as a crash on a thread the
                    // host app cannot catch on.
                    android.util.Log.e("Joryio", "Asynchronous initialize failed", t)
                    null
                }
                // initialize() already drained the buffer (on the main thread,
                // see drainPending), so this runs after every buffered call.
                if (sdk != null && ready != null) mainHandler.post { ready(sdk) }
            }, "joryio-init").apply { isDaemon = true }.start()
        }

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * Actions issued before the SDK finished initializing.
         *
         * Bounded: a host app that never completes initialization must not grow
         * this without limit. Oldest-first drop matches QueueManager.
         */
        private val pendingActions = ArrayDeque<PendingAction>()

        /**
         * A buffered call. [withdrawal] marks an unsubscribe, a group leave
         * or a push unregister: the cap drops the oldest OTHER call first
         * (contract item 51; scan 5) - a withdrawal is the one message that
         * must always get through, and it used to be dropped like a track().
         */
        private class PendingAction(
            val withdrawal: Boolean,
            val run: (Joryio) -> Unit,
            /** Configuration, not data: kept through a pre-init wipeData() (the SDK-auth token and handler). */
            val keepThroughWipe: Boolean = false,
            /** A buffered unregisterPush() (see [hasBufferedUnregisterPush]). */
            val unregistersPush: Boolean = false,
            /** A call that COLLECTS (see [withData]): a later pre-init optOut() or denial drops it. */
            val data: Boolean = false,
        )

        /**
         * A pre-init optOut() / updateConsent(DENIED): the data calls buffered
         * BEFORE it collected what the person has now refused, so they go
         * (contract v8 83; scan 8, Android S2) - as a live optOut() discards
         * what is queued, and as iOS purgeCollectingPreInitCalls() does.
         * They used to stay in the buffer, and a later pre-init optIn() or
         * grant sent them at init. Withdrawals, identify / reset, the token
         * and every other control call are kept.
         */
        private fun purgeBufferedDataCalls() {
            synchronized(pendingActions) {
                var index = 0
                var removedBeforeWipe = 0
                val iterator = pendingActions.iterator()
                while (iterator.hasNext()) {
                    val action = iterator.next()
                    if (action.data) {
                        if (index < preWipeActionCount) removedBeforeWipe++
                        iterator.remove()
                    }
                    index++
                }
                preWipeActionCount -= removedBeforeWipe
            }
        }

        /**
         * A pre-init unregisterPush() is waiting in the buffer: init must not
         * register the FCM token automatically first (contract v6 69). The
         * token fetched at init used to race it - registered on the server
         * while the replayed unregister found no stored token to remove, so the
         * user's "push off" was undone by an automatic token, not by the user.
         * An explicit registerPushToken() replayed after it still lifts it.
         */
        internal fun hasBufferedUnregisterPush(): Boolean =
            synchronized(pendingActions) { pendingActions.any { it.unregistersPush } }

        /**
         * How many buffered calls were made BEFORE a wipeData() made before
         * initialize(). The wipe drops the buffered calls that collect, so
         * these are the withdrawals of the identity being erased: they run
         * inside initialize() right before the wipe, under that identity, and
         * the outbox keeps them through it (contract v4 36; Unity scan 5, N4).
         * They used to replay after the wipe, under the new anonymous id - and
         * a track() buffered before the wipe survived it.
         */
        private var preWipeActionCount = 0

        /** The same bound on every SDK (shared SDK contract item 20); oldest dropped. */
        internal const val MAX_PENDING_ACTIONS = 1000

        // ── Consent decided before initialize() ──────────────────────────
        //
        // Applied by the constructor rather than replayed after it: a
        // replayed optOut() ran after init had started the in-app sync and
        // the push registration it was meant to prevent. Kept in memory AND
        // on disk (PreInitStore, scan 3 finding 11): a decision made in a
        // process that died before initialize() used to be lost, and the next
        // launch tracked a user who had said no.

        private class PreInitConsent(
            val optOut: Boolean?,
            val consent: TrackingConsent?,
            val wipe: Boolean,
            val authToken: String? = null,
            val authHandler: ((SdkAuthError) -> Unit)? = null,
        )

        private val preInitLock = Any()
        private var preInitOptOut: Boolean? = null
        private var preInitConsentValue: TrackingConsent? = null
        private var preInitWipe = false

        /**
         * The SDK-auth token and handler set before initialize(), for the
         * constructor (memory only: a credential is never written to the
         * plain pre-init file). The token only while no identify(), reset()
         * or wipeData() came before it ([preInitIdentityCalled]).
         */
        private var preInitAuthToken: String? = null
        private var preInitAuthHandler: ((SdkAuthError) -> Unit)? = null
        private var preInitIdentityCalled = false

        /** Set once a constructor has read the values; later calls go through the buffer. */
        private var preInitConsumed = false

        private fun takePreInitConsent(context: Context): PreInitConsent = synchronized(preInitLock) {
            if (PreInitStore.appContext == null) PreInitStore.appContext = context.applicationContext
            preInitConsumed = true
            // Memory first: it is this process's latest word. The disk copy
            // covers a process that died before initialize().
            val stored = PreInitStore.load()
            PreInitConsent(
                optOut = preInitOptOut ?: stored?.optOut,
                consent = preInitConsentValue ?: stored?.consent,
                wipe = preInitWipe || stored?.wipe == true,
                authToken = preInitAuthToken,
                authHandler = preInitAuthHandler,
            ).also {
                PreInitStore.clear()
                preInitOptOut = null
                preInitConsentValue = null
                preInitWipe = false
                preInitAuthToken = null
                preInitAuthHandler = null
                preInitIdentityCalled = false
            }
        }

        /** Record [apply] for the constructor; false when an SDK already exists. */
        private fun recordPreInit(apply: () -> Unit): Boolean = synchronized(preInitLock) {
            if (preInitConsumed || instance != null) return false
            // Merge with what an earlier process left on disk, so recording
            // one decision does not erase another.
            if (preInitOptOut == null && preInitConsentValue == null && !preInitWipe) {
                PreInitStore.load()?.let { stored ->
                    preInitOptOut = stored.optOut
                    preInitConsentValue = stored.consent
                    preInitWipe = stored.wipe
                }
            }
            apply()
            PreInitStore.save(preInitOptOut, preInitConsentValue, preInitWipe)
            true
        }

        /**
         * True once the buffer has been replayed. Until then every call is
         * buffered EVEN IF the instance already exists, so a call made while
         * the replay is running cannot overtake older buffered ones. Checking
         * `instance != null` alone was a race: a track() landing between the
         * instance being set and the replay ran first.
         */
        private var drained = false

        /**
         * Run [action] against the SDK as soon as it is ready.
         *
         * The public form of [withSdk], for callers that hold no instance and
         * must not care whether initialization has finished — the React Native
         * bridge above all, where a throw inside a @ReactMethod surfaces as a red
         * box in development and an unhandled JS error in production.
         *
         * Use this instead of getInstance() for anything fire-and-forget.
         * getInstance() still throws by design: a caller that genuinely needs the
         * instance now should hear about it.
         *
         * Not for the SDK-auth token and handler: use the static
         * [setSdkAuthenticationToken] / [setSdkAuthenticationErrorHandler].
         * Only those are taken by the constructor ahead of its first request
         * and kept through a wipeData() made before initialize() (contract v8
         * 84; scan 8, Android S1) - an action here is neither.
         */
        @JvmStatic
        fun whenReady(action: ((Joryio) -> Unit)?) {
            if (action == null) return
            withSdk(action = action)
        }

        private fun withSdk(
            withdrawal: Boolean = false,
            keepThroughWipe: Boolean = false,
            unregistersPush: Boolean = false,
            data: Boolean = false,
            action: (Joryio) -> Unit,
        ) {
            val sdk = synchronized(pendingActions) {
                val ready = instance
                if (ready == null || !drained) {
                    if (pendingActions.size >= MAX_PENDING_ACTIONS) {
                        val victim = pendingActions.firstOrNull { !it.withdrawal } ?: pendingActions.first()
                        if (pendingActions.indexOf(victim) < preWipeActionCount) preWipeActionCount--
                        pendingActions.remove(victim)
                        android.util.Log.w(
                            "Joryio",
                            "Dropping the oldest pre-initialization call: more than " +
                                "$MAX_PENDING_ACTIONS were made before initialize() completed.",
                        )
                    }
                    pendingActions.addLast(PendingAction(withdrawal, action, keepThroughWipe, unregistersPush, data))
                    return
                }
                ready
            }
            try {
                action(sdk)
            } catch (t: Throwable) {
                // Contract item 12: a public call never throws into host code.
                android.util.Log.w("Joryio", "SDK call failed: ${t.message}")
            }
        }

        /**
         * Replay everything buffered before readiness, in the order it was
         * called - on the MAIN thread, where the buffered calls would have run
         * had the SDK been ready (several drive Activity state). Calls arriving
         * during the replay are appended and replayed in turn; only an empty
         * buffer marks the SDK drained.
         */
        private fun drainPending(sdk: Joryio) {
            if (!Looper.getMainLooper().isCurrentThread) {
                mainHandler.post { drainPending(sdk) }
                return
            }
            while (true) {
                val batch = synchronized(pendingActions) {
                    if (pendingActions.isEmpty()) {
                        drained = true
                        return
                    }
                    val copy = pendingActions.toList()
                    pendingActions.clear()
                    copy
                }
                batch.forEach { action ->
                    try {
                        action.run(sdk)
                    } catch (t: Throwable) {
                        android.util.Log.w("Joryio", "A buffered SDK call failed: ${t.message}")
                    }
                }
            }
        }

        /** Run the calls made before a pre-init wipeData() (see [preWipeActionCount]). */
        private fun runActionsMadeBeforeTheWipe(sdk: Joryio) {
            val batch = synchronized(pendingActions) {
                val count = minOf(preWipeActionCount, pendingActions.size)
                preWipeActionCount = 0
                List(count) { pendingActions.removeFirst() }
            }
            batch.forEach { action ->
                try {
                    action.run(sdk)
                } catch (t: Throwable) {
                    android.util.Log.w("Joryio", "A buffered SDK call failed: ${t.message}")
                }
            }
        }

        /** Test seam: forget the singleton and the buffer. */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTesting() {
            synchronized(pendingActions) {
                pendingActions.clear()
                preWipeActionCount = 0
                drained = false
            }
            synchronized(preInitLock) {
                preInitConsumed = false
                preInitOptOut = null
                preInitConsentValue = null
                preInitWipe = false
                preInitAuthToken = null
                preInitAuthHandler = null
                preInitIdentityCalled = false
            }
            instance = null
            io.joryio.sdk.queue.JoryioDatabase.clearInstance()
        }

        /**
         * A data call (an event, an attribute write, an alias, a subscribe)
         * made before initialize() while the person is opted out or consent
         * is DENIED - decided in this process before init, or stored by an
         * earlier launch - is DROPPED now (contract v7 73; RN scan 7, S1). It
         * used to wait in the buffer and be judged against the state at
         * replay, so `optOut(); track("x"); optIn(); initialize()` sent x,
         * collected while the person had said no. Withdrawals, identity and
         * control calls are not data calls and are never dropped here.
         */
        private fun refusedBeforeInit(): Boolean = synchronized(preInitLock) {
            if (preInitConsumed || instance != null) return false
            val refused = isUserOptedOut() || getTrackingConsent() == TrackingConsent.DENIED
            if (refused) android.util.Log.d("Joryio", "Opted out or consent denied: a call made before initialize() is dropped")
            refused
        }

        /** [withSdk] for a data call: dropped while refused before init (see [refusedBeforeInit]). */
        private fun withData(action: (Joryio) -> Unit) {
            if (refusedBeforeInit()) return
            withSdk(data = true, action = action)
        }

        /** An identity call before initialize(): a later token is no longer taken early (see [preInitAuthToken]). */
        private fun notePreInitIdentityCall() {
            synchronized(preInitLock) {
                if (!preInitConsumed && instance == null) preInitIdentityCalled = true
            }
        }

        // MARK: - Convenience Methods

        fun track(eventName: String?, properties: Map<String, Any?>? = emptyMap()) {
            withData { it.track(eventName, properties) }
        }

        fun trackScreen(screenName: String?, properties: Map<String, Any?>? = emptyMap()) {
            withData { it.trackScreen(screenName, properties) }
        }

        fun identify(userId: String?) {
            notePreInitIdentityCall()
            withSdk { it.identify(userId) }
        }

        fun addAlias(label: String?, name: String?) {
            withData { it.addAlias(label, name) }
        }

        fun reset() {
            notePreInitIdentityCall()
            withSdk { it.reset() }
        }

        fun setAttribute(key: String?, value: Any?) {
            withData { it.setAttribute(key, value) }
        }

        fun setAttributes(attributes: Map<String, Any?>?) {
            withData { it.setAttributes(attributes) }
        }

        fun incrementAttribute(key: String?, by: Number? = 1) {
            withData { it.incrementAttribute(key, by) }
        }

        fun unsetAttribute(key: String?) {
            withData { it.unsetAttribute(key) }
        }

        fun flush() {
            withSdk { it.flush() }
        }

        // Marketing subscription - NOT optIn/optOut, which is tracking consent.
        fun setSubscription(channel: String?, status: String?) {
            if (status == "unsubscribed") {
                withSdk(withdrawal = true) { it.setSubscription(channel, status) }
            } else {
                withData { it.setSubscription(channel, status) }
            }
        }

        fun addToSubscriptionGroup(groupId: String?, channel: String?) {
            withData { it.addToSubscriptionGroup(groupId, channel) }
        }

        fun removeFromSubscriptionGroup(groupId: String?, channel: String?) {
            withSdk(withdrawal = true) { it.removeFromSubscriptionGroup(groupId, channel) }
        }

        /**
         * Turn push off for this device (see the instance method). Before
         * initialize() it is buffered as a withdrawal - kept ahead of every
         * other buffered call by the cap - and runs at init.
         */
        fun unregisterPush() {
            withSdk(withdrawal = true, unregistersPush = true) { it.unregisterPush() }
        }

        fun setSdkAuthenticationToken(token: String?) {
            // Before initialize(), also taken by the constructor ahead of its
            // first requests (see [preInitAuthToken]).
            synchronized(preInitLock) {
                if (!preInitConsumed && instance == null && !preInitIdentityCalled && !token.isNullOrBlank()) {
                    preInitAuthToken = token
                }
            }
            withSdk(keepThroughWipe = true) { it.setSdkAuthenticationToken(token) }
        }

        fun setSdkAuthenticationErrorHandler(handler: ((SdkAuthError) -> Unit)?) {
            synchronized(preInitLock) {
                if (!preInitConsumed && instance == null) preInitAuthHandler = handler
            }
            withSdk(keepThroughWipe = true) { it.setSdkAuthenticationErrorHandler(handler) }
        }
    }
}

