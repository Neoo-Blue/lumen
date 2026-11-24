package ai.lumen.assistant

import android.app.Application

/** Starts the chosen mode with the process, so the assistant panel and screen control can reach the server. */
class LumenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        when (PhoneMode.mode(this)) {
            "server" -> Tailnet.start(this) // the person's own server, over the private network built into the app
            "phone" -> LumenCore.ensure(this) // everything on this phone
        }
    }
}
