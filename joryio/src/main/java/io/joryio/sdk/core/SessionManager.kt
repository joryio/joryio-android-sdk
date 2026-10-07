package io.joryio.sdk.core

import io.joryio.sdk.models.SessionInfo
import java.util.Date
import java.util.UUID

/**
 * Manages user sessions with automatic timeout
 */
internal class SessionManager(
    private val storage: StorageManager,
    private val logger: Logger,
    private val sessionTimeout: Long = 1800000 // 30 minutes
) {
    // Guards every read-modify-write of currentSession. Session state is touched
    // from the RN module thread, Dispatchers.Main, and the lifecycle observer, so
    // the id could otherwise diverge under concurrent rotation.
    private val lock = Any()

    @Volatile
    private var currentSession: SessionInfo? = null

    /**
     * True when this launch RESUMED a session that had not timed out. Such a
     * launch sends no Session Start (contract item 32): the session it
     * continues already has one. Android used to send a second one on every
     * cold start inside the timeout.
     */
    var resumedAtLaunch: Boolean = false
        private set

    init {
        // Try to restore previous session
        val savedSession = storage.getSession()
        if (savedSession != null) {
            if (isSessionValid(savedSession)) {
                currentSession = savedSession
                resumedAtLaunch = true
                logger.debug("Restored previous session: ${savedSession.sessionId}")
            } else {
                logger.debug("Previous session expired, will create new session")
            }
        }
    }

    /**
     * The id the NEXT session will get, when there is no current one (after
     * wipeData(), or before anything started one). A read reports it, and the
     * session that does start takes it - so a value a host or an in-app sync
     * read is the id the events then carry.
     */
    private var nextSessionId: String? = null

    /**
     * The current session - a pure READ (shared SDK contract item 54; scan 5,
     * Android S4 and Unity S1). It never starts a session and never extends
     * one.
     *
     * It used to do both: an expired session was replaced here with NO Session
     * Start, and a live one had its lastActivity refreshed. getSessionId() and
     * getSessionInfo() (a game stamping its own logs with the id every frame),
     * an in-app sync, resolve or impression all went through it, so a polled
     * session never timed out, and after a long foreground idle the next read
     * minted a new session that no Session Start ever announced - the next
     * track() found it valid. Sessions start in [checkAndRenew] (track(),
     * foreground) and [startNewSession], whose callers send the Session Start.
     *
     * An expired session is reported as it is - its id until the next event
     * starts a new one, as on iOS. A copy: the caller cannot move the clock.
     */
    fun getSession(): SessionInfo = synchronized(lock) {
        currentSession?.copy() ?: run {
            val id = nextSessionId ?: UUID.randomUUID().toString().also { nextSessionId = it }
            val now = Date()
            SessionInfo(sessionId = id, sessionStart = now, lastActivity = now)
        }
    }

    /** Current session id - a pure read, see [getSession]. */
    fun getSessionId(): String = getSession().sessionId

    /**
     * Start a new session
     */
    fun startNewSession(): SessionInfo = synchronized(lock) {
        startNewSessionLocked()
    }

    // Caller must hold [lock].
    private fun startNewSessionLocked(): SessionInfo {
        // Whatever this launch resumed is over.
        resumedAtLaunch = false
        // The id a read handed out while there was no session, if any.
        val sessionId = nextSessionId ?: UUID.randomUUID().toString()
        nextSessionId = null
        val now = Date()

        val session = SessionInfo(
            sessionId = sessionId,
            sessionStart = now,
            lastActivity = now
        )

        currentSession = session
        storage.setSession(session)

        logger.info("Started new session: $sessionId")
        return session
    }

    /**
     * End current session
     */
    fun endSession() = synchronized(lock) {
        val session = currentSession
        resumedAtLaunch = false
        if (session != null) {
            logger.info("Ending session: ${session.sessionId}")
            currentSession = null
            storage.clearSession()
        }
    }

    /**
     * Update last activity timestamp - of a session that is still LIVE.
     *
     * It used to refresh whatever session was held, expired or not. The app
     * coming to the foreground called this, so a session never expired while
     * the process lived: back after two hours, same session, no Session Start.
     * An expired session is now left alone, and the next [checkAndRenew]
     * (track(), foreground) starts a new one.
     */
    fun updateActivity() = synchronized(lock) {
        val session = currentSession ?: return@synchronized
        if (!isSessionValid(session)) return@synchronized
        session.lastActivity = Date()
        storage.setSession(session)
    }

    /**
     * Foreground check, mirroring iOS's `checkAndRenewSession`: start a new
     * session when the current one has expired (or there is none), otherwise
     * extend it. Returns true when a NEW session started, so the caller can
     * emit Session Start.
     */
    fun checkAndRenew(): Boolean = synchronized(lock) {
        val session = currentSession
        if (session == null || !isSessionValid(session)) {
            startNewSessionLocked()
            true
        } else {
            session.lastActivity = Date()
            storage.setSession(session)
            false
        }
    }

    /**
     * Check if session is still valid
     */
    private fun isSessionValid(session: SessionInfo): Boolean {
        val now = Date()
        val timeSinceLastActivity = now.time - session.lastActivity.time
        return timeSinceLastActivity < sessionTimeout
    }

    /**
     * Get time until session expires
     */
    fun getTimeUntilExpiration(): Long? = synchronized(lock) {
        val session = currentSession ?: return@synchronized null
        val now = Date()
        val timeSinceLastActivity = now.time - session.lastActivity.time
        val remaining = sessionTimeout - timeSinceLastActivity
        if (remaining > 0) remaining else null
    }
}
