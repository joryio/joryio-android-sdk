package io.joryio.sdk.messaging

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * How long, in milliseconds, an in-app message must stay on screen before it
 * counts as displayed: only then is `displayed` reported (`in_app.displayed`)
 * and the local frequency cap spent.
 *
 * The same name and value on all five SDKs. Production evidence (2026-11-02/03,
 * web): a modal was served and reported displayed in the same second the page
 * was navigating away - a sign-in redirect, a logout. The person saw at most a
 * flash, both "displays" counted, the cap (2 per 7 days) was used up and the
 * message never appeared again. A screen that closes within the dwell is a
 * screen change, not a view.
 *
 * A click or a dismissal before the dwell counts: the person evidently saw the
 * message, so `displayed` is reported first and then the interaction.
 */
const val IN_APP_DISPLAY_DWELL_MS: Long = 1_000L

/**
 * The messages the SDK drew that have not yet counted as displayed.
 *
 * Main thread only: presenter events, Activity lifecycle callbacks and the
 * dwell timer all run there. Each [Presentation] ends exactly once, either
 * CONFIRMED (seen for the dwell, or interacted with) or ABANDONED (its screen
 * closed, it was removed, or the person changed before the dwell).
 */
internal class InAppDisplayDwell(
    private val dwellMs: Long = IN_APP_DISPLAY_DWELL_MS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    enum class State { WAITING, CONFIRMED, ABANDONED }

    inner class Presentation internal constructor(
        val campaignId: String,
        activity: Activity,
        /** The dwell elapsed with the message on screen: report `displayed`. */
        internal val onConfirm: () -> Unit,
        /** Ended unseen. `screenGone`: its Activity finished or was destroyed. */
        internal val onAbandon: (screenGone: Boolean) -> Unit,
    ) {
        private val activityRef = WeakReference(activity)
        val activity: Activity? get() = activityRef.get()

        var state: State = State.WAITING
            internal set

        /** The presenter said the message is on screen; the dwell may run. */
        internal var shown = false
        internal var timer: Runnable? = null
    }

    private val waiting = LinkedHashSet<Presentation>()

    fun begin(
        campaignId: String,
        activity: Activity,
        onConfirm: () -> Unit,
        onAbandon: (screenGone: Boolean) -> Unit,
    ): Presentation = Presentation(campaignId, activity, onConfirm, onAbandon).also { waiting.add(it) }

    /** The presenter drew [p]. The dwell starts now if its screen is in the foreground, else on its next resume. */
    fun shown(p: Presentation, foreground: Boolean) {
        if (p.state != State.WAITING || p.shown) return
        p.shown = true
        if (foreground) schedule(p)
    }

    /**
     * The person interacted with [p] before the dwell: it was seen. True when
     * this call confirmed it - the caller then reports `displayed` before the
     * interaction itself. False when it was already confirmed or abandoned.
     */
    fun confirmNow(p: Presentation): Boolean {
        if (p.state != State.WAITING) return false
        cancel(p)
        p.state = State.CONFIRMED
        waiting.remove(p)
        return true
    }

    /** End [p] unseen. True when this call abandoned it. */
    fun abandon(p: Presentation, screenGone: Boolean): Boolean {
        if (p.state != State.WAITING) return false
        cancel(p)
        p.state = State.ABANDONED
        waiting.remove(p)
        p.onAbandon(screenGone)
        return true
    }

    /** The screen left the foreground: the dwell stops (and restarts in full on resume). */
    fun activityPaused(activity: Activity) {
        waiting.filter { it.activity === activity }.forEach { cancel(it) }
        if (activity.isFinishing) activityGone(activity)
    }

    fun activityResumed(activity: Activity) {
        waiting.filter { it.activity === activity && it.shown && it.timer == null }.forEach { schedule(it) }
    }

    /** The screen is finishing or destroyed: whatever it showed unseen is abandoned. */
    fun activityGone(activity: Activity) {
        waiting.filter { it.activity === activity || it.activity == null }.forEach { abandon(it, screenGone = true) }
    }

    /** The person or the permission to send changed: nothing pending may count any more. */
    fun abandonAll() {
        waiting.toList().forEach { abandon(it, screenGone = false) }
    }

    fun pendingIds(): List<String> = waiting.map { it.campaignId }

    private fun schedule(p: Presentation) {
        cancel(p)
        val tick = Runnable {
            p.timer = null
            if (p.state != State.WAITING) return@Runnable
            val activity = p.activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                abandon(p, screenGone = true)
                return@Runnable
            }
            p.state = State.CONFIRMED
            waiting.remove(p)
            p.onConfirm()
        }
        p.timer = tick
        handler.postDelayed(tick, dwellMs)
    }

    private fun cancel(p: Presentation) {
        p.timer?.let { handler.removeCallbacks(it) }
        p.timer = null
    }
}
