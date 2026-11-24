package ai.lumen.assistant

import android.net.Uri

/**
 * Where Lumen's page comes from: the private bridge inside this app (Tailnet.kt), which carries every request over
 * Tailscale to the person's own server. A fixed port keeps the page's origin, and so its saved settings, the same.
 */
object LumenOrigin {
    const val PORT = 47120
    const val HOST = "127.0.0.1"
    const val HOME = "http://127.0.0.1:47120"

    fun allows(uri: Uri?): Boolean =
        uri != null && uri.scheme.equals("http", ignoreCase = true) && uri.host == HOST && uri.port == PORT

    fun allows(url: String?): Boolean =
        !url.isNullOrBlank() && try { allows(Uri.parse(url)) } catch (_: Exception) { false }
}
