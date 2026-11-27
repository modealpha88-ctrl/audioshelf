package com.mohamed.audioshelf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

// Use the actual printed pixels rather than attempting to identify a proprietary font.
// Only accept a complete title match; avoid extracting review blurbs or narrator credits.
internal fun spineLetteringKey(text: String): String =
    text.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

private data class LetteringResult(val bitmap: Bitmap?)
private val letteringCache = LruCache<String, LetteringResult>(48)
private val letteringLock = Mutex()

internal suspend fun coverTitleLettering(
    drawable: Drawable, coverKey: String, title: String,
): Bitmap? = withContext(Dispatchers.Default) {
    val wanted = spineLetteringKey(title)
    if (wanted.length < 4 || coverKey.isBlank()) return@withContext null
    val key = "$coverKey:$wanted"
    letteringLock.withLock {
        letteringCache.get(key)?.let { return@withLock it.bitmap }
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@withLock null
        if (bitmap.width < 100 || bitmap.height < 100) return@withLock null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val recognized = try {
            suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                    .addOnCanceledListener { continuation.cancel() }
            }
        } finally {
            recognizer.close()
        }
        val groups = recognized.textBlocks.map { block -> block.lines }
        val all = groups.flatten().sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        var best: List<Rect>? = null
        var bestArea = 0L
        for (lines in groups + listOf(all)) {
            for (start in lines.indices) {
                var joined = ""
                val rects = mutableListOf<Rect>()
                for (end in start until minOf(lines.size, start + 5)) {
                    val line = lines[end]
                    val box = line.boundingBox ?: break
                    joined += spineLetteringKey(line.text)
                    if (!wanted.startsWith(joined)) break
                    val rect = Rect(box)
                    if (!rect.intersect(0, 0, bitmap.width, bitmap.height) ||
                        rect.width() < 4 || rect.height() < 4) break
                    // Never join lines from separate regions with an unrelated illustration between them.
                    if (rects.isNotEmpty()) {
                        val previous = rects.last()
                        if (rect.top - previous.bottom > bitmap.height * .12f ||
                            rect.top < previous.top) break
                    }
                    rects.add(rect)
                    if (joined == wanted) {
                        val area = rects.sumOf { it.width().toLong() * it.height() }
                        if (area > bestArea) { bestArea = area; best = rects.toList() }
                        break
                    }
                }
            }
        }
        val output = best?.let { rects ->
            // Reflow separate cover lines into one readable strip, keeping each line's
            // own typography and ink. Do not recolor or stretch individual letters.
            val tallest = rects.maxOf { it.height() }.coerceAtMost(160)
            val scale = tallest.toFloat() / rects.maxOf { it.height() }
            val gap = (tallest * .12f).roundToInt().coerceAtLeast(2)
            val widths = rects.map { (it.width() * scale).roundToInt().coerceAtLeast(1) }
            val total = widths.sum() + gap * (rects.size - 1)
            if (total > 4096 || total <= 0) null else {
                val result = Bitmap.createBitmap(total, tallest, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                var x = 0
                rects.forEachIndexed { index, rect ->
                    val h = (rect.height() * scale).roundToInt().coerceAtLeast(1)
                    val top = (tallest - h) / 2
                    canvas.drawBitmap(bitmap, rect, Rect(x, top, x + widths[index], top + h), paint)
                    x += widths[index] + gap
                }
                result
            }
        }
        letteringCache.put(key, LetteringResult(output))
        output
    }
}
