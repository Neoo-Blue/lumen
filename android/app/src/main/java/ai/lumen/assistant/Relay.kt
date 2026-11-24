package ai.lumen.assistant

import android.content.Context
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
    private val RELAY_OK = setOf("formReview", "screenshot", "lifeSearch", "notify", "refreshTriggers", "skillList", "screenRead", "screenTap", "screenType", "screenScroll", "screenDo", "press", "toggle", "setting", "settings", "openApp", "openUrl", "skillRun", "flashlight", "battery", "phoneInfo", "location", "media")
    @Volatile var bridge: LumenBridge? = null   // set while Lumen's screen exists; otherwise the always-on service answers
    @Volatile private var started = false
    private var ctx: Context? = null

    @Synchronized fun start(c: Context) { ctx = c.applicationContext; if (started) return; started = true; Thread({ loop() }, "lumen-relay").apply { isDaemon = true }.start() }

    private fun cookie(): String = CookieManager.getInstance().getCookie(LumenOrigin.HOME) ?: ""

    private fun loop() {
        while (true) {
            try {
                val ck = cookie()
                if (!ck.contains("lumen_auth=")) { Thread.sleep(5000); continue }
                val c = URL(LumenOrigin.HOME + "/device/next").openConnection() as HttpURLConnection
                c.setRequestProperty("cookie", ck); c.connectTimeout = 10000; c.readTimeout = 35000
                when (c.responseCode) {
                    200 -> {
                        val job = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val b = bridge; val id = job.getString("id"); val m = job.getString("method"); val args = job.optJSONObject("args") ?: JSONObject()
                        if (m !in RELAY_OK) { answer(id, JSONObject().put("ok", false).put("error", "not allowed over the direct line")); continue }
                        if (b != null && b.alive()) b.invoke("srv:$id", m, args.toString())
                        else Thread { answer(id, ServiceActions.run(ctx!!, m, args)) }.start()
                    }
                    401 -> Thread.sleep(15000)
                }
                c.disconnect()
            } catch (_: Exception) {
                try { Thread.sleep(3000) } catch (_: Exception) {}
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
