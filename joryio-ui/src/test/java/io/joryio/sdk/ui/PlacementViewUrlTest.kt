package io.joryio.sdk.ui

import io.joryio.sdk.placements.PLACEMENT_PRODUCT_CARD_WIDTH
import io.joryio.sdk.placements.PLACEMENT_PRODUCT_ELEMENT_PREFIX
import io.joryio.sdk.placements.PlacementProduct
import io.joryio.sdk.placements.placementProductElementId
import io.joryio.sdk.ui.PlacementView.LinkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PlacementView hands the system http(s) links only - the in-app renderer's rule - and the app only isAppDeepLink links. */
class PlacementViewUrlTest {
    @Test
    fun onlyHttpAndHttpsAreOpened() {
        assertTrue(PlacementView.isSafeUrl("https://shop.example/sale"))
        assertTrue(PlacementView.isSafeUrl("HTTP://shop.example"))
        for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///data/x", "content://x", "myapp://cart", "data:text/html,x")) {
            assertFalse(url, PlacementView.isSafeUrl(url))
        }
    }

    @Test
    fun anAppDeepLinkReachesTheHandler() {
        assertEquals(LinkRoute.DeepLink("myapp://cart"), PlacementView.linkRoute("deep_link", "myapp://cart", hasDeepLinkHandler = true))
        // Without a handler, or on a plain `url` button, an app link is not opened by the view.
        assertEquals(LinkRoute.None, PlacementView.linkRoute("deep_link", "myapp://cart", hasDeepLinkHandler = false))
        assertEquals(LinkRoute.None, PlacementView.linkRoute("url", "myapp://cart", hasDeepLinkHandler = true))
    }

    @Test
    fun refusedSchemesReachNeitherTheHandlerNorTheSystem() {
        for (url in listOf("javascript:alert(1)", "javascript://%0aalert(1)", "data:text/html,<script>x</script>", "intent://x#Intent;end", "file:///data/x", "content://x")) {
            assertEquals(url, LinkRoute.None, PlacementView.linkRoute("deep_link", url, hasDeepLinkHandler = true))
            assertEquals(url, LinkRoute.None, PlacementView.linkRoute("url", url, hasDeepLinkHandler = true))
        }
    }

    @Test
    fun httpOpensThroughTheSafePathAndNeverReachesTheHandler() {
        for (url in listOf("https://shop.example/p", "http://shop.example/p")) {
            assertEquals(LinkRoute.Open(url), PlacementView.linkRoute("deep_link", url, hasDeepLinkHandler = true))
            assertEquals(LinkRoute.Open(url), PlacementView.linkRoute("url", url, hasDeepLinkHandler = false))
        }
        assertEquals(LinkRoute.None, PlacementView.linkRoute("dismiss", "https://shop.example", hasDeepLinkHandler = true))
    }

    /** The built-in product cards row (D444): what a product tap reports and where it goes. */
    @Test
    fun productCardsReportProductIdsAndOpenLikeADeepLinkButton() {
        val p = PlacementProduct("sku-1", "Mug", "$9", "$12", 25.0, "https://cdn.example/mug.png", "https://shop.example/mug")
        assertEquals(listOf(p), PlacementView.productsToDraw(listOf(p), showProducts = true))
        assertEquals(emptyList<PlacementProduct>(), PlacementView.productsToDraw(listOf(p), showProducts = false))
        assertEquals("product:sku-1", placementProductElementId("sku-1"))
        assertEquals("product:", PLACEMENT_PRODUCT_ELEMENT_PREFIX)
        assertEquals(128, placementProductElementId("x".repeat(300)).length)
        assertEquals(160, PLACEMENT_PRODUCT_CARD_WIDTH)
        // A product link follows the deep_link button rule: http(s) opens, an app link goes to a handler, nothing else.
        assertEquals(LinkRoute.Open("https://shop.example/mug"), PlacementView.linkRoute("deep_link", p.url, hasDeepLinkHandler = false))
        assertEquals(LinkRoute.DeepLink("myapp://product/1"), PlacementView.linkRoute("deep_link", "myapp://product/1", hasDeepLinkHandler = true))
        assertEquals(LinkRoute.None, PlacementView.linkRoute("deep_link", "javascript:alert(1)", hasDeepLinkHandler = true))
    }
}
