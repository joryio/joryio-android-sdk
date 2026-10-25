package io.joryio.sdk

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.models.InAppCampaign
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * holdInAppMessagesForHandler (SDK 1.3.2). An app that draws in-app messages
 * itself sets its callback AFTER initialize(); the sync initialize() starts
 * could finish first, and the SDK drew that message with its own views - the
 * React Native bug found testing 1.3.1-beta.2 ("Displaying campaign" in
 * logcat before the JS subscription took effect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InAppHoldForHandlerTest {

    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[{"id":"c1","name":"One","type":"modal"}]}""")
        }
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun init(hold: Boolean): Joryio = Joryio.initialize(
        app,
        key(),
        api.apiHost,
        JoryioConfig(flushInterval = 60_000, trackSessionStart = false, holdInAppMessagesForHandler = hold),
    )

    private fun recordingPresenter(sdk: Joryio, drawn: MutableList<String>) {
        sdk.setInAppPresenter(object : InAppMessagePresenter {
            override fun present(activity: Activity, campaign: InAppCampaign, onEvent: (String, Map<String, Any?>) -> Unit): Boolean {
                drawn.add(campaign.id)
                return true
            }
        })
    }

    @Test
    fun `without the option a message ready before the callback is drawn by the SDK - the race`() {
        // The behaviour the option exists for: documents the race it closes.
        val sdk = init(hold = false)
        val drawn = mutableListOf<String>()
        recordingPresenter(sdk, drawn)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { drawn.contains("c1") })
    }

    @Test
    fun `with the option the SDK draws nothing and the held message reaches the callback set later`() {
        val sdk = init(hold = true)
        val drawn = mutableListOf<String>()
        recordingPresenter(sdk, drawn)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { sdk.heldForHandlerIdsForTesting() == listOf("c1") })
        pumpFor(300)
        assertTrue("the SDK must not draw a held message", drawn.isEmpty())

        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        assertTrue(pumpUntil { handed == listOf("c1") })
        assertTrue(sdk.heldForHandlerIdsForTesting().isEmpty())
        assertTrue(drawn.isEmpty())
    }

    @Test
    fun `clearing instead of setting ends the hold and the SDK draws the held message`() {
        // React Native does this when the app has no onInAppMessage listener
        // once initialize() has completed: nothing is lost either way.
        val sdk = init(hold = true)
        val drawn = mutableListOf<String>()
        recordingPresenter(sdk, drawn)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { sdk.heldForHandlerIdsForTesting() == listOf("c1") })

        sdk.clearInAppMessageCallback()
        assertTrue(pumpUntil { drawn == listOf("c1") })
        assertTrue(sdk.heldForHandlerIdsForTesting().isEmpty())
    }

    @Test
    fun `the hold ends at the first set - a later clear hands rendering back as before`() {
        val sdk = init(hold = true)
        val drawn = mutableListOf<String>()
        recordingPresenter(sdk, drawn)
        Robolectric.buildActivity(Activity::class.java).setup()
        sdk.setInAppMessageCallback({ _: InAppCampaign -> })
        sdk.clearInAppMessageCallback()
        pumpFor(500)
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","delayBetweenCampaigns":0,"campaigns":[{"id":"c2","name":"Two","type":"modal"}]}""")
        }
        sdk.syncInAppCampaigns()
        assertTrue("a cleared callback means the SDK draws again", pumpUntil { drawn.contains("c2") })
        assertTrue(sdk.heldForHandlerIdsForTesting().isEmpty())
    }

    @Test
    fun `reset drops a held message - it belonged to the previous person`() {
        val sdk = init(hold = true)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { sdk.heldForHandlerIdsForTesting() == listOf("c1") })
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[]}""")
        }
        sdk.reset()
        pumpFor(300)
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        pumpFor(500)
        assertEquals(emptyList<String>(), handed)
    }

    @Test
    fun `a message held for user A is never handed to user B after identify`() {
        val sdk = init(hold = true)
        sdk.identify("user-a")
        pumpFor(300)
        sdk.syncInAppCampaigns()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { sdk.heldForHandlerIdsForTesting() == listOf("c1") })
        // B's sync has nothing for B.
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[]}""")
        }
        sdk.identify("user-b")
        assertTrue("identify(B) drops what was held for A", sdk.heldForHandlerIdsForTesting().isEmpty())
        pumpFor(500)
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        pumpFor(500)
        assertEquals(emptyList<String>(), handed)
    }

    @Test
    fun `opting out drops a held message`() {
        val sdk = init(hold = true)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { sdk.heldForHandlerIdsForTesting() == listOf("c1") })
        sdk.optOut()
        assertTrue(sdk.heldForHandlerIdsForTesting().isEmpty())
        val handed = mutableListOf<String>()
        sdk.setInAppMessageCallback({ c: InAppCampaign -> handed.add(c.id) })
        pumpFor(500)
        assertEquals(emptyList<String>(), handed)
    }
}
