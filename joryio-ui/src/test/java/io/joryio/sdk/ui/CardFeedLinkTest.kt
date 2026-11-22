package io.joryio.sdk.ui

import io.joryio.sdk.core.isAppDeepLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card's link follows the placement rule (CardFeedView reuses PlacementView's
 * helpers): http(s) to the system, an app deep link to the app's own handler,
 * refused schemes nowhere.
 */
class CardFeedLinkTest {
    @Test
    fun httpGoesToTheSystemAndAppDeepLinksToTheApp() {
        assertTrue(PlacementView.isSafeUrl("https://shop.example/sale"))
        assertFalse(isAppDeepLink("https://shop.example/sale"))
        assertTrue(isAppDeepLink("myapp://cart"))
        assertFalse(PlacementView.isSafeUrl("myapp://cart"))
    }

    @Test
    fun refusedSchemesAreNeverOpenedNorHandedToTheApp() {
        for (url in listOf(
            "javascript:alert(1)", "javascript://x", "data:text/html,x", "file:///data/x", "intent://x#Intent;end",
            "content://x", "vbscript://x", "blob://x", "about://blank", "  JavaScript://x", "myapp:cart", "",
        )) {
            assertFalse(url, PlacementView.isSafeUrl(url))
            assertFalse(url, isAppDeepLink(url))
        }
    }
}
