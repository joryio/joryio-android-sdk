package io.joryio.sdk.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.joryio.sdk.models.*
import java.util.*

/**
 * Manages local storage for user data and SDK state
 */
internal class StorageManager(
    context: Context,
    private val sdkKey: String,
    private val logger: Logger
) {
    // Encrypted at rest via EncryptedSharedPreferences (see SecurePrefs); this file holds the
    // sensitive identity KV (anonymousId, userId, session) and the FCM device token. Falls back
    // to plaintext SharedPreferences only on API < 23 or a keystore failure — never crashes.
    private val prefs: SharedPreferences = run {
        val file = "joryio_${sdkKey.hashCode()}"
        // A launch that ran on the plaintext fallback (Keystore unavailable)
        // queued writes there; they are merged into the encrypted store when
        // it opens again, never left stranded in cleartext (scan 5, S6).
        SecurePrefs.registerMerger(
            file,
            SecurePrefs.Merger { key, encrypted, plaintext -> mergeFallbackValue(key, encrypted, plaintext) },
            SecurePrefs.Resolver { key, encrypted, plaintext, log -> resolveFallback(key, encrypted, plaintext, log) },
            // The outbox can be CREATED by the merge: the outgoing user's
            // unsent attributes, sealed under their ids (see resolveFallback).
            visit = setOf(KEY_OUTBOX),
        )
        SecurePrefs.get(context, file, logger)
    }
    // WireJson, not Gson(): Gson's default Date format is the device LOCALE's
    // ("Oct 6, 2026 9:15:00 AM"), so a stored session stopped parsing the day
    // the user changed their phone's language.
    private val gson: Gson = io.joryio.sdk.network.WireJson.gson

    /**
     * A second, PLAIN copy of the opt-out flag - one boolean, nothing
     * identifying.
     *
     * The encrypted store can be lost: a keystore that cannot be opened falls
     * back to a plaintext file for the launch, and an undecryptable file
     * (restore from backup) is discarded and recreated. Either way it comes
     * back EMPTY, and an opt-out held only there read as "not opted out" -
     * the SDK resumed tracking a user who had withdrawn consent. The mirror
     * survives both, and [getOptedOut] honours either copy.
     */
    private val consentMirror: SharedPreferences =
        context.applicationContext.getSharedPreferences("joryio_consent_${sdkKey.hashCode()}", Context.MODE_PRIVATE)

    /** For the key-independent copy read before initialize() ([StoredDecisions]). */
    private val appContext: Context = context.applicationContext

    init {
        // An install upgraded from a build without the mirror: copy an
        // existing opt-out across now, while the encrypted store still opens.
        if (prefs.getBoolean(KEY_OPTED_OUT, false) && !consentMirror.contains(KEY_OPTED_OUT)) {
            consentMirror.edit().putBoolean(KEY_OPTED_OUT, true).apply()
        }
        // And to the copy the pre-init getters read, which older builds
        // never wrote.
        StoredDecisions.set(appContext, StoredDecisions.OPTED_OUT, getOptedOut())
        StoredDecisions.set(appContext, StoredDecisions.CONSENT_DENIED, getConsentDenied())
    }

    companion object {
        /**
         * A key in both the encrypted store and a plaintext-fallback file:
         * outbox entries and retired tokens from both are kept (the encrypted
         * store's first); for anything else the encrypted value stays (null).
         */
        internal fun mergeFallbackValue(key: String, encrypted: Any?, plaintext: Any?): Any? {
            val enc = encrypted as? String ?: return null
            val plain = plaintext as? String ?: return null
            return try {
                when (key) {
                    KEY_OUTBOX -> {
                        val a = com.google.gson.JsonParser.parseString(enc).asJsonArray
                        val seen = a.mapNotNull { it.asJsonObject.get("id")?.asString }.toHashSet()
                        com.google.gson.JsonParser.parseString(plain).asJsonArray.forEach { e ->
                            val id = e.asJsonObject.get("id")?.asString
                            if (id == null || seen.add(id)) a.add(e)
                        }
                        a.toString()
                    }
                    KEY_RETIRED_AUTH_TOKENS -> {
                        val a = com.google.gson.JsonParser.parseString(enc).asJsonObject
                        com.google.gson.JsonParser.parseString(plain).asJsonObject.entrySet().forEach { (k, v) ->
                            if (!a.has(k)) a.add(k, v)
                        }
                        a.toString()
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Retired tokens, entry by entry: one malformed entry is skipped and
         * never loses the others (contract v6 61). The map was decoded in one
         * go and a Kotlin non-null check on a field Gson had left null threw,
         * so one bad entry emptied it - every logged-out user's leftovers then
         * went out without their token and were dropped.
         */
        internal fun decodeRetiredTokens(json: String): Map<String, io.joryio.sdk.network.SdkAuthenticator.Retired> {
            val obj = try {
                com.google.gson.JsonParser.parseString(json) as? com.google.gson.JsonObject
            } catch (e: Exception) {
                null
            } ?: return emptyMap()
            val out = LinkedHashMap<String, io.joryio.sdk.network.SdkAuthenticator.Retired>()
            for ((user, value) in obj.entrySet()) {
                try {
                    val entry = value as? com.google.gson.JsonObject ?: continue
                    val token = entry.get("token")?.takeIf { it.isJsonPrimitive }?.asString
                    if (user.isEmpty() || token.isNullOrEmpty()) continue
                    val at = entry.get("retiredAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                        ?: System.currentTimeMillis() // unknown age: the 30-day cap counts from now
                    val standby = entry.get("standby")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
                    out[user] = io.joryio.sdk.network.SdkAuthenticator.Retired(token, at, standby)
                } catch (e: Exception) {
                    // This entry only.
                }
            }
            return out
        }

        /**
         * One key of a logged fallback merge (contract v6 67; scan 6, Android
         * S3). The fallback launch's last write wins - a reset() (logout),
         * a wipeData(), an optIn() or a grant made on it survive the merge -
         * with these exceptions, because that launch started from an EMPTY
         * store and never saw the encrypted values:
         *
         * - the anonymous id it generated for want of the stored one does not
         *   replace it, unless it logged the user out (user_id removed) or
         *   wiped; the device id likewise, unless it wiped;
         * - queues are merged, never replaced by what that launch knew: the
         *   write outbox (after a wipe, only the encrypted store's
         *   withdrawals stay), retired SDK-auth tokens, a pending push
         *   unregister (its own wins), pending attribute writes (dropped on a
         *   logout or a wipe, like the live ones) and impression history.
         *
         * A USER SWITCH on the fallback launch (contract v7 76; scan 7,
         * Android S1) is any change of user id, not only a final deletion: a
         * logout followed by a login leaves a write as the last op on
         * `user_id`, and an identify() of another user never removes it. On a
         * switch nothing of the encrypted store's person is handed to the
         * fallback's: its anonymous id and held user id are not kept, and its
         * unsent attributes are never re-addressed - dropped after a logout or
         * wipe (as reset() drops them live), else sealed into the outbox under
         * THEIR ids (as identify() of another user seals them live, contract
         * item 53). The pending set is a bare key-to-value map, sent with
         * whatever ids are current at flush, so keeping it put user A's email
         * on user B's profile.
         */
        internal fun resolveFallback(
            key: String,
            encrypted: Any?,
            plaintext: Any?,
            log: SecurePrefs.FallbackLog,
        ): SecurePrefs.Decision? {
            val loggedOut = fallbackLoggedOut(log)
            val switched = fallbackSwitchedUser(log)
            if (key == KEY_OUTBOX) return resolveOutbox(encrypted as? String, plaintext as? String, log)
            return when {
                encrypted == null -> null
                // Never the encrypted person's: the fallback's own, or none
                // (a fresh one is generated at the next read).
                key == KEY_ANONYMOUS_ID || key == KEY_HELD_USER_ID -> when {
                    !switched -> if (key == KEY_ANONYMOUS_ID) SecurePrefs.Decision.Keep else null
                    plaintext != null -> SecurePrefs.Decision.Put(plaintext)
                    else -> SecurePrefs.Decision.Remove
                }
                key == KEY_DEVICE_ID -> if (log.cleared) null else SecurePrefs.Decision.Keep
                key == KEY_RETIRED_AUTH_TOKENS ->
                    if (plaintext == null) SecurePrefs.Decision.Keep
                    else mergeFallbackValue(key, encrypted, plaintext)?.let { SecurePrefs.Decision.Put(it) }
                key == KEY_PENDING_UNREGISTER ->
                    if (plaintext != null) SecurePrefs.Decision.Put(plaintext) else SecurePrefs.Decision.Keep
                key == KEY_PENDING_ATTRIBUTES -> when {
                    // Dropped (logout, wipe) or sealed into the outbox under
                    // their owner's ids (resolveOutbox): only the fallback's
                    // own unsent attributes stay pending.
                    loggedOut || switched -> plaintext?.let { SecurePrefs.Decision.Put(it) } ?: SecurePrefs.Decision.Remove
                    plaintext == null -> SecurePrefs.Decision.Keep
                    else -> mergeJsonObjects(encrypted as? String, plaintext as? String)?.let { SecurePrefs.Decision.Put(it) }
                }
                key.startsWith(KEY_CAMPAIGN_IMPRESSIONS) -> when {
                    log.cleared -> null
                    plaintext == null -> SecurePrefs.Decision.Keep
                    else -> mergeImpressions(encrypted as? String, plaintext as? String)?.let { SecurePrefs.Decision.Put(it) }
                }
                else -> null
            }
        }

        /** The fallback launch logged the encrypted store's person out: reset() or wipeData() at any point. */
        private fun fallbackLoggedOut(log: SecurePrefs.FallbackLog): Boolean =
            log.cleared || KEY_USER_ID in log.everDeleted

        /**
         * The fallback launch ended as a different person than the encrypted
         * store's: logged out at any point, or identified another user.
         */
        internal fun fallbackSwitchedUser(log: SecurePrefs.FallbackLog): Boolean {
            if (fallbackLoggedOut(log)) return true
            val now = log.fallback[KEY_USER_ID] as? String
            val before = log.encrypted[KEY_USER_ID] as? String
            return now != null && before != null && now != before
        }

        /**
         * The merged outbox: the encrypted store's entries (only its
         * withdrawals after a wipe), then - on a switch to another user
         * without a logout - the encrypted person's unsent attributes under
         * their own ids, then the fallback launch's entries.
         */
        private fun resolveOutbox(encrypted: String?, plaintext: String?, log: SecurePrefs.FallbackLog): SecurePrefs.Decision? {
            val sealed = if (fallbackSwitchedUser(log) && !fallbackLoggedOut(log)) sealedAttributes(log) else null
            if (encrypted == null && sealed == null) return null
            var kept = if (log.cleared) withdrawalsOnly(encrypted) else encrypted
            if (sealed != null) kept = (mergeFallbackValue(KEY_OUTBOX, kept ?: "[]", sealed) as? String) ?: kept
            val merged = if (kept != null && plaintext != null) mergeFallbackValue(KEY_OUTBOX, kept, plaintext) else null
            return when {
                merged != null -> SecurePrefs.Decision.Put(merged)
                kept != null && kept != "[]" -> SecurePrefs.Decision.Put(kept)
                plaintext != null -> SecurePrefs.Decision.Put(plaintext)
                encrypted != null -> SecurePrefs.Decision.Remove
                else -> null
            }
        }

        /**
         * The encrypted store's pending attribute sets as one outbox write
         * under the encrypted store's user and anonymous id (a one-entry
         * serialised outbox), or null when there are none.
         */
        private fun sealedAttributes(log: SecurePrefs.FallbackLog): String? = try {
            val pending = log.encrypted[KEY_PENDING_ATTRIBUTES] as? String
            val user = log.encrypted[KEY_USER_ID] as? String
            val anon = log.encrypted[KEY_ANONYMOUS_ID] as? String
            if (pending == null || user == null || anon == null) {
                null
            } else {
                val attrs = com.google.gson.JsonParser.parseString(pending).asJsonObject
                if (attrs.size() == 0) {
                    null
                } else {
                    val body = com.google.gson.JsonObject().apply {
                        addProperty("userId", user)
                        addProperty("anonymousId", anon)
                        add("attributes", attrs)
                    }
                    val entry = com.google.gson.JsonObject().apply {
                        addProperty("id", UUID.randomUUID().toString())
                        addProperty("kind", "attributes")
                        addProperty("path", "v1/attributes")
                        addProperty("body", io.joryio.sdk.network.WireJson.gson.toJson(body))
                        addProperty("attempts", 0)
                    }
                    com.google.gson.JsonArray().apply { add(entry) }.toString()
                }
            }
        } catch (e: Exception) {
            null
        }

        /** The withdrawals of a serialised outbox (an unsubscribe, the opt-out marker set to true). */
        private fun withdrawalsOnly(json: String?): String? = if (json == null) null else try {
            val all = com.google.gson.JsonParser.parseString(json).asJsonArray
            val kept = com.google.gson.JsonArray()
            all.forEach { e ->
                val o = e.asJsonObject
                val kind = o.get("kind")?.asString
                val body = o.get("body")?.asString ?: ""
                if (kind == "unsubscribe" || (kind == "opt_out_marker" && body.contains("\"tracking_opted_out\":true"))) kept.add(e)
            }
            kept.toString()
        } catch (e: Exception) {
            null
        }

        /** Two JSON objects, the second's keys over the first's. */
        private fun mergeJsonObjects(older: String?, newer: String?): String? {
            if (older == null) return newer
            if (newer == null) return older
            return try {
                val a = com.google.gson.JsonParser.parseString(older).asJsonObject
                com.google.gson.JsonParser.parseString(newer).asJsonObject.entrySet().forEach { (k, v) -> a.add(k, v) }
                a.toString()
            } catch (e: Exception) {
                newer
            }
        }

        /** Two impression histories (epoch millis), merged, the latest 100 kept. */
        private fun mergeImpressions(a: String?, b: String?): String? = try {
            val all = sortedSetOf<Long>()
            listOfNotNull(a, b).forEach { json ->
                com.google.gson.JsonParser.parseString(json).asJsonArray.forEach { all += it.asLong }
            }
            com.google.gson.JsonArray().apply { all.toList().takeLast(100).forEach { add(it) } }.toString()
        } catch (e: Exception) {
            b
        }

        private const val KEY_ANONYMOUS_ID = "anonymous_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION = "session"
        private const val KEY_SESSION_START_DUE = "session_start_due"
        private const val KEY_ATTRIBUTES = "attributes"
        private const val KEY_PENDING_ATTRIBUTES = "pending_attributes"
        private const val KEY_OPTED_OUT = "opted_out"
        private const val KEY_CONSENT_DENIED = "consent_denied"
        private const val KEY_OPT_OUT_MARKER_ACKED = "opt_out_marker_acked"
        private const val KEY_REPORTED_PUSH_PERMISSION = "reported_push_permission"
        private const val KEY_REGISTERED_PUSH_KEY = "registered_push_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_CAMPAIGN_IMPRESSIONS = "campaign_impressions"
        private const val KEY_USER_SEGMENTS = "user_segments"
        private const val KEY_OUTBOX = "write_outbox"
        private const val KEY_PUSH_UNREGISTERED = "push_unregistered"
        private const val KEY_PENDING_UNREGISTER = "pending_unregister"
        private const val KEY_RETIRED_AUTH_TOKENS = "retired_sdk_auth_tokens"
        private const val KEY_HELD_USER_ID = "held_user_id"
    }

    // MARK: - Retired SDK-auth tokens (owner decision D-a)

    /**
     * Logged-out users' SDK-auth tokens, bound to their queued items until
     * those settle. In THIS (encrypted) store: a JWT is a credential.
     */
    fun getRetiredAuthTokens(): Map<String, io.joryio.sdk.network.SdkAuthenticator.Retired> {
        val json = prefs.getString(KEY_RETIRED_AUTH_TOKENS, null) ?: return emptyMap()
        return decodeRetiredTokens(json)
    }

    fun setRetiredAuthTokens(tokens: Map<String, io.joryio.sdk.network.SdkAuthenticator.Retired>) {
        if (tokens.isEmpty()) prefs.edit().remove(KEY_RETIRED_AUTH_TOKENS).apply()
        else prefs.edit().putString(KEY_RETIRED_AUTH_TOKENS, gson.toJson(tokens)).apply()
    }

    // MARK: - Held user id (owner decision D-b)

    /**
     * A userId identify() received while tracking was refused (consent
     * denied or opted out). Not the identity - only what a withdrawal made
     * meanwhile carries; identify() runs for real when tracking is allowed.
     */
    fun getHeldUserId(): String? = prefs.getString(KEY_HELD_USER_ID, null)

    fun setHeldUserId(userId: String?) {
        if (userId == null) prefs.edit().remove(KEY_HELD_USER_ID).apply()
        else prefs.edit().putString(KEY_HELD_USER_ID, userId).apply()
    }

    // MARK: - Anonymous ID

    fun getAnonymousId(): String {
        val existingId = prefs.getString(KEY_ANONYMOUS_ID, null)
        if (existingId != null) {
            return existingId
        }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ANONYMOUS_ID, newId).apply()
        logger.debug("Generated new anonymous ID: $newId")
        return newId
    }

    /**
     * Whether an anonymous id has been persisted yet. Lets a caller-supplied id
     * seed storage without [getAnonymousId] generating one first.
     */
    fun hasStoredAnonymousId(): Boolean = prefs.getString(KEY_ANONYMOUS_ID, null) != null

    fun setAnonymousId(id: String) {
        prefs.edit().putString(KEY_ANONYMOUS_ID, id).apply()
    }

    // MARK: - Device ID

    fun getDeviceId(): String {
        val existingId = prefs.getString(KEY_DEVICE_ID, null)
        if (existingId != null) {
            return existingId
        }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        logger.debug("Generated new device ID: $newId")
        return newId
    }

    // MARK: - User ID

    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    fun setUserId(userId: String?) {
        if (userId != null) {
            prefs.edit().putString(KEY_USER_ID, userId).apply()
            logger.debug("User ID set: ${maskIdentifier(userId)}")
        } else {
            prefs.edit().remove(KEY_USER_ID).apply()
            logger.debug("User ID cleared")
        }
    }

    // MARK: - Session

    fun getSession(): SessionInfo? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            gson.fromJson(json, SessionInfo::class.java)
        } catch (e: Exception) {
            logger.error("Failed to deserialize session", e)
            null
        }
    }

    fun setSession(session: SessionInfo) {
        val json = gson.toJson(session)
        prefs.edit().putString(KEY_SESSION, json).apply()
        logger.debug("Session saved: ${session.sessionId}")
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
        logger.debug("Session cleared")
    }

    /**
     * A Session Start is owed: it was due while collection was blocked (opted
     * out, consent denied). Persisted so the next launch that may collect
     * announces its session even when it resumes the stored one (contract v8
     * 85).
     */
    fun isSessionStartDue(): Boolean = prefs.getBoolean(KEY_SESSION_START_DUE, false)

    fun setSessionStartDue(value: Boolean) {
        if (value) prefs.edit().putBoolean(KEY_SESSION_START_DUE, true).apply()
        else prefs.edit().remove(KEY_SESSION_START_DUE).apply()
    }

    // MARK: - User Attributes
    //
    // The user-attribute trait bag (email/phone/name/custom) is PII and is
    // NEVER persisted at rest — it is held in memory by IdentityManager for the
    // session only. The only durable operation kept here is a purge, used both
    // as a one-time cleanup on init and on reset()/logout, so any bag written by
    // an older SDK build is removed from existing installs.

    // MARK: - Tracking opt-out
    //
    // PERSISTED. A withdrawal of consent has to outlive the process that
    // received it - an in-memory flag meant the next launch read config and
    // silently opted the user back in.

    /**
     * The push-permission value most recently reported to the backend.
     *
     * Kept so the attribute is sent only when it CHANGES. Without it, reporting
     * on every launch and foreground would cost a request per app open to
     * re-state a value that moves maybe twice in an install's lifetime.
     */
    fun getReportedPushPermission(): String? = prefs.getString(KEY_REPORTED_PUSH_PERMISSION, null)

    fun setReportedPushPermission(value: String) {
        prefs.edit().putString(KEY_REPORTED_PUSH_PERMISSION, value).apply()
    }

    fun getOptedOut(): Boolean =
        prefs.getBoolean(KEY_OPTED_OUT, false) || consentMirror.getBoolean(KEY_OPTED_OUT, false)

    fun setOptedOut(value: Boolean) {
        prefs.edit().putBoolean(KEY_OPTED_OUT, value).apply()
        consentMirror.edit().putBoolean(KEY_OPTED_OUT, value).apply()
        StoredDecisions.set(appContext, StoredDecisions.OPTED_OUT, value)
    }

    /**
     * Tracking consent DENIED, persisted (contract item 24, consent model A).
     * Separate from the opt-out: consent is the app's banner, the opt-out is
     * the person's own switch, and either one saying no stops collection.
     * Only `denied` is stored - `pending` holds for one launch, and `granted`
     * is simply the absence of a stored denial. Mirrored in the plain file
     * like the opt-out, so losing the encrypted store cannot lift it.
     */
    fun getConsentDenied(): Boolean =
        prefs.getBoolean(KEY_CONSENT_DENIED, false) || consentMirror.getBoolean(KEY_CONSENT_DENIED, false)

    fun setConsentDenied(value: Boolean) {
        prefs.edit().putBoolean(KEY_CONSENT_DENIED, value).apply()
        consentMirror.edit().putBoolean(KEY_CONSENT_DENIED, value).apply()
        StoredDecisions.set(appContext, StoredDecisions.CONSENT_DENIED, value)
    }

    /**
     * The `tracking_opted_out` value the server last ACKNOWLEDGED (false when
     * never sent - an unmarked profile reads as not opted out). The marker is
     * sent only when the opt-out switch differs from this (contract item 33).
     */
    fun getOptOutMarkerAcked(): Boolean = consentMirror.getBoolean(KEY_OPT_OUT_MARKER_ACKED, false)

    fun setOptOutMarkerAcked(value: Boolean) {
        consentMirror.edit().putBoolean(KEY_OPT_OUT_MARKER_ACKED, value).apply()
    }

    // MARK: - Write outbox (identify, addAlias, attribute ops, subscriptions)
    //
    // Undelivered work, like the pending attribute writes below: encrypted,
    // cleared as the server acknowledges each entry, purged on opt-out and
    // wipeData().

    fun getOutbox(): String? = prefs.getString(KEY_OUTBOX, null)

    fun setOutbox(json: String?) {
        if (json.isNullOrEmpty()) prefs.edit().remove(KEY_OUTBOX).apply()
        else prefs.edit().putString(KEY_OUTBOX, json).apply()
    }

    // MARK: - Push unregistration (durable, shared SDK contract item 22)

    /** True after unregisterPush() until the app registers a token again. */
    fun isPushUnregistered(): Boolean = prefs.getBoolean(KEY_PUSH_UNREGISTERED, false)

    fun setPushUnregistered(value: Boolean) {
        if (value) prefs.edit().putBoolean(KEY_PUSH_UNREGISTERED, true).apply()
        else prefs.edit().remove(KEY_PUSH_UNREGISTERED).apply()
    }

    /** An unregister request the server has not confirmed yet (JSON). */
    fun getPendingUnregister(): String? = prefs.getString(KEY_PENDING_UNREGISTER, null)

    fun setPendingUnregister(json: String?) {
        if (json.isNullOrEmpty()) prefs.edit().remove(KEY_PENDING_UNREGISTER).apply()
        else prefs.edit().putString(KEY_PENDING_UNREGISTER, json).apply()
    }

    // MARK: - Pending attribute writes (outbound queue)
    //
    // DELIBERATELY separate from the trait bag above, which is never persisted.
    // This is not a cache of who the user is - it is UNDELIVERED WORK: the few
    // keys the app wrote that the server has not acknowledged. If it is lost,
    // the write never happens and the server never learns the value; if the
    // trait bag is lost, the server already has it.
    //
    // Encrypted at rest (EncryptedSharedPreferences, see SecurePrefs), cleared
    // the moment the server acks, and purged on reset()/logout/opt-out - so it
    // holds PII only for the span between a failed send and the next successful
    // one, and only when a send has actually failed.

    fun getPendingAttributes(): String? = prefs.getString(KEY_PENDING_ATTRIBUTES, null)

    fun setPendingAttributes(json: String?) {
        if (json.isNullOrEmpty()) {
            prefs.edit().remove(KEY_PENDING_ATTRIBUTES).apply()
        } else {
            prefs.edit().putString(KEY_PENDING_ATTRIBUTES, json).apply()
        }
    }

    fun clearPendingAttributes() {
        prefs.edit().remove(KEY_PENDING_ATTRIBUTES).apply()
        logger.debug("Pending attribute writes cleared")
    }

    fun clearUserAttributes() {
        prefs.edit().remove(KEY_ATTRIBUTES).apply()
        logger.debug("User attributes cleared")
    }

    // MARK: - Device Token (for push notifications)

    fun getDeviceToken(): String? {
        return prefs.getString(KEY_DEVICE_TOKEN, null)
    }

    fun setDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
        logger.debug("Device token saved: ${token.take(20)}...")
    }

    fun clearDeviceToken() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
        logger.debug("Device token cleared")
    }

    // MARK: - Campaign Impressions

    fun getCampaignImpressions(campaignId: String): List<Date> {
        val key = "${KEY_CAMPAIGN_IMPRESSIONS}_$campaignId"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Long>>() {}.type
            val timestamps: List<Long> = gson.fromJson(json, type) ?: emptyList()
            timestamps.map { Date(it) }
        } catch (e: Exception) {
            logger.error("Failed to deserialize campaign impressions", e)
            emptyList()
        }
    }

    /**
     * Forget every recorded impression, so frequency caps start clean.
     *
     * Backs Joryio.resetDisplayedCampaigns(), which exists so a campaign can be
     * re-tested without reinstalling the app. Keys are per-campaign, so this
     * sweeps the prefix rather than a single entry.
     */
    /**
     * The token+identity pair last CONFIRMED registered by the backend.
     *
     * Stored only after a successful response, never before: if the marker were
     * written optimistically, a failed registration would be remembered as done
     * and the device would never retry - silently unreachable by push.
     *
     * The identity is part of the key because the same token under a new user is
     * a genuinely different registration (see reset()).
     */
    fun getRegisteredPushKey(): String? = prefs.getString(KEY_REGISTERED_PUSH_KEY, null)

    fun setRegisteredPushKey(value: String) {
        prefs.edit().putString(KEY_REGISTERED_PUSH_KEY, value).apply()
    }

    fun clearRegisteredPushKey() {
        prefs.edit().remove(KEY_REGISTERED_PUSH_KEY).apply()
    }

    fun clearCampaignImpressions() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_CAMPAIGN_IMPRESSIONS) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun addCampaignImpression(campaignId: String, timestamp: Date) {
        val impressions = getCampaignImpressions(campaignId).toMutableList()
        impressions.add(timestamp)

        // Keep only last 100 impressions
        val recentImpressions = impressions.takeLast(100)

        val key = "${KEY_CAMPAIGN_IMPRESSIONS}_$campaignId"
        val timestamps = recentImpressions.map { it.time }
        val json = gson.toJson(timestamps)
        prefs.edit().putString(key, json).apply()
        logger.debug("Campaign impression recorded: $campaignId")
    }

    // MARK: - User Segments

    // User segments were stored here for a client-side targeting re-check that
    // has been removed: the server decides eligibility, and re-deciding it on
    // the device could only subtract. setUserSegments() was never called by
    // anything, so the check rejected EVERY segment-targeted campaign. Removed
    // rather than left dead, so nobody wires it back up.

    // MARK: - Clear All Data

    /**
     * Erase everything this SDK stored - EXCEPT the opt-out (shared SDK
     * contract item 21). Deleting data is not consent: a user who opted out
     * and then asked for their data to be erased must not come back opted in,
     * which is what clearing the whole file did.
     */
    fun clearAllData() {
        val optedOut = getOptedOut()
        val consentDenied = getConsentDenied()
        // Retired SDK-auth tokens are bound to withdrawals that survive the
        // erasure (contract item 36, D-a); they go when those settle.
        val retiredTokens = prefs.getString(KEY_RETIRED_AUTH_TOKENS, null)
        prefs.edit().clear().apply()
        retiredTokens?.let { prefs.edit().putString(KEY_RETIRED_AUTH_TOKENS, it).apply() }
        if (optedOut) setOptedOut(true)
        // A consent denial is a decision, not data, like the opt-out. The
        // acknowledged marker lives in the plain file and is untouched.
        if (consentDenied) setConsentDenied(true)
        logger.info("All SDK data cleared (opt-out and consent kept)")
    }
}
