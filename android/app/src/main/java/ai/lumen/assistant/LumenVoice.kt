package ai.lumen.assistant

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.time.Instant

/** Keeps the latest assistant screen context in memory for 2 minutes. Nothing leaves the phone unless asked. */
object AssistContext {
    private const val TTL_MS = 2L * 60_000L
    private const val MAX_CHARS = 6000
    private val SENSITIVE_HINT = Regex("(?i)(password|passcode|passphrase|creditcard|card.?number|cvv|cvc|security.?code|ssn|otp|pin)")

    private class Snap(val app: String, val url: String, val text: String, val atMs: Long)

    @Volatile private var latest: Snap? = null

    @RequiresApi(29)
    fun capture(state: VoiceInteractionSession.AssistState) {
        try {
            val structure = state.assistStructure
            val app = structure?.activityComponent?.packageName.orEmpty()
            val url = state.assistContent?.webUri?.toString().orEmpty()
            val sb = StringBuilder()
            var lastPiece = ""
            if (structure != null) {
                for (i in 0 until structure.windowNodeCount) {
                    if (sb.length >= MAX_CHARS) break
                    val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
                    walk(root, sb, 0) { piece ->
                        if (piece.isNotEmpty() && piece != lastPiece && sb.length < MAX_CHARS) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            val room = MAX_CHARS - sb.length
                            sb.append(if (piece.length <= room) piece else piece.substring(0, room))
                            lastPiece = piece
                        }
                    }
                }
            }
            val text = sb.toString().trim()
            if (app.isNotEmpty() || url.isNotEmpty() || text.isNotEmpty()) {
                latest = Snap(app, url, text, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Telemetry.warn("assist", "capture failed: ${e.message}")
        }
    }

    fun read(): JSONObject {
        val snap = latest
        if (snap == null || System.currentTimeMillis() - snap.atMs > TTL_MS) {
            return LumenAccess.err("nothing captured")
        }
        return JSONObject().put("ok", true).put(
            "data",
            JSONObject()
                .put("app", snap.app)
                .put("url", snap.url)
                .put("text", snap.text)
                .put("at", Instant.ofEpochMilli(snap.atMs).toString())
        )
    }

    private fun walk(node: AssistStructure.ViewNode, sb: StringBuilder, depth: Int, emit: (String) -> Unit) {
        if (depth > 48 || sb.length >= MAX_CHARS) return
        if (node.visibility != View.VISIBLE) return
        if (!isSensitiveInput(node)) {
            val text = node.text?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (text.isNotEmpty()) emit(text)
            if (desc.isNotEmpty() && desc != text) emit(desc)
        }
        for (i in 0 until node.childCount) {
            if (sb.length >= MAX_CHARS) break
            val child = node.getChildAt(i) ?: continue
            walk(child, sb, depth + 1, emit)
        }
    }

    private fun isSensitiveInput(node: AssistStructure.ViewNode): Boolean {
        val itype = node.inputType
        val cls = itype and InputType.TYPE_MASK_CLASS
        val variation = itype and InputType.TYPE_MASK_VARIATION
        val isPassword = (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (isPassword) return true

        val hints = node.autofillHints
        if (hints != null && hints.any { SENSITIVE_HINT.containsMatchIn(it) }) return true

        val editable = itype != 0 || (node.className?.contains("EditText", ignoreCase = true) == true)
        if (editable) {
            val meta = "${node.idEntry.orEmpty()} ${node.hint.orEmpty()}"
            if (meta.isNotBlank() && SENSITIVE_HINT.containsMatchIn(meta)) return true
        }
        return false
    }
}

class LumenVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady(); instance = this
        Telemetry.init(applicationContext)
        Stash.start(applicationContext)
        LocalRules.start(applicationContext)
        // the system connects the assistant at boot and after updates: bring "Hey Lumen" back if it was on
        if (WakeService.enabled(this) && WakeService.instance == null) try { WakeService.setEnabled(this, true) } catch (e: Exception) { Telemetry.warn("wake", "not restarted at start-up: ${e.message}") }
    }
    override fun onShutdown() { if (instance === this) instance = null; super.onShutdown() }

    /** As the phone's chosen assistant, Lumen may open itself from anywhere, even over the lock screen. */
    fun summon(): Boolean = try { showSession(Bundle(), 0); true } catch (_: Exception) { false }

    companion object { @Volatile var instance: LumenVoiceService? = null }
}

class LumenVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LumenVoiceSession(this)
    }
}

class LumenVoiceSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }

    @RequiresApi(29)
    override fun onHandleAssist(state: AssistState) {
        if (Build.VERSION.SDK_INT >= 29) {
            AssistContext.capture(state)
        }
        super.onHandleAssist(state)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startAssistantActivity(intent)
        } catch (_: Throwable) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        hide()
    }
}

class LumenRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        fail(listener)
    }

    override fun onCancel(listener: Callback) {
    }

    override fun onStopListening(listener: Callback) {
        fail(listener)
    }

    private fun fail(listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
        }
    }
}
