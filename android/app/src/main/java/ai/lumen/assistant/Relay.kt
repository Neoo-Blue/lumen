package ai.lumen.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's direct line to Lumen: waits on /device/next for the next screen or app action and answers on /device.
 * Screen control keeps working while Lumen's page sits behind another app (browsers pause hidden pages).
 */
object DeviceRelay {
    // what the server may ask for over the direct line: reading and steering, never sending texts, calls, installs or settings of Lumen itself
    private val RELAY_OK = setOf("formReview", "screenshot", "lifeSearch", "notify", "refreshTriggers", "skillList", "screenRead", "screenTap", "screenType", "screenScroll", "screenDo", "press", "toggle", "setting", "settings", "openApp", "openUrl", "skillRun", "flashlight", "battery", "phoneInfo", "location", "media", "calendarEvents", "calendarAdd", "playSearch", "systemScreen", "notifReplyTargets", "triage", "notifDigest", "live", "assistContext", "health", "stashSearch", "stashNote", "stashStats", "localRules", "teachStart", "teachStop", "parked", "localModel")
    @Volatile var bridge: LumenBridge? = null   // set while Lumen's screen exists; otherwise the always-on service answers
    @Volatile private var started = false
    @Volatile private var poke = false
    @Volatile private var poll: HttpURLConnection? = null
    private var ctx: Context? = null
    private var screenRx: BroadcastReceiver? = null

    @Synchronized fun start(c: Context) { ctx = c.applicationContext; LocalRules.ensureMonitors(ctx!!); if (started) return; started = true; Thread({ loop() }, "lumen-relay").apply { isDaemon = true }.start() }

    private fun cookie(): String = CookieManager.getInstance().getCookie(LumenOrigin.HOME) ?: ""

    /** Screen on: ask the server to answer within 25s. Screen off: 55s, and a screen-on broadcast drops the poll at once. */
    private fun listenForScreen() {
        val app = ctx ?: return
        if (screenRx != null) return
        try { listenForScreenNow(app) } catch (_: Exception) {}
    }

    private fun listenForScreenNow(app: Context) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                poke = true
                val conn = poll
                Thread({
                    try { conn?.disconnect() } catch (_: Exception) {}
                }, "lumen-relay-drop").apply { isDaemon = true }.start()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(rx, filter)
        screenRx = rx
    }

    private fun screenOn(): Boolean = try {
        ctx?.getSystemService(PowerManager::class.java)?.isInteractive == true
    } catch (_: Exception) { true }

    private fun pause(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!poke && System.currentTimeMillis() < end) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            try { Thread.sleep(minOf(200L, left)) } catch (_: InterruptedException) { break }
        }
    }

    private fun loop() {
        listenForScreen()
        while (true) {
            var c: HttpURLConnection? = null
            try {
                val ck = cookie()
                // phone mode: wait until this install's secret is in place; server mode: Tailscale says who we are
                if (PhoneMode.mode(ctx!!) == "phone" && !ck.contains("lumen_local=")) { pause(3000); continue }
                if (PhoneMode.mode(ctx!!) == "") { pause(5000); continue }
                val wait = if (screenOn()) 25 else 55
                c = URL(LumenOrigin.HOME + "/device/next?wait=" + wait).openConnection() as HttpURLConnection
                poll = c
                c.setRequestProperty("cookie", ck); c.connectTimeout = 10000; c.readTimeout = wait * 1000 + 15_000
                when (c.responseCode) {
                    200 -> {
                        val job = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val b = bridge; val id = job.getString("id"); val m = job.getString("method"); val args = job.optJSONObject("args") ?: JSONObject()
                        if (m !in RELAY_OK) answer(id, JSONObject().put("ok", false).put("error", "not allowed over the direct line"))
                        else if (b != null && b.alive() && m != "localModel") b.invoke("srv:$id", m, args.toString())
                        else Thread { answer(id, ServiceActions.run(ctx!!, m, args)) }.start()
                    }
                    401 -> pause(15000)
                    204 -> {}
                    else -> pause(3000) // not ready yet (setup, a restart): never spin
                }
            } catch (_: Exception) {
                if (!poke) pause(3000)
            } finally {
                poke = false
                if (poll === c) poll = null
                try { c?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /** Answer a server-issued action straight to the server (no page involved). */
        fun answer(id: String, result: JSONObject) = Thread {
            try {
                val c = URL(LumenOrigin.HOME + "/device").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("cookie", CookieManager.getInstance().getCookie(LumenOrigin.HOME) ?: "")
                c.outputStream.use { it.write(JSONObject().put("id", id).put("result", result).toString().toByteArray()) }
                c.responseCode; c.disconnect()
            } catch (e: Exception) { Telemetry.warn("relay", "could not answer: ${e.message}") }
        }.start()
}
