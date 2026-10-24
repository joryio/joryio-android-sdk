package io.joryio.sdk.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An HTML in-app message stays inside its own document (shared SDK contract
 * item 57; scan 5, iOS S1 - checked on Android here). Each test fails on
 * 44379f8e1, where none of these rules existed.
 */
class HtmlMessageContainmentTest {

    @Test
    fun `author CSS cannot close its style element`() {
        val css = """p{color:red}</style><meta http-equiv="refresh" content="0;url=https://evil.example/">"""
        val escaped = InAppMessageRenderer.escapeCss(css)
        assertFalse(escaped, escaped.contains("</"))
        assertTrue(escaped.contains("<\\/style>"))
        assertEquals("", InAppMessageRenderer.escapeCss(null))
        assertEquals("a{b:c}", InAppMessageRenderer.escapeCss("a{b:c}"))
    }

    @Test
    fun `only a tapped http link leaves the message, a navigation the page starts opens nothing`() {
        assertTrue(InAppMessageRenderer.navigationOpensExternally("https://joryio.com/x", tapped = true))
        // A meta refresh / script redirect: cancelled, and the browser is not opened either.
        assertFalse(InAppMessageRenderer.navigationOpensExternally("https://evil.example/", tapped = false))
        assertFalse(InAppMessageRenderer.navigationOpensExternally("javascript:alert(1)", tapped = true))
        assertFalse(InAppMessageRenderer.navigationOpensExternally("intent://x#Intent;end", tapped = true))
    }

    @Test
    fun `only the initial document counts as the message`() {
        assertTrue(InAppMessageRenderer.isInitialDocument("about:blank"))
        assertFalse(InAppMessageRenderer.isInitialDocument("https://evil.example/"))
    }

    @Test
    fun `an image body is capped while it is read`() {
        // Scan 5, Android NICE 9: a chunked body (no Content-Length) was read
        // whole before the size check.
        val big = ByteArray(64 * 1024) { 1 }
        assertNull(BoundedRead.read(big.inputStream(), max = 32 * 1024))
        val small = ByteArray(1000) { 2 }
        assertArrayEquals(small, BoundedRead.read(small.inputStream(), max = 32 * 1024))
    }
}
