package com.vinylens.discogs

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Une fiche locale : disque mis de côté, brouillon à soumettre, ajout réussi,
 * ou ajout en attente de réseau.
 */
data class Item(
    val id: Long = System.currentTimeMillis(),
    val kind: String,
    val artist: String = "",
    val title: String = "",
    val catno: String = "",
    val label: String = "",
    val year: String = "",
    val format: String = "",
    val notes: String = "",
    val releaseId: Int = 0,
    val instanceId: Int = 0,
    val folderId: Int = 1,
    val frontPath: String = "",
    val backPath: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("artist", artist); put("title", title)
        put("catno", catno); put("label", label); put("year", year); put("format", format)
        put("notes", notes); put("releaseId", releaseId); put("instanceId", instanceId)
        put("folderId", folderId); put("frontPath", frontPath); put("backPath", backPath)
    }

    fun label(): String {
        val head = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ")
        val tail = listOf(catno, label, year).filter { it.isNotBlank() }.joinToString(" · ")
        return listOf(head.ifBlank { "Sans titre" }, tail).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    fun photos(): List<String> = listOf(frontPath, backPath).filter { it.isNotBlank() }
}

object Store {

    const val REVIEW = "review"     // à réidentifier plus tard
    const val CREATE = "create"     // absent de Discogs, à soumettre
    const val ADDED = "added"       // ajouté à la collection pendant la session
    const val PENDING = "pending"   // ajout à rejouer quand le réseau revient

    private const val FILE = "vinylens_items.json"
    private val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    private fun file(c: Context) = File(c.filesDir, FILE)

    @Synchronized
    fun all(c: Context): MutableList<Item> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Item(
                        id = o.optLong("id"), kind = o.optString("kind"),
                        artist = o.optString("artist"), title = o.optString("title"),
                        catno = o.optString("catno"), label = o.optString("label"),
                        year = o.optString("year"), format = o.optString("format"),
                        notes = o.optString("notes"), releaseId = o.optInt("releaseId"),
                        instanceId = o.optInt("instanceId"), folderId = o.optInt("folderId", 1),
                        frontPath = o.optString("frontPath"), backPath = o.optString("backPath")
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(c: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            file(c).writeText(arr.toString())
        } catch (e: Exception) {
            // disque plein ou stockage indisponible : on ne fait pas planter la session
        }
    }

    fun add(c: Context, item: Item) {
        val items = all(c)
        items.add(item)
        write(c, items)
    }

    fun remove(c: Context, id: Long) {
        val items = all(c).filterNot { it.id == id }
        write(c, items)
    }

    fun update(c: Context, item: Item) {
        val items = all(c).map { if (it.id == item.id) item else it }
        write(c, items)
    }

    fun byKind(c: Context, kind: String): List<Item> =
        all(c).filter { it.kind == kind }.sortedByDescending { it.id }

    fun count(c: Context, kind: String): Int = all(c).count { it.kind == kind }

    fun clearKind(c: Context, kind: String) {
        write(c, all(c).filterNot { it.kind == kind })
    }

    /** Copie une photo dans le stockage durable de l'app (le cache est effaçable). */
    fun persistPhoto(c: Context, uri: Uri, prefix: String): String = try {
        val dir = File(c.filesDir, "photos").apply { mkdirs() }
        val out = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
        c.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out.absolutePath else ""
    } catch (e: Exception) {
        ""
    }

    private fun csvCell(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** Export CSV, prêt à ouvrir dans un tableur ou à recouper avec Discogs. */
    fun exportCsv(c: Context, kinds: List<String>, fileName: String): File? = try {
        val items = all(c).filter { it.kind in kinds }.sortedBy { it.id }
        val sb = StringBuilder("Date;Type;Artiste;Titre;N° catalogue;Label;Année;Format;Notes;Release Discogs;Photos\n")
        for (it in items) {
            sb.append(
                listOf(
                    csvCell(stamp.format(Date(it.id))),
                    csvCell(it.kind),
                    csvCell(it.artist),
                    csvCell(it.title),
                    csvCell(it.catno),
                    csvCell(it.label),
                    csvCell(it.year),
                    csvCell(it.format),
                    csvCell(it.notes),
                    csvCell(if (it.releaseId > 0) "https://www.discogs.com/release/${it.releaseId}" else ""),
                    csvCell(it.photos().joinToString(" | ") { p -> File(p).name })
                ).joinToString(";")
            )
            sb.append('\n')
        }
        val dir = File(c.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeText(sb.toString())
        f
    } catch (e: Exception) {
        null
    }
}
