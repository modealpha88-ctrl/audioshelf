package com.mohamed.audioshelf

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private fun red(c: Int) = c ushr 16 and 255
private fun green(c: Int) = c ushr 8 and 255
private fun blue(c: Int) = c and 255
private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
private fun channel(v: Int): Double {
    val x = v / 255.0
    return if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
}
private fun luminance(c: Int) = .2126 * channel(red(c)) + .7152 * channel(green(c)) + .0722 * channel(blue(c))
internal fun spineInkContrast(a: Int, b: Int): Double {
    val x = luminance(a); val y = luminance(b)
    return (max(x, y) + .05) / (min(x, y) + .05)
}
private fun blend(a: Int, b: Int, amount: Double): Int {
    fun mix(x: Int, y: Int) = (x + (y - x) * amount).roundToInt().coerceIn(0, 255)
    return rgb(mix(red(a), red(b)), mix(green(a), green(b)), mix(blue(a), blue(b)))
}

/** Preserve the original hue wherever possible; lighten/darken only as much as necessary. */
internal fun readableSpineInk(ink: Int, cloth: Int, minimum: Double = 4.5): Int {
    if (spineInkContrast(ink, cloth) >= minimum) return ink
    val white = -1; val black = 0xff000000.toInt()
    val target = if (spineInkContrast(white, cloth) >= spineInkContrast(black, cloth)) white else black
    var low = 0.0; var high = 1.0
    repeat(14) {
        val mid = (low + high) / 2
        if (spineInkContrast(blend(ink, target, mid), cloth) >= minimum) high = mid else low = mid
    }
    return blend(ink, target, high)
}

private fun distance(a: Int, b: Int): Double {
    val r = red(a) - red(b); val g = green(a) - green(b); val bl = blue(a) - blue(b)
    return sqrt((r*r + g*g + bl*bl) / 3.0)
}

/** Conservative local background segmentation. Reject noisy or low-confidence crops. */
internal fun separateSpineInk(pixels: IntArray, w: Int, h: Int): IntArray? {
    if (w < 4 || h < 4 || w.toLong() * h != pixels.size.toLong()) return null
    val border = pixels.filterIndexed { i, _ -> i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1 }
    fun median(values: List<Int>) = values.sorted()[values.size / 2]
    val bg = rgb(median(border.map(::red)), median(border.map(::green)), median(border.map(::blue)))
    val noise = border.map { distance(it, bg) }.sorted().let { it[(it.lastIndex * .8).toInt()] }
    if (noise > 35) return null
    val distances = pixels.map { distance(it, bg) }.sorted()
    val peak = distances[(distances.lastIndex * .90).toInt()]
    if (peak < max(30.0, noise * 2.5)) return null
    val lower = max(12.0, noise * 1.6 + 5)
    val upper = max(lower + 12, peak * .75)
    val result = IntArray(pixels.size)
    var strong = 0
    for (i in pixels.indices) {
        val c = pixels[i]
        val alpha = (((distance(c, bg) - lower) / (upper - lower)).coerceIn(0.0, 1.0) * 255).roundToInt()
        if (alpha >= 180) strong++
        // Undo background mixing at the antialiased edges.
        if (alpha > 0) {
            val a = alpha / 255.0
            fun unmix(x: Int, b: Int) = ((x - (1-a)*b) / a).roundToInt().coerceIn(0,255)
            result[i] = (alpha shl 24) or (unmix(red(c),red(bg)) shl 16) or
                (unmix(green(c),green(bg)) shl 8) or unmix(blue(c),blue(bg))
        }
    }
    if (strong.toDouble() / pixels.size !in 0.06..0.75) return null
    return result
}

internal fun extractTransparentSpineLine(bitmap: Bitmap, rect: Rect): Bitmap? {
    val w = rect.width(); val h = rect.height()
    if (w < 4 || h < 4 || w.toLong()*h > 800000) return null
    val pixels = IntArray(w*h)
    bitmap.getPixels(pixels,0,w,rect.left,rect.top,w,h)
    val mask = separateSpineInk(pixels,w,h) ?: return null
    return Bitmap.createBitmap(mask,w,h,Bitmap.Config.ARGB_8888)
}

internal fun improveSpineBitmapContrast(bitmap: Bitmap, cloth: Int): Bitmap {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    // Retain the original opacity and multicolored lettering. Cache color corrections.
    val colors = HashMap<Int,Int>()
    for (i in pixels.indices) {
        val alpha = pixels[i] ushr 24
        if (alpha == 0) continue
        val c = pixels[i] or 0xff000000.toInt()
        val corrected = colors.getOrPut(c) { readableSpineInk(c,cloth) }
        pixels[i] = (alpha shl 24) or (corrected and 0x00ffffff)
    }
    return Bitmap.createBitmap(pixels,bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
}

/** Find representative printed ink, never the average colour of the artwork. */
internal fun dominantSpineInk(pixels: IntArray): Int? {
    val counts = HashMap<Int, Int>()
    val actual = HashMap<Int, Int>()
    for (c in pixels) {
        if (c ushr 24 < 220) continue
        val bucket = ((red(c) / 24) shl 8) or ((green(c) / 24) shl 4) or (blue(c) / 24)
        counts[bucket] = (counts[bucket] ?: 0) + 1
        actual.putIfAbsent(bucket, c or 0xff000000.toInt())
    }
    val best = counts.maxByOrNull { it.value } ?: return null
    return actual[best.key]
}

/** Estimate title ink from a word-sized OCR crop. Reject ambiguous illustration colours. */
internal fun sampleSpineInk(pixels: IntArray, w: Int, h: Int): Int? {
    if (w < 4 || h < 4 || w.toLong() * h != pixels.size.toLong()) return null
    separateSpineInk(pixels, w, h)?.let { dominantSpineInk(it)?.let { c -> return c } }
    // A second conservative path for coloured ink on illustrated backgrounds:
    // look for a repeatable interior colour that is uncommon at the crop border.
    fun bucket(c: Int) = ((red(c) / 32) shl 6) or ((green(c) / 32) shl 3) or (blue(c) / 32)
    val border = HashMap<Int, Int>()
    val inside = HashMap<Int, Int>()
    val colors = HashMap<Int, Int>()
    var borderN = 0
    var insideN = 0
    for (i in pixels.indices) {
        val c = pixels[i]
        if (c ushr 24 < 220) continue
        val key = bucket(c)
        val x = i % w; val y = i / w
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
            border[key] = (border[key] ?: 0) + 1; borderN++
        } else {
            inside[key] = (inside[key] ?: 0) + 1; insideN++
            colors.putIfAbsent(key, c)
        }
    }
    if (borderN == 0 || insideN == 0) return null
    val ranked = inside.mapNotNull { (key, n) ->
        val share = n.toDouble() / insideN
        val edge = (border[key] ?: 0).toDouble() / borderN
        if (share in 0.08..0.65 && share >= edge * 3 + .04) key to (share - edge) else null
    }.sortedByDescending { it.second }
    val first = ranked.firstOrNull() ?: return null
    if (ranked.size > 1 && first.second < ranked[1].second * 1.25) return null
    return colors[first.first]
}

internal fun sampleCoverWordInk(bitmap: Bitmap, original: Rect): Int? {
    val box = Rect(original)
    val pad = (box.height() * .08f).roundToInt().coerceAtLeast(1)
    box.inset(-pad, -pad)
    if (!box.intersect(0, 0, bitmap.width, bitmap.height)) return null
    val w = box.width(); val h = box.height()
    if (w < 4 || h < 4 || w.toLong() * h > 800000) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
    return sampleSpineInk(pixels, w, h)
}

/** Adjust cloth, not the publisher's ink. Choose the smallest sufficient shade change. */
internal fun spineClothForInk(cloth: Int, inks: List<Int>, minimum: Double = 4.5): Int {
    if (inks.isEmpty()) return cloth
    fun contrast(c: Int) = inks.minOf { spineInkContrast(it, c) }
    if (contrast(cloth) >= minimum) return cloth
    var best = cloth
    var bestContrast = contrast(cloth)
    for (step in 1..100) {
        val amount = step / 100.0
        for (target in listOf(0xff000000.toInt(), -1)) {
            val candidate = blend(cloth, target, amount)
            val score = contrast(candidate)
            if (score >= minimum) return candidate
            if (score > bestContrast) { best = candidate; bestContrast = score }
        }
    }
    return best
}

internal fun bitmapSpineInk(bitmap: Bitmap): List<Int> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    // Include prominent different colours, not every antialiased edge or highlight.
    val groups = pixels.filter { it ushr 24 >= 220 }.groupBy {
        ((red(it) / 48) shl 6) or ((green(it) / 48) shl 3) or (blue(it) / 48)
    }
    val total = groups.values.sumOf { it.size }
    return groups.values.sortedByDescending { it.size }.filter { it.size >= total * .08 }
        .take(5).mapNotNull { dominantSpineInk(it.toIntArray()) }
}
