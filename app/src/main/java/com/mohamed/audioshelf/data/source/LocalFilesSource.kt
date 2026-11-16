package com.mohamed.audioshelf.data.source

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/** Locally picked MP3/M4B audio with persistable SAF permissions.
 * Detects chapter markers without copying files or depending on network access.
 */
class LocalFilesSource : AudioSource {
    override val id = "local_files"
    override val name = "My audio files"
    override val attribution = "Audio files selected from your device"
    @Volatile private var appContext: Context? = null

    fun bind(context: Context) { appContext = context.applicationContext }

    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        require(book.id.startsWith("content://")) { "Local audio must use an Android document URI." }
        val context = checkNotNull(appContext) { "Local files are not initialized." }
        val uri = Uri.parse(book.id)
        val fallback = listOf(AudioChapter(id = book.id, title = book.title, audioUrl = book.id))
        // A broken/unsupported chapter table leaves a perfectly usable single-track book.
        val markers = runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open the selected audio file.")
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    M4bChapterParser.read(channel)
                }
            }
        }.getOrDefault(emptyList())
        if (markers.isEmpty()) return@withContext fallback

        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        val valid = markers.filter { duration == 0L || it.startMs < duration }.toMutableList()
        if (valid.isEmpty()) return@withContext fallback
        if (valid.first().startMs in 1L..999L) {
            valid[0] = valid[0].copy(startMs = 0)
        } else if (valid.first().startMs > 0L) {
            valid.add(0, M4bChapterParser.Marker(0, "Beginning"))
        }
        valid.mapIndexed { index, marker ->
            val end = valid.getOrNull(index + 1)?.startMs ?: duration.takeIf { it > marker.startMs }
            AudioChapter(
                id = book.id + "#m4b-chapter-" + (index + 1),
                title = marker.title,
                audioUrl = book.id,
                startMs = marker.startMs,
                durationMs = if (end != null && end > marker.startMs) end - marker.startMs else 0L,
            )
        }
    }
}
