package io.joryio.sdk.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a link the SDK is about to open came from. [wire] is the value every
 * Joryio SDK uses (it crosses the React Native and Unity bridges as this
 * string), pinned by the backend spec sdk-link-handler-parity.spec.ts.
 */
enum class JoryioLinkSource(val wire: String) {
    /** A button or link in an in-app message. */
    IN_APP("in_app"),

    /** A button or link in a placement (`PlacementView`). */
    PLACEMENT("placement"),

    /** A content card tap (`CardFeedView` / `JoryioCardFeed`). */
    CARD("card"),

    /**
     * A push notification tap. Not emitted on Android: the SDK never opens a
     * push link itself here - it starts your launcher activity with the link
     * in the `jry_deep_link` extra, which your app reads (iOS opens it, so
     * iOS routes it through the handler).
     */
    PUSH("push"),
}

/**
 * A link the SDK is about to open, handed to the app's link handler first
 * (`Joryio.setLinkHandler`).
 *
 * [url] is an http(s) link or an app deep link (`myapp://cart`, see
 * [isAppDeepLink]). A refused scheme (`javascript:`, `data:`, `file:`,
 * `intent:`, ... - the list every Joryio SDK shares) never reaches the handler.
 */
class JoryioLink internal constructor(
    val url: String,
    val source: JoryioLinkSource,
    /** The in-app or card campaign, when known. */
    val campaignId: String? = null,
    /** The placement the link was tapped in (source PLACEMENT). */
    val placementKey: String? = null,
    /** The placement item (source PLACEMENT). */
    val itemId: String? = null,
    /** The card (source CARD). */
    val cardId: String? = null,
    private val fallback: () -> Unit,
) {
    private val done = AtomicBoolean(false)

    /** True for an app-scheme link (`myapp://cart`), false for http(s). */
    val isAppDeepLink: Boolean get() = isAppDeepLink(url)

    /**
     * Do what the SDK does when no handler takes the link: http(s) opens with
     * the system (Android routes your verified App Links to your app), an app
     * deep link goes to the view's `onDeepLink`. For a handler that decides
     * later (after a prompt, or across the React Native or Unity bridge).
     * Runs at most once, on the main thread.
     */
    fun openDefault() {
        if (!done.compareAndSet(false, true)) return
        LinkRouter.onMain(fallback)
    }

    override fun toString(): String = "JoryioLink(${source.wire}, $url)"
}

/**
 * The app's link handler: return true to take the link over, false to let the
 * SDK open it as it would without a handler. Called on the main thread.
 */
fun interface JoryioLinkHandler {
    fun handle(link: JoryioLink): Boolean
}

/**
 * The one way every SDK surface (in-app messages, placements, cards) opens a
 * link: refused schemes stop here, the app's handler goes first, then the
 * surface's own fallback. SDK-internal; apps use `Joryio.setLinkHandler`.
 */
object LinkRouter {
    @Volatile
    internal var handler: JoryioLinkHandler? = null

    /** Whether the app set a handler (`setLinkHandler`). */
    val hasHandler: Boolean get() = handler != null

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** http(s): what the SDK may open itself. */
    fun isSafeHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * Route [url]: the app's handler first, then [fallback]. Only http(s) and
     * app deep links are routed; anything else (a refused scheme) is dropped
     * and false returned.
     */
    @JvmOverloads
    fun route(
        url: String,
        source: JoryioLinkSource,
        campaignId: String? = null,
        placementKey: String? = null,
        itemId: String? = null,
        cardId: String? = null,
        fallback: () -> Unit,
    ): Boolean {
        val trimmed = url.trim()
        if (!isSafeHttpUrl(trimmed) && !isAppDeepLink(trimmed)) return false
        val link = JoryioLink(trimmed, source, campaignId, placementKey, itemId, cardId, fallback)
        val h = handler
        if (h != null) {
            val taken = try {
                h.handle(link)
            } catch (t: Throwable) {
                // A throwing handler declines: the link still opens.
                Log.w("Joryio", "Your link handler threw; the link opens as without one: ${t.message}")
                false
            }
            if (taken) return true
        }
        link.openDefault()
        return true
    }

    internal fun onMain(block: () -> Unit) {
        val isMain = try {
            Looper.myLooper() == Looper.getMainLooper()
        } catch (_: Throwable) {
            true // JVM unit tests: no Looper, run inline.
        }
        if (isMain) block() else main.post(block)
    }
}
