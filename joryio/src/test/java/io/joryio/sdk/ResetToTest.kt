package io.joryio.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.PreInitStore
import io.joryio.sdk.core.ResetTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * resetTo(anonymousId) (BACKLOG D366): reset(), then the caller's anonymous
 * id instead of a random one. Same rule on all five SDKs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResetToTest {
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
        app.getSharedPreferences("joryio_decisions", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        PreInitStore.clear()
        api.shutdown()
    }

    private fun cfg() = JoryioConfig(flushInterval = 60_000, maxRetries = 0, trackSessionStart = false)
    private fun start() = Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, cfg())

    @Test
    fun `the rule accepts tracker ids and refuses bad shapes, profile ids and user ids`() {
        assertNull(ResetTo.refusal("0b9c6c1e-6f43-4f7a-9d2a-6f0b2c4d5e6f", emptyList()))
        assertNull(ResetTo.refusal("jry_0b9c6c1e-6f43-4f7a-9d2a-6f0b2c4d5e6f", emptyList()))
        assertNull(ResetTo.refusal("a".repeat(255), emptyList()))
        assertNotNull(ResetTo.refusal("short", emptyList()))
        assertNotNull(ResetTo.refusal("a".repeat(256), emptyList()))
        assertNotNull(ResetTo.refusal("ada@example.com", emptyList()))
        assertNotNull(ResetTo.refusal(null, emptyList()))
        assertNotNull(ResetTo.refusal("65f1a2b3c4d5e6f708192a3b", emptyList()))
        assertNotNull(ResetTo.refusal("customer-1042", listOf(null, "customer-1042")))
        // The anonymous id held while a user is signed in.
        assertNotNull(ResetTo.refusal("jry_alice-device-0001", listOf("alice"), "jry_alice-device-0001"))
        assertNull(ResetTo.refusal("jry_alice-device-0001", listOf(null), null))
    }

    @Test
    fun `resetTo logs the user out and adopts the given id synchronously`() {
        val sdk = start()
        sdk.identify("user-a")
        var adopted: Boolean? = null
        sdk.resetTo("jry_tracker-visitor-0001") { adopted = it }
        assertEquals(true, adopted)
        assertNull(sdk.getUserId())
        assertEquals("jry_tracker-visitor-0001", sdk.getAnonymousId())
        sdk.track("after")
        sdk.flush()
        assertTrue(pumpUntil(6_000) { api.seen.any { it.body.contains("\"after\"") && it.body.contains("jry_tracker-visitor-0001") } })
    }

    @Test
    fun `a refused id is a plain reset - logged out with a random id`() {
        val sdk = start()
        sdk.identify("customer-1042")
        val before = sdk.getAnonymousId()
        var adopted: Boolean? = null
        sdk.resetTo("customer-1042") { adopted = it }
        assertEquals(false, adopted)
        assertNull(sdk.getUserId())
        assertNotEquals("customer-1042", sdk.getAnonymousId())
        assertNotEquals(before, sdk.getAnonymousId())
    }

    @Test
    fun `the companion resetTo reaches the instance`() {
        start()
        Joryio.resetTo("hp_abcdef0123456789")
        assertEquals("hp_abcdef0123456789", Joryio.getInstance().getAnonymousId())
    }

    @Test
    fun `the anonymous id the signed-in user had is refused - the next person must not inherit their profile`() {
        val sdk = start()
        sdk.resetTo("jry_alice-device-0001")
        sdk.identify("alice")
        var adopted: Boolean? = null
        sdk.resetTo("jry_alice-device-0001") { adopted = it }
        assertEquals(false, adopted)
        assertNull(sdk.getUserId())
        assertNotEquals("jry_alice-device-0001", sdk.getAnonymousId())
    }

    @Test
    fun `a companion call held before initialize answers when it runs`() {
        var adopted: Boolean? = null
        Joryio.resetTo("hp_abcdef0123456789") { adopted = it }
        assertNull(adopted)
        start()
        assertTrue(pumpUntil(6_000) { adopted != null })
        assertEquals(true, adopted)
        assertEquals("hp_abcdef0123456789", Joryio.getInstance().getAnonymousId())
    }

    @Test
    fun `an anonymous id a signed-in user held before an earlier reset is refused too`() {
        val sdk = start()
        sdk.resetTo("jry_alice-device-0001")
        sdk.identify("alice")
        sdk.reset()
        var adopted: Boolean? = null
        sdk.resetTo("jry_alice-device-0001") { adopted = it }
        assertEquals(false, adopted)
        assertNotEquals("jry_alice-device-0001", sdk.getAnonymousId())
    }

    @Test
    fun `the remembered list keeps the newest 20 and wipeData forgets it`() {
        var list = emptyList<String>()
        for (i in 1..25) list = ResetTo.remember(list, "jry_device-%04d".format(i))
        assertEquals(ResetTo.SIGNED_IN_ANONYMOUS_IDS_MAX, list.size)
        assertEquals("jry_device-0006", list.first())
        assertEquals("jry_device-0025", list.last())
        // A repeat moves to the newest end instead of duplicating.
        assertEquals(listOf("jry_device-a", "jry_device-b"), ResetTo.remember(listOf("jry_device-b", "jry_device-a"), "jry_device-b"))

        val sdk = start()
        sdk.resetTo("jry_alice-device-0002")
        sdk.identify("alice")
        sdk.reset()
        sdk.wipeData()
        var adopted: Boolean? = null
        sdk.resetTo("jry_alice-device-0002") { adopted = it }
        assertEquals(true, adopted)
    }

    @Test
    fun `a held resetTo discarded by a pre-initialize wipeData answers false, once`() {
        val answers = mutableListOf<Boolean>()
        Joryio.resetTo("hp_abcdef0123456789") { answers.add(it) }
        Joryio.wipeData()
        assertEquals(listOf(false), answers)
        start()
        pumpUntil(1_000) { false }
        assertEquals(listOf(false), answers)
    }
}
