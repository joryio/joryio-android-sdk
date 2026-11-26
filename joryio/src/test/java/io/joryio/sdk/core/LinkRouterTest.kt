package io.joryio.sdk.core

import io.joryio.sdk.Joryio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-wide link handler (`Joryio.setLinkHandler`): the routing decision
 * every surface shares and the refused schemes. The same cases as iOS
 * LinkHandlerTests and the web SDK's links.spec.ts.
 */
class LinkRouterTest {
    @After
    fun clear() = Joryio.clearLinkHandler()

    private fun route(url: String, source: JoryioLinkSource = JoryioLinkSource.PLACEMENT): Pair<Boolean, Int> {
        var fallback = 0
        val routed = LinkRouter.route(url, source, placementKey = "home_hero", itemId = "i1") { fallback++ }
        return routed to fallback
    }

    @Test
    fun withoutAHandlerTheFallbackRuns() {
        assertFalse(LinkRouter.hasHandler)
        for (url in listOf("https://shop.example/sale", "myapp://cart")) {
            assertEquals(url, true to 1, route(url))
        }
    }

    @Test
    fun aHandlerThatTakesTheLinkStopsTheFallback() {
        val seen = mutableListOf<JoryioLink>()
        Joryio.setLinkHandler { seen.add(it); true }
        assertTrue(LinkRouter.hasHandler)
        assertEquals(true to 0, route("https://shop.example/sale"))
        assertEquals(true to 0, route("myapp://cart"))
        // Every link, http(s) included, with its context.
        assertEquals(listOf("https://shop.example/sale", "myapp://cart"), seen.map { it.url })
        assertEquals(listOf("placement", "placement"), seen.map { it.source.wire })
        assertEquals(listOf(false, true), seen.map { it.isAppDeepLink })
        assertEquals("home_hero", seen[0].placementKey)
        assertEquals("i1", seen[0].itemId)
    }

    @Test
    fun aHandlerThatDeclinesOrThrowsFallsBack() {
        var calls = 0
        Joryio.setLinkHandler { calls++; false }
        assertEquals(true to 1, route("https://shop.example/sale"))
        Joryio.setLinkHandler { throw IllegalStateException("boom") }
        assertEquals(true to 1, route("https://shop.example/sale"))
        assertEquals(1, calls)
    }

    @Test
    fun openDefaultLaterRunsTheFallbackOnce() {
        var held: JoryioLink? = null
        Joryio.setLinkHandler { held = it; true }
        var fallback = 0
        LinkRouter.route("myapp://cart", JoryioLinkSource.CARD, campaignId = "camp", cardId = "c1") { fallback++ }
        assertEquals(0, fallback)
        held!!.openDefault()
        held!!.openDefault()
        assertEquals(1, fallback)
        assertEquals("c1", held!!.cardId)
        assertEquals("camp", held!!.campaignId)
    }

    @Test
    fun refusedSchemesReachNeitherTheHandlerNorTheSystem() {
        var calls = 0
        Joryio.setLinkHandler { calls++; false }
        for (url in listOf(
            "javascript:alert(1)", "data:text/html,x", "file:///etc/hosts", "vbscript:x", "blob:https://x/1",
            "about:blank", "content://x", "intent://x#Intent;end", "mailto:a@b.example", "", "not a url",
        )) {
            assertEquals(url, false to 0, route(url, JoryioLinkSource.IN_APP))
        }
        assertEquals(0, calls)
    }

    @Test
    fun sourceWireValues() {
        assertEquals(listOf("in_app", "placement", "card", "push"), JoryioLinkSource.values().map { it.wire })
    }

    @Test
    fun theInstanceAndTheStaticFormsShareOneHandler() {
        Joryio.setLinkHandler { true }
        assertTrue(LinkRouter.hasHandler)
        Joryio.clearLinkHandler()
        assertFalse(LinkRouter.hasHandler)
    }
}
