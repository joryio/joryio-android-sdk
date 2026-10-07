package io.joryio.sdk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.res.ResourcesCompat
import io.joryio.sdk.models.ButtonAction
import io.joryio.sdk.models.InAppContent
import io.joryio.sdk.models.MessageType

/**
 * Builds a native in-app message out of real Android views.
 *
 * No WebView, and that is the whole point:
 *
 *  - **Security.** There is no interpreter, so a contact attribute containing
 *    markup is just text. Apps whose security policy forbids running authored
 *    HTML/JS in-process can use in-app messaging at all.
 *  - **Look.** Text renders with the app's own font scaling and dark mode, and
 *    TalkBack reads it, all for free.
 *  - **Weight.** No WebView init per message, which is a visible hitch on
 *    low-end devices.
 *
 * Everything here treats server strings as TEXT. The server does NOT
 * HTML-escape native fields (escaping would show the user `A &amp; B`), so
 * passing any of these to a WebView would reintroduce exactly the injection the
 * native path is meant to avoid.
 */
internal object InAppNativeView {

    /** Callbacks the host renderer supplies. */
    interface Host {
        fun onButton(buttonId: String, action: ButtonAction, url: String?)
        fun onClose(reason: String)
    }

    fun build(
        context: Context,
        content: InAppContent.Native,
        type: MessageType,
        host: Host,
    ): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Author overrides. Every one is optional and every fallback below is
        // the host app's own theme, so a campaign that sets nothing looks like
        // the app - which is the reason to pick native over HTML at all.
        val st = content.style

        // Direction from the MESSAGE, not the device locale: one workspace sends
        // Hebrew and English from the same campaign list, so a locale decision
        // gets one of them wrong. Set on the card, so the button row, the
        // padding and the image all follow without a rule each.
        val rtl = isRightToLeft(content.body)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection =
                if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(hex(st?.backgroundColor) ?: surfaceColor(context))
                cornerRadius = if (type == MessageType.FULLSCREEN) {
                    // Fullscreen ignores a radius on purpose: rounded corners on
                    // an edge-to-edge sheet leave the app showing through.
                    0f
                } else {
                    dp(st?.cornerRadius?.toInt() ?: 14).toFloat()
                }
            }
        }

        // Image first - it is the thing people see before they read.
        content.imageUrl?.let { url ->
            val image = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(140),
                ).apply { bottomMargin = dp(14) }
                // Decorative: the headline and body carry the meaning, so an
                // unlabelled image would just make TalkBack announce "image".
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(image)
            ImageLoader.load(url, image)
        }

        content.title?.let { title ->
            card.addView(
                TextView(context).apply {
                    text = title
                    // Scales from the body size so one control keeps the
                    // hierarchy, matching the web and iOS renderers' 1.3x.
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, (st?.fontSize ?: 14.5f) * 1.3f)
                    setTypeface(fontOrNull(context, st?.fontFamily) ?: typeface, titleStyle(st?.titleWeight))
                    applyAlignment(st?.textAlign)
                    setTextColor(hex(st?.textColor) ?: primaryTextColor(context))
                    // Announced as a heading so TalkBack users can navigate by it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isAccessibilityHeading = true
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(8)
                        // Room for the ✕, which floats over the card's trailing
                        // corner. marginEnd, not marginRight: the card carries
                        // an RTL layoutDirection for a Hebrew or Arabic message,
                        // so this gap lands on the LEFT there - the same side
                        // the button is on.
                        if (content.closeButton) marginEnd = dp(28)
                    }
                },
            )
        }

        card.addView(
            TextView(context).apply {
                text = content.body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, st?.fontSize ?: 14.5f)
                fontOrNull(context, st?.fontFamily)?.let { setTypeface(it) }
                applyAlignment(st?.textAlign)
                // Body is a softened title colour rather than the raw override:
                // one flat colour for headline and body loses the hierarchy the
                // theme's primary/secondary pair gives for free.
                setTextColor(hex(st?.textColor)?.let(::softened) ?: secondaryTextColor(context))
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(16) }
            },
        )

        if (content.buttons.isNotEmpty()) {
            // Side by side for two, stacked beyond that - three buttons in a row
            // truncate their labels on a narrow phone.
            val row = LinearLayout(context).apply {
                orientation =
                    if (content.buttons.size <= 2) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            content.buttons.forEachIndexed { index, b ->
                // The FIRST button is the primary action, matching the order the
                // author put them in - the renderer does not reorder, because
                // the author decided which action leads.
                row.addView(buttonView(context, b, primary = index == 0, style = st, host = host, dp = ::dp))
            }
            card.addView(row)
        }

        return FrameLayout(context).apply {
            layoutDirection =
                if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (type == MessageType.FULLSCREEN) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    },
                ),
            )

            // An ✕ in the card's TRAILING corner, not a "Close" row under the
            // buttons. Three reasons it changed:
            //   - it is where people look to dismiss a card, on every platform;
            //   - the old row hardcoded the English word "Close", which is
            //     simply wrong in a Hebrew or Arabic message - a glyph needs no
            //     translation, only a contentDescription;
            //   - the web SDK already drew an ✕ for HTML messages while drawing
            //     "Close" for native ones, so one product had two designs.
            // A dismissible message must always have a visible way out; relying
            // on the backdrop alone traps fullscreen users, which have none.
            if (content.closeButton) {
                addView(
                    TextView(context).apply {
                        text = "\u2715"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(
                            hex(st?.textColor)?.let(::softened) ?: secondaryTextColor(context),
                        )
                        gravity = Gravity.CENTER
                        // Icon-only, so TalkBack would otherwise announce it as
                        // an unlabelled button.
                        contentDescription = "Close"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { host.onClose("close_button") }
                        layoutParams = FrameLayout.LayoutParams(
                            // 44dp is the tap target; the glyph inside is 16sp.
                            // Smaller is hard to hit, and it is the only way out
                            // of a full-screen message.
                            dp(44),
                            dp(44),
                            // Gravity.END, not RIGHT: resolves against the
                            // layoutDirection set above, so it flips for RTL.
                            Gravity.TOP or Gravity.END,
                        ).apply {
                            topMargin = dp(4)
                            marginEnd = dp(4)
                        }
                    },
                )
            }
        }
    }

    private fun buttonView(
        context: Context,
        b: InAppContent.Button,
        primary: Boolean,
        style: InAppContent.NativeStyle?,
        host: Host,
        dp: (Int) -> Int,
    ): View = Button(context).apply {
        text = b.text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style?.fontSize ?: 14f)
        fontOrNull(context, style?.fontFamily)?.let { setTypeface(it) }
        val primaryFill = hex(style?.primaryButtonColor) ?: accentColor(context)
        setTextColor(
            if (primary) {
                // No explicit label colour: derive one from the FILL rather than
                // defaulting to white. An author who picks a pale brand colour
                // and stops there would otherwise get white-on-pale, i.e. an
                // invisible label - and it would look fine in the editor, where
                // the preview has its own defaults.
                hex(style?.primaryButtonTextColor) ?: readableOn(primaryFill)
            } else {
                hex(style?.textColor) ?: primaryTextColor(context)
            },
        )
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            if (primary) {
                setColor(primaryFill)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), dividerColor(context))
            }
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        setOnClickListener { host.onButton(b.id, b.action, b.url) }
    }

    /**
     * Aligns text by its OWN direction rather than the layout's.
     *
     * TEXT_ALIGNMENT_TEXT_START resolves against the paragraph's direction, so
     * a Hebrew body aligns right and an English one left - inside the same app,
     * with no locale check. That is why it is the default rather than VIEW_START.
     */
    private fun TextView.applyAlignment(align: String?) {
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = when (align) {
            "center" -> View.TEXT_ALIGNMENT_CENTER
            "end" -> View.TEXT_ALIGNMENT_TEXT_END
            else -> View.TEXT_ALIGNMENT_TEXT_START // covers "start" and "auto"
        }
    }

    private fun titleStyle(weight: String?): Int = when (weight) {
        "regular", "medium" -> android.graphics.Typeface.NORMAL
        else -> android.graphics.Typeface.BOLD // semibold/bold/unset
    }

    /**
     * Resolves an author-named font, or null to keep the app's own.
     *
     * Tries the app's own `res/font` first, then a system family name.
     * Typeface.create NEVER fails - it silently returns the default - so a
     * miss is checked rather than assumed, and a name this app does not ship
     * leaves the typeface alone instead of pretending it applied.
     */
    private fun fontOrNull(context: Context, family: String?): android.graphics.Typeface? {
        val name = family?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val resId = context.resources.getIdentifier(
            name.lowercase().replace(' ', '_'), "font", context.packageName,
        )
        if (resId != 0) {
            runCatching { return ResourcesCompat.getFont(context, resId) }
        }

        val created = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        // create() falls back to DEFAULT for an unknown name, and returning that
        // would look like the override worked while changing nothing.
        return created.takeIf { it != android.graphics.Typeface.DEFAULT }
    }

    /**
     * True when the text reads right to left, by FIRST STRONG character.
     *
     * The same rule the Unicode BiDi algorithm and the web's dir="auto" use, so
     * all three renderers agree on the same message. Leading punctuation,
     * digits and emoji are neutral and skipped.
     */
    @VisibleForTesting
    internal fun isRightToLeft(text: String): Boolean {
        for (ch in text) {
            when (ch.code) {
                in 0x0590..0x05FF, in 0x0600..0x07BF, in 0x0800..0x085F,
                in 0xFB1D..0xFDFF, in 0xFE70..0xFEFF -> return true
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x058F,
                in 0x0900..0x1FFF, in 0x2C00..0xD7FF -> return false
            }
        }
        return false
    }

    // ── Colours ─────────────────────────────────────────────────────────────
    //
    // Resolved from the host app's THEME, not hard-coded. That is the point of
    // native rendering: the message inherits the app's palette and dark mode
    // instead of shipping its own. Falls back to sane light values on a theme
    // that does not define the attribute.

    /**
     * Parses an author-supplied colour, returning null on anything unusable.
     *
     * Color.parseColor THROWS on a malformed string. The value comes from a
     * text box in the campaign editor, so a typo there would otherwise crash
     * the customer's app at display time - a message that fails to render is
     * bad, a message that takes the app down is unacceptable. Null falls back
     * to the theme.
     */
    private fun hex(value: String?): Int? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    /**
     * Black or white, whichever stays legible on [background].
     *
     * Rec. 709 luma, the same weighting the WCAG contrast formula uses.
     */
    private fun readableOn(background: Int): Int {
        val luma = 0.2126 * Color.red(background) +
            0.7152 * Color.green(background) +
            0.0722 * Color.blue(background)
        return if (luma > 150) Color.parseColor("#0A1240") else Color.WHITE
    }

    /** Same hue, less weight - used for body text under an overridden title. */
    private fun softened(color: Int): Int =
        Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    private fun surfaceColor(context: Context) =
        themeColor(context, android.R.attr.colorBackground, Color.WHITE)

    private fun primaryTextColor(context: Context) =
        themeColor(context, android.R.attr.textColorPrimary, Color.parseColor("#0A1240"))

    private fun secondaryTextColor(context: Context) =
        themeColor(context, android.R.attr.textColorSecondary, Color.parseColor("#5A6080"))

    private fun accentColor(context: Context) =
        themeColor(context, android.R.attr.colorAccent, Color.parseColor("#0A1240"))

    private fun dividerColor(context: Context) =
        themeColor(context, android.R.attr.listDivider, Color.parseColor("#E5EAF2"))
}
