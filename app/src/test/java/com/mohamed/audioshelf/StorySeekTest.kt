package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test

class StorySeekTest {
    private val chapters = listOf(120_000L, 60_000L, 90_000L)

    @Test fun fourSkipIntervalsWithinChapter() {
        assertEquals(StorySeek.Target(1, 10_000), StorySeek.byOffset(chapters, 1, 20_000, -10_000))
        assertEquals(StorySeek.Target(0, 110_000), StorySeek.byOffset(chapters, 0, 80_000, 30_000))
        assertEquals(StorySeek.Target(1, 30_000), StorySeek.byOffset(chapters, 1, 20_000, 10_000))
        assertEquals(StorySeek.Target(1, 50_000), StorySeek.byOffset(chapters, 1, 20_000, 30_000))
    }

    @Test fun skipsAcrossM4bChapterBoundaries() {
        assertEquals(StorySeek.Target(1, 15_000), StorySeek.byOffset(chapters, 0, 115_000, 20_000))
        assertEquals(StorySeek.Target(0, 110_000), StorySeek.byOffset(chapters, 1, 10_000, -20_000))
        assertEquals(StorySeek.Target(2, 5_000), StorySeek.byOffset(chapters, 1, 55_000, 10_000))
        assertEquals(StorySeek.Target(1, 30_000), StorySeek.byOffset(chapters, 0, 120_000, 30_000))
    }

    @Test fun clampAtStartAndEndOfBook() {
        assertEquals(StorySeek.Target(0, 0), StorySeek.byOffset(chapters, 0, 1_000, -30_000))
        assertEquals(StorySeek.Target(2, 90_000), StorySeek.byOffset(chapters, 2, 89_000, 30_000))
    }

    @Test fun unknownDurationNeverInventsNextChapterOffset() {
        val unknown = listOf(0L, 45_000L)
        assertEquals(StorySeek.Target(0, 40_000), StorySeek.byOffset(unknown, 0, 10_000, 30_000))
        assertEquals(StorySeek.Target(1, 0), StorySeek.byOffset(unknown, 1, 3_000, -10_000))
    }

    @Test fun safeFormattingForLongAudiobooks() {
        assertEquals("0:00", StorySeek.clock(-100))
        assertEquals("1:01:01", StorySeek.clock(3_661_000))
        assertEquals("24:38:00", StorySeek.clock(88_680_000))
    }
}
