package ai.lumen.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/**
 * Phone mode: Lumen's server runs inside this app (Node, see NodeRuntime), on 127.0.0.1 at LumenOrigin.HOME.
 * Every app on a phone can reach 127.0.0.1, so the server only answers requests carrying this install's secret,
 * which lives in this app's own cookie store (the page and the app's own requests send it; other apps cannot read it).
 */
object PhoneMode {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mode", Context.MODE_PRIVATE)

    /** "server" (the person's own server over Tailscale), "phone" (everything here), or "" (not chosen yet). */
    fun mode(ctx: Context): String = prefs(ctx).getString("mode", "") ?: ""
    fun choose(ctx: Context, m: String) { prefs(ctx).edit().putString("mode", m).apply() }

    private fun token(ctx: Context): String = prefs(ctx).getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { prefs(ctx).edit().putString("token", it).apply() }

    /** Unpacks the bundled server (once per app version), writes its settings, starts it, and gives the WebView the secret. */
    @Synchronized fun start(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val dir = File(app.filesDir, "server")
        val version = Updater.installed(app).toString()
        val stamp = File(dir, ".version")
        if (!stamp.exists() || stamp.readText() != version) {
            dir.deleteRecursively(); dir.mkdirs()
            app.assets.open("server.zip").use { input -> ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue // never write outside the folder
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            } }
            stamp.writeText(version)
        }
        val data = File(app.filesDir, "lumen-data").apply { mkdirs() }
        val settings = File(app.filesDir, "server-env.json")
        settings.writeText(JSONObject().put("LUMEN_DATA", data.absolutePath).put("PORT", LumenOrigin.PORT.toString())
            .put("LUMEN_LISTEN", "127.0.0.1").put("LUMEN_LOCAL_TOKEN", token(app)).put("LUMEN_DEVICE_KEY", LocalModel.key(app)).put("HOME", app.filesDir.absolutePath)
            .put("TMPDIR", app.cacheDir.absolutePath).toString())
        settings.setReadable(false, false); settings.setReadable(true, true)
        CookieManager.getInstance().apply {
            setCookie(LumenOrigin.HOME, "lumen_local=${token(app)}; Path=/; HttpOnly; SameSite=Strict")
            flush()
        }
        return NodeRuntime.start(app, File(dir, "phone.mjs").absolutePath, mapOf("LUMEN_SETTINGS" to settings.absolutePath))
    }

    /** Set in the background once the server on this phone answers; safe to read on the main thread. */
    @Volatile var up = false

    /** True once the server on this phone answers (a network call: not on the main thread). */
    fun ready(): Boolean = try {
        val c = URL(LumenOrigin.HOME + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 2000
        val ok = c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("lumen"); c.disconnect(); ok
    } catch (_: Exception) { false }

    fun setupDone(): Boolean = try {
        val c = URL(LumenOrigin.HOME + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 2000
        val ok = JSONObject(c.inputStream.bufferedReader().readText()).optBoolean("ready"); c.disconnect(); ok
    } catch (_: Exception) { false }
}

/** Phone mode comes back after a reboot or an app update without the person opening Lumen. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) LumenCore.ensure(ctx)
    }
}

/** Keeps Lumen's server running in phone mode, so jobs, triggers and calls carry on when the app is closed. */
class LumenCore : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("core", "Lumen on this phone", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "core").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Lumen is ready")
            .setContentText("Running on this phone").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        Thread {
            try { PhoneMode.start(this) } catch (e: Throwable) { Telemetry.warn("phone", "could not start: ${e.message}") }
            repeat(120) { if (PhoneMode.ready()) { PhoneMode.up = true; return@Thread }; Thread.sleep(500) } // first start unpacks the server: allow a minute
            Telemetry.warn("phone", "the server on this phone did not answer")
        }.start()
        return START_STICKY
    }

    companion object {
        fun ensure(ctx: Context) {
            if (PhoneMode.mode(ctx) != "phone") return
            try { ctx.startForegroundService(Intent(ctx, LumenCore::class.java)) } catch (e: Exception) { Telemetry.warn("phone", "service: ${e.message}") }
        }
    }
}
