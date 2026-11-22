package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.joryio.sdk.core.IdentityManager
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.SessionManager
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.messaging.InAppMessagingManager
import io.joryio.sdk.messaging.ScreenClock
import io.joryio.sdk.models.InAppCampaign
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.queue.JoryioDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * `time_on_screen` in-app triggers (Joryio BACKLOG D444 follow-up): the app
 * equivalent of the web's time_on_page. Contract:
 * packages/backend/src/common/in-app-screen-triggers.ts. Mirrors iOS
 * ScreenTriggerTests.swift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenTriggerTest {

    private lateinit var api: TestServer
    private lateinit var app: Application
    private val logger = Logger(false, LogLevel.ERROR)
    private val key = "jry_sdk_android_test_${UUID.randomUUID()}"
    private var nowMs = 1_000_000_000L

    @Before
    fun setUp() {
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        JoryioDatabase.clearInstance()
    }

    @After
    fun tearDown() {
        api.shutdown()
        JoryioDatabase.clearInstance()
    }

    private fun manager(): InAppMessagingManager {
        val storage = StorageManager(app, key, logger)
        return InAppMessagingManager(
            networkClient = NetworkClient(context = app, sdkKey = key, apiHost = api.apiHost, logger = logger, maxRetries = 0),
            identityManager = IdentityManager(storage, logger),
            sessionManager = SessionManager(storage, logger),
            storage = storage,
            logger = logger,
        ).also {
            it.screenClock = ScreenClock(now = { nowMs })
            // Nothing is drawn in these tests: the trigger decision is what is checked.
            it.canSend = { false }
        }
    }

    private fun advance(seconds: Double) {
        nowMs += (seconds * 1000).toLong()
    }

    private fun campaign(id: String, value: String, screen: String? = null): InAppCampaign {
        val name = screen?.let { ""","screenName":"$it"""" } ?: ""
        return Gson().fromJson(
            """{"id":"$id","name":"$id","type":"modal","priority":1,"triggers":[{"type":"time_on_screen","value":$value$name}]}""",
            InAppCampaign::class.java,
        )
    }

    @Test
    fun `screen names match trimmed and case-insensitive, and no name means any screen`() {
        assertTrue(ScreenClock.screenNameMatches(null, null))
        assertTrue(ScreenClock.screenNameMatches("  ", "Home"))
        assertTrue(ScreenClock.screenNameMatches("Checkout", " checkout "))
        assertFalse(ScreenClock.screenNameMatches("Checkout", "CheckoutConfirm"))
        assertFalse(ScreenClock.screenNameMatches("Checkout", null))
    }

    @Test
    fun `the clock restarts on a new screen only, and background time does not count`() {
        val clock = ScreenClock(now = { nowMs })
        assertTrue(clock.enter("Home"))
        advance(10.0)
        assertFalse("the same screen again is not a new visit", clock.enter("home"))
        assertEquals(10.0, clock.seconds, 0.001)
        clock.pause()
        advance(100.0)
        assertEquals(10.0, clock.seconds, 0.001)
        clock.resume()
        advance(5.0)
        assertEquals("resumed, not restarted", 15.0, clock.seconds, 0.001)
        assertTrue(clock.enter("Cart"))
        assertEquals(0.0, clock.seconds, 0.001)
        assertEquals("Cart", clock.name)
    }

    @Test
    fun `it fires on the named screen after the seconds, once per visit`() {
        val m = manager()
        m.setCampaignsForTesting(listOf(campaign("c1", "30", "Checkout")))

        m.onScreenViewed("Home")
        advance(60.0)
        assertEquals("another screen", emptyList<String>(), m.screenCheckDue())
        assertNull(m.nextScreenDeadlineMs())

        m.onScreenViewed("Checkout")
        assertEquals(30_000L, m.nextScreenDeadlineMs())
        advance(29.0)
        assertEquals(emptyList<String>(), m.screenCheckDue())
        advance(1.0)
        assertEquals(listOf("c1"), m.screenCheckDue())
        advance(60.0)
        assertEquals("once per visit", emptyList<String>(), m.screenCheckDue())

        m.onScreenViewed("Home")
        m.onScreenViewed("Checkout")
        advance(30.0)
        assertEquals("a new visit re-arms it", listOf("c1"), m.screenCheckDue())
    }

    @Test
    fun `without a screen name any screen counts, and the background pauses it`() {
        val m = manager()
        m.setCampaignsForTesting(listOf(campaign("c1", "\"10\"")))
        advance(4.0)
        m.onBackground()
        advance(600.0)
        assertNull("nothing is scheduled in the background", m.nextScreenDeadlineMs())
        assertEquals(emptyList<String>(), m.screenCheckDue())
        m.onForeground()
        assertEquals("time before the first trackScreen counts for any screen", 6_000L, m.nextScreenDeadlineMs())
        advance(6.0)
        assertEquals(listOf("c1"), m.screenCheckDue())
    }

    @Test
    fun `unusable seconds never fire`() {
        val m = manager()
        m.setCampaignsForTesting(listOf(campaign("zero", "0"), campaign("huge", "99999"), campaign("text", "\"soon\"")))
        advance(100_000.0)
        assertEquals(emptyList<String>(), m.screenCheckDue())
    }

    @Test
    fun `sync declares screen_context, also when a host draws`() = runBlocking {
        assertEquals(listOf("content.native", "screen_context"), ScreenClock.withCapability(listOf("content.native")))
        assertEquals(listOf("screen_context"), ScreenClock.withCapability(listOf("screen_context")))
        assertEquals("time_on_screen", ScreenClock.TRIGGER_TYPE)
        assertEquals("screen_context", ScreenClock.CAPABILITY)

        val m = manager()
        m.canSend = { true }
        m.hostCapabilities = listOf("content.native")
        api.answers["/v1/in-app/sync"] = { TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[]}""") }
        m.sync()
        val body = api.seen.last { it.path.endsWith("/v1/in-app/sync") }.body
        assertTrue(body, body.contains("\"capabilities\":[\"content.native\",\"screen_context\"]"))
    }
}
