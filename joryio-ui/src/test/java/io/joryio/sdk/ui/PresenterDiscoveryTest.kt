package io.joryio.sdk.ui

import io.joryio.sdk.InAppMessagePresenter
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ONE link between the two artifacts that no compiler checks.
 *
 * `Joryio.discoverInAppPresenter()` in the base module finds this class with
 * `Class.forName` and calls a specific constructor. Nothing in either module
 * references the other by type, which is the whole point of the split - and it
 * means renaming this class, moving its package, or changing its constructor
 * compiles perfectly and then silently displays no in-app messages, forever.
 *
 * That failure mode has already happened four times in this codebase in other
 * forms (a renderer that was never instantiated, a setter never called, a
 * config key the iOS bridge ignored). This test is the tripwire.
 *
 * If it fails, do not "fix" it by editing the string here - change base's
 * lookup and this class together, in one commit.
 */
class PresenterDiscoveryTest {

    /** Must match Joryio.discoverInAppPresenter() exactly. */
    private val lookupName = "io.joryio.sdk.ui.DefaultInAppMessagePresenter"

    @Test
    fun `base can find this artifact by the name it looks up`() {
        val cls = Class.forName(lookupName)
        assertTrue(
            "$lookupName must implement InAppMessagePresenter",
            InAppMessagePresenter::class.java.isAssignableFrom(cls),
        )
    }

    @Test
    fun `the constructor base calls exists and produces a presenter`() {
        val cls = Class.forName(lookupName)
        // (allowHtmlJsInAppMessages, debugEnabled) - primitives, matching the
        // getDeclaredConstructor call in base. Boxed Booleans would NOT match.
        val ctor = cls.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        val instance = ctor.newInstance(false, false)
        assertTrue(instance is InAppMessagePresenter)
    }
}
