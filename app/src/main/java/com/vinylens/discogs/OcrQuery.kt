package com.vinylens.discogs

import com.google.mlkit.vision.text.Text

/**
 * Deux traitements différents selon la face photographiée :
 *  - RECTO  : les plus gros textes = artiste + titre
 *  - VERSO  : on cherche le n° de catalogue et le code-barres, qui identifient le pressage exact
 */
object OcrQuery {

    private val NOISE = listOf(
        "stereo", "mono", "hi-fi", "hifi", "long play", "microgroove", "rpm",
        "side a", "side b", "face a", "face b", "made in", "printed in",
        "all rights reserved", "unauthorised", "unauthorized", "copying",
        "manufactured", "distributed", "produced by", "gatefold", "vol.",
        "disque", "album", "tous droits"
    )

    private val CLEAN_RE = Regex("[^\\p{L}\\p{N}&'’\\-. ]")
    private val SPACES_RE = Regex("\\s+")

    // 12 ou 13 chiffres, éventuellement séparés par des espaces ou tirets (EAN / UPC)
    private val BARCODE_RE = Regex("(?<!\\d)(?:\\d[ \\-]?){11,12}\\d(?!\\d)")

    // Ex. SHVL 804 / PCS 7027 / LP-9001 / 88985 44950 1 / CDP 7 46001 2
    private val CATNO_RE = Regex("\\b[A-Z]{1,6}[ \\-]?\\d{2,6}(?:[ \\-][A-Z0-9]{1,4})?\\b")

    private val CATNO_BLACKLIST = listOf(
        "RPM", "LP", "EP", "CD", "AAD", "DDD", "ADD", "STEREO", "MONO", "SIDE", "FACE",
        "VOL", "NO", "TRACK", "MIN", "SEC", "BPM"
    )

    private fun clean(raw: String): String =
        raw.replace(CLEAN_RE, " ").replace(SPACES_RE, " ").trim()

    private fun isNoise(s: String): Boolean {
        val low = s.lowercase()
        if (low.length < 3) return true
        if (low.count { it.isLetter() } < 2) return true
        return NOISE.any { low == it || (low.length <= it.length + 4 && low.contains(it)) }
    }

    /** Lignes candidates, les plus grosses d'abord (artiste, titre). */
    fun candidates(text: Text, max: Int = 10): List<String> {
        data class Cand(val txt: String, val h: Int)

        val list = ArrayList<Cand>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val t = clean(line.text)
                if (t.isBlank() || t.length > 60 || isNoise(t)) continue
                list.add(Cand(t, line.boundingBox?.height() ?: 0))
            }
        }
        return list.sortedByDescending { it.h }
            .distinctBy { it.txt.lowercase() }
            .take(max)
            .map { it.txt }
    }

    fun suggestQuery(candidates: List<String>): String =
        candidates.take(2).joinToString(" ").trim()

    /** Code-barres EAN/UPC (12-13 chiffres) trouvé au dos. */
    fun extractBarcode(text: Text): String? {
        for (m in BARCODE_RE.findAll(text.text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length == 12 || digits.length == 13) return digits
        }
        return null
    }

    /** N° de catalogue probables, le plus vraisemblable en premier. */
    fun extractCatalogNumbers(text: Text, max: Int = 5): List<String> {
        val found = LinkedHashMap<String, Int>()
        for (m in CATNO_RE.findAll(text.text.uppercase())) {
            val raw = m.value.trim()
            val letters = raw.takeWhile { it.isLetter() }
            if (letters.isEmpty()) continue
            if (CATNO_BLACKLIST.any { letters == it }) continue
            val digits = raw.count { it.isDigit() }
            if (digits < 2) continue
            // Un pressage a rarement un n° de plus de 14 caractères
            if (raw.length > 14) continue

            var score = 0
            if (letters.length in 2..5) score += 3
            if (raw.contains(' ') || raw.contains('-')) score += 2
            if (digits in 3..6) score += 2
            if (raw.length in 5..12) score += 1
            found[raw] = maxOf(found[raw] ?: 0, score)
        }
        return found.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }
}
