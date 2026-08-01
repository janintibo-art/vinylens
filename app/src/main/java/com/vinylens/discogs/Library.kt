package com.vinylens.discogs

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Un disque de la bibliothèque locale : ce que Discogs sait, plus ce que toi seul sais. */
data class Disc(
    val id: Long = System.currentTimeMillis(),
    val artist: String = "",
    val title: String = "",
    val catno: String = "",
    val label: String = "",
    val year: String = "",
    val country: String = "",
    val format: String = "",
    val genres: List<String> = emptyList(),
    val releaseId: Int = 0,
    val discogsUrl: String = "",
    val coverUrl: String = "",
    val coverPath: String = "",
    val photos: List<String> = emptyList(),
    val box: String = "",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val inDiscogs: Boolean = false
) {
    /** Ce qu'on affiche en gros dans la liste. */
    fun heading(): String = when {
        artist.isNotBlank() && title.isNotBlank() -> "$artist — $title"
        title.isNotBlank() -> title
        artist.isNotBlank() -> artist
        else -> "Sans titre"
    }

    fun subheading(): String =
        listOf(year, country, label, catno, format)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** Clé de tri alphabétique : on ignore les articles et la casse. */
    fun sortKey(byTitle: Boolean): String {
        val base = (if (byTitle) title else artist).ifBlank { heading() }
        return base.trim().lowercase()
            .removePrefix("the ").removePrefix("le ").removePrefix("la ").removePrefix("les ")
    }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return listOf(artist, title, catno, label, box, notes, year, format)
            .any { it.lowercase().contains(q) } || genres.any { it.lowercase().contains(q) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("artist", artist); put("title", title); put("catno", catno)
        put("label", label); put("year", year); put("country", country); put("format", format)
        put("genres", JSONArray(genres)); put("releaseId", releaseId); put("discogsUrl", discogsUrl)
        put("coverUrl", coverUrl); put("coverPath", coverPath); put("photos", JSONArray(photos))
        put("box", box); put("notes", notes); put("addedAt", addedAt); put("inDiscogs", inDiscogs)
    }
}

object Library {

    private const val FILE = "vinylens_library.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun file(c: Context) = File(c.filesDir, FILE)

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    @Synchronized
    fun all(c: Context): MutableList<Disc> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Disc>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Disc(
                        id = o.optLong("id"), artist = o.optString("artist"),
                        title = o.optString("title"), catno = o.optString("catno"),
                        label = o.optString("label"), year = o.optString("year"),
                        country = o.optString("country"), format = o.optString("format"),
                        genres = strings(o, "genres"), releaseId = o.optInt("releaseId"),
                        discogsUrl = o.optString("discogsUrl"), coverUrl = o.optString("coverUrl"),
                        coverPath = o.optString("coverPath"), photos = strings(o, "photos"),
                        box = o.optString("box"), notes = o.optString("notes"),
                        addedAt = o.optLong("addedAt"), inDiscogs = o.optBoolean("inDiscogs")
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(c: Context, discs: List<Disc>) {
        try {
            val arr = JSONArray()
            discs.forEach { arr.put(it.toJson()) }
            file(c).writeText(arr.toString())
        } catch (e: Exception) {
            // stockage indisponible : on ne fait pas tomber la session
        }
    }

    fun add(c: Context, disc: Disc) {
        val list = all(c)
        list.add(disc)
        write(c, list)
    }

    fun update(c: Context, disc: Disc) {
        write(c, all(c).map { if (it.id == disc.id) disc else it })
    }

    fun get(c: Context, id: Long): Disc? = all(c).firstOrNull { it.id == id }

    fun count(c: Context): Int = all(c).size

    /** Supprime la fiche et les images qui n'appartenaient qu'à elle. */
    fun delete(c: Context, disc: Disc) {
        (disc.photos + disc.coverPath).filter { it.isNotBlank() }.forEach {
            try { File(it).delete() } catch (e: Exception) { }
        }
        write(c, all(c).filterNot { it.id == disc.id })
    }

    fun hasRelease(c: Context, releaseId: Int): Boolean =
        releaseId > 0 && all(c).any { it.releaseId == releaseId }

    fun boxes(c: Context): List<String> =
        all(c).map { it.box }.filter { it.isNotBlank() }.distinct().sorted()

    fun genres(c: Context): List<String> =
        all(c).flatMap { it.genres }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

    // ---------- fichiers ----------

    private fun dir(c: Context) = File(c.filesDir, "library").apply { mkdirs() }

    fun copyPhoto(c: Context, uri: Uri, discId: Long): String = try {
        val out = File(dir(c), "p_${discId}_${System.currentTimeMillis()}.jpg")
        c.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out.absolutePath else ""
    } catch (e: Exception) {
        ""
    }

    fun copyFile(c: Context, path: String, discId: Long): String = try {
        val src = File(path)
        if (!src.exists()) "" else {
            val out = File(dir(c), "p_${discId}_${System.currentTimeMillis()}.jpg")
            src.copyTo(out, overwrite = true)
            out.absolutePath
        }
    } catch (e: Exception) {
        ""
    }

    /** Télécharge la pochette une bonne fois : la bibliothèque reste consultable hors ligne. */
    fun downloadCover(c: Context, url: String, discId: Long): String = try {
        if (url.isBlank()) "" else {
            val req = Request.Builder().url(url)
                .header("User-Agent", "VinyLens/3.1")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else {
                    val out = File(dir(c), "cover_$discId.jpg")
                    resp.body?.byteStream()?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    if (out.length() > 0) out.absolutePath else ""
                }
            }
        }
    } catch (e: Exception) {
        ""
    }
}
