package ai.lumen.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Lumen updates itself from the project's GitHub releases: checks version.json, downloads the build, verifies its hash, hands it to Android. */
object Updater {
    private const val RELEASES = "https://github.com/Neoo-Blue/lumen/releases/"
    private const val INFO = RELEASES + "latest/download/version.json"

    fun installed(ctx: Context): Long = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    } catch (_: Exception) { 0 }

    private fun latest(): JSONObject? = try {
        val c = URL(INFO).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
    } catch (_: Exception) { null }

    /** Tell the page when a newer build is out, so it can offer it. */
    fun check(activity: MainActivity) = Thread {
        val v = latest() ?: return@Thread
        if (v.optLong("code") <= installed(activity)) return@Thread
        val info = JSONObject().put("version", v.optString("version")).put("notes", v.optString("notes")).put("current", installed(activity))
        activity.runOnUiThread { activity.page("window.__lumenUpdate && window.__lumenUpdate(" + info + ")") }
    }.start()

    /** Download, verify and install. Progress goes to the page; returns ok, or why not. */
    @Synchronized fun install(activity: MainActivity): JSONObject {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.runOnUiThread { try { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))) } catch (_: Exception) {} }
            return LumenAccess.err("allow_installs: switch on \"Allow from this source\" for Lumen on the page that opened, then tap Update again")
        }
        val v = latest() ?: return LumenAccess.err("could not reach the update")
        if (v.optLong("code") <= installed(activity)) return JSONObject().put("ok", true).put("data", JSONObject().put("upToDate", true))
        if (!v.optString("url").startsWith(RELEASES + "download/")) return LumenAccess.err("the update address is not the project's own releases")
        val file = File(activity.cacheDir, "update.apk")
        try {
            val c = URL(v.getString("url")).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val total = c.contentLengthLong.coerceAtLeast(1)
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L; var shown = -1
            c.inputStream.use { i -> file.outputStream().use { o ->
                val buf = ByteArray(65536)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); got += n
                    val pct = (got * 100 / total).toInt(); if (pct != shown && pct % 5 == 0) { shown = pct; activity.runOnUiThread { activity.page("window.__lumenUpdateProgress && window.__lumenUpdateProgress($pct)") } } }
            } }
            if (md.digest().joinToString("") { "%02x".format(it) } != v.optString("sha256")) { file.delete(); return LumenAccess.err("the download did not match its fingerprint; nothing was installed") }
            val pi = activity.packageManager.packageInstaller
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(activity.packageName) })
            pi.openSession(id).use { s ->
                s.openWrite("lumen.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val status = PendingIntent.getBroadcast(activity, 7, Intent(activity, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(status.intentSender)
            }
            Telemetry.info("update", "installing ${v.optString("version")}")
            return JSONObject().put("ok", true).put("data", JSONObject().put("installing", v.optString("version")))
        } catch (e: Exception) {
            file.delete(); return LumenAccess.err("update failed: ${e.message}")
        }
    }
}

/** Android asks the person to confirm the install; this opens that screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { try { ctx.startActivity(it) } catch (_: Exception) {} }
            }
            PackageInstaller.STATUS_SUCCESS -> Telemetry.info("update", "installed")
            else -> Telemetry.warn("update", "install did not finish: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
