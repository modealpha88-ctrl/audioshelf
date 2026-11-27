package com.mohamed.audioshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

internal data class CoverFontMatch(
    val family: String,
    val typeface: Typeface,
    val upperCase: Boolean,
    val score: Float,
)

private data class FontCandidate(val name: String, val face: Typeface)
internal data class GlyphMask(val cells: FloatArray, val aspect: Float, val density: Float)
private const val MASK_W = 64
private const val MASK_H = 24
private val fontFamilies = listOf("cinzel", "ebgaramond", "oswald", "bebasneue", "anton", "caveat")
private var loadedFonts: List<FontCandidate>? = null

@Synchronized
private fun candidates(context: Context): List<FontCandidate> {
    loadedFonts?.let { return it }
    return fontFamilies.flatMap { family ->
        val file = "spine_fonts/$family.ttf"
        val weights = if (family in listOf("bebasneue", "anton")) listOf(400) else listOf(400, 700)
        weights.mapNotNull { weight ->
            runCatching {
                val builder = Typeface.Builder(context.assets, file)
                if (weights.size > 1) builder.setFontVariationSettings("'wght' $weight")
                builder.build()?.let { FontCandidate("$family-$weight", it) }
            }.getOrNull()
        }
    }.also { loadedFonts = it }
}

// Use the same letters for both the reference and each candidate. This avoids
// mistaking long words for condensed fonts, or short words for wide fonts.
internal fun matchCoverFont(
    context: Context, bitmap: Bitmap, recognized: Text, title: String,
): CoverFontMatch? {
    val titleWords = title.split(Regex("[^\\p{L}\\p{N}]+"))
        .map { spineLetteringKey(it) }.filter { it.length >= 4 }.toSet()
    if (titleWords.isEmpty()) return null
    val samples = recognized.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        .filter { spineLetteringKey(it.text) in titleWords }
        .sortedByDescending { (it.boundingBox?.height() ?: 0) * (it.boundingBox?.width() ?: 0) }
        .mapNotNull { element ->
            val bounds = element.boundingBox ?: return@mapNotNull null
            if (bounds.height() < 12 || bounds.width() < 20) return@mapNotNull null
            if (!element.text.all { it.isLetterOrDigit() }) return@mapNotNull null
            coverMask(bitmap, bounds)?.let { element.text to it }
        }.take(3)
    if (samples.isEmpty()) return null
    val scored = candidates(context).mapNotNull { candidate ->
        val distances = samples.mapNotNull { (word, target) ->
            renderMask(word, candidate.face)?.let { fontMaskDistance(target, it) }
        }
        if (distances.size != samples.size) null
        else candidate to distances.average().toFloat()
    }.sortedBy { it.second }
    val best = scored.firstOrNull() ?: return null
    // A conservative threshold; cluttered illustrations must not force a match.
    if (best.second > .70f) return null
    val letters = samples.joinToString("") { it.first }.filter { it.isLetter() }
    val uppercase = letters.isNotEmpty() && letters.count { it.isUpperCase() } >= letters.length * .85f
    return CoverFontMatch(best.first.name, best.first.face, uppercase, best.second)
}

private fun luminance(pixel: Int): Int =
    (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100

private fun coverMask(bitmap: Bitmap, original: Rect): GlyphMask? {
    val bounds = Rect(original)
    if (!bounds.intersect(0, 0, bitmap.width, bitmap.height)) return null
    val w = bounds.width()
    val h = bounds.height()
    if (w < 20 || h < 12 || w.toLong() * h > 800000) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, bounds.left, bounds.top, w, h)
    val gray = pixels.map { luminance(it) }
    val edges = gray.filterIndexed { index, _ ->
        val x = index % w; val y = index / w
        x == 0 || y == 0 || x == w - 1 || y == h - 1
    }.sorted()
    val bg = edges[edges.size / 2]
    val deviations = gray.map { abs(it - bg) }.sorted()
    val strong = deviations[(deviations.size * .90f).toInt().coerceAtMost(deviations.lastIndex)]
    if (strong < 24) return null
    val threshold = (strong * .48f).roundToInt().coerceAtLeast(18)
    val mask = BooleanArray(w * h) { abs(gray[it] - bg) >= threshold }
    return normalized(mask, w, h)
}

private fun renderMask(word: String, face: Typeface): GlyphMask? {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = 64f; color = Color.WHITE
    }
    val bounds = Rect()
    paint.getTextBounds(word, 0, word.length, bounds)
    val w = bounds.width() + 4
    val h = bounds.height() + 4
    if (w !in 5..1500 || h !in 5..160) return null
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    try {
        Canvas(bitmap).drawText(word, 2f - bounds.left, 2f - bounds.top, paint)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return normalized(BooleanArray(w * h) { Color.alpha(pixels[it]) >= 100 }, w, h)
    } finally { bitmap.recycle() }
}

private fun normalized(mask: BooleanArray, width: Int, height: Int): GlyphMask? {
    var left = width; var right = -1; var top = height; var bottom = -1
    var ink = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (mask[y * width + x]) {
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y); ink++
        }
    }
    val w = right - left + 1; val h = bottom - top + 1
    if (w < 4 || h < 4) return null
    val density = ink.toFloat() / (w * h)
    if (density !in 0.07f..0.85f) return null
    val cells = FloatArray(MASK_W * MASK_H)
    val count = IntArray(cells.size)
    for (y in top..bottom) for (x in left..right) {
        val i = ((y - top) * MASK_H / h) * MASK_W + (x - left) * MASK_W / w
        count[i]++
        if (mask[y * width + x]) cells[i]++
    }
    // Sample centers in upscaled cells that received no source pixel.
    for (i in cells.indices) {
        if (count[i] != 0) cells[i] /= count[i]
        else {
            val x = left + ((i % MASK_W + .5f) * w / MASK_W).toInt().coerceAtMost(w - 1)
            val y = top + ((i / MASK_W + .5f) * h / MASK_H).toInt().coerceAtMost(h - 1)
            cells[i] = if (mask[y * width + x]) 1f else 0f
        }
    }
    return GlyphMask(cells, w.toFloat() / h, density)
}

internal fun fontMaskDistance(a: GlyphMask, b: GlyphMask): Float {
    var overlap = 0f
    var union = 0f
    for (i in a.cells.indices) {
        overlap += minOf(a.cells[i], b.cells[i])
        union += maxOf(a.cells[i], b.cells[i])
    }
    val shape = 1f - overlap / union.coerceAtLeast(1f)
    val width = abs(ln(a.aspect / b.aspect)).coerceAtMost(1f)
    val weight = abs(a.density - b.density)
    return .70f * shape + .20f * width + .10f * weight
}

/** Draw the full title in the chosen real font, not a stretched crop. */
internal fun renderFontMatch(title: String, match: CoverFontMatch, ink: Int): Bitmap? {
    val label = if (match.upperCase) title.uppercase(java.util.Locale.ROOT) else title
    if (label.isBlank()) return null
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = match.typeface; textSize = 80f; color = ink
    }
    val bounds = Rect()
    paint.getTextBounds(label, 0, label.length, bounds)
    if (bounds.width() > 3500) {
        paint.textSize *= 3500f / bounds.width()
        paint.getTextBounds(label, 0, label.length, bounds)
    }
    val w = bounds.width() + 8
    val h = bounds.height() + 8
    if (w !in 9..4096 || h !in 9..256) return null
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawText(label, 4f - bounds.left, 4f - bounds.top, paint)
    }
}
