package com.mohamed.audioshelf

import android.content.Context
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

internal data class LetteringResult(val bitmap: Bitmap?, val fontMatch: CoverFontMatch? = null,
    val wordInks: Map<String, Int> = emptyMap())
// Charge positive entries by bitmap bytes and negative entries by a small fixed cost.
private val letteringCache = object : LruCache<String, LetteringResult>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: LetteringResult): Int =
        value.bitmap?.allocationByteCount ?: 4096
}
private val letteringLock = Mutex()

internal suspend fun coverTitleLettering(
    context: Context, drawable: Drawable, coverKey: String, title: String,
): LetteringResult? = withContext(Dispatchers.Default) {
    val wanted = spineLetteringKey(title)
    if (wanted.length < 4 || coverKey.isBlank()) return@withContext null
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
    if (bitmap.width < 100 || bitmap.height < 100) return@withContext null
    val key = spineRecognitionKey(bitmap, title)
    letteringLock.withLock {
        // Exact artwork is immutable for a given content hash. Font matches can improve
        // after a catalog refresh, so use their time-limited disk cache instead.
        letteringCache.get(key)?.let { return@withLock it }
        val saved = withContext(Dispatchers.IO) { readSpineRecognition(context, key) }
        if (saved != null) {
            if (saved.bitmap != null) letteringCache.put(key, saved)
            return@withLock saved
        }
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
            // Never paste opaque rectangles from the front cover onto a spine.
            // If any title line cannot be separated reliably, use font recognition.
            val lines = rects.map { extractTransparentSpineLine(bitmap, it) }
            if (lines.any { it == null }) {
                lines.forEach { it?.recycle() }
                return@let null
            }
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
                    canvas.drawBitmap(requireNotNull(lines[index]), null, Rect(x, top, x + widths[index], top + h), paint)
                    x += widths[index] + gap
                }
                result
            }.also { lines.forEach { line -> line?.recycle() } }
        }
        // Use OCR title words, never the generic cover palette. Keep individual
        // words separate so a two-colour title stays two-colour when recreated.
        val titleWords = title.split(Regex("[^\\p{L}\\p{N}]+"))
            .map(::spineLetteringKey).filter { it.length >= 2 }.toSet()
        val wordInks = linkedMapOf<String, Int>()
        if (output == null) {
            val elements = all.flatMap { it.elements }.filter {
                spineLetteringKey(it.text) in titleWords
            }.sortedByDescending {
                (it.boundingBox?.width() ?: 0).toLong() * (it.boundingBox?.height() ?: 0)
            }
            for (element in elements.take(24)) {
                val word = spineLetteringKey(element.text)
                if (word in wordInks) continue
                val box = element.boundingBox ?: continue
                sampleCoverWordInk(bitmap, box)?.let { wordInks[word] = it }
            }
        }
        val fontMatch = if (output == null) matchCoverFont(context, bitmap, recognized, title) else null
        val result = LetteringResult(output, fontMatch, wordInks)
        if (result.bitmap != null) letteringCache.put(key, result)
        withContext(Dispatchers.IO) { writeSpineRecognition(context, key, result) }
        result
    }
}
