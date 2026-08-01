package com.vinylens.discogs

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Release(
    val id: Int,
    val title: String,
    val subtitle: String,
    val thumb: String,
    val url: String
)

data class Folder(val id: Int, val name: String, val count: Int)

/** Critères envoyés à Discogs. Plus il y en a, plus le pressage trouvé est précis. */
data class Criteria(
    val q: String = "",
    val catno: String = "",
    val barcode: String = "",
    val vinylOnly: Boolean = true
) {
    fun label(): String = when {
        barcode.any { it.isLetter() } -> "identifiant $barcode"
        barcode.isNotBlank() -> "code-barres $barcode"
        catno.isNotBlank() && q.isNotBlank() -> "n° $catno + « $q »"
        catno.isNotBlank() -> "n° de catalogue $catno"
        else -> "« $q »"
    }

    fun isEmpty(): Boolean = q.isBlank() && catno.isBlank() && barcode.isBlank()
}

object DiscogsApi {

    private const val UA = "VinyLens/1.0 (+https://github.com/vinylens)"
    private const val BASE = "https://api.discogs.com"

    /**
     * Discogs autorise 60 requêtes/minute par jeton. On se cale à 50 pour garder
     * de la marge, et on patiente si la fenêtre est pleine plutôt que de se faire jeter.
     */
    private const val MAX_PER_MINUTE = 50
    private val callTimes = ArrayDeque<Long>()

    private fun throttle() {
        synchronized(callTimes) {
            while (true) {
                val now = System.currentTimeMillis()
                while (callTimes.isNotEmpty() && now - callTimes.first() > 60_000) callTimes.removeFirst()
                if (callTimes.size < MAX_PER_MINUTE) {
                    callTimes.addLast(now)
                    return
                }
                val wait = 60_000 - (now - callTimes.first()) + 50
                try { Thread.sleep(wait.coerceIn(50, 60_000)) } catch (e: InterruptedException) { return }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun req(url: String, token: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (token.isNotBlank()) b.header("Authorization", "Discogs token=$token")
        return b
    }

    private fun message(code: Int, action: String): String = when (code) {
        401 -> "Jeton invalide ou expiré (menu ⋮ > Compte Discogs)."
        403 -> "Ce jeton n'a pas le droit de $action."
        404 -> "Introuvable côté Discogs."
        422 -> "Discogs a refusé l'opération (déjà présent ?)."
        429 -> "Trop de requêtes, attends une minute."
        else -> "Erreur Discogs (HTTP $code)."
    }

    @Throws(IOException::class)
    private fun call(request: Request, action: String, retryOn429: Boolean = true): String {
        throttle()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 429 && retryOn429) {
                // Discogs indique parfois combien de temps patienter
                val wait = (resp.header("Retry-After")?.toLongOrNull() ?: 5L).coerceIn(1, 60) * 1000
                try { Thread.sleep(wait) } catch (e: InterruptedException) { }
                return call(request, action, retryOn429 = false)
            }
            if (!resp.isSuccessful) throw IOException(message(resp.code, action))
            return body
        }
    }

    // ---------- Recherche ----------

    fun webSearchUrl(c: Criteria): String {
        val sb = StringBuilder("https://www.discogs.com/search/?type=release")
        if (c.q.isNotBlank()) sb.append("&q=").append(enc(c.q))
        if (c.catno.isNotBlank()) sb.append("&catno=").append(enc(c.catno))
        if (c.barcode.isNotBlank()) sb.append("&barcode=").append(enc(c.barcode))
        if (c.vinylOnly) sb.append("&format_exact=Vinyl")
        return sb.toString()
    }

    @Throws(IOException::class)
    fun search(c: Criteria, token: String): List<Release> {
        val sb = StringBuilder("$BASE/database/search?type=release&per_page=40")
        if (c.q.isNotBlank()) sb.append("&q=").append(enc(c.q))
        if (c.catno.isNotBlank()) sb.append("&catno=").append(enc(c.catno))
        if (c.barcode.isNotBlank()) sb.append("&barcode=").append(enc(c.barcode))
        if (c.vinylOnly) sb.append("&format=Vinyl")

        val body = call(req(sb.toString(), token).build(), "chercher")
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Release>(results.length())

        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue

            val labels = o.optJSONArray("label")
            val label = if (labels != null && labels.length() > 0) labels.optString(0) else ""
            val formats = o.optJSONArray("format")
            val fmt = StringBuilder()
            if (formats != null) {
                for (f in 0 until minOf(formats.length(), 3)) {
                    if (fmt.isNotEmpty()) fmt.append(", ")
                    fmt.append(formats.optString(f))
                }
            }

            val subtitle = listOf(
                o.optString("year"), o.optString("country"), label,
                o.optString("catno"), fmt.toString()
            ).filter { it.isNotBlank() }.joinToString(" · ")

            val uri = o.optString("uri")
            val id = o.optInt("id")
            val link = when {
                uri.startsWith("http") -> uri
                uri.isNotBlank() -> "https://www.discogs.com$uri"
                else -> "https://www.discogs.com/release/$id"
            }

            out.add(
                Release(
                    id = id,
                    title = o.optString("title").ifBlank { "Sans titre" },
                    subtitle = subtitle,
                    thumb = o.optString("cover_image").ifBlank { o.optString("thumb") },
                    url = link
                )
            )
        }
        return out
    }

    // ---------- Compte ----------

    /** Vérifie le jeton et renvoie le pseudo Discogs associé. */
    @Throws(IOException::class)
    fun identity(token: String): String {
        val body = call(req("$BASE/oauth/identity", token).build(), "lire ton compte")
        return JSONObject(body).optString("username")
    }

    @Throws(IOException::class)
    fun folders(username: String, token: String): List<Folder> {
        val url = "$BASE/users/${enc(username)}/collection/folders"
        val arr = JSONObject(call(req(url, token).build(), "lire tes dossiers"))
            .optJSONArray("folders") ?: return emptyList()
        val out = ArrayList<Folder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // le dossier 0 = "All", on ne peut pas y ajouter
            if (o.optInt("id") == 0) continue
            out.add(Folder(o.optInt("id"), o.optString("name"), o.optInt("count")))
        }
        return out
    }

    /** Combien d'exemplaires de ce pressage sont déjà dans la collection. */
    @Throws(IOException::class)
    fun copiesInCollection(username: String, releaseId: Int, token: String): Int {
        val url = "$BASE/users/${enc(username)}/collection/releases/$releaseId"
        return try {
            val o = JSONObject(call(req(url, token).build(), "lire ta collection"))
            o.optJSONObject("pagination")?.optInt("items")
                ?: (o.optJSONArray("releases")?.length() ?: 0)
        } catch (e: IOException) {
            0
        }
    }

    /** Ajoute le pressage à un dossier de la collection. */
    @Throws(IOException::class)
    fun addToCollection(username: String, folderId: Int, releaseId: Int, token: String) {
        val url = "$BASE/users/${enc(username)}/collection/folders/$folderId/releases/$releaseId"
        val empty = ByteArray(0).toRequestBody(null)
        call(req(url, token).post(empty).build(), "modifier ta collection")
    }

    /** Ajoute le pressage à la liste de souhaits. */
    @Throws(IOException::class)
    fun addToWantlist(username: String, releaseId: Int, token: String) {
        val url = "$BASE/users/${enc(username)}/wants/$releaseId"
        val empty = ByteArray(0).toRequestBody(null)
        call(req(url, token).put(empty).build(), "modifier ta wantlist")
    }
}
