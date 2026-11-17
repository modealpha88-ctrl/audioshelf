package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class WoodenBookshelfTest {
    @Test fun smallLibrariesAlwaysHaveAFullBookcase() {
        assertEquals(4, woodenShelfRowCount(0))
        assertEquals(4, woodenShelfRowCount(1))
        assertEquals(4, woodenShelfRowCount(2))
        assertEquals(4, woodenShelfRowCount(12))
    }

    @Test fun largerLibrariesGrowWithoutTruncatingBooks() {
        assertEquals(5, woodenShelfRowCount(13))
        assertEquals(5, woodenShelfRowCount(15))
        assertEquals(6, woodenShelfRowCount(16))
        assertEquals(34, woodenShelfRowCount(100))
    }
}
