package io.joryio.sdk.core

import android.util.Log
import io.joryio.sdk.LogLevel

/**
 * Logger for SDK
 */
internal class Logger(
    private val enabled: Boolean,
    private val logLevel: LogLevel
) {
    companion object {
        private const val TAG = "Joryio"
        // internal, not private: Joryio.kt logs the version at startup and was
        // hardcoding its own copy, which said 1.0.0 while this said 1.1.0. The
        // version-parity tripwire cannot catch that, because a log string is not
        // one of the sites it pins. One constant, no second copy to drift.
        internal const val SDK_VERSION = "1.3.0"
    }

    fun verbose(message: String) {
        if (enabled && logLevel.ordinal <= LogLevel.VERBOSE.ordinal) {
            Log.v(TAG, message)
        }
    }

    fun debug(message: String) {
        if (enabled && logLevel.ordinal <= LogLevel.DEBUG.ordinal) {
            Log.d(TAG, message)
        }
    }

    fun info(message: String) {
        if (enabled && logLevel.ordinal <= LogLevel.INFO.ordinal) {
            Log.i(TAG, message)
        }
    }

    fun warn(message: String) {
        if (enabled && logLevel.ordinal <= LogLevel.WARN.ordinal) {
            Log.w(TAG, message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (enabled && logLevel.ordinal <= LogLevel.ERROR.ordinal) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }
}

/**
 * Log-safe form of an end-user identifier.
 *
 * Passes an ordinary id through UNCHANGED. Our docs tell customers not to use an
 * email as the userId - among other reasons, a guessable id lets someone forge
 * another user's data - so the normal case is an opaque id that is not PII, and
 * masking it would only make a debug log useless for tracing one user's problem.
 *
 * Masks ONLY when the value looks like an email, i.e. when a customer ignored
 * that guidance. Defence in depth for the non-compliant case, zero cost to the
 * compliant one.
 *
 * Note these logs are gated behind enableDebug, so a production build emits
 * nothing either way.
 */
internal fun maskIdentifier(value: String?): String {
    if (value.isNullOrEmpty()) return "(none)"
    val at = value.indexOf('@')
    // Not an email → not PII by our own contract → log it in full.
    if (at <= 0) return value

    val local = value.substring(0, at)
    val domain = value.substring(at + 1)
    val dot = domain.lastIndexOf('.')
    val tld = if (dot >= 0) domain.substring(dot) else ""
    return "${local.first()}***@${domain.firstOrNull() ?: '?'}***$tld"
}
