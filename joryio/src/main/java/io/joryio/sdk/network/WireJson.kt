package io.joryio.sdk.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.TypeAdapter
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
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, UtcDateAdapter)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .disableHtmlEscaping()
        .create()
}
