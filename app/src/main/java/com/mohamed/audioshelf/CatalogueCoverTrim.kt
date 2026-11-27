package com.mohamed.audioshelf

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Only unwrap landscape catalogue canvases with substantial, nearly symmetric
 * blank white/transparent side margins and a portrait or square image inside.
 * Never change the original file or the artwork used by the library shelf.
 */
internal fun catalogueCoverBounds(width: Int, height: Int, pixel: (Int, Int) -> Int): IntRange? {
    if (width < 32 || height < 32 || width.toFloat() / height < 1.18f) return null
    fun blank(p: Int): Boolean {
        val alpha = p ushr 24 and 255
        val r = p ushr 16 and 255
        val g = p ushr 8 and 255
        val b = p and 255
        return alpha < 16 || (minOf(r, g, b) >= 243 && maxOf(r, g, b) - minOf(r, g, b) <= 10)
    }
    fun blankColumn(x: Int): Boolean {
        var blanks = 0
        for (y in 0 until height) if (blank(pixel(x, y))) blanks++
        return blanks >= height * .985f
    }
    var left = 0
    var right = width - 1
    while (left < width && blankColumn(left)) left++
    while (right > left && blankColumn(right)) right--
    val leftMargin = left
    val rightMargin = width - 1 - right
    val contentWidth = right - left + 1
    if (minOf(leftMargin, rightMargin) < width * .12f ||
        kotlin.math.abs(leftMargin - rightMargin) > width * .12f ||
        contentWidth.toFloat() / height !in .40f..1.08f) return null
    // Leave a pixel of breathing room around detected edges.
    return (left - 1).coerceAtLeast(0)..(right + 1).coerceAtMost(width - 1)
}

internal object CatalogueCoverTrim : Transformation {
    override val cacheKey = "catalogue-cover-trim-v1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 32 || h < 32 || w.toFloat() / h < 1.18f) return input
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val bounds = catalogueCoverBounds(w, h) { x, y -> pixels[y * w + x] } ?: return input
        return Bitmap.createBitmap(input, bounds.first, 0, bounds.last - bounds.first + 1, h)
    }
}
