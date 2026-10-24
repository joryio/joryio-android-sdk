package io.joryio.sdk

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.network.SdkAuthenticator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Fix round 9 (scan 9 probes P1, P1c, P3 lifted as regression tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound9Test {
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
    private fun cfg() = JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false, enableSdkAuthentication = true)

    private fun jwt(sub: String): String {
        fun b64(s: String) = android.util.Base64.encodeToString(
            s.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        return "${b64("""{"alg":"HS256"}""")}.${b64("""{"sub":"$sub"}""")}.sig"
    }

    private fun authOf(sdk: Joryio): SdkAuthenticator {
        val client = Joryio::class.java.getDeclaredField("networkClient").apply { isAccessible = true }.get(sdk) as NetworkClient
        return client.javaClass.getDeclaredField("sdkAuthenticator").apply { isAccessible = true }.get(client) as SdkAuthenticator
    }

    @Volatile private var sentBeforeWipe = 0
    private fun afterWipe() = api.seen.drop(sentBeforeWipe)

    private fun scenario(holdB: Boolean): Pair<Joryio, AtomicInteger> {
        val phase = AtomicInteger(0)
        api.answers["/v1/subscriptions/channel"] = {
            val last = api.seen.last { it.path.endsWith("/v1/subscriptions/channel") }
            when {
                phase.get() == 0 -> TestServer.json("""{"error":"down"}""", code = 503)
                last.headers["X-Joryio-Auth"] == null ->
                    TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
                else -> TestServer.json("""{"success":true}""")
            }
        }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.setSdkAuthenticationToken(jwt("user-a"))
        sdk.identify("user-a")
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/identify") } })
        // A's unsubscribe; the server is down, so it stays in the outbox.
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/subscriptions/channel") } })
        sdk.updateConsent(TrackingConsent.DENIED)
        // An account switch while denied: B is held for withdrawals only (D-b).
        if (holdB) sdk.identify("user-b")
        phase.set(1)
        sentBeforeWipe = api.seen.size
        sdk.wipeData()
        return sdk to phase
    }

    // P1: the identified user's token is filed as a STANDBY token (keyed
    // against the held id) and wipeData() drops it; A's unsubscribe then
    // goes out with no token, gets 401 missing and A is no longer current.
    @Test
    fun `P1 wipe while a different user is held keeps the identified user's token for their unsubscribe`() {
        val (sdk, _) = scenario(holdB = true)
        pumpFor(300)
        val kept = authOf(sdk).retiredUserIds()
        val delivered = pumpUntil(8_000) {
            afterWipe().any {
                it.path.endsWith("/v1/subscriptions/channel") && it.body.contains("unsubscribed") &&
                    it.headers["X-Joryio-Auth"] == jwt("user-a")
            }
        }
        val log = api.seen.filter { it.path.endsWith("/v1/subscriptions/channel") }
            .joinToString("\n") { "${it.body} auth=${it.headers["X-Joryio-Auth"] != null}" }
        assertTrue("user-a's token gone after the wipe (kept=$kept); sends:\n$log", delivered)
    }

    @Test
    fun `P1c control - the same wipe with no held user delivers A's unsubscribe with A's token`() {
        scenario(holdB = false)
        assertTrue(
            pumpUntil(8_000) {
                afterWipe().any {
                    it.path.endsWith("/v1/subscriptions/channel") && it.body.contains("unsubscribed") &&
                        it.headers["X-Joryio-Auth"] == jwt("user-a")
                }
            },
        )
    }

    // P3: a held user's OWN token kept on standby (set before a reset while
    // anonymous) is dropped by wipeData(); their undelivered unsubscribe then
    // goes out with no token and is dropped.
    @Test
    fun `P3 wipe keeps a held user's standby token for their unsubscribe`() {
        val phase = AtomicInteger(0)
        api.answers["/v1/subscriptions/channel"] = {
            val last = api.seen.last { it.path.endsWith("/v1/subscriptions/channel") }
            when {
                phase.get() == 0 -> TestServer.json("""{"error":"down"}""", code = 503)
                last.headers["X-Joryio-Auth"] == null ->
                    TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
                else -> TestServer.json("""{"success":true}""")
            }
        }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.setSdkAuthenticationToken(jwt("user-h")) // the next user's, while anonymous
        sdk.reset() // standby for user-h
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-h") // held (D-b)
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/subscriptions/channel") } })
        phase.set(1)
        sentBeforeWipe = api.seen.size
        sdk.wipeData()
        val ok = pumpUntil(8_000) {
            afterWipe().any {
                it.path.endsWith("/v1/subscriptions/channel") && it.body.contains("unsubscribed") &&
                    it.headers["X-Joryio-Auth"] == jwt("user-h")
            }
        }
        val log = api.seen.filter { it.path.endsWith("/v1/subscriptions/channel") }
            .joinToString("\n") { "${it.body} auth=${it.headers["X-Joryio-Auth"] != null}" }
        assertTrue("held user's token dropped by the wipe; sends:\n$log", ok)
    }
    private fun failUntilPhase(phase: AtomicInteger) {
        api.answers["/v1/subscriptions/channel"] = {
            val last = api.seen.last { it.path.endsWith("/v1/subscriptions/channel") }
            when {
                phase.get() == 0 -> TestServer.json("""{"error":"down"}""", code = 503)
                last.headers["X-Joryio-Auth"] == null ->
                    TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
                else -> TestServer.json("""{"success":true}""")
            }
        }
    }

    // iOS scan 9 case (1) on Android: A signed in, the app sets B's token
    // and calls reset() (B's token on standby), then identify(B) while OPTED
    // OUT holds B (D-b); B unsubscribes offline; wipeData(). B's own token
    // must survive for that unsubscribe - the held identify un-flags it.
    @Test
    fun `held user's standby token set while another user was signed in survives an opted-out wipe`() {
        val phase = AtomicInteger(0)
        failUntilPhase(phase)
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.setSdkAuthenticationToken(jwt("user-a"))
        sdk.identify("user-a")
        sdk.setSdkAuthenticationToken(jwt("user-b")) // the next user's
        sdk.reset() // B's token kept on standby
        sdk.optOut()
        sdk.identify("user-b") // held (D-b)
        val held = authOf(sdk)
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/subscriptions/channel") } })
        // The held identify takes B's token off standby (contract v9 89).
        assertTrue("user-b" in held.retiredUserIds())
        phase.set(1)
        sentBeforeWipe = api.seen.size
        sdk.wipeData()
        val ok = pumpUntil(8_000) {
            afterWipe().any {
                it.path.endsWith("/v1/subscriptions/channel") && it.body.contains("unsubscribed") &&
                    it.headers["X-Joryio-Auth"] == jwt("user-b")
            }
        }
        val log = api.seen.filter { it.path.endsWith("/v1/subscriptions/channel") }
            .joinToString("\n") { "${it.body} auth=${it.headers["X-Joryio-Auth"]?.let(SdkAuthenticator::subjectOf)}" }
        assertTrue("held user's token dropped by the wipe; sends:\n$log", ok)
    }

    // iOS scan 9 S1 on Android: a pre-init wipeData() must not delete the
    // token supplied for this launch (contract 84) - here from the config.
    @Test
    fun `a pre-init wipe keeps the config token for this launch`() {
        Joryio.wipeData() // before initialize()
        val sdk = Joryio.initialize(
            app, key(), api.apiHost,
            JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false, sdkAuthenticationToken = jwt("user-b")),
        )
        sdk.identify("user-b")
        sdk.track("b-event")
        sdk.flush()
        assertTrue(
            "b-event went out without B's token",
            pumpUntil(8_000) {
                api.seen.any { it.path.endsWith("/v1/track/batch") && it.body.contains("b-event") && it.headers["X-Joryio-Auth"] == jwt("user-b") }
            },
        )
    }

    // The same with the token set through the static setter before the wipe.
    @Test
    fun `a pre-init wipe keeps a token set before initialize for this launch`() {
        Joryio.setSdkAuthenticationToken(jwt("user-b"))
        Joryio.wipeData()
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.identify("user-b")
        sdk.track("b-event")
        sdk.flush()
        assertTrue(
            "b-event went out without B's token",
            pumpUntil(8_000) {
                api.seen.any { it.path.endsWith("/v1/track/batch") && it.body.contains("b-event") && it.headers["X-Joryio-Auth"] == jwt("user-b") }
            },
        )
    }

    // Rule 89 at the SdkAuthenticator level.
    @Test
    fun `a token whose sub is the signed-in user is never standby, also with another user held`() {
        val auth = SdkAuthenticator(enabled = true, initialToken = null)
        auth.setToken(jwt("user-a"))
        auth.retireToken("user-b", signedInUserId = "user-a") // B held, A signed in
        auth.dropStandby()
        assertTrue(auth.retiredUserIds().contains("user-a"))
    }

    @Test
    fun `a held identify takes the user's token off standby, so a wipe keeps it`() {
        val auth = SdkAuthenticator(enabled = true, initialToken = null)
        auth.setToken(jwt("user-h"))
        auth.retireToken(null) // reset() while anonymous: standby for H
        auth.unflagStandby("user-h")
        auth.dropStandby()
        assertTrue(auth.retiredUserIds().contains("user-h"))
    }

    @Test
    fun `dropStandby keeps the listed users and the given token, drops the rest`() {
        val auth = SdkAuthenticator(enabled = true, initialToken = null)
        for (u in listOf("user-x", "user-y", "user-z")) {
            auth.setToken(jwt(u))
            auth.retireToken(null)
        }
        auth.dropStandby(keep = setOf("user-x"), keepToken = jwt("user-y"))
        org.junit.Assert.assertEquals(setOf("user-x", "user-y"), auth.retiredUserIds())
    }
}
