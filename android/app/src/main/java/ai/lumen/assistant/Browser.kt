package ai.lumen.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lumen's own browser: the third way in, for services with no API or MCP. The person signs in here once; the
 * session stays in the app's cookie store, and Lumen reads or acts on those sites later in a hidden page.
 * None of these pages ever gets the phone bridge: only the main Lumen page has it.
 */
object Sessions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    fun list(ctx: Context): JSONArray = JSONArray(prefs(ctx).getStringSet("sessions", emptySet())!!.sorted())
    fun add(ctx: Context, host: String) {
        val set = prefs(ctx).getStringSet("sessions", emptySet())!!.toMutableSet()
        if (set.add(host.removePrefix("www."))) prefs(ctx).edit().putStringSet("sessions", set).apply()
    }
    fun forget(ctx: Context, host: String) {
        val h = host.removePrefix("www.")
        val set = prefs(ctx).getStringSet("sessions", emptySet())!!.toMutableSet()
        set.remove(h); prefs(ctx).edit().putStringSet("sessions", set).apply()
        val cm = CookieManager.getInstance()
        for (u in listOf("https://$h", "https://www.$h")) cm.getCookie(u)?.split(";")?.forEach { c ->
            val name = c.substringBefore("=").trim()
            if (name.isNotEmpty()) for (d in listOf(h, ".$h", "www.$h")) cm.setCookie("https://$d", "$name=; Max-Age=0; Path=/")
        }
        cm.flush()
    }

    /** Sign-in pages, Google's included, refuse anything that says it is an embedded web view. */
    fun browserAgent(web: WebView): String = web.settings.userAgentString.replace("; wv", "").replace(Regex(" Version/[\\d.]+"), "")

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = browserAgent(web)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    fun httpsOnly(url: String): Uri? = try { Uri.parse(url).takeIf { it.scheme == "https" && !it.host.isNullOrBlank() } } catch (_: Exception) { null }
}

class BrowserActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = Sessions.httpsOnly(intent.getStringExtra("url") ?: "") ?: return finish()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#07080D")); setPadding(36, 24, 24, 24)
        }
        val title = TextView(this).apply { setTextColor(Color.parseColor("#C9CBD6")); textSize = 14f; text = start.host }
        val done = Button(this).apply { text = "Done"; setOnClickListener { finish() } }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); bar.addView(done)
        web = WebView(this)
        Sessions.configure(web)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !WebGuard.ok(request.url) // https sites only: no app links, intents, plain http or this phone's own addresses
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebGuard.block(request.url)
            override fun onPageFinished(view: WebView, url: String?) {
                title.text = Uri.parse(url ?: "").host ?: ""
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar); addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(start.toString())
    }

    override fun finish() {
        if (::web.isInitialized) {
            // remember the site only if it actually left a session behind
            val host = Sessions.httpsOnly(intent.getStringExtra("url") ?: "")?.host
            if (host != null && !CookieManager.getInstance().getCookie("https://$host").isNullOrBlank()) Sessions.add(this, host)
            CookieManager.getInstance().flush()
        }
        super.finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}

/** A page nobody sees: loads a site with the person's sessions, optionally clicks and types, then reads what is there. */
class HiddenPage(private val ctx: Context, private val done: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var finished = false

    fun run(url: String, steps: JSONArray) {
        val start = Sessions.httpsOnly(url) ?: return finish(JSONObject().put("ok", false).put("error", "https only"))
        val w = WebView(ctx); web = w
        Sessions.configure(w)
        w.measure(1080, 2200); w.layout(0, 0, 1080, 2200)
        var started = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !WebGuard.ok(request.url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebGuard.block(request.url)
            override fun onPageFinished(view: WebView, u: String?) {
                if (started) return
                started = true
                main.postDelayed({ step(steps, 0) }, 2500) // let scripts draw the page
            }
        }
        main.postDelayed({ finish(JSONObject().put("ok", false).put("error", "the page took too long")) }, 40000)
        w.loadUrl(start.toString())
    }

    private fun step(steps: JSONArray, i: Int) {
        val w = web ?: return
        if (i >= steps.length()) return w.evaluateJavascript(READ) { raw -> finish(parse(raw)) }
        w.evaluateJavascript("($ACT)(${steps.optJSONObject(i) ?: JSONObject()})") { r ->
            if (r?.trim('"') == "missing") return@evaluateJavascript w.evaluateJavascript(READ) { raw ->
                finish(parse(raw).also { it.put("ok", false).put("error", "step ${i + 1} found nothing to act on") })
            }
            main.postDelayed({ step(steps, i + 1) }, 2500)
        }
    }

    private fun parse(raw: String?): JSONObject = try {
        val page = JSONObject(JSONArray("[$raw]").getString(0))
        JSONObject().put("ok", true).put("data", page)
    } catch (_: Exception) { JSONObject().put("ok", false).put("error", "could not read the page") }

    private fun finish(result: JSONObject) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        web?.destroy(); web = null
        done(result)
    }

    companion object {
        private const val READ = """(function(){var t=document.body?document.body.innerText:'';
var links=[].slice.call(document.querySelectorAll('a[href]')).map(function(a){return [a.innerText.trim().slice(0,80),a.href]}).filter(function(x){return x[0]}).slice(0,40);
var fields=[].slice.call(document.querySelectorAll('input:not([type=hidden]),textarea,select,button')).slice(0,40).map(function(e){return {tag:e.tagName.toLowerCase(),type:e.type||'',name:e.name||e.id||'',label:((e.labels&&e.labels[0]&&e.labels[0].innerText)||e.placeholder||e.getAttribute('aria-label')||e.innerText||'').trim().slice(0,60)}});
return JSON.stringify({title:document.title,url:location.href,text:t.replace(/\n{3,}/g,'\n\n').slice(0,5000),links:links,fields:fields});})()"""

        private const val ACT = """function(s){function find(q){try{var e=document.querySelector(q);if(e)return e}catch(_){}q=String(q).toLowerCase().trim();
var all=[].slice.call(document.querySelectorAll('a,button,input,textarea,select,label,[role=button],[onclick]'));
function vals(e){return [e.innerText,e.value,e.name,e.id,e.placeholder,e.getAttribute('aria-label'),e.labels&&e.labels[0]&&e.labels[0].innerText]}
return all.find(function(e){return vals(e).some(function(v){return v&&String(v).toLowerCase().trim()===q})})||all.find(function(e){return vals(e).some(function(v){return v&&String(v).toLowerCase().indexOf(q)>=0})})}
if(s.go){location.href=s.go;return 'ok'}
if(s.click){var e=find(s.click);if(!e)return 'missing';e.scrollIntoView();e.click();return 'ok'}
if(s.type){var f=find(s.type.into);if(!f)return 'missing';f.focus();var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(f),'value');if(d&&d.set)d.set.call(f,s.type.text);else f.value=s.type.text;
f.dispatchEvent(new Event('input',{bubbles:true}));f.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'}
if(s.submit){var g=(document.activeElement&&document.activeElement.form)||document.querySelector('form');if(!g)return 'missing';if(g.requestSubmit)g.requestSubmit();else g.submit();return 'ok'}
return 'ok'}"""
    }
}


/**
 * Lumen's browsers share the app's cookie store, and Lumen's own pages live on this phone (127.0.0.1). A site must
 * never be able to steer them to this phone's or the home network's addresses, or downgrade to plain http.
 */
object WebGuard {
    fun ok(u: android.net.Uri?): Boolean = u != null && u.scheme == "https" && !private(u.host ?: "")
    /** Blocks any request (page or part of a page) to this phone or a private network. */
    fun block(u: android.net.Uri?): android.webkit.WebResourceResponse? =
        if (u != null && (u.scheme == "http" || u.scheme == "https") && private(u.host ?: "")) android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) else null
    fun private(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        val v4 = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").find(h)?.groupValues?.drop(1)?.map { it.toInt() }
        if (v4 != null) { val (a, b) = v4; return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224 }
        return h.contains(':') // any raw IPv6 address: sites use names
    }
}
