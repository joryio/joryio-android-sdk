package io.joryio.sdk

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.push.JoryioPermissionActivity
import io.joryio.sdk.push.PushSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The SDK facade end to end: real Room, real SharedPreferences, real HTTP
 * against a local server. Each test says which bug it would catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JoryioFacadeTest {

    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        // The pre-init decision files hang off a static Context: point it at
        // THIS test's app, or a decision stored by the previous test class's
        // app (an opt-out, a denial) is read here and drops the pre-init calls.
        io.joryio.sdk.core.PreInitStore.appContext = app
        allowNetwork(app)
        warmUpSqlite()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    /** A fresh key per test: the encrypted stores are cached per file, and the file is per key. */
    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"

    private fun init(config: JoryioConfig = JoryioConfig(flushInterval = 60_000)): Joryio =
        Joryio.initialize(app, key(), api.apiHost, config)

    private fun bodiesOf(pathSuffix: String) = api.seen.filter { it.path.endsWith(pathSuffix) }.map { it.body }

    @Test
    fun `reset starts a new anonymous identity`() {
        // Fails before 1.3.0: the anonymous id survived reset().
        val sdk = init()
        val before = sdk.getAnonymousId()
        sdk.identify("user-1")
        sdk.reset()
        assertNotEquals(before, sdk.getAnonymousId())
        assertEquals(null, sdk.getUserId())
    }

    @Test
    fun `consent pending sends nothing, then optIn releases everything held`() {
        // Fails before 1.3.0: PENDING was ignored and everything was sent.
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.PENDING))
        sdk.track("Viewed Pricing")
        sdk.setAttribute("plan", "pro")
        sdk.addAlias("loyalty_id", "L-1")
        pumpFor(1_200)
        assertEquals(emptyList<String>(), api.paths())

        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").any { it.contains("Viewed Pricing") } })
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("\"plan\":\"pro\"") } })
        assertTrue(pumpUntil { bodiesOf("/v1/alias/add").isNotEmpty() })
        val events = bodiesOf("/v1/track/batch").joinToString()
        assertTrue("Session Start is released too", events.contains("Session Start"))
    }

    @Test
    fun `consent pending then optOut discards what was held`() {
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackingConsent = TrackingConsent.PENDING))
        sdk.track("A")
        sdk.addAlias("loyalty_id", "L-1")
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.optIn()
        pumpFor(1_200)
        assertTrue(bodiesOf("/v1/track/batch").none { it.contains("\"A\"") })
        assertTrue(bodiesOf("/v1/alias/add").isEmpty())
    }

    @Test
    fun `opt-out is enforced on every write path`() {
        // Fails before 1.3.0: increment, unset, subscriptions, impressions and
        // push registration all sent while opted out.
        val sdk = init(JoryioConfig(flushInterval = 60_000, optOut = true))
        sdk.incrementAttribute("logins", 1)
        sdk.unsetAttribute("plan")
        sdk.addAlias("loyalty_id", "L-1")
        sdk.setSubscription("email", "subscribed")
        sdk.trackInAppImpression("c1", "displayed")
        var result: io.joryio.sdk.push.PushRegistrationResult? = null
        sdk.registerPushToken("token-1") { result = it }
        sdk.reportPushDelivered("t1")
        sdk.trackPushClick("t2")
        sdk.track("E")
        pumpFor(1_200)
        // The one request: the opt-out RECORD on the profile, sent because the
        // switch changed at init (contract item 33). Nothing else.
        assertTrue(api.seen.all { it.path.endsWith("/v1/attributes") && it.body.contains("\"tracking_opted_out\":true") })
        assertEquals(false, result?.success)
    }

    @Test
    fun `addAlias works before identify`() {
        // Fails before 1.3.0: Android had no addAlias at all (and web refused anonymous).
        val sdk = init()
        sdk.addAlias("loyalty_id", "L-1")
        assertTrue(pumpUntil { bodiesOf("/api/v1/alias/add").isNotEmpty() })
        val body = bodiesOf("/api/v1/alias/add").first()
        assertTrue(body, body.contains("\"anonymousId\":\"${sdk.getAnonymousId()}\""))
        assertTrue(body, body.contains("\"aliasLabel\":\"loyalty_id\""))
        assertTrue(body, body.contains("\"aliasName\":\"L-1\""))
        assertFalse(body, body.contains("userId"))
    }

    @Test
    fun `calls made before a synchronous initialize are replayed`() {
        // Fails before 1.3.0: only initializeAsync drained the buffer.
        Joryio.track("Before Init")
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackSessionStart = false))
        // Replayed means queued: the event is on the device once init returns.
        assertTrue(pumpUntil(15_000) { sdk.getQueueSize() == 1 })
        sdk.flush()
        assertTrue(pumpUntil(15_000) { bodiesOf("/v1/track/batch").any { it.contains("Before Init") } })
    }

    @Test
    fun `push click and delivery receipts go to the host root`() {
        // Fails before 1.3.0: both went to /api/track/push/..., a 404 on production.
        val sdk = init()
        sdk.trackPushClick("click-1")
        sdk.reportPushDelivered("recv-1")
        assertTrue(pumpUntil { api.paths().contains("/track/push/click/click-1") })
        assertTrue(pumpUntil { api.paths().contains("/track/push/delivered/recv-1") })
        assertTrue(api.paths().none { it.startsWith("/api/track/push") })
    }

    @Test
    fun `every request carries the real SDK version`() {
        // Fails before 1.3.0: the header and the User-Agent said 1.0.0.
        init()
        assertTrue(pumpUntil { api.seen.isNotEmpty() })
        val headers = api.seen.first().headers
        assertEquals(Joryio.SDK_VERSION, headers["X-Joryio-SDK-Version"])
        assertTrue(headers["User-Agent"]!!.startsWith("Joryio-Android/${Joryio.SDK_VERSION}"))
    }

    @Test
    fun `the notification channel name comes from the config`() {
        // Fails before 1.3.0: hard-coded "Joryio Notifications".
        init(JoryioConfig(notificationChannelName = "Benachrichtigungen"))
        assertEquals("Benachrichtigungen", PushSettings.channelName(app))
    }

    @Test
    fun `an Activity passed to initialize hosts the launch prompt, and a plain Activity gets a real dialog`() {
        // Fails before 1.3.0 twice over: the Activity was never seeded (no
        // prompt until the next screen), and a plain android.app.Activity got
        // ActivityCompat.requestPermissions plus an immediate DENIED.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Joryio.initialize(activity, key(), api.apiHost, JoryioConfig(requestPushPermissionAtLaunch = true))
        pumpFor(300)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(JoryioPermissionActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun `public calls tolerate nulls from Java callers`() {
        val sdk = init()
        sdk.track(null, null)
        sdk.identify(null)
        sdk.setAttribute(null, null)
        sdk.addAlias(null, "x")
        sdk.trackInAppImpression(null, null, null)
        sdk.registerPushToken(null)
        sdk.requestPushPermission(null)
        Joryio.whenReady(null)
        pumpFor(200)
    }

    @Test
    fun `a null apiHost starts the SDK in no-send mode instead of crashing`() {
        val sdk = Joryio.initialize(app, key(), null)
        sdk.track("x")
        pumpFor(300)
        assertEquals(emptyList<String>(), api.paths())
    }
}
