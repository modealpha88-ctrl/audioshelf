package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectorSleeveTest {
    @Test fun extractsPortraitFromWidePaddedThumbnail() {
        val pixels = IntArray(200 * 100) { -1 }
        for (y in 5 until 95) for (x in 70 until 130) pixels[y * 200 + x] = 0xff383027.toInt()
        assertEquals(MatteBounds(70, 5, 60, 90), coverMatteBounds(pixels, 200, 100))
    }

    @Test fun retainsWhiteSquareArtworkAndLandscapesWithoutARectangularMatte() {
        val square = IntArray(100 * 100) { -1 }
        assertNull(coverMatteBounds(square, 100, 100))
        val landscape = IntArray(200 * 100) { -1 }
        for (y in 8 until 92) landscape[y * 200 + 50 + y] = 0xff252525.toInt()
        assertNull(coverMatteBounds(landscape, 200, 100))
    }
}
