package ai.lumen.assistant

import androidx.annotation.RequiresApi
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque

open class MainActivity : AppCompatActivity() {
    /** The connect screen (a bundled page) talks to Tailnet through this; it answers only while that page is shown. */
    inner class ConnectBridge {
        private fun onConnectPage(): Boolean = try { runOnUiThreadBlocking { webView.url } == CONNECT } catch (_: Exception) { false }
        @android.webkit.JavascriptInterface fun status(): String {
            if (!onConnectPage()) return "{}"
            if (PhoneMode.mode(this@MainActivity) == "phone") return org.json.JSONObject().put("mode", "phone").put("ready", PhoneMode.ready()).toString()
            return org.json.JSONObject().put("mode", PhoneMode.mode(this@MainActivity)).put("state", Tailnet.state()).put("login", Tailnet.loginUrl()).put("server", Tailnet.server ?: "")
                .put("name", Tailnet.serverName).put("error", Tailnet.lastError).toString()
        }
        @android.webkit.JavascriptInterface fun login() { if (onConnectPage()) Tailnet.loginUrl().takeIf { it.startsWith("https://") }?.let { openInBrowser(this@MainActivity, Uri.parse(it)) } }
        @android.webkit.JavascriptInterface fun find(): Boolean = onConnectPage() && Tailnet.discover(this@MainActivity)
        @android.webkit.JavascriptInterface fun use(url: String): Boolean = onConnectPage() && Tailnet.use(this@MainActivity, url)
        /** The person picked where Lumen runs: "phone" (everything here) or "server" (their own server over Tailscale). */
        @android.webkit.JavascriptInterface fun mode(m: String) {
            if (!onConnectPage() || m !in setOf("phone", "server")) return
            PhoneMode.choose(this@MainActivity, m)
            if (m == "phone") LumenCore.ensure(this@MainActivity) else Tailnet.start(this@MainActivity)
        }
        @android.webkit.JavascriptInterface fun control(url: String) { if (onConnectPage()) Tailnet.setControl(this@MainActivity, url) }
        @android.webkit.JavascriptInterface fun open() { if (onConnectPage()) runOnUiThread { if (!incoming(intent)) webView.loadUrl(if (overlay) LumenOrigin.HOME + "/?overlay=1" else LumenOrigin.HOME) } } // a share or shortcut that started Lumen goes through once connected
    }
    private fun <T> runOnUiThreadBlocking(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val l = java.util.concurrent.CountDownLatch(1); var out: T? = null
        runOnUiThread { out = f(); l.countDown() }; l.await(2, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST") return out as T
    }
    private val CONNECT = "file:///android_asset/connect.html"

    /** The assistant panel over other apps reuses this whole screen in a small see-through window. */
    protected open val overlay = false
    protected open fun onPanel(msg: JSONObject) {}

    /** Run a line of script in Lumen's page (only while it shows Lumen's own page). */
    @Volatile private var barsCss = ""
    fun page(js: String) { if (!webViewDestroyed && ::webView.isInitialized && LumenOrigin.allows(webView.url)) webView.evaluateJavascript(js, null) }
    private var updateChecked = false

    lateinit var webView: WebView
        private set

    @Volatile
    var pageHost: String? = null
        private set

    private var bridge: LumenBridge? = null
    private var pageReady = false
    private var voicePending = false
    private var voiceFired = false
    private var webViewDestroyed = false
    private var popupView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoDialog: AlertDialog? = null
    private var pendingWebAudio: PermissionRequest? = null

    private class PermReq(
        val requested: Array<String>,
        val anyOf: Boolean,
        val consider: Array<String>,
        val done: (Boolean) -> Unit
    )

    private val permissionQueue = ArrayDeque<PermReq>()
    private var permissionInFlight = false
    private var inflightPerm: PermReq? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val req = inflightPerm
        inflightPerm = null
        permissionInFlight = false
        if (req != null) {
            val granted = if (req.anyOf) {
                req.consider.any { result[it] == true || hasPerm(it) }
            } else {
                req.requested.all { result[it] == true || hasPerm(it) }
            }
            try {
                req.done(granted)
            } catch (_: Exception) {
            }
        }
        drainPermissions()
    }

    private class CamReq(val done: (File?) -> Unit)

    private val camQueue = ArrayDeque<CamReq>()
    private var camInFlight: Pair<CamReq, File>? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val flight = camInFlight
        camInFlight = null
        if (flight != null) {
            val (req, file) = flight
            if (success && file.exists() && file.length() > 0L) {
                req.done(file)
            } else {
                file.delete()
                req.done(null)
            }
        }
        drainCamera()
    }

    private class PickReq(val multiple: Boolean, val done: (List<Uri>?) -> Unit)

    private val pickQueue = ArrayDeque<PickReq>()
    private var pickInFlight: PickReq? = null

    private val singlePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        finishPick(if (uri == null) null else listOf(uri))
    }

    private val multiPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris ->
        finishPick(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.init(applicationContext)
        applySystemBars()
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView = WebView(this)
        configureWebView(webView)
        setContentView(webView)
        window.decorView.setBackgroundColor(if (overlay) Color.TRANSPARENT else Color.parseColor("#07080D"))
        if (overlay) webView.setBackgroundColor(Color.TRANSPARENT)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(0, 0, 0, ime)
            // the page draws under the status and navigation bars: tell it how tall they are on this phone
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            barsCss = "document.documentElement.style.setProperty('--sat','${(bars.top / d).toInt()}px');document.documentElement.style.setProperty('--sab','${(bars.bottom / d).toInt()}px')"
            page(barsCss)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!webViewDestroyed && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        cleanOldCaptures()
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        webView.addJavascriptInterface(ConnectBridge(), "LumenConnect")
        val connected = when (PhoneMode.mode(this)) { "phone" -> PhoneMode.up; "server" -> Tailnet.ready(); else -> false }
        if (!connected) webView.loadUrl(CONNECT) // first: where Lumen runs, and the connection to it
        else if (!handOff(intent) && !incoming(intent) && restored == null) webView.loadUrl(if (overlay) LumenOrigin.HOME + "/?overlay=1" else LumenOrigin.HOME)
        overLockScreen(intent)
        armVoice(intent)
        if (!overlay) askEverythingOnce()
        if (WakeService.enabled(this) && WakeService.instance == null) try { WakeService.setEnabled(this, true) } catch (e: Exception) { Telemetry.warn("wake", "could not restart: ${e.message}") }
        ViewCompat.requestApplyInsets(webView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handOff(intent) || incoming(intent)
        overLockScreen(intent)
        armVoice(intent)
    }

    private val OWN_ASKS = setOf("Take a picture and tell me what it is", "What does my day look like?")

    /** Text arriving from elsewhere: a long-press shortcut, "Ask Lumen" on selected text, or the share sheet. */
    private fun incoming(source: Intent?): Boolean {
        val src = source ?: return false
        val text = when {
            // any app can open this link, so only Lumen's own shortcuts ask at once; anything else waits in the box for the person
            src.data?.scheme == "ai.lumen.assistant" && src.data?.host == "ask" -> src.data?.getQueryParameter("q")?.let { if (it in OWN_ASKS) "a" to it else "p" to it }
            src.data?.scheme == "ai.lumen.assistant" && src.data?.host == "open" -> src.data?.getQueryParameter("what")?.let { "o" to it }
            src.action == Intent.ACTION_PROCESS_TEXT -> src.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { "s" to it }
            src.action == Intent.ACTION_SEND -> listOfNotNull(src.getStringExtra(Intent.EXTRA_SUBJECT), src.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n").takeIf { it.isNotBlank() }?.let { "s" to it }
            else -> null
        } ?: return false
        src.action = Intent.ACTION_MAIN; src.data = null // a later recreate must not replay it
        val (kind, body) = text
        webView.loadUrl(LumenOrigin.HOME + (when (kind) { "a" -> "/?ask="; "p" -> "/?prefill="; "o" -> "/?open="; else -> "/?share=" }) + Uri.encode(body.take(4000)))
        return true
    }

    /** Called up as the assistant while the phone is locked: appear, and ask for the unlock to continue. */
    private fun overLockScreen(source: Intent?) {
        if (!wantsVoice(source) || Build.VERSION.SDK_INT < 27) return
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked != true) return
        setShowWhenLocked(true); setTurnScreenOn(true)
        km.requestDismissKeyguard(this, null)
    }

    override fun onResume() {
        super.onResume()
        WakeService.instance?.pause() // Lumen is on screen and owns the microphone
        // screen control was on and Android switched it off (an update, a force-stop): say so, with the way back
        if (!overlay && getSharedPreferences("lumen", MODE_PRIVATE).getBoolean("controlWanted", false) && LumenAccess.instance == null)
            webView.postDelayed({ if (LumenAccess.instance == null) page("window.__lumenControlLost && window.__lumenControlLost()") }, 3000)
    }

    /** Settings asked to fill in missing permissions: ask again for whatever is not granted. */
    fun askMissing() { getSharedPreferences("lumen", MODE_PRIVATE).edit().remove("askedSet").apply(); askEverythingOnce() }

    /** First run: offer the hands-free ways in, once each. */
    private fun offerWays() {
        val prefs = getSharedPreferences("lumen", MODE_PRIVATE)
        if (!prefs.getBoolean("offeredWake", false) && hasPerm(android.Manifest.permission.RECORD_AUDIO)) {
            prefs.edit().putBoolean("offeredWake", true).apply()
            AlertDialog.Builder(this).setTitle("Turn on \"Hey Lumen\"?")
                .setMessage("Say \"Hey Lumen\" any time to start talking. Listening happens only on this phone, nothing is sent until you say it, and a small notification shows while it is on.")
                .setPositiveButton("Turn on") { _, _ -> WakeService.setEnabled(this, true); offerWays() }
                .setNegativeButton("Not now") { _, _ -> offerWays() }.show()
            return
        }
        if (LumenAccess.instance == null && !prefs.getBoolean("offeredControl", false)) {
            prefs.edit().putBoolean("offeredControl", true).apply()
            AlertDialog.Builder(this).setTitle("Let Lumen use your apps for you?")
                .setMessage("Lumen can then open any app, tap, type and scroll for you, and flip switches like Wi-Fi and Bluetooth. Anything that sends, pays, buys, deletes or posts still waits for your OK. On the next screen tap Installed apps, then Lumen, and switch it on.")
                .setPositiveButton("Open settings") { _, _ -> try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }
                .setNegativeButton("Not now") { _, _ -> offerWays() }.show()
            return
        }
        val held = Build.VERSION.SDK_INT >= 29 && getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        if (!held && !prefs.getBoolean("offeredAssistant", false)) {
            prefs.edit().putBoolean("offeredAssistant", true).apply()
            AlertDialog.Builder(this).setTitle("Make Lumen your assistant?")
                .setMessage("Then holding the side button (or swiping up from a corner) opens Lumen instead of the old assistant, even from the lock screen. On the next screen choose Digital assistant app, then Lumen.")
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (_: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (_: Exception) {} }
                }.setNegativeButton("Not now", null).show()
        }
    }

    /** The browser finished sign-in and sent a one-time code; the page redeems it with the secret only this app holds. */
    private fun handOff(source: Intent?): Boolean {
        val src = source ?: return false
        val data = src.data ?: return false
        if (data.scheme != "ai.lumen.assistant" || data.host != "login") return false
        val code = data.getQueryParameter("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,64}")) } ?: return false
        src.data = null // a later recreate must not replay it
        Telemetry.info("handoff", "sign-in code received")
        webView.loadUrl(LumenOrigin.HOME + "/?handoff=" + code)
        return true
    }

    override fun onPause() {
        if (WakeService.enabled(this)) WakeService.instance?.resume()
        if (::webView.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized && !webViewDestroyed) webView.saveState(outState)
    }

    override fun onDestroy() {
        if (DeviceRelay.bridge === bridge) DeviceRelay.bridge = null
        photoDialog?.setOnCancelListener(null)
        photoDialog?.dismiss()
        finishFileChooser(null)
        pendingWebAudio = null
        popupView?.destroy()
        popupView = null
        destroyWebView()
        super.onDestroy()
    }

    fun isOriginAllowed(): Boolean {
        if (webViewDestroyed || !::webView.isInitialized) return false
        return try {
            val current = webView.url
            if (current.isNullOrBlank()) return pageHost == LumenOrigin.HOST
            val host = Uri.parse(current).host?.lowercase()
            if (host != null) pageHost = host
            pageHost == LumenOrigin.HOST
        } catch (_: Exception) {
            pageHost == LumenOrigin.HOST
        }
    }

    /** First launch: one pass through every runtime permission Lumen uses, so the assistant never stops to ask mid-task. */
    private fun askEverythingOnce() {
        val all = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA,
            android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS, android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) all += android.Manifest.permission.ACTIVITY_RECOGNITION
        if (android.os.Build.VERSION.SDK_INT >= 31) all += android.Manifest.permission.BLUETOOTH_CONNECT
        if (android.os.Build.VERSION.SDK_INT >= 33) all += listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.POST_NOTIFICATIONS)
        // ask again whenever a new version adds permissions, never twice for the same set
        val prefs = getSharedPreferences("lumen", MODE_PRIVATE)
        val set = all.sorted().joinToString(",")
        if (prefs.getString("askedSet", "") == set) return
        prefs.edit().putString("askedSet", set).apply()
        ensurePermissions(all.toTypedArray(), false) { _ ->
            val missing = all.filter { !hasPerm(it) }.map { it.substringAfterLast('.') }
            Telemetry.info("permissions", if (missing.isEmpty()) "all granted" else "not granted: " + missing.joinToString(","))
            offerWays()
        }
    }

    /** A permission turned down twice can no longer be asked for; the app's settings page is the only way back. */
    fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
    }

    fun ensurePermissions(perms: Array<String>, anyOf: Boolean, done: (Boolean) -> Unit) {
        runOnUiThread {
            if (isDestroyed) {
                done(false)
                return@runOnUiThread
            }
            val missing = perms.filter { !hasPerm(it) }
            if (missing.isEmpty() || (anyOf && perms.any { hasPerm(it) })) {
                done(true)
                return@runOnUiThread
            }
            permissionQueue.add(PermReq(missing.toTypedArray(), anyOf, perms, done))
            drainPermissions()
        }
    }

    fun capturePhoto(done: (File?) -> Unit) {
        runOnUiThread {
            camQueue.add(CamReq(done))
            drainCamera()
        }
    }

    fun pickImages(multiple: Boolean, done: (List<Uri>?) -> Unit) {
        runOnUiThread {
            pickQueue.add(PickReq(multiple, done))
            drainPicks()
        }
    }

    private fun hasPerm(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun drainPermissions() {
        if (permissionInFlight) return
        val next = permissionQueue.pollFirst() ?: return
        permissionInFlight = true
        inflightPerm = next
        try {
            permissionLauncher.launch(next.requested)
        } catch (_: Exception) {
            permissionInFlight = false
            inflightPerm = null
            try {
                next.done(false)
            } catch (_: Exception) {
            }
            drainPermissions()
        }
    }

    private fun drainCamera() {
        if (camInFlight != null) return
        val next = camQueue.pollFirst() ?: return
        val dir = File(cacheDir, "captures")
        if (!dir.exists() && !dir.mkdirs()) {
            next.done(null)
            drainCamera()
            return
        }
        val file = File(dir, "cap-${System.currentTimeMillis()}.jpg")
        try {
            if (!file.exists()) file.createNewFile()
        } catch (_: IOException) {
            next.done(null)
            drainCamera()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Exception) {
            file.delete()
            next.done(null)
            drainCamera()
            return
        }
        camInFlight = next to file
        try {
            cameraLauncher.launch(uri)
        } catch (_: Exception) {
            camInFlight = null
            file.delete()
            next.done(null)
            drainCamera()
        }
    }

    private fun drainPicks() {
        if (pickInFlight != null) return
        val next = pickQueue.pollFirst() ?: return
        pickInFlight = next
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()
        try {
            if (next.multiple) multiPicker.launch(request) else singlePicker.launch(request)
        } catch (_: Exception) {
            pickInFlight = null
            next.done(null)
            drainPicks()
        }
    }

    private fun finishPick(uris: List<Uri>?) {
        val req = pickInFlight
        pickInFlight = null
        val result = if (uris.isNullOrEmpty()) null else uris
        try {
            req?.done?.invoke(result)
        } catch (_: Exception) {
        }
        drainPicks()
    }

    private fun applySystemBars() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) disableBarContrast()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(web: WebView) {
        web.setBackgroundColor(Color.parseColor("#07080D"))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            setGeolocationEnabled(true)
            userAgentString = userAgentString + " LumenOne/1.0"
        }
        if (Build.VERSION.SDK_INT >= 29) disableForceDark(web.settings)
        // Lumen keeps working while it drives another app: never let Android freeze or drop its page in the background
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = pageClient
        web.webChromeClient = chromeClient
        val native = LumenBridge(this)
        bridge = native
        if (!overlay) { DeviceRelay.bridge = native; DeviceRelay.start(this) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "LumenNative", setOf(LumenOrigin.HOME), object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                    if (!isMainFrame || !LumenOrigin.allows(sourceOrigin)) return
                    val msg = try { JSONObject(message.data ?: return) } catch (_: Exception) { return }
                    if (msg.optString("type") in setOf("overlay", "busy", "close", "front", "full")) { onPanel(msg); return }
                    if (msg.optString("type") == "caps") { replyProxy.postMessage(JSONObject().put("type", "caps").put("methods", JSONArray(native.capabilities())).toString()); return }
                    native.invoke(msg.optString("id"), msg.optString("method"), msg.optJSONObject("args")?.toString() ?: "{}")
                }
            })
        } else {
            Telemetry.warn("bridge", "WebView too old for the origin-locked bridge; phone features are off")
        }
    }

    private fun noteUrl(url: String?) {
        pageHost = try {
            if (url.isNullOrBlank()) null else Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    private fun wantsVoice(source: Intent?): Boolean {
        if (source == null) return false
        if (source.getBooleanExtra(EXTRA_VOICE, false)) return true
        return source.action == Intent.ACTION_ASSIST || source.action == Intent.ACTION_VOICE_COMMAND
    }

    private fun armVoice(source: Intent?) {
        if (!wantsVoice(source)) return
        voiceFired = false
        if (pageReady && !webViewDestroyed && LumenOrigin.allows(webView.url)) fireVoice() else voicePending = true
    }

    private fun fireVoice() {
        if (voiceFired || webViewDestroyed || !LumenOrigin.allows(webView.url)) return
        voiceFired = true
        voicePending = false
        webView.evaluateJavascript(VOICE_JS, null)
        intent?.removeExtra(EXTRA_VOICE)
    }

    private fun destroyWebView() {
        if (webViewDestroyed || !::webView.isInitialized) return
        webViewDestroyed = true
        bridge?.close()
        bridge = null
        webView.stopLoading()
        webView.webChromeClient = null
        webView.removeJavascriptInterface("LumenNative")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun cleanOldCaptures() {
        val dir = File(cacheDir, "captures")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun finishFileChooser(uris: Array<Uri>?) {
        val callback = fileCallback ?: return
        fileCallback = null
        try {
            callback.onReceiveValue(uris)
        } catch (_: Exception) {
        }
    }

    private fun showFileChooser(multiple: Boolean) {
        photoDialog?.setOnCancelListener(null)
        photoDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.photo_source_title)
            .setItems(
                arrayOf(
                    getString(R.string.photo_source_camera),
                    getString(R.string.photo_source_library)
                )
            ) { _, which ->
                if (which == 0) {
                    ensurePermissions(arrayOf(android.Manifest.permission.CAMERA), false) { granted ->
                        if (!granted) {
                            finishFileChooser(null)
                            return@ensurePermissions
                        }
                        capturePhoto { file ->
                            if (file == null) {
                                finishFileChooser(null)
                                return@capturePhoto
                            }
                            val uri = try {
                                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            } catch (_: Exception) {
                                finishFileChooser(null)
                                return@capturePhoto
                            }
                            try {
                                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Exception) {
                            }
                            finishFileChooser(arrayOf(uri))
                        }
                    }
                } else {
                    pickImages(multiple) { uris ->
                        if (uris.isNullOrEmpty()) finishFileChooser(null)
                        else finishFileChooser(uris.take(6).toTypedArray())
                    }
                }
            }
            .setOnCancelListener { finishFileChooser(null) }
            .create()
        photoDialog = dialog
        dialog.show()
    }

    private val pageClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url ?: return false
            if (LumenOrigin.allows(uri)) return false
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                openInBrowser(this@MainActivity, uri)
                return true
            }
            if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "smsto") {
                startExternal(this@MainActivity, Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            pageReady = false
            noteUrl(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            noteUrl(url)
            pageReady = true
            if (barsCss.isNotEmpty() && LumenOrigin.allows(url)) view?.evaluateJavascript(barsCss, null)
            if (voicePending && LumenOrigin.allows(url)) fireVoice()
            if (!overlay && !updateChecked && LumenOrigin.allows(url)) { updateChecked = true; view?.postDelayed({ Updater.check(this@MainActivity) }, 2500) }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            noteUrl(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) Telemetry.error("load", "${error.errorCode} ${error.description}", JSONObject().put("url", request.url.toString().take(200)))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame || response.statusCode >= 500) Telemetry.warn("http", "${response.statusCode} ${request.url.path}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Telemetry.error("renderer", if (detail.didCrash()) "WebView renderer crashed" else "WebView renderer killed for memory")
            if (view === webView) {
                destroyWebView()
                finish()
            }
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Telemetry.warn("console", message.message(), JSONObject().put("at", "${message.sourceId()?.substringAfterLast('/')}:${message.lineNumber()}"))
            return false
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val host = request.origin?.host
            val wantsAudio = request.resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true
            val wantsVideo = request.resources?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true
            if (!host.equals(LumenOrigin.HOST, ignoreCase = true) || (!wantsAudio && !wantsVideo)) {
                request.deny()
                return
            }
            if (wantsVideo && !wantsAudio) { // live camera ("look"): Lumen's own page only
                ensurePermissions(arrayOf(android.Manifest.permission.CAMERA), false) { granted ->
                    try { if (granted && isOriginAllowed()) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny() } catch (_: Exception) {}
                }
                return
            }
            try {
                pendingWebAudio?.deny()
            } catch (_: Exception) {
            }
            pendingWebAudio = request
            ensurePermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), false) { granted ->
                if (pendingWebAudio !== request) return@ensurePermissions
                pendingWebAudio = null
                try {
                    if (granted && isOriginAllowed()) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        Telemetry.warn("mic", if (!granted) "microphone permission not granted" else "page origin not allowed")
                        request.deny()
                        if (!granted && !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                            AlertDialog.Builder(this@MainActivity)
                                .setMessage("Lumen needs the microphone to talk. Turn it on under Permissions.")
                                .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                                .setNegativeButton("Not now", null).show()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            if (pendingWebAudio === request) pendingWebAudio = null
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            if (callback == null) return
            val host = try {
                if (origin.isNullOrBlank()) null else Uri.parse(origin).host
            } catch (_: Exception) {
                null
            }
            if (!host.equals(LumenOrigin.HOST, ignoreCase = true)) {
                callback.invoke(origin, false, false)
                return
            }
            ensurePermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                true
            ) { granted ->
                val stillHere = try {
                    !origin.isNullOrBlank() &&
                        Uri.parse(origin).host.equals(LumenOrigin.HOST, ignoreCase = true) &&
                        isOriginAllowed()
                } catch (_: Exception) {
                    false
                }
                try {
                    callback.invoke(origin, granted && stillHere, false)
                } catch (_: Exception) {
                }
            }
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            finishFileChooser(null)
            if (!isOriginAllowed()) {
                try {
                    filePathCallback?.onReceiveValue(null)
                } catch (_: Exception) {
                }
                return true
            }
            fileCallback = filePathCallback
            val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            try {
                showFileChooser(multiple)
            } catch (_: Exception) {
                finishFileChooser(null)
            }
            return true
        }

        // A false return drops the popup before the target URL exists.
        // Known link URLs open outside and return false. window.open needs a temporary
        // WebView so Chromium can hand over the URL; that path returns true, then leaves.
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val hint = view?.hitTestResult?.extra
            if (!hint.isNullOrBlank()) {
                val lower = hint.lowercase()
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    val uri = Uri.parse(hint)
                    if (LumenOrigin.allows(uri)) view?.loadUrl(hint) else openInBrowser(this@MainActivity, uri)
                    return false
                }
            }
            val message = resultMsg ?: return false
            val transport = message.obj as? WebView.WebViewTransport ?: return false
            popupView?.destroy()
            val temp = WebView(this@MainActivity)
            temp.setBackgroundColor(Color.parseColor("#07080D"))
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popup: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri?.scheme?.lowercase()
                    if (scheme == "about") return false
                    if (uri != null && LumenOrigin.allows(uri)) {
                        if (!webViewDestroyed) webView.loadUrl(uri.toString())
                    } else if ((scheme == "http" || scheme == "https") && uri != null) {
                        openInBrowser(this@MainActivity, uri)
                    }
                    popup.post {
                        if (popupView === popup) popupView = null
                        popup.destroy()
                    }
                    return true
                }
            }
            popupView = temp
            transport.webView = temp
            message.sendToTarget()
            return true
        }
    }

    @RequiresApi(29)
    private fun disableBarContrast() {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    @RequiresApi(29)
    private fun disableForceDark(settings: WebSettings) {
        @Suppress("DEPRECATION")
        settings.forceDark = WebSettings.FORCE_DARK_OFF
    }

    companion object {
        const val EXTRA_VOICE = "voice"
        private const val VOICE_JS = "window.__lumenVoice && window.__lumenVoice()"
    }
}
