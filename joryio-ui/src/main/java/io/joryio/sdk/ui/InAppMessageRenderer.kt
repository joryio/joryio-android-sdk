package io.joryio.sdk.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.joryio.sdk.models.InAppCampaign
import io.joryio.sdk.models.ButtonAction
import io.joryio.sdk.models.InAppContent
import io.joryio.sdk.models.MessageType

/**
 * Renders an in-app campaign's HTML in a WebView.
 *
 * Mirrors `packages/sdk-web/src/core/inapp.ts` deliberately rather than
 * inventing a second behaviour. That renderer is the proven one - the same
 * documents, authored in the same editor, already display through it on the
 * web - so anywhere the two could differ (bridge method names, per-type
 * placement, URL safety rules) this follows it.
 *
 * The message document is UNTRUSTED in the same sense a web page is: it is
 * authored in the dashboard and kept as written by the server, so it may
 * carry <script> and on* handlers. Those do not run - the nonce CSP in
 * buildDocument lets only the SDK's bootstrap execute - and the document gets
 * no file access, no ability to navigate the app, and reaches the SDK only
 * through the explicit bridge methods below.
 */
internal class InAppMessageRenderer(
    /**
     * Whether HTML in-app messages are displayed. See
     * JoryioConfig.allowHtmlJsInAppMessages - off by default, because an
     * HTML message needs a JavaScript-enabled WebView inside the host app
     * (author script in it is blocked by the CSP; only the bridge runs).
     */
    private val allowHtmlJsInAppMessages: Boolean = false,
) {
    /** Bridge object name. Must match the web SDK and the authored templates. */
    internal companion object {
        const val BRIDGE = "joryioBridge"
        const val DEFAULT_MODAL_MARGIN_DP = 24

        /** A navigation this soon after a tap counts as the tap's (API 23 only). */
        const val TAP_WINDOW_MS = 1_000L

        /**
         * Author CSS, made unable to end the `<style>` element it sits in
         * (contract item 57): `</` becomes `<\/` - the same characters to the
         * CSS parser (`\/` is an escaped `/`), not an end tag to the HTML
         * parser. Without it `</style><meta http-equiv="refresh" ...>` in a
         * template's CSS put live markup into the document.
         */
        fun escapeCss(css: String?): String = css.orEmpty().replace("</", "<\\/")

        /** Only a tapped http(s) link leaves the message, and only for the browser. */
        fun navigationOpensExternally(url: String, tapped: Boolean): Boolean =
            tapped && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

        /** The document loadDataWithBaseURL(null, ...) itself. */
        fun isInitialDocument(url: String): Boolean =
            url == "about:blank" || url.startsWith("data:", ignoreCase = true)
    }

    private var current: Dialog? = null

    fun isShowing(): Boolean = current?.isShowing == true

    /**
     * Display [campaign] over [activity].
     *
     * @param onEvent impression/click/dismiss, forwarded to the manager for
     *   `/v1/in-app/track`. Called on the main thread.
     * @return false when the campaign carries nothing renderable - the caller
     *   must not count an impression in that case.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun show(
        activity: Activity,
        campaign: InAppCampaign,
        onEvent: (action: String, meta: Map<String, Any?>) -> Unit,
    ): Boolean {
        val content = campaign.content
        if (content == null) {
            // An empty modal the user must dismiss is worse than no message, and
            // counting an impression for it would inflate the campaign's reach.
            UiLog.warn("Campaign ${campaign.id} has no renderable content; skipping")
            return false
        }

        // `custom` means the HOST app places the content. Rendering our own
        // container would override the very decision the type expresses.
        if (campaign.type == MessageType.CUSTOM) {
            UiLog.debug("Campaign ${campaign.id} is type=custom; host app renders it")
            return false
        }

        // Native content takes an entirely different path: real Android views,
        // no WebView. See InAppNativeView for why that is the point rather than
        // an optimisation.
        if (content is InAppContent.Native) {
            return showNative(activity, campaign, content, onEvent)
        }
        val html = content as InAppContent.Html

        // HTML needs a JavaScript-enabled WebView in this app's process (only
        // the SDK bridge runs; the CSP blocks author script). An app that has
        // not opted in never reaches a WebView at all - and this is a
        // SKIP, not a failure: native campaigns keep displaying, so the channel
        // stays usable rather than becoming all-or-nothing.
        if (!allowHtmlJsInAppMessages) {
            UiLog.warn(
                "Campaign ${campaign.id} is an HTML in-app message, but " +
                    "JoryioConfig.allowHtmlJsInAppMessages is false - not displaying it.",
            )
            return false
        }

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // No filesystem or content-provider reach from the document.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(wrap(activity, webView, campaign.type))
            // Back button dismisses, like tapping the backdrop. A message the
            // user cannot escape is a trap, whatever the campaign says.
            setCancelable(true)
            setOnCancelListener { onEvent("dismissed", mapOf("reason" to "back")) }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(backdropColor(campaign.type)))
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setGravity(gravityFor(campaign.type))
            }
        }

        webView.addJavascriptInterface(
            Bridge(
                campaignId = campaign.id,
                type = campaign.type,
                webView = webView,
                dismiss = { reason ->
                    activity.runOnUiThread {
                        dialog.dismiss()
                        current = null
                        onEvent("dismissed", mapOf("reason" to reason))
                    }
                },
                onEvent = { action, meta -> activity.runOnUiThread { onEvent(action, meta) } },
                openUrl = { url -> activity.runOnUiThread { openExternally(activity, url) } },
                requestPush = { activity.runOnUiThread { requestPushPermission(activity) } },
            ),
            BRIDGE,
        )

        // The last time the person touched the message: below API 24 the
        // WebView does not say whether a navigation came from a tap.
        var lastTouchAt = 0L
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) lastTouchAt = android.os.SystemClock.uptimeMillis()
            false
        }

        webView.webViewClient = object : WebViewClient() {
            // ONLY the initial document load is allowed (contract item 57;
            // scan 5, iOS S1 checked here): every other navigation - a link,
            // a meta refresh, a form, a frame - is CANCELLED, so no other
            // page can ever run inside the message with the bridge attached.
            // A link the person TAPPED opens in the system browser; anything
            // the document starts on its own (a meta refresh smuggled in
            // through the CSS) opens nothing.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val req = request ?: return true
                val url = req.url?.toString() ?: return true
                val tapped = req.hasGesture() && req.isForMainFrame
                if (navigationOpensExternally(url, tapped)) {
                    openExternally(activity, url)
                    onEvent("clicked", mapOf("action" to "link", "url" to url))
                }
                return true
            }

            // API 23: the request form above is not called there, and this
            // one returned false - ALLOWING the navigation.
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return true
                val tapped = android.os.SystemClock.uptimeMillis() - lastTouchAt < TAP_WINDOW_MS
                if (navigationOpensExternally(target, tapped)) {
                    openExternally(activity, target)
                    onEvent("clicked", mapOf("action" to "link", "url" to target))
                }
                return true
            }

            // Belt and braces: a navigation that reached the page anyway is
            // stopped before it renders.
            private var initialStarted = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val first = !initialStarted
                initialStarted = true
                if (!first && url != null && !isInitialDocument(url)) {
                    view?.stopLoading()
                    UiLog.warn("In-app message ${campaign.id} tried to navigate away; stopped")
                    return
                }
                super.onPageStarted(view, url, favicon)
            }
        }

        webView.loadDataWithBaseURL(
            null,
            buildDocument(campaign.id, html, campaign.type),
            "text/html",
            "UTF-8",
            null,
        )

        current?.dismiss()
        current = dialog
        dialog.show()
        onEvent("displayed", emptyMap())
        return true
    }

    fun dismiss(reason: String = "programmatic") {
        current?.dismiss()
        current = null
    }


    /**
     * Display structured content using the app's own views.
     *
     * Shares the dialog, placement and backdrop rules with the HTML path -
     * those are properties of the message TYPE, not of its content - and
     * differs only in what goes inside.
     */
    private fun showNative(
        activity: Activity,
        campaign: InAppCampaign,
        content: InAppContent.Native,
        onEvent: (action: String, meta: Map<String, Any?>) -> Unit,
    ): Boolean {
        var dialog: Dialog? = null

        val host = object : InAppNativeView.Host {
            override fun onButton(buttonId: String, action: ButtonAction, url: String?) {
                onEvent("clicked", mapOf("action" to buttonId.ifBlank { "button" }))
                when (action) {
                    ButtonAction.URL, ButtonAction.DEEP_LINK -> url?.let { openExternally(activity, it) }
                    ButtonAction.REQUEST_PUSH_PERMISSION ->
                        // Reflective: joryio-ui must not gain a compile-time
                        // dependency on the base SDK's Joryio class - the split
                        // between the two artifacts is the whole point. The base
                        // SDK is always present at runtime when this renders.
                        requestPushPermission(activity)
                    ButtonAction.DISMISS -> Unit
                }
                // EVERY button dismisses. A message the user has acted on and
                // which stays on screen reads as broken, and an unknown action
                // decodes to DISMISS precisely so a future action type cannot
                // trap someone in a modal.
                dialog?.dismiss()
                current = null
                onEvent("dismissed", mapOf("reason" to "button"))
            }

            override fun onClose(reason: String) {
                dialog?.dismiss()
                current = null
                onEvent("dismissed", mapOf("reason" to reason))
            }
        }

        val body = InAppNativeView.build(activity, content, campaign.type, host)

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(wrap(activity, body, campaign.type))
            // Honour the author's choice, but the back button ALWAYS works:
            // backdropDismissible=false must not become "cannot be closed".
            setCancelable(true)
            setCanceledOnTouchOutside(content.backdropDismissible)
            setOnCancelListener { onEvent("dismissed", mapOf("reason" to "back")) }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(backdropColor(campaign.type)))
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setGravity(gravityFor(campaign.type))
            }
        }
        dialog = d

        current?.dismiss()
        current = d
        d.show()
        onEvent("displayed", emptyMap())
        return true
    }

    // ── Presentation ────────────────────────────────────────────────────────

    /** Placement per type, matching the web renderer. */
    private fun gravityFor(type: MessageType): Int = when (type) {
        MessageType.BANNER -> Gravity.TOP
        MessageType.SLIDEUP -> Gravity.BOTTOM
        else -> Gravity.CENTER
    }

    /**
     * Only the screen-blocking types dim the app behind them. A banner or
     * slide-up that dimmed the screen would read as blocking when its whole
     * point is that the app stays usable.
     */
    private fun backdropColor(type: MessageType): Int = when (type) {
        MessageType.MODAL, MessageType.FULLSCREEN -> Color.argb(102, 0, 0, 0)
        else -> Color.TRANSPARENT
    }

    private fun wrap(activity: Activity, body: View, type: MessageType): View {
        val density = activity.resources.displayMetrics.density
        val margin = (DEFAULT_MODAL_MARGIN_DP * density).toInt()

        return FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (type == MessageType.FULLSCREEN) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    // Height tracks the content, driven by the bridge's setSize.
                    ViewGroup.LayoutParams.WRAP_CONTENT
                },
            ).apply {
                gravity = gravityFor(type)
                if (type == MessageType.MODAL) {
                    leftMargin = margin
                    rightMargin = margin
                }
            }
            addView(body, lp)
        }
    }

    // ── Document ────────────────────────────────────────────────────────────

    /**
     * Compose the document the WebView loads: the campaign's CSS, its HTML, and
     * a small shim that (a) tags the device class so the editor's "mobile only"
     * / "desktop only" rules resolve, and (b) reports content height back so a
     * non-fullscreen message is exactly as tall as its content.
     */
    private fun buildDocument(campaignId: String, content: InAppContent.Html, type: MessageType): String {
        val cid = jsString(campaignId)
        /*
         * A fresh, unpredictable nonce per message.
         *
         * The server keeps the author's markup as written (it drops only
         * <base> and meta refresh), so <script> and on* handlers ARE in this
         * document, and this WebView has JavaScript enabled because the
         * bootstrap below needs it. The nonce is the control: only that
         * bootstrap can execute, so nothing the author wrote runs. There is no
         * server sanitiser in front of it.
         *
         * SecureRandom, not Random: a guessable nonce is one an attacker can
         * put on their own injected <script>, which defeats the point entirely.
         */
        val nonce = java.math.BigInteger(128, java.security.SecureRandom()).toString(16)
        // Author CSS that cannot close its <style> element (contract item 57).
        val css = escapeCss(content.css)
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src https: data:; media-src https: data:; font-src https: data:; connect-src 'none'; form-action 'none'; base-uri 'none'; upgrade-insecure-requests">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <style>
              html,body{margin:0;padding:0;background:transparent;}
              /* Responsive by default - see the iOS renderer for why. Before
                 the author stylesheet, so an explicit width still wins. */
              img,video{max-width:100%;height:auto;}
              ${if (type == MessageType.FULLSCREEN) "html,body{height:100%;}" else ""}
            </style>
            <style>${css}</style>
            </head>
            <body>
            ${content.html}
            <script nonce="$nonce">
            (function(){
              var CID = $cid;
              // The editor emits jr-vp-* classes for per-device visibility. On
              // mobile the message viewport IS the device, so this is simply the
              // width - unlike web, where the iframe is message-sized and the
              // host page width has to be read instead.
              try {
                document.documentElement.className +=
                  (window.innerWidth <= 480 ? ' jr-vp-mobile' : ' jr-vp-desktop');
              } catch (e) {}

              // The same surface as the web SDK's joryioBridge, over the
              // Java interface (which takes strings): the object the message
              // sees keeps the web method signatures, and the Java side gets
              // JSON. BACKLOG C23 (2026-09-26): logCustomEvent and friends
              // used to be missing here, so a script calling them did nothing.
              var native = window.$BRIDGE;
              var pending = {}, seq = 0;
              window.__joryioFormResult = function(id, result) {
                var p = pending[id]; delete pending[id];
                if (p) { p(result || {ok: false}); }
              };
              function str(v) { return v === undefined || v === null ? '' : String(v); }
              window.$BRIDGE = {
                logCustomEvent: function(name, props){ try { native.logCustomEvent(CID, str(name), JSON.stringify(props || {})); } catch (e) {} },
                setCustomUserAttribute: function(k, v){ try { native.setCustomUserAttribute(CID, str(k), JSON.stringify({value: v})); } catch (e) {} },
                changeUser: function(uid){ try { native.changeUser(CID, str(uid)); } catch (e) {} },
                requestPushPermission: function(){ try { native.requestPushPermission(CID); } catch (e) {} },
                logConversion: function(ev){ try { native.logConversion(CID, str(ev || 'in_app_conversion')); } catch (e) {} },
                logClick: function(action){ try { native.trackClick(CID, str(action || 'click')); } catch (e) {} },
                trackClick: function(cid, action){ try { native.trackClick(CID, str(action || 'click')); } catch (e) {} },
                closeMessage: function(){ try { native.closeMessage(CID); } catch (e) {} },
                navigate: function(url, target){
                  if (typeof target === 'string' && /^https?:/i.test(target) && !/^https?:/i.test(String(url))) { url = target; target = arguments[2]; }
                  try { native.navigate(CID, str(url), target ? str(target) : null); } catch (e) {}
                },
                setSize: function(cid, h){ try { native.setSize(CID, h | 0); } catch (e) {} },
                submitForm: function(values){
                  return new Promise(function(resolve){
                    var id = 'f' + (++seq);
                    pending[id] = resolve;
                    try { native.submitForm(CID, id, JSON.stringify(values || {})); } catch (e) { delete pending[id]; resolve({ok: false}); }
                  });
                }
              };

              // data-joryio-action buttons, as on web.
              document.addEventListener('click', function(e){
                var el = e.target;
                while (el && el !== document.body && el !== document.documentElement) {
                  var action = el.getAttribute && el.getAttribute('data-joryio-action');
                  if (action && typeof window.$BRIDGE[action] === 'function') { e.preventDefault(); window.$BRIDGE[action](el.getAttribute('data-joryio-event') || undefined); return; }
                  var tag = el.tagName;
                  if (tag === 'A' || tag === 'BUTTON') {
                    // As on web: every link and button click is reported, named
                    // by data-action when the author gave one. A link opens
                    // outside the message; navigate only opens, so one click.
                    if (el.type === 'submit') { return; }
                    var da = el.getAttribute('data-action');
                    var href = tag === 'A' ? el.getAttribute('href') : null;
                    window.$BRIDGE.trackClick(CID, da || (href ? 'link_click' : 'cta_click'));
                    if (href && /^(https?:\\/\\/|\\/(?!\\/))/i.test(href)) { e.preventDefault(); window.$BRIDGE.navigate(href, el.getAttribute('target')); }
                    return;
                  }
                  el = el.parentNode;
                }
              }, true);

              // Forms (data-jry-form): serialise, hand the values to the app,
              // show the answer in place. A redirect goes through navigate.
              document.addEventListener('submit', function(ev){
                var f = ev.target;
                if (!(f && f.tagName === 'FORM' && f.hasAttribute('data-jry-form'))) return;
                ev.preventDefault();
                if (f.getAttribute('data-jry-busy')) return;
                var d = {}, els = f.querySelectorAll('input,select,textarea');
                for (var i = 0; i < els.length; i++) {
                  var el = els[i], n = el.name; if (!n || el.disabled) continue;
                  if (el.type === 'checkbox') { if (!el.checked) continue; if (d[n] === undefined) d[n] = []; d[n].push(el.value || 'on'); }
                  else if (el.type === 'radio') { if (el.checked) d[n] = el.value; }
                  else d[n] = el.value;
                }
                function msg(text, ok){
                  var b = f.querySelector('[data-jry-message]');
                  if (!b) { b = document.createElement('div'); b.setAttribute('data-jry-message', ''); b.setAttribute('role', 'status'); f.appendChild(b); }
                  b.textContent = text; b.className = ok ? 'jry-form-success' : 'jry-form-error';
                  report();
                }
                f.setAttribute('data-jry-busy', '1');
                var btn = f.querySelector('button[type="submit"],input[type="submit"]'); if (btn) btn.disabled = true;
                window.$BRIDGE.logClick('form_submit');
                window.$BRIDGE.submitForm(d).then(function(r){
                  if (r && r.ok) {
                    if (r.redirect) { window.$BRIDGE.navigate(r.redirect, '_top'); return; }
                    var all = f.querySelectorAll('input,select,textarea,button'); for (var j = 0; j < all.length; j++) { all[j].disabled = true; }
                    msg(r.message || 'Thank you.', true);
                  } else {
                    msg((r && (r.message || (r.errors && r.errors[0]))) || 'Something went wrong. Please try again.', false);
                    f.removeAttribute('data-jry-busy'); if (btn) btn.disabled = false;
                  }
                });
              }, true);

              function report() {
                try {
                  var h = Math.max(
                    document.body ? document.body.scrollHeight : 0,
                    document.documentElement ? document.documentElement.scrollHeight : 0
                  );
                  if (h > 0 && native) native.setSize(CID, h);
                } catch (e) {}
              }
              window.addEventListener('load', report);
              // Images and web fonts land after load and change the height, so
              // a single measurement would leave the message clipped.
              if (window.ResizeObserver && document.body) {
                new ResizeObserver(report).observe(document.body);
              }
              setTimeout(report, 60);
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Hand the primer's "yes" to the base SDK, which owns the permission flow.
     *
     * Called by reflection so this UI artifact keeps zero compile-time coupling
     * to `io.joryio.sdk.Joryio`. A failure here is logged and swallowed: the
     * message has already been acted on, and no primer is worth crashing an app.
     */
    private fun requestPushPermission(activity: android.app.Activity) {
        try {
            val joryio = Class.forName("io.joryio.sdk.Joryio")
            val instance = joryio.getMethod("getInstance").invoke(null)
            joryio.getMethod(
                "requestPushPermission",
                android.app.Activity::class.java,
                kotlin.jvm.functions.Function1::class.java,
            ).invoke(instance, activity, null)
        } catch (t: Throwable) {
            UiLog.warn("Could not request push permission: ${t.message}")
        }
    }

    private fun openExternally(activity: Activity, url: String) {
        if (!isSafeUrl(url)) return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            UiLog.warn("No activity to open $url: ${e.message}")
        }
    }

    /**
     * http(s) only.
     *
     * Same rule as the web renderer, for the same reason: a campaign document
     * is authored content, and `javascript:`, `data:`, `file:` and `intent:`
     * URLs are how authored content escapes its container. Deep links are
     * deliberately routed through the host app's own click handler rather than
     * being opened here.
     */
    private fun isSafeUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** JSON-safe string literal for embedding in the shim. */
    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    // ── Bridge ──────────────────────────────────────────────────────────────

    /**
     * The ONLY surface the document's script - the SDK bootstrap; the CSP
     * blocks author script - can reach. Method names match the web SDK's
     * `joryioBridge` so a template authored once works on both.
     */
    private class Bridge(
        private val campaignId: String,
        private val type: MessageType,
        private val webView: WebView,
        private val dismiss: (String) -> Unit,
        private val onEvent: (String, Map<String, Any?>) -> Unit,
        private val openUrl: (String) -> Unit,
        private val requestPush: () -> Unit,
    ) {
        // The rest of the web bridge (BACKLOG C23, 2026-09-26). Arguments
        // arrive as JSON strings from the bootstrap; the base SDK does the work
        // through onEvent, so this artifact stays free of a dependency on it.
        @JavascriptInterface
        fun logCustomEvent(cid: String, name: String?, propertiesJson: String?) {
            if (cid != campaignId || name.isNullOrBlank()) return
            onEvent("custom_event", mapOf("name" to name, "properties" to parseJsonObject(propertiesJson)))
        }

        @JavascriptInterface
        fun setCustomUserAttribute(cid: String, key: String?, valueJson: String?) {
            if (cid != campaignId || key.isNullOrBlank()) return
            val value = parseJsonObject(valueJson)["value"] ?: return
            onEvent("set_attribute", mapOf("key" to key, "value" to value))
        }

        @JavascriptInterface
        fun changeUser(cid: String, userId: String?) {
            if (cid != campaignId || userId.isNullOrBlank()) return
            onEvent("change_user", mapOf("userId" to userId))
        }

        @JavascriptInterface
        fun requestPushPermission(cid: String) {
            if (cid != campaignId) return
            requestPush()
        }

        @JavascriptInterface
        fun logConversion(cid: String, event: String?) {
            if (cid != campaignId) return
            onEvent("converted", mapOf("event" to (event ?: "in_app_conversion")))
        }

        /**
         * A form inside the message. The base SDK posts the values with its
         * identity and answers through `respond`, which lands the JSON in the
         * message's promise (window.__joryioFormResult).
         */
        @JavascriptInterface
        fun submitForm(cid: String, requestId: String?, valuesJson: String?) {
            if (cid != campaignId || requestId.isNullOrBlank()) return
            val id = requestId.replace("\\", "\\\\").replace("'", "\\'")
            val respond: (String) -> Unit = { json ->
                webView.post { webView.evaluateJavascript("window.__joryioFormResult('$id', $json);", null) }
            }
            onEvent("form_submit", mapOf("values" to parseJsonObject(valuesJson), "respond" to respond))
        }

        @JavascriptInterface
        fun trackClick(cid: String, action: String?) {
            if (cid != campaignId) return
            onEvent("clicked", mapOf("action" to (action ?: "click")))
        }

        @JavascriptInterface
        fun closeMessage(cid: String) {
            if (cid != campaignId) return
            dismiss("bridge_close")
        }

        @JavascriptInterface
        fun navigate(cid: String, url: String?, target: String?) {
            if (cid != campaignId || url == null) return
            openUrl(url)
        }

        /**
         * Size the container to the content.
         *
         * Fullscreen ignores this - it already fills the screen, and honouring a
         * reported height there would shrink it to its content and leave a
         * dimmed backdrop with a floating strip.
         */
        @JavascriptInterface
        fun setSize(cid: String, height: Int) {
            if (cid != campaignId || height <= 0 || type == MessageType.FULLSCREEN) return
            webView.post {
                val density = webView.resources.displayMetrics.density
                val px = (height * density).toInt()
                val max = (webView.resources.displayMetrics.heightPixels * 0.9).toInt()
                webView.layoutParams = webView.layoutParams?.apply {
                    this.height = minOf(px, max)
                } ?: return@post
                webView.requestLayout()
            }
        }
    }
}

/** A JSON object from the bootstrap as a plain map (strings, numbers, booleans, lists, nested maps). File-level so the nested Bridge can use it. */
@Suppress("UNCHECKED_CAST")
private fun parseJsonObject(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        toKotlin(org.json.JSONObject(json)) as Map<String, Any?>
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun toKotlin(v: Any?): Any? = when (v) {
    null, org.json.JSONObject.NULL -> null
    is org.json.JSONObject -> v.keys().asSequence().associateWith { k -> toKotlin(v.opt(k)) }
    is org.json.JSONArray -> (0 until v.length()).map { i -> toKotlin(v.opt(i)) }
    else -> v
}
