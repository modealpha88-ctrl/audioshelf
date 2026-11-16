package com.mohamed.audioshelf.data.captions

import android.content.Context
import android.net.Uri
import com.mohamed.audioshelf.model.AudioBook
import java.io.File
import java.security.MessageDigest

/** Private, offline caption storage, keyed by audiobook and chapter. */
class TimedCaptionStore(private val context: Context) {
    private fun path(book: AudioBook, chapterIndex: Int): File {
        val key = (book.sourceId + ":" + book.id + ":" + chapterIndex).toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "captions"), hash + ".txt")
    }

    fun load(book: AudioBook, chapterIndex: Int): List<TimedCue> =
        runCatching { TimedCaptionParser.parse(path(book, chapterIndex).readText()) }
            .getOrDefault(emptyList())

    fun import(book: AudioBook, chapterIndex: Int, uri: Uri): Int {
        require(chapterIndex >= 0) { "Choose a chapter first." }
        val data = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(4096)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2_000_000) {
                    throw IllegalArgumentException("Captions must be smaller than 2 MB.")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw IllegalArgumentException("Cannot read the subtitle file.")
        val cues = TimedCaptionParser.parse(data)
        require(cues.isNotEmpty()) { "No timed captions found. Choose an SRT, VTT or LRC file." }
        val file = path(book, chapterIndex)
        file.parentFile?.mkdirs()
        file.writeText(data, Charsets.UTF_8)
        return cues.size
    }
}
