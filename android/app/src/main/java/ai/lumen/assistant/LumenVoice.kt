package ai.lumen.assistant

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class LumenVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady(); instance = this
        Telemetry.init(applicationContext)
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
