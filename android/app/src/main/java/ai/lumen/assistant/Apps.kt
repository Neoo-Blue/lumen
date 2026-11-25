package ai.lumen.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Opening apps, links inside them and special screens from any context (the app, or the always-on service). */
object Apps {
    val SCREENS = mapOf(
        "wechat.scan" to Triple("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", mapOf("LauncherUI.From.Scaner.Shortcut" to true)),
    )

    fun launchable(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val seen = HashSet<String>(); val rows = ArrayList<Pair<String, String>>()
        for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (seen.add(pkg)) rows.add((info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg) to pkg)
        }
        return rows.sortedBy { it.first.lowercase() }
    }

    fun bestMatch(rows: List<Pair<String, String>>, name: String): String? {
        val needle = name.trim().lowercase(); if (needle.isEmpty()) return null
        var best: String? = null; var top = 0
        for ((label, pkg) in rows) {
            val t = label.lowercase()
            val s = when { t == needle -> 100; t.replace(" ", "") == needle.replace(" ", "") -> 90; t.startsWith(needle) -> 80; needle.startsWith(t) && t.length >= 3 -> 70; t.contains(needle) -> 60; else -> 0 }
            if (s > top) { top = s; best = pkg }
        }
        return if (top >= 60) best else null
    }

    /** Open an app by name or package, a link inside it, or a known screen; true when something opened. Safe from any thread. */
    fun launch(ctx: Context, o: JSONObject): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1); var opened = false
        val go = {
            try {
                val pkg = o.optString("pkg").trim(); val name = o.optString("name").trim(); val link = o.optString("link").ifEmpty { o.optString("url") }.trim()
                opened = SCREENS[o.optString("screen")]?.let { s -> startExternal(ctx, Intent(Intent.ACTION_VIEW).setClassName(s.first, s.second).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).also { i -> s.third.forEach { (k, v) -> i.putExtra(k, v) } }) } ?: false
                val owner = pkg.ifEmpty { if (name.isNotEmpty()) bestMatch(launchable(ctx), name) ?: "" else "" }
                if (!opened && link.isNotEmpty()) {
                    val uri = Uri.parse(link)
                    if (uri.scheme?.lowercase() !in setOf("javascript", "file", "content", "intent", "data"))
                        opened = startExternal(ctx, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (owner.isNotEmpty()) it.setPackage(owner) })
                    if (!opened && owner.isNotEmpty() && uri.scheme == "https") opened = startExternal(ctx, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                if (!opened && owner.isNotEmpty()) {
                    val i = ctx.packageManager.getLaunchIntentForPackage(owner)
                    if (i != null) opened = startExternal(ctx, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (o.optBoolean("fresh", false)) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)))
                }
            } catch (_: Exception) {}
            latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) go() else { Handler(Looper.getMainLooper()).post(go); latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return opened
    }
}

/** What the always-on service can do for the direct line while Lumen's screen is closed. */
object ServiceActions {
    private fun ok(d: Any) = JSONObject().put("ok", true).put("data", d)
    fun run(ctx: Context, method: String, args: JSONObject): JSONObject {
        val a = LumenAccess.instance
        return try { when (method) {
            "notify" -> { Triggers.notify(ctx, args.optString("title"), args.optString("text"), args.optString("open")); ok(JSONObject().put("shown", true)) }
            "openApp", "openUrl" -> if (Apps.launch(ctx, args)) ok(JSONObject().put("opened", true)) else LumenAccess.err("could not open it")
            "setting" -> Knobs.set(ctx, args.optString("name"), args.optString("value"))
            "settings" -> ok(Knobs.read(ctx))
            "skillList" -> ok(Skills.list(ctx))
            "calendarEvents" -> Cal.events(ctx, args)
            "live" -> LiveUpdates.post(ctx, args)
            "assistContext" -> AssistContext.read()
            "health" -> {
                val what = args.optString("what").trim()
                if (what.isEmpty()) ok(HealthData.overview(ctx)) else HealthData.query(ctx, what)
            }
            "stashSearch" -> Stash.search(ctx, args.optString("query"), args.optInt("limit", 10).coerceIn(1, 50))
            "stashNote" -> Stash.addNote(ctx, args.optString("text"))
            "stashStats" -> ok(Stash.stats(ctx))
            "localRules" -> LocalRules.handle(ctx, args)
            "parked" -> Driving.parked(ctx)
            "teachStart" -> LumenAccess.instance?.teachStart(args.optString("name")) ?: LumenAccess.err("screen control is off")
            "teachStop" -> LumenAccess.instance?.teachStop() ?: LumenAccess.err("screen control is off")
            "battery" -> ctx.getSystemService(android.os.BatteryManager::class.java)?.let { b -> ok(JSONObject().put("level", b.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceAtLeast(0)).put("charging", b.isCharging)) } ?: LumenAccess.err("unavailable")
            "location" -> lastFix(ctx)
            "notifReplyTargets" -> ReplyCache.targets(ctx)
            // notifReply is never run from here: a reply is sent only from Lumen's own screen, after the person taps Send
            "triage" -> NotifTriage.handle(ctx, args)
            "notifDigest" -> NotifTriage.digest(ctx)
            "calendarAdd" -> Cal.add(ctx, args)
            "localModel" -> LocalModel.handle(ctx, args) // the AI model on this phone: download, start, stop, status
            "refreshTriggers" -> { Triggers.forget(); ok(JSONObject().put("refreshed", true)) }
            "lifeSearch" -> ok(Powers.lifeSearch(ctx, args.optString("query"), args.optString("from"), args.optString("to"), args.optInt("limit", 8).coerceIn(1, 25)))
            else -> {
                if (a == null) return LumenAccess.err("screen control is off")
                when (method) {
                    "screenRead" -> ok(a.read())
                    "screenshot" -> a.screenshot()
                    "screenTap" -> a.tap(args)
                    "screenType" -> a.type(args)
                    "screenScroll" -> a.scroll(args)
                    "press" -> a.press(args.optString("key"))
                    "screenDo" -> Skills.runSteps(ctx, args.optJSONArray("steps") ?: JSONArray(), args.optJSONObject("params") ?: JSONObject(), "batch") { Apps.launch(ctx, it.put("fresh", true)) }
                    "skillRun" -> Skills.run(ctx, args.optString("name"), args.optJSONObject("params") ?: JSONObject()) { Apps.launch(ctx, it.put("fresh", true)) }
                    "formReview" -> a.sheet.ask("review", args)?.let { r -> if (r.optBoolean("ok")) { a.approve(args.optJSONArray("approves")); ok(JSONObject().put("fields", r.optJSONArray("fields") ?: JSONArray()).put("confirmed", true)) } else LumenAccess.err("the person said not now") } ?: LumenAccess.err("overlay unavailable")
                    "toggle" -> { val l = java.util.concurrent.CountDownLatch(1); var res = LumenAccess.err("timeout")
                        Handler(Looper.getMainLooper()).post { a.toggle(args.optString("name"), if (args.has("on")) args.optBoolean("on") else null) { res = it; l.countDown() } }
                        l.await(8, java.util.concurrent.TimeUnit.SECONDS); res }
                    else -> LumenAccess.err("open Lumen for this one")
                }
            }
        } } catch (e: Exception) { LumenAccess.err("failed: ${e.message}") }
    }
}


/** The phone's calendar without Lumen's screen open (email bookings filed by the server, the routines' agenda). */
object Cal {
    private fun time(raw: String): Long? = raw.trim().takeIf { it.isNotEmpty() }?.let { t ->
        runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
    private fun allowed(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun events(ctx: Context, args: JSONObject): JSONObject {
        if (!allowed(ctx, android.Manifest.permission.READ_CALENDAR)) return LumenAccess.err("calendar permission is off")
        val from = time(args.optString("from")) ?: System.currentTimeMillis(); val to = time(args.optString("to")) ?: (from + 86_400_000L)
        val b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon(); android.content.ContentUris.appendId(b, from); android.content.ContentUris.appendId(b, to)
        val out = JSONArray()
        ctx.contentResolver.query(b.build(), arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END, android.provider.CalendarContract.Instances.EVENT_LOCATION, android.provider.CalendarContract.Instances.ALL_DAY), null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.length() < 100) out.put(JSONObject().put("title", c.getString(0).orEmpty()).put("start", java.time.Instant.ofEpochMilli(c.getLong(1)).toString())
                .put("end", java.time.Instant.ofEpochMilli(c.getLong(2)).toString()).put("location", c.getString(3).orEmpty()).put("allDay", c.getInt(4) == 1))
        }
        return JSONObject().put("ok", true).put("data", JSONObject().put("events", out))
    }

    fun add(ctx: Context, args: JSONObject): JSONObject {
        if (!allowed(ctx, android.Manifest.permission.WRITE_CALENDAR)) return LumenAccess.err("calendar permission is off")
        val title = args.optString("title").trim().take(200); val start = time(args.optString("start")) ?: return LumenAccess.err("bad start")
        val end = (time(args.optString("end")) ?: (start + 3_600_000L)).coerceAtLeast(start)
        val cal = ctx.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI, arrayOf(android.provider.CalendarContract.Calendars._ID),
            "${android.provider.CalendarContract.Calendars.VISIBLE} = 1 AND ${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${android.provider.CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return LumenAccess.err("no calendar to write to")
        if (title.isEmpty()) return LumenAccess.err("bad title")
        val v = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.CALENDAR_ID, cal); put(android.provider.CalendarContract.Events.TITLE, title)
            put(android.provider.CalendarContract.Events.DTSTART, start); put(android.provider.CalendarContract.Events.DTEND, end)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.time.ZoneId.systemDefault().id)
            args.optString("location").takeIf { it.isNotBlank() }?.let { put(android.provider.CalendarContract.Events.EVENT_LOCATION, it.take(200)) }
            args.optString("notes").takeIf { it.isNotBlank() }?.let { put(android.provider.CalendarContract.Events.DESCRIPTION, it.take(500)) }
        }
        val uri = ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, v) ?: return LumenAccess.err("could not add it")
        return JSONObject().put("ok", true).put("data", JSONObject().put("id", uri.lastPathSegment))
    }
}

/** The phone's last known position without Lumen's screen (time to leave); no new fix is requested from here. */
@android.annotation.SuppressLint("MissingPermission")
fun lastFix(ctx: Context): JSONObject {
    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return LumenAccess.err("location permission is off")
    val lm = ctx.getSystemService(android.location.LocationManager::class.java) ?: return LumenAccess.err("unavailable")
    val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return LumenAccess.err("no recent location")
    return JSONObject().put("ok", true).put("data", JSONObject().put("lat", best.latitude).put("lon", best.longitude).put("accuracy", best.accuracy.toInt()).put("at", java.time.Instant.ofEpochMilli(best.time).toString()))
}
