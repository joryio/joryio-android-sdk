package io.joryio.sdk

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.IdentityManager
import io.joryio.sdk.core.Logger
import io.joryio.sdk.core.SecurePrefs
import io.joryio.sdk.core.SessionManager
import io.joryio.sdk.core.StorageManager
import io.joryio.sdk.messaging.InAppMessagingManager
import io.joryio.sdk.messaging.canonicalInAppAction
import io.joryio.sdk.network.NetworkClient
import io.joryio.sdk.push.JoryioPermissionActivity
import io.joryio.sdk.push.PushNotificationManager
import io.joryio.sdk.push.PushPermission
import io.joryio.sdk.push.PushPermissionStatus
import io.joryio.sdk.push.PushRegistrationResult
import io.joryio.sdk.queue.JoryioDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The SDK's parts one at a time, on a real Android runtime. Each test names the
 * bug it catches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComponentsTest {

    private lateinit var api: TestServer
    private lateinit var app: Application
    private val logger = Logger(false, LogLevel.ERROR)
    private val key = "jry_sdk_android_test_${UUID.randomUUID()}"

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

    private fun network() = NetworkClient(context = app, sdkKey = key, apiHost = api.apiHost, logger = logger, maxRetries = 0)

    private fun storage() = StorageManager(app, key, logger)

    // ── In-app ───────────────────────────────────────────────────────────

    private fun inApp(storage: StorageManager = storage()): InAppMessagingManager {
        val identity = IdentityManager(storage, logger)
        return InAppMessagingManager(
            networkClient = network(),
            identityManager = identity,
            sessionManager = SessionManager(storage, logger),
            storage = storage,
            logger = logger,
        )
    }

    @Test
    fun `only a display counts toward frequency caps`() = runBlocking {
        // Fails before 1.3.0: displayed + clicked + dismissed stored THREE
        // impressions, so a "show twice" cap was spent by one showing.
        val storage = storage()
        val manager = inApp(storage)
        manager.trackImpression("c1", "displayed")
        manager.trackImpression("c1", "clicked", "cta")
        manager.trackImpression("c1", "dismissed")
        assertEquals(1, storage.getCampaignImpressions("c1").size)
    }

    @Test
    fun `a click sends the vocabulary action and the button id separately`() = runBlocking {
        // Fails before 1.3.0: the button id replaced the action ("cta"), which
        // the backend rejects as an unknown action.
        inApp().trackImpression("c1", "click", "cta")
        val body = api.seen.single { it.path.endsWith("/v1/in-app/track") }.body
        assertTrue(body, body.contains("\"action\":\"clicked\""))
        assertTrue(body, body.contains("\"elementId\":\"cta\""))
        assertTrue(body, body.contains("\"clicked\":true"))
    }

    @Test
    fun `the action vocabulary matches the backend's`() {
        assertEquals("displayed", canonicalInAppAction("impression"))
        assertEquals("clicked", canonicalInAppAction("button_click"))
        assertEquals("dismissed", canonicalInAppAction("closed"))
        assertEquals("converted", canonicalInAppAction("conversion"))
        assertNull(canonicalInAppAction("button:cta"))
    }

    @Test
    fun `a push tap during an in-flight sync gets a fresh sync`() {
        // Fails before 1.3.0: the tap's sync() returned at once because one was
        // already running, and the tap evaluated campaigns from before it.
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"campaigns":[],"evaluatedAt":"x"}""")
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
        }
        val manager = inApp()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { manager.sync() }
        assertTrue(pumpUntil(15_000) { api.seen.isNotEmpty() })
        scope.launch { manager.onPushTapped() }
        assertTrue(pumpUntil(15_000) { api.seen.count { it.path.endsWith("/v1/in-app/sync") } == 2 })
    }

    @Test
    fun `no sync while sending is not allowed`() = runBlocking {
        val manager = inApp()
        manager.canSend = { false }
        manager.sync()
        manager.trackImpression("c1", "displayed")
        assertTrue(api.seen.isEmpty())
    }

    // ── Push registration ────────────────────────────────────────────────

    @Test
    fun `200 success false is not remembered as registered, and unregister allows re-registering`() {
        // Fails before 1.3.0 twice: a {success:false} 200 stored the
        // "registered" marker (never retried), and unregisterPush left it, so
        // registering again was skipped.
        var registerAnswer = """{"success":false,"message":"rejected"}"""
        api.answers["/v1/push/register"] = { TestServer.json(registerAnswer) }
        val storage = storage()
        val push = PushNotificationManager(app, network(), IdentityManager(storage, logger), storage, logger)

        var result: PushRegistrationResult? = null
        push.registerToken("tok") { result = it }
        assertTrue(pumpUntil { result != null })
        assertEquals(false, result!!.success)
        assertNull(storage.getRegisteredPushKey())

        registerAnswer = """{"success":true}"""
        result = null
        push.registerToken("tok") { result = it }
        assertTrue(pumpUntil { result != null })
        assertEquals(true, result!!.success)
        assertTrue(storage.getRegisteredPushKey() != null)

        push.unregisterToken()
        assertTrue(pumpUntil { storage.getRegisteredPushKey() == null })
        assertTrue(pumpUntil { api.seen.any { it.path.endsWith("/v1/push/unregister") } })

        // The host turning push back on registers again (explicit).
        result = null
        push.registerToken("tok", explicit = true) { result = it }
        assertTrue(pumpUntil { result != null })
        assertEquals(3, api.seen.count { it.path.endsWith("/v1/push/register") })
    }

    @Test
    fun `a registration still in flight when unregisterPush runs leaves no stale mark, and an explicit re-register is sent`() {
        // Fails before: the in-flight registration's success wrote the
        // "registered" mark AFTER the unregister cleared it, so the next
        // registerPushToken() with the same token was skipped as "already
        // registered" while the server had it switched off.
        val storage = storage()
        var unregisterMidFlight = true
        api.answers["/v1/push/register"] = {
            // unregisterPush() lands while this request is on the wire.
            if (unregisterMidFlight) storage.setPushUnregistered(true)
            TestServer.json("""{"success":true}""")
        }
        val push = PushNotificationManager(app, network(), IdentityManager(storage, logger), storage, logger)
        var result: PushRegistrationResult? = null
        push.registerToken("tok") { result = it }
        assertTrue(pumpUntil { result != null })
        assertNull(storage.getRegisteredPushKey())
        unregisterMidFlight = false

        // An explicit registration is sent even with a matching mark.
        storage.setPushUnregistered(false)
        result = null
        push.registerToken("tok", explicit = true) { result = it }
        assertTrue(pumpUntil { result != null })
        result = null
        push.registerToken("tok", explicit = true) { result = it }
        assertTrue(pumpUntil { result != null })
        assertEquals(3, api.seen.count { it.path.endsWith("/v1/push/register") })
    }

    // ── Sessions ─────────────────────────────────────────────────────────

    @Test
    fun `an expired session is not revived by activity, and foreground starts a new one`() {
        // Fails before 1.3.0: updateActivity() refreshed the expired session,
        // so it never ended while the process lived.
        // Generous: Robolectric's first SharedPreferences writes can take
        // hundreds of milliseconds, and the second checkAndRenew below must
        // land inside the timeout.
        val sessions = SessionManager(storage(), logger, sessionTimeout = 3_000)
        // Started on the lifecycle path: a read never starts one (contract 54).
        sessions.checkAndRenew()
        val first = sessions.getSessionId()
        Thread.sleep(3_200)
        sessions.updateActivity()
        assertTrue("foreground after the timeout starts a session", sessions.checkAndRenew())
        assertNotEquals(first, sessions.getSessionId())
        assertFalse("a live session is extended, not replaced", sessions.checkAndRenew())
    }

    // ── Encrypted storage ────────────────────────────────────────────────

    @Test
    fun `an undecryptable store is recreated, not downgraded to plaintext`() {
        // Fails before 1.3.0: any open failure fell back to plaintext, forever.
        val encrypted = app.getSharedPreferences("enc-test", 0)
        val plaintext = app.getSharedPreferences("plain-test", 0)
        var discarded = false
        val result = SecurePrefs.openWithRecovery(
            open = {
                // Restored from backup without its Keystore key: Tink's AEAD
                // tag check fails on every open until the file is replaced.
                if (!discarded) {
                    throw java.security.GeneralSecurityException(
                        "keyset", javax.crypto.AEADBadTagException("restored without its key"),
                    )
                }
                encrypted
            },
            discardEncrypted = { discarded = true },
            plaintextFallback = { plaintext },
            logger = null,
            retryDelayMs = 0,
        )
        assertSame(encrypted, result)
        assertTrue(discarded)
    }

    @Test
    fun `a transient keystore error never deletes the encrypted store`() {
        // Fails on 44379f8e1 (scan 5, Android S6): ANY first failure deleted
        // the store - identity, outbox and its unsubscribes included.
        val encrypted = app.getSharedPreferences("enc-test-3", 0)
        val plaintext = app.getSharedPreferences("plain-test-3", 0)
        var attempts = 0
        var discarded = false
        val healed = SecurePrefs.openWithRecovery(
            open = {
                attempts++
                if (attempts == 1) throw java.security.KeyStoreException("busy after boot") else encrypted
            },
            discardEncrypted = { discarded = true },
            plaintextFallback = { plaintext },
            logger = null,
            retryDelayMs = 0,
        )
        assertSame("retried once and opened", encrypted, healed)
        assertFalse(discarded)

        val stillDown = SecurePrefs.openWithRecovery(
            open = { throw java.security.ProviderException("keystore unavailable") },
            discardEncrypted = { discarded = true },
            plaintextFallback = { plaintext },
            logger = null,
            retryDelayMs = 0,
        )
        assertSame("plaintext for this launch", plaintext, stillDown)
        assertFalse("nothing deleted", discarded)
    }

    @Test
    fun `writes made on the plaintext fallback are merged into the encrypted store`() {
        // Fails on 44379f8e1 (scan 5, Android S6): once migrated, a fallback
        // launch's plaintext file was never read again - its queued
        // unsubscribe stayed on disk in cleartext and was never sent.
        val encrypted = app.getSharedPreferences("enc-merge", 0)
        val plaintext = app.getSharedPreferences("plain-merge", 0)
        encrypted.edit()
            .putString("anonymous_id", "real-anon")
            .putString("write_outbox", "[{\"id\":\"a\",\"kind\":\"identify\"}]")
            .commit()
        plaintext.edit()
            .putString("anonymous_id", "fallback-anon")
            .putString("user_id", "u1")
            .putString("write_outbox", "[{\"id\":\"b\",\"kind\":\"unsubscribe\"}]")
            .commit()
        SecurePrefs.mergeInto(plaintext, encrypted, firstMigration = false) { key, enc, plain ->
            io.joryio.sdk.core.StorageManager.mergeFallbackValue(key, enc, plain)
        }
        assertEquals("the encrypted identity stays", "real-anon", encrypted.getString("anonymous_id", null))
        assertEquals("u1", encrypted.getString("user_id", null))
        val outbox = encrypted.getString("write_outbox", "")!!
        assertTrue(outbox, outbox.contains("\"a\"") && outbox.contains("\"b\""))
        assertTrue("no cleartext left", plaintext.all.isEmpty())
    }

    @Test
    fun `plaintext stays the last resort when the keystore is truly broken`() {
        val plaintext: SharedPreferences = app.getSharedPreferences("plain-test-2", 0)
        val result = SecurePrefs.openWithRecovery(
            open = { throw SecurityException("no keystore") },
            discardEncrypted = {},
            plaintextFallback = { plaintext },
            logger = null,
        )
        assertSame(plaintext, result)
    }

    // ── Queue database ───────────────────────────────────────────────────

    @Test
    fun `upgrading the v1 queue migrates it instead of deleting it, into the no-backup directory`() = runBlocking {
        // Fails before 1.3.0: fallbackToDestructiveMigration dropped every
        // queued event on the v1 -> v2 upgrade.
        val legacy = app.getDatabasePath(JoryioDatabase.fileName(key))
        legacy.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(legacy, null).use { db ->
            db.execSQL(
                "CREATE TABLE `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                    "`properties` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `userId` TEXT, " +
                    "`anonymousId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`retryCount` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO events (type, properties, timestamp, userId, anonymousId, sessionId, createdAt, retryCount) " +
                    "VALUES ('Queued Offline', '{\"n\":1}', 1, NULL, 'anon', 'sess', 1, 0)",
            )
            db.version = 1
        }

        val db = JoryioDatabase.getInstance(app, key)
        val rows = db.eventDao().getAllEvents()
        assertEquals(1, rows.size)
        assertEquals("Queued Offline", rows[0].type)
        assertTrue(rows[0].eventId.length == 32)
        assertTrue(JoryioDatabase.databaseFile(app, key).path.startsWith(app.noBackupFilesDir.path))
        assertFalse(legacy.exists())
        db.close()
    }

    // ── Push permission on a plain Activity (Unity) ──────────────────────

    @Test
    fun `a plain Activity reports the permission answer when the dialog closes, not before`() {
        // Fails before 1.3.0: the callback fired synchronously with DENIED.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var answered: PushPermissionStatus? = null
        PushPermission.request(activity) { answered = it }
        assertNull("no answer while the dialog is up", answered)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(JoryioPermissionActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `the public permission names match the other SDKs`() {
        assertEquals("notDetermined", PushPermissionStatus.NOT_DETERMINED.publicName)
        assertEquals("granted", PushPermissionStatus.GRANTED.publicName)
        // ...while the attribute wire value stays snake_case.
        assertEquals("not_determined", PushPermissionStatus.NOT_DETERMINED.name.lowercase())
    }
}
