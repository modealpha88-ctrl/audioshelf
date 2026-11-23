package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class WoodenBookshelfTest {
    @Test fun smallLibrariesOnlyUseOccupiedRows() {
        assertEquals(0, woodenShelfRowCount(0))
        assertEquals(1, woodenShelfRowCount(1))
        assertEquals(1, woodenShelfRowCount(2))
        assertEquals(1, woodenShelfRowCount(3))
        assertEquals(3, woodenShelfRowCount(8))
    }

    @Test fun narrowShelvesWrapAfterTwoBooks() {
        assertEquals(5, woodenShelfRowCount(9, 2))
    }

    @Test fun sleevesHaveOpposingMildPerspectiveAcrossRows() {
        assertEquals(-5.5f, shelfSleeveTilt(0))
        assertEquals(5.5f, shelfSleeveTilt(1))
        assertEquals(-5.5f, shelfSleeveTilt(2))
        assertEquals(5.5f, shelfSleeveTilt(3))
    }

    @Test fun largerLibrariesGrowWithoutTruncatingBooks() {
        assertEquals(3, woodenShelfRowCount(9))
        assertEquals(4, woodenShelfRowCount(10))
        assertEquals(4, woodenShelfRowCount(11))
        assertEquals(34, woodenShelfRowCount(100))
    }
}
