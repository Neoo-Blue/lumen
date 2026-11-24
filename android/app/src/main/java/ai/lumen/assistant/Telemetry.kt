package ai.lumen.assistant

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diagnostics for troubleshooting: crashes, WebView errors, page load failures and bridge problems go to
 * the Lumen server (/telemetry). A crash is written to disk first and sent on the next launch.
 */
object Telemetry {
    private const val ENDPOINT = LumenOrigin.HOME + "/telemetry"
    private val queue = ArrayList<JSONObject>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lumen-telemetry").apply { isDaemon = true } }
    private var install = "unknown"
    private var version = "?"
    private lateinit var crashFile: File

    @Volatile private var started = false

    @Synchronized fun init(context: Context) {
        if (started) return
        started = true
        val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        install = prefs.getString("install", null) ?: UUID.randomUUID().toString().take(10).also { prefs.edit().putString("install", it).apply() }
        version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        crashFile = File(context.filesDir, "last-crash.json")
        // a crash from the previous run
        if (crashFile.exists()) {
            try { add(JSONObject(crashFile.readText())) } catch (_: Exception) {}
            crashFile.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile.writeText(event("error", "crash", "${error.javaClass.name}: ${error.message}", JSONObject().put("thread", thread.name).put("stack", error.stackTraceToString().take(3000))).toString())
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, error)
        }
        worker.scheduleWithFixedDelay({ flush() }, 5, 10, TimeUnit.SECONDS)
        info("start", "app started")
    }

    private fun event(level: String, kind: String, msg: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("t", Instant.now().toString()).put("level", level).put("kind", kind).put("msg", msg.take(1500)).also { if (data != null) it.put("data", data) }

    private fun add(e: JSONObject) = synchronized(queue) { queue.add(e); if (queue.size > 60) queue.removeAt(0) }

    fun info(kind: String, msg: String, data: JSONObject? = null) = add(event("info", kind, msg, data))
    fun warn(kind: String, msg: String, data: JSONObject? = null) = add(event("warn", kind, msg, data))
    fun error(kind: String, msg: String, data: JSONObject? = null) { add(event("error", kind, msg, data)); worker.execute { flush() } }

    private fun flush() {
        val batch = synchronized(queue) { if (queue.isEmpty()) return; ArrayList(queue).also { queue.clear() } }
        val body = JSONObject()
            .put("install", install).put("app", "android").put("version", version)
            .put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("events", JSONArray(batch))
        try {
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {
            synchronized(queue) { queue.addAll(0, batch.takeLast(30)) } // try again next round
        }
    }
}
