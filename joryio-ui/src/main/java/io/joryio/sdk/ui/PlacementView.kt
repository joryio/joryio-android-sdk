package io.joryio.sdk.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.joryio.sdk.Joryio
import io.joryio.sdk.core.JoryioLinkSource
import io.joryio.sdk.core.LinkRouter
import io.joryio.sdk.core.isAppDeepLink
import io.joryio.sdk.messaging.IN_APP_DISPLAY_DWELL_MS
import io.joryio.sdk.placements.PLACEMENT_PRODUCT_CARD_WIDTH
import io.joryio.sdk.placements.PLACEMENT_VIEWABLE_FRACTION
import io.joryio.sdk.placements.Placement
import io.joryio.sdk.placements.PlacementContent
import io.joryio.sdk.placements.PlacementEmptyBehavior
import io.joryio.sdk.placements.PlacementHost
import io.joryio.sdk.placements.PlacementItem
import io.joryio.sdk.placements.PlacementProduct
import io.joryio.sdk.placements.placementProductElementId

/**
 * A placement inside your layout (docs/PLACEMENTS_PLAN.md, D444):
 *
 * ```xml
 * <io.joryio.sdk.ui.PlacementView
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:joryioPlacementKey="home_hero" />
 * ```
 *
 * or `PlacementView(context, "home_hero")`. The SDK fills it for the signed-in
 * person, draws native items as a card and HTML items (only delivered when
 * `allowHtmlJsInAppMessages` is on) in a WebView with no script, measures
 * viewability - at least [PLACEMENT_VIEWABLE_FRACTION] of an item on screen
 * for [IN_APP_DISPLAY_DWELL_MS] while the window has focus - and reports
 * impressions, clicks and dismissals. A held-out person sees nothing and the
 * empty spot reports `in_app.control_viewed` the same way.
 *
 * Content is never swapped while the view is on screen: a newer answer is
 * applied when it next leaves the screen or is attached again.
 *
 * Empty: `collapse` leaves the view with no children (wrap_content -> no
 * height); `keep_space` keeps the height it last had.
 *
 * Recommended products (an item's `products`) are drawn as a horizontal row of
 * cards - image, title, price (the compare-at price struck through) and, with
 * [productButtonText], a button - below the item's content, or alone for a
 * data item. A tap reports `clicked` with [placementProductElementId] and
 * opens the product's link like a `deep_link` button, through
 * `Joryio.setLinkHandler` first (source `placement`). Text colours and the
 * button come from the app's theme (`textColorPrimary`, `textColorSecondary`,
 * `colorAccent`), so dark mode and the app's styles apply. XML:
 * `app:joryioShowProducts`, `app:joryioProductButtonText`,
 * `app:joryioProductCardWidth`. `showProducts = false` leaves the products to you.
 */
class PlacementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), PlacementHost {

    constructor(context: Context, placementKey: String) : this(context, null, 0) {
        this.placementKeyValue = placementKey
    }

    private var placementKeyValue: String = ""

    override val placementKey: String get() = placementKeyValue

    /** Called after the view (re)drew, with what it drew. */
    var onRender: ((Placement) -> Unit)? = null

    /**
     * A `deep_link` button with an app link (`myapp://cart`) was tapped: route
     * it in your app (the click is already reported). An http(s) link is
     * opened by the view instead, and any other scheme (javascript:, data:,
     * intent:, ...) is refused - it reaches neither this handler nor the system.
     * A handler set with `Joryio.setLinkHandler` sees every link first; this
     * one is called when it returns false.
     */
    var onDeepLink: ((String, PlacementItem) -> Unit)? = null

    /** Draw an item's recommended products as a row of cards (default true). Applies from the next draw. */
    var showProducts: Boolean = true

    /** Label of a button on every product card; null = no button (the whole card opens the product). */
    var productButtonText: String? = null

    /** Width of one product card, in dp. */
    var productCardWidth: Int = PLACEMENT_PRODUCT_CARD_WIDTH

    private var sdk: Joryio? = null
    private var current: Placement? = null
    private var pending: Placement? = null
    private var drawn: List<Pair<View, PlacementItem>> = emptyList()
    private val viewability = ViewabilityTracker(this)

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { check() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { check() }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { check() }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.PlacementView, defStyleAttr, 0)
            try {
                placementKeyValue = a.getString(R.styleable.PlacementView_joryioPlacementKey) ?: ""
                showProducts = a.getBoolean(R.styleable.PlacementView_joryioShowProducts, true)
                productButtonText = a.getString(R.styleable.PlacementView_joryioProductButtonText)
                productCardWidth = a.getInt(R.styleable.PlacementView_joryioProductCardWidth, PLACEMENT_PRODUCT_CARD_WIDTH)
            } finally {
                a.recycle()
            }
        }
    }

    /** Point the view at another placement. */
    fun setPlacementKey(key: String) {
        if (key == placementKeyValue) return
        sdk?.detachPlacementHost(this)
        placementKeyValue = key
        current = null
        pending = null
        clear()
        if (isAttachedToWindow) attachToSdk()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        attachToSdk()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        sdk?.detachPlacementHost(this)
        cancelTrackers()
        super.onDetachedFromWindow()
    }

    private fun attachToSdk() {
        if (placementKeyValue.isEmpty()) return
        Joryio.whenReady { joryio ->
            post {
                if (!isAttachedToWindow) return@post
                sdk = joryio
                joryio.attachPlacementHost(this)
            }
        }
    }

    // ── PlacementHost ───────────────────────────────────────────────────

    override fun shownIds(): List<String> = drawn.map { it.second.id }

    override fun clearPlacement() {
        pending = null
        current = null
        clear()
        minimumHeight = 0
    }

    override fun onPlacementUpdated(placement: Placement) {
        // Never under the reader's eyes.
        if (drawn.isNotEmpty() && fractionVisible(this) > 0.0) {
            pending = placement
            return
        }
        render(placement)
    }

    // ── drawing ─────────────────────────────────────────────────────────

    private fun clear() {
        cancelTrackers()
        removeAllViews()
        drawn = emptyList()
    }

    private fun render(placement: Placement) {
        pending = null
        val previousHeight = height
        clear()
        current = placement
        val views = ArrayList<Pair<View, PlacementItem>>()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (item in placement.items) {
            val v = drawItem(item) ?: continue
            column.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            views.add(v to item)
        }
        drawn = views
        if (views.isNotEmpty()) {
            minimumHeight = 0
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            views.forEach { (v, item) -> viewability.add(v) { item.markDisplayed() } }
        } else {
            minimumHeight = if (placement.emptyBehavior == PlacementEmptyBehavior.KEEP_SPACE) previousHeight else 0
            // A held-out spot reports control_viewed; on any empty spot it also tells
            // the SDK the spot was viewable (a deferred control of a Show when campaign).
            viewability.add(this) { placement.markDisplayed() }
        }
        contentDescription = if (views.isEmpty()) null else placement.placementKey
        try {
            onRender?.invoke(placement)
        } catch (e: Exception) {
            UiLog.warn("PlacementView onRender threw; ignored: ${e.message}")
        }
        post { check() }
    }

    private fun drawItem(item: PlacementItem): View? {
        val content = item.content
        val node: View? = when (content?.kind) {
            null -> null
            "native" -> nativeCard(item, content)
            "html" -> htmlView(item, content)
            else -> return null
        }
        // Recommended products: a row of cards below the content, or alone for
        // a data item (without products a data item is the app's to draw).
        val products = productsToDraw(item.products, showProducts)
        if (products.isEmpty()) return node
        val row = productRow(item, products)
        if (node == null) return row
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(node, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun themeColor(attr: Int): Int? {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return null
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    private fun productRow(item: PlacementItem, products: List<PlacementProduct>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val width = dp(if (productCardWidth > 0) productCardWidth else PLACEMENT_PRODUCT_CARD_WIDTH)
        products.forEachIndexed { index, p ->
            row.addView(productCard(item, p, width), LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            tag = "joryio-placement-products:${item.id}"
            addView(row)
        }
    }

    private fun productCard(item: PlacementItem, p: PlacementProduct, width: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "joryio-placement-product:${p.id}"
        }
        p.imageUrl?.let { url ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
            }
            card.addView(image, LinearLayout.LayoutParams(width, width))
            ImageLoader.load(url, image)
        }
        p.title?.takeIf { it.isNotEmpty() }?.let {
            card.addView(TextView(context).apply {
                text = it
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                themeColor(android.R.attr.textColorPrimary)?.let { c -> setTextColor(c) }
            })
        }
        p.price?.takeIf { it.isNotEmpty() }?.let { price ->
            val text = android.text.SpannableStringBuilder(price)
            p.compareAtPrice?.takeIf { it.isNotEmpty() }?.let { was ->
                text.append(" ")
                val start = text.length
                text.append(was)
                text.setSpan(android.text.style.StrikethroughSpan(), start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                themeColor(android.R.attr.textColorSecondary)?.let { c ->
                    text.setSpan(android.text.style.ForegroundColorSpan(c), start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            card.addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                themeColor(android.R.attr.textColorPrimary)?.let { c -> setTextColor(c) }
            })
        }
        if (p.url == null) return card
        productButtonText?.takeIf { it.isNotEmpty() }?.let { label ->
            card.addView(Button(context).apply {
                text = label
                isAllCaps = false
                themeColor(android.R.attr.colorAccent)?.let { c -> setTextColor(c) }
                setOnClickListener { productTapped(item, p) }
            })
        }
        card.isClickable = true
        card.isFocusable = true
        card.contentDescription = listOfNotNull(p.title, p.price).joinToString(", ")
        card.setOnClickListener { productTapped(item, p) }
        return card
    }

    /** A product card: report the click as `product:<id>`, then open its link like a `deep_link` button. */
    private fun productTapped(item: PlacementItem, p: PlacementProduct) {
        buttonAction(item, placementProductElementId(p.id), "deep_link", p.url)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun nativeCard(item: PlacementItem, content: PlacementContent): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            tag = "joryio-placement-card:${item.id}"
        }
        content.imageUrl?.let { url ->
            val image = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            card.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            ImageLoader.load(url, image)
        }
        content.title?.takeIf { it.isNotEmpty() }?.let {
            card.addView(TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        content.body?.takeIf { it.isNotEmpty() }?.let {
            card.addView(TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        if (content.buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            content.buttons.forEach { b ->
                row.addView(Button(context).apply {
                    text = b.text
                    isAllCaps = false
                    setOnClickListener { buttonAction(item, b.id, b.action, b.url) }
                })
            }
            card.addView(row)
        }
        // Inline content has no close control unless the author asked for one.
        if (content.closeButton) {
            card.addView(Button(context).apply {
                text = "×"
                contentDescription = "Close"
                setOnClickListener { item.markDismissed() }
            })
        }
        return card
    }

    /** Report the click, then act. An inline item stays after a click; only `dismiss` removes it. */
    private fun buttonAction(item: PlacementItem, id: String, action: String, url: String?) {
        item.markClicked(id)
        when (val route = linkRoute(action, url, onDeepLink != null || LinkRouter.hasHandler)) {
            is LinkRoute.Open -> open(route.url, item)
            is LinkRoute.DeepLink -> {
                // The app routes its own links: the app-wide link handler
                // first, then this view's onDeepLink.
                LinkRouter.route(route.url, JoryioLinkSource.PLACEMENT, placementKey = placementKey, itemId = item.id) {
                    try {
                        onDeepLink?.invoke(route.url, item)
                    } catch (e: Exception) {
                        UiLog.warn("PlacementView onDeepLink threw; ignored: ${e.message}")
                    }
                }
            }
            LinkRoute.None -> if (action == "dismiss" || action == "close") item.markDismissed()
        }
    }

    /**
     * http(s) only - the rule of InAppMessageRenderer.isSafeUrl; nothing else
     * reaches startActivity. The app-wide link handler sees it first.
     */
    private fun open(url: String, item: PlacementItem) {
        if (!isSafeUrl(url)) return
        LinkRouter.route(url, JoryioLinkSource.PLACEMENT, placementKey = placementKey, itemId = item.id) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                UiLog.warn("No activity to open $url: ${e.message}")
            }
        }
    }

    /**
     * HTML without script: the document draws, links open outside (and count
     * as clicks), nothing in it runs. The overlay's WebView settings otherwise.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun htmlView(item: PlacementItem, content: PlacementContent): View {
        val web = WebView(context)
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.isVerticalScrollBarEnabled = false
        val client = PlacementWebViewClient { url ->
            item.markClicked("link_click")
            open(url, item)
        }
        // The deprecated overload (API 23) has no gesture flag: a tap is a
        // touch-up within TAP_WINDOW_MS, as InAppMessageRenderer decides it.
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) client.lastTouchAt = android.os.SystemClock.uptimeMillis()
            false
        }
        web.webViewClient = client
        val css = (content.css ?: "").replace("</", "<\\/")
        val doc = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src https: data:; font-src https: data:; media-src https: data:; form-action 'none'; base-uri 'none'\">" +
            "<style>html,body{margin:0;padding:0}img,video{max-width:100%;height:auto}$css</style></head><body>${content.html ?: ""}</body></html>"
        web.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
        return web
    }

    // ── viewability ─────────────────────────────────────────────────────

    private fun cancelTrackers() = viewability.cancel()

    private fun fractionVisible(v: View): Double = Viewability.fraction(v)

    private fun check() {
        val p = pending
        if (p != null && fractionVisible(this) == 0.0) {
            render(p)
            return
        }
        viewability.check()
    }

    /**
     * The HTML item's navigation rule: EVERY navigation is cancelled - both
     * overloads return true. Only the person's own tap on an http(s) link
     * counts and opens; a meta refresh, a redirect or anything else the
     * document starts opens nothing, silently. Without the deprecated String
     * overload, Android below API 24 (minSdk is 23) calls it, gets the
     * default `false`, and navigates the WebView inside the placement.
     */
    internal class PlacementWebViewClient(
        private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
        private val onTappedLink: (String) -> Unit,
    ) : WebViewClient() {
        /** Uptime of the last touch-up on the WebView (the API 23 tap signal). */
        var lastTouchAt = 0L

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return true
            val url = req.url?.toString() ?: return true
            route(url, tapped = req.hasGesture() && !req.isRedirect)
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val target = url ?: return true
            route(target, tapped = legacyTapped(now(), lastTouchAt))
            return true
        }

        internal fun route(url: String, tapped: Boolean) {
            if (InAppMessageRenderer.navigationOpensExternally(url, tapped)) onTappedLink(url)
        }

        internal companion object {
            /** The API 23 overload's tap: a touch-up within InAppMessageRenderer.TAP_WINDOW_MS. */
            fun legacyTapped(now: Long, lastTouchAt: Long): Boolean =
                lastTouchAt > 0L && now - lastTouchAt < InAppMessageRenderer.TAP_WINDOW_MS
        }
    }

    /** Where a button's link goes. */
    internal sealed class LinkRoute {
        data class Open(val url: String) : LinkRoute()
        data class DeepLink(val url: String) : LinkRoute()
        object None : LinkRoute()
    }

    internal companion object {
        /** The products the view draws for an item: none when [showProducts] is off. */
        fun productsToDraw(products: List<PlacementProduct>, showProducts: Boolean): List<PlacementProduct> =
            if (showProducts) products else emptyList()

        /** The same rule as InAppMessageRenderer.isSafeUrl: http(s) only. */
        fun isSafeUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        /**
         * A `url` / `deep_link` button: http(s) opens through [isSafeUrl]; a
         * `deep_link` app link goes to the app's handler only when
         * [isAppDeepLink] says so; anything else is refused silently.
         */
        fun linkRoute(action: String, url: String?, hasDeepLinkHandler: Boolean): LinkRoute {
            if ((action != "url" && action != "deep_link") || url == null) return LinkRoute.None
            if (isSafeUrl(url)) return LinkRoute.Open(url)
            if (action == "deep_link" && hasDeepLinkHandler && isAppDeepLink(url)) return LinkRoute.DeepLink(url)
            return LinkRoute.None
        }
    }
}
