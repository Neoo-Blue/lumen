package ai.lumen.assistant

import android.app.Application

/** Starts the private connection with the process, so the assistant panel and screen control can reach the server. */
class LumenApp : Application() {
    override fun onCreate() { super.onCreate(); Tailnet.start(this) }
}
