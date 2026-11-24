package io.joryio.sdk.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The ONE JSON configuration for everything the SDK puts on the wire or on disk.
 *
 * Two rules, both from shipped bugs:
 *
 * 1. Dates are ISO-8601 in UTC with a real `Z` (shared SDK contract item 7).
 *    The client used `GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")`,
 *    which formats in the device's DEFAULT time zone and then writes a literal
 *    Z after it. A phone in Israel reported every event three hours in the
 *    future; one in California, seven hours in the past. The pattern looked
 *    right in review, which is why it lasted.
 *
 * 2. A number without a fraction reads back as a Long, not a Double
 *    ([ToNumberPolicy.LONG_OR_DOUBLE]). Gson's default turns every JSON number
 *    in a `Map<String, Any?>` into a Double, so `track("x", mapOf("qty" to 2))`
 *    went to disk as 2 and came back as 2.0 - the queue changed the TYPE of a
 *    property between the app and the server.
 */
internal object WireJson {

    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** SimpleDateFormat is not thread-safe; one per thread. */
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat(PATTERN, Locale.US).apply { timeZone = UTC }
    }

    /** `2026-10-06T09:15:00.000Z` - always UTC, whatever the device's zone. */
    fun formatUtc(date: Date): String = formatter.get()!!.format(date)

    /** Inverse of [formatUtc]; also accepts epoch milliseconds. Null when unparseable. */
    fun parseUtc(value: String): Date? = try {
        formatter.get()!!.parse(value)
    } catch (e: ParseException) {
        value.toLongOrNull()?.let { Date(it) }
    }

    private object UtcDateAdapter : TypeAdapter<Date>() {
        override fun write(out: JsonWriter, value: Date?) {
            if (value == null) out.nullValue() else out.value(formatUtc(value))
        }

        override fun read(input: JsonReader): Date? = when (input.peek()) {
            JsonToken.NULL -> {
                input.nextNull(); null
            }
            JsonToken.NUMBER -> Date(input.nextLong())
            else -> parseUtc(input.nextString())
        }
    }

    /**
     * 3. No HTML escaping (contract item 41; scan 4, Android finding M2).
     *    Gson's default writes `<`, `>`, `&`, `=` and `'` as six-character
     *    `<` escapes. The JSON means the same, but every size the SDK
     *    measures with it - the 50 KB property limit at track(), the batch
     *    byte budget - read up to six times larger than the backend's
     *    `JSON.stringify(...).length`, so an event the server accepts was
     *    dropped at track() for being "too big". Nothing this serializer
     *    writes is parsed as HTML: request bodies, the SDK's own files, and a
     *    form answer handed to `evaluateJavascript` (a JS literal, not markup;
     *    U+2028/U+2029 are still escaped by Gson either way).
     */
    /**
     * 4. A whole-number Double or Float is written as an integer (1.4.0-beta.9
     *    test). Gson writes 3.0 as `3.0`; iOS (JSONSerialization) and the web
     *    (JSON.stringify) write `3`, so one property had two wire values. React
     *    Native hands every JS number to Android as a Double, and Unity's JSON
     *    can too - `track("x", { n: 3 })` from JS reached the server as 3.0
     *    from Android only. A real fraction (3.5) is written as before, and
     *    so is a whole number beyond 2^53, where a Double no longer holds
     *    every integer exactly.
     */
    internal fun isWireInteger(d: Double): Boolean =
        !d.isNaN() && !d.isInfinite() && d == Math.floor(d) && Math.abs(d) <= MAX_SAFE_INTEGER

    private const val MAX_SAFE_INTEGER = 9007199254740991.0 // 2^53 - 1

    private object WholeNumberFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            val raw = type.rawType
            if (raw != java.lang.Double::class.java && raw != java.lang.Double.TYPE &&
                raw != java.lang.Float::class.java && raw != java.lang.Float.TYPE
            ) return null
            val delegate = gson.getDelegateAdapter(this, type)
            return object : TypeAdapter<T>() {
                override fun write(out: JsonWriter, value: T?) {
                    val d = (value as? Number)?.toDouble()
                    if (d != null && isWireInteger(d)) out.value(d.toLong()) else delegate.write(out, value)
                }

                override fun read(input: JsonReader): T? = delegate.read(input)
            }
        }
    }

    val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(WholeNumberFactory)
        .registerTypeAdapter(Date::class.java, UtcDateAdapter)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .disableHtmlEscaping()
        .create()
}
