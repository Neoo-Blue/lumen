package ai.lumen.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CalendarContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device automation rules that run with no server:
 * time schedules, car Bluetooth, charging, Wi-Fi join/leave, and built-in meetingSilence.
 */
object LocalRules {
    private const val PREFS = "local_rules"
    private const val KEY_RULES = "rules"
    private const val KEY_ALARM_IDS = "alarmIds"
    private const val KEY_MEETING_ON = "meetingSilence"
    private const val KEY_MEETING_PREV = "meetingPrevRinger"
    private const val KEY_MEETING_UNTIL = "meetingSilenceUntil"
    private const val KEY_MEETING_CODES = "meetingAlarmCodes"

    const val ACTION_TIME_RULE = "ai.lumen.assistant.LOCAL_RULE_TIME"
    const val ACTION_MEETING_START = "ai.lumen.assistant.MEETING_SILENCE_START"
    const val ACTION_MEETING_END = "ai.lumen.assistant.MEETING_SILENCE_END"
    const val ACTION_MEETING_REFRESH = "ai.lumen.assistant.MEETING_SILENCE_REFRESH"

    private const val CODE_MEETING_REFRESH = 910_000
    private val VIDEO_RE = Regex(
        "(?i)(zoom\\.us|meet\\.google\\.com|teams\\.microsoft\\.com|teams\\.live\\.com|webex\\.com|facetime\\.apple\\.com|whereby\\.com|chime\\.aws|gotomeeting\\.com|bluejeans\\.com|meet\\.jit\\.si|voovmeeting\\.com|meeting\\.tencent\\.com|feishu\\.cn/vc|larksuite\\.com/vc)"
    )

    private val monitorsStarted = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-rules").apply { isDaemon = true } }
    @Volatile private var currentSsid: String? = null
    @Volatile private var wifiReady = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        ensureMonitors(app)
        worker.execute {
            rescheduleTimeRules(app)
            if (meetingSilence(app)) scheduleMeetingSilence(app)
        }
    }

    /** Idempotent runtime registration for charging and Wi-Fi callbacks. */
    fun ensureMonitors(ctx: Context) {
        val app = ctx.applicationContext
        if (!monitorsStarted.compareAndSet(false, true)) return
        registerCharging(app)
        registerWifi(app)
    }

    fun list(ctx: Context): JSONArray = try {
        JSONArray(prefs(ctx).getString(KEY_RULES, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    fun meetingSilence(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MEETING_ON, false)

    fun handle(ctx: Context, args: JSONObject): JSONObject {
        val app = ctx.applicationContext
        ensureMonitors(app)
        if (args.has("set")) {
            val arr = args.optJSONArray("set") ?: return LumenAccess.err("bad_args")
            if (arr.length() > 50) return LumenAccess.err("at most 50 rules")
            val clean = JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optString("id").trim().ifEmpty { "rule-${i + 1}" }
                val on = r.optBoolean("on", true)
                val whenObj = r.optJSONObject("when") ?: continue
                val steps = r.optJSONArray("steps") ?: JSONArray()
                clean.put(JSONObject().put("id", id).put("on", on).put("when", whenObj).put("steps", steps))
            }
            prefs(app).edit().putString(KEY_RULES, clean.toString()).apply()
            rescheduleTimeRules(app)
        }
        if (args.has("meetingSilence")) {
            val on = args.optBoolean("meetingSilence", false)
            prefs(app).edit().putBoolean(KEY_MEETING_ON, on).apply()
            if (on) scheduleMeetingSilence(app) else cancelMeetingSilence(app, restore = true)
        }
        return JSONObject().put("ok", true).put(
            "data",
            JSONObject()
                .put("rules", list(app))
                .put("meetingSilence", meetingSilence(app))
        )
    }

    fun onCar(ctx: Context, on: Boolean) {
        val want = if (on) "on" else "off"
        trigger(ctx.applicationContext) { w -> w.optString("car").equals(want, ignoreCase = true) }
    }

    fun onCharging(ctx: Context, on: Boolean) {
        val want = if (on) "on" else "off"
        trigger(ctx.applicationContext) { w -> w.optString("charging").equals(want, ignoreCase = true) }
    }

    private fun onWifi(ctx: Context, event: String, ssid: String) {
        if (ssid.isBlank()) return
        trigger(ctx.applicationContext) { w ->
            w.optString("wifi").equals(event, ignoreCase = true) &&
                w.optString("ssid").trim().equals(ssid, ignoreCase = true)
        }
    }

    fun onTimeRule(ctx: Context, ruleId: String) {
        val app = ctx.applicationContext
        val rules = list(app)
        var target: JSONObject? = null
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (r.optString("id") == ruleId && r.optBoolean("on", true)) {
                target = r
                break
            }
        }
        rescheduleTimeRules(app)
        if (target != null) runRule(app, target)
    }

    private fun trigger(ctx: Context, matches: (JSONObject) -> Boolean) {
        val rules = list(ctx)
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("on", true)) continue
            val w = r.optJSONObject("when") ?: continue
            if (matches(w)) worker.execute { runRule(ctx, r) }
        }
    }

    private fun runRule(ctx: Context, rule: JSONObject) {
        val id = rule.optString("id", "rule")
        val steps = rule.optJSONArray("steps") ?: return
        if (steps.length() == 0) return
        Telemetry.info("rules", "running local rule: $id")
        Skills.runSteps(ctx, steps, JSONObject(), "rule:$id") { Apps.launch(ctx, it.put("fresh", true)) }
    }

    // ---- Time rules via AlarmManager ----

    private fun rescheduleTimeRules(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(ctx)
        val oldIds = try { JSONArray(p.getString(KEY_ALARM_IDS, "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until oldIds.length()) {
            val id = oldIds.optString(i)
            if (id.isNotEmpty()) am.cancel(timePendingIntent(ctx, id))
        }
        val rules = list(ctx)
        val newIds = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("on", true)) continue
            val w = r.optJSONObject("when") ?: continue
            val hhmm = w.optString("time").trim()
            if (hhmm.isEmpty()) continue
            val nextMs = nextTimeMillis(hhmm, w.optString("days", "daily")) ?: continue
            val id = r.optString("id")
            if (id.isEmpty()) continue
            scheduleAlarm(am, nextMs, timePendingIntent(ctx, id))
            newIds.put(id)
        }
        p.edit().putString(KEY_ALARM_IDS, newIds.toString()).apply()
    }

    private fun timePendingIntent(ctx: Context, ruleId: String): PendingIntent {
        val intent = Intent(ctx, LocalRuleReceiver::class.java)
            .setAction(ACTION_TIME_RULE)
            .putExtra("id", ruleId)
        val code = 900_000 + (ruleId.hashCode() and 0x7FFF)
        return PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun nextTimeMillis(hhmm: String, days: String): Long? {
        val parts = hhmm.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val min = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || min !in 0..59) return null
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val targetTime = LocalTime.of(hour, min)
        val kind = days.trim().lowercase()
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val dow = date.dayOfWeek
            val dayOk = when (kind) {
                "weekdays" -> dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
                "weekends" -> dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                else -> true
            }
            if (!dayOk) continue
            val candidate = ZonedDateTime.of(date, targetTime, zone)
            if (candidate.isAfter(now.plusSeconds(2))) return candidate.toInstant().toEpochMilli()
        }
        return null
    }

    private fun scheduleAlarm(am: AlarmManager, atMs: Long, pi: PendingIntent) {
        val exactOk = Build.VERSION.SDK_INT < 31 || try { am.canScheduleExactAlarms() } catch (_: Exception) { false }
        if (exactOk) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                return
            } catch (_: SecurityException) {}
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (_: Exception) {}
    }

    // ---- Charging receiver ----

    private fun registerCharging(app: Context) {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_POWER_CONNECTED -> onCharging(context, true)
                        Intent.ACTION_POWER_DISCONNECTED -> onCharging(context, false)
                    }
                }
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Telemetry.warn("rules", "charging monitor failed: ${e.message}")
        }
    }

    // ---- Wi-Fi NetworkCallback ----

    private fun registerWifi(app: Context) {
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = if (Build.VERSION.SDK_INT >= 31) {
                object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        handleWifiCaps(app, caps)
                    }
                    override fun onLost(network: Network) {
                        handleWifiLost(app)
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        handleWifiCaps(app, caps)
                    }
                    override fun onLost(network: Network) {
                        handleWifiLost(app)
                    }
                }
            }
            cm.registerNetworkCallback(req, cb)
            // Already off Wi-Fi: the first join is a real join. Leaving wifiReady false would swallow it as the baseline.
            if (!activeIsWifi(cm)) {
                synchronized(this) {
                    if (!wifiReady) {
                        wifiReady = true
                        currentSsid = null
                    }
                }
            }
        } catch (e: Exception) {
            Telemetry.warn("rules", "wifi monitor failed: ${e.message}")
        }
    }

    /** True when the network in use is Wi-Fi. Unknown counts as yes so we do not invent a join. */
    private fun activeIsWifi(cm: ConnectivityManager): Boolean = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Exception) { true }

    private fun hasLocationPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun readSsid(ctx: Context, caps: NetworkCapabilities): String? {
        if (!hasLocationPerm(ctx)) return null
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= 29) {
            caps.transportInfo as? WifiInfo
        } else null
        val raw = info?.ssid ?: try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        } catch (_: Exception) {
            null
        }
        val clean = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (clean.isEmpty() || clean.equals("<unknown ssid>", ignoreCase = true)) return null
        return clean
    }

    @Synchronized
    private fun handleWifiCaps(ctx: Context, caps: NetworkCapabilities) {
        val ssid = readSsid(ctx, caps) ?: return
        if (!wifiReady) {
            wifiReady = true
            currentSsid = ssid
            return
        }
        val prev = currentSsid
        if (ssid != prev) {
            if (prev != null) onWifi(ctx, "leave", prev)
            currentSsid = ssid
            onWifi(ctx, "join", ssid)
        }
    }

    @Synchronized
    private fun handleWifiLost(ctx: Context) {
        val prev = currentSsid
        currentSsid = null
        wifiReady = true
        if (prev != null && hasLocationPerm(ctx)) onWifi(ctx, "leave", prev)
    }

    // ---- Built-in meetingSilence ----

    fun scheduleMeetingSilence(ctx: Context) {
        val app = ctx.applicationContext
        if (!meetingSilence(app)) return
        cancelMeetingSilence(app, restore = false)
        // Reschedule cancelled the end alarms. If the silenced meeting is already over, put the ringer back.
        if (prefs(app).getInt(KEY_MEETING_PREV, -1) != -1 &&
            prefs(app).getLong(KEY_MEETING_UNTIL, 0L) <= System.currentTimeMillis()) {
            exitMeetingSilence(app, force = true)
        }
        val am = app.getSystemService(AlarmManager::class.java) ?: return

        // Schedule next morning refresh at 05:00 local time
        val zone = ZoneId.systemDefault()
        val nowZdt = ZonedDateTime.now(zone)
        var nextMorning = ZonedDateTime.of(LocalDate.now(zone), LocalTime.of(5, 0), zone)
        if (!nextMorning.isAfter(nowZdt.plusMinutes(1))) nextMorning = nextMorning.plusDays(1)
        scheduleAlarm(am, nextMorning.toInstant().toEpochMilli(), meetingRefreshIntent(app))

        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return

        val now = System.currentTimeMillis()
        val endWindow = now + 26L * 3600_000L
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, now - 4L * 3600_000L)
        ContentUris.appendId(uriBuilder, endWindow)
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY
        )
        val codes = JSONArray()
        var idx = 0
        var live = false
        var queried = false
        try {
            app.contentResolver.query(uriBuilder.build(), cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && idx < 20) {
                    val eventId = c.getLong(0)
                    val title = c.getString(1).orEmpty()
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    val loc = c.getString(4).orEmpty()
                    val desc = c.getString(5).orEmpty()
                    val allDay = c.getInt(6) == 1
                    if (allDay || end <= now || end <= begin) continue
                    if (!isMeeting(app, eventId, title, loc, desc)) continue

                    val startCode = 911_000 + idx * 2
                    val endCode = startCode + 1
                    idx++
                    if (begin <= now) {
                        enterMeetingSilence(app, end)
                        live = true
                    } else {
                        scheduleAlarm(am, begin, meetingIntent(app, ACTION_MEETING_START, startCode, end))
                        codes.put(startCode)
                    }
                    scheduleAlarm(am, end, meetingIntent(app, ACTION_MEETING_END, endCode, end))
                    codes.put(endCode)
                }
            }
            queried = true
        } catch (e: Exception) {
            Telemetry.warn("rules", "meetingSilence query failed: ${e.message}")
        }
        prefs(app).edit().putString(KEY_MEETING_CODES, codes.toString()).apply()
        // The old end alarm is gone and nothing is in progress. Put back the mode we changed.
        if (queried && !live && prefs(app).getInt(KEY_MEETING_PREV, -1) != -1) {
            exitMeetingSilence(app, force = true)
        }
    }

    private fun isMeeting(ctx: Context, eventId: Long, title: String, loc: String, desc: String): Boolean {
        if (VIDEO_RE.containsMatchIn("$title $loc $desc")) return true
        return try {
            var count = 0
            ctx.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.ATTENDEE_STATUS),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(0) != CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) count++
                    if (count >= 2) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun enterMeetingSilence(ctx: Context, endMs: Long) {
        val app = ctx.applicationContext
        if (!meetingSilence(app)) return
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val p = prefs(app)
        val ed = p.edit()
        if (p.getInt(KEY_MEETING_PREV, -1) == -1) {
            ed.putInt(KEY_MEETING_PREV, audio.ringerMode)
        }
        val prevUntil = p.getLong(KEY_MEETING_UNTIL, 0L)
        if (endMs > prevUntil) ed.putLong(KEY_MEETING_UNTIL, endMs)
        ed.apply()
        try {
            // Already silent stays silent. Only a normal ringer drops to vibrate.
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
        } catch (_: SecurityException) {}
    }

    fun exitMeetingSilence(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val until = p.getLong(KEY_MEETING_UNTIL, 0L)
        if (!force && until > 0L && System.currentTimeMillis() + 5000L < until) return
        val prev = p.getInt(KEY_MEETING_PREV, -1)
        p.edit().putInt(KEY_MEETING_PREV, -1).putLong(KEY_MEETING_UNTIL, 0L).apply()
        if (prev == -1) return
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        try {
            audio.ringerMode = prev
        } catch (_: SecurityException) {}
    }

    private fun cancelMeetingSilence(ctx: Context, restore: Boolean) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val p = prefs(app)
        val codes = try { JSONArray(p.getString(KEY_MEETING_CODES, "[]")) } catch (_: Exception) { JSONArray() }
        if (am != null) {
            am.cancel(meetingRefreshIntent(app))
            for (i in 0 until codes.length()) {
                val code = codes.optInt(i, -1)
                if (code >= 0) {
                    val action = if (code % 2 == 0) ACTION_MEETING_START else ACTION_MEETING_END
                    am.cancel(meetingIntent(app, action, code, 0L))
                }
            }
        }
        p.edit().putString(KEY_MEETING_CODES, "[]").apply()
        if (restore) exitMeetingSilence(app, force = true)
    }

    private fun meetingRefreshIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, LocalRuleReceiver::class.java).setAction(ACTION_MEETING_REFRESH)
        return PendingIntent.getBroadcast(ctx, CODE_MEETING_REFRESH, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun meetingIntent(ctx: Context, action: String, code: Int, endMs: Long): PendingIntent {
        val i = Intent(ctx, LocalRuleReceiver::class.java).setAction(action).putExtra("endMs", endMs)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

class LocalRuleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val app = ctx.applicationContext
        if (intent.action == LocalRules.ACTION_TIME_RULE) {
            // Rule steps can outlast the broadcast window. Run them on their own thread and release the receiver now.
            val id = intent.getStringExtra("id").orEmpty()
            if (id.isNotEmpty()) {
                Thread({
                    try { LocalRules.onTimeRule(app, id) } catch (e: Exception) {
                        Telemetry.warn("rules", "alarm error: ${e.message}")
                    }
                }, "lumen-rule-time").apply { isDaemon = true }.start()
            }
            pending.finish()
            return
        }
        Thread {
            try {
                when (intent.action) {
                    LocalRules.ACTION_MEETING_START -> LocalRules.enterMeetingSilence(app, intent.getLongExtra("endMs", 0L))
                    LocalRules.ACTION_MEETING_END -> LocalRules.exitMeetingSilence(app)
                    LocalRules.ACTION_MEETING_REFRESH -> LocalRules.scheduleMeetingSilence(app)
                }
            } catch (e: Exception) {
                Telemetry.warn("rules", "alarm error: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Synchronous on-device TextToSpeech helper for {say: "..."} rule steps. */
object Speaker {
    fun say(ctx: Context, text: String): JSONObject {
        val clean = text.trim().take(500)
        if (clean.isEmpty()) return LumenAccess.err("nothing to say")
        val initLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        var ready = false
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                initLatch.countDown()
            }
            if (!initLatch.await(5, TimeUnit.SECONDS) || !ready) {
                return LumenAccess.err("speech unavailable")
            }
            val uid = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == uid) doneLatch.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == uid) doneLatch.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == uid) doneLatch.countDown() }
            })
            val rc = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, uid)
            if (rc != TextToSpeech.SUCCESS) return LumenAccess.err("could not speak")
            doneLatch.await(15, TimeUnit.SECONDS)
            return JSONObject().put("ok", true).put("data", JSONObject().put("said", clean))
        } catch (e: Exception) {
            return LumenAccess.err("speech failed: ${e.message}")
        } finally {
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        }
    }
}
