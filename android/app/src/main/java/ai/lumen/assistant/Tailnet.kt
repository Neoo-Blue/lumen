package ai.lumen.assistant

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tailscale inside the app (no separate Tailscale app, no VPN slot): joins the person's private network, finds their
 * Lumen server on it, and opens the bridge at LumenOrigin.HOME that carries the page's requests there.
 */
object Tailnet {
    @Volatile private var started = false
    @Volatile var server: String? = null; private set
    @Volatile var serverName: String = ""; private set
    @Volatile var lastError: String = ""; private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tailnet", Context.MODE_PRIVATE)

    /** Starts Tailscale once per process; safe to call from anywhere, returns at once. */
    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread {
            try {
                val dir = File(app.filesDir, "tailscale").apply { mkdirs(); setReadable(false, false); setReadable(true, true) }
                val host = "lumen-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20)
                tsbridge.Tsbridge.start(dir.absolutePath, host, "", prefs(app).getString("control", "") ?: "")
                prefs(app).getString("server", null)?.let { use(app, it, prefs(app).getString("name", "") ?: "") }
            } catch (e: Throwable) { lastError = e.message ?: "could not start the private connection"; started = false }
        }.start()
    }

    fun state(): String = try { tsbridge.Tsbridge.state() } catch (_: Throwable) { "stopped" }
    fun loginUrl(): String = try { tsbridge.Tsbridge.loginURL() } catch (_: Throwable) { "" }
    fun ready(): Boolean = server != null && state() == "running"

    /** Point the bridge at a server and check it answers as Lumen. */
    fun use(ctx: Context, url: String, name: String = ""): Boolean {
        val target = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        return try {
            tsbridge.Tsbridge.proxy(target, LumenOrigin.PORT.toLong())
            val h = health() ?: return false.also { lastError = "no Lumen server answered at $target" }
            server = target; serverName = h.optString("name", name)
            prefs(ctx).edit().putString("server", target).putString("name", serverName).apply()
            true
        } catch (e: Throwable) { lastError = e.message ?: "could not reach $target"; false }
    }

    /** Looks through the devices on the person's tailnet for their Lumen server. */
    fun discover(ctx: Context): Boolean {
        if (state() != "running") return false
        val peers = try { JSONArray(tsbridge.Tsbridge.peers()) } catch (_: Throwable) { JSONArray() }
        val order = (0 until peers.length()).map { peers.getJSONObject(it) }.filter { it.optBoolean("online") && it.optString("dns").isNotEmpty() }
            .sortedByDescending { it.optString("name").contains("lumen", true) } // the usual name first
        for (p in order) if (use(ctx, "https://" + p.optString("dns"))) return true
        lastError = if (order.isEmpty()) "no other devices are online on your Tailscale network" else "none of your devices answered as a Lumen server yet"
        return false
    }

    /** Use another coordination server (Headscale); empty for Tailscale's own. Takes effect on the next start. */
    fun setControl(ctx: Context, url: String) { prefs(ctx).edit().putString("control", url.trim()).apply() }

    fun forget(ctx: Context) { server = null; prefs(ctx).edit().remove("server").remove("name").apply() }

    private fun health(): JSONObject? = try {
        val c = URL(LumenOrigin.HOME + "/health").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        val j = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
        if (j.optBoolean("lumen")) j else null
    } catch (_: Exception) { null }
}
