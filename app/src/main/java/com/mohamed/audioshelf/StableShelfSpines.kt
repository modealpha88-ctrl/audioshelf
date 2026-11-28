package com.mohamed.audioshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.mohamed.audioshelf.data.SpineBindingMode
import com.mohamed.audioshelf.data.SpineBindingStore
import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil.imageLoader
import coil.request.SuccessResult
import kotlin.math.roundToInt

internal data class StableSpineKey(
    val sourceId: String, val bookId: String, val title: String,
    val coverUrl: String, val revision: Int,
)

internal fun stableSpineKey(book: AudioBook, revision: Int) =
    StableSpineKey(book.sourceId, book.id, book.title, book.coverUrl, revision)

internal data class StableSpineVisual(
    val palette: BookstoreSpineColors,
    val preview: ImageBitmap? = null,
    val lettering: ImageBitmap? = null,
)

internal class StableSpineEntry(
    val binding: SpineBindingMode,
    initial: StableSpineVisual,
) {
    var visual by mutableStateOf(initial)
        internal set
    var artwork by mutableStateOf<String?>(null)
        internal set
    internal var pendingVisual: StableSpineVisual? = null
    internal var pendingArtwork: String? = null
    internal var hasPendingArtwork = false
    internal var artworkReady = false
    internal var artworkLoading = false
    internal var analyzing = false
    internal var preloading = false
    internal val restoreLock = Mutex()
    internal var checkedCover = false
    internal var restored by mutableStateOf(false)
    var ratio by mutableStateOf<Float?>(null)
        internal set
    internal var coverHash: String? = null
    internal var lastAttempt = 0L
}

/**
 * Shelf-owned, rather than virtual-slot-owned. Repeated copies of a book share
 * the same entry and recognition work. All published state is changed on Main.
 * Keep at most 64 entries with small, bounded render bitmaps.
 */
internal class StableShelfSpines(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val store = SpineBindingStore(context)
    private val snapshots = ShelfSnapshotStore(context)
    private val entries = LruCache<StableSpineKey, StableSpineEntry>(64)
    private var settled = true

    fun setSettled(value: Boolean) {
        if (settled == value) return
        settled = value
        if (value) entries.snapshot().values.forEach { entry ->
            entry.pendingVisual?.let { entry.visual = it }
            entry.pendingVisual = null
            if (entry.hasPendingArtwork) {
                entry.artwork = entry.pendingArtwork
                entry.pendingArtwork = null
                entry.hasPendingArtwork = false
            }
        }
    }

    private fun publish(entry: StableSpineEntry, visual: StableSpineVisual) {
        if (settled) entry.visual = visual else entry.pendingVisual = visual
    }

    fun entry(book: AudioBook, revision: Int): StableSpineEntry {
        val key = stableSpineKey(book, revision)
        entries.get(key)?.let { return it }
        val binding = store.mode(book)
        val texture = bookstoreCloth(book)
        val saved = if (binding != SpineBindingMode.CLASSIC) store.palette(book) else null
        val palette = saved?.let { BookstoreSpineColors(Color(it.first), Color(it.second), true) }
            ?: bookstoreSpineColors(book.title, null, texture)
        return StableSpineEntry(binding, StableSpineVisual(palette)).also { entries.put(key, it) }
    }

    suspend fun restore(book: AudioBook, entry: StableSpineEntry) {
        entry.restoreLock.withLock {
            if (entry.restored) return
            val saved = withContext(Dispatchers.IO) { snapshots.read(book, entry.binding) }
            if (saved != null) {
                entry.ratio = saved.ratio
                // This is the first appearance of this entry, even during a drag.
                entry.visual = saved.visual
                if (saved.complete) entry.coverHash = saved.hash
            }
            entry.restored = true
        }
    }

    fun loadArtwork(book: AudioBook, entry: StableSpineEntry) {
        if (entry.artworkReady || entry.artworkLoading) return
        entry.artworkLoading = true
        scope.launch {
            try {
                val art = withContext(Dispatchers.IO) { store.displayedArtwork(book, entry.binding) }
                if (settled) entry.artwork = art else {
                    entry.pendingArtwork = art
                    entry.hasPendingArtwork = true
                }
                entry.artworkReady = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retain last successful artwork; retry on a subsequent visit.
            } finally { entry.artworkLoading = false }
        }
    }

    suspend fun preload(book: AudioBook, revision: Int) {
        if (book.coverUrl.isBlank()) return
        val entry = entry(book, revision)
        restore(book, entry)
        loadArtwork(book, entry)
        if (entry.preloading || entry.analyzing || entry.checkedCover) return
        entry.preloading = true
        try {
            val result = context.imageLoader.execute(shelfCoverRequest(context, book).newBuilder()
                .memoryCacheKey("spine-source-840-v1:" + book.coverUrl)
                .precision(coil.size.Precision.EXACT)
                .build())
            if (result is SuccessResult) analyze(book, entry, result.drawable)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Offline cover lookup remains usable. */ }
        finally { entry.preloading = false }
    }

    private fun analyze(book: AudioBook, entry: StableSpineEntry, drawable: Drawable) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (entry.analyzing) return
        // Avoid repeatedly retrying failed analysis while rapidly swiping.
        val now = android.os.SystemClock.elapsedRealtime()
        if (entry.coverHash == null && entry.lastAttempt != 0L && now - entry.lastAttempt < 15000L) return
        entry.lastAttempt = now
        entry.analyzing = true
        scope.launch {
            try {
                val hash = withContext(Dispatchers.Default) {
                    spineRecognitionKey(bitmap, bookstoreSpineTitle(book.title))
                }
                if (entry.coverHash == hash) {
                    entry.checkedCover = true
                    return@launch
                }
                // Earlier snapshots can originate from another decode size of the same jacket.
                // Adopt the canonical hash without exchanging their finished lettering.
                val saved = entry.visual
                val previousPreview = saved.preview
                val ratio = bitmap.width.toFloat() / bitmap.height
                if (entry.coverHash != null && previousPreview != null && withContext(Dispatchers.Default) {
                        sameShelfJacket(previousPreview.asAndroidBitmap(), bitmap)
                    }) {
                    withContext(Dispatchers.IO) {
                        snapshots.write(book, entry.binding, ShelfSnapshot(saved, ratio, hash, true))
                    }
                    entry.ratio = ratio
                    entry.coverHash = hash
                    entry.checkedCover = true
                    return@launch
                }
                entry.ratio = ratio
                val preview = withContext(Dispatchers.Default) { scaled(bitmap, 384, 512).asImageBitmap() }
                val jacket = entry.visual.copy(preview = preview)
                if (entry.visual.preview == null || entry.binding == SpineBindingMode.CLASSIC) {
                    publish(entry, jacket)
                    val classic = entry.binding == SpineBindingMode.CLASSIC
                    withContext(Dispatchers.IO) {
                        snapshots.write(book, entry.binding, ShelfSnapshot(jacket, ratio, hash, classic))
                    }
                    if (classic) {
                        entry.coverHash = hash
                        entry.checkedCover = true
                        return@launch
                    }
                }
                var palette = withContext(Dispatchers.Default) {
                    val raw = bookstoreSpineColors(book.title, drawable, bookstoreCloth(book))
                    BookstoreSpineColors(raw.cloth, Color(readableSpineInk(raw.ink.toArgb(), raw.cloth.toArgb())), raw.hasCoverPalette)
                }
                val lettering = if (entry.binding == SpineBindingMode.AUTOMATIC) {
                    try {
                        val match = coverTitleLettering(context, drawable, book.coverUrl,
                            bookstoreSpineTitle(book.title))
                        withContext(Dispatchers.Default) {
                            val exact = match?.bitmap
                            val wordInks = match?.wordInks.orEmpty()
                            val inks = if (exact != null) bitmapSpineInk(exact) else wordInks.values.distinct()
                            if (inks.isNotEmpty()) {
                                val ink = inks.first()
                                val cloth = spineClothForInk(palette.cloth.toArgb(), inks)
                                palette = BookstoreSpineColors(Color(cloth), Color(ink), true)
                            }
                            // Exact artwork retains its RGB and alpha, including metallic
                            // highlights. When extraction fails, colour the matched font
                            // word by word; use a local serif only if no font was matched.
                            val font = match?.fontMatch ?: if (wordInks.isNotEmpty())
                                CoverFontMatch("system-serif", android.graphics.Typeface.SERIF, false, 1f)
                                else null
                            val rendered = exact ?: font?.let {
                                renderFontMatch(bookstoreSpineTitle(book.title), it, palette.ink.toArgb(), wordInks)
                            }
                            rendered?.let { scaled(it, 1024, 128).asImageBitmap() }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                // Do not downgrade an existing successful result on a transient failure.
                // A different cover is allowed to replace it only with another complete result.
                if (entry.coverHash == null || lettering != null || (entry.pendingVisual ?: entry.visual).lettering == null) {
                    val visual = StableSpineVisual(palette, preview, lettering)
                    publish(entry, visual)
                    val complete = lettering != null || entry.binding != SpineBindingMode.AUTOMATIC
                    withContext(Dispatchers.IO) {
                        snapshots.write(book, entry.binding, ShelfSnapshot(visual, ratio, hash, complete))
                    }
                    // Allow a later visit to retry a failed automatic match.
                    if (complete) {
                        entry.coverHash = hash
                        entry.checkedCover = true
                    }
                    if (palette.hasCoverPalette) withContext(Dispatchers.IO) {
                        store.savePalette(book, palette.cloth.toArgb(), palette.ink.toArgb())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An image-load/OCR failure must not replace an already rendered spine.
            } finally { entry.analyzing = false }
        }
    }

    private fun scaled(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val factor = minOf(1f, maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        return if (factor == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * factor).roundToInt().coerceAtLeast(1),
            (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
    }
}

private fun sameShelfJacket(saved: Bitmap, current: Bitmap): Boolean {
    val savedRatio = saved.width.toDouble() / saved.height
    val ratio = current.width.toDouble() / current.height
    if (kotlin.math.abs(savedRatio / ratio - 1.0) > 0.015) return false
    fun sample(image: Bitmap): IntArray {
        val thumbnail = Bitmap.createScaledBitmap(image, 32, 32, true)
        return IntArray(1024).also { pixels ->
            thumbnail.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        }
    }
    return matchingShelfSamples(sample(saved), sample(current))
}

internal fun matchingShelfSamples(left: IntArray, right: IntArray): Boolean {
    if (left.isEmpty() || left.size != right.size) return false
    var total = 0L
    var outliers = 0
    for (i in left.indices) {
        var largest = 0
        for (shift in 0..24 step 8) {
            val difference = kotlin.math.abs(((left[i] ushr shift) and 255) - ((right[i] ushr shift) and 255))
            total += difference
            largest = maxOf(largest, difference)
        }
        if (largest > 28) outliers++
    }
    return total <= left.size * 4L * 6 && outliers * 100 <= left.size * 3
}
