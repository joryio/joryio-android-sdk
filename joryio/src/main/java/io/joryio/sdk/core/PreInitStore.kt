package io.joryio.sdk.core

import android.content.Context
import io.joryio.sdk.TrackingConsent

/**
 * Privacy decisions made BEFORE initialize() - optOut(), optIn(),
 * updateConsent(GRANTED / DENIED), wipeData() through the static methods -
 * kept on disk until a constructor applies them (scan 3, Android finding 11).
 *
 * They used to live in memory only: a user who tapped "opt out" on a consent
 * screen shown before the SDK started, in a process that then died before
 * initialize() ran, was tracked on the next launch. iOS persisted them.
 *
 * Not keyed by SDK key - none is known before init - and holds no identity:
 * three small flags, cleared the moment a constructor reads them. `pending`
 * is never written: it applies to one launch only (contract item 24).
 *
 * The Context comes from [JoryioContextInitializer], which androidx.startup
 * runs at process start. An app that disables the startup provider keeps the
 * in-memory behaviour (the decision still applies, it just does not survive
 * the process).
 */
internal object PreInitStore {
    @Volatile
    var appContext: Context? = null

    private const val FILE = "joryio_preinit"
    private const val KEY_OPT_OUT = "opt_out"
    private const val KEY_CONSENT = "consent"
    private const val KEY_WIPE = "wipe"

    class Decisions(val optOut: Boolean?, val consent: TrackingConsent?, val wipe: Boolean)

    private fun prefs() = try {
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        null
    }

    fun save(optOut: Boolean?, consent: TrackingConsent?, wipe: Boolean) {
        val p = prefs() ?: return
        try {
            val e = p.edit()
            if (optOut == null) e.remove(KEY_OPT_OUT) else e.putBoolean(KEY_OPT_OUT, optOut)
            when (consent) {
                TrackingConsent.GRANTED, TrackingConsent.DENIED -> e.putString(KEY_CONSENT, consent.name)
                // Not persisted (contract item 24); a stored decision it
                // replaced in memory is not one any more either.
                TrackingConsent.PENDING, null -> e.remove(KEY_CONSENT)
            }
            if (wipe) e.putBoolean(KEY_WIPE, true) else e.remove(KEY_WIPE)
            e.commit()
        } catch (t: Throwable) {
            android.util.Log.w("Joryio", "Could not store a decision made before initialize(): ${t.message}")
        }
    }

    fun load(): Decisions? {
        val p = prefs() ?: return null
        return try {
            if (p.all.isEmpty()) return null
            Decisions(
                optOut = if (p.contains(KEY_OPT_OUT)) p.getBoolean(KEY_OPT_OUT, false) else null,
                consent = p.getString(KEY_CONSENT, null)?.let { raw ->
                    TrackingConsent.values().firstOrNull { it.name == raw }
                },
                wipe = p.getBoolean(KEY_WIPE, false),
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun clear() {
        try {
            prefs()?.edit()?.clear()?.commit()
        } catch (t: Throwable) {
            // Nothing to undo; the next constructor reads and clears again.
        }
    }
}

/**
 * The two STORED privacy decisions - opted out, consent denied - in one plain
 * file that does not depend on the SDK key (scan 4, Android finding M4;
 * contract item 44).
 *
 * The real copies live in the per-key stores ([StorageManager]), which
 * cannot be opened before initialize() because the key is not known yet. So
 * `Joryio.isUserOptedOut()` and `Joryio.getTrackingConsent()` called before
 * init answered `false` / `null` for a user whose opt-out or denial was
 * stored by an earlier launch - and a bridge (Unity's GetTrackingConsent)
 * turned that into "granted". Written by [StorageManager] next to its own
 * copies; read only before initialize(). Two booleans, nothing identifying.
 */
internal object StoredDecisions {
    private const val FILE = "joryio_decisions"
    const val OPTED_OUT = "opted_out"
    const val CONSENT_DENIED = "consent_denied"

    private fun prefs(context: Context?) = try {
        context?.applicationContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        null
    }

    fun set(context: Context?, key: String, value: Boolean) {
        try {
            prefs(context)?.edit()?.putBoolean(key, value)?.apply()
        } catch (t: Throwable) {
            // A diagnostic copy; the per-key store is the one that counts.
        }
    }

    /** True / false when a decision is stored, null when none is (or no Context yet). */
    fun get(context: Context?, key: String): Boolean? = try {
        prefs(context)?.takeIf { it.contains(key) }?.getBoolean(key, false)
    } catch (t: Throwable) {
        null
    }
}

/**
 * Hands [PreInitStore] the application context at process start, so a
 * privacy decision made before initialize() can be written to disk, and
 * starts [ForegroundActivity]. Run by androidx.startup's InitializationProvider
 * (declared in this library's manifest).
 */
internal class JoryioContextInitializer : androidx.startup.Initializer<Unit> {
    override fun create(context: Context) {
        PreInitStore.appContext = context.applicationContext
        // Watch for the first Activity from process start, so an SDK built
        // after it resumed (initializeAsync) still knows it (scan 5, S3).
        (context.applicationContext as? android.app.Application)?.let { ForegroundActivity.install(it) }
    }

    override fun dependencies(): List<Class<out androidx.startup.Initializer<*>>> = emptyList()
}
