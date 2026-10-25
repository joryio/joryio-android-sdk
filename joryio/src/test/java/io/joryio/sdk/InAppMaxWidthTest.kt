package io.joryio.sdk

import com.google.gson.Gson
import io.joryio.sdk.models.InAppCampaign
import io.joryio.sdk.models.InAppSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Responsive HTML in-app messages: a modal or slide-up is
 * min(maxWidth, screen width - margins) wide, as on web and iOS. Before this
 * an HTML modal spanned a tablet edge to edge.
 */
class InAppMaxWidthTest {
    private fun campaign(type: String, maxWidth: Number? = null, kind: String = "html"): InAppCampaign {
        val mw = if (maxWidth != null) ""","maxWidth":$maxWidth""" else ""
        val json = """{"id":"c-$type","name":"n","type":"$type","content":{"kind":"$kind","html":"<p>Hi</p>","css":"","body":"Hi"$mw}}"""
        return Gson().fromJson(json, InAppCampaign::class.java)
    }

    @Test
    fun `maxWidth comes from the payload, with the same defaults as every SDK`() {
        assertEquals(680, campaign("modal", 680).maxWidth)
        assertEquals(600, campaign("modal").maxWidth)
        assertEquals(400, campaign("slideup").maxWidth)
        assertNull(campaign("banner", 680).maxWidth)
        assertNull(campaign("fullscreen", 680).maxWidth)
        assertNull(campaign("modal", 680, kind = "native").maxWidth)
    }

    @Test
    fun `frame width is the smaller of maxWidth and the screen`() {
        assertEquals(680, InAppSizing.frameWidth(680, 1024, 24))
        assertEquals(342, InAppSizing.frameWidth(600, 390, 24))
        assertEquals(0, InAppSizing.frameWidth(600, 10, 24))
    }
}
