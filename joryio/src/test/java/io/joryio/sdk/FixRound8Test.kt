package io.joryio.sdk

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.SdkAuthenticator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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
 * Fix round 8 (contract v8 81-85, 88). The scan 8 probes (P2c-P5), lifted as
 * regression tests, plus the tests of the other round-8 fixes. Unless marked
 * as a control, each fails on dc70d92d9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound8Test {
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

    private fun key() = "jry_sdk_android_test_${UUID.randomUUID()}"
    private fun cfg(
        sdkAuth: Boolean = false,
        userId: String? = null,
        sessionStart: Boolean = false,
        consent: TrackingConsent? = null,
    ) = JoryioConfig(
        flushInterval = 60_000,
        maxRetries = 0,
        trackSessionStart = sessionStart,
        enableSdkAuthentication = sdkAuth,
        userId = userId,
        trackingConsent = consent,
    )

    private fun jwt(sub: String): String {
        fun b64(s: String) = android.util.Base64.encodeToString(
            s.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        return "${b64("""{"alg":"HS256"}""")}.${b64("""{"sub":"$sub"}""")}.sig"
    }

    private fun authError(reason: String) =
        TestServer.json("""{"error":"sdk_authentication_error","reason":"$reason"}""", code = 401)

    private fun bodiesOf(p: String) = api.seen.filter { it.path.endsWith(p) }.map { it.body }

    private fun clientOf(sdk: Joryio): NetworkClient =
        Joryio::class.java.getDeclaredField("networkClient").apply { isAccessible = true }.get(sdk) as NetworkClient

    private fun authOf(sdk: Joryio): SdkAuthenticator {
        val client = clientOf(sdk)
        return client.javaClass.getDeclaredField("sdkAuthenticator").apply { isAccessible = true }.get(client) as SdkAuthenticator
    }

    // ── S1 / rule 84: the static setters are pre-init aware ──────────────
    // The React Native bridge's whenReady path (scan 8 P1/P2) is the RN
    // agent's fix; these pin what the bridge now relies on. Controls: they
    // pass on dc70d92d9 too.

    @Test
    fun `control - a token set with the static setter before init is applied by the constructor`() {
        Joryio.setSdkAuthenticationToken(jwt("user-u"))
        var sdk: Joryio? = null
        val t = Thread { sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true, userId = "user-u")) }
        t.start()
        t.join()
        assertEquals(jwt("user-u"), authOf(sdk!!).currentToken())
    }

    @Test
    fun `control - the static token survives a pre-init wipe and goes with the erased user's unsubscribe`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(sdkAuth = true))
        first.setSdkAuthenticationToken(jwt("user-a"))
        first.identify("user-a")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        api.answers["/v1/subscriptions/channel"] = {
            val last = api.seen.last { it.path.endsWith("/v1/subscriptions/channel") }
            if (last.headers["X-Joryio-Auth"] == null) authError("missing") else TestServer.json("""{"success":true}""")
        }
        Joryio.setSdkAuthenticationToken(jwt("user-a"))
        Joryio.setSubscription("email", "unsubscribed")
        Joryio.wipeData()
        Joryio.initialize(app, k, api.apiHost, cfg(sdkAuth = true))
        assertTrue(
            pumpUntil {
                api.seen.any {
                    it.path.endsWith("/v1/subscriptions/channel") && it.body.contains("unsubscribed") &&
                        it.headers["X-Joryio-Auth"] == jwt("user-a")
                }
            },
        )
    }

    // ── S2 / rule 83: a pre-init refusal purges the data calls before it ─

    @Test
    fun `P3 an event buffered before a pre-init optOut is not sent after a later optIn`() {
        Joryio.track("made-before-opt-out")
        Joryio.setAttribute("plan", "before-opt-out")
        Joryio.optOut()
        Joryio.optIn()
        Joryio.track("made-after-opt-in")
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("made-after-opt-in") })
        pumpFor(1_200)
        val all = api.seen.joinToString("\n") { it.body }
        assertFalse("collected before an opt-out, sent after the opt-in: $all", all.contains("made-before-opt-out"))
        assertFalse("an attribute set before the opt-out was sent: $all", all.contains("before-opt-out\""))
    }

    @Test
    fun `an event buffered before a pre-init denial is not sent after a later grant`() {
        Joryio.track("made-before-denial")
        Joryio.updateConsent(TrackingConsent.DENIED)
        Joryio.updateConsent(TrackingConsent.GRANTED)
        Joryio.track("made-after-grant")
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("made-after-grant") })
        pumpFor(800)
        assertFalse(bodiesOf("/v1/track/batch").joinToString().contains("made-before-denial"))
    }

    @Test
    fun `control - the purge keeps a withdrawal and identify buffered before the opt-out`() {
        Joryio.identify("user-w")
        Joryio.setSubscription("email", "unsubscribed")
        Joryio.optOut()
        Joryio.initialize(app, key(), api.apiHost, cfg())
        assertTrue(
            pumpUntil {
                bodiesOf("/v1/subscriptions/channel").any { it.contains("unsubscribed") && it.contains("user-w") }
            },
        )
    }

    // ── NICE 1 / rule 82: wipeData drops standby tokens ──────────────────

    @Test
    fun `P4 wipeData keeps no SDK-auth token for a next user set before the erasure`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        sdk.setSdkAuthenticationToken(jwt("user-a"))
        sdk.identify("user-a")
        sdk.setSdkAuthenticationToken(jwt("user-b")) // B's token, before A's erasure
        sdk.wipeData()
        pumpFor(300)
        val kept = authOf(sdk).retiredUserIds()
        assertFalse("user-b's token survives the erasure: $kept", "user-b" in kept)
        assertTrue("the erased user's own token stays with their withdrawals: $kept", "user-a" in kept)
    }

    @Test
    fun `a held user's own token survives wipeData for their withdrawals`() {
        // identify() while opted out holds the id for withdrawals only (D-b);
        // the token set for that held user is theirs, not a next user's
        // standby, so the erasure must keep it for their queued withdrawals
        // (same on iOS and web).
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        sdk.optOut()
        sdk.identify("user-h")
        sdk.setSdkAuthenticationToken(jwt("user-h"))
        sdk.wipeData()
        pumpFor(300)
        val kept = authOf(sdk).retiredUserIds()
        assertTrue("the held user's token stays with their withdrawals: $kept", "user-h" in kept)
    }

    // ── NICE 2 / rule 82: standby while anonymous ────────────────────────

    @Test
    fun `P5 a token set for the next user while anonymous survives reset until identify`() {
        var now = 1_000_000L
        val auth = SdkAuthenticator(enabled = true, initialToken = null, clock = { now })
        auth.setToken(jwt("user-b"))
        auth.retireToken(null) // reset() of an anonymous visitor
        now += 2 * 60 * 1000L
        auth.prune(emptySet())
        auth.adoptRetired("user-b")
        assertEquals(jwt("user-b"), auth.tokenFor("user-b", "user-b"))
    }

    @Test
    fun `control - the outgoing user's own token is not standby and is pruned once nothing is queued`() {
        var now = 1_000_000L
        val auth = SdkAuthenticator(enabled = true, initialToken = null, clock = { now })
        auth.setToken(jwt("user-a"))
        auth.retireToken("user-a")
        now += 2 * 60 * 1000L
        auth.prune(emptySet())
        assertTrue(auth.retiredUserIds().isEmpty())
    }

    // ── iOS S1 equivalent / rule 81: adoptRetired switches SDK auth on ───

    @Test
    fun `adopting a kept token at identify after a restart keeps SDK authentication on`() {
        // A launch whose config does not enable SDK auth; the app turned it on
        // with setSdkAuthenticationToken() last launch, and reset() kept the
        // token for user-a.
        val kept = mapOf("user-a" to SdkAuthenticator.Retired(jwt("user-a"), System.currentTimeMillis(), standby = true))
        val auth = SdkAuthenticator(enabled = false, initialToken = null)
        auth.attachStore(object : SdkAuthenticator.Store {
            override fun load() = kept
            override fun save(tokens: Map<String, SdkAuthenticator.Retired>) {}
        })
        assertTrue(auth.isFeatureOn)
        auth.adoptRetired("user-a")
        assertTrue("identify() switched SDK auth off", auth.isFeatureOn)
        assertTrue(auth.isEnabled)
        assertEquals(jwt("user-a"), auth.tokenFor("user-a", "user-a"))
    }

    @Test
    fun `after a restart identify adopts the kept token and requests still carry it`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg())
        first.setSdkAuthenticationToken(jwt("user-b")) // the next user's, set while anonymous
        first.reset()
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        val second = Joryio.initialize(app, k, api.apiHost, cfg())
        second.identify("user-b")
        second.track("after-adopt")
        second.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("after-adopt") })
        val batch = api.seen.last { it.path.endsWith("/v1/track/batch") && it.body.contains("after-adopt") }
        assertEquals(jwt("user-b"), batch.headers["X-Joryio-Auth"])
    }

    // ── Rule 85: Session Start owed by a blocked launch ──────────────────

    @Test
    fun `a session started while consent was denied is announced by the next allowed launch`() {
        val k = key()
        Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true, consent = TrackingConsent.DENIED))
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        val second = Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true, consent = TrackingConsent.GRANTED))
        second.track("After")
        second.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("\"After\"") })
        val all = bodiesOf("/v1/track/batch").joinToString()
        assertTrue("no Session Start for the resumed, never-announced session: $all", all.indexOf("Session Start") in 0 until all.indexOf("\"After\""))
    }

    @Test
    fun `a session whose Session Start an opt-out discarded is announced after a pre-init optIn next launch`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true))
        first.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("Session Start") })
        first.optOut()
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        Joryio.optIn()
        val second = Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true))
        second.track("Back")
        second.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("\"Back\"") })
        val all = bodiesOf("/v1/track/batch").joinToString()
        assertTrue(all, all.indexOf("Session Start") in 0 until all.indexOf("\"Back\""))
    }

    @Test
    fun `control - an allowed launch that resumes an announced session sends no second Session Start`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true))
        first.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("Session Start") })
        Joryio.resetForTesting()
        api.seen.clear()
        val second = Joryio.initialize(app, k, api.apiHost, cfg(sessionStart = true))
        second.track("Again")
        second.flush()
        assertTrue(pumpUntil { bodiesOf("/v1/track/batch").joinToString().contains("\"Again\"") })
        assertFalse(bodiesOf("/v1/track/batch").joinToString().contains("Session Start"))
    }

    // ── Rule 72 / 82: the push-click beacon carries only a sub-matching token
    // (iOS scan 8 N3). Control on Android: the bodyless click is routed as
    // the current user's request and gets a token only when its sub matches.

    @Test
    fun `control - the push-click beacon never carries another user's token`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        sdk.identify("user-a")
        sdk.setSdkAuthenticationToken(jwt("user-b")) // the next user's, before identify(B)
        runBlocking { clientOf(sdk).trackPushClick("t1") }
        val first = api.seen.last { it.path.contains("/track/push/click/t1") }
        assertNull("user-b's token on user-a's click", first.headers["X-Joryio-Auth"])
        sdk.setSdkAuthenticationToken(jwt("user-a"))
        runBlocking { clientOf(sdk).trackPushClick("t2") }
        val second = api.seen.last { it.path.contains("/track/push/click/t2") }
        assertEquals(jwt("user-a"), second.headers["X-Joryio-Auth"])
    }

    // ── NICE 4: the test server's teardown ───────────────────────────────

    @Test
    fun `the test server shuts down without throwing while a request is still in flight`() {
        val slow = TestServer()
        slow.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(6_500)
                return MockResponse().setBody("{}")
            }
        }
        val caller = Thread {
            try {
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(slow.server.url("/slow")).build()).execute().close()
            } catch (e: Exception) {
                // The server going away under it is fine.
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        slow.shutdown() // threw "Gave up waiting for queue to shut down" after 5 s
        caller.join(10_000)
    }
}
