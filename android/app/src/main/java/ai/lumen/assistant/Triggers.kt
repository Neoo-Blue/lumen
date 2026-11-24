package ai.lumen.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notifications in and out. In: a private log on the phone (for "search my life") and, for jobs started by a
 * notification, only the matching ones are sent to Lumen. Out: a finished job shows up as a notification.
 */
object Triggers {
    @Volatile private var rules = JSONArray()
    @Volatile private var fetched = 0L

    private fun cookie() = CookieManager.getInstance().getCookie(LumenOrigin.HOME) ?: ""

    private fun refresh() {
        if (System.currentTimeMillis() - fetched < 10 * 60_000) return
        fetched = System.currentTimeMillis()
        try {
            val c = URL(LumenOrigin.HOME + "/triggers").openConnection() as HttpURLConnection
            c.setRequestProperty("cookie", cookie()); c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 200) rules = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("triggers") ?: JSONArray()
        } catch (_: Exception) {}
    }
    fun forget() { fetched = 0 }

    fun posted(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == ctx.packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        Thread {
            log(ctx, JSONObject().put("at", java.time.Instant.ofEpochMilli(sbn.postTime).toString()).put("app", app).put("pkg", sbn.packageName).put("title", title.take(200)).put("text", text.take(600)))
            refresh()
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                val want = r.optString("app").lowercase(); val words = r.optString("contains").lowercase()
                if (want.isEmpty()) continue // a rule must name an app
                val appOk = app.lowercase() == want || sbn.packageName.lowercase() == want || (want.contains('.') && sbn.packageName.lowercase().startsWith(want))
                val wordsOk = words.isEmpty() || "$title $text".lowercase().contains(words)
                if (appOk && wordsOk) send(r.optString("id"), app, title, text, sbn.packageName)
            }
        }.start()
    }

    private fun send(id: String, app: String, title: String, text: String, pkg: String = "") = try {
        val c = URL(LumenOrigin.HOME + "/trigger").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("cookie", cookie())
        c.outputStream.use { it.write(JSONObject().put("id", id).put("app", app).put("pkg", pkg).put("title", title.take(300)).put("text", text.take(400)).toString().toByteArray()) }
        Telemetry.info("trigger", "job $id started by a notification from $app (${c.responseCode})"); c.disconnect()
    } catch (e: Exception) { Telemetry.warn("trigger", "could not start job: ${e.message}") }

    // ---- a private notification history on the phone, a week deep, for life search ----
    private fun file(ctx: Context) = File(ctx.filesDir, "notif-log.jsonl")
    @Synchronized private fun log(ctx: Context, o: JSONObject) {
        val f = file(ctx); f.appendText(o.toString() + "\n")
        if (f.length() > 400_000) { val cut = System.currentTimeMillis() - 8L * 24 * 3600_000
            val keep = f.readLines().filter { l -> try { java.time.Instant.parse(JSONObject(l).optString("at")).toEpochMilli() >= cut } catch (_: Exception) { false } }.takeLast(3000)
            f.writeText(keep.joinToString("\n", postfix = "\n")) }
    }
    @Synchronized fun search(ctx: Context, words: List<String>, limit: Int): JSONArray {
        val out = JSONArray(); val f = file(ctx); if (!f.exists()) return out
        val cutoff = System.currentTimeMillis() - 8L * 24 * 3600_000
        for (line in f.readLines().asReversed()) {
            if (out.length() >= limit) break
            val o = try { JSONObject(line) } catch (_: Exception) { continue }
            if (try { java.time.Instant.parse(o.optString("at")).toEpochMilli() < cutoff } catch (_: Exception) { false }) break
            val hay = "${o.optString("app")} ${o.optString("title")} ${o.optString("text")}".lowercase()
            if (words.all { hay.contains(it) }) out.put(o)
        }
        return out
    }

    // ---- out: a finished job, as a notification that opens its receipt ----
    fun notify(ctx: Context, title: String, text: String, open: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("jobs", "Lumen jobs", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, open.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse("ai.lumen.assistant://open?what=" + Uri.encode(open))).setClass(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "jobs") else @Suppress("DEPRECATION") Notification.Builder(ctx)
        nm.notify(open.hashCode(), b.setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(tap).setAutoCancel(true).build())
    }
}
