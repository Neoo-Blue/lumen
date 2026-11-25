package ai.lumen.assistant

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque

class LumenNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        try {
            val active = activeNotifications
            NotificationStore.replace(this, active)
            ReplyCache.seed(this, active)
        } catch (_: Exception) {
        }
        NotifTriage.scheduleNext(this)
        CallCard.register(this)
    }

    override fun onListenerDisconnected() {
        CallCard.unregister(this)
        if (connected === this) connected = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        CallCard.onCallNotification(this, sbn)
        val p = NotifHygiene.parse(this, sbn)
        NotificationStore.add(this, sbn, p)
        ReplyCache.capture(this, sbn, p)
        if (NotifHygiene.isNoise(sbn)) return
        OtpGuard.handle(this, p.appLabel, p.title, p.rawText)
        val held = NotifTriage.shouldHold(this, p.app, p.who, p.title)
        if (held) {
            try { cancelNotification(sbn.key) } catch (_: Exception) {}
        } else {
            DrivingAnnouncer.onMessage(this, sbn, p)
        }
        Triggers.posted(this, sbn, p, held)
    }

    companion object {
        @Volatile
        var connected: LumenNotificationListener? = null
    }
}

object NotificationAccess {
    fun enabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val mine = ComponentName(context, LumenNotificationListener::class.java)
        return flat.split(':').any { part ->
            val component = ComponentName.unflattenFromString(part.trim()) ?: return@any false
            component.packageName == mine.packageName && component.className == mine.className
        }
    }
}

object NotificationStore {
    private const val CAPACITY = 100
    private val items = ArrayDeque<Item>()
    private val lock = Any()

    private class Item(val key: String, val json: JSONObject)

    fun add(context: Context, sbn: StatusBarNotification, parsed: NotifHygiene.Parsed? = null) {
        val item = Item(keyOf(sbn), toJson(context, sbn, parsed))
        synchronized(lock) {
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key == item.key) iterator.remove()
            }
            items.addFirst(item)
            while (items.size > CAPACITY) items.removeLast()
        }
    }

    fun replace(context: Context, notifications: Array<StatusBarNotification>?) {
        val sorted = notifications?.sortedBy { it.postTime } ?: emptyList()
        synchronized(lock) {
            items.clear()
            for (sbn in sorted) {
                if (sbn.packageName == context.packageName) continue
                items.addFirst(Item(keyOf(sbn), toJson(context, sbn)))
                while (items.size > CAPACITY) items.removeLast()
            }
        }
    }

    fun recent(limit: Int): JSONArray {
        val out = JSONArray()
        synchronized(lock) {
            var count = 0
            for (item in items) {
                if (count >= limit) break
                out.put(JSONObject(item.json.toString()))
                count++
            }
        }
        return out
    }

    private fun keyOf(sbn: StatusBarNotification): String {
        return sbn.packageName + ":" + sbn.id + ":" + (sbn.tag ?: "")
    }

    private fun toJson(context: Context, sbn: StatusBarNotification, parsed: NotifHygiene.Parsed? = null): JSONObject {
        val p = parsed ?: NotifHygiene.parse(context, sbn)
        return JSONObject()
            .put("app", p.appLabel)
            .put("pkg", p.app)
            .put("title", p.redactedTitle)
            .put("text", p.redactedText)
            .put("time", Instant.ofEpochMilli(p.postTime).toString())
    }
}

private val labelCache = HashMap<String, String>()

fun appLabel(context: Context, pkg: String): String {
    synchronized(labelCache) {
        labelCache[pkg]?.let { return it }
    }
    val label = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
    } catch (_: Exception) {
        pkg
    }
    synchronized(labelCache) {
        labelCache[pkg] = label
    }
    return label
}
