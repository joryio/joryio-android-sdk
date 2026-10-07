package io.joryio.sdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.core.SecurePrefs
import io.joryio.sdk.core.StorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round 6 fixes. The scan-6 probes (Q1-Q5) are kept as regression tests; each
 * test here fails on 011d4a88c.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound6Test {
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
    private fun cfg(consent: TrackingConsent? = null, sdkAuth: Boolean = false) =
        JoryioConfig(
            flushInterval = 60_000,
            maxRetries = 0,
            trackSessionStart = false,
            trackingConsent = consent,
            enableSdkAuthentication = sdkAuth,
        )
    private fun bodiesOf(p: String) = api.seen.filter { it.path.endsWith(p) }.map { it.body }

    // ── S1 / contract v6 63: the held identify is released at init and cleared by identify() ──

    @Test
    fun `Q1 held identify runs at the next launch whose config grants consent`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.DENIED))
        first.identify("user-held")
        pumpFor(500)
        Joryio.resetForTesting() // process death
        api.seen.clear()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.GRANTED))
        val sent = pumpUntil(6_000) { bodiesOf("/v1/identify").any { it.contains("user-held") } }
        assertTrue("identify sent=$sent userId=${sdk.getUserId()}", sent && sdk.getUserId() == "user-held")
    }

    @Test
    fun `Q1b held identify runs when a pre-init optIn lifts the opt-out`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg())
        first.optOut()
        first.identify("user-held2")
        pumpFor(500)
        Joryio.resetForTesting()
        api.seen.clear()
        Joryio.optIn()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        val sent = pumpUntil(6_000) { bodiesOf("/v1/identify").any { it.contains("user-held2") } }
        assertTrue("identify sent=$sent userId=${sdk.getUserId()}", sent && sdk.getUserId() == "user-held2")
    }

    @Test
    fun `Q1c a stale held id does not take over another user's unsubscribe`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.DENIED))
        first.identify("user-held")
        pumpFor(500)
        Joryio.resetForTesting()
        api.seen.clear()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.GRANTED))
        sdk.identify("user-b")
        pumpFor(500)
        sdk.optOut()
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        val body = bodiesOf("/v1/subscriptions/channel").first()
        assertTrue("user-b's unsubscribe sent as: $body", body.contains("\"userId\":\"user-b\""))
    }

    @Test
    fun `control - a held identify runs on a runtime grant at a later launch`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.DENIED))
        first.identify("user-late")
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        // The default config: the stored denial stays until a grant.
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        pumpFor(300)
        assertFalse(bodiesOf("/v1/identify").any { it.contains("user-late") })
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/identify").any { it.contains("user-late") } })
    }

    @Test
    fun `an explicit init userId wins over a stored held id`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg(TrackingConsent.DENIED))
        first.identify("user-held3")
        pumpFor(300)
        Joryio.resetForTesting()
        api.seen.clear()
        val sdk = Joryio.initialize(
            app, k, api.apiHost,
            JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false, userId = "user-config", optOut = true),
        )
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        assertTrue(bodiesOf("/v1/subscriptions/channel").first().contains("\"userId\":\"user-config\""))
    }

    // ── S2 / contract v6 63: every withdrawal carries the held id ──

    @Test
    fun `Q2 opt-out marker made after a held identify carries the held userId`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-m")
        sdk.optOut()
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/attributes").any { it.contains("tracking_opted_out") } })
        val marker = bodiesOf("/v1/attributes").first { it.contains("tracking_opted_out") }
        assertTrue("marker=$marker", marker.contains("\"userId\":\"user-m\""))
    }

    @Test
    fun `a push unregister made after a held identify carries the held userId`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        var r: io.joryio.sdk.push.PushRegistrationResult? = null
        sdk.registerPushToken("tok-held") { r = it }
        assertTrue(pumpUntil { r != null })
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-p")
        sdk.unregisterPush()
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/push/unregister").isNotEmpty() })
        val body = bodiesOf("/v1/push/unregister").first()
        assertTrue("unregister=$body", body.contains("\"userId\":\"user-p\""))
    }

    @Test
    fun `an SDK-auth refusal of the held user's withdrawal asks the app for a token`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        val errors = java.util.concurrent.CopyOnWriteArrayList<SdkAuthError>()
        sdk.setSdkAuthenticationErrorHandler { errors += it }
        pumpFor(300)
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-h")
        api.answers["/v1/subscriptions/channel"] = {
            TestServer.json("""{"error":"sdk_authentication_error","reason":"missing"}""", code = 401)
        }
        sdk.setSubscription("email", "unsubscribed")
        assertTrue("handler never asked for the held user's token", pumpUntil(6_000) { errors.isNotEmpty() })
    }

    @Test
    fun `Q4 control unsubscribe after held identify carries it`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-u")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        assertTrue(bodiesOf("/v1/subscriptions/channel").first().contains("\"userId\":\"user-u\""))
    }

    // ── contract v6 64: an anonymous subscribe resolved to the user never lands after their unsubscribe ──

    @Test
    fun `a held anonymous subscribe never lands after the identified user's unsubscribe`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(TrackingConsent.PENDING))
        sdk.setSubscription("email", "subscribed") // the newsletter box, before login, banner unanswered
        sdk.identify("user-a")
        sdk.setSubscription("email", "unsubscribed") // a withdrawal: sent at once
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/identify").isNotEmpty() })
        pumpFor(1_000)
        val wire = bodiesOf("/v1/subscriptions/channel")
        assertTrue("wire order: $wire", wire.last().contains("\"status\":\"unsubscribed\""))
        assertTrue("the older subscribe was sent after the unsubscribe: $wire", wire.none { it.contains("\"status\":\"subscribed\"") })
    }

    // ── NICE 1: a withdrawal does not wait for a 402 backoff ──

    @Test
    fun `Q5 an unsubscribe made during a 402 suspension goes out without waiting for the backoff`() {
        api.answers["/v1/identify"] = { TestServer.json("""{"code":"SDK_INGESTION_SUSPENDED"}""", code = 402) }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.identify("user-s")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        pumpFor(300)
        sdk.setSubscription("email", "unsubscribed")
        val sent = pumpUntil(2_500) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() }
        assertTrue("unsubscribe waited for the 402 backoff", sent)
    }

    // ── contract v6 61: retired tokens after a restart; one malformed entry ──

    @Test
    fun `a logged-out user's leftovers carry their token after a restart when SDK auth was on only through a token`() {
        val k = key()
        var down = true
        api.answers["/v1/subscriptions/channel"] = {
            if (down) TestServer.json("""{"error":"down"}""", code = 503) else TestServer.json("""{"success":true}""")
        }
        var sdk = Joryio.initialize(app, k, api.apiHost, cfg()) // enableSdkAuthentication left false
        sdk.setSdkAuthenticationToken("token-a")
        sdk.identify("user-a")
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        sdk.reset()
        pumpFor(300)
        Joryio.resetForTesting() // process death; the next launch sets no token
        down = false
        api.seen.clear()
        sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        runBlocking { sdk.drainOutbox(force = true) }
        pumpFor(1_500)
        val sent = api.seen.filter { it.path.endsWith("/v1/subscriptions/channel") }
        assertTrue("headers: ${sent.map { it.headers["X-Joryio-Auth"] }}", sent.any { it.headers["X-Joryio-Auth"] == "token-a" })
    }

    @Test
    fun `one malformed retired token never loses the others`() {
        val k = key()
        val logger = Logger(false, LogLevel.ERROR)
        val storage = StorageManager(app, k, logger)
        SecurePrefs.get(app, "joryio_${k.hashCode()}", logger).edit()
            .putString(
                "retired_sdk_auth_tokens",
                """{"user-a":{"token":"t-a","retiredAt":1},"user-b":{"retiredAt":2},"user-c":"junk","user-d":{"token":"t-d"}}""",
            )
            .commit()
        val restored = storage.getRetiredAuthTokens()
        assertEquals("t-a", restored["user-a"]?.token)
        assertEquals("t-d", restored["user-d"]?.token)
        assertEquals(setOf("user-a", "user-d"), restored.keys)
    }

    // ── contract v6 65: the pre-init consent getter matches iOS ──

    @Test
    fun `pre-init getTrackingConsent with nothing decided or stored is GRANTED, as on iOS`() {
        assertEquals(TrackingConsent.GRANTED, Joryio.getTrackingConsent())
        Joryio.updateConsent(TrackingConsent.PENDING)
        assertEquals(TrackingConsent.PENDING, Joryio.getTrackingConsent())
    }

    // ── contract v6 69: push token trimmed; a held unregister wins over the automatic token ──

    @Test
    fun `registerPushToken stores and sends the trimmed token`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        var r: io.joryio.sdk.push.PushRegistrationResult? = null
        sdk.registerPushToken("  tok-trim\n") { r = it }
        assertTrue(pumpUntil { r != null })
        assertTrue(bodiesOf("/v1/push/register").first().contains("\"token\":\"tok-trim\""))
        assertEquals("tok-trim", sdk.getDeviceToken())
    }

    @Test
    fun `a pre-init unregisterPush wins over the token FCM handed over before init`() {
        // onNewToken ran before initialize(): the token waits for init to register it.
        io.joryio.sdk.push.PendingTokenStore.save(app, "tok-auto")
        Joryio.unregisterPush()
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(1_500)
        assertTrue("registered: ${bodiesOf("/v1/push/register")}", bodiesOf("/v1/push/register").isEmpty())
        // The user turning push back on still does.
        var r: io.joryio.sdk.push.PushRegistrationResult? = null
        sdk.registerPushToken("tok-auto") { r = it }
        assertTrue(pumpUntil { r != null })
        assertEquals(true, r!!.success)
    }

    // ── NICE 4: a pre-init token survives a pre-init wipe ──

    @Test
    fun `a pre-init SDK-auth token is kept through a pre-init wipeData`() {
        val k = key()
        val first = Joryio.initialize(app, k, api.apiHost, cfg())
        first.identify("user-w")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        Joryio.resetForTesting()
        api.seen.clear()
        Joryio.setSdkAuthenticationToken("tok-w")
        Joryio.setSubscription("email", "unsubscribed")
        Joryio.wipeData()
        Joryio.initialize(app, k, api.apiHost, cfg())
        assertTrue(pumpUntil(6_000) { bodiesOf("/v1/subscriptions/channel").isNotEmpty() })
        val sent = api.seen.first { it.path.endsWith("/v1/subscriptions/channel") }
        assertTrue(sent.body, sent.body.contains("\"userId\":\"user-w\""))
        assertEquals("tok-w", sent.headers["X-Joryio-Auth"])
    }

    // ── NICE 5: a push click while consent is pending ──

    @Test
    fun `a push click while consent is pending is held and sent on GRANTED`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(TrackingConsent.PENDING))
        sdk.trackPushClick("trk-pending")
        pumpFor(400)
        assertFalse(api.paths().any { it.contains("trk-pending") })
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil(6_000) { api.paths().any { it.contains("/track/push/click/trk-pending") } })
    }

    @Test
    fun `a push click held while pending is dropped on DENIED`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(TrackingConsent.PENDING))
        sdk.trackPushClick("trk-denied")
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.updateConsent(TrackingConsent.GRANTED)
        pumpFor(1_000)
        assertFalse(api.paths().any { it.contains("trk-denied") })
    }

    // ── S3 / contract v6 67: the plaintext-fallback merge applies what that launch did ──

    /** The store an earlier launch left encrypted, for the merge at the next encrypted open. */
    private fun encrypted(name: String, vararg values: Pair<String, Any>): SharedPreferences {
        val enc = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val e = enc.edit().clear().putBoolean("__joryio_migrated_from_plaintext", true)
        values.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                else -> error("type")
            }
        }
        e.commit()
        return enc
    }

    /** The next launch that opens the encrypted store merges the fallback file into it. */
    private fun mergeFallbackOf(sdkKey: String, enc: SharedPreferences) {
        SecurePrefs.mergeInto(
            app.getSharedPreferences("joryio_${sdkKey.hashCode()}", Context.MODE_PRIVATE),
            enc,
            firstMigration = false,
            resolve = SecurePrefs.Resolver { k, e, p, log -> StorageManager.resolveFallback(k, e, p, log) },
            merge = SecurePrefs.Merger { k, e, p -> StorageManager.mergeFallbackValue(k, e, p) },
        )
    }

    private val outboxA = """[{"id":"a","kind":"identify","path":"v1/identify","body":"{\"userId\":\"user-a\"}","attempts":0},""" +
        """{"id":"u","kind":"unsubscribe","path":"v1/subscriptions/channel","body":"{\"userId\":\"user-a\",\"status\":\"unsubscribed\"}","attempts":0}]"""

    private fun fallbackLaunch(sdkKey: String): Joryio {
        val sdk = Joryio.initialize(app, sdkKey, api.apiHost, cfg())
        // Robolectric has no Android Keystore: every store runs on the fallback.
        assertTrue(SecurePrefs.get(app, "joryio_${sdkKey.hashCode()}") is SecurePrefs.LoggingPrefs)
        return sdk
    }

    @Test
    fun `Q3 a logout on the fallback survives the merge`() {
        val k = key()
        val sdk = fallbackLaunch(k)
        sdk.reset()
        pumpFor(200)
        val enc = encrypted("r6-enc-logout-$k", "anonymous_id" to "anon-1", "user_id" to "user-a", "device_id" to "dev-1")
        mergeFallbackOf(k, enc)
        assertNull("logged-out user restored", enc.getString("user_id", null))
        assertNotEquals("the logout's new anonymous id", "anon-1", enc.getString("anonymous_id", null))
        assertEquals("the device id stays", "dev-1", enc.getString("device_id", null))
    }

    @Test
    fun `Q3b a wipeData on the fallback survives the merge, withdrawals kept`() {
        val k = key()
        val sdk = fallbackLaunch(k)
        sdk.wipeData()
        pumpFor(200)
        val enc = encrypted(
            "r6-enc-wipe-$k",
            "anonymous_id" to "anon-1", "user_id" to "user-a", "device_token" to "tok",
            "device_id" to "dev-1", "pending_attributes" to """{"email":"a@b.c"}""",
            "write_outbox" to outboxA, "pending_unregister" to """{"token":"tok-old"}""",
            "retired_sdk_auth_tokens" to """{"user-z":{"token":"t-z","retiredAt":1}}""",
        )
        mergeFallbackOf(k, enc)
        assertNull(enc.getString("user_id", null))
        assertNull(enc.getString("device_token", null))
        assertNull(enc.getString("pending_attributes", null))
        assertNotEquals("anon-1", enc.getString("anonymous_id", null))
        assertNotEquals("dev-1", enc.getString("device_id", null))
        val outbox = enc.getString("write_outbox", "")!!
        assertTrue("the unsubscribe survives: $outbox", outbox.contains("\"u\""))
        assertFalse("the identify is erased: $outbox", outbox.contains("\"id\":\"a\""))
        assertEquals("""{"token":"tok-old"}""", enc.getString("pending_unregister", null))
        assertTrue(enc.getString("retired_sdk_auth_tokens", "")!!.contains("t-z"))
    }

    @Test
    fun `an optIn on the fallback survives the merge`() {
        val k = key()
        val sdk = fallbackLaunch(k)
        sdk.optIn()
        pumpFor(200)
        val enc = encrypted("r6-enc-optin-$k", "opted_out" to true, "anonymous_id" to "anon-1")
        mergeFallbackOf(k, enc)
        assertEquals(false, enc.getBoolean("opted_out", true))
    }

    @Test
    fun `a fallback launch with no identity change keeps the stored identity and merges the queues`() {
        val k = key()
        val sdk = fallbackLaunch(k)
        sdk.setSubscription("email", "unsubscribed")
        pumpFor(200)
        val enc = encrypted(
            "r6-enc-keep-$k",
            "anonymous_id" to "anon-1", "user_id" to "user-a", "device_id" to "dev-1", "write_outbox" to outboxA,
        )
        mergeFallbackOf(k, enc)
        assertEquals("user-a", enc.getString("user_id", null))
        assertEquals("anon-1", enc.getString("anonymous_id", null))
        assertEquals("dev-1", enc.getString("device_id", null))
        val outbox = enc.getString("write_outbox", "")!!
        assertTrue(outbox, outbox.contains("\"id\":\"a\"") && outbox.contains("\"u\""))
    }

    @Test
    fun `the plaintext file is not left behind, even when the fallback launch only cleared it`() {
        val raw = app.getSharedPreferences("r6-plain-only-clear", Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        SecurePrefs.LoggingPrefs(raw).edit().clear().commit()
        assertTrue("the log is hidden from readers", SecurePrefs.LoggingPrefs(raw).all.isEmpty())
        assertFalse("but on disk, so the merge is not skipped", raw.all.isEmpty())
        val enc = encrypted("r6-enc-only-clear", "user_id" to "user-a")
        SecurePrefs.mergeInto(raw, enc, firstMigration = false, merge = null)
        assertNull(enc.getString("user_id", null))
        assertTrue(raw.all.isEmpty())
    }

    // ── NICE 2 / contract v6 67: a permanently unusable key; no sleep on the main thread ──

    @Test
    fun `a master key unusable on three launches in a row moves the SDK to a new key, once`() {
        val healthPrefs = app.getSharedPreferences("r6-health", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        // One KeystoreHealth per launch (process), as in the app; a bare
        // "unusable" error needs a day of failures since round 7 (scan 7,
        // Android NICE 3).
        var now = 0L
        var launchNo = 0
        var health = SecurePrefs.KeystoreHealth(healthPrefs, clock = { now }, launchId = "l0")
        val plaintext = app.getSharedPreferences("r6-plain-unusable", Context.MODE_PRIVATE)
        val fresh = app.getSharedPreferences("r6-enc-fresh", Context.MODE_PRIVATE)
        var discarded = 0
        var openedGeneration = -1
        fun openOnce(): SharedPreferences = SecurePrefs.openWithRecovery(
            open = {
                throw java.security.KeyStoreException(
                    "the master key android-keystore://_androidx_security_master_key_ exists but is unusable",
                )
            },
            discardEncrypted = { error("an unusable key is not an undecryptable file") },
            plaintextFallback = { plaintext },
            logger = null,
            retryDelayMs = 0,
            onPermanentlyUnusable = {
                SecurePrefs.recoverUnusableKey(
                    health, "f", health.keyGeneration(), null,
                    discardOld = { discarded++ },
                    openNew = { generation -> openedGeneration = generation; fresh },
                )
            },
        )
        fun launch(): SharedPreferences {
            launchNo++
            now += 12L * 60 * 60 * 1000
            health = SecurePrefs.KeystoreHealth(healthPrefs, clock = { now }, launchId = "l$launchNo")
            return openOnce()
        }
        assertSame(plaintext, launch())
        assertSame(plaintext, launch())
        assertEquals(0, discarded)
        assertSame("third launch in a row: a new key", fresh, launch())
        assertEquals(1, discarded)
        assertEquals(1, openedGeneration)
        assertEquals(1, health.keyGeneration())
    }

    @Test
    fun `a transient error on the main thread is retried without sleeping`() {
        val enc = app.getSharedPreferences("r6-enc-main", Context.MODE_PRIVATE)
        val plain = app.getSharedPreferences("r6-plain-main", Context.MODE_PRIVATE)
        var slept = 0L
        var attempts = 0
        val onMain = SecurePrefs.openWithRecovery(
            open = { if (attempts++ == 0) throw java.security.KeyStoreException("busy") else enc },
            discardEncrypted = {},
            plaintextFallback = { plain },
            logger = null,
            isMainThread = { true },
            sleep = { slept += it },
        )
        assertSame(enc, onMain)
        assertEquals(0L, slept)
        attempts = 0
        SecurePrefs.openWithRecovery(
            open = { if (attempts++ == 0) throw java.security.KeyStoreException("busy") else enc },
            discardEncrypted = {},
            plaintextFallback = { plain },
            logger = null,
            isMainThread = { false },
            sleep = { slept += it },
        )
        assertEquals(150L, slept)
        assertTrue(SecurePrefs.isPermanentlyUnusable(java.security.KeyStoreException("x exists but is unusable")))
        assertFalse(SecurePrefs.isPermanentlyUnusable(java.security.KeyStoreException("busy")))
    }
}
