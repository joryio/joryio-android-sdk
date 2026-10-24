package io.joryio.sdk.core

/**
 * Non-finite numbers (NaN, +/-Infinity) are dropped where they ENTER the SDK
 * - attribute values, event and e-commerce properties - with a warning; the
 * key is omitted and the call otherwise goes ahead (shared SDK contract item
 * 49, the same rule on all five SDKs).
 *
 * JSON has no NaN. Gson throws on one, and that exception came out of
 * setAttributes() into the host app (scan 5, Android finding B1); the value
 * was already in the pending set by then, so every later attribute flush and
 * every in-app sync failed to serialise for the rest of the process. An
 * event carrying one was dropped whole, where web sent `null` (which the
 * backend reads as an unset). One bad value now never blocks anything else.
 */
internal object NonFinite {

    fun isNonFinite(value: Any?): Boolean = when (value) {
        is Double -> value.isNaN() || value.isInfinite()
        is Float -> value.isNaN() || value.isInfinite()
        else -> false
    }

    /**
     * [values] without its non-finite numbers, at any depth: a map key whose
     * value is one is omitted, a list element that is one is skipped. Returns
     * the SAME map when there is nothing to drop (the common case costs one
     * walk, no copy).
     *
     * @param what names the call in the warning ("setAttributes", "track('x')").
     */
    fun sanitize(values: Map<String, Any?>, logger: Logger?, what: String): Map<String, Any?> {
        if (!containsNonFinite(values, 0)) return values
        val dropped = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val clean = clean(values, "", dropped, 0) as Map<String, Any?>
        val message = "$what: dropped non-finite number(s) (NaN or Infinity) at ${dropped.joinToString(", ")}; " +
            "JSON cannot carry them. The rest of the call goes ahead."
        if (logger != null) logger.warn(message) else android.util.Log.w("Joryio", message)
        return clean
    }

    /** Deep enough for anything the backend accepts (5 levels) with room to spare; never recurses forever. */
    private const val MAX_DEPTH = 32

    private fun containsNonFinite(value: Any?, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        return when (value) {
            is Map<*, *> -> value.values.any { isNonFinite(it) || containsNonFinite(it, depth + 1) }
            is Collection<*> -> value.any { isNonFinite(it) || containsNonFinite(it, depth + 1) }
            is Array<*> -> value.any { isNonFinite(it) || containsNonFinite(it, depth + 1) }
            is DoubleArray -> value.any { it.isNaN() || it.isInfinite() }
            is FloatArray -> value.any { it.isNaN() || it.isInfinite() }
            else -> false
        }
    }

    private fun clean(value: Any?, path: String, dropped: MutableList<String>, depth: Int): Any? {
        if (depth > MAX_DEPTH) return value
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<Any?, Any?>()
                for ((k, v) in value) {
                    val here = if (path.isEmpty()) "$k" else "$path.$k"
                    if (isNonFinite(v)) {
                        dropped += here
                    } else {
                        out[k] = clean(v, here, dropped, depth + 1)
                    }
                }
                out
            }
            is Collection<*> -> cleanList(value.toList(), path, dropped, depth)
            is Array<*> -> cleanList(value.toList(), path, dropped, depth)
            is DoubleArray -> cleanList(value.toList(), path, dropped, depth)
            is FloatArray -> cleanList(value.toList(), path, dropped, depth)
            else -> value
        }
    }

    private fun cleanList(items: List<Any?>, path: String, dropped: MutableList<String>, depth: Int): List<Any?> {
        val out = ArrayList<Any?>(items.size)
        items.forEachIndexed { i, v ->
            if (isNonFinite(v)) dropped += "$path[$i]" else out += clean(v, "$path[$i]", dropped, depth + 1)
        }
        return out
    }
}
