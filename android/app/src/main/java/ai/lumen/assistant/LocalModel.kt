package ai.lumen.assistant

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The AI model on this phone (fully offline mode): llama.cpp's server, shipped in the app as libllama_server.so and
 * run from the app's native library folder, serving an OpenAI-compatible API on 127.0.0.1:PORT with a per-install
 * key. Models download once (resumable, checked against their published SHA-256) into the app's private files.
 * Lumen's server reaches it like any OpenAI-compatible model (brain.mjs, provider "device").
 */
object LocalModel {
    const val PORT = 47130
    private val lock = Any()
    @Volatile private var proc: Process? = null
    @Volatile private var running: String? = null
    @Volatile private var download: JSONObject? = null // progress while a download runs

    /** The models offered, with their files and hashes (bundled from android/llama/models.json). */
    private fun catalog(ctx: Context): JSONArray = JSONObject(ctx.assets.open("models.json").bufferedReader().readText()).getJSONArray("models")
    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("localmodel", Context.MODE_PRIVATE)

    /** The key Lumen's server uses to talk to the local model (other apps on the phone do not have it). */
    fun key(ctx: Context): String = prefs(ctx).getString("key", null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { prefs(ctx).edit().putString("key", it).apply() }

    private fun ramGb(ctx: Context): Double { val m = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(m); return m.totalMem / 1e9 }

    private fun model(ctx: Context, id: String): JSONObject? { val c = catalog(ctx); return (0 until c.length()).map { c.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
    private fun files(m: JSONObject) = m.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun have(ctx: Context, m: JSONObject) = files(m).all { File(dir(ctx), it.getString("name")).length() == it.getLong("bytes") }

    fun handle(ctx: Context, args: JSONObject): JSONObject = try {
        when (args.optString("action")) {
            "status" -> ok(status(ctx))
            "download" -> { startDownload(ctx, args.optString("model")); ok(status(ctx)) }
            "start" -> { start(ctx, args.optString("model")); ok(status(ctx)) }
            "stop" -> { stop(); ok(status(ctx)) }
            "delete" -> { stop(); model(ctx, args.optString("model"))?.let { m -> files(m).forEach { File(dir(ctx), it.getString("name")).delete() } }; ok(status(ctx)) }
            else -> LumenAccess.err("unknown action")
        }
    } catch (e: Exception) { LumenAccess.err(e.message ?: "local model error") }

    private fun ok(d: JSONObject) = JSONObject().put("ok", true).put("data", d)

    fun status(ctx: Context): JSONObject {
        val c = catalog(ctx)
        val ram = ramGb(ctx)
        val free = StatFs(ctx.filesDir.path).availableBytes
        val list = JSONArray()
        for (i in 0 until c.length()) { val m = c.getJSONObject(i)
            list.put(JSONObject().put("id", m.optString("id")).put("label", m.optString("label")).put("bytes", files(m).sumOf { it.getLong("bytes") })
                .put("downloaded", have(ctx, m)).put("fits", ram >= m.optDouble("minRamGb", 0.0))) }
        val recommended = (0 until c.length()).map { c.getJSONObject(it) }.firstOrNull { ram >= it.optDouble("minRamGb", 0.0) }?.optString("id") ?: ""
        return JSONObject().put("models", list).put("recommended", recommended).put("ramGb", "%.1f".format(ram)).put("freeBytes", free)
            .put("running", running ?: "").put("port", PORT).put("download", download ?: JSONObject())
            .put("runtime", File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so").exists())
    }

    /** Downloads every file of a model in the background, resuming partial files and checking each hash. */
    private fun startDownload(ctx: Context, id: String) {
        val m = model(ctx, id) ?: throw IllegalArgumentException("unknown model")
        synchronized(lock) { if (download?.optBoolean("active") == true) return; download = JSONObject().put("model", id).put("active", true).put("done", 0L).put("total", files(m).sumOf { it.getLong("bytes") }) }
        Thread {
            try {
                var before = 0L
                for (f in files(m)) {
                    val name = f.getString("name"); val bytes = f.getLong("bytes"); val out = File(dir(ctx), name)
                    if (out.length() == bytes) { before += bytes; continue }
                    val part = File(dir(ctx), "$name.part")
                    while (part.length() < bytes) {
                        val c = URL(f.getString("url")).openConnection() as HttpURLConnection
                        c.instanceFollowRedirects = true; c.connectTimeout = 20000; c.readTimeout = 60000
                        if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
                        val resume = c.responseCode == 206
                        RandomAccessFile(part, "rw").use { raf ->
                            if (!resume) raf.setLength(0) else raf.seek(part.length())
                            c.inputStream.use { input -> val buf = ByteArray(1 shl 16)
                                while (true) { val n = input.read(buf); if (n < 0) break; raf.write(buf, 0, n)
                                    download?.put("done", before + raf.filePointer) } }
                        }
                        c.disconnect()
                    }
                    download?.put("checking", name)
                    val md = MessageDigest.getInstance("SHA-256")
                    part.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                    val sum = md.digest().joinToString("") { "%02x".format(it) }
                    if (!sum.equals(f.getString("sha256"), true)) { part.delete(); throw IllegalStateException("$name did not match its published fingerprint; it was deleted") }
                    if (!part.renameTo(out)) throw IllegalStateException("could not save $name")
                    before += bytes
                }
                download = JSONObject().put("model", id).put("active", false).put("finished", true)
            } catch (e: Exception) { download = JSONObject().put("model", id).put("active", false).put("error", e.message ?: "download failed") }
        }.start()
    }

    /** Starts the local model server for a downloaded model (replacing one that runs). */
    fun start(ctx: Context, id: String) = synchronized(lock) {
        val m = model(ctx, id) ?: throw IllegalArgumentException("unknown model")
        if (!have(ctx, m)) throw IllegalStateException("download the model first")
        if (running == id && proc?.isAlive == true) return
        stop()
        val lib = ctx.applicationInfo.nativeLibraryDir
        val bin = File(lib, "libllama_server.so")
        if (!bin.exists()) throw IllegalStateException("this build of Lumen has no on-device model runtime")
        val fs = files(m); val main = fs.first { !it.getString("name").startsWith("mmproj") }; val proj = fs.firstOrNull { it.getString("name").startsWith("mmproj") }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val cmd = mutableListOf(bin.absolutePath, "--host", "127.0.0.1", "--port", PORT.toString(), "--api-key", key(ctx), "--jinja", "--reasoning", "off",
            "-m", File(dir(ctx), main.getString("name")).absolutePath, "-c", "8192", "-ctk", "q8_0", "-ctv", "q8_0", "-fa", "on", "--threads", threads.toString(), "--no-webui")
        if (proj != null) cmd += listOf("--mmproj", File(dir(ctx), proj.getString("name")).absolutePath)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(ctx.cacheDir)
        pb.environment()["LD_LIBRARY_PATH"] = lib
        val p = pb.start(); proc = p; running = id
        Thread { p.inputStream.bufferedReader().forEachLine { if (it.contains("error", true)) Telemetry.warn("model", it.take(300)) } }.start()
    }

    fun stop() = synchronized(lock) { proc?.destroy(); proc = null; running = null }
}
