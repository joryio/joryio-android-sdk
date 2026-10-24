// REQUIRES: androidx.security:security-crypto:1.1.0-alpha06 (EncryptedSharedPreferences + MasterKey).
// Consuming apps / the published AAR MUST provide this dependency. See packages/sdk-android/README.md ("Dependencies").
package io.joryio.sdk.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Factory for the SDK's sensitive key-value stores.
 *
 * Returns an [EncryptedSharedPreferences] instance (AES256_GCM values / AES256_SIV keys,
 * backed by a [MasterKey] in the Android Keystore) so identity, session and the FCM token
 * are encrypted at rest — satisfying "encrypt all data at rest".
 *
 * Robustness guarantees (never crash the host app):
 *  - minSdk is 23, exactly the floor this scheme requires, so there is no "unencrypted on old
 *    Android" path any more. Encryption at rest is unconditional.
 *  - EncryptedSharedPreferences.create() can THROW on some devices with a corrupted keystore
 *    or after a keystore reset (e.g. lock-screen credential change, factory-key rotation). Any
 *    failure is caught and we fall back to plaintext [SharedPreferences] with a clear warning
 *    rather than crashing.
 *
 * The SharedPreferences interface returned is identical in both branches, so call sites do
 * NOT change — only the construction goes through here.
 */
internal object SecurePrefs {

    private const val ENC_SUFFIX = "_enc"
    private const val MIGRATION_FLAG = "__joryio_migrated_from_plaintext"

    /**
     * One instance per backing file, for the process lifetime.
     *
     * Without this, EVERY call rebuilt the MasterKey, re-created the
     * EncryptedSharedPreferences and re-ran the migration check — all Android
     * Keystore work, measured at ~163ms for the first build on a healthy
     * emulator. [PendingTokenStore] calls this on every token read AND write, so
     * that cost was paid repeatedly rather than once.
     *
     * SharedPreferences is documented as a singleton per file and is thread-safe,
     * so sharing one instance is also what the platform expects.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()

    /**
     * Returns the sensitive store for [plaintextFileName].
     *
     * When encryption is available, the encrypted store lives in a separate backing file
     * ("<name>$ENC_SUFFIX") because EncryptedSharedPreferences cannot read a plaintext file.
     * On first run with the new code, values from any pre-existing plaintext file are migrated
     * into the encrypted store and the plaintext file's cleartext contents are then removed.
     *
     * @param logger optional; PendingTokenStore has no Logger instance, so nulls are tolerated.
     */
    fun get(context: Context, plaintextFileName: String, logger: Logger? = null): SharedPreferences {
        // Cached per file: keystore setup is the single most expensive thing the
        // SDK does, and it must be paid at most once per process.
        cache[plaintextFileName]?.let { return it }
        synchronized(this) {
            cache[plaintextFileName]?.let { return it }
            val created = create(context, plaintextFileName, logger)
            cache[plaintextFileName] = created
            return created
        }
    }

    /** Pay the keystore cost now, off the caller's thread, so the first real use is free. */
    fun prewarm(context: Context, plaintextFileName: String, logger: Logger? = null) {
        if (cache.containsKey(plaintextFileName)) return
        Thread({ runCatching { get(context, plaintextFileName, logger) } }, "joryio-prefs-warm")
            .apply { isDaemon = true }
            .start()
    }

    private fun create(
        context: Context,
        plaintextFileName: String,
        logger: Logger?,
    ): SharedPreferences {
        val appContext = context.applicationContext

        // No API floor to check: minSdk is 23, EXACTLY the floor the keystore-backed
        // MasterKey scheme needs. The plaintext fallback that used to live here was
        // the only path on which identity, session and the FCM token were written
        // UNENCRYPTED; raising minSdk from 21 to 23 removed a weakened mode rather
        // than merely deleting dead code, and 23 is the lowest floor that does it.

        val health = KeystoreHealth(appContext.getSharedPreferences(HEALTH_FILE, Context.MODE_PRIVATE))
        val generation = health.keyGeneration()
        return openWithRecovery(
            open = {
                // Another store moved the SDK to a new key. This store's file
                // under the old key is READ into the new one when the old key
                // still opens it (scan 7, Android NICE 3: the move is global,
                // and a store that was readable must not lose its data because
                // another one gave up), and deleted only once the new store
                // is open.
                val previous = if (generation > 0) encryptedFileName(plaintextFileName, generation - 1) else null
                val seed = previous?.let { readPreviousGeneration(appContext, plaintextFileName, generation - 1) }
                openEncrypted(appContext, plaintextFileName, generation, logger, seed)
                    .also {
                        health.noteHealthy()
                        previous?.let { name -> deleteSharedPreferencesFile(appContext, name) }
                    }
            },
            discardEncrypted = { deleteSharedPreferencesFile(appContext, encryptedFileName(plaintextFileName, generation)) },
            // Every write of a fallback launch is logged, deletions and
            // clears included, so the merge at the next encrypted open can
            // apply them (contract v6 67).
            plaintextFallback = {
                LoggingPrefs(appContext.getSharedPreferences(plaintextFileName, Context.MODE_PRIVATE))
            },
            logger = logger,
            onPermanentlyUnusable = { confirmed ->
                recoverUnusableKey(
                    health = health,
                    file = plaintextFileName,
                    generation = generation,
                    logger = logger,
                    confirmed = confirmed,
                    discardOld = {
                        deleteSharedPreferencesFile(appContext, encryptedFileName(plaintextFileName, generation))
                        if (generation > 0) deleteKeystoreAlias(masterKeyAlias(generation))
                    },
                    openNew = { next -> openEncrypted(appContext, plaintextFileName, next, logger) },
                )
            },
        )
    }

    /**
     * The master key itself is unusable ("exists but is unusable": a
     * lock-screen change invalidated it, a vendor keystore lost it).
     * Retrying never helps, and every launch used to run on plaintext
     * forever with the encrypted store - and its undelivered withdrawals -
     * never read again (scan 6, Android NICE 2). The SDK then moves to a NEW
     * key of its own (never deleting the shared androidx key, which the host
     * app may use), once per confirmation, and the fallback's data moves
     * into a fresh encrypted file (a first migration). The encrypted store
     * under the old key is DISCARDED - whatever it still held, queued
     * withdrawals included, is lost - so the move is made only on evidence
     * (scan 7, Android NICE 3), because Tink reports ANY keystore error on an
     * existing key as "unusable", transient ones included:
     *
     * - [UNUSABLE_LAUNCHES_BEFORE_NEW_KEY] LAUNCHES in a row, counted once
     *   per process across every store (all of them share the key) - a
     *   process with several stores used to count three at once;
     * - no store opened under the key in this process (a store that opens
     *   proves the key works), and any healthy open resets the count;
     * - the cause says the key cannot decrypt ([confirmed]: invalidated,
     *   unrecoverable, a failed decryption), or the failures have lasted at
     *   least [MIN_UNUSABLE_SPAN_MS].
     *
     * Null: stay on the fallback this launch, store kept.
     */
    internal fun recoverUnusableKey(
        health: KeystoreHealth,
        file: String,
        generation: Int,
        logger: Logger?,
        discardOld: () -> Unit,
        openNew: (Int) -> SharedPreferences,
        confirmed: Boolean = false,
    ): SharedPreferences? {
        if (health.healthyThisLaunch()) return null
        if (health.noteUnusable() < UNUSABLE_LAUNCHES_BEFORE_NEW_KEY) return null
        if (!confirmed && health.unusableForMs() < MIN_UNUSABLE_SPAN_MS) return null
        val next = health.advanceKeyGeneration(generation)
        logger?.warn(
            "Keystore master key unusable on $UNUSABLE_LAUNCHES_BEFORE_NEW_KEY launches in a row ($file); " +
                "moving to a new key - the store under the old key is discarded",
        )
        discardOld()
        return openNew(next).also { health.noteHealthy() }
    }

    /**
     * [plaintextFileName]'s store under the PREVIOUS key [generation], when
     * that key still exists and opens it; null otherwise. Never creates a
     * key: a key the SDK deleted is not rebuilt under the old alias.
     */
    private fun readPreviousGeneration(appContext: Context, plaintextFileName: String, generation: Int): Map<String, *>? {
        val name = encryptedFileName(plaintextFileName, generation)
        if (!File(appContext.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()) return null
        val alias = if (generation == 0) MasterKey.DEFAULT_MASTER_KEY_ALIAS else masterKeyAlias(generation)
        return try {
            if (!java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)) return null
            val builder = if (generation == 0) MasterKey.Builder(appContext) else MasterKey.Builder(appContext, alias)
            EncryptedSharedPreferences.create(
                appContext,
                name,
                builder.setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).all
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * A NEW store (never opened: no [MIGRATION_FLAG]) starts with [seed],
     * the same store's values under the previous key, as if it had always
     * held them - so the plaintext fallback's log is then merged over them
     * like over any encrypted store, deletions included.
     */
    internal fun seedNewStore(store: SharedPreferences, seed: Map<String, *>?) {
        if (seed.isNullOrEmpty() || store.contains(MIGRATION_FLAG)) return
        val editor = store.edit()
        seed.forEach { (k, v) -> if (!isLogKey(k)) put(editor, k, v) }
        editor.putBoolean(MIGRATION_FLAG, true)
        editor.commit()
    }

    /** The encrypted store of [plaintextFileName] under master-key [generation]. */
    private fun openEncrypted(
        appContext: Context,
        plaintextFileName: String,
        generation: Int,
        logger: Logger?,
        seed: Map<String, *>? = null,
    ): SharedPreferences {
        val builder = if (generation == 0) {
            MasterKey.Builder(appContext)
        } else {
            MasterKey.Builder(appContext, masterKeyAlias(generation))
        }
        val masterKey = builder.setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext,
            encryptedFileName(plaintextFileName, generation),
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            seedNewStore(it, seed)
            migratePlaintextIfNeeded(appContext, plaintextFileName, it, logger)
        }
    }

    /** Generation 0 is the androidx default key and the original file name, unchanged for existing installs. */
    private fun encryptedFileName(plaintextFileName: String, generation: Int) =
        if (generation == 0) plaintextFileName + ENC_SUFFIX else "$plaintextFileName${ENC_SUFFIX}_k$generation"

    private fun masterKeyAlias(generation: Int) = "_joryio_sdk_master_key_$generation"

    /** Best effort: an SDK-owned key that is unusable anyway. */
    private fun deleteKeystoreAlias(alias: String) {
        try {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (t: Throwable) {
            // Unusable keys are harmless where they are.
        }
    }

    private const val HEALTH_FILE = "joryio_keystore_health"

    /** Consecutive launches the master key must fail as unusable before the SDK moves to a new key. */
    internal const val UNUSABLE_LAUNCHES_BEFORE_NEW_KEY = 3

    /** Without a cause that says the key cannot decrypt, the failures must have lasted this long. */
    internal const val MIN_UNUSABLE_SPAN_MS = 24L * 60 * 60 * 1000

    /** This process: one launch, however many stores it opens. */
    private val LAUNCH_ID: String = java.util.UUID.randomUUID().toString()

    /**
     * Which master key the SDK's stores use, and how many LAUNCHES in a row
     * found it unusable - one count per process, whichever store failed, as
     * every store uses the same key. A plain file: no secrets, only counts.
     */
    internal class KeystoreHealth(
        private val prefs: SharedPreferences,
        private val clock: () -> Long = System::currentTimeMillis,
        private val launchId: String = LAUNCH_ID,
    ) {
        fun keyGeneration(): Int = prefs.getInt(KEY_GENERATION, 0)

        /** One more launch on which the key was unusable (once per launch); returns the count. */
        // commit: written once per failing launch, and it must be on disk before the next.
        @android.annotation.SuppressLint("ApplySharedPref")
        @Synchronized
        fun noteUnusable(): Int {
            val counted = prefs.getInt(KEY_UNUSABLE_LAUNCHES, 0)
            if (prefs.getString(KEY_LAST_LAUNCH, null) == launchId) return counted
            val editor = prefs.edit()
                .putInt(KEY_UNUSABLE_LAUNCHES, counted + 1)
                .putString(KEY_LAST_LAUNCH, launchId)
            if (!prefs.contains(KEY_UNUSABLE_SINCE)) editor.putLong(KEY_UNUSABLE_SINCE, clock())
            editor.commit()
            return counted + 1
        }

        /** How long the key has been failing, from the first launch of the current run of failures. */
        fun unusableForMs(): Long = clock() - prefs.getLong(KEY_UNUSABLE_SINCE, clock())

        /** A store opened under the key in this launch: the key works. */
        fun healthyThisLaunch(): Boolean = synchronized(healthyLaunches) { launchId in healthyLaunches }

        fun noteHealthy() {
            synchronized(healthyLaunches) { healthyLaunches += launchId }
            if (prefs.contains(KEY_UNUSABLE_LAUNCHES) || prefs.contains(KEY_UNUSABLE_SINCE)) {
                prefs.edit()
                    .remove(KEY_UNUSABLE_LAUNCHES)
                    .remove(KEY_UNUSABLE_SINCE)
                    .remove(KEY_LAST_LAUNCH)
                    .apply()
            }
        }

        /** Move every store to the next key, once per confirmed failure: concurrent callers agree. */
        // commit: written once per failing launch, and it must be on disk before the next.
        @android.annotation.SuppressLint("ApplySharedPref")
        @Synchronized
        fun advanceKeyGeneration(from: Int): Int {
            val current = keyGeneration()
            if (current != from) return current
            prefs.edit()
                .putInt(KEY_GENERATION, current + 1)
                .remove(KEY_UNUSABLE_LAUNCHES)
                .remove(KEY_UNUSABLE_SINCE)
                .remove(KEY_LAST_LAUNCH)
                .commit()
            return current + 1
        }

        private companion object {
            const val KEY_GENERATION = "master_key_generation"
            const val KEY_UNUSABLE_LAUNCHES = "unusable_launches"
            const val KEY_UNUSABLE_SINCE = "unusable_since"
            const val KEY_LAST_LAUNCH = "unusable_last_launch"

            /** Launches (processes) in which a store opened under the key. */
            val healthyLaunches = HashSet<String>()
        }
    }

    /**
     * Open the encrypted store. Only a store that CANNOT BE DECRYPTED is
     * deleted and recreated; any other failure leaves it alone (scan 5,
     * Android S6).
     *
     * Undecryptable: a restore from Android backup brings the file back
     * without the Keystore master key that encrypted it (Keystore keys are
     * never backed up), and every open throws a decryption error. That file
     * holds nothing recoverable, so it is discarded and a fresh encrypted one
     * created - never a permanent fall back to plaintext.
     *
     * Anything else - a KeyStoreException early after boot or under load, a
     * provider hiccup on some OEMs - is TRANSIENT: tried again once, and if it
     * still fails this launch runs on the plaintext fallback WITHOUT deleting
     * anything. It used to delete the store on ANY first failure, losing the
     * anonymous id and userId (a split profile), the write outbox with its
     * undelivered unsubscribes, the pending push unregister and attributes.
     * What the fallback launch writes is merged back into the encrypted store
     * on the next launch that opens it (see [migratePlaintextIfNeeded]).
     */
    internal fun openWithRecovery(
        open: () -> SharedPreferences,
        discardEncrypted: () -> Unit,
        plaintextFallback: () -> SharedPreferences,
        logger: Logger?,
        retryDelayMs: Long = 150L,
        isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        /**
         * Both attempts said the master key is permanently unusable: a store
         * under a new key, or null to stay on the fallback this launch. The
         * argument: either cause says the key cannot decrypt
         * ([isUnusableKeyConfirmed]), not only that the keystore refused.
         */
        onPermanentlyUnusable: ((Boolean) -> SharedPreferences?)? = null,
    ): SharedPreferences {
        val first = try {
            return open()
        } catch (t: Throwable) {
            t
        }
        // Once more: a transient Keystore error usually clears in moments.
        // Never a sleep on the main thread (contract v6 67): a synchronous
        // initialize() from Application.onCreate() paid it before the first
        // frame, on every launch while the Keystore was down.
        try {
            if (retryDelayMs > 0 && !isMainThread()) sleep(retryDelayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val second = try {
            return open()
        } catch (t: Throwable) {
            t
        }
        // The KEY is unusable (Tink's "exists but is unusable"): never a
        // reason to delete the file, whatever the cause chain holds - a key
        // that fails its own decryption check carries an AEADBadTagException
        // too, and deleting the file then lost the data while the key stayed
        // broken (scan 7, Android NICE 3).
        val keyUnusable = isPermanentlyUnusable(first) && isPermanentlyUnusable(second)
        if (!keyUnusable && (isUndecryptable(first) || isUndecryptable(second))) {
            logger?.warn("Encrypted store cannot be decrypted (${second.message}); recreating it")
            try {
                discardEncrypted()
                return open()
            } catch (third: Throwable) {
                logger?.warn("EncryptedSharedPreferences unavailable, using plaintext for this launch: ${third.message}")
                return plaintextFallback()
            }
        }
        if (onPermanentlyUnusable != null && keyUnusable) {
            try {
                onPermanentlyUnusable(isUnusableKeyConfirmed(first) || isUnusableKeyConfirmed(second))?.let { return it }
            } catch (t: Throwable) {
                logger?.warn("A new Keystore master key could not be created: ${t.message}")
            }
        }
        // Corrupted keystore / unsupported OEM crypto provider / a transient
        // error that persisted. Do NOT crash, and do NOT delete.
        logger?.warn("EncryptedSharedPreferences unavailable (${second.message}); using plaintext for this launch, store kept")
        return plaintextFallback()
    }

    /**
     * True when [t] says the data cannot be decrypted with the key we have -
     * a wrong or missing master key - rather than that the Keystore could not
     * be reached. Tink reports the first as an AEAD tag mismatch or an
     * unreadable keyset, anywhere in the cause chain.
     */
    internal fun isUndecryptable(t: Throwable?): Boolean {
        var cause = t
        var depth = 0
        while (cause != null && depth++ < 10) {
            if (cause is javax.crypto.AEADBadTagException || cause is javax.crypto.BadPaddingException) return true
            val name = cause.javaClass.name
            if (name.endsWith("InvalidProtocolBufferException")) return true
            // Tink: "decryption failed", "could not decrypt", "invalid keyset".
            if (cause is java.security.GeneralSecurityException && cause !is java.security.KeyStoreException) {
                val m = cause.message?.lowercase() ?: ""
                if ("decrypt" in m || "invalid keyset" in m || "tag mismatch" in m) return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * True when [t] says the master key will never work again - Tink's
     * "the master key ... exists but is unusable", an invalidated or
     * unrecoverable Keystore key - rather than that the Keystore is busy.
     */
    internal fun isPermanentlyUnusable(t: Throwable?): Boolean {
        var cause = t
        var depth = 0
        while (cause != null && depth++ < 10) {
            val name = cause.javaClass.name
            if (name.endsWith("KeyPermanentlyInvalidatedException")) return true
            if (cause is java.security.UnrecoverableKeyException) return true
            if (cause is java.security.KeyStoreException && cause.message?.contains("unusable", ignoreCase = true) == true) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * True when the cause chain of an "unusable key" error says the key
     * cannot decrypt - invalidated (lock-screen change), unrecoverable, or a
     * decryption that failed - rather than a bare keystore refusal, which can
     * be transient (Tink wraps any keystore error on an existing key the same
     * way).
     */
    internal fun isUnusableKeyConfirmed(t: Throwable?): Boolean {
        var cause = t
        var depth = 0
        while (cause != null && depth++ < 10) {
            if (cause.javaClass.name.endsWith("KeyPermanentlyInvalidatedException")) return true
            if (cause is java.security.UnrecoverableKeyException) return true
            if (cause is javax.crypto.AEADBadTagException || cause is javax.crypto.BadPaddingException) return true
            cause = cause.cause
        }
        return false
    }

    private fun deleteSharedPreferencesFile(context: Context, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(name)
        } else {
            File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").delete()
        }
    }

    /**
     * Copy a plaintext prefs file into the encrypted store, then wipe the
     * plaintext copy so no cleartext stays on disk.
     *
     * Two cases. The first open of the encrypted store ([MIGRATION_FLAG] not
     * set yet): everything is copied, as before. Later opens: the plaintext
     * file only has content if a launch ran on the plaintext FALLBACK
     * (Keystore unavailable then) - its writes are merged in rather than left
     * stranded in cleartext (scan 5, Android S6). That launch's write log
     * ([LoggingPrefs]) says what it wrote AND what it removed: its last
     * write wins key by key, deletions and a clear() included (contract v6
     * 67), except where the owner's [Resolver] says otherwise. A file with no
     * log (written by an older build) keeps the old rule: keys the encrypted
     * store lacks are copied, keys it has keep the encrypted value except
     * where [Merger] combines both.
     *
     * Never returns early while the file holds a log: a wipeData() made on
     * the fallback leaves only its log behind, and an early return on an
     * "empty" file brought every erased value back (scan 6, Android S3).
     */
    private fun migratePlaintextIfNeeded(
        context: Context,
        plaintextFileName: String,
        encrypted: SharedPreferences,
        logger: Logger?
    ) {
        try {
            val old = context.getSharedPreferences(plaintextFileName, Context.MODE_PRIVATE)
            val migrated = encrypted.getBoolean(MIGRATION_FLAG, false)
            if (migrated && old.all.isEmpty()) return
            val copied = mergeInto(
                old,
                encrypted,
                firstMigration = !migrated,
                resolve = resolvers[plaintextFileName],
                merge = mergers[plaintextFileName],
                visit = visits[plaintextFileName] ?: emptySet(),
            )
            if (copied >= 0) {
                deletePlaintextFile(context, plaintextFileName)
                if (copied > 0) logger?.info("Moved $copied entries from plaintext to encrypted storage")
            }
        } catch (e: Exception) {
            // A failed migration must not crash init; the plaintext copy stays
            // for the next attempt.
            logger?.warn("Plaintext -> encrypted prefs migration failed: ${e.message}")
        }
    }

    /**
     * Combines a key present in BOTH files during a fallback merge: returns
     * the value to keep, or null to keep the encrypted one.
     */
    fun interface Merger {
        fun merge(key: String, encrypted: Any?, plaintext: Any?): Any?
    }

    /** Per-file mergers, registered by the store's owner before it is opened. */
    private val mergers = java.util.concurrent.ConcurrentHashMap<String, Merger>()
    private val resolvers = java.util.concurrent.ConcurrentHashMap<String, Resolver>()
    private val visits = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * @param visit keys the [resolver] is asked about even when neither file
     *   holds them - a key it may need to CREATE from other keys' values.
     */
    fun registerMerger(
        plaintextFileName: String,
        merger: Merger,
        resolver: Resolver? = null,
        visit: Set<String> = emptySet(),
    ) {
        mergers[plaintextFileName] = merger
        resolver?.let { resolvers[plaintextFileName] = it }
        if (visit.isNotEmpty()) visits[plaintextFileName] = visit
    }

    /** What a logged fallback merge does with one key of the encrypted store. */
    sealed class Decision {
        /** Leave the encrypted value as it is. */
        object Keep : Decision()

        /** Remove the key from the encrypted store. */
        object Remove : Decision()

        /** Write [value]. */
        class Put(val value: Any) : Decision()
    }

    /**
     * What a fallback launch did, read from its write log: the keys it
     * removed and did not write again, and whether it cleared the store
     * (wipeData()). The fallback launch started from an EMPTY file - it could
     * not read the encrypted store - so everything it did is newer than
     * everything the encrypted store holds.
     *
     * [everDeleted]: every key the launch removed at ANY point, also when it
     * wrote the key again afterwards (a logout followed by a login), which
     * [deleted] does not show (scan 7, Android S1). [fallback] and
     * [encrypted] are both files' values (log keys excluded), for a rule that
     * depends on more than one key - the user id decides what the anonymous
     * id and the pending attributes do.
     */
    class FallbackLog(
        val cleared: Boolean,
        val deleted: Set<String>,
        val everDeleted: Set<String> = deleted,
        val fallback: Map<String, *> = emptyMap<String, Any?>(),
        val encrypted: Map<String, *> = emptyMap<String, Any?>(),
    ) {
        companion object {
            internal fun from(raw: Map<String, *>, encrypted: Map<String, *> = emptyMap<String, Any?>()): FallbackLog? {
                if (raw.keys.none { isLogKey(it) }) return null
                val deleted = raw.keys.filter { it.startsWith(LOG_DELETED) }.map { it.removePrefix(LOG_DELETED) }.toSet()
                val ever = raw.keys.filter { it.startsWith(LOG_EVER_DELETED) }.map { it.removePrefix(LOG_EVER_DELETED) }.toSet()
                return FallbackLog(
                    cleared = raw.containsKey(LOG_CLEARED),
                    deleted = deleted,
                    everDeleted = deleted + ever,
                    fallback = raw.filterKeys { !isLogKey(it) && it != MIGRATION_FLAG },
                    encrypted = encrypted.filterKeys { it != MIGRATION_FLAG },
                )
            }
        }
    }

    /**
     * The owner's rule for one key in a logged merge: a [Decision], or null
     * for the default - the fallback's last write wins (its value, or a
     * removal when it deleted the key or cleared the store), and a key it
     * never touched keeps the encrypted value.
     */
    fun interface Resolver {
        fun resolve(key: String, encrypted: Any?, plaintext: Any?, log: FallbackLog): Decision?
    }

    internal fun defaultDecision(key: String, plaintext: Any?, log: FallbackLog): Decision = when {
        plaintext != null -> Decision.Put(plaintext)
        log.cleared || key in log.deleted -> Decision.Remove
        else -> Decision.Keep
    }

    private const val LOG_PREFIX = "__joryio_wlog_"
    private const val LOG_WRITTEN = "${LOG_PREFIX}w:"
    private const val LOG_DELETED = "${LOG_PREFIX}d:"
    private const val LOG_CLEARED = "${LOG_PREFIX}clear"

    /** A removal that a later write of the same key does NOT erase (see [FallbackLog.everDeleted]). */
    private const val LOG_EVER_DELETED = "${LOG_PREFIX}x:"

    private fun isLogKey(key: String) = key.startsWith(LOG_PREFIX)

    /**
     * The plaintext fallback, with a write log in the same file: each put
     * records when, each remove records the deletion, clear() records the
     * clear - in the same commit as the data, so data and log never
     * disagree. Readers never see the log keys.
     */
    internal class LoggingPrefs(private val delegate: SharedPreferences) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> =
            delegate.all.filterKeys { !isLogKey(it) }.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? = delegate.getString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            delegate.getStringSet(key, defValues)
        override fun getInt(key: String?, defValue: Int): Int = delegate.getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long): Long = delegate.getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = delegate.getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = delegate.getBoolean(key, defValue)
        override fun contains(key: String?): Boolean = delegate.contains(key)
        override fun edit(): SharedPreferences.Editor = LoggingEditor(delegate.edit())
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
            delegate.registerOnSharedPreferenceChangeListener(l)
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) =
            delegate.unregisterOnSharedPreferenceChangeListener(l)

        private class LoggingEditor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
            private fun wrote(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    e.putLong(LOG_WRITTEN + key, System.currentTimeMillis())
                    e.remove(LOG_DELETED + key)
                }
                return this
            }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (value == null) return remove(key)
                e.putString(key, value); return wrote(key)
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (values == null) return remove(key)
                e.putStringSet(key, values); return wrote(key)
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { e.putInt(key, value); return wrote(key) }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { e.putLong(key, value); return wrote(key) }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { e.putFloat(key, value); return wrote(key) }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { e.putBoolean(key, value); return wrote(key) }

            override fun remove(key: String?): SharedPreferences.Editor {
                e.remove(key)
                if (key != null) {
                    e.remove(LOG_WRITTEN + key)
                    val now = System.currentTimeMillis()
                    e.putLong(LOG_DELETED + key, now)
                    e.putLong(LOG_EVER_DELETED + key, now)
                }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                // Applied before this editor's puts, like any Editor; the
                // marker below is one of those puts, so it survives.
                e.clear()
                e.putLong(LOG_CLEARED, System.currentTimeMillis())
                return this
            }

            override fun commit(): Boolean = e.commit()
            override fun apply() = e.apply()
        }
    }

    /**
     * The merge itself, on two plain [SharedPreferences] so it can be tested
     * without a Keystore. Clears [old] when done. Returns how many keys were
     * written to [encrypted].
     */
    internal fun mergeInto(
        old: SharedPreferences,
        encrypted: SharedPreferences,
        firstMigration: Boolean,
        resolve: Resolver? = null,
        visit: Set<String> = emptySet(),
        merge: Merger?,
    ): Int {
        val raw = old.all
        val existing = encrypted.all
        val log = FallbackLog.from(raw, existing)
        val entries = raw.filterKeys { !isLogKey(it) && it != MIGRATION_FLAG }
        val editor = encrypted.edit()
        var copied = 0
        if (firstMigration || log == null) {
            for ((key, plainValue) in entries) {
                val value: Any? = if (!firstMigration && existing.containsKey(key)) {
                    merge?.merge(key, existing[key], plainValue) ?: continue
                } else {
                    plainValue
                }
                if (put(editor, key, value)) copied++
            }
        } else {
            val keys = LinkedHashSet<String>().apply {
                addAll(existing.keys)
                addAll(entries.keys)
                addAll(log.deleted)
                if (resolve != null) addAll(visit)
            }
            keys.remove(MIGRATION_FLAG)
            for (key in keys) {
                val enc = existing[key]
                val plain = entries[key]
                val decision = resolve?.resolve(key, enc, plain, log) ?: defaultDecision(key, plain, log)
                when (decision) {
                    is Decision.Keep -> {}
                    is Decision.Remove -> if (existing.containsKey(key)) {
                        editor.remove(key)
                        copied++
                    }
                    is Decision.Put -> if (decision.value != enc && put(editor, key, decision.value)) copied++
                }
            }
        }
        editor.putBoolean(MIGRATION_FLAG, true)
        // commit, not apply: the plaintext copy is deleted right after, and
        // it must not go before the encrypted copy is on disk.
        editor.commit()
        if (raw.isNotEmpty()) old.edit().clear().commit()
        return copied
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            else -> return false // null / unexpected types are skipped.
        }
        return true
    }

    /** Best-effort deletion of the backing plaintext .xml file. */
    private fun deletePlaintextFile(context: Context, plaintextFileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(plaintextFileName)
            } else {
                // API 23: deleteSharedPreferences arrived in 24. clear().apply()
                // above already removed the cleartext; drop the empty file too.
                val file = File(context.applicationInfo.dataDir, "shared_prefs/$plaintextFileName.xml")
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            // Non-fatal: cleartext is already cleared via clear().apply().
        }
    }
}
