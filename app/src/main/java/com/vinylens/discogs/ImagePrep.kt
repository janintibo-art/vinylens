package com.vinylens.discogs

import android.graphics.Bitmap

/**
 * Renforcement de contraste local. Une gravure de dead wax n'a quasiment pas de contraste
 * global : le sillon est de la même couleur que le disque, seule l'ombre du creux distingue
 * les lettres. On estime donc le fond par un flou fort, on le soustrait, puis on étire ce
 * qui reste. Pas d'OpenCV : tout se fait sur un IntArray, quelques centaines de ms.
 */
object ImagePrep {

    fun enhance(src: Bitmap, maxSide: Int = 1400): Bitmap? = try {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src

        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // luminance
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (((p shr 16 and 0xFF) * 77) + ((p shr 8 and 0xFF) * 151) + ((p and 0xFF) * 28)) shr 8
        }

        // fond estimé : deux passes de flou boîte (séparable, donc rapide)
        val radius = maxOf(6, minOf(w, h) / 40)
        val blurred = boxBlur(boxBlur(gray, w, h, radius), w, h, radius)

        // hautes fréquences + étirement
        val diff = IntArray(w * h)
        var min = 255
        var max = -255
        for (i in gray.indices) {
            val d = gray[i] - blurred[i]
            diff[i] = d
            if (d < min) min = d
            if (d > max) max = d
        }
        val span = maxOf(1, max - min)

        val out = IntArray(w * h)
        for (i in diff.indices) {
            var v = (diff[i] - min) * 255 / span
            // léger renforcement du milieu de plage, là où vit la gravure
            v = ((v - 128) * 1.6f + 128).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        null
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val dst = IntArray(w * h)

        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * r + 1)
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return dst
    }
}
