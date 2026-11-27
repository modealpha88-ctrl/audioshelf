package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShelfFlipSoundTest {
    @Test fun travelCountsBothDirectionsButNotStationaryFrames() {
        assertEquals(.30f, shelfSoundTravel(.30f, 0f), .0001f)
        assertEquals(.51f, shelfSoundTravel(.30f, -.21f), .0001f)
        assertEquals(.51f, shelfSoundTravel(.30f, .21f), .0001f)
    }

    @Test fun waveIsShortValidAndNonSilent() {
        val wave = shelfFlipWave()
        val buffer = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
        assertEquals(wave.size - 8, buffer.getInt(4))
        assertEquals("WAVE", String(wave, 8, 4, Charsets.US_ASCII))
        assertEquals(22050, buffer.getInt(24))
        assertEquals(wave.size - 44, buffer.getInt(40))
        assertEquals(22050 * 92 / 1000, (wave.size - 44) / 2)
        val samples = (44 until wave.size step 2).map { buffer.getShort(it).toInt() }
        assertTrue(samples.any { it != 0 })
        assertTrue(samples.all { kotlin.math.abs(it) < 16000 })
        assertArrayEquals(wave, shelfFlipWave())
    }
}
