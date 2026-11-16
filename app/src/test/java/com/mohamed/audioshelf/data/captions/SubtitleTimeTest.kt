package com.mohamed.audioshelf.data.captions

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimeTest {
    @Test fun fullDayUsesHoursNotChapters() {
        val ms = (24L * 3600 + 38L * 60 + 15L) * 1000 + 25L
        assertEquals("24:38:15.025", SubtitleTime.format(ms))
        assertEquals("24:38:15,025", SubtitleTime.format(ms, true))
    }
    @Test fun boundaryAndNegativeTimestamps() {
        assertEquals("00:00:00.000", SubtitleTime.format(-40))
        assertEquals("01:00:00.001", SubtitleTime.format(3600001))
    }
}
