package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShelfFlipSoundTest {
    @Test fun movementLevelIsSilentAtRestAndSymmetric() {
        assertEquals(0f, shelfMovementLevel(0f), .0001f)
        assertEquals(0f, shelfMovementLevel(.02f), .0001f)
        assertEquals(shelfMovementLevel(1f), shelfMovementLevel(-1f), .0001f)
        assertEquals(1f, shelfMovementLevel(100f), .0001f)
    }

    @Test fun waveIsLoopSizedValidAndNonSilent() {
        val wave = shelfFlipWave()
        val buffer = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
        assertEquals(wave.size - 8, buffer.getInt(4))
        assertEquals("WAVE", String(wave, 8, 4, Charsets.US_ASCII))
        assertEquals(22050, buffer.getInt(24))
        assertEquals(wave.size - 44, buffer.getInt(40))
        assertEquals(22050 * 2 - 22050 / 12, (wave.size - 44) / 2)
        val samples = (44 until wave.size step 2).map { buffer.getShort(it).toInt() }
        assertTrue(samples.any { it != 0 })
        assertTrue(samples.all { kotlin.math.abs(it) <= 5000 })
        assertTrue(kotlin.math.abs(samples.first() - samples.last()) < 5000)
        assertArrayEquals(wave, shelfFlipWave())
    }
}
