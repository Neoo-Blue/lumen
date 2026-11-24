package ai.lumen.assistant

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lumen's overlay over any app, drawn by the accessibility service (no extra permission): review every box before
 * filling, watch the filling, and give the final OK on top of the page. Content is a bundled page; data is shown as text.
 */
class Sheet(private val svc: LumenAccess) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var web: WebView? = null
    private var interactive = false
    private var answer: ((JSONObject) -> Unit)? = null

    inner class Bridge {
        @JavascriptInterface fun done(json: String) {
            val r = try { JSONObject(json) } catch (_: Exception) { JSONObject().put("ok", false) }
            main.post { val a = answer; answer = null; hideNow(); a?.invoke(r) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun mount(touchable: Boolean, onReady: (WebView) -> Unit) {
        if (web != null && interactive == touchable) return onReady(web!!)
        hideNow()
        val w = WebView(svc)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.addJavascriptInterface(Bridge(), "LumenOverlay")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true // the overlay never navigates
            override fun onPageFinished(view: WebView, url: String?) { onReady(view) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (touchable) WindowManager.LayoutParams.MATCH_PARENT else (svc.resources.displayMetrics.density * 220).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        wm.addView(w, lp)
        web = w; interactive = touchable
        w.loadUrl("file:///android_asset/overlay.html")
    }

    private fun showNow(mode: String, data: JSONObject, touchable: Boolean) = main.post {
        try {
            val js = "show(" + JSONObject.quote(mode) + "," + data.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"
            if (web != null && interactive == touchable && mode == "progress") web!!.evaluateJavascript(js, null)
            else mount(touchable) { it.evaluateJavascript(js, null) }
        } catch (e: Exception) { Telemetry.warn("overlay", "could not show: ${e.message}"); val a = answer; answer = null; a?.invoke(JSONObject().put("ok", false).put("unavailable", true)) }
    }

    fun hide() { main.post { hideNow() } }
    private fun hideNow() { web?.let { try { wm.removeView(it) } catch (_: Exception) {}; it.destroy() }; web = null }

    /** Shows a review or confirmation and waits for the person (from a worker thread). Null when it could not be shown. */
    fun ask(mode: String, data: JSONObject, timeoutMin: Long = 10): JSONObject? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1); var out: JSONObject? = null
        main.post { answer = { out = it; latch.countDown() } }
        showNow(mode, data, true)
        if (!latch.await(timeoutMin, TimeUnit.MINUTES)) { main.post { answer = null }; hide(); return JSONObject().put("ok", false).put("timeout", true) }
        return out?.takeUnless { it.optBoolean("unavailable") }
    }

    /** The slim card at the top while Lumen fills things in; touches go straight through to the app. */
    fun progress(title: String, steps: List<String>, at: Int, done: String? = null) =
        showNow("progress", JSONObject().put("title", title).put("steps", org.json.JSONArray(steps)).put("at", at).also { if (done != null) it.put("done", done) }, false)
}
