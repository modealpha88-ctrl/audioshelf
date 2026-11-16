package com.mohamed.audioshelf.data.captions

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class LargeTimedCaptionImportTest {
    private fun stream(input: String): List<TimedCue> {
        val all = ArrayList<TimedCue>()
        val count = TimedCaptionParser.parseStream(
            BufferedReader(StringReader(input))
        ) { cue -> all += cue }
        assertEquals(all.size, count)
        return all
    }

    @Test fun importsOversized24HourWebVttWithoutTruncating() {
        val text = buildString {
            append("WEBVTT\n\n")
            // 25,000 SRT/VTT-sized cues; more than the old 2 MB import limit.
            for (i in 0 until 25_000) {
                val begin = i * 3_546L
                append(SubtitleTime.format(begin)).append(" --> ")
                    .append(SubtitleTime.format(begin + 3_500)).append('\n')
                append("This is a long audiobook subtitle cue with text and ")
                    .append("proper names like Kelsier, Vin, and Luthadel.\n\n")
            }
        }
        assertTrue("Fixture must exceed previous 2MB cap", text.toByteArray().size > 2_000_000)
        val cues = stream(text)
        assertEquals(25_000, cues.size)
        assertEquals("00:00:00.000", SubtitleTime.format(cues.first().startMs))
        assertTrue("Final cue must exceed 24 hours", cues.last().startMs >= 24L * 3_600_000)
        assertEquals("Kelsier, Vin, and Luthadel.",
            cues.last().text.substringAfter("proper names like "))
    }

    @Test fun streamsSrtWithIdAndMarkupAndLongHours() {
        val text = """
            1
            24:37:52,718 --> 24:37:56,963
            <b>Last</b> long-audiobook line.

            2
            24:37:57,038 --> 24:37:59,925
            THE END
        """.trimIndent()
        val cues = stream(text)
        assertEquals(2, cues.size)
        assertEquals(24L * 3_600_000 + 37 * 60_000 + 52_718L, cues[0].startMs)
        assertEquals("Last long-audiobook line.", cues[0].text)
        assertEquals("THE END", cues[1].text)
    }

    @Test fun streamsLrcAndEndTimeUsesFollowingLine() {
        val cues = stream("[00:01.30]First word\n[00:03.50]Second word")
        assertEquals(2, cues.size)
        assertEquals(1300L, cues.first().startMs)
        assertEquals(3500L, cues.first().endMs)
        assertEquals(7500L, cues.last().endMs)
    }

    @Test fun rejectsUntimedTextRatherThanClaimingSuccessfulImport() {
        assertTrue(stream("A plain novel is not a timed transcript.").isEmpty())
    }
}
