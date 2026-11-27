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
