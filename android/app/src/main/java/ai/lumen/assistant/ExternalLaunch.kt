package ai.lumen.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
import android.provider.AlarmClock
import android.provider.Settings

fun startExternal(context: Context, intent: Intent): Boolean {
    return try {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

fun openInBrowser(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri)
    val packageName = customTabPackage(context)
    if (packageName != null) {
        intent.setPackage(packageName)
        intent.putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null as IBinder?) })
        intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", Color.parseColor("#07080D"))
        if (startExternal(context, intent)) return true
        intent.setPackage(null)
    }
    return startExternal(context, intent)
}

fun openHttpUrl(context: Context, url: String): Boolean {
    val uri = try {
        Uri.parse(url)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "javascript" || scheme == "file" || scheme == "content" || scheme == "intent") {
        return false
    }
    if (scheme == "http" || scheme == "https") {
        return openInBrowser(context, uri)
    }
    return startExternal(context, Intent(Intent.ACTION_VIEW, uri))
}

fun openStore(context: Context, pkg: String?, name: String?): Boolean {
    if (!pkg.isNullOrBlank()) {
        val id = Uri.encode(pkg)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
        if (startExternal(context, market)) return true
        return openInBrowser(context, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
    }
    if (!name.isNullOrBlank()) {
        val query = Uri.encode(name)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query&c=apps"))
        if (startExternal(context, market)) return true
        return openInBrowser(
            context,
            Uri.parse("https://play.google.com/store/search?q=$query&c=apps")
        )
    }
    return false
}

fun openNavigation(context: Context, destination: String, mode: String): Boolean {
    val query = Uri.encode(destination)
    val modeChar = when (mode) {
        "walking" -> "w"
        "bicycling" -> "b"
        else -> "d"
    }
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$query&mode=$modeChar"))
    if (startExternal(context, nav)) return true
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query"))
    return startExternal(context, geo)
}

fun settingsIntent(page: String): Intent? {
    val action = when (page) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
        "notifications" -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        else -> return null
    }
    return Intent(action)
}

fun alarmIntent(hour: Int, minute: Int, label: String): Intent {
    return Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
    }
}

fun timerIntent(seconds: Int, label: String): Intent {
    return Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
    }
}

private fun customTabPackage(context: Context): String? {
    val pm = context.packageManager
    val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    val browsers = try {
        pm.queryIntentActivities(view, PackageManager.MATCH_ALL)
    } catch (_: Exception) {
        emptyList()
    }
    val services = try {
        pm.queryIntentServices(
            Intent("android.support.customtabs.action.CustomTabsService"),
            PackageManager.MATCH_ALL
        )
    } catch (_: Exception) {
        emptyList()
    }
    val servicePackages = services.map { it.serviceInfo.packageName }.toSet()
    val defaultPackage = try {
        pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }
    if (defaultPackage != null && defaultPackage in servicePackages && isBrowser(defaultPackage)) {
        return defaultPackage
    }
    return browsers.firstOrNull {
        val pkg = it.activityInfo?.packageName
        pkg != null && pkg in servicePackages && isBrowser(pkg)
    }?.activityInfo?.packageName
}

private fun isBrowser(packageName: String): Boolean {
    return packageName != "android" && packageName != "ai.lumen.assistant"
}
