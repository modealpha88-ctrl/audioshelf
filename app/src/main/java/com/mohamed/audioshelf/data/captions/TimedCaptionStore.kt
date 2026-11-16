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

    companion object {
        // Five-minute partitions let the player seek through a whole 25-hour
        // audiobook without loading its complete transcript into memory.
        const val INDEX_SPAN_MS = 300_000L
        private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    }

    private fun indexPath(book: AudioBook, chapterIndex: Int): File {
        val file = path(book, chapterIndex)
        return File(file.parentFile, file.nameWithoutExtension + "_index")
    }

    /**
     * Prefer the latest imported index, fall back to old imported caption
     * files and automatically generated STT without migration or data loss.
     */
    fun loadNear(book: AudioBook, chapterIndex: Int, positionMs: Long): List<TimedCue> {
        val indexed = indexPath(book, chapterIndex)
        if (File(indexed, ".complete").isFile) {
            val bucket = positionMs.coerceAtLeast(0) / INDEX_SPAN_MS
            val cues = File(indexed, "part_$bucket.vtt")
            return runCatching { TimedCaptionParser.parse(cues.readText()) }
                .getOrDefault(emptyList())
        }
        return load(book, chapterIndex)
    }

    /**
     * Import from Android SAF, with a 64-MiB safety limit and streaming parsing.
     * Imported full-book captions are indexed in five-minute chunks so Story
     * Mode never reparses a 24-hour SRT/VTT for every chapter.
     */
    fun importIndexed(book: AudioBook, chapterIndex: Int, uri: Uri): Int {
        require(chapterIndex >= 0) { "Choose a chapter first." }
        val index = indexPath(book, chapterIndex)
        val base = index.parentFile ?: throw IllegalStateException("Missing captions directory.")
        base.mkdirs()
        val raw = File(base, index.name + ".import")
        val stage = File(base, index.name + ".stage")
        val backup = File(base, index.name + ".backup")
        stage.deleteRecursively()
        stage.mkdirs()
        var imported = 0
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open the selected subtitle file.")
            source.use { input ->
                raw.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_IMPORT_BYTES) {
                            "Subtitle file exceeds the 64 MB import limit."
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
            val writers = object : LinkedHashMap<Long, java.io.BufferedWriter>(16, .75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Long, java.io.BufferedWriter>
                ): Boolean {
                    if (size <= 12) return false
                    eldest.value.close()
                    return true
                }
            }
            try {
                raw.bufferedReader(Charsets.UTF_8).use { reader ->
                    imported = TimedCaptionParser.parseStream(reader) { cue ->
                        val first = cue.startMs.coerceAtLeast(0) / INDEX_SPAN_MS
                        val last = (cue.endMs - 1).coerceAtLeast(0) / INDEX_SPAN_MS
                        require(last >= first && last - first <= 512) {
                            "A subtitle cue has an invalid duration."
                        }
                        for (bucket in first..last) {
                            val writer = writers[bucket] ?: java.io.FileOutputStream(
                                File(stage, "part_$bucket.vtt"), true
                            ).bufferedWriter(Charsets.UTF_8).also { writers[bucket] = it }
                            writer.append(timecode(cue.startMs)).append(" --> ")
                                .append(timecode(cue.endMs)).append('\n')
                                .append(cue.text.replace('\n', ' ').replace('\r', ' '))
                                .append("\n\n")
                        }
                    }
                }
            } finally {
                writers.values.forEach { runCatching { it.close() } }
            }
            require(imported > 0) { "No timed captions found. Choose SRT, VTT or LRC." }
            File(stage, ".complete").writeText(imported.toString())
            backup.deleteRecursively()
            if (index.exists()) check(index.renameTo(backup)) {
                "Cannot prepare replacement subtitle index."
            }
            if (!stage.renameTo(index)) {
                if (backup.exists()) backup.renameTo(index)
                throw IllegalStateException("Unable to save indexed subtitles.")
            }
            backup.deleteRecursively()
            return imported
        } finally {
            raw.delete()
            stage.deleteRecursively()
        }
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

    fun import(book: AudioBook, chapterIndex: Int, uri: Uri): Int =
        importIndexed(book, chapterIndex, uri)
}
