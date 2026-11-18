package io.joryio.sdk

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.joryio.sdk.core.ClickIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Click-link identity: a tracked message link opens the app with `jr_t`, and
 * identifyFromLink sends it with the anonymous id to POST v1/identify-link -
 * once, only while tracking is allowed and nobody is identified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClickIdentityTest {

    private lateinit var api: TestServer
    private lateinit var app: Application

    private val token = "AbCdEfGhIjKlMnOp_qrstuv-0123456789" // gitleaks:allow - a fake jr_t test token
    private val link = "https://shop.example.com/sale?utm_source=joryio&jr_cid=c1&jr_t=$token#top"

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

    private fun init(config: JoryioConfig = JoryioConfig(flushInterval = 60_000, trackSessionStart = false)): Joryio =
        Joryio.initialize(app, "jry_sdk_android_test_${UUID.randomUUID()}", api.apiHost, config)

    private fun linkBodies() = api.seen.filter { it.path.endsWith("/v1/identify-link") }.map { it.body }

    @Test
    fun `the token is read from the query only when well-formed`() {
        assertEquals(token, ClickIdentity.tokenFrom(link))
        assertEquals(token, ClickIdentity.tokenFrom("myapp://open?jr_t=$token"))
        assertNull(ClickIdentity.tokenFrom("https://shop.example.com/sale?jr_cid=c1"))
        assertNull(ClickIdentity.tokenFrom("https://shop.example.com/sale#jr_t=$token"))
        assertNull("too short", ClickIdentity.tokenFrom("https://x.example/?jr_t=short"))
        assertNull("bad characters", ClickIdentity.tokenFrom("https://x.example/?jr_t=abcdefghijklmnop%2Fq"))
        assertNull(ClickIdentity.tokenFrom(null))
        assertNull(ClickIdentity.tokenFrom(""))
    }

    @Test
    fun `sends exactly token and anonymousId, once per token`() {
        val sdk = init()
        sdk.identifyFromLink(Uri.parse(link))
        sdk.identifyFromLink(link)
        assertTrue(pumpUntil { linkBodies().isNotEmpty() })
        pumpFor(400)
        assertEquals(1, linkBodies().size)
        val body = com.google.gson.JsonParser.parseString(linkBodies().single()).asJsonObject
        assertEquals(setOf("token", "anonymousId"), body.keySet())
        assertEquals(token, body["token"].asString)
        assertEquals(sdk.getIdentity().second, body["anonymousId"].asString)
    }

    @Test
    fun `a url without jr_t sends nothing`() {
        val sdk = init()
        sdk.identifyFromLink("https://shop.example.com/sale?jr_cid=c1")
        pumpFor(400)
        assertEquals(emptyList<String>(), linkBodies())
    }

    @Test
    fun `an identified user is never re-attached by a link`() {
        val sdk = init()
        sdk.identify("user-1")
        sdk.identifyFromLink(link)
        pumpFor(500)
        assertEquals(emptyList<String>(), linkBodies())
    }

    @Test
    fun `opted out or consent denied - the token is dropped`() {
        val sdk = init()
        sdk.optOut()
        sdk.identifyFromLink(link)
        pumpFor(300)
        sdk.optIn()
        pumpFor(300)
        assertEquals(emptyList<String>(), linkBodies())

        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.identifyFromLink(link)
        sdk.updateConsent(TrackingConsent.GRANTED)
        pumpFor(300)
        assertEquals(emptyList<String>(), linkBodies())
    }

    @Test
    fun `consent pending - held in memory and sent on GRANTED`() {
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackSessionStart = false, trackingConsent = TrackingConsent.PENDING))
        sdk.identifyFromLink(link)
        pumpFor(400)
        assertEquals(emptyList<String>(), linkBodies())
        sdk.updateConsent(TrackingConsent.GRANTED)
        assertTrue(pumpUntil { linkBodies().size == 1 })
    }

    @Test
    fun `consent pending then denied - the held token is dropped`() {
        val sdk = init(JoryioConfig(flushInterval = 60_000, trackSessionStart = false, trackingConsent = TrackingConsent.PENDING))
        sdk.identifyFromLink(link)
        sdk.updateConsent(TrackingConsent.DENIED)
        sdk.updateConsent(TrackingConsent.GRANTED)
        pumpFor(400)
        assertEquals(emptyList<String>(), linkBodies())
    }

    @Test
    fun `before initialize - held and handled at init`() {
        Joryio.identifyFromLink(link)
        init()
        assertTrue(pumpUntil { linkBodies().size == 1 })
    }

    @Test
    fun `before initialize then opted out - dropped`() {
        Joryio.identifyFromLink(link)
        Joryio.optOut()
        init()
        pumpFor(500)
        assertEquals(emptyList<String>(), linkBodies())
    }

    @Test
    fun `a 4xx is not retried`() {
        api.answers["/v1/identify-link"] = { TestServer.json("""{"success":false}""", code = 400) }
        val sdk = init()
        sdk.identifyFromLink(link)
        assertTrue(pumpUntil { linkBodies().isNotEmpty() })
        pumpFor(600)
        assertEquals(1, linkBodies().size)
    }
}
