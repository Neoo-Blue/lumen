package ai.lumen.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * "Hey Lumen": an offline listener in a foreground service. Audio never leaves the phone; only hearing the phrase
 * opens Lumen. Only phones' built-in assistants get the always-on hotword chip, so this keeps a small notification.
 */
class WakeService : Service(), RecognitionListener {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false   // Lumen itself is using the microphone
    private var yielded = false  // screen off, a call, or another app recording
    private var lastFire = 0L
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Telemetry.init(applicationContext)
        LocalRules.ensureMonitors(applicationContext)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val n = notification()
        // Android 14+ refuses a microphone service without the permission, or when started from the background: stop quietly
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        try { if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n) }
        catch (e: Exception) { Telemetry.warn("wake", "Android did not allow listening now: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        if (model == null) Thread {
            try {
                model = Model(ensureModel(this).absolutePath)
                main.post { if (!paused) listen(); watch() }
                Telemetry.info("wake", "Hey Lumen is listening")
            } catch (e: Exception) {
                Telemetry.error("wake", "could not start: ${e.message}")
                main.post { stopSelf() }
            }
        }.start()
        return START_STICKY
    }

    private fun listen() {
        val m = model ?: return
        if (paused || yielded) return
        if (speech != null) return
        try {
            val rec = Recognizer(m, 16000f, "[\"hey lumen\", \"[unk]\"]").apply { setWords(true) } // per-word confidence, checked in heard()
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            Telemetry.warn("wake", "microphone busy: ${e.message}")
            main.postDelayed({ if (!paused) listen() }, 5000)
        }
    }

    /** Every two seconds: hand the microphone over when the screen is off, during a call, or while another app records.
     *  Music and podcasts count as busy too, except while driving, so Hey Lumen still hears the person over the car speakers. */
    private fun watch() {
        val am = getSystemService(android.media.AudioManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        val others = try { am.activeRecordingConfigurations.size - (if (speech != null) 1 else 0) } catch (_: Exception) { 0 }
        // screen off: only while driving, or when the person chose "listen with the screen off" and the phone is charging
        val charging = getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
        val driving = Driving.on(this)
        val screenOk = pm.isInteractive || driving || (screenOff(this) && charging)
        // a call (mode other than normal) stays busy. playback does not, while driving
        val busy = !screenOk || am.mode != android.media.AudioManager.MODE_NORMAL || others > 0 || (!driving && playing(am))
        if (busy && !yielded) { yielded = true; release() }
        else if (!busy && yielded) { yielded = false; if (!paused) listen() }
        main.postDelayed({ watch() }, 2000)
    }

    /** Any app playing media, a game or a video right now (covers AAudio players that isMusicActive misses). */
    private var lastPlayback = ""
    private fun playing(am: android.media.AudioManager): Boolean = try {
        val media = am.activePlaybackConfigurations.filter { it.audioAttributes.usage in setOf(android.media.AudioAttributes.USAGE_MEDIA, android.media.AudioAttributes.USAGE_GAME, android.media.AudioAttributes.USAGE_UNKNOWN) }
        // a player counts only while it is actually playing (state 2 = started); paused and idle players do not
        val states = media.map { c -> try { c.javaClass.getMethod("getPlayerState").invoke(c) as Int } catch (_: Throwable) { -1 } }
        val now = "${am.isMusicActive} $states"
        if (now != lastPlayback) { lastPlayback = now; Telemetry.info("wake", "playback: $now") }
        am.isMusicActive || states.any { it == 2 }
    } catch (_: Exception) { am.isMusicActive }

    fun recheck() { main.post { yielded = false; if (!paused) listen() } }

    private fun release() { speech?.stop(); speech?.shutdown(); speech = null }

    /** Lumen itself is on screen and needs the microphone; give it up until Lumen goes away. */
    @Volatile private var uiSeen = false
    fun pause() { uiSeen = true; paused = true; main.removeCallbacks(resumeLater); release() }
    private val resumeLater = Runnable { if (!paused && model != null) listen() }
    fun resume() { paused = false; main.removeCallbacks(resumeLater); main.postDelayed(resumeLater, 4000) } // after Lumen's own voice has gone quiet

    /** Only a final result counts, and both words must be heard clearly: partial guesses fire on near-misses. */
    private fun heard(json: String?) {
        val j = try { JSONObject(json ?: "") } catch (_: Exception) { return }
        val text = j.optString("text").trim()
        val words = j.optJSONArray("result")
        // car speakers throw off the recognizer, so driving asks for a slightly clearer phrase
        val need = MIN_CONF + if (Driving.on(this)) 0.03 else 0.0
        val clear = words != null && words.length() == 2 && (0 until 2).all { words.getJSONObject(it).optDouble("conf", 0.0) >= need }
        if (text == "hey lumen" && !clear) { Telemetry.info("wake", "near miss ignored: ${words}"); return }
        if (text != "hey lumen" || !clear || System.currentTimeMillis() - lastFire < 3000) return
        lastFire = System.currentTimeMillis()
        Telemetry.info("wake", "heard: $text")
        uiSeen = false
        paused = true; main.removeCallbacks(resumeLater); release()
        summon(this)
        // Android may block opening the panel from the background: if no Lumen screen showed up, listen again
        main.postDelayed({ if (!uiSeen && paused) { paused = false; Telemetry.warn("wake", "the panel did not open; listening again"); listen() } }, 5000)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onResult(hypothesis: String?) = heard(hypothesis)
    override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
    override fun onError(exception: Exception?) { Telemetry.warn("wake", "listener error: ${exception?.message}"); release(); main.postDelayed({ if (!paused) listen() }, 3000) }
    override fun onTimeout() { release(); if (!paused) listen() }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        release(); model?.close(); model = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Lumen", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val talk = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getService(this, 2, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Say \"Hey Lumen\"").setContentText("Listening on this phone only. Tap to talk.")
            .setContentIntent(talk).setOngoing(true).addAction(Notification.Action.Builder(null, "Turn off", off).build()).build()
    }

    companion object {
        /** How sure the recognizer must be of each word (Vosk gives 1.0 for a clear phrase). */
        const val MIN_CONF = 0.9
        const val ACTION_STOP = "ai.lumen.assistant.WAKE_STOP"
        private const val CHANNEL = "wake"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        @Volatile var instance: WakeService? = null

        fun screenOff(ctx: Context) = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE).getBoolean("wakeScreenOff", false)
        fun setScreenOff(ctx: Context, v: Boolean) = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE).edit().putBoolean("wakeScreenOff", v).apply()
        fun enabled(ctx: Context) = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE).getBoolean("wake", false)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE).edit().putBoolean("wake", on).apply()
            if (on) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(Intent(ctx, WakeService::class.java)) else ctx.startService(Intent(ctx, WakeService::class.java)) }
            else ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_STOP))
        }

        /** Open Lumen listening. As the phone's assistant it may do this from anywhere; otherwise a tap on a heads-up note. */
        fun summon(ctx: Context) {
            if (LumenVoiceService.instance?.summon() == true) return
            try {
                ctx.startActivity(Intent(ctx, OverlayActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }

        /** The speech model is downloaded once (about 40 MB), checked against its known hash, and unpacked privately. */
        fun ensureModel(ctx: Context): File {
            val dir = File(ctx.filesDir, "wake-model")
            val ready = File(dir, ".ok")
            if (ready.exists()) return dir.listFiles { f -> f.isDirectory }!!.first()
            dir.deleteRecursively(); dir.mkdirs()
            val zip = File(ctx.cacheDir, "wake.zip")
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { i -> zip.outputStream().use { o -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) } } }
            if (md.digest().joinToString("") { "%02x".format(it) } != MODEL_SHA256) { zip.delete(); throw IllegalStateException("speech model failed its check") }
            val root = dir.canonicalPath + File.separator
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(root)) throw IllegalStateException("bad entry")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
            zip.delete(); ready.createNewFile()
            return dir.listFiles { f -> f.isDirectory }!!.first()
        }
    }
}
