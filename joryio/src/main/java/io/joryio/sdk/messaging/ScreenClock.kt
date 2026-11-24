package io.joryio.sdk.messaging

/**
 * How long the person has been on the current screen - the clock behind the
 * `time_on_screen` in-app trigger (Joryio BACKLOG D444 follow-up; the app
 * equivalent of the web SDK's `time_on_page`). Wire contract:
 * packages/backend/src/common/in-app-screen-triggers.ts.
 *
 * - The screen is the one last reported with `trackScreen(name)` (a
 *   `Screen Viewed` event). A NEW name starts the clock over; the same name
 *   again (a view re-rendering) does not.
 * - Only foreground time counts: [pause] when the app goes to the background,
 *   [resume] when it returns - resumed, not restarted.
 * - Before the first `trackScreen` the screen is unnamed: a trigger without a
 *   `screenName` (any screen) still counts that time.
 * - Each campaign fires once per screen visit ([markFired]), as the web fires
 *   once per page.
 *
 * iOS mirrors this in ScreenClock.swift; `sdk-screen-triggers-parity.spec.ts`
 * pins both to the same wire values.
 */
internal class ScreenClock(
    private val now: () -> Long = { System.currentTimeMillis() },
    visible: Boolean = true,
) {
    private var currentName: String? = null
    private var accumulatedMs = 0L
    private var visibleSinceMs: Long? = if (visible) now() else null
    private val fired = mutableSetOf<String>()
    private val observers = ArrayList<(Change) -> Unit>()

    /** What moved the clock - for the placements' "Show when" (PlacementManager). */
    enum class Change { SCREEN, PAUSED, RESUMED }

    /** The current screen's name, null before the first trackScreen. */
    val name: String? @Synchronized get() = currentName

    val isVisible: Boolean @Synchronized get() = visibleSinceMs != null

    /** Foreground seconds on the current screen so far. */
    val seconds: Double
        @Synchronized get() = (accumulatedMs + (visibleSinceMs?.let { now() - it } ?: 0L)) / 1000.0

    /** The app reported a screen. True when it is a different screen (the clock restarted). */
    fun enter(screenName: String): Boolean {
        val next = screenName.trim()
        if (next.isEmpty()) return false
        val changed = synchronized(this) {
            if (currentName?.lowercase() == next.lowercase()) {
                false
            } else {
                currentName = next
                accumulatedMs = 0L
                if (visibleSinceMs != null) visibleSinceMs = now()
                fired.clear()
                true
            }
        }
        if (changed) notify(Change.SCREEN)
        return changed
    }

    fun pause() {
        val was = synchronized(this) {
            val since = visibleSinceMs
            if (since != null) accumulatedMs += now() - since
            visibleSinceMs = null
            since != null
        }
        if (was) notify(Change.PAUSED)
    }

    fun resume() {
        val started = synchronized(this) {
            if (visibleSinceMs == null) { visibleSinceMs = now(); true } else false
        }
        if (started) notify(Change.RESUMED)
    }

    /** Called after every new screen, pause and resume (outside the clock's lock). */
    fun observe(observer: (Change) -> Unit) {
        synchronized(this) { observers.add(observer) }
    }

    private fun notify(change: Change) {
        val all = synchronized(this) { observers.toList() }
        all.forEach { runCatching { it(change) } }
    }

    @Synchronized
    fun hasFired(campaignId: String): Boolean = fired.contains(campaignId)

    @Synchronized
    fun markFired(campaignId: String) {
        fired.add(campaignId)
    }

    companion object {
        /** The trigger type and the capability every app SDK sends on /sync. */
        const val TRIGGER_TYPE = "time_on_screen"
        const val CAPABILITY = "screen_context"
        /** Seconds a trigger may ask for (the backend refuses more at save). */
        const val MAX_SECONDS = 3600.0

        /**
         * Does the screen the app is on satisfy a trigger's `screenName`? No
         * name = any screen. Trimmed, case-insensitive equality - no patterns.
         * Mirrors `screenNameMatches` in the backend's in-app-screen-triggers.ts.
         */
        fun screenNameMatches(expected: String?, current: String?): Boolean {
            val want = expected?.trim().orEmpty()
            if (want.isEmpty()) return true
            return current != null && current.trim().lowercase() == want.lowercase()
        }

        /** A `time_on_screen` trigger's seconds, or null when it is not one or its value is unusable. */
        fun thresholdSeconds(trigger: Map<String, Any?>): Double? {
            if (trigger["type"] != TRIGGER_TYPE) return null
            val n = when (val v = trigger["value"]) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            } ?: return null
            return if (n.isFinite() && n > 0 && n <= MAX_SECONDS) n else null
        }

        /** The /sync capability list plus `screen_context`, once. */
        fun withCapability(capabilities: List<String>): List<String> =
            if (capabilities.contains(CAPABILITY)) capabilities else capabilities + CAPABILITY
    }
}
