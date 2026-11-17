package com.mohamed.audioshelf.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
        if (book.sourceId != "local_files" || book.coverUrl.isNotBlank()) return@withContext null
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
}
