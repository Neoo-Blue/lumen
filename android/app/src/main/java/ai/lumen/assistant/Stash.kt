package ai.lumen.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private on-device second brain: indexes text from screenshots (Latin and Chinese) and saved notes
 * into a local SQLite FTS4 database in the app's private files. Sensitive screenshots are skipped.
 */
object Stash {
    private const val MAX_TEXT = 4000
    private val started = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-stash").apply { isDaemon = true } }
    private val pendingScan = Runnable { executor.execute { scan(appCtx ?: return@execute, 10) } }

    @Volatile private var appCtx: Context? = null
    @Volatile private var dbHelper: StashDb? = null

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    private val PASS_RE = Regex("(?i)\\b(password|passcode|passphrase|master\\s*key|recovery\\s*(key|phrase|code)|seed\\s*phrase|secret\\s*key|backup\\s*codes?)\\b|密码|口令|助记词|私钥")
    private val CVV_RE = Regex("(?i)\\b(cvv|cvc|cvv2|cid|security\\s*code|card\\s*verification)\\b")
    private val SSN_RE = Regex("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b|(?i)\\b(ssn|social\\s*security)\\b")
    private val CARD_RE = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val CJK_RE = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!started.compareAndSet(false, true)) return
        try {
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    main.removeCallbacks(pendingScan)
                    main.postDelayed(pendingScan, 2500)
                }
            }
            app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: Exception) {
            Telemetry.warn("stash", "observer failed: ${e.message}")
        }
        scheduleWorkers(app)
        main.postDelayed(pendingScan, 4000)
    }

    private fun scheduleWorkers(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx)
            val chargeReq = PeriodicWorkRequestBuilder<StashWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            wm.enqueueUniquePeriodicWork("lumen-stash-charge", ExistingPeriodicWorkPolicy.KEEP, chargeReq)
            val idleReq = PeriodicWorkRequestBuilder<StashWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
                .build()
            wm.enqueueUniquePeriodicWork("lumen-stash-idle", ExistingPeriodicWorkPolicy.KEEP, idleReq)
        } catch (e: Exception) {
            Telemetry.warn("stash", "work schedule failed: ${e.message}")
        }
    }

    @Synchronized
    private fun db(ctx: Context): StashDb {
        val existing = dbHelper
        if (existing != null) return existing
        return StashDb(ctx.applicationContext).also { dbHelper = it }
    }

    private fun hasImagePerm(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    fun scan(ctx: Context, maxNew: Int = 30): Int {
        if (!hasImagePerm(ctx)) return 0
        if (!scanning.compareAndSet(false, true)) return 0
        var indexed = 0
        try {
            val helper = db(ctx)
            val shots = queryScreenshots(ctx, maxNew * 3)
            var processed = 0
            for ((uriStr, ts) in shots) {
                if (processed >= maxNew) break
                if (helper.isSeen(uriStr)) continue
                processed++
                val text = recognize(ctx, Uri.parse(uriStr))
                if (text == null) continue // model missing, timed out, or the file could not be read: try again later
                helper.markSeen(uriStr, ts)
                if (text.isBlank() || isSensitive(text)) continue
                helper.put(uriStr, ts, text.take(MAX_TEXT))
                indexed++
            }
        } catch (e: Exception) {
            Telemetry.warn("stash", "scan error: ${e.message}")
        } finally {
            scanning.set(false)
        }
        return indexed
    }

    private fun queryScreenshots(ctx: Context, limit: Int): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        val sel = "$pathCol LIKE ?"
        val args = arrayOf("%Screenshots%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            ctx.contentResolver.query(collection, cols, sel, args, sort)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val taken = c.getLong(1)
                    val added = c.getLong(2)
                    val ts = if (taken > 0L) taken else added * 1000L
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    out.add(uri to ts)
                }
            }
        } catch (_: SecurityException) {}
        return out
    }

    /** Text from the screenshot, "" when the picture has none, null when recognition itself failed. */
    private fun recognize(ctx: Context, uri: Uri): String? {
        val img = try {
            InputImage.fromFilePath(ctx, uri)
        } catch (_: Throwable) {
            return null
        }
        val zhText = try {
            Tasks.await(chinese.process(img), 15, TimeUnit.SECONDS)?.text.orEmpty().trim()
        } catch (_: Exception) {
            null
        }
        if (zhText != null && zhText.isNotEmpty() && CJK_RE.containsMatchIn(zhText)) {
            return compact(zhText)
        }
        val latText = try {
            Tasks.await(latin.process(img), 15, TimeUnit.SECONDS)?.text.orEmpty().trim()
        } catch (_: Exception) {
            null
        }
        if (latText == null) return if (zhText.isNullOrEmpty()) null else compact(zhText)
        // Latin ran and found nothing, but the other model never started: retry once it is available.
        if (zhText == null && latText.isEmpty()) return null
        return compact(if (latText.isNotEmpty()) latText else zhText.orEmpty())
    }

    private fun compact(raw: String): String =
        raw.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    fun isSensitive(text: String): Boolean {
        if (PASS_RE.containsMatchIn(text)) return true
        if (CVV_RE.containsMatchIn(text)) return true
        if (SSN_RE.containsMatchIn(text)) return true
        for (m in CARD_RE.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhnValid(digits)) return true
        }
        return false
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var dbl = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (dbl) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    fun addNote(ctx: Context, text: String): JSONObject {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return LumenAccess.err("bad_args")
        val now = System.currentTimeMillis()
        db(ctx).put("note:", now, clean)
        return JSONObject().put("ok", true).put(
            "data",
            JSONObject().put("saved", true).put("at", Instant.ofEpochMilli(now).toString())
        )
    }

    fun search(ctx: Context, query: String, limit: Int): JSONObject {
        val q = query.trim()
        val cap = limit.coerceIn(1, 50)
        val arr = db(ctx).search(q, cap)
        return JSONObject().put("ok", true).put("data", arr)
    }

    fun stats(ctx: Context): JSONObject = db(ctx).stats()
}

class StashWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Stash.scan(applicationContext, maxNew = 40)
        return Result.success()
    }
}

private class StashDb(ctx: Context) : SQLiteOpenHelper(
    ctx,
    File(ctx.filesDir, "stash.db").absolutePath,
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS stash_fts USING fts4(uri, t, text, tokenize=unicode61)")
        db.execSQL("CREATE TABLE IF NOT EXISTS stash_seen (uri TEXT PRIMARY KEY, t INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun isSeen(uri: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM stash_seen WHERE uri = ? LIMIT 1", arrayOf(uri)).use { c ->
            return c.moveToFirst()
        }
    }

    @Synchronized
    fun markSeen(uri: String, t: Long) {
        val v = ContentValues().apply { put("uri", uri); put("t", t) }
        writableDatabase.insertWithOnConflict("stash_seen", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun put(uri: String, t: Long, text: String) {
        val db = writableDatabase
        if (uri != "note:") db.delete("stash_fts", "uri = ?", arrayOf(uri))
        val v = ContentValues().apply { put("uri", uri); put("t", t); put("text", text) }
        db.insert("stash_fts", null, v)
    }

    @Synchronized
    fun search(query: String, limit: Int): JSONArray {
        val out = JSONArray()
        val db = readableDatabase
        if (query.isEmpty()) {
            db.rawQuery(
                "SELECT uri, t, text FROM stash_fts ORDER BY CAST(t AS INTEGER) DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.put(rowJson(c.getString(0), c.getLong(1), c.getString(2).take(220)))
                }
            }
            return out
        }

        val hasCjk = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]").containsMatchIn(query)
        val seenKeys = HashSet<String>()
        if (!hasCjk) {
            val ftsQuery = buildFtsQuery(query)
            if (ftsQuery.isNotEmpty()) {
                try {
                    db.rawQuery(
                        "SELECT uri, t, snippet(stash_fts, '', '', '...', 2, 24) FROM stash_fts WHERE text MATCH ? ORDER BY CAST(t AS INTEGER) DESC LIMIT ?",
                        arrayOf(ftsQuery, limit.toString())
                    ).use { c ->
                        while (c.moveToNext() && out.length() < limit) {
                            val u = c.getString(0); val t = c.getLong(1)
                            val key = "$u#$t"
                            if (seenKeys.add(key)) out.put(rowJson(u, t, c.getString(2).orEmpty()))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        if (out.length() < limit) {
            val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }.take(5)
            if (terms.isNotEmpty()) {
                val where = terms.joinToString(" AND ") { "text LIKE ?" }
                val args = terms.map { "%$it%" }.toTypedArray() + limit.toString()
                db.rawQuery(
                    "SELECT uri, t, text FROM stash_fts WHERE $where ORDER BY CAST(t AS INTEGER) DESC LIMIT ?",
                    args
                ).use { c ->
                    while (c.moveToNext() && out.length() < limit) {
                        val u = c.getString(0); val t = c.getLong(1); val body = c.getString(2).orEmpty()
                        val key = "$u#$t"
                        if (seenKeys.add(key)) out.put(rowJson(u, t, makeSnippet(body, terms.first())))
                    }
                }
            }
        }
        return out
    }

    @Synchronized
    fun stats(): JSONObject {
        val db = readableDatabase
        var items = 0L
        var notes = 0L
        var last = 0L
        db.rawQuery("SELECT COUNT(*), COALESCE(MAX(CAST(t AS INTEGER)), 0) FROM stash_fts", null).use { c ->
            if (c.moveToFirst()) {
                items = c.getLong(0)
                last = c.getLong(1)
            }
        }
        db.rawQuery("SELECT COUNT(*) FROM stash_fts WHERE uri = 'note:'", null).use { c ->
            if (c.moveToFirst()) notes = c.getLong(0)
        }
        return JSONObject()
            .put("items", items)
            .put("notes", notes)
            .put("last", if (last > 0L) Instant.ofEpochMilli(last).toString() else JSONObject.NULL)
    }

    private fun rowJson(uri: String, t: Long, snippet: String) = JSONObject()
        .put("uri", uri)
        .put("at", Instant.ofEpochMilli(t).toString())
        .put("snippet", snippet.replace('\n', ' ').trim().take(260))

    private fun buildFtsQuery(raw: String): String {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(6)
        return tokens.joinToString(" ") { "$it*" }
    }

    private fun makeSnippet(text: String, needle: String): String {
        val idx = text.indexOf(needle, ignoreCase = true)
        if (idx < 0) return text.take(220)
        val start = (idx - 60).coerceAtLeast(0)
        val end = (idx + needle.length + 140).coerceAtMost(text.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return prefix + text.substring(start, end) + suffix
    }
}
