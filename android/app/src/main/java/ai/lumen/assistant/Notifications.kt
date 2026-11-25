package ai.lumen.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/** Shared notification preferences stored in the main "lumen" SharedPreferences. */
object NotifPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    fun otpCopy(c: Context) = prefs(c).getBoolean("otpCopy", true)
    fun setOtpCopy(c: Context, v: Boolean) = prefs(c).edit().putBoolean("otpCopy", v).apply()
    fun callCard(c: Context) = prefs(c).getBoolean("callCard", true)
    fun setCallCard(c: Context, v: Boolean) = prefs(c).edit().putBoolean("callCard", v).apply()
}

/** Shared helpers for filtering noisy notifications, extracting fields, and building open links. */
object NotifHygiene {
    data class Parsed(
        val key: String,
        val app: String,
        val appLabel: String,
        val title: String,
        val who: String,
        val rawText: String,
        val redactedTitle: String,
        val redactedText: String,
        val postTime: Long
    )

    fun isNoise(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return true
        val f = n.flags
        if ((f and Notification.FLAG_ONGOING_EVENT) != 0) return true
        if ((f and Notification.FLAG_GROUP_SUMMARY) != 0) return true
        if ((f and Notification.FLAG_FOREGROUND_SERVICE) != 0) return true
        val c = n.category
        if (c == Notification.CATEGORY_PROGRESS || c == Notification.CATEGORY_TRANSPORT || c == Notification.CATEGORY_NAVIGATION) return true
        val ex = n.extras
        if (ex != null) {
            if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return true
            if (ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return true
            if (ex.getInt(Notification.EXTRA_PROGRESS, 0) > 0) return true
        }
        return false
    }

    fun parse(ctx: Context, sbn: StatusBarNotification): Parsed {
        val ex = sbn.notification?.extras
        val label = appLabel(ctx, sbn.packageName)
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conv = ex?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extractText(ex)
        val (redTitle, redText) = OtpGuard.redact(title, rawText)
        val redConv = if (conv.isEmpty()) "" else OtpGuard.redact(conv, rawText).first
        val who = when {
            redConv.isNotEmpty() && redTitle.isNotEmpty() && !redTitle.contains(redConv, true) -> "$redConv: $redTitle"
            redTitle.isNotEmpty() -> redTitle
            redConv.isNotEmpty() -> redConv
            else -> label
        }
        return Parsed(sbn.key, sbn.packageName, label, title, who, rawText, redTitle, redText, sbn.postTime)
    }

    private fun extractText(ex: Bundle?): String {
        if (ex == null) return ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (big.isNotEmpty()) return big
        val plain = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (plain.isNotEmpty()) return plain
        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            val last = msgs.lastOrNull() as? Bundle
            val m = last?.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (!m.isNullOrEmpty()) return m
        }
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            val last = lines.lastOrNull()?.toString()?.trim().orEmpty()
            if (last.isNotEmpty()) return last
        }
        return ""
    }

    fun openIntent(ctx: Context, open: String, reqCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            reqCode,
            Intent(Intent.ACTION_VIEW, Uri.parse("ai.lumen.assistant://open?what=" + Uri.encode(open)))
                .setClass(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** Detects one-time sign-in codes, copies them sensitively, shows a quiet 60s notice, clears after 2m, and redacts stored text. */
object OtpGuard {
    private const val REDACTED = "\u2022\u2022\u2022\u2022"
    private const val CHANNEL = "otp"
    private val KEYWORD = Regex("(?i)(?:^|[^a-z])(code|verification|otp|passcode|one[- ]?time)(?:$|[^a-z])|验证码|校验码")
    private val TOKEN = Regex("(?<![0-9])(\\d{3,4}-\\d{3,4}|\\d{4,8})(?![0-9])")
    private val YEAR = Regex("^(19|20)\\d{2}$")

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var lastCode = ""
    private var lastAt = 0L
    private var clipGen = 0L

    private fun validMatches(s: String): List<MatchResult> = TOKEN.findAll(s).filter { m ->
        val start = m.range.first
        val end = m.range.last + 1
        val prev = if (start > 0) s[start - 1] else ' '
        val prev2 = if (start > 1) s[start - 2] else ' '
        val next = if (end < s.length) s[end] else ' '
        val next2 = if (end + 1 < s.length) s[end + 1] else ' '
        if (prev == ':' || next == ':') return@filter false
        if (prev == '-' && prev2.isDigit()) return@filter false
        if (next == '-' && next2.isDigit()) return@filter false
        val digits = m.value.replace("-", "")
        digits.length in 4..8
    }.toList()

    fun extract(title: String, text: String): String? {
        val full = "$title $text"
        val kw = KEYWORD.find(full) ?: return null
        val matches = validMatches(full)
        if (matches.isEmpty()) return null
        val kwPos = kw.range.first
        val nonYears = matches.filter { !YEAR.matches(it.value) }
        val pool = if (nonYears.isNotEmpty()) nonYears else matches
        val best = pool.minByOrNull { kotlin.math.abs(it.range.first - kwPos) } ?: return null
        return best.value.replace("-", "")
    }

    fun redact(title: String, text: String): Pair<String, String> {
        val full = "$title $text"
        if (!KEYWORD.containsMatchIn(full)) return title to text
        return redactPart(title) to redactPart(text)
    }

    private fun redactPart(s: String): String {
        if (s.isEmpty()) return s
        val matches = validMatches(s)
        if (matches.isEmpty()) return s
        val sb = StringBuilder(s)
        for (m in matches.asReversed()) {
            sb.replace(m.range.first, m.range.last + 1, REDACTED)
        }
        return sb.toString()
    }

    fun handle(ctx: Context, appLabel: String, title: String, text: String) {
        if (!NotifPrefs.otpCopy(ctx)) return
        val code = extract(title, text) ?: return
        val now = System.currentTimeMillis()
        val gen: Long
        synchronized(lock) {
            if (code == lastCode && now - lastAt < 30_000L) return
            lastCode = code
            lastAt = now
            gen = ++clipGen
        }
        val tag = "lumen-otp-$gen"
        val appCtx = ctx.applicationContext
        main.post {
            try {
                val cm = appCtx.getSystemService(ClipboardManager::class.java) ?: return@post
                val clip = ClipData.newPlainText(tag, code)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                cm.setPrimaryClip(clip)
                postQuietNotice(appCtx, appLabel)
                main.postDelayed({ clearIfUnchanged(appCtx, gen, code) }, 120_000L)
            } catch (_: Exception) {}
        }
    }

    private fun clearIfUnchanged(ctx: Context, gen: Long, code: String) {
        synchronized(lock) { if (gen != clipGen) return }
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
            // Android 10 and later hide the clipboard from a background app, so a read often comes back null.
            // Clear when we cannot read it, or when it still holds our code. Skip only when we can read something else.
            val cur = try { cm.primaryClip?.getItemAt(0)?.text?.toString() } catch (_: Exception) { null }
            if (cur != null && cur != code) return
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    private fun postQuietNotice(ctx: Context, appLabel: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in codes", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                })
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Code from $appLabel copied")
                .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60_000L)
            nm.notify(4201, b.build())
        } catch (_: Exception) {}
    }
}

/** In-memory 2h cache of active notification RemoteInput reply actions. */
object ReplyCache {
    private const val TTL_MS = 2L * 3600_000L
    private const val MAX_ITEMS = 20

    private class Entry(
        val key: String,
        val app: String,
        val appLabel: String,
        val who: String,
        val text: String,
        val atMs: Long,
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>
    )

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>()

    fun seed(ctx: Context, active: Array<StatusBarNotification>?) {
        val sorted = active?.sortedBy { it.postTime } ?: return
        for (sbn in sorted) capture(ctx, sbn)
    }

    fun capture(ctx: Context, sbn: StatusBarNotification, parsed: NotifHygiene.Parsed? = null) {
        if (sbn.packageName == ctx.packageName) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val action = findReplyAction(n) ?: return
        val pi = action.actionIntent ?: return
        val inputs = action.remoteInputs ?: return
        if (inputs.isEmpty()) return
        val p = parsed ?: NotifHygiene.parse(ctx, sbn)
        val entry = Entry(
            key = sbn.key,
            app = p.app,
            appLabel = p.appLabel,
            who = p.who,
            text = p.redactedText.take(160),
            atMs = p.postTime,
            actionIntent = pi,
            remoteInputs = inputs
        )
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            map.remove(entry.key)
            map[entry.key] = entry
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        n.actions?.firstOrNull { it.actionIntent != null && !it.remoteInputs.isNullOrEmpty() }?.let { return it }
        return try {
            Notification.WearableExtender(n).actions.firstOrNull { it.actionIntent != null && !it.remoteInputs.isNullOrEmpty() }
        } catch (_: Exception) { null }
    }

    private fun pruneLocked(now: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.atMs > TTL_MS) it.remove()
        }
    }

    fun targets(ctx: Context): JSONObject {
        if (!NotificationAccess.enabled(ctx)) return LumenAccess.err("needs_access")
        val list = synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            map.values.sortedByDescending { it.atMs }.take(MAX_ITEMS)
        }
        val out = JSONArray()
        for (e in list) {
            out.put(
                JSONObject()
                    .put("key", e.key)
                    .put("app", e.app)
                    .put("appLabel", e.appLabel)
                    .put("who", e.who)
                    .put("text", e.text)
                    .put("at", Instant.ofEpochMilli(e.atMs).toString())
            )
        }
        return JSONObject().put("ok", true).put("data", out)
    }

    fun reply(ctx: Context, args: JSONObject): JSONObject {
        if (!NotificationAccess.enabled(ctx)) return LumenAccess.err("needs_access")
        val text = args.optString("text").trim()
        if (text.isEmpty()) return LumenAccess.err("bad_args")
        val key = args.optString("key").trim()
        val who = args.optString("who").trim()
        val app = args.optString("app").trim()
        if (key.isEmpty() && who.isEmpty()) return LumenAccess.err("say who to reply to") // never a guess at the newest chat

        val target = synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            if (key.isNotEmpty()) {
                map[key]
            } else {
                val appLower = app.lowercase()
                val whoLower = who.lowercase()
                val candidates = map.values.filter { e ->
                    appLower.isEmpty() ||
                        e.app.lowercase() == appLower ||
                        e.appLabel.lowercase() == appLower ||
                        e.app.lowercase().contains(appLower) ||
                        e.appLabel.lowercase().contains(appLower)
                }
                if (whoLower.isEmpty()) {
                    candidates.maxByOrNull { it.atMs }
                } else {
                    var best: Entry? = null
                    var bestScore = 0
                    for (e in candidates) {
                        val w = e.who.lowercase()
                        val score = when {
                            w == whoLower -> 100
                            w.startsWith(whoLower) -> 80
                            w.contains(whoLower) -> 60
                            else -> 0
                        }
                        if (score > bestScore || (score > 0 && score == bestScore && e.atMs > (best?.atMs ?: 0L))) {
                            bestScore = score
                            best = e
                        }
                    }
                    best
                }
            }
        } ?: return LumenAccess.err(
            when {
                who.isNotEmpty() -> "no recent message from $who to reply to"
                app.isNotEmpty() -> "no recent message on $app to reply to"
                else -> "no recent message to reply to"
            }
        )

        val fill = Intent()
        val results = Bundle()
        for (ri in target.remoteInputs) {
            results.putCharSequence(ri.resultKey, text)
        }
        RemoteInput.addResultsToIntent(target.remoteInputs, fill, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            target.actionIntent.send(ctx, 0, fill)
            JSONObject().put("ok", true).put(
                "data",
                JSONObject().put("sent", true).put("who", target.who).put("app", target.app)
            )
        } catch (_: PendingIntent.CanceledException) {
            synchronized(lock) { map.remove(target.key) }
            LumenAccess.err("reply action expired")
        } catch (e: Exception) {
            LumenAccess.err("could not send reply: ${e.message}")
        }
    }
}

/** Notification triage: quiet apps held for scheduled digests unless sender matches a VIP. */
object NotifTriage {
    private const val PREF_KEY = "triage"
    private const val LAST_DIGEST_KEY = "triageLastDigestAt"
    private const val MAX_HELD = 200
    private const val ALARM_REQ = 4101
    private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")

    data class Config(val vips: List<String>, val quiet: List<String>, val digestTimes: List<String>)

    private fun prefs(c: Context) = c.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    private fun heldFile(c: Context) = File(c.filesDir, "notif-held.jsonl")

    fun config(ctx: Context): Config {
        val raw = prefs(ctx).getString(PREF_KEY, null)
        if (raw.isNullOrBlank()) return Config(emptyList(), emptyList(), listOf("12:30", "18:00"))
        return try {
            val o = JSONObject(raw)
            Config(
                vips = stringList(o.optJSONArray("vips")),
                quiet = stringList(o.optJSONArray("quiet")),
                digestTimes = if (o.has("digestTimes")) parseTimes(o.optJSONArray("digestTimes")) else listOf("12:30", "18:00")
            )
        } catch (_: Exception) {
            Config(emptyList(), emptyList(), listOf("12:30", "18:00"))
        }
    }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val v = a.optString(i).trim()
            if (v.isNotEmpty() && v !in out) out.add(v)
        }
        return out
    }

    private fun parseTimes(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val m = TIME_RE.matchEntire(a.optString(i).trim()) ?: continue
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val min = m.groupValues[2].toIntOrNull() ?: continue
            if (h in 0..23 && min in 0..59) {
                val fmt = String.format(java.util.Locale.US, "%02d:%02d", h, min)
                if (fmt !in out) out.add(fmt)
            }
        }
        return out.sorted()
    }

    private fun toJson(c: Config): JSONObject = JSONObject()
        .put("vips", JSONArray(c.vips))
        .put("quiet", JSONArray(c.quiet))
        .put("digestTimes", JSONArray(c.digestTimes))

    fun handle(ctx: Context, args: JSONObject): JSONObject {
        val setObj = args.optJSONObject("set")
        var cur = config(ctx)
        if (setObj != null) {
            cur = Config(
                vips = if (setObj.has("vips")) stringList(setObj.optJSONArray("vips")) else cur.vips,
                quiet = if (setObj.has("quiet")) stringList(setObj.optJSONArray("quiet")) else cur.quiet,
                digestTimes = if (setObj.has("digestTimes")) parseTimes(setObj.optJSONArray("digestTimes")) else cur.digestTimes
            )
            prefs(ctx).edit().putString(PREF_KEY, toJson(cur).toString()).apply()
            scheduleNext(ctx)
        }
        return JSONObject().put("ok", true).put("data", toJson(cur))
    }

    fun shouldHold(ctx: Context, pkg: String, who: String, title: String): Boolean {
        val c = config(ctx)
        if (pkg !in c.quiet) return false
        val isVip = c.vips.any { v ->
            v.isNotBlank() && (who.contains(v, ignoreCase = true) || title.contains(v, ignoreCase = true))
        }
        return !isVip
    }

    @Synchronized
    fun recordHeld(ctx: Context, p: NotifHygiene.Parsed) {
        val f = heldFile(ctx)
        val entry = JSONObject()
            .put("app", p.app)
            .put("appLabel", p.appLabel)
            .put("who", p.who.take(120))
            .put("text", p.redactedText.take(200))
            .put("at", Instant.ofEpochMilli(p.postTime).toString())
        try {
            val lines = if (f.exists()) f.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
            lines.add(entry.toString())
            val keep = if (lines.size > MAX_HELD) lines.takeLast(MAX_HELD) else lines
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun readHeld(ctx: Context): JSONArray {
        val out = JSONArray()
        val f = heldFile(ctx)
        if (!f.exists()) return out
        val lines = try { f.readLines() } catch (_: Exception) { return out }
        for (line in lines.asReversed()) {
            if (out.length() >= MAX_HELD) break
            val o = try { JSONObject(line) } catch (_: Exception) { continue }
            out.put(o)
        }
        return out
    }

    fun digest(ctx: Context): JSONObject {
        val held = readHeld(ctx)
        val recent = Triggers.recentNonHeld(ctx, 12L * 3600_000L, 40)
        return JSONObject().put("ok", true).put("data", JSONObject().put("held", held).put("recent", recent))
    }

    fun scheduleNext(ctx: Context) {
        val appCtx = ctx.applicationContext
        val am = appCtx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            appCtx,
            ALARM_REQ,
            Intent(appCtx, DigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val times = config(appCtx).digestTimes
        if (times.isEmpty()) {
            am.cancel(pi)
            return
        }
        val now = ZonedDateTime.now()
        val floor = now.plusSeconds(30)
        var soonest: ZonedDateTime? = null
        for (t in times) {
            val m = TIME_RE.matchEntire(t) ?: continue
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            var candidate = now.withHour(h).withMinute(min).withSecond(0).withNano(0)
            if (!candidate.isAfter(floor)) candidate = candidate.plusDays(1)
            if (soonest == null || candidate.isBefore(soonest)) soonest = candidate
        }
        val nextMs = soonest?.toInstant()?.toEpochMilli() ?: return
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pi)
        } catch (_: Exception) {
            try { am.set(AlarmManager.RTC_WAKEUP, nextMs, pi) } catch (_: Exception) {}
        }
    }

    fun postDigest(ctx: Context) {
        val held = readHeld(ctx)
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val since = p.getLong(LAST_DIGEST_KEY, now - 24L * 3600_000L)
        p.edit().putLong(LAST_DIGEST_KEY, now).apply()
        val counts = LinkedHashMap<String, Int>()
        var total = 0
        for (i in 0 until held.length()) {
            val o = held.optJSONObject(i) ?: continue
            val atMs = try { Instant.parse(o.optString("at")).toEpochMilli() } catch (_: Exception) { 0L }
            if (atMs <= since) continue
            total++
            val label = o.optString("appLabel").ifEmpty { o.optString("app") }
            counts[label] = (counts[label] ?: 0) + 1
        }
        if (total == 0) return
        val parts = counts.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} ${it.value}" }
        val summary = "$total held: $parts"
        Triggers.notify(ctx, "Notification digest", summary, "ask:What did I miss?")
    }
}

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val appCtx = ctx.applicationContext
        NotifTriage.postDigest(appCtx)
        NotifTriage.scheduleNext(appCtx)
    }
}

/** Driving mode messaging announcements: TTS sender + app (never body), rate-limited, plus heads-up Read/Reply actions. */
object DrivingAnnouncer {
    private const val CHANNEL = "driving_msg"
    private const val RATE_LIMIT_MS = 120_000L
    private val CHAT_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.tencent.mm",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.facebook.mlite",
        "com.instagram.android",
        "com.Slack",
        "com.discord",
        "jp.naver.line.android"
    )

    private val lock = Any()
    private val lastBySender = HashMap<String, Long>()
    private val pendingSpeech = ArrayList<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun onMessage(ctx: Context, sbn: StatusBarNotification, p: NotifHygiene.Parsed) {
        if (!Driving.on(ctx)) return
        if (sbn.packageName == ctx.packageName) return
        val isMsg = sbn.notification?.category == Notification.CATEGORY_MESSAGE || sbn.packageName in CHAT_PACKAGES
        if (!isMsg) return
        val who = p.who.ifBlank { p.appLabel }
        val senderKey = "${p.app}:${who.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prev = lastBySender[senderKey] ?: 0L
            if (now - prev < RATE_LIMIT_MS) return
            lastBySender[senderKey] = now
            if (lastBySender.size > 64) {
                val cut = now - 600_000L
                lastBySender.entries.removeIf { it.value < cut }
            }
        }
        speak(ctx.applicationContext, "Message from $who on ${p.appLabel}")
        postHeadsUp(ctx.applicationContext, senderKey, who, p.appLabel)
    }

    private fun speak(ctx: Context, phrase: String) {
        synchronized(lock) {
            if (ttsReady && tts != null) {
                tts?.speak(phrase, TextToSpeech.QUEUE_ADD, null, "drv-${System.currentTimeMillis()}")
                return
            }
            if (pendingSpeech.size < 5) pendingSpeech.add(phrase)
            if (tts != null) return
            // 1 ready, -1 failed, 0 still starting. A failed engine is shut down and dropped so the next message can try again.
            var engine: TextToSpeech? = null
            var initState = 0
            engine = TextToSpeech(ctx) { status ->
                synchronized(lock) {
                    if (status == TextToSpeech.SUCCESS) {
                        initState = 1
                        val target = engine ?: tts
                        if (target != null) {
                            tts = target
                            ttsReady = true
                            target.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            for (line in pendingSpeech) {
                                target.speak(line, TextToSpeech.QUEUE_ADD, null, "drv-${System.currentTimeMillis()}")
                            }
                            pendingSpeech.clear()
                        }
                    } else {
                        initState = -1
                        ttsReady = false
                        try { (engine ?: tts)?.shutdown() } catch (_: Exception) {}
                        tts = null
                        pendingSpeech.clear()
                    }
                }
            }
            if (initState < 0) {
                try { engine?.shutdown() } catch (_: Exception) {}
                tts = null
                ttsReady = false
            } else {
                tts = engine
                if (initState > 0) ttsReady = true
            }
        }
    }

    private fun postHeadsUp(ctx: Context, senderKey: String, who: String, appLabel: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Driving messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val readPi = NotifHygiene.openIntent(ctx, "ask:Read me the message from $who", ("drv-read:$senderKey").hashCode())
            val replyPi = NotifHygiene.openIntent(ctx, "prefill:Reply to $who: ", ("drv-reply:$senderKey").hashCode())
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            @Suppress("DEPRECATION")
            val readAct = Notification.Action.Builder(0, "Read it", readPi).build()
            @Suppress("DEPRECATION")
            val replyAct = Notification.Action.Builder(0, "Reply", replyPi).build()
            nm.notify(
                ("drv:$senderKey").hashCode(),
                b.setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Message from $who")
                    .setContentText("New message on $appLabel")
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentIntent(readPi)
                    .setAutoCancel(true)
                    .addAction(readAct)
                    .addAction(replyAct)
                    .build()
            )
        } catch (_: Exception) {}
    }
}

/** Before a call: resolves caller contact, gathers last 3 local items, shows passive top card or heads-up notice. */
object CallCard {
    private const val CHANNEL = "call_card"
    private const val NOTIF_ID = 4301
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-call").apply { isDaemon = true } }
    private val lock = Any()
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var shownForCall = false
    private var lastTarget = ""
    private var lastShownAt = 0L
    private var callback: Any? = null

    fun register(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        registerApi31(ctx.applicationContext)
    }

    fun unregister(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        unregisterApi31(ctx.applicationContext)
    }

    @RequiresApi(31)
    private fun registerApi31(ctx: Context) {
        synchronized(lock) {
            if (callback != null) return
            val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onState(ctx, state, null)
                }
            }
            try {
                tm.registerTelephonyCallback(worker, cb)
                callback = cb
            } catch (_: Exception) {}
        }
    }

    @RequiresApi(31)
    private fun unregisterApi31(ctx: Context) {
        synchronized(lock) {
            val cb = callback as? TelephonyCallback ?: return
            callback = null
            try { ctx.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) } catch (_: Exception) {}
        }
    }

    fun onState(ctx: Context, state: Int, number: String?) {
        val appCtx = ctx.applicationContext
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            synchronized(lock) {
                lastState = state
                shownForCall = false
                lastTarget = ""
            }
            LumenAccess.instance?.sheet?.hideCard()
            return
        }
        if (!NotifPrefs.callCard(appCtx)) {
            synchronized(lock) { lastState = state }
            return
        }
        val num = number?.trim().orEmpty()
        val shouldRun: Boolean
        synchronized(lock) {
            val entering = state == TelephonyManager.CALL_STATE_RINGING ||
                (state == TelephonyManager.CALL_STATE_OFFHOOK && (lastState == TelephonyManager.CALL_STATE_IDLE || (!shownForCall && num.isNotEmpty())))
            lastState = state
            val now = System.currentTimeMillis()
            shouldRun = entering && num.isNotEmpty() && (!shownForCall || (num != lastTarget && now - lastShownAt > 2000L))
            if (shouldRun) {
                shownForCall = true
                lastTarget = num
                lastShownAt = now
            }
        }
        if (shouldRun) worker.execute { showForNumber(appCtx, num, null) }
    }

    /** Fallback when dialer posts a call notification while a call is active and PHONE_STATE had no number. */
    fun onCallNotification(ctx: Context, sbn: StatusBarNotification) {
        if (!NotifPrefs.callCard(ctx)) return
        if (sbn.notification?.category != Notification.CATEGORY_CALL) return
        val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return
        val lower = title.lowercase()
        if (lower in setOf("ongoing call", "incoming call", "dialing", "calling", "phone", "call")) return
        val shouldRun: Boolean
        synchronized(lock) {
            if (lastState == TelephonyManager.CALL_STATE_IDLE || shownForCall) return
            shownForCall = true
            lastTarget = title
            lastShownAt = System.currentTimeMillis()
            shouldRun = true
        }
        if (shouldRun) worker.execute {
            val digits = title.filter { it.isDigit() }
            if (digits.length >= 7) showForNumber(ctx.applicationContext, title, null)
            else showForNumber(ctx.applicationContext, "", title)
        }
    }

    private fun lookupName(ctx: Context, number: String): String {
        if (number.isBlank()) return ""
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0)?.trim().orEmpty() else "" } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun showForNumber(ctx: Context, number: String, fallbackName: String?) {
        val resolved = lookupName(ctx, number).ifEmpty { fallbackName.orEmpty() }
        val displayName = resolved.ifEmpty { number }
        if (displayName.isBlank()) return
        val lines = gatherRecent(ctx, resolved, number)
        val shown = lines.ifEmpty { listOf("No recent notifications or texts found") }
        val openWhat = "ask:Brief me on $displayName"
        val sheetShown = LumenAccess.instance?.sheet?.showCard(displayName, shown, openWhat) == true
        if (!sheetShown) postHeadsUp(ctx, displayName, shown, openWhat)
    }

    private data class Item(val atMs: Long, val text: String)

    private fun gatherRecent(ctx: Context, name: String, number: String): List<String> {
        val found = ArrayList<Item>()
        val digits = number.filter { it.isDigit() }.takeLast(7)
        val nameLower = name.lowercase().trim()

        // 1. Recent notifications in the listener's in-memory store
        val active = NotificationStore.recent(100)
        for (i in 0 until active.length()) {
            val o = active.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val text = o.optString("text")
            val app = o.optString("app")
            val hay = "$title $text".lowercase()
            val match = (nameLower.length >= 2 && hay.contains(nameLower)) ||
                (digits.length >= 7 && hay.filter { it.isDigit() }.contains(digits))
            if (match && text.isNotBlank()) {
                val at = parseMs(o.optString("time"))
                found.add(Item(at, "$app: ${text.take(90)}"))
            }
        }

        // 2. Local lifeSearch (SMS threads and notification history) without touching any server
        val queries = LinkedHashSet<String>()
        if (name.length >= 2) queries.add(name)
        if (digits.length >= 7) queries.add(digits)
        for (q in queries) {
            val res = try { Powers.lifeSearch(ctx, q, "", "", 6) } catch (_: Exception) { continue }
            val texts = res.optJSONArray("texts")
            if (texts != null) {
                for (i in 0 until texts.length()) {
                    val o = texts.optJSONObject(i) ?: continue
                    val body = o.optString("text").trim()
                    if (body.isEmpty()) continue
                    val dir = if (o.optBoolean("sent")) "You (SMS)" else "SMS"
                    found.add(Item(parseMs(o.optString("at")), "$dir: ${body.take(90)}"))
                }
            }
            val notifs = res.optJSONArray("notifications")
            if (notifs != null) {
                for (i in 0 until notifs.length()) {
                    val o = notifs.optJSONObject(i) ?: continue
                    val body = o.optString("text").trim()
                    if (body.isEmpty()) continue
                    val app = o.optString("app").ifEmpty { "Notification" }
                    found.add(Item(parseMs(o.optString("at")), "$app: ${body.take(90)}"))
                }
            }
        }

        // Deduplicate and take top 3 newest
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (item in found.sortedByDescending { it.atMs }) {
            val key = item.text.lowercase().replace(Regex("\\s+"), " ").take(60)
            if (seen.add(key)) {
                out.add(item.text)
                if (out.size >= 3) break
            }
        }
        return out
    }

    private fun parseMs(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }

    private fun postHeadsUp(ctx: Context, name: String, lines: List<String>, openWhat: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Call brief", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val briefPi = NotifHygiene.openIntent(ctx, openWhat, ("call-brief:$name").hashCode())
            val body = lines.joinToString("\n")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            @Suppress("DEPRECATION")
            val act = Notification.Action.Builder(0, "Brief me", briefPi).build()
            b.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(name)
                .setContentText(lines.first())
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(briefPi)
                .setAutoCancel(true)
                .addAction(act)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(30_000L)
            nm.notify(NOTIF_ID, b.build())
        } catch (_: Exception) {}
    }
}

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        CallCard.onState(ctx, state, number)
    }
}
