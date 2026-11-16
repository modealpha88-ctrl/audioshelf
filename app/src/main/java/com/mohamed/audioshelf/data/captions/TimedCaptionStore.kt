package com.mohamed.audioshelf.data.captions

import android.content.Context
import android.net.Uri
import com.mohamed.audioshelf.model.AudioBook
import java.io.File
import java.security.MessageDigest

/** Private, offline caption storage, keyed by audiobook and chapter. */
class TimedCaptionStore(private val context: Context) {
    private fun path(book: AudioBook, chapterIndex: Int): File {
        // One local M4B file may contain many clipped chapters. Share its transcript.
        val suffix = if (book.sourceId == "local_files") "whole-file" else chapterIndex.toString()
        val key = (book.sourceId + ":" + book.id + ":" + suffix).toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "captions"), hash + ".txt")
    }

    private fun autoPath(book: AudioBook, chapterIndex: Int): File {
        val original = path(book, chapterIndex)
        return File(original.parentFile, "auto_" + original.name)
    }

    fun load(book: AudioBook, chapterIndex: Int): List<TimedCue> {
        val manual = runCatching {
            TimedCaptionParser.parse(path(book, chapterIndex).readText())
        }.getOrDefault(emptyList())
        if (manual.isNotEmpty()) return manual
        return runCatching {
            TimedCaptionParser.parse(autoPath(book, chapterIndex).readText())
        }.getOrDefault(emptyList())
    }

    private fun timecode(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val h = safe / 3600000
        val m = safe / 60000 % 60
        val sec = safe / 1000 % 60
        return "%02d:%02d:%02d.%03d".format(java.util.Locale.US, h, m, sec, safe % 1000)
    }

    fun saveRecognizedWords(book: AudioBook, chapterIndex: Int, words: List<TimedCue>) {
        if (words.isEmpty()) return
        val file = autoPath(book, chapterIndex)
        val existing = runCatching { TimedCaptionParser.parse(file.readText()) }.getOrDefault(emptyList())
        val merged = (existing + words).filter { it.endMs > it.startMs }
            .distinctBy { it.startMs to it.text }.sortedBy { it.startMs }.take(120000)
        file.parentFile?.mkdirs()
        // Atomic-ish replace so a failed transcription cannot truncate older captions.
        val temp = File(file.parentFile, file.name + ".part")
        temp.writeText(buildString {
            append("WEBVTT\n\n")
            merged.forEach { cue ->
                append(timecode(cue.startMs)).append(" --> ").append(timecode(cue.endMs)).append('\n')
                append(cue.text).append("\n\n")
            }
        }, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not save captions." }
        }
    }

    private fun windowKey(book: AudioBook, chapterIndex: Int, startMs: Long): String =
        autoPath(book, chapterIndex).name + ":" + startMs

    fun isRecognizedWindow(book: AudioBook, chapterIndex: Int, startMs: Long): Boolean =
        context.getSharedPreferences("audio_shelf_stt_windows", Context.MODE_PRIVATE)
            .getBoolean(windowKey(book, chapterIndex, startMs), false)

    fun markRecognizedWindow(book: AudioBook, chapterIndex: Int, startMs: Long) {
        context.getSharedPreferences("audio_shelf_stt_windows", Context.MODE_PRIVATE)
            .edit().putBoolean(windowKey(book, chapterIndex, startMs), true).apply()
    }

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
