package io.joryio.sdk.ui

import android.graphics.Rect
import android.view.View
import io.joryio.sdk.messaging.IN_APP_DISPLAY_DWELL_MS
import io.joryio.sdk.placements.PLACEMENT_VIEWABLE_FRACTION

/**
 * The viewability rule of Joryio's inline views (PlacementView, CardFeedView):
 * at least [PLACEMENT_VIEWABLE_FRACTION] of a view on screen for
 * [IN_APP_DISPLAY_DWELL_MS] while the window has focus. One implementation, so
 * a placement and a card count a view the same way.
 */
internal object Viewability {
    /** Share of [v] on screen; a zero-size view (an empty spot) counts as 1 while it is on screen. */
    fun fraction(v: View): Double {
        if (!v.isShown || !v.isAttachedToWindow) return 0.0
        val r = Rect()
        if (v.width == 0 || v.height == 0) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val frame = Rect()
            v.getWindowVisibleDisplayFrame(frame)
            return if (frame.contains(loc[0], loc[1]) || frame.contains(loc[0] + 1, loc[1])) 1.0 else 0.0
        }
        if (!v.getGlobalVisibleRect(r)) return 0.0
        return (r.width().toDouble() * r.height()) / (v.width.toDouble() * v.height)
    }
}

/**
 * Dwell timers for the targets inside [host]: each target's callback runs once,
 * after it has been viewable ([Viewability.fraction] >= PLACEMENT_VIEWABLE_FRACTION,
 * window focused) for IN_APP_DISPLAY_DWELL_MS without a break. The host calls
 * [check] on scroll / layout / focus changes and [cancel] when it detaches.
 */
internal class ViewabilityTracker(private val host: View) {
    private class Tracker(val target: View, val onViewable: () -> Unit) {
        var timer: Runnable? = null
        var done = false
    }

    private val trackers = ArrayList<Tracker>()

    fun add(target: View, onViewable: () -> Unit) {
        trackers.add(Tracker(target, onViewable))
    }

    fun cancel() {
        trackers.forEach { t -> t.timer?.let { host.removeCallbacks(it) } }
        trackers.clear()
    }

    fun check() {
        val focused = host.hasWindowFocus()
        for (t in trackers.toList()) {
            if (t.done) continue
            val viewable = focused && Viewability.fraction(t.target) >= PLACEMENT_VIEWABLE_FRACTION
            if (viewable && t.timer == null) {
                val r = Runnable {
                    t.timer = null
                    if (!t.done && host.hasWindowFocus() && Viewability.fraction(t.target) >= PLACEMENT_VIEWABLE_FRACTION && t in trackers) {
                        t.done = true
                        t.onViewable()
                    }
                }
                t.timer = r
                host.postDelayed(r, IN_APP_DISPLAY_DWELL_MS)
            } else if (!viewable) {
                t.timer?.let { host.removeCallbacks(it) }
                t.timer = null
            }
        }
    }
}
