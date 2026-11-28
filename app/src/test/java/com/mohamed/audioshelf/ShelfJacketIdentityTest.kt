package com.mohamed.audioshelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfJacketIdentityTest {
    @Test fun toleratesSmallDecodeDifferences() {
        assertTrue(matchingShelfSamples(IntArray(1024) { 0xff256473.toInt() },
            IntArray(1024) { 0xff286675.toInt() }))
    }

    @Test fun detectsChangedArtworkEvenWhenMostOfTheJacketIsUnchanged() {
        val saved = IntArray(1024) { 0xff183021.toInt() }
        val replaced = saved.copyOf()
        for (i in 0 until 48) replaced[i] = 0xffccddff.toInt()
        assertFalse(matchingShelfSamples(saved, replaced))
    }
}
