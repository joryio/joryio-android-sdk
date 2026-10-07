package io.joryio.sdk.core

import io.joryio.sdk.models.UserAttributes

/**
 * Manages user identity and attributes
 */
internal class IdentityManager(
    private val storage: StorageManager,
    private val logger: Logger,
    initialUserId: String? = null,
    initialAnonymousId: String? = null
) {
    // The user-attribute trait bag (email/phone/name/custom) is PII and is held
    // IN MEMORY ONLY for the session — never persisted at rest. The server is
    // the source of truth, and increment/append/remove are now server-atomic,
    // so no local value is needed for correctness. Only used for in-session
    // read-back (getUserAttributes / getAttribute).
    private val attributes: MutableMap<String, Any?> = mutableMapOf()

    /**
     * The id this run reports under when the caller supplied one. Mirrors the
     * web SDK exactly: a caller-supplied id is used for this run and SEEDS
     * storage only when nothing is stored yet, so an id set once at init
     * survives the app dropping the option later. `JoryioConfig.anonymousId`
     * was accepted and never read here (audit 2026-09-26, A17).
     */
    private var suppliedAnonymousId: String? = initialAnonymousId?.takeIf { it.isNotBlank() }

    init {
        // Set initial user ID if provided
        if (initialUserId != null && storage.getUserId() == null) {
            storage.setUserId(initialUserId)
        }

        // Seed the anonymous id if provided and nothing is stored yet.
        val seed = suppliedAnonymousId
        if (seed != null && !storage.hasStoredAnonymousId()) {
            storage.setAnonymousId(seed)
        }

        // One-time purge: delete any attributes bag persisted by an older SDK
        // build so previously-stored PII is removed from existing installs.
        storage.clearUserAttributes()
    }

    // MARK: - Anonymous ID

    fun getAnonymousId(): String {
        return suppliedAnonymousId ?: storage.getAnonymousId()
    }

    /**
     * After wipeData() the stored id is regenerated; the seed must not outlive
     * the data it seeded, or the "wiped" install would keep reporting under it.
     */
    fun onDataWiped() {
        suppliedAnonymousId = null
    }

    // MARK: - User ID

    fun getUserId(): String? {
        return storage.getUserId()
    }

    fun identify(userId: String) {
        logger.info("Identifying user: ${maskIdentifier(userId)}")

        // Store user ID
        storage.setUserId(userId)
    }

    /**
     * Logout: forget the person AND start a new anonymous identity.
     *
     * The anonymous id used to survive reset(), so everything the NEXT person
     * did on this phone before signing in was recorded under the previous
     * person's anonymous id - which the backend had already merged into the
     * previous person's profile. Two people's activity ended up in one
     * profile. Shared SDK contract item 6: a fresh anonymous id on every SDK.
     *
     * A caller-supplied anonymous id (JoryioConfig.anonymousId) is dropped too:
     * it identified the outgoing session, not the device.
     */
    fun reset() {
        // Drop un-acked writes for the OUTGOING user - sending them after a
        // reset would attribute one person's traits to the next.
        pending.clear()
        logger.info("Resetting user identity")
        storage.setUserId(null)
        // Clear the in-memory trait bag and purge any orphaned persisted bag.
        attributes.clear()
        storage.clearUserAttributes()
        suppliedAnonymousId = null
        storage.setAnonymousId(java.util.UUID.randomUUID().toString())
    }

    // MARK: - User Attributes (in-memory only — never persisted)

    fun getUserAttributes(): UserAttributes {
        return attributes.toMap()
    }

    /** Current in-session value of a single attribute (null if unset). */
    fun getAttribute(key: String): Any? {
        return attributes[key]
    }

    /**
     * Attributes written locally but not acknowledged by the server. Lives here
     * because it is identity state, and because both the SDK facade (to flush
     * it) and the in-app sync (to send it) already hold this manager -
     * threading a new dependency through their constructors would have changed
     * signatures the RN bridge and demo app compile against.
     */
    internal val pending = PendingAttributes(storage = storage)

    fun setAttributes(attributes: UserAttributes) {
        logger.debug("Setting ${attributes.size} user attributes")
        // Merge in memory only — NOT persisted (PII stays off durable storage).
        this.attributes.putAll(attributes)
    }

    fun setAttribute(key: String, value: Any) {
        logger.debug("Setting user attribute: $key")
        // In-memory only — NOT persisted (PII stays off durable storage).
        attributes[key] = value
    }

    fun incrementAttribute(key: String, by: Number = 1) {
        logger.debug("Incrementing user attribute: $key by $by")
        // In-memory optimistic echo only. The server receives the delta as an
        // atomic `$inc`; this local value is just for in-session read-back.
        val currentValue = attributes[key]

        val newValue: Any = when (currentValue) {
            is Int -> currentValue + by.toInt()
            is Long -> currentValue + by.toLong()
            is Float -> currentValue + by.toFloat()
            is Double -> currentValue + by.toDouble()
            else -> by
        }

        attributes[key] = newValue
    }

    fun unsetAttribute(key: String) {
        logger.debug("Unsetting user attribute: $key")
        // In-memory only — NOT persisted (PII stays off durable storage).
        attributes.remove(key)
    }
}
