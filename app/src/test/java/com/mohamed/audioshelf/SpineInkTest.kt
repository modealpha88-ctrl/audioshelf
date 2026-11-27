package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test

class SpineInkTest {
    @Test fun clearInkRemovesUniformBackground() {
        val pixels = IntArray(20 * 20) { 0xff201810.toInt() }
        for (y in 4..15) for (x in 6..11) pixels[y * 20 + x] = 0xffe1b74b.toInt()
        val mask = requireNotNull(separateSpineInk(pixels,20,20))
        assertEquals(0, mask[0] ushr 24)
        assertEquals(255, mask[8 * 20 + 8] ushr 24)
    }

    @Test fun flatAndNoisyArtworkDoesNotBecomeLettering() {
        assertNull(separateSpineInk(IntArray(400) { 0xff555555.toInt() },20,20))
        assertNull(separateSpineInk(IntArray(400) { if (it % 2 == 0) -1 else 0xff000000.toInt() },20,20))
    }

    @Test fun contrastAdjustmentRetainsReadableColorAndCorrectsDarkInk() {
        val cloth = 0xff181c28.toInt()
        val gold = 0xffebc96c.toInt()
        assertEquals(gold, readableSpineInk(gold, cloth))
        val corrected = readableSpineInk(0xff292c34.toInt(),cloth)
        assertTrue(spineInkContrast(corrected,cloth) >= 4.49)
    }

    @Test fun lightClothGetsDarkerInk() {
        val cloth = 0xffeeeeee.toInt()
        assertTrue(spineInkContrast(readableSpineInk(0xffd8c9a9.toInt(),cloth),cloth) >= 4.49)
    }
}
