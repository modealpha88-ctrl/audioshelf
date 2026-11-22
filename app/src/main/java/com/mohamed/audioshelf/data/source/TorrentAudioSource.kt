package com.mohamed.audioshelf.data.source

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mohamed.audioshelf.data.torrent.TorrentDownloads
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Verified downloaded torrent audio is played from app-private music storage. */
class TorrentAudioSource : AudioSource {
    override val id = "torrent_files"
    override val name = "Downloaded torrents"
    override val attribution = "Audiobooks downloaded to this device through BitTorrent"
    @Volatile private var appContext: Context? = null

    fun bind(context: Context) { appContext = context.applicationContext }
    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        val context = checkNotNull(appContext) { "Torrent storage is not initialized." }
        require(book.sourceId == id && TorrentDownloads.get(context, book.id)?.state == "completed") {
            "The audiobook torrent has not finished downloading."
        }
        val tracks = audioFiles(context, book.id)
        require(tracks.isNotEmpty()) { "Downloaded audio was moved or removed from this device." }
        if (tracks.size == 1 && tracks.first().extension.equals("m4b", true)) {
            val file = tracks.first()
            val markers = runCatching {
                FileInputStream(file).channel.use { channel -> M4bChapterParser.read(channel) }
            }.getOrDefault(emptyList())
            if (markers.isNotEmpty()) {
                val length = runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(file.absolutePath)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally { mmr.release() }
                }.getOrDefault(0L)
                val valid = markers.filter { length == 0L || it.startMs < length }.toMutableList()
                if (valid.isNotEmpty()) {
                    if (valid.first().startMs in 1L..999L) valid[0] = valid[0].copy(startMs = 0L)
                    else if (valid.first().startMs > 0L) valid.add(0, M4bChapterParser.Marker(0L, "Beginning"))
                    return@withContext valid.mapIndexed { index, marker ->
                        val end = valid.getOrNull(index + 1)?.startMs ?: length.takeIf { it > marker.startMs }
                        AudioChapter(
                            id = "${book.id}:m4b:$index", title = marker.title,
                            audioUrl = Uri.fromFile(file).toString(), startMs = marker.startMs,
                            durationMs = if (end != null && end > marker.startMs) end - marker.startMs else 0L,
                        )
                    }
                }
            }
        }
        tracks.mapIndexed { index, file ->
            AudioChapter(
                id = "${book.id}:track:$index",
                title = file.nameWithoutExtension.replace('_', ' ').replace('-', ' '),
                audioUrl = Uri.fromFile(file).toString(),
            )
        }
    }

    companion object {
        fun audioFiles(context: Context, id: String): List<File> {
            val folder = TorrentDownloads.directory(context, id)
            if (!folder.isDirectory) return emptyList()
            val base = folder.canonicalPath + File.separator
            return folder.walkTopDown().maxDepth(8)
                .filter { it.isFile && it.length() > 0 && it.canonicalPath.startsWith(base) &&
                    TorrentDownloads.isAudioPath(it.relativeTo(folder).invariantSeparatorsPath) }
                .take(5000).sortedWith(compareBy<File> { it.parentFile?.absolutePath }
                    .thenBy { it.name.lowercase().replace(Regex("(\\d+)")) { match ->
                        match.value.padStart(12, '0')
                    } }).toList()
        }
    }
}
