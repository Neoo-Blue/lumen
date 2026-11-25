package ai.lumen.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap
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
    @Volatile private var style = "" // "modal", "pass", "card", "band" (the teaching bar)
    @Volatile private var teachCb: (() -> Unit)? = null
    @Volatile private var answer: ((JSONObject) -> Unit)? = null
    private var askGen = 0 // main thread only: which confirmation owns the modal
    private val cardHide = Runnable { if (style == "card") hideNow() }
    // JS waiting on overlay.html, one list per WebView. Touched on the main thread only.
    private val pendingJs = IdentityHashMap<WebView, ArrayList<String>>()
    private val pageReady = IdentityHashMap<WebView, Boolean>()

    inner class Bridge {
        @JavascriptInterface fun done(json: String) {
            val r = try { JSONObject(json) } catch (_: Exception) { JSONObject().put("ok", false) }
            main.post {
                if (style == "band") {
                    val t = teachCb
                    teachCb = null
                    hideNow()
                    t?.invoke()
                } else {
                    val a = answer
                    answer = null
                    hideNow()
                    a?.invoke(r)
                }
            }
        }
        @JavascriptInterface fun open(what: String) {
            main.post {
                hideNow()
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("ai.lumen.assistant://open?what=" + Uri.encode(what)))
                    .setClass(svc, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { svc.startActivity(i) } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun mount(kind: String, js: String) {
        val current = web
        if (current != null && style == kind) {
            enqueue(current, js)
            return
        }
        // A fresh modal keeps the answer just posted for it. Any other replacement releases a waiting confirmation.
        val stranded = if (kind == "modal" && answer != null) null else takeModalAnswer()
        destroyWeb()
        style = ""
        stranded?.invoke(closed())
        val w = WebView(svc)
        pendingJs[w] = arrayListOf(js)
        pageReady[w] = false
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.addJavascriptInterface(Bridge(), "LumenOverlay")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true // the overlay never navigates
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady[view] = true
                val queued = pendingJs[view]?.toList().orEmpty()
                pendingJs[view]?.clear()
                for (item in queued) view.evaluateJavascript(item, null)
            }
        }
        val d = svc.resources.displayMetrics.density
        val height = when (kind) {
            "modal" -> WindowManager.LayoutParams.MATCH_PARENT
            "card" -> (d * 230).toInt()
            "band" -> (d * 72).toInt()
            else -> (d * 220).toInt()
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or when (kind) {
            "modal" -> 0
            "card", "band" -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE; if (kind == "band") y = statusBarPx() }
        try {
            wm.addView(w, lp)
        } catch (e: Exception) {
            pendingJs.remove(w)
            pageReady.remove(w)
            try { w.destroy() } catch (_: Exception) {}
            throw e
        }
        web = w
        style = kind
        w.loadUrl("file:///android_asset/overlay.html")
    }

    /** Run show() now, or keep it until overlay.html has finished loading. */
    private fun enqueue(view: WebView, js: String) {
        if (pageReady[view] == true) view.evaluateJavascript(js, null)
        else pendingJs.getOrPut(view) { ArrayList() }.add(js)
    }

    /** Main thread. A card or a progress strip never covers a confirmation or the teaching bar. */
    private fun present(mode: String, data: JSONObject, kind: String) {
        if (kind != "modal" && kind != "band" && (style == "modal" || style == "band" || answer != null)) return
        try {
            main.removeCallbacks(cardHide)
            val js = "show(" + JSONObject.quote(mode) + "," + data.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")"
            mount(kind, js)
            if (kind == "card") main.postDelayed(cardHide, 25_000L)
        } catch (e: Exception) {
            Telemetry.warn("overlay", "could not show: ${e.message}")
            val a = answer
            answer = null
            a?.invoke(JSONObject().put("ok", false).put("unavailable", true))
        }
    }

    private fun closed(): JSONObject = JSONObject().put("ok", false).put("error", "closed")

    /** Answer waiting on the modal that is about to go away. Nulls it first so it runs once. */
    private fun takeModalAnswer(): ((JSONObject) -> Unit)? {
        if (style != "modal") return null
        val a = answer ?: return null
        answer = null
        return a
    }

    private fun destroyWeb() {
        main.removeCallbacks(cardHide)
        val w = web ?: return
        web = null
        pendingJs.remove(w)
        pageReady.remove(w)
        try { wm.removeView(w) } catch (_: Exception) {}
        w.destroy()
    }

    fun hide() { main.post { if (style == "pass") hideNow() } }
    fun hideCard() { main.post { if (style == "card") hideNow() } }
    private fun hideNow() {
        val a = takeModalAnswer()
        destroyWeb()
        style = ""
        a?.invoke(closed())
    }

    /** Shows a review or confirmation and waits for the person (from a worker thread). Null when it could not be shown. */
    fun ask(mode: String, data: JSONObject, timeoutMin: Long = 10): JSONObject? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1)
        var out: JSONObject? = null
        val mine = java.util.concurrent.atomic.AtomicInteger(0)
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        main.post {
            if (!open.get()) return@post
            val previous = answer
            answer = null
            // Release the confirmation we are replacing before this one takes the slot.
            try { if (previous != null) previous.invoke(closed()) } catch (_: Exception) {}
            val g = ++askGen
            mine.set(g)
            answer = { result ->
                if (g == askGen) {
                    out = result
                    latch.countDown()
                }
            }
            present(mode, data, "modal")
        }
        if (!latch.await(timeoutMin, TimeUnit.MINUTES)) {
            open.set(false)
            main.post {
                // A newer confirmation owns the sheet. Leave it alone.
                if (mine.get() == 0 || mine.get() != askGen) return@post
                answer = null
                askGen++
                if (style == "modal") hideNow()
            }
            return JSONObject().put("ok", false).put("timeout", true)
        }
        return out?.takeUnless { it.optBoolean("unavailable") }
    }

    /** The slim card at the top while Lumen fills things in; touches go straight through to the app. */
    fun progress(title: String, steps: List<String>, at: Int, done: String? = null) {
        // A skill or a rule must not cover a confirmation or the teaching bar.
        if (style == "modal" || style == "band" || answer != null) return
        val data = JSONObject().put("title", title).put("steps", JSONArray(steps)).put("at", at)
        if (done != null) data.put("done", done)
        main.post {
            if (style == "modal" || style == "band" || answer != null) return@post
            present("progress", data, "pass")
        }
    }

    /**
     * Non-blocking top card (e.g. call brief). Returns false when a confirmation or the teaching bar is up,
     * so the caller can post its heads-up notice instead.
     */
    fun showCard(title: String, lines: List<String>, openWhat: String): Boolean {
        val go = {
            if (answer != null || style == "modal" || style == "band") false
            else {
                present("card", JSONObject().put("title", title).put("lines", JSONArray(lines)).put("open", openWhat), "card")
                style == "card"
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return go()
        val latch = CountDownLatch(1)
        var ok = false
        main.post { ok = go(); latch.countDown() }
        return latch.await(2, TimeUnit.SECONDS) && ok
    }

    private fun statusBarPx(): Int {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else (svc.resources.displayMetrics.density * 24).toInt()
    }

    /** A short bar over the status area with a Stop button while Lumen watches a lesson; touches elsewhere reach the app. */
    fun watchTeach(onStop: () -> Unit): Boolean {
        val go = {
            try {
                teachCb = onStop
                val js = "show(" + JSONObject.quote("teach") + "," + JSONObject().put("title", "Lumen is watching: tap Stop when done").toString() + ")"
                mount("band", js)
                true
            } catch (e: Exception) {
                teachCb = null
                Telemetry.warn("teach", "bar failed: ${e.message}")
                false
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return go()
        val latch = CountDownLatch(1)
        var ok = false
        main.post { ok = go(); latch.countDown() }
        return latch.await(2, TimeUnit.SECONDS) && ok
    }

    fun hideTeach() { main.post { teachCb = null; if (style == "band") hideNow() } }
}
