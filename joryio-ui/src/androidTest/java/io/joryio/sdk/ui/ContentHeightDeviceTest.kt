package io.joryio.sdk.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.joryio.sdk.models.InAppContent
import io.joryio.sdk.models.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * D204 on a real WebView: the document the renderer builds reports its
 * content height, and keeps reporting it as the content shrinks - the old
 * measure (max of body and root scrollHeight) never went below the WebView's
 * own height.
 */
@RunWith(AndroidJUnit4::class)
class ContentHeightDeviceTest {
    class Recorder {
        val heights = CopyOnWriteArrayList<Int>()
        @JavascriptInterface fun setSize(cid: String, h: Int) { heights.add(h) }
        @JavascriptInterface fun logClick(cid: String, a: String?) {}
    }

    private fun waitFor(limitMs: Long = 20_000, pred: () -> Boolean) {
        val until = System.currentTimeMillis() + limitMs
        while (!pred() && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    @Test
    fun aShortMessageReportsItsHeightAndShrinksAfterAnUpdate() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val recorder = Recorder()
        lateinit var webView: WebView
        val html = """<div id="form" style="height:300px">Form</div><div style="height:50px">Footer</div>"""
        val doc = InAppMessageRenderer().buildDocument("c", InAppContent.Html(html, ""), MessageType.MODAL)
        val scenario = ActivityScenario.launch(HostActivity::class.java)
        scenario.onActivity { activity ->
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(recorder, InAppMessageRenderer.BRIDGE)
            }
            // A frame TALLER than the content, which is exactly what kept the
            // old measure from ever reporting less than the frame.
            val host = FrameLayout(activity)
            host.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1500))
            activity.setContentView(host)
            webView.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
        }
        waitFor { recorder.heights.contains(350) }
        assertEquals(350, recorder.heights.lastOrNull())
        inst.runOnMainSync { webView.evaluateJavascript("document.getElementById('form').remove()", null) }
        waitFor { recorder.heights.lastOrNull() == 50 }
        assertEquals(50, recorder.heights.lastOrNull())
        scenario.close()
    }
}
