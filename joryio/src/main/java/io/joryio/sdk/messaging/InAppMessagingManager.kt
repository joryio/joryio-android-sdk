package io.joryio.sdk.messaging

import io.joryio.sdk.core.IdentityManager
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.SessionManager
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.models.*
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.NetworkResult
import kotlinx.coroutines.*
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A generic click name says nothing about WHICH element was tapped, so it is
 * not sent as `elementId`; the same list as iOS and web.
 */
internal val GENERIC_ELEMENT_NAMES = setOf("click", "clicked", "button", "link", "cta_click", "link_click", "button_click")

/**
 * Manages in-app messaging campaigns
 */
internal class InAppMessagingManager(
    private val networkClient: NetworkClient,
    private val identityManager: IdentityManager,
    private val sessionManager: SessionManager,
    private val storage: StorageManager,
    private val logger: Logger,
    /**
     * Mirrors JoryioConfig.allowHtmlJsInAppMessages so the capabilities this
     * reports match what the renderer will actually do with a campaign. Passed
     * in rather than read from a global for the same reason the renderer takes
     * it: one source, no drift.
     */
    private val allowHtmlJsInAppMessages: Boolean = false,
) {
    /**
     * What the HOST can render, when the host renders instead of this SDK.
     *
     * The list below describes THIS SDK's own views. The moment a host sets an
     * in-app callback those views never run, so describing them is describing
     * the wrong renderer - and the answer matters, because the server targets
     * campaigns on it and its rollup resolves an absent capability to "no",
     * not to "unknown".
     *
     * A Unity game laying a message out on its own canvas can render structured
     * `content.native` and almost certainly cannot render `content.html` unless
     * it also hosts a WebView. Only the host knows, so only the host can say.
     *
     * Null means nobody has said, and the SDK keeps describing its own renderer
     * - which is exactly right while the SDK is the one drawing.
     */
    internal var hostCapabilities: List<String>? = null
    // Volatile: written by the sync on the main thread, read by track() on
    // whatever thread the host tracks from.
    @Volatile
    private var campaigns: List<InAppCampaign> = emptyList()
    private var lastSyncTime: Date? = null

    /**
     * Is a sync IN FLIGHT right now?
     *
     * Android had no sync guard of ANY kind - not an in-flight flag and not a
     * minimum interval - so every trigger (session start, foreground, push tap,
     * attribute change, a host calling syncInAppCampaigns) went straight to the
     * network, and two that overlapped both evaluated and both could display.
     *
     * The web SDK has always claimed `isSyncing` before its request. iOS
     * checked a `lastSyncTime` that is only written once the response arrives,
     * which is a check-then-act race across the whole round trip. Observed on
     * production 2026-08-31: paired /v1/in-app/sync requests 40ms apart on every
     * launch, and one queued test send counted as two displays.
     *
     * Atomic compare-and-set rather than a plain flag: `sync()` is called from
     * several coroutines and there is no lock around this class.
     */
    private val isSyncing = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Seconds between two DIFFERENT campaigns. Replaced by the server's value. */
    private var minDelayBetweenCampaignsSeconds: Int = 3
    private var lastDisplayAtMs: Long = 0

    /** Attributes a differential-sync campaign is waiting on. */
    private var watchedAttributes: Set<String> = emptySet()

    // Callback for displaying messages
    var onMessageReady: ((InAppCampaign) -> Unit)? = null

    /**
     * Whether anything may be sent. False while tracking consent is pending or
     * the user opted out: no sync (it carries identity and attributes) and no
     * impression reports. Wired by the facade; true when nothing says otherwise.
     */
    var canSend: () -> Boolean = { true }

    /**
     * A trigger arrived while a sync was in flight. That sync started BEFORE
     * the trigger, so its answer may predate whatever caused it (the push tap
     * that just opened the app, an attribute that just changed). One follow-up
     * sync runs when the current one finishes.
     */
    /**
     * Bumped by [invalidate] - reset(), wipeData(), identify(), a consent or
     * opt-out change. A sync or re-check answer from before it is about a
     * person or a decision that no longer holds, and is discarded (contract
     * item 26).
     */
    @Volatile
    private var generation = 0

    /**
     * Campaigns claimed for display: handed over, rendering, on screen, or
     * waiting out a display delay / a re-check (contract item 27). Claimed
     * SYNCHRONOUSLY, before any delay or network call, so a second trigger in
     * the same burst - two events tracked back to back, a sync landing while
     * a delayed message waits - finds the claim and does nothing. It used to
     * check the impression history, which is written only once a display is
     * reported, so a burst showed the same message twice.
     *
     * Released when the message is dismissed, when display was declined or
     * the re-check said no, and by [clear]. A claim older than
     * [CLAIM_TTL_MS] past its display time is stale (a host that never
     * reports a dismissal) and no longer blocks.
     */
    private val claims = HashMap<String, Long>()

    @Volatile
    private var syncAgainRequested = false

    /** A push tap waiting for the next completed sync to evaluate against. */
    @Volatile
    private var pushTapPending = false

    init {
        logger.debug("InAppMessagingManager initialized")
    }

    /**
     * Sync campaigns from backend
     */
    /**
     * Whatever is in flight is about a person or a decision that no longer
     * holds: discard its answer when it lands and sync again right after it.
     */
    fun invalidate() {
        generation++
        if (isSyncing.get()) syncAgainRequested = true
    }

    suspend fun sync() {
        if (!canSend()) {
            logger.debug("In-app sync skipped: consent pending or opted out")
            return
        }
        // Check AND claim in one atomic step. Reading a flag and then setting it
        // is the race this exists to close, so compareAndSet does both or
        // neither.
        if (!isSyncing.compareAndSet(false, true)) {
            // Not dropped: the in-flight request predates this trigger, so a
            // follow-up runs when it finishes (see syncAgainRequested). A push
            // tap used to land here and do nothing at all.
            syncAgainRequested = true
            logger.debug("A sync is already in flight; one follow-up sync queued")
            return
        }
        try {
            logger.debug("Syncing in-app campaigns")
            // reset()/wipeData() while this request is in flight: the answer
            // is about the person who just left and must not be shown to the
            // next one.
            val generationAtStart = generation

            val request = SessionSyncRequest(
                userId = identityManager.getUserId() ?: "",
                anonymousId = identityManager.getAnonymousId(),
                sessionId = sessionManager.getSessionId(),
                attributes = identityManager.getUserAttributes(),
                // Only what the server has not acknowledged. Everything else is
                // already in the stored profile, and the server should read its
                // own copy rather than trust a device's cache.
                pendingAttributes = identityManager.pending.pending(),
                // Native is unconditional - those messages are drawn by this
                // SDK's own views with no WebView involved. HTML appears only
                // when the integration opted in, because for everyone else it
                // genuinely cannot display.
                capabilities = hostCapabilities ?: if (allowHtmlJsInAppMessages) {
                    listOf("content.native", "content.html")
                } else {
                    listOf("content.native")
                }
            )

            when (val result = networkClient.syncInAppMessages(request)) {
                is NetworkResult.Success -> if (generation != generationAtStart || !canSend()) {
                    // Not returned from: the follow-up sync a reset() queued
                    // behind this one must still run.
                    logger.debug("Discarding a sync answer from before an identity or consent change")
                } else {
                    val response = result.data
                    // An unknown `type` from a newer server: Gson leaves the
                    // non-null field null, and the renderer's when(type) - or
                    // a host callback reading campaign.type - threw on it
                    // (scan 5, Android NICE 10). CUSTOM, as on iOS.
                    campaigns = response.campaigns.mapNotNull { withKnownType(it) }
                    // evaluationMode was decoded into a model field and never
                    // branched on, so a campaign set to differential-sync
                    // behaved as session-start-only on Android while working on
                    // web. Collect what those campaigns watch; setAttributes
                    // re-syncs when one of them changes.
                    watchedAttributes = response.campaigns
                        .filter { it.evaluationMode == "differential-sync" }
                        .flatMap { it.watchedAttributes ?: emptyList() }
                        .toSet()
                    // Minimum gap between DIFFERENT campaigns, in seconds. The
                    // model field existed and nothing read it - and the server
                    // only sent a hardcoded constant - so a workspace's
                    // configured gap reached nothing on either side.
                    response.delayBetweenCampaigns?.let {
                        if (it >= 0) minDelayBetweenCampaignsSeconds = it
                    }
                    lastSyncTime = Date()

                    logger.info("Synced ${campaigns.size} campaigns")

                    // Evaluate and display campaigns
                    evaluateAndDisplay()
                }
                is NetworkResult.Error -> {
                    logger.error("Failed to sync campaigns: ${result.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to sync campaigns: ${e.message}", e)
        } finally {
            // Released on EVERY exit, the exception path included. A sync that
            // fails must not wedge the flag on, or in-app messaging goes silent
            // for the rest of the process with no error to show for it.
            isSyncing.set(false)
        }

        if (syncAgainRequested) {
            syncAgainRequested = false
            sync()
            return
        }
        // Evaluated against the freshest answer available - after the
        // follow-up, if one was queued; held campaigns if the sync failed.
        if (pushTapPending) {
            pushTapPending = false
            evaluatePushTap()
        }
    }

    /**
     * Evaluate campaigns and display eligible ones
     */
    private fun evaluateAndDisplay() {
        if (campaigns.isEmpty()) {
            logger.debug("No campaigns to evaluate")
            return
        }

        // Sort by priority (higher first)
        val sortedCampaigns = campaigns.sortedByDescending { it.priority }

        for (campaign in sortedCampaigns) {
            if (displaysOnSync(campaign) && shouldDisplayCampaign(campaign)) {
                displayCampaign(campaign)
                // Only display one campaign at a time
                break
            }
        }
    }

    /**
     * Hand ONE campaign to the presenter, honouring its display delay.
     *
     * Extracted from evaluateAndDisplay so the sync path and the local
     * event-trigger path share it. Two copies would drift - the delay, the
     * lastDisplayAtMs stamp that powers the cross-campaign gap, and the
     * presenter handoff all have to behave identically however the display was
     * decided.
     *
     * Callers must have passed shouldDisplayCampaign() first; this does not
     * re-check.
     */
    /**
     * Fetch a re-check campaign's content, re-verifying eligibility server-side.
     *
     * Returns null when the user no longer qualifies OR the request fails.
     * Both mean SHOW NOTHING: a campaign in this mode is one the marketer said
     * must not display on stale information, so falling back to anything held
     * would defeat the setting they chose. That includes the offline case.
     */
    private suspend fun resolveForDisplay(campaign: InAppCampaign): InAppCampaign? {
        val generationAtStart = generation
        return try {
            val result = networkClient.resolveInAppCampaign(
                io.joryio.sdk.models.ResolveRequest(
                    userId = identityManager.getUserId() ?: "",
                    anonymousId = identityManager.getAnonymousId(),
                    sessionId = sessionManager.getSessionId(),
                    campaignId = campaign.id,
                    attributes = identityManager.getUserAttributes(),
                    pendingAttributes = identityManager.pending.pending(),
                ),
            )
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    if (generation != generationAtStart || !canSend()) {
                        logger.debug("Discarding a re-check answer from before an identity or consent change")
                        null
                    } else if (body.eligible && body.campaign != null) {
                        body.campaign
                    } else {
                        logger.info("Campaign ${campaign.id} not displayed: re-check said no")
                        null
                    }
                }
                is NetworkResult.Error -> {
                    logger.warn("Re-check failed for ${campaign.id}; not displaying")
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Re-check failed for ${campaign.id}; not displaying: ${e.message}")
            null
        }
    }

    /**
     * Claim [campaignId] for display; false when it is already claimed (one
     * display per trigger burst, contract item 27).
     */
    internal fun tryClaim(campaignId: String, displayDelayMs: Long = 0): Boolean = synchronized(claims) {
        val now = System.currentTimeMillis()
        val until = claims[campaignId]
        if (until != null && now < until) return false
        claims[campaignId] = now + displayDelayMs + CLAIM_TTL_MS
        true
    }

    /** The message is gone (dismissed, declined, refused by the re-check): it may display again. */
    internal fun releaseClaim(campaignId: String) {
        synchronized(claims) { claims.remove(campaignId) }
    }

    internal fun isClaimed(campaignId: String): Boolean = synchronized(claims) {
        val until = claims[campaignId] ?: return false
        System.currentTimeMillis() < until
    }

    private fun displayCampaign(campaign: InAppCampaign) {
        // Nothing is displayed while sending is not allowed (contract item
        // 43; scan 4, Android finding M3): campaigns synced while consent was
        // granted stay on the device, and an event tracked after a runtime
        // updateConsent(PENDING) - events still queue then - triggered one,
        // with no way to report it.
        if (!canSend()) {
            logger.debug("Campaign ${campaign.id} not displayed: consent not granted or opted out")
            return
        }
        // Claimed BEFORE the delay or the re-check, on this thread: the next
        // trigger of the same burst sees it.
        if (!tryClaim(campaign.id, (campaign.delay ?: 0) * 1000L)) {
            logger.debug("Campaign ${campaign.id} is already being displayed; trigger ignored")
            return
        }
        // Re-check mode: this campaign arrived WITHOUT content. Ask the
        // server, which re-verifies eligibility and renders now, then continue
        // with ITS version - freshly checked, freshly rendered.
        if (campaign.reevaluateBeforeDisplay == true) {
            val generationAtStart = generation
            scope.launch {
                val resolved = resolveForDisplay(campaign)
                if (resolved == null || generation != generationAtStart) {
                    releaseClaim(campaign.id)
                    return@launch
                }
                presentResolved(resolved)
            }
            return
        }
        presentResolved(campaign)
    }

    /**
     * [campaign] with a usable `type`: an unknown value (or none) becomes
     * CUSTOM. Set in place, the way Gson set it - copy() refuses the other
     * fields Gson may also have left null. Null (skipped) only if even that
     * fails. java.util.Objects.isNull: the compiler folds `== null` on a
     * non-null type away.
     */
    private fun withKnownType(campaign: InAppCampaign): InAppCampaign? {
        if (!java.util.Objects.isNull(campaign.type)) return campaign
        return try {
            InAppCampaign::class.java.getDeclaredField("type").apply { isAccessible = true }.set(campaign, MessageType.CUSTOM)
            logger.debug("Campaign ${campaign.id} has a message type this SDK does not know; treated as custom")
            campaign
        } catch (t: Throwable) {
            logger.warn("Campaign ${campaign.id} has an unknown message type; skipped")
            null
        }
    }

    /**
     * To the presenter / host callback, ON THE MAIN THREAD (scan 5, Android
     * NICE 7): an event-triggered campaign is evaluated on the thread the
     * host called track() from, and a host callback that touched its views
     * from a worker thread crashed. Posted, the claim taken above still holds.
     */
    private fun handOver(campaign: InAppCampaign) {
        val run = {
            try {
                // Nobody to hand it to: the claim is released.
                onMessageReady?.invoke(campaign) ?: releaseClaim(campaign.id)
            } catch (t: Throwable) {
                releaseClaim(campaign.id)
                logger.error("In-app display failed: ${t.message}", t)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { run() }
        }
    }

    /** Hand a campaign (already resolved, if it needed it) to the presenter. */
    private fun presentResolved(campaign: InAppCampaign) {
        logger.info("Displaying campaign: ${campaign.name}")

        val delaySeconds = campaign.delay ?: 0
        if (delaySeconds > 0) {
            // Display delay, in SECONDS. Applied AFTER every eligibility gate
            // so the wait cannot skip a check, and before the host is notified
            // so nothing is on screen during it.
            logger.debug("Campaign ${campaign.id} waits ${delaySeconds}s before display")
            val generationAtStart = generation
            // In this manager's scope, so clear() cancels it with the rest:
            // no delayed message outlives the person it was for.
            scope.launch {
                delay(delaySeconds * 1000L)
                if (generation != generationAtStart || !canSend()) {
                    releaseClaim(campaign.id)
                    return@launch
                }
                handOver(campaign)
            }
        } else {
            handOver(campaign)
        }
    }

    /**
     * Record that a message REALLY reached the screen (or was handed to a host
     * that renders it). Only this stamps the cross-campaign gap.
     *
     * It used to be stamped in [presentResolved], before anyone knew whether the
     * message could be shown. During a cold start there is no foreground Activity
     * yet, so the presenter dropped the message — and the gap was consumed
     * anyway. Observed on Android: "Displaying campaign: Late-cancel explainer" →
     * "No foreground Activity; not displaying" → "Too soon since the last in-app
     * message; skipping" for that campaign AND every other one. A message that
     * was never seen silently spent the budget for the ones that could have been.
     *
     * Leaving the stamp alone on a failed display also makes the next sync retry
     * it, which is why no explicit pending-queue is needed here.
     */
    internal fun noteDisplayed() {
        lastDisplayAtMs = System.currentTimeMillis()
    }

    /**
     * Check if campaign should be displayed
     */
    /**
     * Does this campaign display on SYNC, or is it waiting for something?
     *
     * `evaluateAndDisplay` used to show the first eligible campaign whatever
     * its triggers said, because no trigger type was evaluated at all. Now that
     * `event` triggers fire locally, that is actively wrong: an
     * event-triggered campaign would appear on sync AND again when the event
     * happens - the first one before the user did the thing it is about.
     *
     * Displays on sync: no triggers at all (the historical default), or an
     * `immediate` trigger. Anything waiting on an event, an attribute
     * threshold or a timer must NOT.
     *
     * Unknown trigger types wait rather than display. An older SDK meeting a
     * newer trigger type should stay silent, not fire at the wrong moment.
     */
    private fun displaysOnSync(campaign: InAppCampaign): Boolean {
        val triggers = campaign.triggers ?: emptyList()
        if (triggers.isEmpty()) return true
        return triggers.any { it["type"] == "immediate" }
    }

    private fun shouldDisplayCampaign(campaign: InAppCampaign): Boolean {
        // Already being displayed (handed over, delayed, on screen): this
        // trigger is part of the same burst (contract item 27).
        if (isClaimed(campaign.id)) {
            logger.debug("Campaign ${campaign.name} is already being displayed; skipping")
            return false
        }
        // Minimum gap since the last message of ANY campaign. Distinct from the
        // per-campaign frequency cap below: this one stops two different
        // messages landing back to back.
        val since = System.currentTimeMillis() - lastDisplayAtMs
        if (lastDisplayAtMs > 0 && since < minDelayBetweenCampaignsSeconds * 1000L) {
            logger.debug("Too soon since the last in-app message; skipping ${campaign.name}")
            return false
        }

        // SHOW ONCE unless the marketer asked for more.
        //
        // The product rule is that a campaign is shown to a user once, and
        // repeating it is something you turn ON - which is what a frequency cap
        // expresses. Android honoured the cap but had no default, so a campaign
        // with no cap set re-displayed on EVERY sync: every launch, every
        // foreground. iOS has always applied show-once-ever in exactly this
        // position (`frequencyCap != nil || !displayedCampaigns.contains(id)`),
        // so the same campaign behaved oppositely on the two platforms and the
        // marketer had no way to know which they were getting.
        //
        // Deliberately built on the impression history rather than on a second
        // "displayed" set. That history is already persisted, and it is written
        // in trackImpression - AFTER a message really reached the screen. iOS's
        // separate set is written when a display is CLAIMED, which is how one
        // failed render silently suppressed a campaign forever there
        // (fixed 2026-08-31). Reusing the record that means "this was actually
        // seen" cannot reproduce that.
        //
        // resetDisplayedCampaigns() clears this same history, so the testing
        // affordance keeps working unchanged.
        if (campaign.frequencyCap == null && storage.getCampaignImpressions(campaign.id).isNotEmpty()) {
            logger.debug("Campaign ${campaign.name} was already shown and sets no frequency cap; skipping")
            return false
        }

        // Check frequency cap
        if (!checkFrequencyCap(campaign)) {
            logger.debug("Campaign ${campaign.name} frequency cap exceeded")
            return false
        }

        // NO local targeting re-check. The server sent this campaign BECAUSE the
        // user matched its segments and filters; re-deciding that here can only
        // ever SUBTRACT, using data this device does not have.
        //
        // Both branches of the old check were broken in exactly that way:
        //
        //   segments  storage.getUserSegments() was always empty -
        //             setUserSegments() is called by nothing, and the backend
        //             never sent the user's segments. So EVERY segment-targeted
        //             campaign was rejected AFTER the server approved it: zero
        //             Android impressions, no error anywhere.
        //
        //   filters   evaluated against identityManager.getUserAttributes(),
        //             which is in-memory only and EMPTY on every cold start, so
        //             a filter-targeted campaign was suppressed until the host
        //             app happened to call setAttributes.
        //
        // The device evaluates only what the server cannot know - did the
        // trigger fire, has the delay elapsed, is the local cap reached. See
        // docs/CLIENT_SIDE_TRIGGERS_PLAN.md.

        return true
    }

    /**
     * Check frequency cap
     */
    private fun checkFrequencyCap(campaign: InAppCampaign): Boolean {
        val frequencyCap = campaign.frequencyCap ?: return true

        val impressions = storage.getCampaignImpressions(campaign.id)
        val now = Date()

        // Filter impressions within time window. null = ALL TIME
        // ('once' / 'lifetime' / 'session'), so every impression on record
        // counts - which is what makes the composer's default, "each user sees
        // this campaign one time only", mean once rather than once per day.
        val windowStart = getWindowStart(now, frequencyCap.timeWindow)
        val recentImpressions =
            if (windowStart == null) impressions else impressions.filter { it.after(windowStart) }

        // Check max impressions. null = UNLIMITED, so there is no ceiling to
        // reach - the window and the minimum delay below still apply.
        val maxImpressions = frequencyCap.maxImpressions
        if (maxImpressions != null && recentImpressions.size >= maxImpressions) {
            return false
        }

        // Check min delay between impressions
        val minDelay = frequencyCap.minDelayBetweenImpressions
        if (minDelay != null && impressions.isNotEmpty()) {
            val lastImpression = impressions.maxOrNull()!!
            val timeSinceLastImpression = now.time - lastImpression.time
            if (timeSinceLastImpression < minDelay * 1000) {
                return false
            }
        }

        return true
    }

    /**
     * Get window start time based on time window string
     */
    /**
     * Start of the frequency-cap window, or `null` for ALL TIME.
     *
     * Shared vocabulary - see the backend's common/in-app-frequency-window.ts.
     * Android knew only d/h/m, and read `m` as MINUTES while the dashboard and
     * every other layer meant MONTHS: a three-month cap was enforced as three
     * minutes, a 43,000x error in the direction of over-messaging, which is the
     * one direction a frequency cap exists to prevent.
     *
     * Two more it could not read, both falling through to "1 day":
     *   'lifetime'  the DEFAULT saved for "show once per user", so the product
     *               promise "one time only" meant "once per day, forever"
     *   '2w'        weeks had no branch at all - and the fallback discarded the
     *               NUMBER too, so any weeks window became one day
     *
     * `min` is minutes and `m` is months, tested longest-first: "30min" ends
     * with "m" as well.
     */
    private fun getWindowStart(now: Date, timeWindow: String): Date? {
        val w = timeWindow.trim().lowercase()

        when (w) {
            // 'session' is all-time here deliberately: this manager holds no
            // session boundary, and counting MORE impressions caps harder,
            // which is the safe direction for a control against over-messaging.
            "once", "lifetime", "session" -> return null
            "day" -> return Date(now.time - 24L * 60 * 60 * 1000)
            "week" -> return Date(now.time - 7L * 24 * 60 * 60 * 1000)
            "month" -> return Date(now.time - 30L * 24 * 60 * 60 * 1000)
        }

        // Longest suffix first, so "min" and "mo" are never read as "m".
        val units = listOf(
            "min" to 60L * 1000,
            "mo" to 30L * 24 * 60 * 60 * 1000,
            "h" to 60L * 60 * 1000,
            "d" to 24L * 60 * 60 * 1000,
            "w" to 7L * 24 * 60 * 60 * 1000,
            "m" to 30L * 24 * 60 * 60 * 1000,
        )
        for ((suffix, millis) in units) {
            if (!w.endsWith(suffix)) continue
            val value = w.dropLast(suffix.length).toLongOrNull() ?: break
            if (value <= 0) break
            return Date(now.time - value * millis)
        }

        // Unparseable. One day, matching the backend - safer than all-time
        // (which caps harder than asked) or zero (which does not cap).
        return Date(now.time - 24L * 60 * 60 * 1000)
    }

    /** ISO-8601 UTC, the format the web SDK sends and the backend canonicalises to. */
    private fun isoTimestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    /**
     * Report what happened to a message.
     *
     * @param action anything in the backend's vocabulary
     *   (common/events/in-app-action-vocabulary.ts): displayed, clicked,
     *   dismissed, converted and their accepted synonyms. Sent as the
     *   CANONICAL word.
     * @param elementId what the message called a click (a button id,
     *   data-action, logClick); sent separately as the wire field `elementId`.
     *
     * Two fixes here (shared SDK contract item 9):
     *  - Only a DISPLAY is recorded in the local impression history that
     *    frequency caps and show-once count. Every action used to be, so one
     *    message displayed, clicked and dismissed spent THREE impressions of a
     *    "show twice" cap and was never shown again.
     *  - The click's name used to REPLACE the action (`action: "cta"`), which
     *    the backend rejects as an unknown action - named clicks were not
     *    recorded as clicks.
     */
    suspend fun trackImpression(campaignId: String, action: String, elementId: String? = null) {
        try {
            val canonical = canonicalInAppAction(action)
            // Dismissed: the message is gone, the claim with it - whether or
            // not the report may be sent (contract item 43).
            if (canonical == "dismissed") releaseClaim(campaignId)
            if (!canSend()) {
                logger.debug("Impression not reported: consent pending or opted out")
                return
            }
            if (canonical == null) {
                logger.warn(
                    "Unknown in-app action '$action' not reported. Use displayed, clicked, dismissed or converted.",
                )
                return
            }
            logger.debug("Tracking impression: $campaignId - $canonical")

            if (canonical == "displayed") {
                storage.addCampaignImpression(campaignId, Date())
                // The cross-campaign gap is spent HERE, when a display is
                // reported - by the SDK's renderer or by a host that draws its
                // own (shared SDK contract item 17). It used to be stamped when
                // a message was handed to a host callback, so a message the host
                // declined to show still pushed every other campaign back.
                noteDisplayed()
            }

            // Echo the delivery token the server issued for this message - proof we
            // actually served it, and the key that makes each transition one-shot.
            // Looked up from the campaign rather than passed in, so every call
            // site (display, click, dismiss) carries it without having to
            // remember to.
            val deliveryToken = campaigns.firstOrNull { it.id == campaignId }?.deliveryToken

            // The SAME structured markers the web SDK sends; the backend gate
            // for in_app.displayed reads these.
            val nowIso = isoTimestamp()
            val request = TrackImpressionRequest(
                campaignId = campaignId,
                userId = identityManager.getUserId() ?: "",
                anonymousId = identityManager.getAnonymousId(),
                sessionId = sessionManager.getSessionId(),
                action = canonical,
                displayedAt = if (canonical == "displayed") nowIso else null,
                clicked = if (canonical == "clicked") true else null,
                clickedAt = if (canonical == "clicked") nowIso else null,
                dismissedAt = if (canonical == "dismissed") nowIso else null,
                converted = if (canonical == "converted") true else null,
                convertedAt = if (canonical == "converted") nowIso else null,
                deliveryToken = deliveryToken,
                // The backend accepts at most 128 characters.
                elementId = elementId?.trim()?.takeIf { canonical == "clicked" && it.isNotEmpty() && it.lowercase() !in GENERIC_ELEMENT_NAMES }?.take(128),
            )

            when (val result = networkClient.trackImpression(request)) {
                is NetworkResult.Success -> {
                    logger.debug("Impression tracked successfully")
                }
                is NetworkResult.Error -> {
                    logger.error("Failed to track impression: ${result.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to track impression: ${e.message}", e)
        }
    }

    /**
     * Get all campaigns
     */
    fun getCampaigns(): List<InAppCampaign> {
        return campaigns
    }

    /**
     * Get last sync time
     */
    fun getLastSyncTime(): Date? {
        return lastSyncTime
    }

    /**
     * Forget everything held for the current person: campaigns, the
     * differential-sync watch list, and any message waiting on a display delay
     * or a re-check. Called by reset() - a delayed message for the person who
     * just logged out must not appear in front of the next one.
     */
    fun clear() {
        invalidate()
        scope.coroutineContext.cancelChildren()
        synchronized(claims) { claims.clear() }
        campaigns = emptyList()
        lastSyncTime = null
        watchedAttributes = emptySet()
        pushTapPending = false
        logger.debug("Campaigns cleared")
    }


    /**
     * Re-run the display decision over the campaigns already held.
     *
     * No network: this is deliberately the same evaluation the sync path runs,
     * so a caller cannot get a second, subtly different set of rules.
     */
    fun evaluateNow() {
        evaluateAndDisplay()
    }

    /**
     * Forget which campaigns have already been shown, so they can display again.
     *
     * Testing affordance, mirroring iOS. Without it the only way to re-test a
     * campaign on Android was to uninstall the app, because frequency state is
     * held locally (that is what lets a trigger fire instantly and offline).
     * Clears the cross-campaign gap too, or the next message would still be
     * suppressed by it.
     */
    fun resetDisplayedCampaigns() {
        storage.clearCampaignImpressions()
        lastDisplayAtMs = 0
        logger.debug("Reset displayed campaigns")
    }

    /**
     * Shutdown manager
     */
    fun shutdown() {
        scope.cancel()
        logger.debug("InAppMessagingManager shutdown")
    }

    /**
     * Re-evaluate when a WATCHED attribute changes.
     *
     * Web polls on a timer; a background loop on a phone is a battery cost for
     * something the SDK is told about directly, so this is event-driven - the
     * host app setting an attribute is the event.
     *
     * No watched attribute, no sync: an app that sets attributes frequently
     * must not turn every one of them into a network round trip.
     */
    /**
     * An event just happened in the app - show anything waiting on it.
     *
     * The server sends five trigger types and `event` was evaluated by nobody:
     * a campaign set to "show when the user does add_to_cart" waited for the
     * event to upload and the next sync to decide, so it appeared on the next
     * launch rather than at the moment they did it. Everything needed was
     * already on the device.
     *
     * NO eligibility re-check - the server sent this campaign because the user
     * matched it. Re-deciding here can only subtract, using data the device
     * does not have, which is exactly how this class came to suppress every
     * segment-targeted campaign.
     */
    /**
     * The user just tapped a push - show anything waiting on that.
     *
     * `push_notification_tap` is a trigger type the campaign editor OFFERS
     * (InAppDeliveryStep.tsx), the backend stores and the sync response sends -
     * and no SDK acted on it. A marketer could build "tap the push, see the
     * coupon details", save it, and nothing would ever appear.
     *
     * Syncs FIRST, deliberately. Unlike an event trigger - where the campaign
     * is already on the device because the server pre-authorised it - a push
     * tap is a moment where the freshest eligibility matters, and the tap has
     * just brought the app to the foreground anyway, so the round trip costs
     * nothing the user notices.
     */
    suspend fun onPushTapped() {
        // Evaluated when the sync that STARTS after this tap completes - see
        // the end of sync(). A sync already in flight is followed by one more.
        pushTapPending = true
        sync()
        // Consent pending / opted out: sync() returned without running.
        if (!canSend()) pushTapPending = false
    }

    private fun evaluatePushTap() {
        val match = campaigns
            .sortedByDescending { it.priority }
            .firstOrNull { campaign ->
                (campaign.triggers ?: emptyList<Map<String, Any?>>())
                    .any { it["type"] == "push_notification_tap" }
            } ?: return

        if (shouldDisplayCampaign(match)) {
            logger.info("Push tap triggered campaign: ${match.name}")
            displayCampaign(match)
        }
    }

    fun onEventTracked(eventName: String, properties: Map<String, Any?>) {
        if (eventName.isEmpty()) return
        val match = campaigns
            .sortedByDescending { it.priority }
            .firstOrNull { campaign ->
                (campaign.triggers ?: emptyList<Map<String, Any?>>()).any { t ->
                    t["type"] == "event" &&
                        t["event"] == eventName &&
                        triggerFilterMatches(t, properties)
                }
            } ?: return

        // Reuse the ONE display routine, so caps, delay, the cross-campaign gap
        // and the presenter handoff all behave exactly as they do on a sync.
        // A second display path here is how the two would drift apart.
        if (shouldDisplayCampaign(match)) {
            logger.info("Event '$eventName' triggered campaign: ${match.name}")
            displayCampaign(match)
        }
    }

    /**
     * A trigger's own property condition. No condition matches on the event
     * name alone; an UNKNOWN operator does NOT match - failing closed, because
     * a typo that silently matched would look like it worked.
     */
    private fun triggerFilterMatches(trigger: Map<String, Any?>, properties: Map<String, Any?>): Boolean {
        val attribute = trigger["attribute"] as? String ?: return true
        val actual = properties[attribute]
        val expected = trigger["value"]
        fun num(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        return when (trigger["operator"] as? String) {
            null, "equals", "eq" -> actual?.toString() == expected?.toString()
            "not_equals", "neq" -> actual?.toString() != expected?.toString()
            "greater_than", "gt" -> num(actual) > num(expected)
            "less_than", "lt" -> num(actual) < num(expected)
            "contains" -> actual?.toString().orEmpty().contains(expected?.toString().orEmpty())
            "exists" -> actual != null
            else -> false
        }
    }

    fun attributesDidChange(keys: Collection<String>) {
        // FAST PATH: a campaign already on this device whose attribute_threshold
        // is now satisfied can display immediately, with no round trip.
        checkAttributeThresholds(keys)
        if (watchedAttributes.isEmpty()) return
        if (keys.none { watchedAttributes.contains(it) }) return

        scope.launch { sync() }
    }

    /**
     * Display a held campaign whose `attribute_threshold` is now met.
     *
     * A trigger type the editor offers and the server sends, evaluated by no
     * SDK: "show when loyaltyPoints passes 1000" only ever appeared on some
     * later sync, if at all.
     *
     * Evaluated against the CURRENT attribute values, not the changed keys
     * alone - a threshold is about where the value landed, not that it moved.
     */
    private fun checkAttributeThresholds(changedKeys: Collection<String>) {
        if (changedKeys.isEmpty()) return
        val attributes = identityManager.getUserAttributes()

        val match = campaigns
            .sortedByDescending { it.priority }
            .firstOrNull { campaign ->
                (campaign.triggers ?: emptyList<Map<String, Any?>>()).any { t ->
                    t["type"] == "attribute_threshold" &&
                        (t["attribute"] as? String)?.let { changedKeys.contains(it) } == true &&
                        triggerFilterMatches(t, attributes)
                }
            } ?: return

        if (shouldDisplayCampaign(match)) {
            logger.info("Attribute threshold triggered campaign: ${match.name}")
            displayCampaign(match)
        }
    }
}

/** How long a claim outlives its display time when nothing releases it. */
internal const val CLAIM_TTL_MS = 5 * 60 * 1000L

/**
 * The backend's in-app action vocabulary, mapped to its canonical word -
 * mirrors IN_APP_ACTIONS / IN_APP_CANONICAL_ACTIONS in
 * packages/backend/src/common/events/in-app-action-vocabulary.ts. Null for a
 * word the backend would reject, which the SDK says out loud rather than
 * sending.
 */
internal fun canonicalInAppAction(action: String?): String? = when (action?.trim()?.lowercase()) {
    "displayed", "display", "impression", "shown", "viewed" -> "displayed"
    "clicked", "click", "cta_click", "link_click", "button_click", "tapped" -> "clicked"
    "dismissed", "dismiss", "closed" -> "dismissed"
    "converted", "convert", "conversion" -> "converted"
    else -> null
}
