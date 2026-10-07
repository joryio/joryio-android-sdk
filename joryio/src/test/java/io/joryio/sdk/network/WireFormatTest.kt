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
