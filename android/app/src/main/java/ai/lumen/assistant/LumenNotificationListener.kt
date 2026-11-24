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
            NotificationStore.replace(this, activeNotifications)
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationStore.add(this, sbn)
        Triggers.posted(this, sbn)
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

    fun add(context: Context, sbn: StatusBarNotification) {
        val item = Item(keyOf(sbn), toJson(context, sbn))
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

    private fun toJson(context: Context, sbn: StatusBarNotification): JSONObject {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        return JSONObject()
            .put("app", appLabel(context, sbn.packageName))
            .put("pkg", sbn.packageName)
            .put("title", title)
            .put("text", text)
            .put("time", Instant.ofEpochMilli(sbn.postTime).toString())
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
