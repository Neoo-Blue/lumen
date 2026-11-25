package ai.lumen.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lumen's hands on the phone: read any screen, tap, type, scroll, press Back or Home, flip Quick Settings tiles.
 * The person turns it on once under Accessibility. A tap on a final commit (send, pay, buy, delete, post)
 * comes back as "needs confirmation" until the person has confirmed it on screen.
 * Window events stay off until a screen action or a lesson is running, then a settle() wait replaces fixed sleeps.
 */
class LumenAccess : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var last: List<AccessibilityNodeInfo> = emptyList()
    val sheet by lazy { Sheet(this) }
    private val gate = Any()
    @Volatile private var uses = 0
    private val settleLock = java.lang.Object()
    @Volatile private var lastChange = 0L
    private var needEventAfter = 0L
    // buttons the person already approved on a reviewed plan (one confirmation for a whole chain), for a few minutes
    @Volatile private var approved: Pair<Set<String>, Long> = emptySet<String>() to 0L
    fun approve(labels: org.json.JSONArray?) { val s = (0 until (labels?.length() ?: 0)).map { labels!!.optString(it).lowercase().trim() }.filter { it.length >= 3 }.take(6).toSet(); if (s.isNotEmpty()) approved = s to System.currentTimeMillis() + 180_000 }
    private fun preApproved(what: String): Boolean { val (s, until) = approved; val w = what.lowercase(); return System.currentTimeMillis() < until && s.any { Regex("(^|[^\\p{L}])" + Regex.escape(it) + "($|[^\\p{L}])").containsMatchIn(w) } }
    fun appName(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    override fun onServiceConnected() { Telemetry.init(applicationContext); getSharedPreferences("lumen", MODE_PRIVATE).edit().putBoolean("controlWanted", true).apply(); instance = this; applyEvents(); DeviceRelay.start(applicationContext); Telemetry.info("control", "screen control on") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) && event.packageName?.toString() != packageName) {
            synchronized(settleLock) { lastChange = System.currentTimeMillis(); settleLock.notifyAll() }
        }
        if (teaching) { teachSeen++; if (uses == 0) teachOn(event) else teachBusy++ }
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        main.removeCallbacks(teachEnd)
        teaching = false
        hideTeachUi(); dropTeachRx()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Turn window events on for one screen action or a whole skill. Nested calls stay on until the last one ends. */
    fun beginWatch() { synchronized(gate) { uses++; applyLocked() } }
    fun endWatch() { synchronized(gate) { uses = (uses - 1).coerceAtLeast(0); applyLocked() } }

    /** Mark the moment just before an action. The next settle() waits for a window event after this. */
    fun arm() {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        synchronized(settleLock) { needEventAfter = System.currentTimeMillis() }
    }
    fun disarm() { synchronized(settleLock) { needEventAfter = 0L } }

    /**
     * Wait until a window event has landed and the screen has then been quiet for quietMs, or until maxMs.
     * Armed (call arm() first): the event must be newer than the mark, so the wait is for this action.
     * Unarmed: if the screen is already quiet, return at once. Never call this on the main thread.
     */
    fun settle(maxMs: Long, quietMs: Long = 250) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val since = synchronized(settleLock) { val s = needEventAfter; needEventAfter = 0L; s }
        waitUntil(since, maxMs, quietMs)
    }

    private fun waitUntil(since: Long, maxMs: Long, quietMs: Long) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val deadline = System.currentTimeMillis() + maxMs
        synchronized(settleLock) {
            while (true) {
                val now = System.currentTimeMillis()
                val seen = since > 0 && lastChange >= since
                val quiet = lastChange > 0L && now - lastChange >= quietMs
                if (since > 0) { if (seen && quiet) return } else if (quiet) return
                if (now >= deadline) return
                val target = if (seen || (since == 0L && lastChange > 0L)) lastChange + quietMs else now + 80
                val nap = (minOf(target, deadline) - System.currentTimeMillis()).coerceIn(20, 300)
                try { settleLock.wait(nap) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            }
        }
    }

    private fun applyEvents() { synchronized(gate) { applyLocked() } }
    private fun applyLocked() {
        val info = try { serviceInfo } catch (_: Exception) { null } ?: return
        var types = 0
        if (uses > 0) types = types or EV_SETTLE
        if (teaching) types = types or EV_TEACH
        val timeout = if (types == 0) 200L else 50L
        if (info.eventTypes == types && info.notificationTimeout == timeout) return
        info.eventTypes = types
        info.notificationTimeout = timeout
        try { serviceInfo = info } catch (_: Exception) {}
    }

    /** The app the person is looking at (never Lumen's own panel, never edge panels or the keyboard). The shade and
     *  other system windows only when flipping Quick Settings switches. */
    private fun roots(system: Boolean = false): List<AccessibilityNodeInfo> {
        val ws = try { windows } catch (_: Exception) { emptyList() }
        if (system) return ws.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }.sortedByDescending { it.layer }.mapNotNull { it.root }
        val apps = ws.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.root?.let { w to it } }.filter { it.second.packageName != packageName }.sortedByDescending { it.first.layer }
        val front = apps.firstOrNull { it.first.isActive || it.first.isFocused } ?: apps.firstOrNull()
        return listOfNotNull(front?.second ?: rootInActiveWindow?.takeIf { it.packageName != packageName })
    }

    private fun label(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text, n.contentDescription, if (Build.VERSION.SDK_INT >= 26) n.hintText else null).joinToString(" ").trim().take(120)

    /** What is on screen now, as a numbered list the assistant can act on. */
    private val readLock = Any()
    fun read(system: Boolean = false): JSONObject = synchronized(readLock) { readNow(system) }
    private fun readNow(system: Boolean): JSONObject {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val items = JSONArray()
        var app = ""
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || items.length() >= 150) return
            if (!n.isVisibleToUser) return
            if (app.isEmpty() && n.packageName != null) app = n.packageName.toString()
            val text = label(n)
            val useful = text.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (useful) {
                val o = JSONObject().put("i", nodes.size).put("text", text).put("kind", n.className?.toString()?.substringAfterLast('.') ?: "")
                if (n.isClickable) o.put("tap", true)
                if (n.isEditable) o.put("edit", true)
                if (n.isScrollable) o.put("scroll", true)
                if (n.isCheckable) o.put("on", n.isChecked)
                n.viewIdResourceName?.substringAfter(":id/")?.let { o.put("id", it) }
                if (n.isPassword) o.put("text", "(password field)")
                items.put(o); nodes.add(n)
            }
            for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
        }
        roots(system).forEach { walk(it, 0) }
        last = nodes
        if (!system && nodes.isEmpty()) return JSONObject().put("app", "lumen").put("items", items).put("note", "Lumen itself is on screen and screen control is working: open the app or page first (openApp or openUrl), then read it")
        return JSONObject().put("app", app).put("items", items)
    }

    private fun find(target: JSONObject): AccessibilityNodeInfo? {
        if (target.has("i")) return last.getOrNull(target.optInt("i", -1))
        val want = target.optString("text").lowercase().trim()
        if (want.isEmpty()) return null
        read()
        fun idHit(n: AccessibilityNodeInfo): Boolean {
            val tail = n.viewIdResourceName?.substringAfter(":id/")?.trim()?.lowercase().orEmpty()
            if (tail.isEmpty()) return false
            return tail == want || tail.substringAfterLast("/") == want
        }
        return last.firstOrNull { label(it).lowercase() == want }
            ?: last.firstOrNull { idHit(it) }
            ?: last.firstOrNull { label(it).lowercase().contains(want) }
    }

    /** Inside a web page (a browser or a web view): pages listen for real touches and keystrokes, not accessibility clicks. */
    private fun isWeb(n: AccessibilityNodeInfo): Boolean {
        if (n.packageName?.toString() in BROWSERS) return true
        var p: AccessibilityNodeInfo? = n; var d = 0
        while (p != null && d < 40) { if (p.className?.toString() == "android.webkit.WebView") return true; p = p.parent; d++ }
        return false
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? { var p: AccessibilityNodeInfo? = n; while (p != null && !p.isClickable) p = p.parent; return p }

    /** Not on screen: scroll the main list up, then down, looking for it (lists only expose what is visible). */
    private fun seek(args: JSONObject): AccessibilityNodeInfo? {
        find(args)?.let { return it }
        if (!args.has("text")) return null
        val words = { last.joinToString("|") { label(it) } }
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            var mover: AccessibilityNodeInfo? = null // the layer that actually moves the content (apps nest several)
            for (step in 0 until 10) {
                val before = words()
                val scrollers = scrollers() // node references stay valid across re-reads
                var moved = false
                for (node in (mover?.let { listOf(it) } ?: scrollers)) {
                    arm()
                    if (!node.performAction(action)) { disarm(); continue }
                    settle(1500, 200)
                    find(args)?.let { return it }
                    if (words() != before) { mover = node; moved = true; break }
                }
                if (!moved) { // some apps ignore scroll commands: swipe like a finger instead
                    val big = scrollers.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.height() * r.width() }
                    if (big != null) {
                        arm()
                        val swiped = swipe(big, action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        if (swiped) settle(1500, 200) else disarm()
                        if (swiped) {
                            find(args)?.let { return it }
                            moved = words() != before
                        }
                    }
                }
                if (!moved) break // at the end in this direction
            }
        }
        return null
    }

    /** Taps something. A request can never say it was already confirmed: risky taps are confirmed here, on the overlay. */
    fun tap(args: JSONObject): JSONObject { beginWatch(); try { return tapAt(args, false) } finally { endWatch() } }

    private fun tapAt(args: JSONObject, confirmedHere: Boolean): JSONObject {
        val n = seek(args) ?: return err("nothing on screen matches")
        val cl = clickable(n)
        // judge by everything the tap reaches: the item and the button around it ("$500" inside "Pay $500")
        val what = listOf(label(n), cl?.let { label(it) } ?: "").filter { it.isNotEmpty() }.distinct().joinToString(" \u00b7 ").take(120)
        val guarded = n.packageName?.toString() in GUARDED_APPS
        val risky = RISKY.containsMatchIn(what) || confirmRisky(what)
        if (!confirmedHere && (guarded || risky) && (guarded || !preApproved(what))) {
            // ask right on top of the page: a big Lumen confirmation, no switching apps
            val app0 = n.packageName?.toString() ?: ""
            val res = sheet.ask("confirm", JSONObject().put("kicker", "Your OK, please").put("title", "Tap \u201c" + what.take(40) + "\u201d?")
                .put("detail", "Lumen is about to do this in " + appName(app0) + ". Nothing happens unless you confirm.").put("site", appName(app0))
                .put("confirm", "Yes, " + what.take(24).lowercase()).put("danger", true), 5)
            if (res != null) {
                if (!res.optBoolean("ok")) return err("the person said not now to \"$what\"")
                Telemetry.info("confirm", "confirmed on the overlay: $what")
                return tapAt(args, true)
            }
            return err("this tap needs your OK, and the confirmation could not be shown")
        }
        val c = cl
        arm()
        val done = if (isWeb(n)) {
            if (gestureTap(n)) true else { arm(); c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        } else {
            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) true else gestureTap(n)
        }
        if (!done) { disarm(); return err("could not tap") }
        settle(1500, 200)
        return JSONObject().put("ok", true).put("data", JSONObject().put("tapped", what).put("screen", view()))
    }

    /**
     * "Confirm" is treated as a commit (asks the person) unless the screen is plainly a picker: a date, a time, a
     * language, a filter. Anything about money, sending, deleting or an account always asks.
     */
    private fun confirmRisky(what: String): Boolean {
        if (!CONFIRM.containsMatchIn(what)) return false
        val screen = screenText() + " " + what
        if (CONFIRM_CTX.containsMatchIn(screen) || MONEY.containsMatchIn(screen)) return true
        return !PICKER.containsMatchIn(screen)
    }

    /** Scrollable layers, the real list first: tall ones, innermost before the frames that only collapse a header. */
    private fun scrollers(): List<AccessibilityNodeInfo> {
        val screen = resources.displayMetrics.heightPixels
        fun depth(n: AccessibilityNodeInfo): Int { var d = 0; var p = n.parent; while (p != null && d < 60) { d++; p = p.parent }; return d }
        val all = last.filter { it.isScrollable }
        val tall = all.filter { val r = Rect(); it.getBoundsInScreen(r); r.height() > screen * 0.35 }
        return tall.sortedByDescending { depth(it) } + (all - tall.toSet())
    }

    /** A finger swipe over a list: down reveals what is above, up what is below. */
    private fun swipe(n: AccessibilityNodeInfo, towardTop: Boolean): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.height() < 200) return false
        val x = r.exactCenterX(); val a = r.top + r.height() * 0.28f; val b = r.top + r.height() * 0.78f
        val path = Path().apply { if (towardTop) { moveTo(x, a); lineTo(x, b) } else { moveTo(x, b); lineTo(x, a) } }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 280)).build(), null, null)
    }

    private fun gestureTap(n: AccessibilityNodeInfo): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (r.isEmpty || !screen.contains(r.centerX(), r.centerY())) { // bring it into view first
            val since = System.currentTimeMillis()
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (Looper.myLooper() != Looper.getMainLooper()) waitUntil(since, 1500, 200) // pages scroll smoothly; touch once it has stopped
            n.refresh(); n.getBoundsInScreen(r)
        }
        if (r.isEmpty) return false
        arm()
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    /** True when no other app's window is in front (Lumen is what the person sees). */
    fun frontIsLumen(): Boolean = roots().isEmpty()

    /** The screen as one compact line per item: "i:text [tap,edit,on]" - small enough to return after every action. */
    fun view(): String {
        read()
        val b = StringBuilder()
        if (last.isEmpty()) return "(Lumen itself is on screen: open the app or page first)"
        last.forEachIndexed { i, n ->
            val t = label(n); val f = listOfNotNull(if (n.isClickable) "tap" else null, if (n.isEditable) "edit" else null, if (n.isCheckable) (if (n.isChecked) "on" else "off") else null, if (n.isScrollable) "scroll" else null)
            if (t.isNotEmpty() || f.isNotEmpty()) b.append(i).append(':').append(if (n.isPassword) "(password field)" else t.take(70)).append(if (f.isEmpty()) "" else " [" + f.joinToString(",") + "]").append('\n')
        }
        return b.toString().take(3500)
    }

    /** The screen as a picture for Lumen to look at (maps, photos, games, video: what the reader cannot read).
     *  Password fields are painted over before the picture leaves the phone. */
    fun screenshot(): JSONObject {
        if (Build.VERSION.SDK_INT < 30) return err("needs Android 11 or newer")
        val secret = ArrayList<Rect>(); var seen = 0
        fun hunt(x: AccessibilityNodeInfo?) { if (x == null || seen++ > 6000) return; if (x.isPassword) { val r = Rect(); x.getBoundsInScreen(r); secret.add(r) }; for (k in 0 until x.childCount) hunt(x.getChild(k)) }
        (try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }).forEach { hunt(it) }
        read()
        // Android allows about one screenshot a second: space them out, and try once more if it still says too soon
        val wait = 1100 - (System.currentTimeMillis() - lastShot); if (wait > 0) Thread.sleep(wait)
        val first = shoot(secret); lastShot = System.currentTimeMillis()
        if (first.optString("error").contains("(3)")) { Thread.sleep(1100); return shoot(secret).also { lastShot = System.currentTimeMillis() } }
        return first
    }
    @Volatile private var lastShot = 0L
    private fun shoot(secret: List<Rect>): JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1); var out: JSONObject = err("could not take the screenshot")
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    shot.hardwareBuffer.close()
                    if (bmp != null) {
                        val canvas = android.graphics.Canvas(bmp); val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                        secret.forEach { canvas.drawRect(it, paint) }
                        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                        val buf = java.io.ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, buf)
                        out = JSONObject().put("ok", true).put("data", JSONObject().put("image", "data:image/jpeg;base64," + android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)).put("app", last.firstOrNull()?.packageName ?: ""))
                    }
                } catch (e: Exception) { out = err("screenshot failed: ${e.message}") }
                latch.countDown()
            }
            override fun onFailure(code: Int) { out = err("screenshot refused ($code)"); latch.countDown() }
        })
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun screenText(): String = last.joinToString(" ") { label(it) }

    fun type(args: JSONObject): JSONObject { beginWatch(); try { return typeNow(args) } finally { endWatch() } }
    private fun typeNow(args: JSONObject): JSONObject {
        if (args.optString("text").contains('\n') || args.optString("text").contains('\r')) args.put("text", args.optString("text").replace(Regex("[\\r\\n]+$"), "")) // Enter would send in chat apps: sending is a tap Lumen asks about
        val into = args.optJSONObject("into") ?: JSONObject()
        // "field" is the recorded fallback when a box had no name. Type into the focused box, or the first editable one.
        val generic = into.optString("text") == "field" && !into.has("i")
        // a label matched (e.g. "Depart*"): use the input box that goes with it, the next editable item on screen
        val hit = if (into.length() > 0 && !generic) find(into) else null
        if (into.length() > 0 && !generic && hit == null) return err("no box labeled \"${into.optString("text", into.toString()).take(60)}\" on screen: read the screen and aim again") // never type into whatever happens to have focus
        val n0 = hit?.let { h -> if (h.isEditable) h else { val want = label(h).trimEnd('*').trim(); val i = last.indexOf(h)
            last.firstOrNull { it.isEditable && want.length >= 3 && label(it).contains(want, true) } ?: last.drop(i + 1).firstOrNull { it.isEditable } ?: h } }
        val n = n0 ?: run { read(); last.firstOrNull { it.isFocused && it.isEditable } ?: last.firstOrNull { it.isEditable } }
            ?: return err("no text field on screen")
        var ok = !isWeb(n) && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.optString("text")) })
        if (!ok && Build.VERSION.SDK_INT >= 33) { // type as the keyboard: pages see real input, like a person typing
            arm()
            if (isWeb(n)) gestureTap(n) else (clickable(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle(1500, 200)
            val ic = try { inputMethod?.currentInputConnection } catch (_: Exception) { null }
            if (ic != null) {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText(args.optString("text"), 1, null)
                ok = true
            }
        }
        if (!ok) { // some web fields refuse direct text: focus them, then paste like a keyboard would
            arm()
            if (isWeb(n)) gestureTap(n) else (clickable(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle(1500, 200)
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            val before = try { clip.primaryClip } catch (_: Exception) { null }
            val cd = android.content.ClipData.newPlainText("lumen", args.optString("text"))
            if (Build.VERSION.SDK_INT >= 33) cd.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
            try {
            clip.setPrimaryClip(cd)
            val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: n
            arm()
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            settle(1500, 200)
            } finally { try { if (before != null) clip.setPrimaryClip(before) else clip.clearPrimaryClip() } catch (_: Exception) {} }
        }
        // trust what the field shows, not the return value: read the focused box back
        if (!ok) {
            val want = args.optString("text").trim()
            val f = try { roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } } catch (_: Exception) { null }
            ok = want.isNotEmpty() && (f?.text?.toString()?.contains(want, true) == true || n.also { it.refresh() }.text?.toString()?.contains(want, true) == true)
        }
        return if (ok) JSONObject().put("ok", true).put("data", JSONObject().put("typed", true).put("screen", view())) else err("could not type there")
    }

    fun scroll(args: JSONObject): JSONObject { beginWatch(); try { return scrollNow(args) } finally { endWatch() } }
    private fun scrollNow(args: JSONObject): JSONObject {
        read()
        val n = (if (args.has("i")) last.getOrNull(args.optInt("i")) else null) ?: scrollers().firstOrNull() ?: return err("nothing scrolls here")
        val up = args.optString("direction") == "up"
        val before = last.joinToString("|") { label(it) }
        arm()
        n.performAction(if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        settle(1500, 200); read()
        var ok = last.joinToString("|") { label(it) } != before
        if (!ok) {
            arm()
            ok = swipe(n, up)
            if (ok) settle(1500, 200) else disarm()
        }
        return if (ok) JSONObject().put("ok", true).put("data", JSONObject().put("scrolled", true).put("screen", view())) else err("could not scroll")
    }

    fun press(key: String): JSONObject {
        val a = when (key) {
            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quickSettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return err("not on this phone")
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return err("not on this phone")
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return err("unknown key")
        }
        return if (performGlobalAction(a)) JSONObject().put("ok", true).put("data", JSONObject().put("pressed", key)) else err("could not press $key")
    }

    /** Flip a Quick Settings tile by its name (Wi-Fi, Bluetooth, Airplane mode, Hotspot, Location, Dark mode...). */
    fun toggle(name: String, done: (JSONObject) -> Unit) = toggle(name, null, done)

    /** want = true/false sets that state (leaves it alone when it already is); null flips it. */
    fun toggle(name: String, wanted: Boolean?, done: (JSONObject) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        val want = name.lowercase()
        val t0 = System.currentTimeMillis()
        // act the moment the tile shows up instead of waiting a fixed time for the shade animation
        fun attempt() {
            read(system = true)
            val tile = last.firstOrNull { label(it).lowercase().startsWith(want) } ?: last.firstOrNull { label(it).lowercase().contains(want) }
            if (tile == null && System.currentTimeMillis() - t0 < 1500) { main.postDelayed({ attempt() }, 120); return }
            val target = tile?.let { clickable(it) ?: it }
            val before = target?.let { label(it) } ?: ""
            val isOn: Boolean? = when {
                target == null -> null
                target.isCheckable -> target.isChecked
                Regex("\\b(on|enabled)\\b", RegexOption.IGNORE_CASE).containsMatchIn(before.substringAfter(',')) -> true
                Regex("\\b(off|disabled)\\b", RegexOption.IGNORE_CASE).containsMatchIn(before.substringAfter(',')) -> false
                else -> null
            }
            if (tile != null && wanted != null && isOn == wanted) { closeShade(); done(JSONObject().put("ok", true).put("data", JSONObject().put("already", if (wanted) "on" else "off").put("name", name))); return }
            val ok = tile != null && (clickable(tile)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || gestureTap(tile))
            main.postDelayed({
                closeShade()
                done(if (ok) JSONObject().put("ok", true).put("data", JSONObject().put("toggled", before.ifEmpty { name })) else err("no \"$name\" switch in Quick Settings"))
            }, 250)
        }
        main.postDelayed({ attempt() }, 150)
    }

    /** Samsung's shade ignores Back; Android 12+ has a proper close. */
    private fun closeShade() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else { performGlobalAction(GLOBAL_ACTION_BACK); performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    // Teaching: record taps, typing, scrolls and app changes, then save them as a skill.
    @Volatile private var teaching = false
    @Volatile private var teachSeen = 0
    @Volatile private var teachBusy = 0
    private val teachLock = Any()
    private var teachName = ""
    private var teachApp = ""
    private var teachPkg = ""
    private var teachSteps = JSONArray()
    private var teachParams = JSONArray()
    private val fieldParam = HashMap<String, String>()
    private var paramN = 0
    private var lastTap = ""
    private var lastTapAt = 0L
    private var lastScrollY = -1
    private var lastScrollAt = 0L
    private var lastTeach: JSONObject? = null
    private var teachRxOn = false
    private val teachEnd = Runnable { Thread { teachStop() }.start() }
    private val teachRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_TEACH_STOP) Thread { teachStop() }.start()
        }
    }
    private val homes: Set<String> by lazy {
        val known = setOf(
            "com.sec.android.app.launcher", "com.samsung.android.app.launcher",
            "com.google.android.apps.nexuslauncher", "com.android.launcher", "com.android.launcher3",
            "com.teslacoilsw.launcher", "com.microsoft.launcher", "com.miui.home", "com.huawei.android.launcher",
            "com.oppo.launcher", "com.coloros.launcher", "com.bbk.launcher2", "com.oneplus.launcher", "com.nothing.launcher"
        )
        val found = try {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapNotNull { it.activityInfo?.packageName }
        } catch (_: Exception) { emptyList() }
        known + found - setOf("com.android.settings") // Settings holds a fallback home screen, but it is an app people use
    }
    private val skipPkgs = setOf(
        "com.android.systemui", "android",
        "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey", "com.syntellia.fleksy.keyboard", "com.baidu.input", "com.sohu.inputmethod.sogou"
    )

    /** Start recording. Stops itself after 3 minutes, or when teachStop runs. */
    fun teachStart(name: String): JSONObject {
        val n = name.trim().replace(Regex("\\s+"), " ").take(60)
        if (n.isEmpty()) return err("a skill needs a name")
        synchronized(teachLock) {
            if (teaching) return err("already recording")
            teachName = n
            teachSteps = JSONArray()
            teachParams = JSONArray()
            fieldParam.clear()
            paramN = 0
            lastTap = ""
            lastTapAt = 0L
            lastScrollY = -1
            lastScrollAt = 0L
            lastTeach = null
            teachPkg = currentPkg()
            teachApp = if (recordable(teachPkg)) teachPkg else ""
            // Replay has to open the app the lesson started on, or the later taps land on whatever is in front.
            if (teachApp.isNotEmpty()) teachSteps.put(JSONObject().put("open", JSONObject().put("pkg", teachPkg)))
            teaching = true; teachSeen = 0; teachBusy = 0
        }
        applyEvents()
        val via = if (sheet.watchTeach { Thread { teachStop() }.start() }) "overlay" else if (showTeachNote()) "notification" else "none"
        main.removeCallbacks(teachEnd)
        main.postDelayed(teachEnd, 180_000)
        Telemetry.info("teach", "recording: $n")
        return JSONObject().put("ok", true).put("data", JSONObject().put("recording", n).put("via", via))
    }

    /** Stop recording and save the skill. A second call returns the same result. */
    fun teachStop(): JSONObject {
        val snap = synchronized(teachLock) {
            if (!teaching) return lastTeach ?: err("not recording")
            teaching = false
            JSONObject().put("name", teachName).put("app", teachApp)
                .put("steps", JSONArray(teachSteps.toString())).put("params", JSONArray(teachParams.toString()))
        }
        main.removeCallbacks(teachEnd)
        applyEvents()
        hideTeachUi()
        dropTeachRx()
        val steps = snap.getJSONArray("steps")
        val result = if (steps.length() == 0) err("nothing was captured (saw $teachSeen screen events, $teachBusy while Lumen was busy)") else {
            val saved = Skills.save(this, JSONObject()
                .put("name", snap.getString("name"))
                .put("when", "taught by you")
                .put("app", snap.optString("app"))
                .put("params", snap.getJSONArray("params"))
                .put("steps", steps)
                .put("examples", JSONArray().put(snap.getString("name"))))
            if (saved.optBoolean("ok")) JSONObject().put("ok", true).put("data", snap) else saved
        }
        synchronized(teachLock) { lastTeach = result }
        if (result.optBoolean("ok")) Telemetry.info("teach", "saved: ${snap.optString("name")} (${steps.length()} steps)")
        return result
    }

    private fun teachOn(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType
        val src = if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) event.source else null
        try {
            synchronized(teachLock) {
                if (!teaching || stepsFull()) return
                if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) notePkg(pkg)
                if (!recordable(pkg)) return
                when (type) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClick(pkg, src, event)
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordType(pkg, src, event)
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordScroll(pkg, src, event)
                }
            }
        } finally { try { src?.recycle() } catch (_: Exception) {} }
    }

    private fun stepsFull() = teachSteps.length() >= 40

    private fun notePkg(pkg: String) {
        if (!recordable(pkg) || pkg == teachPkg || stepsFull()) return
        teachPkg = pkg
        if (teachApp.isEmpty()) teachApp = pkg
        teachSteps.put(JSONObject().put("open", JSONObject().put("pkg", pkg)))
    }

    private fun recordClick(pkg: String, src: AccessibilityNodeInfo?, event: AccessibilityEvent) {
        notePkg(pkg)
        if (src?.isPassword == true || event.isPassword || stepsFull()) return
        // the tapped view, or when it is already gone, the words the click event carries
        val label = (if (src != null) clickLabel(src) else "").ifEmpty {
            (event.text?.joinToString(" ") { it.toString() }?.trim().orEmpty().ifEmpty { event.contentDescription?.toString()?.trim().orEmpty() }).replace(Regex("\\s+"), " ").take(80) }
        if (label.isEmpty()) return
        val now = System.currentTimeMillis()
        if (label == lastTap && now - lastTapAt < 280) return
        lastTap = label
        lastTapAt = now
        teachSteps.put(JSONObject().put("tap", label))
    }

    private fun recordType(pkg: String, src: AccessibilityNodeInfo?, event: AccessibilityEvent) {
        notePkg(pkg)
        if (src == null || src.isPassword || event.isPassword || stepsFull()) return
        val typed = event.text?.firstOrNull()?.toString()?.trim().orEmpty().ifEmpty { src.text?.toString()?.trim().orEmpty() }
        if (typed.isEmpty()) return
        val label = fieldLabel(src)
        val hint = if (Build.VERSION.SDK_INT >= 26) src.hintText?.toString()?.trim().orEmpty() else ""
        if (hint.isNotEmpty() && typed == hint) return
        val key = src.viewIdResourceName ?: run { val r = Rect(); src.getBoundsInScreen(r); label + "@" + r.left + "," + r.top }
        if (fieldParam.containsKey(key)) return
        paramN += 1
        val p = "text$paramN"
        fieldParam[key] = p
        teachParams.put(p)
        teachSteps.put(JSONObject().put("type", JSONObject().put("into", JSONObject().put("text", label)).put("text", "{$p}")))
    }

    private fun recordScroll(pkg: String, src: AccessibilityNodeInfo?, event: AccessibilityEvent) {
        notePkg(pkg)
        if (src?.isPassword == true || src?.isEditable == true || event.isPassword || stepsFull()) return
        val dir: String? = if (Build.VERSION.SDK_INT >= 28 && (event.scrollDeltaY != 0 || event.scrollDeltaX != 0)) {
            when {
                event.scrollDeltaY > 0 -> "down"
                event.scrollDeltaY < 0 -> "up"
                else -> null
            }
        } else {
            val y = event.scrollY
            val prev = lastScrollY
            lastScrollY = y
            if (prev >= 0 && y != prev) { if (y > prev) "down" else "up" } else null
        }
        if (dir == null) return
        val now = System.currentTimeMillis()
        val last = teachSteps.optJSONObject(teachSteps.length() - 1)
        if (last != null && last.optString("scroll") == dir && now - lastScrollAt < 500) { lastScrollAt = now; return }
        lastScrollAt = now
        teachSteps.put(JSONObject().put("scroll", dir))
    }

    private fun clickLabel(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val id = n.viewIdResourceName?.substringAfter(":id/")?.substringAfterLast("/")?.trim().orEmpty()
        val inner = if (t.isEmpty() && d.isEmpty()) firstText(n, 3) else "" // a list row: its words are in a child view
        return (if (t.isNotEmpty()) t else if (d.isNotEmpty()) d else if (inner.isNotEmpty()) inner else id).replace(Regex("\\s+"), " ").take(80)
    }

    private fun firstText(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth <= 0) return ""
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try {
                val t = c.text?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty()) return t
                val deeper = firstText(c, depth - 1); if (deeper.isNotEmpty()) return deeper
            } finally { try { c.recycle() } catch (_: Exception) {} }
        }
        return ""
    }

    private fun fieldLabel(n: AccessibilityNodeInfo): String {
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        if (hint.isNotEmpty()) return hint.take(60)
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d.take(60)
        val id = n.viewIdResourceName?.substringAfter(":id/")?.substringAfterLast("/")?.trim().orEmpty()
        if (id.isNotEmpty()) return id.take(60)
        return "field"
    }

    private fun recordable(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName || pkg in homes || pkg in GUARDED_APPS || pkg in skipPkgs) return false
        return true
    }

    private fun currentPkg(): String = try {
        roots().firstOrNull()?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: ""
    } catch (_: Exception) { "" }

    private fun hideTeachUi() {
        try { sheet.hideTeach() } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(TEACH_NOTE) } catch (_: Exception) {}
    }

    private fun showTeachNote(): Boolean {
        return try {
            ensureTeachRx()
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("teach", "Teaching", NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
            val stop = PendingIntent.getBroadcast(this, 8, Intent(ACTION_TEACH_STOP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "teach") else @Suppress("DEPRECATION") Notification.Builder(this)
            nm.notify(TEACH_NOTE, b.setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Lumen is watching")
                .setContentText("Tap Stop when done").setOngoing(true).setContentIntent(stop)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
            true
        } catch (_: Exception) { false }
    }

    private fun ensureTeachRx() {
        if (teachRxOn) return
        val filter = IntentFilter(ACTION_TEACH_STOP)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(teachRx, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(teachRx, filter)
        teachRxOn = true
    }

    private fun dropTeachRx() {
        if (!teachRxOn) return
        try { unregisterReceiver(teachRx) } catch (_: Exception) {}
        teachRxOn = false
    }

    companion object {
        @Volatile var instance: LumenAccess? = null
        private val BROWSERS = setOf("com.sec.android.app.sbrowser", "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.duckduckgo.mobile.android", "com.google.android.googlequicksearchbox")
        // Final commits only. "sign" is a document, not sign in / sign up / sign out. Bare "confirm" is handled in confirmRisky.
        val RISKY = Regex("\\b(unlock|frunk|trunk|summon|vent|send|pay|buy|purchase|place order|order|checkout|check out|delete|remove|transfer|post|publish|reply|subscribe|confirm and pay|submit|book|unsubscribe|uninstall|install|reset|erase|call|withdraw|sell|trade|swap|approve|authorize|authorise|donate|wire|forward|wipe|format)\\b|\\bsign\\b(?![\\s-]*(?:in|up|out)\\b)|发送|支付|付款|购买|删除|确认|转账|下单|提交|傳送|付款|購買|刪除|確認|送信|支払|購入|削除|보내기|결제|구매|삭제|enviar|pagar|comprar|eliminar", RegexOption.IGNORE_CASE)
        private val CONFIRM = Regex("\\bconfirm\\b", RegexOption.IGNORE_CASE)
        private val CONFIRM_CTX = Regex("\\b(pay\\w*|order\\w*|purchas\\w*|transfer\\w*|book\\w*|delet\\w*|remov\\w*|send\\w*|sent|message\\w*|account\\w*|subscri\\w*|cancel\\w*|charg\\w*|refund\\w*|post\\w*|publish\\w*|share\\w*|reply|replies)\\b|\\d[\\d,.]*\\s?(?:[A-Z]{3}|元|円|원)\\b|[¥￥]", RegexOption.IGNORE_CASE)
        // a screen that is only choosing something: a date or time picker, a language, a filter
        private val PICKER = Regex("\\b(select (?:a )?(?:date|time)|set (?:date|time)|pick (?:a )?(?:date|time)|date picker|time picker|calendar|AM|PM|hours?|minutes?|language|filter|sort by|timezone|time zone)\\b|\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}\\b", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("(?:[\$€£¥₹₩₺₽]|usd|eur|gbp|dollars?|euros?)\\s?\\d|\\b\\d[\\d.,]*\\s?(?:usd|eur|gbp|dollars?|euros?)\\b|(?<![\\d.])\\d{1,6}[.,]\\d{2}(?![\\d.])", RegexOption.IGNORE_CASE)
        private const val EV_SETTLE = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        private const val EV_TEACH = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
        const val ACTION_TEACH_STOP = "ai.lumen.assistant.TEACH_STOP"
        private const val TEACH_NOTE = 47
        // installers and permission screens: every tap there needs the person, plans cannot pre-approve them
        private val GUARDED_APPS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.samsung.android.packageinstaller", "com.samsung.android.permissioncontroller", "com.android.vending", "com.sec.android.app.samsungapps", "com.miui.packageinstaller", "com.oplus.appdetail", "com.google.android.gms")
        fun err(m: String): JSONObject = JSONObject().put("ok", false).put("error", m)
    }
}

/** Settings Lumen can change directly. Some need a one-time switch from the person, which these open for them. */
object Knobs {
    fun set(ctx: Context, name: String, value: String): JSONObject {
        when (name) {
            "volume", "ringVolume", "alarmVolume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val stream = when (name) { "ringVolume" -> AudioManager.STREAM_RING; "alarmVolume" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
                val max = am.getStreamMaxVolume(stream)
                val pct = value.trimEnd('%').toIntOrNull()?.coerceIn(0, 100) ?: return LumenAccess.err("value is a percent")
                am.setStreamVolume(stream, Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI)
                return done(name, "$pct%")
            }
            "dnd", "silent" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) return need(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb access")
                val on = value == "on" || value == "true"
                if (name == "dnd") nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                else ctx.getSystemService(AudioManager::class.java).ringerMode = if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                return done(name, if (on) "on" else "off")
            }
            "vibrate" -> {
                ctx.getSystemService(AudioManager::class.java).ringerMode = if (value == "on" || value == "true") AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
                return done(name, value)
            }
            "brightness", "autoRotate", "screenTimeout" -> {
                if (!Settings.System.canWrite(ctx)) return need(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, "permission to change system settings")
                val r = ctx.contentResolver
                when (name) {
                    "brightness" -> {
                        val pct = value.trimEnd('%').toIntOrNull()?.coerceIn(1, 100) ?: return LumenAccess.err("value is a percent")
                        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, Math.round(255 * pct / 100f))
                    }
                    "autoRotate" -> Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, if (value == "on" || value == "true") 1 else 0)
                    "screenTimeout" -> Settings.System.putInt(r, Settings.System.SCREEN_OFF_TIMEOUT, ((value.toIntOrNull() ?: 60).coerceIn(15, 1800)) * 1000)
                }
                return done(name, value)
            }
        }
        return LumenAccess.err("unknown setting; use toggle for Quick Settings switches")
    }

    fun read(ctx: Context): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val pct = { s: Int -> Math.round(100f * am.getStreamVolume(s) / am.getStreamMaxVolume(s)) }
        val r = ctx.contentResolver
        return JSONObject().put("volume", pct(AudioManager.STREAM_MUSIC)).put("ringVolume", pct(AudioManager.STREAM_RING))
            .put("ringer", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
            .put("brightness", Math.round(100f * Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255))
            .put("autoRotate", Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
            .put("screenTimeoutSeconds", Settings.System.getInt(r, Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000)
            .put("screenControl", LumenAccess.instance != null)
    }

    private fun done(name: String, value: String) = JSONObject().put("ok", true).put("data", JSONObject().put(name, value))
    private fun need(ctx: Context, action: String, what: String): JSONObject {
        try { ctx.startActivity(Intent(action).apply { if (action == Settings.ACTION_MANAGE_WRITE_SETTINGS) data = android.net.Uri.parse("package:" + ctx.packageName) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        return LumenAccess.err("needs $what: the settings page is open, switch Lumen on there, then ask again")
    }
}
