package io.joryio.sdk.ui

import io.joryio.sdk.core.JoryioLinkSource
import io.joryio.sdk.core.LinkRouter
import io.joryio.sdk.core.isAppDeepLink
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.joryio.sdk.Joryio
import io.joryio.sdk.cards.Card
import io.joryio.sdk.cards.CardFeed
import java.io.Closeable

/**
 * The person's content cards as a plain scrolling list (docs/PLACEMENTS_PLAN.md
 * section 11, D444 Phase 3):
 *
 * ```xml
 * <io.joryio.sdk.ui.CardFeedView
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * or `CardFeedView(context)`. Each card shows its image (if any) on top, the
 * title, the body, its button (if any), a "Pinned" marker, an unread dot and a
 * dismiss control. Text and colors come from the app's theme (dark mode
 * included); there is no design of its own.
 *
 * Reporting is built in: a card at least half on screen for a second is
 * reported `viewed` then `read` (the placement viewability rule); a tap on the
 * card or its button reports `clicked` and opens its url - http(s) in the
 * browser, an app deep link through [onDeepLink], anything else never; the
 * dismiss control reports `dismissed`. The next page loads when the list is
 * scrolled near its end.
 */
class CardFeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    /** Shown when the feed is empty. */
    var emptyText: String = "No messages yet"
        set(value) {
            field = value
            feed?.let { render(it) }
        }

    /** The marker on a pinned card. */
    var pinnedLabel: String = "Pinned"

    /** The accessible label of the dismiss control. */
    var dismissLabel: String = "Dismiss"

    /** The accessible label of the unread dot. */
    var unreadLabel: String = "Unread"

    /**
     * A card's url is an app deep link (`myapp://...`): route it in your app
     * (the click is already reported). Without this only an http(s) link is
     * opened; the view never hands any other scheme to the system.
     */
    var onDeepLink: ((String, Card) -> Unit)? = null

    /** Called after each draw with the feed drawn. */
    var onRender: ((CardFeed) -> Unit)? = null

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val viewability = ViewabilityTracker(this)
    private var sdk: Joryio? = null
    private var subscription: Closeable? = null
    private var feed: CardFeed? = null
    private var loadingMore = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { check() }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { check() }

    init {
        isFillViewport = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        Joryio.whenReady { joryio ->
            post {
                if (!isAttachedToWindow || subscription != null) return@post
                sdk = joryio
                subscription = joryio.onCardsUpdated { render(it) }
                joryio.getCards { render(it) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        try { subscription?.close() } catch (e: Exception) { /* nothing to undo */ }
        subscription = null
        viewability.cancel()
        super.onDetachedFromWindow()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        check()
    }

    // ── drawing ─────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun themeColor(attr: Int): Int? {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else {
            null
        }
    }

    private fun render(next: CardFeed) {
        feed = next
        viewability.cancel()
        column.removeAllViews()
        if (next.cards.isEmpty()) {
            column.addView(TextView(context).apply {
                text = emptyText
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            for (card in next.cards) {
                val v = cardView(card)
                column.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                viewability.add(v) {
                    sdk?.markCardViewed(card)
                    sdk?.markCardRead(card)
                }
            }
        }
        try {
            onRender?.invoke(next)
        } catch (e: Exception) {
            UiLog.warn("CardFeedView onRender threw; ignored: ${e.message}")
        }
        post { check() }
    }

    private fun cardView(card: Card): View {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            tag = "joryio-card:${card.id}"
            isClickable = true
            isFocusable = true
            val tv = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
                setBackgroundResource(tv.resourceId)
            }
            setOnClickListener { click(card) }
        }
        card.imageUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            val image = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            view.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            ImageLoader.load(url, image)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!card.read) {
            header.addView(TextView(context).apply {
                text = "●"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                themeColor(android.R.attr.colorAccent)?.let { setTextColor(it) }
                contentDescription = unreadLabel
                setPadding(0, 0, dp(6), 0)
            })
        }
        header.addView(TextView(context).apply {
            text = card.title ?: ""
            visibility = if (card.title.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (card.pinned) {
            header.addView(TextView(context).apply {
                text = pinnedLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(6), 0, dp(6), 0)
            })
        }
        header.addView(TextView(context).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            contentDescription = dismissLabel
            isClickable = true
            isFocusable = true
            minWidth = dp(40)
            minHeight = dp(40)
            gravity = Gravity.CENTER
            setOnClickListener { sdk?.dismissCard(card) }
        })
        view.addView(header)
        card.body?.takeIf { it.isNotEmpty() }?.let {
            view.addView(TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        val label = card.buttonText
        if (!label.isNullOrEmpty() && !card.url.isNullOrEmpty()) {
            view.addView(Button(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { click(card) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        return view
    }

    /** Report the click, then open the url by the placement link rule. */
    private fun click(card: Card) {
        sdk?.markCardClicked(card)
        val url = card.url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        // The app-wide link handler (`Joryio.setLinkHandler`) sees it first.
        when {
            PlacementView.isSafeUrl(url) -> LinkRouter.route(url, JoryioLinkSource.CARD, campaignId = card.campaignId, cardId = card.id) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    UiLog.warn("No activity to open $url: ${e.message}")
                }
            }
            isAppDeepLink(url) -> LinkRouter.route(url, JoryioLinkSource.CARD, campaignId = card.campaignId, cardId = card.id) {
                try {
                    onDeepLink?.invoke(url, card)
                } catch (e: Exception) {
                    UiLog.warn("CardFeedView onDeepLink threw; ignored: ${e.message}")
                }
            }
            else -> UiLog.warn("Card link refused: not http(s) or an app deep link")
        }
    }

    // ── viewability + paging ────────────────────────────────────────────

    private fun check() {
        viewability.check()
        val f = feed ?: return
        val cursor = f.nextCursor ?: return
        if (loadingMore || f.cards.isEmpty()) return
        val remaining = column.bottom - (scrollY + height)
        if (remaining < dp(200)) {
            loadingMore = true
            sdk?.getCards(cursor) { loadingMore = false } ?: run { loadingMore = false }
        }
    }
}
