package io.joryio.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** isAppDeepLink: the one rule for handing a link to the app (the same on every Joryio SDK). */
class AppDeepLinkTest {
    @Test
    fun anAppSchemeLinkIsAnAppDeepLink() {
        for (url in listOf("myapp://cart", "MyApp://p/1?x=y", "com.shop.app://home", "  myapp://cart ")) {
            assertTrue(url, isAppDeepLink(url))
        }
    }

    @Test
    fun httpRefusedSchemesAndNonLinksAreNot() {
        for (url in listOf(
            "javascript:alert(1)", "javascript://%0aalert(1)", "JAVASCRIPT://x", "data:text/html,x", "data://x",
            "intent://scan#Intent;end", "file:///etc/passwd", "vbscript://x", "blob://x", "about://blank", "content://x",
            "http://x.example", "https://x.example", "myapp:cart", "myapp://", "/relative", "",
        )) {
            assertFalse(url, isAppDeepLink(url))
        }
        assertFalse(isAppDeepLink(null))
    }

    @Test
    fun theRefusedListIsTheSharedOne() {
        assertEquals(listOf("javascript", "data", "file", "vbscript", "blob", "about", "content", "intent"), REFUSED_LINK_SCHEMES)
    }
}
