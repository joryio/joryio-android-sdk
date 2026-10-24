package io.joryio.sdk.models

import com.google.gson.annotations.SerializedName

/**
 * In-app campaign, as `/v1/in-app/sync` actually returns it.
 *
 * WHY THIS FILE WAS REWRITTEN
 * ---------------------------
 * The previous version modelled a STRUCTURED native message - `title`, `body`,
 * `imageUrl`, `buttons` - nested under a `message` object. The server has never
 * sent that. It sends `template { html, css, assets }` (plus `renderedHtml`
 * when Liquid personalisation is on), because the campaign editor is HTML-based
 * and produces HTML. There was no `message` key in the payload at all, so
 * in-app messaging did not partially work on Android - it could not work.
 *
 * WHY HTML AND NOT NATIVE VIEWS
 * -----------------------------
 * Mature in-app SDKs commonly offer both: native slideup/modal/full types
 * alongside an HTML type. Both are worth having, and the
 * split is the right end state - native renders faster and looks like the host
 * app, HTML gives designers full control.
 *
 * But nothing in this product AUTHORS structured content today. Building a
 * native renderer before the editor can produce native templates would be dead
 * code shipped to every customer. So HTML is the content type now, and
 * [InAppContent] is a sealed type rather than a bare string: adding
 * [InAppContent.Native] later is additive, not a rewrite of everything that
 * touches a message.
 */
data class InAppCampaign(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    /**
     * Presentation, NOT content. Decides how the message is placed on screen -
     * see the renderer. A `custom` campaign is still HTML; it simply asks the
     * host app to place it.
     */
    @SerializedName("type")
    val type: MessageType,

    @SerializedName("priority")
    val priority: Int = 0,

    @SerializedName("evaluationMode")
    val evaluationMode: String = "session-start-only",

    /**
     * What to render. ONE field, discriminated on `kind`.
     *
     * Replaced `template` + `renderedHtml` + `liquidEnabled`, which expressed
     * one message three ways and left each SDK to decide which to trust - and
     * they decided differently. The server now sends exactly what should appear
     * on screen, with Liquid already resolved, so no SDK needs a Liquid engine
     * (Android and iOS never had one) and every platform shows the same thing.
     */
    @SerializedName("content")
    val contentPayload: ContentPayload? = null,

    @SerializedName("targeting")
    val targeting: Targeting? = null,

    @SerializedName("triggers")
    val triggers: List<Map<String, Any?>>? = null,

    /**
     * Synced as triggers WITHOUT content: ask the server at display time.
     *
     * Costs offline display for this campaign - with no content held, a
     * trigger firing without a network shows nothing, which is the intent.
     */
    @SerializedName("reevaluateBeforeDisplay")
    val reevaluateBeforeDisplay: Boolean? = null,

    @SerializedName("watchedAttributes")
    val watchedAttributes: List<String>? = null,

    /**
     * Signed proof that the SERVER served this exact message, to this subject,
     * for this campaign/variant. Issued per delivery in the sync response.
     *
     * MUST be echoed back verbatim on every track call. The server takes tenant,
     * campaign, variant and subject FROM THE TOKEN and ignores the equivalent
     * body fields, and permits each transition exactly once per delivery. That is
     * what stops a holder of the publishable SDK key reporting interactions with
     * content that was never served, or replaying one to inflate campaign and A/B
     * metrics.
     *
     * Treat it as opaque - never construct, parse, or reuse one across messages.
     * It is cached with the campaign, which matters: in-app is a PULL channel, so
     * a device may sync, go offline, display, and only report days later. The
     * token has to still be there when it does.
     *
     * Null for an anonymous visitor with no resolvable subject, and for servers
     * predating the token.
     */
    @SerializedName("deliveryToken")
    val deliveryToken: String? = null,

    /**
     * Seconds to wait after the trigger fires before showing this message.
     *
     * Documented and collected in the dashboard long before anything sent or
     * read it, so a marketer's "wait 30 seconds" displayed immediately.
     */
    @SerializedName("delay")
    val delay: Int? = null,

    @SerializedName("frequencyCap")
    val frequencyCap: FrequencyCap? = null,
) {
    /**
     * The content to render, resolved from the wire fields.
     *
     * Returns null when the campaign carries no usable document - a template
     * that failed to render, say. Callers must treat that as "do not display"
     * rather than showing an empty container: a blank modal a user has to
     * dismiss is worse than no message.
     */
    val content: InAppContent?
        get() {
            val c = contentPayload ?: return null
            return when (c.kind) {
                "html" -> c.html?.takeIf { it.isNotBlank() }
                    ?.let { InAppContent.Html(html = it, css = c.css.orEmpty()) }

                "native" -> c.body?.takeIf { it.isNotBlank() }?.let {
                    InAppContent.Native(
                        title = c.title?.takeIf { t -> t.isNotBlank() },
                        body = it,
                        imageUrl = c.imageUrl?.takeIf { u -> u.isNotBlank() },
                        buttons = c.buttons.orEmpty().mapNotNull { b ->
                            val text = b.text?.takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
                            InAppContent.Button(
                                id = b.id.orEmpty(),
                                text = text,
                                action = ButtonAction.from(b.action),
                                url = b.url,
                            )
                        },
                        closeButton = c.closeButton ?: true,
                        style = c.style?.let { st ->
                            InAppContent.NativeStyle(
                                backgroundColor = st.backgroundColor,
                                textColor = st.textColor,
                                primaryButtonColor = st.primaryButtonColor,
                                primaryButtonTextColor = st.primaryButtonTextColor,
                                cornerRadius = st.cornerRadius,
                                fontSize = st.fontSize,
                                titleWeight = st.titleWeight,
                                textAlign = st.textAlign,
                                fontFamily = st.fontFamily,
                            )
                        },
                        backdropDismissible = c.backdropDismissible ?: true,
                    )
                }

                // A kind this SDK version does not know about. Skip it rather
                // than guessing - a half-rendered message is worse than none,
                // and the server only sends kinds it was told we accept.
                else -> null
            }
        }

    /** Wire shape of the `content` field. */
    data class ContentPayload(
        @SerializedName("kind") val kind: String = "",

        // ── kind = "html" ───────────────────────────────────────────────────
        @SerializedName("html") val html: String? = null,
        @SerializedName("css") val css: String? = null,
        @SerializedName("assets") val assets: List<Map<String, Any?>>? = null,

        // ── kind = "native" ─────────────────────────────────────────────────
        @SerializedName("title") val title: String? = null,
        @SerializedName("body") val body: String? = null,
        @SerializedName("imageUrl") val imageUrl: String? = null,
        @SerializedName("buttons") val buttons: List<ButtonPayload>? = null,
        @SerializedName("closeButton") val closeButton: Boolean? = null,
        @SerializedName("backdropDismissible") val backdropDismissible: Boolean? = null,
        @SerializedName("style") val style: StylePayload? = null,
    )

    data class StylePayload(
        @SerializedName("backgroundColor") val backgroundColor: String? = null,
        @SerializedName("textColor") val textColor: String? = null,
        @SerializedName("primaryButtonColor") val primaryButtonColor: String? = null,
        @SerializedName("primaryButtonTextColor") val primaryButtonTextColor: String? = null,
        @SerializedName("cornerRadius") val cornerRadius: Float? = null,
        @SerializedName("fontSize") val fontSize: Float? = null,
        @SerializedName("titleWeight") val titleWeight: String? = null,
        @SerializedName("textAlign") val textAlign: String? = null,
        @SerializedName("fontFamily") val fontFamily: String? = null,
        // customCss is sent for web and deliberately not mapped: there is no
        // CSS engine here to hand it to.
    )

    data class ButtonPayload(
        @SerializedName("id") val id: String? = null,
        @SerializedName("text") val text: String? = null,
        @SerializedName("action") val action: String? = null,
        @SerializedName("url") val url: String? = null,
    )

}

/**
 * What a campaign renders.
 *
 * Sealed on purpose. Today there is exactly one case, and a `String html` field
 * would have been shorter - but the next content type (native title/body/image,
 * once the editor can author it) then becomes a breaking change to every
 * `when` in the SDK and in host apps. This way it is a new branch.
 */
sealed class InAppContent {
    /** An HTML document plus its stylesheet, rendered in a WebView. */
    data class Html(val html: String, val css: String) : InAppContent()

    /**
     * Structured content the app renders with its OWN views.
     *
     * No markup, no interpreter - which is the point. An app that disallows
     * HTML in-app messages can still show these, and they inherit the host
     * app's fonts, colours, dark mode and accessibility settings for free.
     *
     * Strings here are TEXT, never markup: the server does not HTML-escape
     * them, so rendering one into a WebView would be a hole. They belong in a
     * TextView.
     */
    data class Native(
        val title: String?,
        val body: String,
        val imageUrl: String?,
        val buttons: List<Button>,
        val closeButton: Boolean,
        val backdropDismissible: Boolean,
        /**
         * Optional presentation overrides. Null means INHERIT the host app's
         * theme, which is the reason to choose native at all - a campaign sets
         * a colour only when it needs a brand moment.
         */
        val style: NativeStyle? = null,
    ) : InAppContent()

    /**
     * Author overrides.
     *
     * [fontFamily] is BEST EFFORT: an app renders only fonts it ships, so a
     * name this app lacks leaves the app's own typeface rather than
     * substituting something arbitrary. Size, weight and alignment need no font
     * file and always apply.
     */
    data class NativeStyle(
        val backgroundColor: String? = null,
        val textColor: String? = null,
        val primaryButtonColor: String? = null,
        val primaryButtonTextColor: String? = null,
        val cornerRadius: Float? = null,
        /** Body size in sp; the headline scales from it. */
        val fontSize: Float? = null,
        /** regular | medium | semibold | bold - the HEADLINE's weight. */
        val titleWeight: String? = null,
        /** auto | start | center | end. `auto` follows the message's own text. */
        val textAlign: String? = null,
        /** Applied only if this app ships the font; otherwise the app's own. */
        val fontFamily: String? = null,
    )

    data class Button(
        val id: String,
        val text: String,
        val action: ButtonAction,
        val url: String?,
    )
}

/** What a native button does when tapped. */
enum class ButtonAction {
    DISMISS,
    URL,
    DEEP_LINK,

    /**
     * Ask for notification permission - the "push primer" button.
     *
     * The prompt is one-shot on Android 13+, so the value of a primer is that a
     * "no" here costs nothing while a "no" to the system dialog is permanent.
     * The campaign makes the case; only a yes spends the real prompt.
     */
    REQUEST_PUSH_PERMISSION;

    companion object {
        /**
         * Unknown actions become DISMISS rather than throwing.
         *
         * A server that adds an action must not make older apps crash or - worse
         * - render a button that does nothing when tapped, trapping the user in
         * a modal they cannot close.
         */
        fun from(raw: String?): ButtonAction = when (raw?.lowercase()) {
            "url" -> URL
            "deep_link" -> DEEP_LINK
            "request_push_permission" -> REQUEST_PUSH_PERMISSION
            else -> DISMISS
        }
    }
}

/**
 * How the message is presented. Values match the server's `type` exactly -
 * changing a name here silently drops campaigns of that type on the floor,
 * because Gson maps an unknown value to null.
 */
enum class MessageType {
    @SerializedName("modal")
    MODAL,

    @SerializedName("banner")
    BANNER,

    @SerializedName("slideup")
    SLIDEUP,

    @SerializedName("fullscreen")
    FULLSCREEN,

    /** Host app decides placement; content is still HTML. */
    @SerializedName("custom")
    CUSTOM,
}

data class FrequencyCap(
    /**
     * How many times, or `null` for UNLIMITED.
     *
     * Nullable because the composer sends null for it: "Allow this campaign to
     * show more than once per user" with no number means no ceiling, and the
     * backend and web SDK have always read it that way.
     *
     * It was a non-null `Int`. Gson does not run constructors, so a JSON null
     * left the primitive at 0 and `recentImpressions.size >= 0` was ALWAYS
     * true - a campaign set to unlimited displayed never, the exact opposite of
     * the setting. iOS failed the same input differently (it could not decode
     * the cap at all and fell back to show-once).
     */
    @SerializedName("maxImpressions")
    val maxImpressions: Int? = null,

    @SerializedName("timeWindow")
    val timeWindow: String,

    @SerializedName("minDelayBetweenImpressions")
    val minDelayBetweenImpressions: Int? = null,
)

data class Targeting(
    /** Server-side filter groups (the shape the dashboard builds). */
    @SerializedName("filterGroups")
    val filterGroups: Any? = null,

    @SerializedName("segments")
    val segments: List<String>? = null,

    @SerializedName("filters")
    val filters: List<Map<String, Any?>>? = null,
)

/** Session sync request. */
internal data class SessionSyncRequest(
    @SerializedName("userId")
    val userId: String,

    @SerializedName("anonymousId")
    val anonymousId: String,

    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("attributes")
    val attributes: Map<String, Any?>? = null,

    /**
     * What this build can render, e.g. `["content.native", "content.html"]`.
     *
     * Stored server-side per app purely so the campaign editor can warn a
     * marketer that an HTML campaign will not display on some of their apps,
     * instead of letting them publish into silence. It never gates delivery -
     * this device still decides what it shows.
     *
     * Null on older builds, and the server records that absence as "unknown"
     * rather than "cannot", so nothing warns about an integration that simply
     * predates the field.
     */
    @SerializedName("capabilities")
    val capabilities: List<String>? = null,

    /**
     * Attributes written locally that the server has not acknowledged yet.
     *
     * PRESENCE of this key (even empty) tells the backend this SDK keeps a
     * durable attribute queue, so the stored profile is authoritative and only
     * these few keys may override it. An older SDK omits the key entirely and
     * keeps the previous behaviour, where everything in [attributes] wins.
     *
     * Defaults to an empty map rather than null so Gson emits the key - the
     * capability must be detectable, and null would be indistinguishable from
     * an old build.
     */
    @SerializedName("pendingAttributes")
    val pendingAttributes: Map<String, Any?> = emptyMap(),
)

/** Session sync response. */
internal data class SessionSyncResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("campaigns")
    val campaigns: List<InAppCampaign>,

    @SerializedName("evaluatedAt")
    val evaluatedAt: String,

    /**
     * DEPRECATED and unused: no SDK ever acted on it. Nullable so the server
     * can stop sending it - Gson would otherwise put null into a non-null
     * Kotlin field, which blows up somewhere far from the cause.
     */
    @SerializedName("nextEvaluationStrategy")
    val nextEvaluationStrategy: String? = null,

    @SerializedName("delayBetweenCampaigns")
    val delayBetweenCampaigns: Int? = null,

    @SerializedName("error")
    val error: String? = null,
)

/** Track impression request. */
internal data class TrackImpressionRequest(
    @SerializedName("campaignId")
    val campaignId: String,

    @SerializedName("userId")
    val userId: String,

    @SerializedName("anonymousId")
    val anonymousId: String,

    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("action")
    val action: String,

    /**
     * STRUCTURED MARKERS, matching what the web SDK sends.
     *
     * This request used to carry the interaction ONLY in [action], while the web
     * SDK set displayedAt / clicked+clickedAt / dismissedAt. The backend gate
     * that decides whether to emit `in_app.displayed` reads those markers, so on
     * mobile every call fell through to "this is a display" - one message
     * displayed, clicked and dismissed emitted THREE in_app.displayed events,
     * inflating mobile display counts up to 3x and over-crediting revenue
     * attribution, which joins on that event.
     *
     * The backend now understands both shapes, so this is not what fixes it - it
     * removes the second shape, which is what allowed it. One event, one wire
     * format, whichever SDK reports it.
     *
     * Gson omits nulls, so only the markers relevant to this call are sent.
     * ISO-8601 to match web exactly; the backend also tolerates epoch millis.
     */
    @SerializedName("displayedAt")
    val displayedAt: String? = null,

    @SerializedName("clicked")
    val clicked: Boolean? = null,

    @SerializedName("clickedAt")
    val clickedAt: String? = null,

    @SerializedName("dismissedAt")
    val dismissedAt: String? = null,

    @SerializedName("converted")
    val converted: Boolean? = null,

    @SerializedName("convertedAt")
    val convertedAt: String? = null,

    @SerializedName("timestamp")
    val timestamp: java.util.Date = java.util.Date(),

    /**
     * Echoed from [InAppCampaign.deliveryToken] - see that field. Gson omits a
     * null field by default, which is what the server needs: an explicit null
     * would be read as a malformed token rather than as absent.
     */
    @SerializedName("deliveryToken")
    val deliveryToken: String? = null,

    /**
     * Which button or link a click came from (e.g. "cta", max 128). Kept
     * OUT of `action`, which must be one of the backend's vocabulary words
     * (in-app-action-vocabulary.ts) - a button id there was rejected as an
     * unknown action, so a named click was not recorded as a click at all.
     */
    @SerializedName("elementId")
    val elementId: String? = null,
)

/** Body of `POST /v1/in-app/resolve` - the display-time re-check. */
internal data class ResolveRequest(
    @SerializedName("userId") val userId: String,
    @SerializedName("anonymousId") val anonymousId: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("campaignId") val campaignId: String,
    @SerializedName("attributes") val attributes: Map<String, Any?>? = null,
    @SerializedName("pendingAttributes") val pendingAttributes: Map<String, Any?> = emptyMap(),
)

/**
 * `eligible: false` is a NORMAL answer - the user no longer qualifies - and
 * must be distinguishable from a transport failure, which is why it is a 200
 * body rather than a 404.
 */
internal data class ResolveResponse(
    @SerializedName("eligible") val eligible: Boolean,
    @SerializedName("campaign") val campaign: InAppCampaign? = null,
)
