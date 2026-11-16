package com.mohamed.audioshelf.data.captions

import android.content.Context
import android.net.Uri
import com.mohamed.audioshelf.model.AudioBook
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale

/** Each finished minute is committed separately; no 24-hour in-memory transcript. */
class FullBookSubtitleStore(private val context: Context) {
    data class Progress(val completedMs: Long = 0, val durationMs: Long = 0,
                        val paused: Boolean = true, val finished: Boolean = false,
                        val words: Long = 0, val error: String? = null) {
        val percent: Int get() = if (durationMs > 0)
            ((completedMs.coerceIn(0, durationMs) * 100) / durationMs).toInt() else 0
    }
    companion object { const val WINDOW_MS = 60_000L }

    private fun hash(bookId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bookId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun workName(bookId: String) = "full_subtitles_" + hash(bookId)
    private fun base(bookId: String) = File(context.filesDir, "full_subtitles/" + hash(bookId))
    private fun prefs(bookId: String) = context.getSharedPreferences(
        "full_subtitles_" + hash(bookId), Context.MODE_PRIVATE)
    private fun chunkFile(bookId: String, start: Long): File =
        File(base(bookId), "minute_" + String.format(Locale.US, "%012d", start / WINDOW_MS) + ".vtt")

    fun progress(bookId: String): Progress {
        val p = prefs(bookId)
        return Progress(p.getLong("complete_ms", 0), p.getLong("duration_ms", 0),
            p.getBoolean("paused", true), p.getBoolean("finished", false),
            p.getLong("words", 0), p.getString("error", null))
    }
    fun configure(bookId: String, durationMs: Long) {
        require(durationMs > 0L && durationMs < 30L * 24 * 3600 * 1000)
        prefs(bookId).edit().putLong("duration_ms", durationMs).apply()
    }
    fun setPaused(bookId: String, paused: Boolean) {
        prefs(bookId).edit().putBoolean("paused", paused).apply()
    }
    fun setError(bookId: String, error: String?) {
        prefs(bookId).edit().putString("error", error).apply()
    }
    fun markFinished(bookId: String) {
        prefs(bookId).edit().putBoolean("finished", true).putBoolean("paused", true).apply()
    }

    /** Incomplete windows never advance progress. Atomic rename prevents truncated chunks. */
    @Synchronized fun commit(bookId: String, start: Long, end: Long, cues: List<TimedCue>) {
        val folder = base(bookId)
        folder.mkdirs()
        val target = chunkFile(bookId, start)
        val temp = File(folder, target.name + ".part")
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("WEBVTT\n\n")
            for (cue in cues.filter { it.startMs < end && it.endMs > start }) {
                writer.append(SubtitleTime.format(cue.startMs)).append(" --> ")
                    .append(SubtitleTime.format(cue.endMs)).append('\n')
                writer.append(cue.text.replace('\n', ' ').replace('\r', ' ')).append("\n\n")
            }
        }
        check(temp.renameTo(target)) { "Unable to save transcription section." }
        val prev = progress(bookId)
        // Never move checkpoint backwards when a cancelled worker completes late.
        if (end > prev.completedMs) {
            prefs(bookId).edit()
                .putLong("complete_ms", end)
                .putLong("words", prev.words + cues.size)
                .putString("error", null).commit()
        }
    }
    fun loadMinute(bookId: String, absoluteMs: Long): List<TimedCue> {
        val start = (absoluteMs.coerceAtLeast(0L) / WINDOW_MS) * WINDOW_MS
        return runCatching { TimedCaptionParser.parse(chunkFile(bookId, start).readText()) }
            .getOrDefault(emptyList())
    }
    fun hasCaptions(bookId: String): Boolean =
        progress(bookId).completedMs > 0

    /** Android Storage Access Framework destination; no MANAGE_EXTERNAL_STORAGE permission. */
    fun export(bookId: String, destination: Uri, format: String) {
        require(format == "srt" || format == "vtt")
        val completed = progress(bookId)
        require(completed.completedMs > 0) { "No transcript sections are ready yet." }
        val out = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IllegalArgumentException("Could not write to the selected destination.")
        out.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { writer ->
                if (format == "vtt") writer.append("WEBVTT\n\n")
                var counter = 1
                var offset = 0L
                while (offset < completed.completedMs) {
                    for (cue in loadMinute(bookId, offset)) {
                        if (format == "srt") writer.append(counter++.toString()).append('\n')
                        writer.append(SubtitleTime.format(cue.startMs, format == "srt"))
                            .append(" --> ")
                            .append(SubtitleTime.format(cue.endMs, format == "srt"))
                            .append('\n')
                        writer.append(cue.text).append("\n\n")
                    }
                    offset += WINDOW_MS
                }
            }
        }
    }
}

/** M4B transcript timestamps stay absolute even when embedded chapters are clipped. */
object SubtitleTime {
    fun format(ms: Long, srt: Boolean = false): String {
        val x = ms.coerceAtLeast(0)
        val h = x / 3600000
        val m = x / 60000 % 60
        val s = x / 1000 % 60
        val fraction = x % 1000
        return String.format(Locale.US,
            if (srt) "%02d:%02d:%02d,%03d" else "%02d:%02d:%02d.%03d",
            h, m, s, fraction)
    }
}
