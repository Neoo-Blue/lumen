package ai.lumen.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skills Lumen learned on this phone: how to get something done in an app, as steps it can replay without the
 * assistant (open, tap, type, scroll, press, wait, toggle, setting, expect). Stored only on this phone, in the
 * app's private files. Parts that change are written {like_this} and filled from the run's params.
 */
object Skills {
    private fun file(ctx: Context) = File(ctx.filesDir, "skills.json")
    @Synchronized fun all(ctx: Context): JSONObject = try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }
    @Synchronized private fun write(ctx: Context, o: JSONObject) { file(ctx).writeText(o.toString()) }

    fun list(ctx: Context): JSONArray {
        val o = all(ctx); val out = JSONArray()
        o.keys().forEach { k -> val s = o.getJSONObject(k); out.put(JSONObject().put("name", k).put("when", s.optString("when")).put("app", s.optString("app"))
            .put("params", s.optJSONArray("params") ?: JSONArray()).put("steps", s.optJSONArray("steps")?.length() ?: 0).put("uses", s.optInt("uses")).put("lastUsed", s.optString("lastUsed"))) }
        return out
    }

    fun save(ctx: Context, args: JSONObject): JSONObject {
        val name = args.optString("name").trim().take(60)
        val steps = args.optJSONArray("steps")
        if (name.isEmpty() || steps == null || steps.length() == 0 || steps.length() > 40) return LumenAccess.err("a skill needs a name and 1 to 40 steps")
        val o = all(ctx)
        val old = o.optJSONObject(name)
        o.put(name, JSONObject().put("when", args.optString("when").take(200)).put("app", args.optString("app").take(80))
            .put("params", args.optJSONArray("params") ?: JSONArray()).put("steps", steps)
            .put("uses", old?.optInt("uses") ?: 0).put("learned", java.time.Instant.now().toString()))
        write(ctx, o)
        Telemetry.info("skill", "learned: $name (${steps.length()} steps)")
        return JSONObject().put("ok", true).put("data", JSONObject().put("saved", name).put("steps", steps.length()))
    }

    fun delete(ctx: Context, name: String): JSONObject {
        val o = all(ctx); val had = o.remove(name) != null; write(ctx, o)
        return JSONObject().put("ok", true).put("data", JSONObject().put("forgot", if (had) name else ""))
    }

    private fun fill(v: String, params: JSONObject): String = Regex("\\{(\\w+)\\}").replace(v) { m -> params.optString(m.groupValues[1], m.value) }
    private fun fillAll(x: Any?, params: JSONObject): Any? = when (x) {
        is String -> fill(x, params)
        is JSONObject -> JSONObject().also { o -> x.keys().forEach { k -> o.put(k, fillAll(x.get(k), params)) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(fillAll(x.get(i), params)) }
        else -> x
    }

    /** Replays a skill on a worker thread. Stops at the first step that fails or needs the person's confirmation. */
    fun run(activity: Context, name: String, params: JSONObject, launch: (JSONObject) -> Boolean): JSONObject {
        val o = all(activity)
        val skill = o.optJSONObject(name) ?: return LumenAccess.err("no skill called \"$name\"")
        val r = runSteps(activity, skill.getJSONArray("steps"), params, name, launch)
        if (r.optBoolean("ok")) { val o = all(activity); o.optJSONObject(name)?.let { it.put("uses", it.optInt("uses") + 1).put("lastUsed", java.time.Instant.now().toString()) }; write(activity, o) }
        return r
    }

    /** Runs steps on the phone (a learned skill or a one-off batch); stops at the first failure or confirmation. */
    fun runSteps(activity: Context, raw: JSONArray, params: JSONObject, name: String, launch: (JSONObject) -> Boolean): JSONObject {
        val a = LumenAccess.instance ?: return LumenAccess.err("screen control is off")
        if (raw.length() > 40) return LumenAccess.err("at most 40 steps at a time")
        val steps = fillAll(raw, params) as JSONArray
        val log = JSONArray()
        // a progress card over the app while several steps run
        val labels = (0 until steps.length()).map { describe(steps.optJSONObject(it) ?: JSONObject()) }
        val showing = steps.length() >= 3
        val title = if (name == "batch") "Lumen is filling this in" else "Running \u201c$name\u201d"
        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            if (showing) a.sheet.progress(title, labels, i)
            val r: JSONObject = when {
                st.has("open") -> { val ok = launch(st.optJSONObject("open") ?: JSONObject().put("name", st.optString("open"))); Thread.sleep(1800); if (ok) okStep("opened") else LumenAccess.err("could not open the app") }
                st.has("tap") -> a.tap(JSONObject().put("text", st.optString("tap")))
                st.has("type") -> a.type(st.optJSONObject("type") ?: JSONObject().put("text", st.optString("type")))
                st.has("scroll") -> a.scroll(JSONObject().put("direction", st.optString("scroll")))
                st.has("press") -> a.press(st.optString("press"))
                st.has("wait") -> { Thread.sleep(st.optLong("wait", 800).coerceIn(100, 8000)); okStep("waited") }
                st.has("expect") -> { a.read(); val want = st.optString("expect").lowercase(); if (a.screenText().lowercase().contains(want)) okStep("saw $want") else LumenAccess.err("expected to see \"${st.optString("expect")}\"") }
                st.has("toggle") -> { val latch = java.util.concurrent.CountDownLatch(1); var res = LumenAccess.err("timeout"); android.os.Handler(android.os.Looper.getMainLooper()).post { a.toggle(st.optString("toggle"), if (st.has("on")) st.optBoolean("on") else null) { res = it; latch.countDown() } }; latch.await(8, java.util.concurrent.TimeUnit.SECONDS); res }
                st.has("setting") -> { val s = st.optJSONObject("setting") ?: JSONObject(); Knobs.set(activity, s.optString("name"), s.optString("value")) }
                else -> LumenAccess.err("unknown step")
            }
            log.put(JSONObject().put("step", i + 1).put("did", st.toString().take(120)).put("ok", r.optBoolean("ok")))
            if (!r.optBoolean("ok")) {
                Telemetry.warn("skill", "$name stopped at step ${i + 1}: ${r.optString("error")}")
                if (showing) a.sheet.hide()
                return JSONObject().put("ok", false).put("error", "step ${i + 1} of ${steps.length()}: ${r.optString("error")}")
                    .put("text", r.optString("text")).put("pkg", r.optString("pkg")).put("log", log).put("screen", a.view())
            }
            if (!st.has("wait") && !st.has("open")) Thread.sleep(250)
        }
        if (showing) { a.sheet.progress(title, labels, labels.size, "All filled in"); Thread.sleep(900); a.sheet.hide() }
        return JSONObject().put("ok", true).put("data", JSONObject().put("ran", name).put("steps", steps.length()).put("screen", a.view()))
    }

    private fun describe(st: JSONObject): String = when {
        st.has("open") -> "Open " + (st.optJSONObject("open")?.let { it.optString("name").ifEmpty { it.optString("link").ifEmpty { it.optString("pkg") } } } ?: st.optString("open"))
        st.has("tap") -> "Tap " + st.optString("tap").take(32)
        st.has("type") -> { val t = st.optJSONObject("type"); val into = t?.optJSONObject("into")?.optString("text")?.trimEnd('*')?.take(24); val v = t?.optString("text") ?: st.optString("type")
            (if (into.isNullOrBlank()) "Type " else "$into: ") + (if (Regex("pass|pin|cvv|security", RegexOption.IGNORE_CASE).containsMatchIn(into ?: "")) "\u2022\u2022\u2022\u2022" else v.take(30)) }
        st.has("scroll") -> "Scroll " + st.optString("scroll")
        st.has("press") -> "Press " + st.optString("press")
        st.has("wait") -> "Wait for the page"
        st.has("expect") -> "Check for " + st.optString("expect").take(30)
        st.has("toggle") -> "Switch " + st.optString("toggle")
        st.has("setting") -> "Set " + (st.optJSONObject("setting")?.optString("name") ?: "")
        else -> "Step"
    }

    private fun okStep(what: String) = JSONObject().put("ok", true).put("data", JSONObject().put("did", what))
}
