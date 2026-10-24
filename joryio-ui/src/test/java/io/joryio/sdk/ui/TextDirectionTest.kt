package io.joryio.sdk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which way a message reads.
 *
 * Written because the direction work shipped on "it compiles" - and a rule that
 * returns the wrong boolean produces a message that renders perfectly, just
 * mirrored, which no build can notice.
 *
 * It has to agree with the other three renderers: iOS uses the same ranges, and
 * the web gets it from dir="auto". All four implement FIRST STRONG CHARACTER,
 * per the Unicode BiDi algorithm - so the same message reads the same way on
 * every platform.
 */
class TextDirectionTest {

    @Test
    fun `hebrew and arabic read right to left`() {
        assertTrue(InAppNativeView.isRightToLeft("שלום, יש מקום פנוי"))
        assertTrue(InAppNativeView.isRightToLeft("مرحبًا، هناك مكان شاغر"))
    }

    @Test
    fun `latin greek and cyrillic read left to right`() {
        assertFalse(InAppNativeView.isRightToLeft("A place opened up"))
        assertFalse(InAppNativeView.isRightToLeft("Άνοιξε μια θέση"))
        assertFalse(InAppNativeView.isRightToLeft("Освободилось место"))
    }

    @Test
    fun `leading neutrals are skipped, not treated as left to right`() {
        // A Hebrew message that opens with punctuation, a digit or an emoji is
        // still Hebrew. Testing the first character alone would get every one
        // of these backwards.
        assertTrue(InAppNativeView.isRightToLeft("!שלום"))
        assertTrue(InAppNativeView.isRightToLeft("20% הנחה לחברים"))
        assertTrue(InAppNativeView.isRightToLeft("🎉 שלום"))
        assertTrue(InAppNativeView.isRightToLeft("   \n שלום"))
    }

    @Test
    fun `the FIRST strong character decides, not the majority`() {
        // "Hello שלום" is a left-to-right paragraph containing Hebrew, and
        // "שלום Hello" is a right-to-left one containing English. Counting
        // characters instead would flip both.
        assertFalse(InAppNativeView.isRightToLeft("Hello שלום שלום שלום"))
        assertTrue(InAppNativeView.isRightToLeft("שלום Hello Hello Hello"))
    }

    @Test
    fun `text with no strong character defaults to left to right`() {
        assertFalse(InAppNativeView.isRightToLeft(""))
        assertFalse(InAppNativeView.isRightToLeft("123 !? 🎉"))
    }
}
