package com.mohamed.audioshelf.data.captions

/** Caption timestamps refer to the underlying audio file, not the clipped M4B chapter. */
data class TimedCue(val startMs: Long, val endMs: Long, val text: String)

object TimedCaptionParser {
    private val clock = Regex("""^(?:(\d{1,3}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$""")
    private val lrc = Regex("""^\[(\d{1,3}):(\d{2})(?:[.,](\d{1,3}))?](.*)$""")
    private const val MAX_CUES = 60000

    fun timeMs(input: String): Long? {
        val match = clock.matchEntire(input.trim()) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        val h = hours.toLongOrNull() ?: 0L
        val m = minutes.toLongOrNull() ?: return null
        val s = seconds.toLongOrNull() ?: return null
        if ((m > 59 && hours.isNotEmpty()) || s > 59) return null
        val ms = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((h * 3600 + m * 60 + s) * 1000 + ms).takeIf { it >= 0 }
    }

    private fun cleanCaption(input: String): String = input
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ").trim()

    fun parse(text: String): List<TimedCue> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<TimedCue>()
        for (block in normalized.split(Regex("""\n[ \t]*\n"""))) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { it.contains("-->") }
            if (idx < 0) continue
            val stamps = lines[idx].split("-->", limit = 2)
            val start = timeMs(stamps[0]) ?: continue
            val end = timeMs(stamps[1].trim().substringBefore(' ')) ?: continue
            val phrase = cleanCaption(lines.drop(idx + 1).joinToString(" "))
            if (end > start && phrase.isNotBlank()) cues += TimedCue(start, end, phrase)
            if (cues.size >= MAX_CUES) break
        }
        if (cues.isNotEmpty()) return cues.sortedBy { it.startMs }
        val lrcItems = ArrayList<Pair<Long, String>>()
        for (line in normalized.lines()) {
            val match = lrc.matchEntire(line.trim()) ?: continue
            val (mins, secs, fraction, phrase) = match.destructured
            val stamp = mins + ":" + secs + (if (fraction.isNotBlank()) "." + fraction else "")
            val start = timeMs(stamp) ?: continue
            val value = cleanCaption(phrase)
            if (value.isNotBlank()) lrcItems += start to value
            if (lrcItems.size >= MAX_CUES) break
        }
        val sorted = lrcItems.distinctBy { it.first }.sortedBy { it.first }
        return sorted.mapIndexed { i, pair ->
            TimedCue(pair.first, sorted.getOrNull(i + 1)?.first?.coerceAtLeast(pair.first + 1) ?: pair.first + 4000, pair.second)
        }
    }

    fun active(cues: List<TimedCue>, positionMs: Long): TimedCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) { candidate = mid; lo = mid + 1 }
            else hi = mid - 1
        }
        return cues.getOrNull(candidate)?.takeIf { positionMs < it.endMs }
    }

    fun wordAt(cue: TimedCue, positionMs: Long): Pair<String, Int> {
        val words = cue.text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "" to 0
        val elapsed = (positionMs - cue.startMs).coerceAtLeast(0)
        val length = (cue.endMs - cue.startMs).coerceAtLeast(1)
        val index = ((elapsed.toDouble() * words.size) / length).toInt().coerceIn(0, words.lastIndex)
        return words[index] to index
    }
}
