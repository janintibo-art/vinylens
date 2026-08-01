package com.vinylens.discogs

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète : les deux fichiers JSON et toutes les images, dans une archive ZIP
 * que l'utilisateur range où il veut. C'est le seul filet contre un téléphone perdu.
 */
object Backup {

    private val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.FRANCE)

    private fun photoDirs(c: Context) = listOf(
        File(c.filesDir, "library") to "library",
        File(c.filesDir, "photos") to "photos"
    )

    fun export(c: Context): File? = try {
        val out = File(c.cacheDir, "exports").apply { mkdirs() }
            .let { File(it, "vinylens_${stamp.format(Date())}.zip") }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (name in listOf("vinylens_library.json", "vinylens_items.json")) {
                val f = File(c.filesDir, name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for ((dir, prefix) in photoDirs(c)) {
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    zip.putNextEntry(ZipEntry("$prefix/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        if (out.length() > 0) out else null
    } catch (e: Exception) {
        null
    }

    data class Result(val discs: Int, val items: Int, val photos: Int, val error: String? = null)

    /**
     * Restauration fusionnante : les fiches déjà présentes (même identifiant) sont conservées,
     * on n'écrase donc jamais un travail en cours par une archive plus ancienne.
     */
    fun restore(c: Context, uri: Uri): Result = try {
        val tmp = File(c.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var photos = 0

        c.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    val safe = name.isNotBlank() && !entry.isDirectory
                    if (safe) {
                        val target = when {
                            entry.name.startsWith("library/") -> File(File(c.filesDir, "library").apply { mkdirs() }, name)
                            entry.name.startsWith("photos/") -> File(File(c.filesDir, "photos").apply { mkdirs() }, name)
                            name.endsWith(".json") -> File(tmp, name)
                            else -> null
                        }
                        if (target != null) {
                            target.outputStream().use { zip.copyTo(it) }
                            if (!name.endsWith(".json")) photos++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return Result(0, 0, 0, "Archive illisible.")

        // --- fiches de la bibliothèque ---
        var addedDiscs = 0
        val libFile = File(tmp, "vinylens_library.json")
        if (libFile.exists()) {
            val current = Library.all(c)
            val known = current.map { it.id }.toMutableSet()
            val restored = readDiscs(c, libFile)
            for (d in restored) {
                if (d.id in known) continue
                known.add(d.id)
                Library.add(c, remapPaths(c, d))
                addedDiscs++
            }
        }

        // --- piles locales ---
        var addedItems = 0
        val itemsFile = File(tmp, "vinylens_items.json")
        if (itemsFile.exists()) {
            val known = Store.all(c).map { it.id }.toMutableSet()
            for (item in readItems(itemsFile)) {
                if (item.id in known) continue
                known.add(item.id)
                Store.add(c, remapItem(c, item))
                addedItems++
            }
        }

        tmp.deleteRecursively()
        Result(addedDiscs, addedItems, photos)
    } catch (e: Exception) {
        Result(0, 0, 0, e.message ?: "Restauration impossible.")
    }

    /** Les chemins absolus de l'ancien téléphone sont réécrits vers ce stockage-ci. */
    private fun remapPaths(c: Context, d: Disc): Disc {
        fun fix(path: String, sub: String): String {
            if (path.isBlank()) return ""
            val f = File(File(c.filesDir, sub), File(path).name)
            return if (f.exists()) f.absolutePath else ""
        }
        return d.copy(
            coverPath = fix(d.coverPath, "library"),
            photos = d.photos.map { fix(it, "library") }.filter { it.isNotBlank() }
        )
    }

    private fun remapItem(c: Context, item: Item): Item {
        fun fix(path: String): String {
            if (path.isBlank()) return ""
            val f = File(File(c.filesDir, "photos"), File(path).name)
            return if (f.exists()) f.absolutePath else ""
        }
        return item.copy(frontPath = fix(item.frontPath), backPath = fix(item.backPath))
    }

    private fun readDiscs(c: Context, f: File): List<Disc> = try {
        val backup = File(c.filesDir, "vinylens_library.json.tmpread")
        f.copyTo(backup, overwrite = true)
        val arr = org.json.JSONArray(backup.readText())
        val out = ArrayList<Disc>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun list(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            out.add(
                Disc(
                    id = o.optLong("id"), artist = o.optString("artist"), title = o.optString("title"),
                    catno = o.optString("catno"), label = o.optString("label"), year = o.optString("year"),
                    country = o.optString("country"), format = o.optString("format"),
                    genres = list("genres"), tracks = list("tracks"), releaseId = o.optInt("releaseId"),
                    discogsUrl = o.optString("discogsUrl"), coverUrl = o.optString("coverUrl"),
                    coverPath = o.optString("coverPath"), photos = list("photos"),
                    box = o.optString("box"), notes = o.optString("notes"),
                    addedAt = o.optLong("addedAt"), inDiscogs = o.optBoolean("inDiscogs")
                )
            )
        }
        backup.delete()
        out
    } catch (e: Exception) {
        emptyList()
    }

    private fun readItems(f: File): List<Item> = try {
        val arr = org.json.JSONArray(f.readText())
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    id = o.optLong("id"), kind = o.optString("kind"), artist = o.optString("artist"),
                    title = o.optString("title"), catno = o.optString("catno"), label = o.optString("label"),
                    year = o.optString("year"), format = o.optString("format"), notes = o.optString("notes"),
                    releaseId = o.optInt("releaseId"), instanceId = o.optInt("instanceId"),
                    folderId = o.optInt("folderId", 1), frontPath = o.optString("frontPath"),
                    backPath = o.optString("backPath")
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}
