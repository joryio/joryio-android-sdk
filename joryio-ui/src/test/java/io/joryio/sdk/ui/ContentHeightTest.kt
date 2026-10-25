package io.joryio.sdk.ui

import io.joryio.sdk.models.InAppContent
import io.joryio.sdk.models.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D204: an HTML message's height follows its content in BOTH directions. The
 * shim used to report max(body, root) scrollHeight, and the root's is never
 * below the WebView's own height - so a message could grow and never shrink.
 */
class ContentHeightTest {
    @Test
    fun `the height follows the content down as well as up, capped at 90 percent of the screen`() {
        assertEquals(240, InAppMessageRenderer.contentHeightPx(80, 3f, 2400))
        assertEquals(150, InAppMessageRenderer.contentHeightPx(50, 3f, 2400)) // smaller than before: shrinks
        assertEquals(2160, InAppMessageRenderer.contentHeightPx(5000, 3f, 2400))
        assertEquals(1, InAppMessageRenderer.contentHeightPx(0, 3f, 2400))
    }

    @Test
    fun `a partial message measures its body, not the root`() {
        val doc = InAppMessageRenderer().buildDocument("c", InAppContent.Html("<p>x</p>", ""), MessageType.MODAL)
        assertTrue(doc.contains("body{display:flow-root;}"))
        assertTrue(doc.contains("var h = Math.ceil(Math.max(b.scrollHeight, b.getBoundingClientRect().height));"))
        assertFalse(doc.contains("document.documentElement.scrollHeight : 0"))
        val full = InAppMessageRenderer().buildDocument("c", InAppContent.Html("<p>x</p>", ""), MessageType.FULLSCREEN)
        assertFalse(full.contains("body{display:flow-root;}"))
    }

    @Test
    fun `the link regex reaches the page with single backslashes, so the bootstrap parses`() {
        // A raw-string `\\/` arrived as `\\/` and ended the regex early: a
        // SyntaxError that took the whole bootstrap down on device.
        val doc = InAppMessageRenderer().buildDocument("c", InAppContent.Html("<p>x</p>", ""), MessageType.MODAL)
        assertTrue(doc.contains("""/^(https?:\/\/|\/(?!\/))/i.test(href)"""))
        assertFalse(doc.contains("""\\/"""))
    }
}
