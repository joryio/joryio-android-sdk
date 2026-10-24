package io.joryio.sdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.core.SecurePrefs
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.network.SdkAuthenticator
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
 * Fix round 7 (contract v7 72-76, 80). The scan 7 probes (R1-R4), lifted as
 * regression tests, plus the tests of the other round-7 fixes. Each fails on
 * f31cd0f96.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixRound7Test {
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

    private fun encrypted(name: String, vararg values: Pair<String, String>): SharedPreferences {
        val enc = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val e = enc.edit().clear().putBoolean("__joryio_migrated_from_plaintext", true)
        values.forEach { (k, v) -> e.putString(k, v) }
        e.commit()
        return enc
    }

    private fun mergeFallbackOf(sdkKey: String, enc: SharedPreferences) {
        SecurePrefs.mergeInto(
            app.getSharedPreferences("joryio_${sdkKey.hashCode()}", Context.MODE_PRIVATE),
            enc,
            firstMigration = false,
            resolve = SecurePrefs.Resolver { k, e, p, log -> StorageManager.resolveFallback(k, e, p, log) },
            merge = SecurePrefs.Merger { k, e, p -> StorageManager.mergeFallbackValue(k, e, p) },
            // As StorageManager registers it.
            visit = setOf("write_outbox"),
        )
    }

    // R1: logout THEN login on the fallback launch. user_id's last op is a
    // write, so the log has no deletion and "loggedOut" is false.
    @Test
    fun `R1 logout then login on the fallback - the outgoing user's pending attributes and anonymous id do not follow the next user`() {
        val k = key()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        assertTrue(SecurePrefs.get(app, "joryio_${k.hashCode()}") is SecurePrefs.LoggingPrefs)
        sdk.reset()
        sdk.identify("user-b")
        pumpFor(300)
        val enc = encrypted(
            "s7-enc-switch-$k",
            "anonymous_id" to "anon-a", "user_id" to "user-a",
            "pending_attributes" to """{"email":"a@private.example"}""",
        )
        mergeFallbackOf(k, enc)
        val user = enc.getString("user_id", null)
        val anon = enc.getString("anonymous_id", null)
        val pending = enc.getString("pending_attributes", null)
        println("R1 merged: user=$user anon=$anon pending=$pending")
        assertEquals("user-b", user)
        assertFalse("A's unsent email follows B: $pending", pending?.contains("a@private.example") == true)
        assertNotEquals("B inherits A's anonymous id", "anon-a", anon)
    }

    // R1e: end to end - the merged store at the next launch sends A's email as B.
    @Test
    fun `R1e end to end - after the merge the next launch writes A's email on B's profile`() {
        val k = key()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        sdk.reset()
        sdk.identify("user-b")
        pumpFor(300)
        val enc = encrypted(
            "s7-enc-switch-e2e-$k",
            "anonymous_id" to "anon-a", "user_id" to "user-a",
            "pending_attributes" to """{"email":"a@private.example"}""",
        )
        mergeFallbackOf(k, enc)
        // The "encrypted store" the next launch opens: in Robolectric every
        // store is the plaintext file, so write the merged state there.
        Joryio.resetForTesting()
        api.seen.clear()
        val raw = app.getSharedPreferences("joryio_${k.hashCode()}", Context.MODE_PRIVATE)
        val e = raw.edit().clear()
        enc.all.forEach { (kk, v) -> if (v is String) e.putString(kk, v) else if (v is Boolean) e.putBoolean(kk, v) }
        e.commit()
        val next = Joryio.initialize(app, k, api.apiHost, cfg())
        runBlocking { next.flushPendingAttributes() }
        pumpFor(1_000)
        val attrs = api.seen.filter { it.path.endsWith("/v1/attributes") }.map { it.body }
        println("R1e attribute writes: $attrs")
        assertFalse(
            "A's email written to B: $attrs",
            attrs.any { it.contains("a@private.example") && it.contains("\"userId\":\"user-b\"") },
        )
    }

    private fun jwt(sub: String): String {
        fun b64(s: String) = android.util.Base64.encodeToString(
            s.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        return "${b64("""{"alg":"HS256"}""")}.${b64("""{"sub":"$sub"}""")}.sig"
    }

    // R2: a held user's withdrawal no longer picks their retired token
    // (currentUserId is now the held id, so tokenFor() treats them as current).
    @Test
    fun `R2 a held user's unsubscribe carries that user's retired token`() {
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        sdk.identify("user-m")
        sdk.setSdkAuthenticationToken(jwt("user-m"))
        sdk.reset() // retires user-m's token
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identify("user-m") // held (D-b)
        api.seen.clear()
        sdk.setSubscription("email", "unsubscribed")
        assertTrue(pumpUntil(6_000) { api.seen.any { it.path.endsWith("/v1/subscriptions/channel") } })
        val sent = api.seen.first { it.path.endsWith("/v1/subscriptions/channel") }
        println("R2 body=${sent.body} header=${sent.headers["X-Joryio-Auth"]}")
        assertTrue(sent.body, sent.body.contains("\"userId\":\"user-m\""))
        assertEquals("user-m's retired token", jwt("user-m"), sent.headers["X-Joryio-Auth"])
    }

    private fun bodiesOf(p: String) = api.seen.filter { it.path.endsWith(p) }.map { it.body }

    // R3: the opt-out marker is a withdrawal, but syncOptOutMarker() drains
    // unforced, so it waits out a 402 backoff (the round-6 force covers
    // enqueueWrite only).
    @Test
    fun `R3 an optOut made during a 402 suspension sends its marker without waiting for the backoff`() {
        api.answers["/v1/identify"] = { TestServer.json("""{"code":"SDK_INGESTION_SUSPENDED"}""", code = 402) }
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        sdk.identify("user-s")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        pumpFor(300)
        sdk.optOut()
        val sent = pumpUntil(2_500) { bodiesOf("/v1/attributes").any { it.contains("tracking_opted_out") } }
        assertTrue("opt-out marker waited for the 402 backoff", sent)
    }

    // R4: A's undelivered opt-out marker, then logout, then a restart:
    // init's syncOptOutMarker() removes every queued marker and re-queues
    // one for the CURRENT ids - A's profile never gets the record.
    @Test
    fun `R4 a logged-out user's undelivered opt-out marker is still delivered for that user after a restart`() {
        val k = key()
        val sdk = Joryio.initialize(app, k, api.apiHost, cfg())
        sdk.identify("user-a")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        api.answers["/v1/attributes"] = { TestServer.json("""{"error":"down"}""", code = 503) }
        sdk.optOut()
        assertTrue(pumpUntil { bodiesOf("/v1/attributes").any { it.contains("tracking_opted_out") } })
        sdk.reset()
        pumpFor(300)
        Joryio.resetForTesting() // the app is closed
        api.answers.clear()
        api.seen.clear()
        Joryio.initialize(app, k, api.apiHost, cfg())
        pumpUntil(6_000) { bodiesOf("/v1/attributes").any { it.contains("tracking_opted_out") } }
        val markers = bodiesOf("/v1/attributes").filter { it.contains("tracking_opted_out") }
        println("R4 markers after restart: $markers")
        assertTrue("A's marker re-addressed: $markers", markers.any { it.contains("\"userId\":\"user-a\"") })
    }

    // ── S1 (contract v7 76): identify() of another user, no reset, on the fallback ──

    @Test
    fun `S1 identify of another user on the fallback seals the outgoing user's attributes under their own ids`() {
        val k = key()
        val plain = app.getSharedPreferences("joryio_${k.hashCode()}", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        // What a fallback launch that only called identify("user-b") wrote:
        // user_id put, never removed.
        SecurePrefs.LoggingPrefs(plain).edit()
            .putString("anonymous_id", "anon-r")
            .putString("user_id", "user-b")
            .putString("pending_attributes", """{"plan":"b-plan"}""")
            .commit()
        val enc = encrypted(
            "r7-enc-identify-switch-$k",
            "anonymous_id" to "anon-a", "user_id" to "user-a", "held_user_id" to "user-h",
            "pending_attributes" to """{"email":"a@private.example"}""",
        )
        mergeFallbackOf(k, enc)
        assertEquals("user-b", enc.getString("user_id", null))
        assertEquals("the fallback's anonymous id, not A's", "anon-r", enc.getString("anonymous_id", null))
        assertNull("A's held id does not follow B", enc.getString("held_user_id", null))
        val pending = enc.getString("pending_attributes", null)
        assertFalse("A's email still pending for B: $pending", pending?.contains("a@private.example") == true)
        assertTrue(pending, pending?.contains("b-plan") == true)
        val outbox = enc.getString("write_outbox", null)
        assertTrue("A's attributes sealed under A: $outbox", outbox != null && outbox.contains("a@private.example"))
        val sealed = com.google.gson.JsonParser.parseString(outbox).asJsonArray
            .map { it.asJsonObject }
            .first { it.get("body").asString.contains("a@private.example") }
        val body = com.google.gson.JsonParser.parseString(sealed.get("body").asString).asJsonObject
        assertEquals("user-a", body.get("userId").asString)
        assertEquals("anon-a", body.get("anonymousId").asString)
        assertEquals("attributes", sealed.get("kind").asString)
    }

    @Test
    fun `S1 the same user identified again on the fallback keeps the stored identity and attributes`() {
        val k = key()
        val plain = app.getSharedPreferences("joryio_${k.hashCode()}", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        SecurePrefs.LoggingPrefs(plain).edit()
            .putString("anonymous_id", "anon-r")
            .putString("user_id", "user-a")
            .commit()
        val enc = encrypted(
            "r7-enc-same-user-$k",
            "anonymous_id" to "anon-a", "user_id" to "user-a",
            "pending_attributes" to """{"email":"a@private.example"}""",
        )
        mergeFallbackOf(k, enc)
        assertEquals("anon-a", enc.getString("anonymous_id", null))
        assertTrue(enc.getString("pending_attributes", null)!!.contains("a@private.example"))
        assertNull(enc.getString("write_outbox", null))
    }

    // ── Contract v7 72: the token routed by its `sub` ──

    @Test
    fun `72 a token is attached only to requests of the user its sub names`() {
        val auth = SdkAuthenticator(enabled = true, initialToken = null)
        auth.setToken(jwt("user-a"))
        // setToken(B) before identify(B): A is still current.
        auth.setToken(jwt("user-b"))
        assertEquals("A's own (retired) token for A", jwt("user-a"), auth.tokenFor("user-a", "user-a"))
        assertEquals(jwt("user-b"), auth.tokenFor("user-b", "user-a"))
        assertNull("a token naming someone else is never attached", auth.tokenFor("user-c", "user-c"))
        // setToken(B) -> reset -> identify(B): B's own token after the reset.
        auth.retireToken("user-a")
        assertEquals(jwt("user-b"), auth.tokenFor("user-b", "user-b"))
        // An opaque token (no readable sub) stays the current user's.
        val opaque = SdkAuthenticator(enabled = true, initialToken = "opaque-token")
        assertEquals("opaque-token", opaque.tokenFor("user-x", "user-x"))
        // An anonymous request carries no token (contract v7 72, same on web and iOS).
        assertEquals(null, opaque.tokenFor(null, "user-x"))
    }

    @Test
    fun `72 a token set for the next user before reset waits on standby and is adopted at identify`() {
        var now = 1_000_000L
        val auth = SdkAuthenticator(enabled = true, initialToken = null, clock = { now })
        auth.setToken(jwt("user-a"))
        auth.retireToken("user-a")
        auth.setToken(jwt("user-b")) // nothing to retire: the session held no token
        // The app sets B's token while A is still signed in, then logs A out.
        val auth2 = SdkAuthenticator(enabled = true, initialToken = null, clock = { now })
        auth2.setToken(jwt("user-b"))
        auth2.retireToken("user-a")
        // Nothing of B's is queued: an ordinary retired token would go after the grace minute.
        now += 10 * 60 * 1000L
        auth2.prune(emptySet())
        assertEquals("B's standby token survives the prune", jwt("user-b"), auth2.tokenFor("user-b", "user-b"))
        auth2.adoptRetired("user-b")
        auth2.prune(emptySet())
        assertEquals("adopted as the session token", jwt("user-b"), auth2.currentToken())
        assertEquals(jwt("user-b"), auth2.tokenFor("user-b", "user-b"))
        // The outgoing user's own token is not standby: pruned once nothing of theirs is left.
        now += 10 * 60 * 1000L
        auth.prune(emptySet())
        assertNull(auth.tokenFor("user-a", "user-b"))
    }

    // ── Contract v7 73: data calls made while refused before init are dropped ──

    private fun events() = bodiesOf("/v1/track/batch").joinToString("\n")

    @Test
    fun `73 an event tracked while opted out before init is not sent after a later optIn`() {
        Joryio.optOut()
        Joryio.track("made-while-opted-out")
        Joryio.setAttribute("color", "made-while-opted-out")
        Joryio.optIn()
        Joryio.track("made-after-opt-in")
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil { events().contains("made-after-opt-in") })
        pumpFor(1_200)
        assertFalse(events(), events().contains("made-while-opted-out"))
        assertFalse(bodiesOf("/v1/attributes").joinToString(), bodiesOf("/v1/attributes").any { it.contains("made-while-opted-out") })
    }

    @Test
    fun `73 an event tracked while consent is denied before init is not sent after a later grant`() {
        Joryio.updateConsent(TrackingConsent.DENIED)
        Joryio.track("made-while-denied")
        Joryio.updateConsent(TrackingConsent.GRANTED)
        Joryio.track("made-after-grant")
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg())
        pumpFor(300)
        sdk.flush()
        assertTrue(pumpUntil { events().contains("made-after-grant") })
        pumpFor(500)
        assertFalse(events(), events().contains("made-while-denied"))
    }

    @Test
    fun `73 an unsubscribe made while opted out before init is still delivered`() {
        Joryio.optOut()
        Joryio.setSubscription("email", "unsubscribed")
        Joryio.initialize(app, key(), api.apiHost, cfg())
        assertTrue(pumpUntil { bodiesOf("/v1/subscriptions/channel").any { it.contains("unsubscribed") } })
    }

    // ── RN scan 7 N1: a pre-init token applies before the constructor's first requests ──

    @Test
    fun `a token set before initialize is on the first in-app sync of a stored user`() {
        Joryio.setSdkAuthenticationToken(jwt("user-u"))
        var sdk: Joryio? = null
        // From a background thread, as the React Native bridge does: the
        // buffer is then replayed on the main looper, after the constructor
        // has launched its first requests.
        val t = Thread { sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true).copy(userId = "user-u")) }
        t.start()
        t.join()
        // Before the main looper has run anything: the constructor applied
        // it, not the buffer replay that follows its first requests.
        val client = Joryio::class.java.getDeclaredField("networkClient").apply { isAccessible = true }.get(sdk)
        val auth = client.javaClass.getDeclaredField("sdkAuthenticator").apply { isAccessible = true }.get(client) as SdkAuthenticator
        assertEquals("applied by the constructor", jwt("user-u"), auth.currentToken())
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/in-app/sync") } })
        val sync = api.seen.first { it.path.endsWith("/v1/in-app/sync") }
        assertEquals(jwt("user-u"), sync.headers["X-Joryio-Auth"])
        assertTrue(sdk != null)
    }

    @Test
    fun `a token set before initialize after a pre-init reset keeps its place behind the reset`() {
        Joryio.reset()
        Joryio.setSdkAuthenticationToken(jwt("user-n"))
        val sdk = Joryio.initialize(app, key(), api.apiHost, cfg(sdkAuth = true))
        pumpFor(300)
        sdk.identify("user-n")
        assertTrue(pumpUntil { bodiesOf("/v1/identify").isNotEmpty() })
        val identify = api.seen.first { it.path.endsWith("/v1/identify") }
        assertEquals("the next user's token is not retired by the replayed reset", jwt("user-n"), identify.headers["X-Joryio-Auth"])
    }

    // ── NICE 3: the key move needs evidence ──

    private fun unusable(cause: Throwable? = null) = java.security.KeyStoreException(
        "the master key android-keystore://_androidx_security_master_key_ exists but is unusable",
        cause,
    )

    private class Launches(app: Application, private val name: String) {
        val prefs: SharedPreferences = app.getSharedPreferences(name, Context.MODE_PRIVATE).apply { edit().clear().commit() }
        var now = 1_000_000L
        var launch = 0
        fun next(advanceMs: Long): SecurePrefs.KeystoreHealth {
            now += advanceMs
            launch++
            return SecurePrefs.KeystoreHealth(prefs, clock = { now }, launchId = "$name-$launch")
        }
    }

    private fun attempt(
        health: SecurePrefs.KeystoreHealth,
        error: Throwable,
        fallback: SharedPreferences,
        fresh: SharedPreferences,
    ): SharedPreferences = SecurePrefs.openWithRecovery(
        open = { throw error },
        discardEncrypted = { error("an unusable key is not an undecryptable file") },
        plaintextFallback = { fallback },
        logger = null,
        retryDelayMs = 0,
        onPermanentlyUnusable = { confirmed ->
            SecurePrefs.recoverUnusableKey(
                health, "f", health.keyGeneration(), null,
                discardOld = {},
                openNew = { fresh },
                confirmed = confirmed,
            )
        },
    )

    @Test
    fun `NICE3 several stores failing in one launch count as one launch`() {
        val l = Launches(app, "r7-health-one-launch")
        val plain = app.getSharedPreferences("r7-plain-1", Context.MODE_PRIVATE)
        val fresh = app.getSharedPreferences("r7-fresh-1", Context.MODE_PRIVATE)
        val confirmedError = unusable(javax.crypto.AEADBadTagException("tag mismatch"))
        val h = l.next(0)
        repeat(3) { assertSame("one launch, three stores: no move", plain, attempt(h, confirmedError, plain, fresh)) }
        assertEquals(0, h.keyGeneration())
        assertSame(plain, attempt(l.next(1_000), confirmedError, plain, fresh))
        assertSame("third LAUNCH with a confirmed cause: moved", fresh, attempt(l.next(1_000), confirmedError, plain, fresh))
        assertEquals(1, h.keyGeneration())
    }

    @Test
    fun `NICE3 a store that opened in the same launch proves the key works - no move`() {
        val l = Launches(app, "r7-health-healthy")
        val plain = app.getSharedPreferences("r7-plain-2", Context.MODE_PRIVATE)
        val fresh = app.getSharedPreferences("r7-fresh-2", Context.MODE_PRIVATE)
        val confirmedError = unusable(javax.crypto.AEADBadTagException("tag mismatch"))
        attempt(l.next(0), confirmedError, plain, fresh)
        attempt(l.next(1_000), confirmedError, plain, fresh)
        val third = l.next(1_000)
        third.noteHealthy() // another store opened in this launch
        assertSame(plain, attempt(third, confirmedError, plain, fresh))
        assertEquals(0, third.keyGeneration())
    }

    @Test
    fun `NICE3 a bare unusable error moves the key only after the failures have lasted a day`() {
        val l = Launches(app, "r7-health-span")
        val plain = app.getSharedPreferences("r7-plain-3", Context.MODE_PRIVATE)
        val fresh = app.getSharedPreferences("r7-fresh-3", Context.MODE_PRIVATE)
        val bare = unusable(java.security.ProviderException("Keystore operation failed"))
        assertSame(plain, attempt(l.next(0), bare, plain, fresh))
        assertSame(plain, attempt(l.next(60_000), bare, plain, fresh))
        assertSame("three launches in two minutes: maybe transient", plain, attempt(l.next(60_000), bare, plain, fresh))
        val later = l.next(SecurePrefs.MIN_UNUSABLE_SPAN_MS)
        assertSame("still failing a day later: moved", fresh, attempt(later, bare, plain, fresh))
        assertEquals(1, later.keyGeneration())
        assertTrue(SecurePrefs.isUnusableKeyConfirmed(unusable(javax.crypto.AEADBadTagException("x"))))
        assertFalse(SecurePrefs.isUnusableKeyConfirmed(bare))
    }

    @Test
    fun `NICE3 a store readable under the old key moves its values to the new one and the fallback log applies over them`() {
        val k = key()
        val plain = app.getSharedPreferences("joryio_${k.hashCode()}", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        SecurePrefs.LoggingPrefs(plain).edit().remove("device_token").commit()
        val fresh = app.getSharedPreferences("r7-fresh-seed-$k", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        SecurePrefs.seedNewStore(fresh, mapOf("user_id" to "user-a", "device_token" to "t-old"))
        assertEquals("user-a", fresh.getString("user_id", null))
        mergeFallbackOf(k, fresh)
        assertEquals("user-a", fresh.getString("user_id", null))
        assertNull("the fallback's removal applies over the moved values", fresh.getString("device_token", null))
        // A store already opened under the new key is never re-seeded.
        SecurePrefs.seedNewStore(fresh, mapOf("user_id" to "user-z"))
        assertEquals("user-a", fresh.getString("user_id", null))
    }
}
