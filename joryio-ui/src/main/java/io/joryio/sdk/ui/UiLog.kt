package io.joryio.sdk.ui

import android.util.Log

/**
 * Logging for the rendering artifact.
 *
 * Deliberately NOT the base SDK's `Logger`, which is `internal` and therefore
 * invisible across the module boundary. Making it public to share it would
 * promote an implementation detail into base's permanent public API - a cost
 * that outlives whatever convenience it buys.
 *
 * So the boundary stands and this module logs for itself. `debugEnabled` is
 * passed down from the host's JoryioConfig at construction, so verbosity still
 * follows the one setting an integrator sets.
 */
internal object UiLog {
    private const val TAG = "JoryioUI"

    var debugEnabled: Boolean = false

    fun debug(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }
}
