package io.joryio.sdk

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.models.InAppCampaign
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The in-app form token (BACKLOG D371 item 4). The server serves a signed
 * `formToken` with a message that carries a form; the SDK sends it back,
 * unchanged, on POST /v1/in-app/form-submit - from the bridge of a message the
 * SDK drew, and from the public submitInAppForm a host-rendered message
 * (Unity, React Native) calls with only the campaign id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InAppFormTokenTest {

    private lateinit var api: TestServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        Joryio.resetForTesting()
        api = TestServer()
        app = ApplicationProvider.getApplicationContext()
        allowNetwork(app)
        warmUpSqlite()
    }

    @After
    fun tearDown() {
        Joryio.resetForTesting()
        api.shutdown()
    }

    private fun init(campaignJson: String): Joryio {
        api.answers["/v1/in-app/sync"] = {
            TestServer.json("""{"success":true,"evaluatedAt":"x","campaigns":[$campaignJson]}""")
        }
        return Joryio.initialize(
            app,
            "jry_sdk_android_test_${UUID.randomUUID()}",
            api.apiHost,
            JoryioConfig(flushInterval = 60_000, trackSessionStart = false),
        )
    }

    private fun formSubmits() = api.seen.filter { it.path.endsWith("/v1/in-app/form-submit") }

    @Test
    fun `a host-rendered form submit carries the synced form token`() {
        val sdk = init("""{"id":"c1","name":"One","type":"modal","formToken":"opaque.form.token"}""")
        val presented = mutableListOf<String>()
        sdk.setInAppPresenter(object : InAppMessagePresenter {
            override fun present(activity: Activity, campaign: InAppCampaign, onEvent: (String, Map<String, Any?>) -> Unit): Boolean {
                presented.add(campaign.id)
                return true
            }
        })
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { presented.contains("c1") })
        var answered = false
        sdk.submitInAppForm("c1", mapOf("email" to "ada@example.com")) { answered = true }
        assertTrue(pumpUntil { answered && formSubmits().isNotEmpty() })
        val body = formSubmits().first().body
        assertTrue(body, body.contains("\"formToken\":\"opaque.form.token\""))
        assertTrue(body, body.contains("\"campaignId\":\"c1\""))
    }

    @Test
    fun `a presented token survives an identify and goes with a reset - the same on every SDK`() {
        val logger = io.joryio.sdk.core.Logger(false, io.joryio.sdk.LogLevel.ERROR)
        val key = "jry_sdk_android_test_${UUID.randomUUID()}"
        val storage = io.joryio.sdk.core.StorageManager(app, key, logger)
        val manager = io.joryio.sdk.messaging.InAppMessagingManager(
            networkClient = io.joryio.sdk.network.NetworkClient(context = app, sdkKey = key, apiHost = api.apiHost, logger = logger, maxRetries = 0),
            identityManager = io.joryio.sdk.core.IdentityManager(storage, logger),
            sessionManager = io.joryio.sdk.core.SessionManager(storage, logger),
            storage = storage,
            logger = logger,
        )
        val resolved = io.joryio.sdk.network.WireJson.gson.fromJson(
            "{\"id\":\"r1\",\"name\":\"r1\",\"type\":\"modal\",\"formToken\":\"resolved.form.token\"}",
            InAppCampaign::class.java,
        )
        manager.rememberFormToken(resolved)
        manager.invalidate() // identify, consent change
        assertTrue(manager.formTokenFor("r1") == "resolved.form.token")
        manager.clear() // reset, wipeData
        assertTrue(manager.formTokenFor("r1") == null)
    }

    @Test
    fun `no token served (an older server) - none sent`() {
        val sdk = init("""{"id":"c1","name":"One","type":"modal"}""")
        val presented = mutableListOf<String>()
        sdk.setInAppPresenter(object : InAppMessagePresenter {
            override fun present(activity: Activity, campaign: InAppCampaign, onEvent: (String, Map<String, Any?>) -> Unit): Boolean {
                presented.add(campaign.id)
                return true
            }
        })
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(pumpUntil { presented.contains("c1") })
        var answered = false
        sdk.submitInAppForm("c1", mapOf("email" to "ada@example.com")) { answered = true }
        assertTrue(pumpUntil { answered && formSubmits().isNotEmpty() })
        assertFalse(formSubmits().first().body.contains("formToken"))
    }
}
