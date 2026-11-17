package com.mohamed.audioshelf

import com.mohamed.audioshelf.model.ListeningProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryConceptProgressTest {
    @Test fun untouchedBookHasZeroProgress() {
        assertEquals(0f, conceptProgress(ListeningProgress(chapterCount = 18)), .00001f)
    }

    @Test fun chapterTenCanShowNineteenPercent() {
        val progress = ListeningProgress(chapterIndex = 9, chapterCount = 50,
            positionMs = 30_000L, chapterDurationMs = 300_000L)
        assertEquals(.182f, conceptProgress(progress), .00001f)
    }

    @Test fun unknownAndInvalidDurationsDoNotProduceInvalidProgress() {
        assertEquals(0f, conceptProgress(ListeningProgress()), .00001f)
        assertEquals(1f, conceptProgress(ListeningProgress(
            chapterIndex = 100, chapterCount = 3,
            positionMs = Long.MAX_VALUE, chapterDurationMs = -1)), .00001f)
    }

    @Test fun progressIsClampedToValidPercentages() {
        assertEquals(0f, conceptProgress(ListeningProgress(
            chapterIndex = -2, chapterCount = 3,
            positionMs = -50, chapterDurationMs = 100)), .00001f)
        assertEquals(1f, conceptProgress(ListeningProgress(
            chapterIndex = 2, chapterCount = 3,
            positionMs = 400, chapterDurationMs = 100)), .00001f)
    }
}
