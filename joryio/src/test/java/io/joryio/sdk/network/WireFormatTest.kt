package io.joryio.sdk.network

import io.joryio.sdk.core.Logger
import io.joryio.sdk.models.Event
import io.joryio.sdk.queue.EventEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/**
 * What the SDK puts on the wire, checked in a NON-UTC default time zone - the
 * condition the timestamp bug needed and every CI machine (UTC) hid.
 */
class WireFormatTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun pinZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jerusalem"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun `dates are UTC with a real Z, whatever the device zone`() {
        // Fails on the old GsonBuilder().setDateFormat("...'Z'"): that printed
        // 02:00 local (Jerusalem, winter) and labelled it Z.
        assertEquals("1970-01-01T00:00:00.000Z", WireJson.formatUtc(Date(0)))
        val json = WireJson.gson.toJson(
            Event(type = "x", timestamp = Date(1_759_745_700_000), anonymousId = "a", sessionId = "s", eventId = "e"),
        )
        assertTrue(json, json.contains("\"timestamp\":\"2025-10-06T10:15:00.000Z\""))
    }

    @Test
    fun `a UTC timestamp parses back to the same instant`() {
        val d = Date(1_759_745_700_123)
        assertEquals(d, WireJson.parseUtc(WireJson.formatUtc(d)))
    }

    @Test
    fun `integer properties stay integers through the on-disk queue`() {
        // Fails on a bare Gson(): every number came back a Double (2 -> 2.0).
        val event = Event(
            type = "Purchase",
            properties = mapOf("qty" to 2, "price" to 9.5, "big" to 9_007_199_254_740_993L),
            anonymousId = "a",
            sessionId = "s",
        )
        val restored = EventEntity.fromEvent(event).toEvent().properties
        assertEquals(2L, restored["qty"])
        assertEquals(9.5, restored["price"])
        assertEquals(9_007_199_254_740_993L, restored["big"])
        assertTrue(WireJson.gson.toJson(restored).contains("\"qty\":2,"))
    }

    /**
     * 1.4.0-beta.9 test: React Native hands every JS number to Android as a
     * Double, and Gson wrote 3.0 as `3.0` - on /track/batch and in a placement
     * re-check - where iOS and the web send `3`.
     */
    @Test
    fun `a whole-number Double goes on the wire as an integer, as on iOS and the web`() {
        val props: Map<String, Any?> = mapOf(
            "fromJs" to 3.0, "neg" to -2.0, "zero" to -0.0, "f" to 4.0f,
            "int" to 3, "long" to 9_000_000_000L,
            "frac" to 3.5, "small" to 0.1, "fracF" to 2.5f,
            "nested" to mapOf("q" to 1.0, "l" to listOf(2.0, 2.25)),
        )
        val event = Event(type = "x", properties = props, timestamp = Date(0), anonymousId = "a", sessionId = "s", eventId = "e")
        val wire = WireJson.gson.toJson(event)
        val expected = "\"properties\":{\"fromJs\":3,\"neg\":-2,\"zero\":0,\"f\":4,\"int\":3,\"long\":9000000000," +
            "\"frac\":3.5,\"small\":0.1,\"fracF\":2.5,\"nested\":{\"q\":1,\"l\":[2,2.25]}}"
        assertTrue(wire, wire.contains(expected))
        // Through the on-disk queue too (the /track/batch path).
        val back = WireJson.gson.toJson(EventEntity.fromEvent(event).toEvent())
        assertTrue(back, back.contains(expected))
        // A JsonElement tree (a placement re-check's recheckEvent) is the same.
        assertEquals("{\"n\":3,\"x\":3.5}", WireJson.gson.toJson(WireJson.gson.toJsonTree(mapOf("n" to 3.0, "x" to 3.5))))
        assertFalse(WireJson.isWireInteger(Double.NaN))
        assertFalse(WireJson.isWireInteger(Double.POSITIVE_INFINITY))
        assertFalse(WireJson.isWireInteger(9.007199254740994E15))
    }

    @Test
    fun `push tracking routes are built at the host root, not under api`() {
        // Fails on the old `${normalizedBaseUrl}track/push/...` (= /api/track/push).
        val base = NetworkClient.normalizeBaseUrl("https://api-eu1.joryio.com/api")
        assertEquals("https://api-eu1.joryio.com/api/", base)
        assertEquals("https://api-eu1.joryio.com/", NetworkClient.rootUrlOf(base))
        assertEquals(
            "https://api-eu1.joryio.com/track/push/click/abc",
            NetworkClient.pushTrackingUrl(base, "click", "abc"),
        )
        assertEquals(
            "http://10.0.2.2:3000/track/push/delivered/x%2Fy",
            NetworkClient.pushTrackingUrl(NetworkClient.normalizeBaseUrl("http://10.0.2.2:3000/api"), "delivered", "x/y"),
        )
        // Bare host: /api added for the API, still root for tracking.
        assertEquals("https://h.example/", NetworkClient.rootUrlOf(NetworkClient.normalizeBaseUrl("h.example")))
    }

    @Test
    fun `the user agent carries the real SDK version`() {
        // Fails on the old hard-coded "Joryio-Android/1.0.0".
        val ua = NetworkClient.userAgentFor("com.example.app", "14")
        assertEquals("Joryio-Android/${Logger.SDK_VERSION} (com.example.app; Android 14)", ua)
        assertFalse(ua.contains("1.0.0"))
    }
}
