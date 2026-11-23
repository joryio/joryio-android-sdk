package io.joryio.sdk.cards

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.Joryio
import io.joryio.sdk.JoryioConfig
import io.joryio.sdk.JoryioSubscription
import io.joryio.sdk.TestServer
import io.joryio.sdk.allowNetwork
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.pumpFor
import io.joryio.sdk.pumpUntil
import io.joryio.sdk.warmUpSqlite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The card API on the Joryio TYPE (the companion), like iOS's Joryio.shared
 * and the web / React Native / Unity data API (BACKLOG D444, 1.4.0-beta.5):
 * the reports, the unread count and onCardsUpdated are there too, and
 * onCardsUpdated answers the one JoryioSubscription every listener of every
 * SDK returns (onPlacementsUpdated and onFlagsUpdated too).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardStaticShortcutsTest {
    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
        PreInitStore.appContext = app
        app.getSharedPreferences("joryio_decisions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        api.shutdown()
    }

    private fun cfg() = JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false)

    @Test
    fun `before initialize - no unread cards, reports are held without throwing, and onCardsUpdated registers once initialized`() {
        assertEquals(0, Joryio.getUnreadCardCount())
        // Held until initialize() like every other pre-init data call.
        Joryio.markCardViewed("card-x")
        Joryio.markCardRead("card-x")
        Joryio.markCardClicked("card-x")
        Joryio.dismissCard("card-x")

        val heard = AtomicInteger()
        val cancelledEarly = AtomicInteger()
        val sub: JoryioSubscription = Joryio.onCardsUpdated { heard.incrementAndGet() }
        val early: JoryioSubscription = Joryio.onCardsUpdated { cancelledEarly.incrementAndGet() }
        early.cancel()
        early.cancel() // twice is harmless

        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        pumpFor(300)
        // An identity change tells the listeners (the feed of the person before is gone).
        sdk.reset()
        assertTrue(pumpUntil(5_000) { heard.get() > 0 })
        assertEquals("a subscription cancelled before initialize() never registers", 0, cancelledEarly.get())

        val after = heard.get()
        sub.close() // Closeable: close() is cancel()
        sdk.reset()
        pumpFor(500)
        assertEquals("a cancelled subscription hears nothing more", after, heard.get())
    }

    @Test
    fun `onPlacementsUpdated and onFlagsUpdated on the Joryio type answer a JoryioSubscription, and one cancelled before initialize never registers`() {
        val placements = AtomicInteger()
        val flags = AtomicInteger()
        val p: JoryioSubscription = Joryio.onPlacementsUpdated { placements.incrementAndGet() }
        val f: JoryioSubscription = Joryio.onFlagsUpdated { flags.incrementAndGet() }
        p.cancel()
        f.close() // Closeable: close() is cancel()
        p.cancel() // twice is harmless
        val sdk = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())
        pumpFor(300)
        val live: JoryioSubscription = sdk.onFlagsUpdated { }
        live.cancel()
        live.cancel()
        sdk.reset()
        pumpFor(500)
        assertEquals(0, placements.get())
        assertEquals(0, flags.get())
    }
}
