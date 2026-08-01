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

    /**
     * Les n° de catalogue n'ont pas de format unique. Trois familles couvrent l'essentiel :
     *  - anglo-saxon  : SHVL 804, PCS 7027, LP-9001, CLVLX 240
     *  - Pathé / EMI  : 2C 062-11.653, 2C 068 82.456
     *  - Philips, Polydor, Barclay, Vogue : 6325 022, 2473 105, 80 502, 30.123
     *
     * Les micro-labels (SR-04, TG001, AMREP049) tombent dans la première famille :
     * lettres collées aux chiffres, séparateur facultatif.
     */
    private val CATNO_PATTERNS = listOf(
        // au moins une lettre, puis des chiffres, avec suffixe éventuel
        Regex("(?<![A-Z0-9])[A-Z0-9]{0,3}[A-Z][A-Z0-9]{0,4}[ .\\-]?\\d{1,6}(?:[ .\\-]\\d{2,6})?(?:[ .\\-][A-Z0-9]{1,4})?(?![A-Z0-9])"),
        // tout en chiffres, mais groupés : 6325 022, 80 502, 30.123
        Regex("(?<!\\d)\\d{2,4}[ .]\\d{2,4}(?:[ .]\\d{1,3})?(?!\\d)")
    )

    private val CATNO_BLACKLIST = listOf(
        "RPM", "STEREO", "MONO", "SIDE", "FACE", "VOL", "TRACK", "MIN", "SEC",
        "BPM", "AAD", "DDD", "ADD", "TEL", "REF", "COPYRIGHT"
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
    fun extractCatalogNumbers(text: Text, max: Int = 6): List<String> {
        val raw = text.text.uppercase()
        val found = LinkedHashMap<String, Int>()

        for ((familyIndex, re) in CATNO_PATTERNS.withIndex()) {
            for (m in re.findAll(raw)) {
                val cand = m.value.trim().trim('.', '-')
                // les petits labels font court : SR-04, TG001, AMREP049, PBR12
                if (cand.length !in 3..18) continue

                val digits = cand.count { it.isDigit() }
                val letters = cand.takeWhile { it.isLetter() || it.isDigit() }.filter { it.isLetter() }
                if (digits < 1) continue
                if (digits < 2 && letters.length < 2) continue
                if (CATNO_BLACKLIST.any { cand.startsWith(it) && cand.length <= it.length + 4 }) continue

                // une année seule n'est pas un n° de catalogue
                if (cand.none { it.isLetter() } && cand.none { it == ' ' || it == '.' }) continue
                if (cand.matches(Regex("^(19|20)\\d{2}$"))) continue

                var score = if (familyIndex == 0) 4 else 2
                if (letters.length in 2..5) score += 3
                if (cand.contains(' ') || cand.contains('-') || cand.contains('.')) score += 2
                if (digits in 3..7) score += 2
                if (digits == 1) score -= 2
                if (cand.length in 5..12) score += 1
                if (cand.first().isDigit() && letters.isEmpty()) score -= 1

                found[cand] = maxOf(found[cand] ?: 0, score)
            }
        }
        return found.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }
}
