package io.joryio.sdk.core

/**
 * The backend's limits on one event (contract item 29), checked at track()
 * so an event the server would refuse for good is dropped here, with a log
 * line, instead of being queued and re-sent forever.
 *
 * Mirrors packages/backend/src/common/utils/validate-event-properties.util.ts:
 * properties serialize to at most 50 KB (51,200 characters of JSON), have at
 * most 200 top-level keys and nest at most 5 levels (the properties object
 * itself is level 1); the event name is 1-255 characters.
 */
internal object EventLimits {
    const val MAX_NAME_LENGTH = 255
    const val MAX_PROPERTY_KEYS = 200
    const val MAX_NESTING_DEPTH = 5
    const val MAX_PROPERTIES_JSON_LENGTH = 51_200

    /** Null when the event is within every limit, otherwise why it is not. */
    fun violation(name: String, properties: Map<String, Any?>): String? {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            return "the event name must be 1-$MAX_NAME_LENGTH characters (got ${name.length})"
        }
        if (properties.size > MAX_PROPERTY_KEYS) {
            return "more than $MAX_PROPERTY_KEYS property keys (${properties.size})"
        }
        if (depthExceeded(properties, 1)) {
            return "properties nest deeper than $MAX_NESTING_DEPTH levels"
        }
        val json = try {
            io.joryio.sdk.network.WireJson.gson.toJson(properties)
        } catch (t: Throwable) {
            return "properties cannot be serialized (${t.message})"
        }
        if (json.length > MAX_PROPERTIES_JSON_LENGTH) {
            return "properties are larger than 50 KB (${json.length / 1024} KB)"
        }
        return null
    }

    /** Depth-first, stopping as soon as the limit is passed (never recurses past it). */
    private fun depthExceeded(value: Any?, depth: Int): Boolean {
        if (depth > MAX_NESTING_DEPTH) return true
        val children: Collection<Any?> = when (value) {
            is Map<*, *> -> value.values
            is Collection<*> -> value
            is Array<*> -> value.asList()
            else -> return false
        }
        for (child in children) {
            if (child is Map<*, *> || child is Collection<*> || child is Array<*>) {
                if (depthExceeded(child, depth + 1)) return true
            }
        }
        return false
    }
}
