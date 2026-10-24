package io.joryio.sdk.core

/**
 * Attributes written by the app that the server has not acknowledged yet.
 *
 * `setAttributes` used to POST fire-and-forget: on failure it logged and
 * dropped the value. An attribute set while offline never reached the server at
 * all, and because the in-app sync path ALSO carried attributes and let them
 * override the stored profile, in-app targeting still looked right - so the
 * loss was invisible everywhere it mattered (segments, journeys, email).
 *
 * Events have had a durable at-least-once queue with retry since the
 * beginning. Attributes had nothing. This closes that gap.
 *
 * ## Why in-memory and not disk, unlike events
 *
 * The SDK deliberately keeps attributes off durable storage - [IdentityManager]
 * holds them in memory only, because they are PII. A disk-backed retry queue
 * would put exactly that PII at rest.
 *
 * It would also preserve a write whose source of truth is already gone: if the
 * process dies the in-memory attribute dies with it, so a queued copy would
 * outlive the value it represents. In-memory retry matches the lifetime of the
 * data it retries - the correct durability level here, not a compromise.
 *
 * What this does fix is the case that actually loses data in practice: the app
 * is running, the network is not, and the value is dropped for good.
 *
 * ## Second job: the un-acked set IS the dirty set
 *
 * [pending] is what the in-app sync request sends. Only attributes the server
 * has not confirmed need to ride that request; everything else is already in
 * the profile and the server should read its own copy. That turns the sync
 * payload from "everything the device believes" into "the little the server
 * might not know yet", which is what makes server-authoritative targeting safe.
 *
 * Mirrors `PendingAttributes.swift`.
 */
internal class PendingAttributes(
    private val maxKeys: Int = 500,
    /**
     * Encrypted durable backing, or null to stay in-memory only.
     *
     * Persisted because an un-acked write is UNDELIVERED WORK, not a cache: if
     * the process dies before the flush, the value never reaches the server and
     * nothing else holds it. That is the one case in-memory retry cannot cover.
     */
    private val storage: StorageManager? = null,
) {

    private val pending = LinkedHashMap<String, Any?>()
    private val lock = Any()
    // WireJson: an integer attribute must come back from disk an integer.
    private val gson = io.joryio.sdk.network.WireJson.gson

    init {
        restore()
    }

    private fun restore() {
        val json = storage?.getPendingAttributes() ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<LinkedHashMap<String, Any?>>() {}.type
            val restored: LinkedHashMap<String, Any?>? = gson.fromJson(json, type)
            if (restored != null) synchronized(lock) { pending.putAll(restored) }
        } catch (e: Exception) {
            // Corrupt blob: drop it rather than crash the SDK on init.
            storage.clearPendingAttributes()
        }
    }

    /**
     * Called inside the lock. Never throws: a value the serializer refuses
     * must not escape into the host app through setAttributes() (scan 5,
     * Android B1). Non-finite numbers are already stopped at the entry points
     * (NonFinite); anything else unserializable is dropped from the set here
     * so it cannot fail every later write too.
     */
    private fun persistLocked() {
        val s = storage ?: return
        try {
            if (pending.isEmpty()) s.clearPendingAttributes() else s.setPendingAttributes(gson.toJson(pending))
        } catch (t: Throwable) {
            val bad = pending.filter { (_, v) ->
                try {
                    gson.toJson(v); false
                } catch (e: Throwable) {
                    true
                }
            }.keys
            bad.forEach { pending.remove(it) }
            android.util.Log.w("Joryio", "Attribute value(s) $bad cannot be stored as JSON; dropped (${t.message})")
            try {
                if (pending.isEmpty()) s.clearPendingAttributes() else s.setPendingAttributes(gson.toJson(pending))
            } catch (e: Throwable) {
                // Kept in memory; the next write persists again.
            }
        }
    }

    /** Mark attributes as written locally and not yet acknowledged. */
    fun mark(attributes: Map<String, Any?>) {
        synchronized(lock) {
            pending.putAll(attributes)
            // Bound memory if an app sets unique keys in a loop while offline.
            // LinkedHashMap preserves insertion order, so the oldest go first -
            // they are also the likeliest to have been superseded.
            while (pending.size > maxKeys) {
                val oldest = pending.keys.firstOrNull() ?: break
                pending.remove(oldest)
            }
            persistLocked()
        }
    }

    /** Everything the server has not confirmed. */
    fun pending(): Map<String, Any?> = synchronized(lock) { LinkedHashMap(pending) }

    fun isEmpty(): Boolean = synchronized(lock) { pending.isEmpty() }

    /**
     * Clear the keys the server confirmed - but ONLY where the value is still
     * the one that was sent.
     *
     * If the app called `setAttributes(mapOf("plan" to "pro"))` while a flush
     * carrying `plan=free` was in flight, acking the flush must not clear
     * `plan`, or the newer value is silently lost. Comparing values, not just
     * keys, is what makes a concurrent write safe.
     */
    fun acknowledge(sent: Map<String, Any?>) {
        synchronized(lock) {
            for ((key, sentValue) in sent) {
                if (!pending.containsKey(key)) continue
                if (pending[key] == sentValue) {
                    pending.remove(key)
                }
            }
            persistLocked()
        }
    }

    /** Forget these keys - an unset of the same key supersedes a pending set. */
    fun drop(keys: Collection<String>) {
        synchronized(lock) {
            keys.forEach { pending.remove(it) }
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            storage?.clearPendingAttributes()
        }
    }
}
