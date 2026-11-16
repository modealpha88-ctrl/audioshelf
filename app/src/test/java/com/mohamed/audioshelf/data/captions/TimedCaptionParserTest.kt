package com.mohamed.audioshelf.data.captions

import org.junit.Assert.*
import org.junit.Test

class TimedCaptionParserTest {
    @Test fun srtAndWordHighlight() {
        val content = """
            1
            00:00:01,000 --> 00:00:03,000
            Four very short words

            2
            00:00:03,500 --> 00:00:04,500
            Hello there!
        """.trimIndent()
        val cues = TimedCaptionParser.parse(content)
        assertEquals(2, cues.size)
        assertNull(TimedCaptionParser.active(cues, 999))
        assertEquals("Four", TimedCaptionParser.wordAt(cues[0], 1100).first)
        assertEquals("short", TimedCaptionParser.wordAt(cues[0], 2100).first)
        assertEquals("Hello there!", TimedCaptionParser.active(cues, 3700)?.text)
    }
    @Test fun webVttSupportsCueIdsAndSettings() {
        val s = """
            WEBVTT

            caption1
            00:00:00.500 --> 00:00:01.800 align:middle
            <b>Hello</b> world

            caption2
            00:00:02.000 --> 00:00:03.000
            Next phrase.
        """.trimIndent()
        val result = TimedCaptionParser.parse(s)
        assertEquals(2, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(500L, result[0].startMs)
    }
    @Test fun lrcUsesNextLineAsEndTime() {
        val cues = TimedCaptionParser.parse("[00:01.20]First\n[00:03.50]Second")
        assertEquals(2, cues.size)
        assertEquals(1200L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Second", TimedCaptionParser.active(cues, 3600)?.text)
    }
    @Test fun unTimedTextIsNotFabricated() {
        assertTrue(TimedCaptionParser.parse("Hello world, without timestamps").isEmpty())
    }
}
