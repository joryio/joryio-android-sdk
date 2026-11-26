package io.joryio.sdk.placements

/**
 * Placements (docs/PLACEMENTS_PLAN.md, BACKLOG D444): named spots inside the
 * app - `home_hero`, `cart_upsell` - that Joryio fills per person from
 * placement campaigns and journey steps.
 *
 * The names and values here are the same on every Joryio SDK, pinned by the
 * backend's sdk-placements-parity.spec.ts.
 */

/** Share of an item that must be on screen for it to count as seen. */
const val PLACEMENT_VIEWABLE_FRACTION: Double = 0.5

/** A placement key is fetched from the network at most once per this many seconds. */
const val PLACEMENT_MIN_REFRESH_SECONDS: Int = 30

/**
 * A request that got no answer (no connection, a 5xx) holds its keys back
 * this many seconds instead of [PLACEMENT_MIN_REFRESH_SECONDS]: one failed
 * request must not leave a placement empty for 30 s. A 429 keeps the full
 * window (the server asked us to wait).
 */
const val PLACEMENT_RETRY_AFTER_FAILURE_SECONDS: Int = 3

/** Keys asked for within this window go in one request. */
const val PLACEMENT_BATCH_WINDOW_MS: Long = 50L

/** The serve endpoint takes 1-10 keys per request. */
const val PLACEMENT_MAX_KEYS_PER_REQUEST: Int = 10

/** How long an event-triggered item stays eligible after its event, when the server does not say. */
const val PLACEMENT_DEFAULT_TRIGGER_WINDOW_SECONDS: Int = 1800

/**
 * A drawn product card's tap is reported as `clicked` with the elementId
 * `product:<product id>` (at most 128 characters) - the same on every Joryio SDK.
 */
const val PLACEMENT_PRODUCT_ELEMENT_PREFIX: String = "product:"

/** Width of a drawn product card when the app does not set one (dp; points on iOS, CSS px on web). */
const val PLACEMENT_PRODUCT_CARD_WIDTH: Int = 160

/** The elementId a product card's click is reported with - use it when you draw the cards yourself. */
fun placementProductElementId(productId: String): String = (PLACEMENT_PRODUCT_ELEMENT_PREFIX + productId).take(128)

/**
 * "Show when" (BACKLOG D465): the moment a placement campaign's content is
 * revealed on a screen - the `kind` values the wire may carry. An app honours
 * `load`, `seconds` (foreground seconds on the screen) and `event` (tracked in
 * this visit); `scroll` and `exit` are web-only and, like an unknown kind, are
 * never shown.
 */
val SHOW_WHEN_KINDS: List<String> = listOf("load", "seconds", "scroll", "exit", "event")

/**
 * A served "Show when" as an app honours it. Wire contract:
 * packages/backend/src/common/show-when.ts - `{ kind: 'load' }` (also: no
 * field), `{ kind: 'seconds', seconds: 1..3600 }`, `{ kind: 'event', event }`;
 * `scroll` / `exit` (web only), an unknown kind or an invalid value become
 * [Never]: such content is not shown at all (never treated as `load`).
 */
internal sealed class ShowWhenMoment {
    object Load : ShowWhenMoment()
    /** Foreground seconds on the current screen (the time_on_screen clock). */
    data class Seconds(val seconds: Double) : ShowWhenMoment()
    /** The event was tracked in this visit (the SDK session). */
    data class Event(val event: String) : ShowWhenMoment()
    object Never : ShowWhenMoment()

    companion object {
        fun parse(raw: com.google.gson.JsonElement?): ShowWhenMoment {
            if (raw == null || raw.isJsonNull) return Load
            if (!raw.isJsonObject) return Never
            val o = raw.asJsonObject
            val kind = o.get("kind")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            return when (kind) {
                "load" -> Load
                "seconds" -> {
                    val n = o.get("seconds")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    if (n != null && n == Math.floor(n) && n >= 1 && n <= 3600) Seconds(n) else Never
                }
                "event" -> {
                    val e = o.get("event")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
                    if (e.isNotEmpty() && e.length <= 255) Event(e) else Never
                }
                // scroll / exit need a page; anything else is unknown.
                else -> Never
            }
        }
    }
}

/** What the spot does when nothing is eligible: the wire values. */
object PlacementEmptyBehavior {
    const val COLLAPSE = "collapse"
    const val KEEP_SPACE = "keep_space"
}

/** A button of a native item. */
class PlacementButton(
    val id: String,
    val text: String,
    /** `url`, `deep_link`, `dismiss`, `request_push_permission`... (the in-app button actions). */
    val action: String,
    val url: String?,
)

/** What the SDK can draw: a native card, or HTML (only with allowHtmlJsInAppMessages). */
class PlacementContent(
    /** `native` or `html`. */
    val kind: String,
    val title: String? = null,
    val body: String? = null,
    val imageUrl: String? = null,
    val buttons: List<PlacementButton> = emptyList(),
    val closeButton: Boolean = false,
    val html: String? = null,
    val css: String? = null,
)

/** A product card of a recommendation placement item (D444 Phase 2b). Same names on all five SDKs. */
data class PlacementProduct(
    val id: String,
    val title: String?,
    val price: String?,
    val compareAtPrice: String?,
    val discountPercent: Double?,
    val imageUrl: String?,
    val url: String?,
)

/** One item to show, and the calls that report what the person did with it. */
class PlacementItem internal constructor(
    val id: String,
    val variantId: String?,
    /** `native`, `html` or `data`. */
    val kind: String,
    /** The placement's fields, as authored and rendered for this person. */
    val data: Map<String, Any?>,
    val content: PlacementContent?,
    /** Products picked for the person by a recommendation model; empty when the item has none. Draw them as cards. */
    val products: List<PlacementProduct>,
    val priority: Int?,
    val expiresAt: String?,
    internal val placementKey: String,
    internal val wire: PlacementItemWire,
    private val reporter: PlacementReporter?,
    /** The person it was served for: after an identify / reset it reports nothing. */
    internal val generation: Int = 0,
) {
    /** Report a viewable impression (your own UI drew it). Once per delivery. */
    fun markDisplayed() {
        reporter?.report(generation, placementKey, wire, "displayed", null)
    }

    /** Report a tap; [elementId] names the button or link. */
    @JvmOverloads
    fun markClicked(elementId: String? = null) {
        reporter?.report(generation, placementKey, wire, "clicked", elementId)
    }

    /** Report that the person closed it. The placement's dismissal rule applies on the server. */
    fun markDismissed() {
        reporter?.dismiss(generation, placementKey, wire)
    }
}

/** A placement as the server answered it for this person. */
class Placement internal constructor(
    val placementKey: String,
    val items: List<PlacementItem>,
    /** Held out (the placement's control group): nothing to show. */
    val isControl: Boolean,
    /** [PlacementEmptyBehavior.COLLAPSE] or [PlacementEmptyBehavior.KEEP_SPACE]. */
    val emptyBehavior: String,
    val maxItems: Int,
    val ttlSeconds: Int,
    private val reporter: PlacementReporter?,
    internal val generation: Int = 0,
) {
    /**
     * For a held-out person ([isControl]): report that the empty spot was
     * viewable (`in_app.control_viewed`). PlacementView does it itself; an app
     * drawing from getPlacements calls it when its spot was on screen. No-op
     * otherwise.
     */
    fun markDisplayed() {
        reporter?.reportControl(generation, placementKey)
    }

    /**
     * The placement as JSON, for Joryio's React Native and Unity bridges:
     * `{ placementKey, isControl, emptyBehavior, maxItems, ttlSeconds,
     * items: [{ id, variantId, kind, data, content }] }` - no delivery
     * token (reports go back through markDisplayed / markClicked /
     * markDismissed on this object).
     */
    fun toJson(): String {
        val o = com.google.gson.JsonObject()
        o.addProperty("placementKey", placementKey)
        o.addProperty("isControl", isControl)
        o.addProperty("emptyBehavior", emptyBehavior)
        o.addProperty("maxItems", maxItems)
        o.addProperty("ttlSeconds", ttlSeconds)
        val gson = com.google.gson.Gson()
        val arr = com.google.gson.JsonArray()
        for (item in items) {
            val i = com.google.gson.JsonObject()
            i.addProperty("id", item.id)
            item.variantId?.let { i.addProperty("variantId", it) }
            i.addProperty("kind", item.kind)
            i.add("data", gson.toJsonTree(item.data))
            item.content?.let { c -> i.add("content", gson.toJsonTree(c)) }
            // The bridges (React Native, Unity) read the item from here: products must cross too.
            if (item.products.isNotEmpty()) i.add("products", gson.toJsonTree(item.products))
            arr.add(i)
        }
        o.add("items", arr)
        return o.toString()
    }
}

/** What the reporting calls reach. */
internal interface PlacementReporter {
    fun report(generation: Int, key: String, item: PlacementItemWire, type: String, elementId: String?)
    fun dismiss(generation: Int, key: String, item: PlacementItemWire)
    fun reportControl(generation: Int, key: String)
}

/**
 * A view that draws a placement (PlacementView, the React Native view). The
 * SDK fills it and asks it what it shows, for the replacement rule.
 * For Joryio's own views; an app uses PlacementView or getPlacements.
 */
interface PlacementHost {
    val placementKey: String

    /** Item ids currently drawn (the `shown` list of the next request). */
    fun shownIds(): List<String>

    /** A newer answer for [placementKey]. Called on the main thread. */
    fun onPlacementUpdated(placement: Placement)

    /**
     * The person changed (identify, reset, consent): remove what is drawn NOW,
     * on screen or not. Called on the main thread.
     */
    fun clearPlacement()
}
