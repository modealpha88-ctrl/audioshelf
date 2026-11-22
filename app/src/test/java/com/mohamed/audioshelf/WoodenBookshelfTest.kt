package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class WoodenBookshelfTest {
    @Test fun smallLibrariesAlwaysHaveAFullBookcase() {
        assertEquals(4, woodenShelfRowCount(0))
        assertEquals(4, woodenShelfRowCount(1))
        assertEquals(4, woodenShelfRowCount(2))
        assertEquals(4, woodenShelfRowCount(8))
    }

    @Test fun sleevesHaveOpposingMildPerspectiveAcrossRows() {
        assertEquals(-5.5f, shelfSleeveTilt(0))
        assertEquals(5.5f, shelfSleeveTilt(1))
        assertEquals(-5.5f, shelfSleeveTilt(2))
        assertEquals(5.5f, shelfSleeveTilt(3))
    }

    @Test fun largerLibrariesGrowWithoutTruncatingBooks() {
        assertEquals(5, woodenShelfRowCount(9))
        assertEquals(5, woodenShelfRowCount(10))
        assertEquals(6, woodenShelfRowCount(11))
        assertEquals(50, woodenShelfRowCount(100))
    }
}
