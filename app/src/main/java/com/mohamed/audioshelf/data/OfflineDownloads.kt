package com.mohamed.audioshelf.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import java.io.File

class OfflineDownloads(private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)

    fun downloadBook(book: AudioBook, chapters: List<AudioChapter>): Int {
        var queued = 0
        chapters.forEachIndexed { index, chapter ->
            val target = file(book, index)
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
        val local = file(book, index)
        return if (local.exists() && local.length() > 0L) Uri.fromFile(local) else Uri.parse(remoteUrl)
    }

    private fun file(book: AudioBook, index: Int): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val safeBook = "${book.sourceId}-${book.id}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(base, safeBook), "%04d.mp3".format(index + 1))
    }
}
