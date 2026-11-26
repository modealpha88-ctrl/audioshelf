package com.mohamed.audioshelf.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.BitmapFactory
import com.mohamed.audioshelf.data.source.TorrentAudioSource
import com.mohamed.audioshelf.data.torrent.TorrentDownloads
import java.io.ByteArrayOutputStream
import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Exact local artwork is preferred over a web result. Older local M4B/MP3
 * imports can be enriched without reimporting them or touching listening progress.
 */
class LocalEmbeddedCover(private val context: Context) {
    suspend fun find(book: AudioBook): String? = withContext(Dispatchers.IO) {
        if (book.coverUrl.isNotBlank()) return@withContext null
        if (book.sourceId == "torrent_files") {
            return@withContext runCatching { findTorrentCover(book) }.getOrNull()
        }
        if (book.sourceId != "local_files") return@withContext null
        val uri = Uri.parse(book.id)
        if (uri.scheme != "content") return@withContext null
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(book.id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val folder = File(context.filesDir, "covers").apply { mkdirs() }
            val existing = folder.listFiles()?.firstOrNull {
                it.name == "$digest.jpg" || it.name == "$digest.png"
            }
            if (existing != null && existing.isFile && existing.length() > 0) {
                return@runCatching Uri.fromFile(existing).toString()
            }
            val retriever = MediaMetadataRetriever()
            val art = try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            } ?: return@runCatching null
            if (art.size !in 100..5_000_000) return@runCatching null
            val isJpeg = art.size >= 3 &&
                (art[0].toInt() and 0xFF) == 0xFF &&
                (art[1].toInt() and 0xFF) == 0xD8
            val isPng = art.size >= 8 &&
                (art[0].toInt() and 0xFF) == 0x89 &&
                art[1] == 0x50.toByte() && art[2] == 0x4E.toByte()
            if (!isJpeg && !isPng) return@runCatching null
            val image = File(folder, "$digest.${if (isPng) "png" else "jpg"}")
            val temp = File.createTempFile("cover-", ".tmp", folder)
            try {
                temp.writeBytes(art)
                if (!temp.renameTo(image)) {
                    image.writeBytes(art)
                }
            } finally {
                temp.delete()
            }
            Uri.fromFile(image).toString()
        }.getOrNull()
    }

    /**
     * Torrent audio and its companion artwork share the same app-private folder.
     * Imported older torrents may have embedded images even without cover.jpg.
     */
    private fun findTorrentCover(book: AudioBook): String? {
        if (!book.id.matches(Regex("[a-f0-9]{32}"))) return null
        val destination = cachedCoverFile(book, "jpg")
        val cache = destination.parentFile ?: return null
        cache.listFiles()?.firstOrNull {
            it.name.startsWith(coverKey(book) + ".") && it.isFile && it.length() > 0L
        }?.let { return Uri.fromFile(it).toString() }

        val root = TorrentDownloads.directory(context, book.id)
        if (!root.isDirectory) return null
        val prefix = root.canonicalPath + File.separator
        val images = root.walkTopDown().maxDepth(8)
            .filter { file ->
                file.isFile && file.length() in 1_024L..8_388_608L &&
                    file.canonicalPath.startsWith(prefix) &&
                    TorrentDownloads.isArtworkPath(file.relativeTo(root).invariantSeparatorsPath) &&
                    !isNamedSpineImage(file.name)
            }.take(256).toList()
            .sortedBy { file ->
                when (file.nameWithoutExtension.lowercase()) {
                    "cover", "front", "folder", "album", "artwork" -> 0
                    else -> 1
                }
            }
        for (image in images) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            if (bounds.outWidth !in 100..12_000 || bounds.outHeight !in 100..12_000) continue
            val extension = image.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
            val target = cachedCoverFile(book, extension)
            val temp = File.createTempFile("artwork-", ".tmp", target.parentFile)
            try {
                image.copyTo(temp, overwrite = true)
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            } finally {
                temp.delete()
            }
            return Uri.fromFile(target).toString()
        }

        // Some MP3/M4B releases contain only audio but embed the real jacket in the file.
        for (audio in TorrentAudioSource.audioFiles(context, book.id).take(4)) {
            val retriever = MediaMetadataRetriever()
            val image = try {
                retriever.setDataSource(audio.absolutePath)
                retriever.embeddedPicture
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            } ?: continue
            val kind = sniffImageExtension(image) ?: continue
            if (image.size !in 100..6_000_000 || !validImage(image)) continue
            val target = cachedCoverFile(book, kind)
            target.writeBytes(image)
            return Uri.fromFile(target).toString()
        }
        return null
    }

    /** User-supplied cover: app-private, size-limited, validated, and persistent. */
    suspend fun importChosenCover(book: AudioBook, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select a picture from the Android photo picker." }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read the selected picture.")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 6_000_000) { "Cover image must be under 6 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val kind = sniffImageExtension(bytes)
        require(kind != null && validImage(bytes)) {
            "Choose a valid JPG, PNG or WebP cover image."
        }
        // Content-addressed URL prevents Coil from displaying a stale cached jacket
        // when the user selects a different image for the same saved book.
        val revision = MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
            .joinToString("") { "%02x".format(it) }
        val target = cachedCoverFile(book, "custom-$revision." + kind)
        val temp = File.createTempFile("chosen-", ".tmp", target.parentFile)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
        } finally {
            temp.delete()
        }
        Uri.fromFile(target).toString()
    }

    private fun validImage(data: ByteArray): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        return opts.outWidth in 100..12_000 && opts.outHeight in 100..12_000
    }

    private fun sniffImageExtension(data: ByteArray): String? = when {
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "jpg"
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "png"
        data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    private fun coverKey(book: AudioBook): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${book.sourceId}:${book.id}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun cachedCoverFile(book: AudioBook, extension: String): File {
        val folder = File(context.filesDir, "covers").apply { mkdirs() }
        return File(folder, "${coverKey(book)}.$extension")
    }
}
