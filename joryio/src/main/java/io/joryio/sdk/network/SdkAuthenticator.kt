package io.joryio.sdk.network

import io.joryio.sdk.SdkAuthError

/**
 * Thread-safe holder for the optional SDK-authentication JWT and the app's
 * auth-error handler.
 *
 * The token is read on the OkHttp/IO thread (from the auth interceptor) and
 * written from the app thread (via [Joryio.setSdkAuthenticationToken]); every
 * field is [Volatile] so writes are visible across threads without a lock. No
 * compound invariant spans the fields, so per-field volatility is sufficient.
 */
internal class SdkAuthenticator(
    enabled: Boolean,
    initialToken: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Supplying a token is itself an opt-in signal, so a config-provided initial
    // token enables the feature even if the flag was left false.
    @Volatile
    private var enabledFlag: Boolean = enabled || initialToken != null

    @Volatile
    private var token: String? = initialToken

    @Volatile
    private var errorHandler: ((SdkAuthError) -> Unit)? = null

    /**
     * SDK Authentication is switched on (by config or by a token), whether or
     * not a token has arrived yet. A 401 while this is true is reported to the
     * handler even with no token set - reason MISSING (contract item 30).
     *
     * Also on while a logged-out user's token is kept (contract v6 61): an
     * app that turns the feature on only with setSdkAuthenticationToken() at
     * login starts the next launch with it off, and that user's leftovers -
     * restored with their retired token - used to go out with no header,
     * be refused and dropped.
     */
    val isFeatureOn: Boolean
        get() = enabledFlag || hasRetired

    /** True when a token should be attached to outgoing ingest requests. */
    val isEnabled: Boolean
        get() = enabledFlag && token != null

    /** Current JWT, or null if none set. */
    fun currentToken(): String? = token

    /**
     * Set/replace the JWT (from the app thread). Also opts the feature in.
     *
     * A token for a DIFFERENT user (its `sub`) retires the one it replaces to
     * that user's leftovers first (contract item 53): an app that switches
     * accounts without reset() - set B's token, then identify(B), in either
     * order - used to send A's queued writes with B's token, refused as
     * `sub_mismatch` and dropped. A refresh for the same user just replaces.
     */
    @Synchronized
    fun setToken(newToken: String) {
        val previous = token
        if (previous != null && previous != newToken) {
            val previousSub = subjectOf(previous)
            if (previousSub != null && previousSub != subjectOf(newToken)) retireLocked(previousSub, previous)
        }
        token = newToken
        enabledFlag = true
    }

    /**
     * Forget the JWT (reset(): it belongs to the user who just logged out -
     * contract item 39). The feature stays on: the next user's token is set
     * with [setToken], and until then writes go out without one, which the
     * server accepts for an anonymous person.
     */
    fun clearToken() {
        token = null
    }

    /**
     * Logout (reset(), wipeData()): the session stops holding the JWT, but it
     * stays bound to the outgoing user so THEIR queued events and writes still
     * go out authenticated. Clearing it outright made every normal logout
     * under SDK Authentication drop that user's last events with a 401 (the
     * same fix on iOS and web).
     *
     * PERSISTED (owner decision D-a, 2026-10-07; scan 5, Android S5) in the
     * SDK's encrypted store through [store]: kept in memory only, a process
     * that died before the outgoing user's queue drained - an app swiped away
     * right after logout - sent their unsubscribe with no token, and the 401
     * for a user who is no longer signed in dropped it. Deleted when that
     * user's last queued item settles ([prune]); the oldest is forgotten past
     * [MAX_RETIRED_TOKENS].
     *
     * Filed under the token's own `sub` when it can be read, so a token set
     * for the NEXT user before this call is not filed under the outgoing one.
     */
    @Synchronized
    fun retireToken(outgoingUserId: String?, signedInUserId: String? = null) {
        val current = token
        if (current != null) {
            val sub = subjectOf(current)
            val owner = sub ?: outgoingUserId
            // A token set for the NEXT user before this reset() (contract v7
            // 72, same on iOS and web) is kept on STANDBY: nothing of theirs is
            // queued yet, so the "nothing left" prune must not forget it before
            // their identify() adopts it ([adoptRetired]). Also when no one was
            // signed in (contract v8 82; scan 8, Android NICE 2): an anonymous
            // visitor's app that set the next user's token and then called
            // reset() lost it after the one-minute grace - identify() later
            // went out with no token and had to ask the handler.
            // Never standby when its `sub` is the SIGNED-IN user (contract v9
            // 89; scan 9, Android B1): with a different user held for
            // withdrawals (D-b), [outgoingUserId] is the held one, and the
            // identified user's own token was filed as standby - wipeData()
            // then dropped it and their undelivered unsubscribe went out with
            // no token, got a 401 and was dropped.
            val standby = sub != null && sub != outgoingUserId && sub != signedInUserId
            if (!owner.isNullOrEmpty()) retireLocked(owner, current, standby)
        }
        token = null
    }

    /**
     * identify([userId]): with no session token and one kept for that user -
     * set for them before a reset(), or theirs from an earlier logout - it
     * becomes the session's token again, so a refresh replaces it and the
     * prune does not forget it while they are signed in (same on iOS and web).
     */
    @Synchronized
    fun adoptRetired(userId: String) {
        if (token != null) return
        val kept = retired.remove(userId) ?: return
        token = kept.token
        // An adopted token switches the feature on, as setToken() does
        // (contract v8 81; scan 8, iOS S1 - Android identical). An app that
        // turns SDK auth on only with setSdkAuthenticationToken() starts the
        // next launch with the flag off and the feature on only through the
        // kept token; adopting the last kept token used to switch it OFF -
        // no header on any request until the next setToken().
        enabledFlag = true
        hasRetired = retired.isNotEmpty()
        persistLocked()
    }

    /**
     * A held identify() (D-b) of [userId]: their token kept on standby is no
     * longer the next user's (contract v9 89) - kept as theirs, pruned like
     * any other once nothing of theirs is queued. Not adopted as the
     * session's token: that may still be the identified user's.
     */
    @Synchronized
    fun unflagStandby(userId: String) {
        val kept = retired[userId] ?: return
        if (!kept.standby) return
        retired[userId] = kept.copy(standby = false, retiredAt = clock())
        persistLocked()
    }

    /**
     * wipeData(): forget every STANDBY token (contract v8 82; scan 8, Android
     * NICE 1). It belongs to someone not yet signed in - nobody being erased -
     * and kept through the erasure it waited 30 days, never pruned, to be
     * attached at that person's next identify().
     */
    @Synchronized
    fun dropStandby(keep: Set<String> = emptySet(), keepToken: String? = null) {
        val before = retired.size
        // [keep]: the signed-in and held users and anyone with a withdrawal
        // still queued (contract v9 89; scan 9, Android B1b) - a held user's
        // own standby token (set while anonymous, kept by reset(), never
        // adopted on the held path) was dropped and their unsubscribe lost.
        // [keepToken]: this launch's token at a pre-init wipe (contract 84).
        retired.entries.removeAll { (user, r) -> r.standby && user !in keep && (keepToken == null || r.token != keepToken) }
        hasRetired = retired.isNotEmpty()
        if (retired.size != before) persistLocked()
    }

    /**
     * identify() of a DIFFERENT user without reset() (contract item 53): the
     * session's token goes with [outgoingUserId]'s leftovers - unless it is
     * already the incoming user's (the app set it first), which stays.
     */
    @Synchronized
    fun retireTokenOnSwitch(outgoingUserId: String, incomingUserId: String) {
        val current = token ?: return
        val sub = subjectOf(current)
        if (sub == incomingUserId) return
        retireLocked(sub ?: outgoingUserId, current)
        token = null
    }

    private fun retireLocked(userId: String, jwt: String, standby: Boolean = false) {
        retired.remove(userId)
        retired[userId] = Retired(jwt, clock(), standby)
        while (retired.size > MAX_RETIRED_TOKENS) {
            retired.remove(retired.keys.first())
        }
        hasRetired = true
        persistLocked()
    }

    /**
     * The JWT a request about [subjectUserId] carries, routed by the token's
     * `sub` (contract v7 72): the session token when its `sub` is that user,
     * else a retired token filed under that user, else none - the 401 MISSING
     * then asks the handler, for the current user only. A token whose `sub`
     * names someone else is never attached.
     *
     * This used to ask "is the subject the current user?" instead: the
     * session token went on every current-user request, also when it was the
     * NEXT user's (setToken(B) before identify(B): A's events carried B's
     * token, refused as `sub_mismatch` and dropped), and a held user (D-b,
     * current for withdrawals) never got the retired token of their own that
     * a reset() had kept - their unsubscribe went out with no token (scan 7,
     * Android NICE 2). A session token whose `sub` cannot be read stays the
     * current user's, as before. An anonymous request carries no token (the
     * server checks none for an anonymous subject; same on iOS and web).
     */
    @Synchronized
    fun tokenFor(subjectUserId: String?, currentUserId: String?): String? {
        val session = token
        // Anonymous (contract v7 72, same on web and iOS): the server checks no
        // token for an anonymous subject, and no token names this visitor.
        if (subjectUserId.isNullOrEmpty()) return null
        val sessionSub = subjectOf(session)
        if (session != null && sessionSub == subjectUserId) return session
        retired[subjectUserId]?.let { return it.token }
        if (session != null && sessionSub == null && subjectUserId == currentUserId) return session
        return null
    }

    /** True while some logged-out user's JWT is kept (the body is only parsed then). */
    val hasRetiredTokens: Boolean
        get() = hasRetired

    /** A logged-out user's JWT and when it was retired. */
    data class Retired(val token: String, val retiredAt: Long, val standby: Boolean = false)

    private val retired = LinkedHashMap<String, Retired>()

    @Volatile
    private var hasRetired = false

    /** Where retired tokens are kept between launches (the SDK's encrypted store). */
    interface Store {
        fun load(): Map<String, Retired>
        fun save(tokens: Map<String, Retired>)
    }

    @Volatile
    private var store: Store? = null

    /** Wire the encrypted store and restore what an earlier process retired. */
    @Synchronized
    fun attachStore(store: Store) {
        this.store = store
        try {
            store.load().forEach { (user, r) -> if (!retired.containsKey(user)) retired[user] = r }
        } catch (t: Throwable) {
            // An unreadable copy: nothing to restore.
        }
        hasRetired = retired.isNotEmpty()
        prune(activeUserIds = null)
    }

    /**
     * Forget the tokens no longer needed: those of users with nothing left
     * queued ([activeUserIds]; null = unknown, keep them), and any retired
     * more than [MAX_RETIRED_AGE_MS] ago - the backend accepts an expired
     * token for withdrawals for 30 days past its expiry, and never longer.
     */
    @Synchronized
    fun prune(activeUserIds: Set<String>?) {
        val now = clock()
        val before = retired.size
        retired.entries.removeAll { (user, r) ->
            val age = now - r.retiredAt
            // A grace minute before "nothing queued" counts: an event tracked
            // just before the logout may still be on its way into the queue.
            age > MAX_RETIRED_AGE_MS ||
                (!r.standby && activeUserIds != null && user !in activeUserIds && age > SETTLE_GRACE_MS)
        }
        hasRetired = retired.isNotEmpty()
        if (retired.size != before) persistLocked()
    }

    /** Users whose leftovers still have a token. */
    @Synchronized
    fun retiredUserIds(): Set<String> = retired.keys.toSet()

    private fun persistLocked() {
        try {
            store?.save(LinkedHashMap(retired))
        } catch (t: Throwable) {
            // Kept in memory for this process.
        }
    }

    fun setErrorHandler(handler: (SdkAuthError) -> Unit) {
        errorHandler = handler
    }

    /**
     * Surface an auth error to the host app. Runs on the calling (OkHttp) thread
     * and never throws — a misbehaving host callback must not crash the network
     * layer.
     */
    companion object {
        const val MAX_RETIRED_TOKENS = 4
        const val MAX_RETIRED_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val SETTLE_GRACE_MS = 60_000L

        /**
         * The `sub` claim of a JWT, or null when it cannot be read. Read
         * only to tell WHOSE token it is; never verified - the server does.
         */
        fun subjectOf(jwt: String?): String? = try {
            val payload = jwt?.split('.')?.getOrNull(1)
            if (payload.isNullOrEmpty()) {
                null
            } else {
                val bytes = android.util.Base64.decode(
                    payload,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                )
                (com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8)) as? com.google.gson.JsonObject)
                    ?.get("sub")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun notifyError(error: SdkAuthError) {
        try {
            errorHandler?.invoke(error)
        } catch (_: Throwable) {
            // Swallow: the host handler is untrusted code on our IO thread.
        }
    }
}
