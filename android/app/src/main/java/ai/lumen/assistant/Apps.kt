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
