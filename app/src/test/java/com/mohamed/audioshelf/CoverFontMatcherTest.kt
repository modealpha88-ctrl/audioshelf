package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFontMatcherTest {
    private val shape = GlyphMask(floatArrayOf(1f, 0f, 1f, 0f), 2f, .5f)

    @Test fun identicalGlyphsScoreZero() {
        assertEquals(0f, fontMaskDistance(shape, shape), .0001f)
    }

    @Test fun mismatchedStrokesScoreWorse() {
        val other = GlyphMask(floatArrayOf(0f, 1f, 0f, 1f), 2f, .5f)
        assertTrue(fontMaskDistance(shape, other) > fontMaskDistance(shape, shape))
    }

    @Test fun widthAndWeightAffectTheScore() {
        val wide = shape.copy(aspect = 4f, density = .8f)
        assertTrue(fontMaskDistance(shape, wide) > 0f)
    }
}
