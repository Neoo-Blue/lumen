package ai.lumen.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject

/**
 * Live updates on the lock screen and Samsung Now Bar.
 * Uses Android 16 (API 36+) ProgressStyle and promoted ongoing requests when available,
 * falling back to a standard ongoing progress notification on older Android versions.
 */
object LiveUpdates {
    private const val CHANNEL = "live"
    private const val NOTIF_ID = 2000
    private const val DONE_TIMEOUT_MS = 10L * 60_000L
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val FLAG_PROMOTED_ONGOING = 0x00020000

    fun post(ctx: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val title = args.optString("title").trim()
        val text = args.optString("text").trim()
        if (id.isEmpty() || title.isEmpty()) return LumenAccess.err("bad_args")
        val done = args.optBoolean("done", false)
        val open = args.optString("open").trim()
        val progress: Int? = if (args.has("progress") && !args.isNull("progress")) {
            args.optInt("progress", -1).takeIf { it >= 0 }?.coerceIn(0, 100)
        } else null

        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return LumenAccess.err("unavailable")
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Live updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }

        val tag = "live:$id"
        val tapIntent = if (open.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("ai.lumen.assistant://open?what=" + Uri.encode(open)))
                .setClass(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val tap = PendingIntent.getActivity(
            ctx,
            tag.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val publicVersion = builder(ctx)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Lumen")
            .setContentText("Lumen is working on something")
            .build()

        val b = builder(ctx)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)

        var promotedRequested = false
        if (done) {
            b.setOngoing(false)
                .setAutoCancel(true)
                .setStyle(Notification.BigTextStyle().bigText(text))
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(DONE_TIMEOUT_MS)
        } else {
            b.setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
            if (Build.VERSION.SDK_INT >= 36 && applyApi36Live(b, title, progress)) {
                promotedRequested = true
            } else {
                if (progress != null) b.setProgress(100, progress, false)
                else b.setProgress(0, 0, true)
                b.setStyle(Notification.BigTextStyle().bigText(text))
            }
        }

        val notif = b.build()
        val promoted = promotedRequested && ((notif.flags and FLAG_PROMOTED_ONGOING) != 0 || hasPromotable(notif))
        nm.notify(tag, NOTIF_ID, notif)
        return JSONObject().put("ok", true).put(
            "data",
            JSONObject()
                .put("id", id)
                .put("shown", true)
                .put("done", done)
                .put("promoted", promoted)
        )
    }

    private fun builder(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)

    /** Applies Android 16 ProgressStyle and promoted-ongoing flags via reflection so compileSdk 35 builds cleanly. */
    private fun applyApi36Live(b: Notification.Builder, title: String, progress: Int?): Boolean {
        return try {
            val styleCls = Class.forName("android.app.Notification\$ProgressStyle")
            val style = styleCls.getDeclaredConstructor().newInstance()
            if (progress != null) {
                val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                val seg = segCls.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(100)
                styleCls.getMethod("setProgressSegments", List::class.java).invoke(style, listOf(seg))
                styleCls.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, progress)
            } else {
                styleCls.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType).invoke(style, true)
            }
            b.setStyle(style as Notification.Style)
            try {
                b.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType).invoke(b, true)
            } catch (_: Exception) {}
            b.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            try {
                val chip = if (progress != null) "$progress%" else title.take(12)
                b.javaClass.getMethod("setShortCriticalText", String::class.java).invoke(b, chip)
            } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            Telemetry.warn("live", "api36 progress style fallback: ${e.message}")
            false
        }
    }

    private fun hasPromotable(n: Notification): Boolean = try {
        n.javaClass.getMethod("hasPromotableCharacteristics").invoke(n) as? Boolean ?: false
    } catch (_: Exception) {
        false
    }
}
