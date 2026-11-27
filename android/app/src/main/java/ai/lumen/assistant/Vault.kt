package ai.lumen.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's notes vault on the phone (an Obsidian vault, or any folder of Markdown notes), chosen once with
 * Android's folder picker. Lumen lists its folders and notes and writes captured ideas into it; it never leaves
 * that folder (paths are walked by name, and ".." or hidden folders are refused).
 */
object Vault {
    private fun prefs(c: Context) = c.getSharedPreferences("vault", Context.MODE_PRIVATE)
    fun uri(c: Context): Uri? = prefs(c).getString("uri", null)?.let { Uri.parse(it) }

    fun keep(c: Context, picked: Uri): JSONObject {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        c.contentResolver.takePersistableUriPermission(picked, flags)
        val name = DocumentFile.fromTreeUri(c, picked)?.name ?: "vault"
        prefs(c).edit().putString("uri", picked.toString()).putString("name", name).apply()
        return ok(JSONObject().put("picked", name))
    }

    private fun root(c: Context): DocumentFile? = uri(c)?.let { DocumentFile.fromTreeUri(c, it) }?.takeIf { it.canRead() }
    private fun ok(d: Any) = JSONObject().put("ok", true).put("data", d)
    private val hidden = Regex("^[.]|^_?trash$", RegexOption.IGNORE_CASE)

    /** Folders and Markdown notes (newest first), three levels deep at most. */
    fun list(c: Context): JSONObject {
        val r = root(c) ?: return LumenAccess.err("no vault chosen")
        val folders = JSONArray(); val notes = ArrayList<Pair<String, Long>>()
        fun walk(d: DocumentFile, path: String, depth: Int) {
            for (f in d.listFiles()) {
                val n = f.name ?: continue
                if (hidden.containsMatchIn(n)) continue
                if (f.isDirectory) { folders.put("$path$n"); if (depth < 3) walk(f, "$path$n/", depth + 1) }
                else if (n.endsWith(".md", true) && notes.size < 400) notes += "$path$n" to f.lastModified()
            }
        }
        walk(r, "", 1)
        return ok(JSONObject().put("name", r.name).put("folders", folders).put("notes", JSONArray(notes.sortedByDescending { it.second }.take(150).map { it.first })))
    }

    private fun safe(path: String): List<String>? = path.trim('/').split('/').filter { it.isNotEmpty() }.takeIf { p -> p.isNotEmpty() && p.none { it == ".." || it.startsWith(".") } }

    /**
     * mode "append": add the text to the end of an existing note (path "Ideas/Product.md").
     * mode "create": a new note named by the path's last part inside its folder (made if missing).
     */
    fun write(c: Context, args: JSONObject): JSONObject {
        val r = root(c) ?: return LumenAccess.err("no vault chosen")
        val parts = safe(args.optString("path")) ?: return LumenAccess.err("bad path")
        val text = args.optString("text").take(20000); if (text.isBlank()) return LumenAccess.err("nothing to write")
        var dir = r
        for (seg in parts.dropLast(1)) dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg) ?: return LumenAccess.err("could not make $seg")
        val leaf = parts.last().let { if (it.endsWith(".md", true)) it else "$it.md" }
        val existing = dir.findFile(leaf)
        return try {
            if (args.optString("mode") == "append" && existing != null) {
                val before = c.contentResolver.openInputStream(existing.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                c.contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write((before.trimEnd() + "\n\n" + text.trim() + "\n").toByteArray()) }
                ok(JSONObject().put("wrote", "${parts.dropLast(1).joinToString("/")}/$leaf".trim('/')).put("mode", "append"))
            } else {
                var name = leaf.removeSuffix(".md"); var n = 2
                while (dir.findFile("$name.md") != null) name = "${leaf.removeSuffix(".md")} $n".also { n++ }
                val f = dir.createFile("text/markdown", "$name.md") ?: return LumenAccess.err("could not create the note")
                c.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write((text.trim() + "\n").toByteArray()) }
                ok(JSONObject().put("wrote", "${parts.dropLast(1).joinToString("/")}/${f.name}".trim('/')).put("mode", "create"))
            }
        } catch (e: Exception) { LumenAccess.err("could not write: ${e.message}") }
    }
}
