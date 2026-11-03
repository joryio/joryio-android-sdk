package io.joryio.sdk

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.messaging.IN_APP_DISPLAY_DWELL_MS
import io.joryio.sdk.models.InAppCampaign
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

/**
 * An in-app message counts as displayed only after it has stayed on a resumed
 * screen for IN_APP_DISPLAY_DWELL_MS. Production, web, 2026-11-02/03: a
 * modal was served and reported displayed in the same second the page was
 * navigating away (a sign-in redirect, a logout); both counted, the cap of 2
 * was spent, and the person never saw a usable message. Same rule here
 * (BACKLOG D320).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InAppDisplayDwellTest {

    private lateinit var api: TestServer
    private lateinit var app: Application

    /** What the fake presenter was handed, per draw. */
    private class Drawn(val campaignId: String, val activity: Activity, val onEvent: (String, Map<String, Any?>) -> Unit)

    private val drawn = mutableListOf<Drawn>()

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
        api.answers["/v1/in-app/sync"] = {
            TestServer.json(
                """{"success":true,"evaluatedAt":"x","delayBetweenCampaigns":0,"campaigns":[
                {"id":"c1","name":"One","type":"modal","triggers":[{"type":"immediate"}],
                 "frequencyCap":{"maxImpressions":2,"timeWindow":"7d"}}]}""",
            )
        }
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    private fun init(): Joryio {
        val sdk = Joryio.initialize(
            app,
            "jry_sdk_android_test_${UUID.randomUUID()}",
            api.apiHost,
            JoryioConfig(flushInterval = 60_000, trackSessionStart = false),
        )
        sdk.setInAppPresenter(object : InAppMessagePresenter {
            override fun present(activity: Activity, campaign: InAppCampaign, onEvent: (String, Map<String, Any?>) -> Unit): Boolean {
                drawn.add(Drawn(campaign.id, activity, onEvent))
                onEvent("displayed", emptyMap())
                return true
            }
        })
        return sdk
    }

    private fun tracked(action: String): Int =
        api.seen.count { it.path.endsWith("/v1/in-app/track") && it.body.contains("\"action\":\"$action\"") }

    private fun trackedActions(): List<String> =
        api.seen.filter { it.path.endsWith("/v1/in-app/track") }
            .mapNotNull { Regex("\"action\":\"(\\w+)\"").find(it.body)?.groupValues?.get(1) }

    /** Advance the main looper's clock exactly, without the wall clock pumpUntil follows. */
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `the dwell is one second, under the same name as every SDK`() {
        assertEquals(1_000L, IN_APP_DISPLAY_DWELL_MS)
    }

    @Test
    fun `on screen for the dwell - displayed is reported once and the cap spent once`() {
        val sdk = init()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        assertEquals(listOf("c1"), sdk.pendingDisplayIdsForTesting())

        advance(IN_APP_DISPLAY_DWELL_MS - 50)
        // No pumpFor here: it advances the looper by the wall clock and would
        // cross the dwell. A report needs the timer to have fired anyway.
        assertEquals("not yet: the dwell has not elapsed", 0, tracked("displayed"))
        assertEquals(0, sdk.inAppImpressionCountForTesting("c1"))

        advance(IN_APP_DISPLAY_DWELL_MS)
        assertTrue(pumpUntil { tracked("displayed") == 1 })
        pumpFor(300)
        assertEquals(1, tracked("displayed"))
        assertEquals(1, sdk.inAppImpressionCountForTesting("c1"))
        assertTrue(sdk.pendingDisplayIdsForTesting().isEmpty())
    }

    @Test
    fun `a screen that is finishing is never drawn on - the message waits for the next one`() {
        // The sync answers only once the screen is already finishing (a redirect).
        val answer = api.answers["/v1/in-app/sync"]!!
        val release = java.util.concurrent.CountDownLatch(1)
        api.answers["/v1/in-app/sync"] = { release.await(); answer() }
        val sdk = init()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        first.get().finish()
        release.countDown()
        pumpFor(800)
        assertTrue("nothing drawn on a finishing screen", drawn.none { it.activity === first.get() })
        assertEquals(0, tracked("displayed"))
        assertEquals(0, sdk.inAppImpressionCountForTesting("c1"))

        first.pause().stop().destroy()
        val next = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue("offered on the next screen", pumpUntil { drawn.any { it.activity === next.get() } })
    }

    @Test
    fun `the screen closes before the dwell - no displayed, no cap, shown again on the next screen`() {
        val sdk = init()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })

        advance(IN_APP_DISPLAY_DWELL_MS / 2)
        // A logout: the screen finishes half-way through the dwell.
        first.get().finish()
        first.pause().stop().destroy()
        advance(IN_APP_DISPLAY_DWELL_MS * 2)
        pumpFor(300)
        assertEquals("closed before the dwell is not a display", 0, tracked("displayed"))
        assertEquals("and spends no cap", 0, sdk.inAppImpressionCountForTesting("c1"))
        assertTrue(sdk.pendingDisplayIdsForTesting().isEmpty())

        val next = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue("still eligible: offered on the next screen", pumpUntil { drawn.size == 2 && drawn[1].activity === next.get() })
        advance(IN_APP_DISPLAY_DWELL_MS)
        assertTrue(pumpUntil { tracked("displayed") == 1 })
        assertEquals(1, sdk.inAppImpressionCountForTesting("c1"))
    }

    @Test
    fun `paused before the dwell - nothing counts while away, the dwell restarts in full on resume`() {
        val sdk = init()
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        advance(IN_APP_DISPLAY_DWELL_MS - 100)
        screen.pause()
        advance(IN_APP_DISPLAY_DWELL_MS * 5)
        pumpFor(200)
        assertEquals(0, tracked("displayed"))
        assertEquals(0, sdk.inAppImpressionCountForTesting("c1"))

        screen.resume()
        advance(IN_APP_DISPLAY_DWELL_MS - 100)
        assertEquals("the dwell starts again from zero", 0, tracked("displayed"))
        advance(200)
        assertTrue(pumpUntil { tracked("displayed") == 1 })
        assertEquals(1, sdk.inAppImpressionCountForTesting("c1"))
    }

    @Test
    fun `removed without the person acting before the dwell - not counted`() {
        val sdk = init()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        drawn[0].onEvent(InAppMessagePresenter.EVENT_HIDDEN, emptyMap())
        advance(IN_APP_DISPLAY_DWELL_MS * 2)
        pumpFor(300)
        assertEquals(0, tracked("displayed"))
        assertEquals(0, sdk.inAppImpressionCountForTesting("c1"))
        assertTrue(api.seen.none { it.body.contains("\"action\":\"hidden\"") })
    }

    @Test
    fun `a dismissal before the dwell counts - displayed then dismissed, once each`() {
        val sdk = init()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        advance(200)
        drawn[0].onEvent("dismissed", mapOf("reason" to "close_button"))
        assertTrue(pumpUntil { tracked("dismissed") == 1 })
        advance(IN_APP_DISPLAY_DWELL_MS * 2)
        pumpFor(300)
        assertEquals(listOf("displayed", "dismissed"), trackedActions())
        assertEquals(1, sdk.inAppImpressionCountForTesting("c1"))
    }

    @Test
    fun `a click before the dwell counts - displayed then clicked, and the timer adds nothing`() {
        val sdk = init()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        drawn[0].onEvent("clicked", mapOf("action" to "cta"))
        assertTrue(pumpUntil { tracked("clicked") == 1 })
        advance(IN_APP_DISPLAY_DWELL_MS * 2)
        pumpFor(300)
        assertEquals(listOf("displayed", "clicked"), trackedActions())
        assertEquals(1, sdk.inAppImpressionCountForTesting("c1"))
    }

    @Test
    fun `reset before the dwell - nothing is reported for the previous person`() {
        val sdk = init()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.size == 1 })
        api.answers["/v1/in-app/sync"] = { TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[]}""") }
        sdk.reset()
        advance(IN_APP_DISPLAY_DWELL_MS * 2)
        pumpFor(300)
        drawn[0].onEvent("dismissed", mapOf("reason" to "close_button"))
        pumpFor(300)
        assertEquals(emptyList<String>(), trackedActions())
    }
}
