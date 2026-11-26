package io.joryio.sdk.ui

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML placement's WebView cancels every navigation on every API level
 * (security review 2026-11-21): below API 24 the WebView calls the deprecated
 * `shouldOverrideUrlLoading(view, url: String)`, which PlacementView did not
 * override, so its default `false` let the page navigate (joryio-ui minSdk 23).
 */
class PlacementWebViewClientTest {
    @Test
    fun bothOverloadsAreOverridden() {
        val cls = PlacementView.PlacementWebViewClient::class.java
        cls.getDeclaredMethod("shouldOverrideUrlLoading", WebView::class.java, String::class.java)
        cls.getDeclaredMethod("shouldOverrideUrlLoading", WebView::class.java, android.webkit.WebResourceRequest::class.java)
    }

    @Test
    fun theDeprecatedOverloadAlwaysCancelsAndOpensNothingWithoutATap() {
        val opened = mutableListOf<String>()
        val client = PlacementView.PlacementWebViewClient(now = { 5_000L }) { opened.add(it) }
        // No touch yet: a meta refresh / script redirect is cancelled and opens nothing.
        for (url in listOf("https://evil.example/", "javascript:alert(1)", "intent://x#Intent;end", "myapp://cart", null)) {
            @Suppress("DEPRECATION")
            assertTrue(client.shouldOverrideUrlLoading(null, url))
        }
        assertEquals(emptyList<String>(), opened)
        // A touch-up just before: a tapped http(s) link opens (and is still cancelled in the WebView); other schemes never.
        client.lastTouchAt = 4_900L
        @Suppress("DEPRECATION")
        assertTrue(client.shouldOverrideUrlLoading(null, "javascript:alert(1)"))
        @Suppress("DEPRECATION")
        assertTrue(client.shouldOverrideUrlLoading(null, "https://shop.example/p"))
        assertEquals(listOf("https://shop.example/p"), opened)
    }

    @Test
    fun aTappedHttpLinkOpensAnythingElseDoesNot() {
        val opened = mutableListOf<String>()
        val client = PlacementView.PlacementWebViewClient(now = { 0L }) { opened.add(it) }
        client.route("https://shop.example/p", tapped = true)
        client.route("https://evil.example/", tapped = false)
        for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "data:text/html,x", "myapp://cart")) client.route(url, tapped = true)
        assertEquals(listOf("https://shop.example/p"), opened)
    }

    @Test
    fun theLegacyTapIsATouchUpWithinTheInAppWindow() {
        assertFalse(PlacementView.PlacementWebViewClient.legacyTapped(now = 5_000L, lastTouchAt = 0L))
        assertTrue(PlacementView.PlacementWebViewClient.legacyTapped(now = 5_000L, lastTouchAt = 5_000L - InAppMessageRenderer.TAP_WINDOW_MS + 1))
        assertFalse(PlacementView.PlacementWebViewClient.legacyTapped(now = 5_000L, lastTouchAt = 5_000L - InAppMessageRenderer.TAP_WINDOW_MS))
    }
}
