package com.mohamed.audioshelf.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import java.io.File
import java.security.MessageDigest

class OfflineDownloads(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)

    fun downloadBook(book: AudioBook, chapters: List<AudioChapter>): Int {
        var queued = 0
        chapters.forEachIndexed { index, chapter ->
            if (Uri.parse(chapter.audioUrl).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) return@forEachIndexed
            val target = file(book, index, chapter.audioUrl)
            if (!target.exists() || target.length() == 0L) {
                target.parentFile?.mkdirs()
                val request = DownloadManager.Request(Uri.parse(chapter.audioUrl))
                    .setTitle(book.title)
                    .setDescription(chapter.title)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationUri(Uri.fromFile(target))
                manager.enqueue(request)
                queued++
            }
        }
        return queued
    }

    fun playableUri(book: AudioBook, index: Int, remoteUrl: String): Uri {
        val local = file(book, index, remoteUrl)
        return if (local.exists() && local.length() > 0L) Uri.fromFile(local) else Uri.parse(remoteUrl)
    }

    private fun file(book: AudioBook, index: Int, mediaUrl: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val original = "${book.sourceId}-${book.id}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeBook = if (original.length <= 100) original else {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(original.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            original.take(64) + "-" + hash
        }
        val extension = Uri.parse(mediaUrl).path.orEmpty().substringAfterLast('.', "mp3").lowercase()
            .takeIf { it in setOf("mp3", "m4a", "mp4", "aac", "ogg", "opus", "flac", "wav") } ?: "mp3"
        return File(File(base, safeBook), "%04d.%s".format(index + 1, extension))
    }
}
