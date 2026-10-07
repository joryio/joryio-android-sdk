package io.joryio.sdk.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadKeysTest {

    // The wire names, spelled out. The backend's push-payload.contract.ts is
    // the source; a rename there must fail here as well as in its parity spec.
    @Test
    fun keysMatchTheBackendContract() {
        assertEquals("joryio", PushPayloadKeys.MARKER)
        assertEquals("true", PushPayloadKeys.MARKER_VALUE)
        assertEquals("trackingId", PushPayloadKeys.TRACKING_ID)
        assertEquals("jry_campaign_id", PushPayloadKeys.CAMPAIGN_ID)
        assertEquals("jry_deep_link", PushPayloadKeys.DEEP_LINK)
        assertEquals("jry_image_url", PushPayloadKeys.IMAGE_URL)
        assertEquals("jry_sync", PushPayloadKeys.SYNC)
    }

    // What a campaign push looks like now: the marker on every message. Before,
    // only the sync nudge carried it and campaign pushes were dropped.
    @Test
    fun campaignPushIsRecognised() {
        val campaign = mapOf(
            "joryio" to "true",
            "trackingId" to "abc.def",
            "jry_campaign_id" to "camp-1",
            "jry_deep_link" to "myapp://sale",
        )
        assertTrue(PushPayloadKeys.isJoryio(campaign))
        assertFalse(PushPayloadKeys.isJoryio(mapOf("trackingId" to "abc.def")))
        assertFalse(PushPayloadKeys.isJoryio(mapOf("joryio" to "false")))
    }

    @Test
    fun notificationIdIsStablePerTrackingIdAndNonNegative() {
        val a = PushPayloadKeys.notificationId("abc.def")
        assertEquals(a, PushPayloadKeys.notificationId("abc.def"))
        assertTrue(a >= 0)
        assertTrue(PushPayloadKeys.notificationId("other") != a)
        assertTrue(PushPayloadKeys.notificationId(null) >= 0)
    }

    private fun extras(vararg pairs: Pair<String, String>): (String) -> String? {
        val m = mapOf(*pairs)
        return { m[it] }
    }

    @Test
    fun clickOnSystemDrawnNotificationIsTracked() {
        val id = PushPayloadKeys.clickToTrack(
            extras("joryio" to "true", "trackingId" to "abc.def"),
            alreadyTracked = false,
            launchedFromHistory = false,
        )
        assertEquals("abc.def", id)
    }

    @Test
    fun clickIsNotTrackedTwiceOrFromRecentsOrForForeignPushes() {
        val joryio = extras("joryio" to "true", "trackingId" to "abc.def")
        assertNull(PushPayloadKeys.clickToTrack(joryio, alreadyTracked = true, launchedFromHistory = false))
        assertNull(PushPayloadKeys.clickToTrack(joryio, alreadyTracked = false, launchedFromHistory = true))
        assertNull(
            PushPayloadKeys.clickToTrack(
                extras("trackingId" to "someone-elses"),
                alreadyTracked = false,
                launchedFromHistory = false,
            ),
        )
        // A test send carries no trackingId: nothing to report.
        assertNull(
            PushPayloadKeys.clickToTrack(extras("joryio" to "true"), alreadyTracked = false, launchedFromHistory = false),
        )
    }
}
