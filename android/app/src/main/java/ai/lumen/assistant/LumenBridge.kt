package ai.lumen.assistant

import android.Manifest
import androidx.annotation.RequiresApi
import android.content.ComponentName
import android.graphics.Bitmap
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors

class LumenBridge(private val activity: MainActivity) {
    // a few workers: a review waiting for the person must not hold up other phone actions
    private val worker = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "lumen-bridge").apply { isDaemon = true }
    }

    fun alive(): Boolean = !activity.isDestroyed && activity.pageHost == LumenOrigin.HOST

    fun close() {
        worker.shutdownNow()
    }

    private fun work(requestId: String, block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: Exception) {
            deliver(requestId, error("failed"))
        }
    }

    fun capabilities(): String {
        if (activity.pageHost != LumenOrigin.HOST) return "[]"
        val names = JSONArray()
        for (name in METHODS) names.put(name)
        return names.toString()
    }

    fun invoke(requestId: String?, method: String?, argsJson: String?) {
        if (requestId.isNullOrEmpty() || method.isNullOrEmpty()) return
        if (activity.pageHost != LumenOrigin.HOST) {
            if (requestId.startsWith("srv:")) Thread { DeviceRelay.answer(requestId.removePrefix("srv:"), ServiceActions.run(activity, method, try { JSONObject(argsJson ?: "{}") } catch (_: Exception) { JSONObject() })) }.start()
            return
        }
        val args = try {
            if (argsJson.isNullOrBlank() || argsJson == "null") JSONObject() else JSONObject(argsJson)
        } catch (_: Exception) {
            deliver(requestId, error("bad_args"))
            return
        }
        try {
            dispatch(requestId, method, args)
        } catch (_: Exception) {
            deliver(requestId, error("failed"))
        }
    }

    private fun dispatch(requestId: String, method: String, args: JSONObject) {
        when (method) {
            "call" -> call(requestId, args)
            "sms" -> sms(requestId, args)
            "contacts" -> afterGrantBg(requestId, arrayOf(Manifest.permission.READ_CONTACTS)) {
                ok(queryContacts(stringArg(args, "query"), boundedInt(args, "limit", 10, 50)))
            }
            "recentCalls" -> afterGrantBg(requestId, arrayOf(Manifest.permission.READ_CALL_LOG)) {
                ok(queryCalls(boundedInt(args, "limit", 15, 50)))
            }
            "photos" -> afterGrantBg(requestId, arrayOf(imagePermission())) {
                ok(queryPhotos(boundedInt(args, "limit", 24, 60), boundedInt(args, "days", 30, 3650)))
            }
            "photo" -> afterGrantBg(requestId, arrayOf(imagePermission())) {
                loadPhoto(args)
            }
            "takePhoto" -> takePhoto(requestId)
            "pickPhotos" -> pickPhotos(requestId, args)
            "notifications" -> notifications(requestId, args)
            "openNotificationAccess" -> openNotificationAccess(requestId)
            "media" -> media(requestId, args)
            "apps" -> apps(requestId)
            "openApp" -> openApp(requestId, args)
            "installApp" -> installApp(requestId, args)
            "alarm" -> alarm(requestId, args)
            "timer" -> timer(requestId, args)
            "calendarEvents" -> calendarEvents(requestId, args)
            "calendarAdd" -> calendarAdd(requestId, args)
            "location" -> afterGrantBg(
                requestId,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                anyOf = true
            ) { readLocation() }
            "share" -> share(requestId, args)
            "navigate" -> navigate(requestId, args)
            "openUrl" -> openUrl(requestId, args)
            "flashlight" -> flashlight(requestId, args)
            "battery" -> ui(requestId) { deliver(requestId, readBattery()) }
            "openSettings" -> openSettings(requestId, args)
            "vibrate" -> vibrate(requestId, args)
            "smsInbox" -> afterGrantBg(requestId, arrayOf(Manifest.permission.READ_SMS)) {
                ok(Powers.smsInbox(activity, stringArg(args, "query"), boundedInt(args, "limit", 15, 50)))
            }
            "smsSend" -> afterGrantBg(requestId, arrayOf(Manifest.permission.SEND_SMS)) {
                val number = stringArg(args, "number").trim(); val text = stringArg(args, "text")
                if (number.isEmpty() || text.isEmpty()) error("bad_args") else ok(Powers.smsSend(activity, number, text))
            }
            "contactSave" -> afterGrantBg(requestId, arrayOf(Manifest.permission.WRITE_CONTACTS)) {
                val name = stringArg(args, "name").trim()
                if (name.isEmpty()) error("bad_args") else ok(Powers.contactSave(activity, name, stringArg(args, "phone"), stringArg(args, "email")))
            }
            "music" -> afterGrantBg(requestId, arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)) {
                ok(Powers.media(activity, false, stringArg(args, "query"), boundedInt(args, "limit", 20, 60)))
            }
            "videos" -> afterGrantBg(requestId, arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)) {
                ok(Powers.media(activity, true, stringArg(args, "query"), boundedInt(args, "limit", 20, 60)))
            }
            "playLocal" -> ui(requestId) {
                val id = args.optLong("id", -1)
                if (id < 0) deliver(requestId, error("bad_args"))
                else if (startExternal(activity, Powers.playIntent(args.optString("kind") == "video", id))) deliver(requestId, ok(JSONObject().put("playing", true)))
                else deliver(requestId, error("unavailable"))
            }
            "bluetooth" -> if (Build.VERSION.SDK_INT >= 31) afterGrantBg(requestId, arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) { ok(Powers.bluetooth(activity)) }
                else work(requestId) { deliver(requestId, ok(Powers.bluetooth(activity))) }
            "steps" -> if (Build.VERSION.SDK_INT >= 29) afterGrantBg(requestId, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) { ok(Powers.steps(activity)) }
                else work(requestId) { deliver(requestId, ok(Powers.steps(activity))) }
            "phoneInfo" -> afterGrantBg(requestId, arrayOf(Manifest.permission.READ_PHONE_STATE)) { ok(Powers.phoneInfo(activity)) }
            "endCall" -> afterGrantBg(requestId, arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)) { ok(Powers.endCall(activity)) }
            "browserSessions" -> work(requestId) { deliver(requestId, ok(JSONObject().put("signedIn", Sessions.list(activity)))) }
            "browserOpen" -> ui(requestId) {
                val u = Sessions.httpsOnly(stringArg(args, "url"))
                if (u == null) deliver(requestId, error("https only"))
                else { activity.startActivity(Intent(activity, BrowserActivity::class.java).putExtra("url", u.toString())); deliver(requestId, ok(JSONObject().put("opened", u.host))) }
            }
            "browserRead", "browserAct" -> ui(requestId) {
                val steps = if (method == "browserAct") args.optJSONArray("steps") ?: JSONArray() else JSONArray()
                HiddenPage(activity) { deliver(requestId, it) }.run(stringArg(args, "url"), steps)
            }
            "screenRead", "screenshot", "screenTap", "screenType", "screenScroll", "press", "toggle" -> {
                val a = LumenAccess.instance
                if (a == null) ui(requestId) {
                    // a downloaded app's switch is greyed out until "Allow restricted settings": the second time, start there
                    val prefs = activity.getSharedPreferences("lumen", Context.MODE_PRIVATE)
                    val again = prefs.getBoolean("accessAsked", false)
                    prefs.edit().putBoolean("accessAsked", true).apply()
                    if (again) startExternal(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
                    else startExternal(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    deliver(requestId, error(if (again) "screen control is off and Android may be blocking it: on the page now open tap the ⋮ menu (top right), then Allow restricted settings; then Settings > Accessibility > Installed apps > Lumen, switch it on"
                        else "screen control is off: the Accessibility page is open; tap Installed apps, then Lumen, and switch it on (if it is greyed out, ask again and I will open the page that unlocks it)"))
                }
                else if (method == "screenScroll" && a.frontIsLumen()) ui(requestId) {
                    // Lumen itself is the app in front: scroll its own page
                    val up = stringArg(args, "direction") == "up"
                    activity.webView.evaluateJavascript("(document.querySelector('#feed')||document.scrollingElement).scrollBy({top:${if (up) -700 else 700},behavior:'smooth'})", null)
                    deliver(requestId, ok(JSONObject().put("scrolled", true).put("in", "Lumen")))
                } else if (method == "toggle") ui(requestId) { a.toggle(stringArg(args, "name"), if (args.has("on")) args.optBoolean("on") else null) { deliver(requestId, it) } }
                else if (method == "screenTap" && args.optBoolean("confirmed") && stringArg(args, "pkg").isNotEmpty()) ui(requestId) {
                    // confirmed on Lumen's card: return to that app, find the same button by its words, tap it
                    activity.packageManager.getLaunchIntentForPackage(stringArg(args, "pkg"))?.let { startExternal(activity, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ work(requestId) { deliver(requestId, a.tap(JSONObject().put("text", stringArg(args, "text")).put("confirmed", true))) } }, 1200)
                }
                else work(requestId) {
                    deliver(requestId, when (method) {
                        "screenRead" -> ok(a.read())
                        "screenshot" -> a.screenshot()
                        "screenTap" -> a.tap(args)
                        "screenType" -> a.type(args)
                        "screenScroll" -> a.scroll(args)
                        else -> a.press(stringArg(args, "key"))
                    })
                }
            }
            "setting" -> ui(requestId) { deliver(requestId, Knobs.set(activity, stringArg(args, "name"), stringArg(args, "value"))) }
            "settings" -> work(requestId) { deliver(requestId, ok(Knobs.read(activity))) }
            // every box and its value, on Lumen's overlay over the page, before anything is typed
            "formReview" -> work(requestId) {
                val a = LumenAccess.instance
                val r = a?.sheet?.ask("review", args)
                deliver(requestId, when {
                    a == null || r == null -> error("screen control is off")
                    r.optBoolean("ok") -> { a.approve(args.optJSONArray("approves")); ok(JSONObject().put("fields", r.optJSONArray("fields") ?: JSONArray()).put("confirmed", true)) }
                    r.optBoolean("timeout") -> error("no answer from the person")
                    else -> error("the person said not now")
                })
            }
            "lifeSearch" -> work(requestId) { deliver(requestId, ok(Powers.lifeSearch(activity, stringArg(args, "query"), stringArg(args, "from"), stringArg(args, "to"), boundedInt(args, "limit", 8, 25)))) }
            // one look at everything Lumen needs, for Settings: what is on, what is off, and a fix for each
            "health" -> work(requestId) {
                val pm = activity.getSystemService(android.os.PowerManager::class.java)
                val perms = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)
                val missing = perms.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }.map { it.substringAfterLast('.') }
                val d = JSONObject()
                    .put("assistant", Build.VERSION.SDK_INT >= 29 && activity.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true)
                    .put("control", LumenAccess.instance != null)
                    .put("notifications", NotificationAccess.enabled(activity))
                    .put("wake", WakeService.enabled(activity)).put("samsung", android.os.Build.MANUFACTURER.equals("samsung", true)).put("wakeRunning", WakeService.instance != null)
                    .put("installs", Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())
                    .put("battery", pm.isIgnoringBatteryOptimizations(activity.packageName))
                    .put("dnd", activity.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted)
                    .put("systemSettings", android.provider.Settings.System.canWrite(activity))
                    .put("missing", JSONArray(missing)).put("version", try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName } catch (_: Exception) { "" })
                    .put("driving", Driving.on(activity)).put("drivingAuto", Driving.auto(activity)).put("wakeScreenOff", WakeService.screenOff(activity))
                deliver(requestId, ok(d))
            }
            "fix" -> ui(requestId) {
                val what = stringArg(args, "what")
                val i = when (what) {
                    "control" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    "restricted", "appinfo" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
                    "notifications" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    "assistant" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    "installs" -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                    "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
                    // Samsung: the battery page, where Background usage limits holds the "Never sleeping apps" list
                    "sleeping" -> Intent("com.samsung.android.sm.ACTION_BATTERY").takeIf { it.resolveActivity(activity.packageManager) != null } ?: Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    // Samsung: press and hold the side key -> Digital assistant (no microphone while waiting)
                    "sideKey" -> Intent("com.samsung.android.intent.action.SIDE_KEY_LONG_PRESS_SETTINGS").takeIf { it.resolveActivity(activity.packageManager) != null } ?: Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    "systemSettings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + activity.packageName))
                    "permissions" -> { activity.askMissing(); null }
                    "mode" -> { PhoneMode.choose(activity, ""); Tailnet.forget(activity); activity.getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
                        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); android.os.Process.killProcess(android.os.Process.myPid()); null }
                    else -> null
                }
                if (i != null) startExternal(activity, i)
                deliver(requestId, ok(JSONObject().put("opened", what)))
            }
            "prefs" -> work(requestId) { // small switches the page can set: driving auto-on, hearing "Hey Lumen" with the screen off
                if (args.has("drivingAuto")) Driving.setAuto(activity, args.optBoolean("drivingAuto"))
                if (args.has("driving")) Driving.set(activity, args.optBoolean("driving"))
                if (args.has("wakeScreenOff")) WakeService.setScreenOff(activity, args.optBoolean("wakeScreenOff"))
                deliver(requestId, ok(JSONObject().put("driving", Driving.on(activity)).put("drivingAuto", Driving.auto(activity)).put("wakeScreenOff", WakeService.screenOff(activity))))
            }
            "refreshTriggers" -> work(requestId) { Triggers.forget(); deliver(requestId, ok(JSONObject().put("refreshed", true))) }
            "notify" -> work(requestId) { Triggers.notify(activity, stringArg(args, "title"), stringArg(args, "text"), stringArg(args, "open")); Triggers.forget(); deliver(requestId, ok(JSONObject().put("shown", true))) }
            "appUpdate" -> work(requestId) { deliver(requestId, Updater.install(activity)) }
            "skillList" -> work(requestId) { deliver(requestId, ok(Skills.list(activity))) }
            "skillGet" -> work(requestId) { val k = Skills.all(activity).optJSONObject(stringArg(args, "name")); deliver(requestId, if (k == null) error("no such skill") else ok(k.put("name", stringArg(args, "name")))) }
            "skillSave" -> work(requestId) { deliver(requestId, Skills.save(activity, args)) }
            "skillExample" -> work(requestId) { deliver(requestId, Skills.addExample(activity, stringArg(args, "name"), stringArg(args, "text"))) }
            "skillDelete" -> work(requestId) { deliver(requestId, Skills.delete(activity, stringArg(args, "name"))) }
            // many steps in one go, run on the phone, the screen after comes back with the result
            "screenDo" -> work(requestId) { deliver(requestId, Skills.runSteps(activity, args.optJSONArray("steps") ?: JSONArray(), args.optJSONObject("params") ?: JSONObject(), "batch") { launchNow(it) }) }
            "skillRun" -> work(requestId) { deliver(requestId, Skills.run(activity, stringArg(args, "name"), args.optJSONObject("params") ?: JSONObject()) { launchNow(it) }) }
            "wakeWord" -> ui(requestId) {
                if (args.has("on")) WakeService.setEnabled(activity, args.optBoolean("on"))
                deliver(requestId, ok(JSONObject().put("on", WakeService.enabled(activity))))
            }
            "assistantStatus" -> ui(requestId) {
                val held = Build.VERSION.SDK_INT >= 29 && activity.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
                deliver(requestId, ok(JSONObject().put("isAssistant", held)))
            }
            "assistantSetup" -> ui(requestId) {
                val opened = startExternal(activity, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) || startExternal(activity, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                deliver(requestId, if (opened) ok(JSONObject().put("opened", true)) else error("unavailable"))
            }
            "browserForget" -> work(requestId) { Sessions.forget(activity, stringArg(args, "host")); deliver(requestId, ok(JSONObject().put("forgot", stringArg(args, "host")))) }
            else -> deliver(requestId, error("unknown_method"))
        }
    }

    private fun call(requestId: String, args: JSONObject) {
        val number = stringArg(args, "number").trim()
        if (number.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            val uri = Uri.fromParts("tel", number, null)
            val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            if (granted && startExternal(activity, Intent(Intent.ACTION_CALL, uri))) {
                deliver(requestId, ok(JSONObject().put("placed", true)))
                return@ui
            }
            val dialed = startExternal(activity, Intent(Intent.ACTION_DIAL, uri))
            if (dialed) deliver(requestId, ok(JSONObject().put("placed", false)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun sms(requestId: String, args: JSONObject) {
        val number = stringArg(args, "number").trim()
        if (number.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        val text = stringArg(args, "text")
        ui(requestId) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).apply {
                putExtra("sms_body", text)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            if (startExternal(activity, intent)) deliver(requestId, ok(JSONObject().put("opened", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun takePhoto(requestId: String) {
        ui(requestId) {
            activity.ensurePermissions(arrayOf(Manifest.permission.CAMERA), false) { granted ->
                if (activity.isDestroyed || !activity.isOriginAllowed()) return@ensurePermissions
                if (!granted) {
                    deliver(requestId, error("permission_denied"))
                    return@ensurePermissions
                }
                activity.capturePhoto { file ->
                    if (file == null) {
                        deliver(requestId, error("cancelled"))
                        return@capturePhoto
                    }
                    work(requestId) {
                        try {
                            val image = Imaging.jpegDataUrl(file, 1280, 80)
                            if (image == null) deliver(requestId, error("unavailable"))
                            else deliver(requestId, ok(JSONObject().put("image", image)))
                        } catch (_: Exception) {
                            deliver(requestId, error("failed"))
                        } finally {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    private fun pickPhotos(requestId: String, args: JSONObject) {
        val multiple = args.optBoolean("multiple", true)
        ui(requestId) {
            activity.pickImages(multiple) { uris ->
                if (uris.isNullOrEmpty()) {
                    deliver(requestId, error("cancelled"))
                    return@pickImages
                }
                val chosen = uris.take(6)
                work(requestId) {
                    try {
                        val images = JSONArray()
                        for (uri in chosen) {
                            val image = Imaging.jpegDataUrl(activity, uri, 1280, 80) ?: continue
                            images.put(JSONObject().put("image", image))
                        }
                        if (images.length() == 0) deliver(requestId, error("unavailable"))
                        else deliver(requestId, ok(images))
                    } catch (_: Exception) {
                        deliver(requestId, error("failed"))
                    }
                }
            }
        }
    }

    private fun notifications(requestId: String, args: JSONObject) {
        val limit = boundedInt(args, "limit", 20, 100)
        ui(requestId) {
            if (!NotificationAccess.enabled(activity)) {
                deliver(requestId, error("needs_access"))
                return@ui
            }
            deliver(requestId, ok(NotificationStore.recent(limit)))
        }
    }

    private fun openNotificationAccess(requestId: String) {
        ui(requestId) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            if (startExternal(activity, intent)) deliver(requestId, ok(JSONObject().put("opened", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun media(requestId: String, args: JSONObject) {
        val action = if (!args.has("action") || args.isNull("action")) "now" else args.optString("action")
        if (action !in MEDIA_ACTIONS) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            if (!NotificationAccess.enabled(activity)) {
                deliver(requestId, error("needs_access"))
                return@ui
            }
            work(requestId) {
                try {
                    deliver(requestId, mediaResult(action))
                } catch (_: SecurityException) {
                    deliver(requestId, error("needs_access"))
                } catch (_: Exception) {
                    deliver(requestId, error("failed"))
                }
            }
        }
    }

    private fun apps(requestId: String) {
        ui(requestId) {
            work(requestId) {
                try {
                    val rows = launchable()
                    val out = JSONArray()
                    for ((label, pkg) in rows) {
                        out.put(JSONObject().put("label", label).put("pkg", pkg))
                    }
                    deliver(requestId, ok(out))
                } catch (_: Exception) {
                    deliver(requestId, error("failed"))
                }
            }
        }
    }

    private fun openApp(requestId: String, args: JSONObject) {
        val pkg = stringArg(args, "pkg").trim()
        val name = stringArg(args, "name").trim()
        val link = stringArg(args, "link").trim()
        val screen = stringArg(args, "screen").trim()
        if (pkg.isEmpty() && name.isEmpty() && link.isEmpty() && screen.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            // screens apps only open through their own launch flags (what their home-screen shortcuts use)
            SCREENS[screen]?.let { s ->
                val i = Intent(Intent.ACTION_VIEW).setClassName(s.first, s.second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                s.third.forEach { (k, v) -> i.putExtra(k, v) }
                if (startExternal(activity, i)) { deliver(requestId, ok(JSONObject().put("opened", true).put("screen", screen))); return@ui }
            }
            // a link goes straight to a screen inside the app (a tab, a chat, a search); the app itself is the fallback
            if (link.isNotEmpty()) {
                val uri = try { Uri.parse(link) } catch (_: Exception) { null }
                val scheme = uri?.scheme?.lowercase()
                if (uri != null && scheme != null && scheme !in setOf("javascript", "file", "content", "intent", "data")) {
                    val owner = pkg.ifEmpty { if (name.isNotEmpty()) bestMatch(launchable(), name) ?: "" else "" }
                    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (owner.isNotEmpty()) view.setPackage(owner)
                    if (startExternal(activity, view)) {
                        deliver(requestId, ok(JSONObject().put("opened", true).put("link", link).put("pkg", owner)))
                        return@ui
                    }
                    // the app did not take that link: open it without the package pin (browser or any handler)
                    if (owner.isNotEmpty() && scheme == "https" && openHttpUrl(activity, link)) {
                        deliver(requestId, ok(JSONObject().put("opened", true).put("link", link).put("in", "browser")))
                        return@ui
                    }
                }
            }
            val target = when {
                pkg.isNotEmpty() && launchPackage(pkg) -> pkg
                else -> {
                    val match = if (name.isNotEmpty()) bestMatch(launchable(), name) else null
                    if (match != null && launchPackage(match)) match else null
                }
            }
            if (target == null) deliver(requestId, error("not_found"))
            else deliver(requestId, ok(JSONObject().put("opened", true).put("pkg", target)))
        }
    }

    fun launchNow(o: JSONObject): Boolean = Apps.launch(activity, if (o.has("fresh")) o else o.put("fresh", true))

    private fun installApp(requestId: String, args: JSONObject) {
        val pkg = stringArg(args, "pkg").trim()
        val name = stringArg(args, "name").trim()
        if (pkg.isEmpty() && name.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            val opened = openStore(activity, pkg.ifEmpty { null }, name.ifEmpty { null })
            if (opened) deliver(requestId, ok(JSONObject().put("opened", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun alarm(requestId: String, args: JSONObject) {
        if (!args.has("hour") || !args.has("minute")) {
            deliver(requestId, error("bad_args"))
            return
        }
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            deliver(requestId, error("bad_args"))
            return
        }
        val label = stringArg(args, "label")
        ui(requestId) {
            val opened = startExternal(activity, alarmIntent(hour, minute, label))
            if (opened) deliver(requestId, ok(JSONObject().put("set", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun timer(requestId: String, args: JSONObject) {
        if (!args.has("seconds")) {
            deliver(requestId, error("bad_args"))
            return
        }
        val seconds = args.optInt("seconds", -1)
        if (seconds !in 1..86400) {
            deliver(requestId, error("bad_args"))
            return
        }
        val label = stringArg(args, "label")
        ui(requestId) {
            val opened = startExternal(activity, timerIntent(seconds, label))
            if (opened) deliver(requestId, ok(JSONObject().put("set", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun calendarEvents(requestId: String, args: JSONObject) {
        val from = parseTime(stringArg(args, "from"), endOfDay = false)
        val to = parseTime(stringArg(args, "to"), endOfDay = true)
        if (from == null || to == null) {
            deliver(requestId, error("bad_args"))
            return
        }
        afterGrantBg(requestId, arrayOf(Manifest.permission.READ_CALENDAR)) {
            ok(queryEvents(from, to))
        }
    }

    private fun calendarAdd(requestId: String, args: JSONObject) {
        val title = stringArg(args, "title").trim()
        val start = parseTime(stringArg(args, "start"), endOfDay = false)
        val end = parseTime(stringArg(args, "end"), endOfDay = false)
        if (title.isEmpty() || start == null || end == null || end < start) {
            deliver(requestId, error("bad_args"))
            return
        }
        val location = stringArg(args, "location")
        afterGrantBg(
            requestId,
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        ) {
            addEvent(title, start, end, location)
        }
    }

    private fun share(requestId: String, args: JSONObject) {
        val text = stringArg(args, "text")
        val url = stringArg(args, "url")
        ui(requestId) { deliver(requestId, doShare(text, url)) }
    }

    private fun navigate(requestId: String, args: JSONObject) {
        val destination = stringArg(args, "destination").trim()
        if (destination.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        val mode = if (!args.has("mode") || args.isNull("mode")) "driving" else args.optString("mode")
        if (mode != "driving" && mode != "walking" && mode != "bicycling") {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            val opened = openNavigation(activity, destination, mode)
            if (opened) deliver(requestId, ok(JSONObject().put("opened", true)))
            else deliver(requestId, error("unavailable"))
        }
    }

    private fun openUrl(requestId: String, args: JSONObject) {
        val url = stringArg(args, "url").trim()
        if (url.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) {
            if (openHttpUrl(activity, url)) {
                deliver(requestId, ok(JSONObject().put("opened", true)))
                return@ui
            }
            val scheme = try {
                Uri.parse(url).scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
            val blocked = scheme == null ||
                scheme == "javascript" ||
                scheme == "file" ||
                scheme == "content" ||
                scheme == "intent"
            deliver(requestId, error(if (blocked) "bad_args" else "unavailable"))
        }
    }

    private fun flashlight(requestId: String, args: JSONObject) {
        if (!args.has("on") || args.isNull("on")) {
            deliver(requestId, error("bad_args"))
            return
        }
        val on = args.optBoolean("on", false)
        ui(requestId) { deliver(requestId, setTorch(on)) }
    }

    private fun openSettings(requestId: String, args: JSONObject) {
        val page = stringArg(args, "page").trim()
        if (page.isEmpty()) {
            deliver(requestId, error("bad_args"))
            return
        }
        ui(requestId) { deliver(requestId, openSettingsPage(page)) }
    }

    private fun vibrate(requestId: String, args: JSONObject) {
        val ms = vibrateMs(args)
        ui(requestId) { deliver(requestId, doVibrate(ms)) }
    }

    private fun ui(requestId: String, block: () -> Unit) {
        activity.runOnUiThread {
            try {
                if (activity.isDestroyed || !activity.isOriginAllowed()) return@runOnUiThread
                block()
            } catch (_: Exception) {
                deliver(requestId, error("failed"))
            }
        }
    }

    private fun afterGrant(
        requestId: String,
        perms: Array<String>,
        anyOf: Boolean = false,
        block: () -> Unit
    ) {
        activity.ensurePermissions(perms, anyOf) { granted ->
            try {
                if (activity.isDestroyed || !activity.isOriginAllowed()) return@ensurePermissions
                if (!granted) {
                    deliver(requestId, error("permission_denied"))
                    return@ensurePermissions
                }
                block()
            } catch (_: Exception) {
                deliver(requestId, error("failed"))
            }
        }
    }

    private fun afterGrantBg(
        requestId: String,
        perms: Array<String>,
        anyOf: Boolean = false,
        block: () -> JSONObject
    ) {
        afterGrant(requestId, perms, anyOf) {
            work(requestId) {
                try {
                    deliver(requestId, block())
                } catch (_: SecurityException) {
                    deliver(requestId, error("permission_denied"))
                } catch (_: Exception) {
                    deliver(requestId, error("failed"))
                }
            }
        }
    }

    private fun deliver(requestId: String, result: JSONObject) {
        if (!result.optBoolean("ok", true)) Telemetry.warn("bridge", result.optString("error"), JSONObject().put("id", requestId))
        if (requestId.startsWith("srv:")) { DeviceRelay.answer(requestId.removePrefix("srv:"), result); return }
        activity.runOnUiThread {
            try {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (!activity.isOriginAllowed()) return@runOnUiThread
                val json = result.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                val script = "window.__lumenNative(" + JSONObject.quote(requestId) + "," + json + ")"
                activity.webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun queryContacts(query: String, limit: Int): JSONArray {
        val resolver = activity.contentResolver
        val bundle = Bundle().apply {
            if (query.isNotBlank()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(likeArg(query))
                )
            }
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = JSONArray()
        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            bundle,
            null
        ) ?: return out
        cursor.use { rows ->
            val idIdx = rows.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = rows.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            var count = 0
            while (rows.moveToNext() && count < limit) {
                if (idIdx < 0) break
                val id = rows.getString(idIdx) ?: continue
                val name = if (nameIdx >= 0) rows.getString(nameIdx).orEmpty() else ""
                out.put(
                    JSONObject()
                        .put("name", name)
                        .put("phones", contactValues(resolver, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id))
                        .put("emails", contactValues(resolver, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID, id))
                )
                count++
            }
        }
        return out
    }

    private fun contactValues(
        resolver: ContentResolver,
        uri: Uri,
        column: String,
        idColumn: String,
        contactId: String
    ): JSONArray {
        val out = JSONArray()
        val cursor = resolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId), null) ?: return out
        cursor.use { rows ->
            val idx = rows.getColumnIndex(column)
            var count = 0
            while (rows.moveToNext() && count < 30) {
                val value = if (idx >= 0) rows.getString(idx) else null
                if (!value.isNullOrBlank()) {
                    out.put(value)
                    count++
                }
            }
        }
        return out
    }

    private fun queryCalls(limit: Int): JSONArray {
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = JSONArray()
        val cursor = activity.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            bundle,
            null
        ) ?: return out
        cursor.use { rows ->
            val nameIdx = rows.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = rows.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = rows.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = rows.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = rows.getColumnIndex(CallLog.Calls.DURATION)
            var count = 0
            while (rows.moveToNext() && count < limit) {
                val type = if (typeIdx >= 0) rows.getInt(typeIdx) else CallLog.Calls.MISSED_TYPE
                val date = if (dateIdx >= 0) rows.getLong(dateIdx) else 0L
                out.put(
                    JSONObject()
                        .put("name", if (nameIdx >= 0) rows.getString(nameIdx).orEmpty() else "")
                        .put("number", if (numberIdx >= 0) rows.getString(numberIdx).orEmpty() else "")
                        .put("type", callType(type))
                        .put("date", Instant.ofEpochMilli(date).toString())
                        .put("duration", if (durationIdx >= 0) rows.getLong(durationIdx) else 0L)
                )
                count++
            }
        }
        return out
    }

    private fun queryPhotos(limit: Int, days: Int): JSONArray {
        val sinceSec = System.currentTimeMillis() / 1000L - days * 86400L
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val bundle = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(sinceSec.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = JSONArray()
        val cursor = activity.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            ),
            bundle,
            null
        ) ?: return out
        cursor.use { rows ->
            val idIdx = rows.getColumnIndex(MediaStore.Images.Media._ID)
            val takenIdx = rows.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = rows.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val widthIdx = rows.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIdx = rows.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            var count = 0
            while (rows.moveToNext() && count < limit) {
                if (idIdx < 0) break
                val id = rows.getLong(idIdx)
                val taken = if (takenIdx >= 0) rows.getLong(takenIdx) else 0L
                val added = if (addedIdx >= 0) rows.getLong(addedIdx) else 0L
                val whenMs = if (taken > 0L) taken else added * 1000L
                val uri = ContentUris.withAppendedId(collection, id)
                val thumb = try {
                    Imaging.thumbDataUrl(activity, uri)
                } catch (_: Exception) {
                    null
                }
                out.put(
                    JSONObject()
                        .put("id", id.toString())
                        .put("date", Instant.ofEpochMilli(whenMs).toString())
                        .put("width", if (widthIdx >= 0) rows.getInt(widthIdx) else 0)
                        .put("height", if (heightIdx >= 0) rows.getInt(heightIdx) else 0)
                        .put("thumb", thumb ?: JSONObject.NULL)
                )
                count++
            }
        }
        return out
    }

    private fun loadPhoto(args: JSONObject): JSONObject {
        val id = longArg(args, "id") ?: return error("bad_args")
        val max = maxSide(args)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val image = Imaging.jpegDataUrl(activity, uri, max, 80) ?: return error("not_found")
        return ok(JSONObject().put("image", image))
    }

    private fun mediaResult(action: String): JSONObject {
        val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return error("unavailable")
        val component = ComponentName(activity, LumenNotificationListener::class.java)
        val sessions = manager.getActiveSessions(component)
        val controller = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
        val before = controller?.playbackState?.state
        if (controller != null && action != "now") {
            val controls = controller.transportControls
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> {
                    if (before == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                }
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
            }
        }
        return ok(mediaPayload(controller, action, before))
    }

    private fun mediaPayload(controller: MediaController?, action: String, before: Int?): JSONObject {
        if (controller == null) {
            return JSONObject()
                .put("app", JSONObject.NULL)
                .put("title", JSONObject.NULL)
                .put("artist", JSONObject.NULL)
                .put("album", JSONObject.NULL)
                .put("state", "none")
                .put("art", JSONObject.NULL)
        }
        val metadata = controller.metadata
        val state = when (action) {
            "play" -> "playing"
            "pause" -> "paused"
            "toggle" -> if (before == PlaybackState.STATE_PLAYING) "paused" else "playing"
            else -> playbackName(controller.playbackState?.state)
        }
        val artBitmap = try {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) {
            null
        }
        val art = artDataUrl(artBitmap)
        return JSONObject()
            .put("app", appLabel(activity, controller.packageName))
            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: JSONObject.NULL)
            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: JSONObject.NULL)
            .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: JSONObject.NULL)
            .put("state", state)
            .put("art", art)
    }

    private fun artDataUrl(source: Bitmap?): Any {
        if (source == null || source.isRecycled) return JSONObject.NULL
        val raw = source.config
        val config = if (raw == null || raw == Bitmap.Config.HARDWARE) {
            Bitmap.Config.ARGB_8888
        } else {
            raw
        }
        val copy = try {
            source.copy(config, false)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } ?: return JSONObject.NULL
        if (copy === source) return JSONObject.NULL
        return try {
            Imaging.jpegDataUrl(copy, 256, 70)
        } catch (_: Throwable) {
            JSONObject.NULL
        }
    }

    private fun queryEvents(fromMs: Long, toMs: Long): JSONArray {
        val start = minOf(fromMs, toMs)
        val end = maxOf(fromMs, toMs)
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        val out = JSONArray()
        val cursor = activity.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID
            ),
            null,
            null,
            null
        ) ?: return out
        val names = HashMap<Long, String>()
        cursor.use { rows ->
            val titleIdx = rows.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = rows.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = rows.getColumnIndex(CalendarContract.Instances.END)
            val locationIdx = rows.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val calIdx = rows.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
            var count = 0
            while (rows.moveToNext() && count < 200) {
                val begin = if (beginIdx >= 0) rows.getLong(beginIdx) else 0L
                val finish = if (endIdx >= 0) rows.getLong(endIdx) else begin
                val calId = if (calIdx >= 0) rows.getLong(calIdx) else -1L
                out.put(
                    JSONObject()
                        .put("title", if (titleIdx >= 0) rows.getString(titleIdx).orEmpty() else "")
                        .put("start", Instant.ofEpochMilli(begin).toString())
                        .put("end", Instant.ofEpochMilli(finish).toString())
                        .put("location", if (locationIdx >= 0) rows.getString(locationIdx).orEmpty() else "")
                        .put("calendar", if (calId >= 0L) calendarName(calId, names) else "")
                )
                count++
            }
        }
        return out
    }

    private fun addEvent(title: String, start: Long, end: Long, location: String): JSONObject {
        val calendarId = writableCalendarId() ?: return error("no_calendar")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        val uri = activity.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return error("failed")
        val id = uri.lastPathSegment ?: return error("failed")
        return ok(JSONObject().put("id", id))
    }

    private fun writableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val cursor = activity.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            null
        ) ?: return null
        var fallback: Long? = null
        cursor.use { rows ->
            val idIdx = rows.getColumnIndex(CalendarContract.Calendars._ID)
            val primaryIdx = rows.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            while (rows.moveToNext()) {
                if (idIdx < 0) break
                val id = rows.getLong(idIdx)
                val primary = primaryIdx >= 0 && rows.getInt(primaryIdx) == 1
                if (primary) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    private fun calendarName(id: Long, cache: MutableMap<Long, String>): String {
        cache[id]?.let { return it }
        val cursor = activity.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            null,
            null,
            null
        )
        val name = cursor?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0).orEmpty() else ""
        } ?: ""
        cache[id] = name
        return name
    }

    private fun readLocation(): JSONObject {
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return error("unavailable")
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        // nothing recent saved on the phone: ask it for a fresh fix (up to 12 seconds)
        if ((best == null || System.currentTimeMillis() - best.time > 120_000) && Build.VERSION.SDK_INT >= 30) {
            val providers = listOfNotNull(if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { try { manager.isProviderEnabled(it) } catch (_: Exception) { false } }
            for (provider in providers) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var fresh: Location? = null
                try {
                    @Suppress("MissingPermission")
                    val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val cancel = android.os.CancellationSignal()
                    manager.getCurrentLocation(provider, cancel, ex) { fresh = it; latch.countDown() }
                    if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) cancel.cancel()
                    ex.shutdown()
                } catch (_: Exception) {}
                if (fresh != null) { best = fresh; break }
            }
        }
        val found = best ?: return error("location is off or no fix yet")
        return ok(
            JSONObject()
                .put("lat", found.latitude)
                .put("lon", found.longitude)
                .put("accuracy", found.accuracy.toDouble())
        )
    }

    private fun readBattery(): JSONObject {
        val manager = activity.getSystemService(BatteryManager::class.java)
            ?: return error("unavailable")
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ok(
            JSONObject()
                .put("level", if (level >= 0) level else 0)
                .put("charging", manager.isCharging)
        )
    }

    private fun setTorch(on: Boolean): JSONObject {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return error("unavailable")
        return try {
            var chosen: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!flash) continue
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id
                    break
                }
                if (chosen == null) chosen = id
            }
            val cameraId = chosen ?: return error("unavailable")
            manager.setTorchMode(cameraId, on)
            ok(JSONObject().put("on", on))
        } catch (_: Exception) {
            error("unavailable")
        }
    }

    private fun doVibrate(ms: Long): JSONObject {
        val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            vibratorApi31()
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return error("unavailable")
        return try {
            vibrator.vibrate(effect)
            ok(JSONObject().put("ms", ms))
        } catch (_: Exception) {
            error("unavailable")
        }
    }

    @RequiresApi(31)
    private fun vibratorApi31(): Vibrator? {
        return activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }

    private fun doShare(text: String, url: String): JSONObject {
        val body = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
        if (body.isBlank()) return error("bad_args")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(send, activity.getString(R.string.share_title))
        return if (startExternal(activity, chooser)) ok(JSONObject().put("opened", true))
        else error("unavailable")
    }

    private fun openSettingsPage(page: String): JSONObject {
        val intent = settingsIntent(page) ?: return error("bad_args")
        if (startExternal(activity, intent)) return ok(JSONObject().put("opened", true))
        val fallback = Intent(Settings.ACTION_SETTINGS)
        return if (startExternal(activity, fallback)) ok(JSONObject().put("opened", true))
        else error("unavailable")
    }

    private fun launchable(): List<Pair<String, String>> {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val rows = ArrayList<Pair<String, String>>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            rows.add(label to pkg)
        }
        rows.sortBy { it.first.lowercase() }
        return rows
    }

    private fun bestMatch(rows: List<Pair<String, String>>, name: String): String? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        var bestPkg: String? = null
        var bestScore = 0
        for ((label, pkg) in rows) {
            val text = label.lowercase()
            val score = when {
                text == needle -> 100
                text.replace(" ", "") == needle.replace(" ", "") -> 90
                text.startsWith(needle) -> 80
                needle.startsWith(text) && text.length >= 3 -> 70
                text.contains(needle) -> 60
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
            }
        }
        return if (bestScore >= 60) bestPkg else null
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startExternal(activity, intent)
    }

    private fun imagePermission(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun callType(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            else -> "missed"
        }
    }

    private fun playbackName(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            null, PlaybackState.STATE_NONE -> "none"
            else -> "other"
        }
    }

    private fun stringArg(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        return when (val value = args.get(key)) {
            is String -> value
            is Number -> {
                val text = value.toString()
                if (text.endsWith(".0")) text.dropLast(2) else text
            }
            else -> value.toString()
        }
    }

    private fun longArg(args: JSONObject, key: String): Long? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val value = args.get(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun boundedInt(args: JSONObject, key: String, default: Int, max: Int): Int {
        if (!args.has(key) || args.isNull(key)) return default
        val value = args.optInt(key, default)
        if (value <= 0) return default
        return value.coerceAtMost(max)
    }

    private fun maxSide(args: JSONObject): Int {
        if (!args.has("max") || args.isNull("max")) return 1280
        val value = args.optInt("max", 1280)
        if (value <= 0) return 1280
        return value.coerceIn(32, 4096)
    }

    private fun vibrateMs(args: JSONObject): Long {
        if (!args.has("ms") || args.isNull("ms")) return 40L
        val value = args.optLong("ms", 40L)
        if (value <= 0L) return 40L
        return value.coerceIn(1L, 5000L)
    }

    private fun likeArg(raw: String): String {
        val cleaned = raw.replace("%", "").replace("_", "").replace("\\", "")
        return "%$cleaned%"
    }

    private fun parseTime(raw: String, endOfDay: Boolean): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return ZonedDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            val day = LocalDate.parse(text)
            val zoned = if (endOfDay) {
                day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).minusNanos(1)
            } else {
                day.atStartOfDay(ZoneId.systemDefault())
            }
            return zoned.toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    private fun ok(data: Any): JSONObject = JSONObject().put("ok", true).put("data", data)

    private fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)

    companion object {
        private val SCREENS = Apps.SCREENS
        val METHODS = listOf(
            "call",
            "sms",
            "contacts",
            "recentCalls",
            "photos",
            "photo",
            "takePhoto",
            "pickPhotos",
            "notifications",
            "openNotificationAccess",
            "media",
            "apps",
            "openApp",
            "installApp",
            "alarm",
            "timer",
            "calendarEvents",
            "calendarAdd",
            "location",
            "share",
            "navigate",
            "openUrl",
            "flashlight",
            "battery",
            "openSettings",
            "vibrate",
            "smsInbox", "smsSend", "contactSave", "music", "videos", "playLocal", "bluetooth", "steps", "phoneInfo", "endCall",
            "browserSessions", "browserOpen", "browserRead", "browserAct", "browserForget", "assistantStatus", "assistantSetup", "wakeWord",
            "screenRead", "screenshot", "lifeSearch", "screenTap", "screenType", "screenScroll", "press", "toggle", "setting", "settings",
            "skillList", "skillGet", "skillSave", "skillExample", "skillRun", "skillDelete", "screenDo", "appUpdate", "formReview", "health", "fix", "prefs", "notify"
        )

        private val MEDIA_ACTIONS = setOf("now", "play", "pause", "toggle", "next", "previous")
    }
}
