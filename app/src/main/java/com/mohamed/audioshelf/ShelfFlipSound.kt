package com.mohamed.audioshelf

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.random.Random

/** Short, aperiodic paper friction: no pitched tone, bass thud or periodic modulation. */
internal fun shelfFlipWave(): ByteArray {
    val sampleRate = 22050
    val count = sampleRate * 92 / 1000
    val out = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(value: String) { out.put(value.toByteArray(Charsets.US_ASCII)) }
    tag("RIFF"); out.putInt(36 + count * 2); tag("WAVE")
    tag("fmt "); out.putInt(16); out.putShort(1); out.putShort(1)
    out.putInt(sampleRate); out.putInt(sampleRate * 2); out.putShort(2); out.putShort(16)
    tag("data"); out.putInt(count * 2)
    val random = Random(617)
    // Irregular small fibre scratches with a quick dry decay, not a resonant buzz.
    val grains = List(13) { Triple(random.nextDouble(.003, .069),
        random.nextDouble(.0012, .0045), random.nextDouble(.10, .38)) }
    var previous = 0.0
    var previousHigh = 0.0
    var filtered = 0.0
    for (i in 0 until count) {
        val t = i.toDouble() / sampleRate
        val noise = random.nextDouble(-1.0, 1.0)
        val high = noise - previous + .66 * previousHigh
        previous = noise
        previousHigh = high
        filtered += (high - filtered) * .65
        val texture = grains.sumOf { (start, width, strength) ->
            val offset = (t - start) / width
            strength * exp(-offset * offset)
        }.coerceAtMost(1.0)
        val attack = (t / .004).coerceIn(0.0, 1.0)
        val tail = ((.092 - t) / .014).coerceIn(0.0, 1.0)
        val sample = filtered * (.045 + texture * .32) * attack * tail
        out.putShort((sample * 32767).toInt().coerceIn(-12000, 12000).toShort())
    }
    return out.array()
}

/** Accumulate movement in either direction without triggering on stationary frames. */
internal fun shelfSoundTravel(accumulated: Float, movement: Float): Float =
    accumulated + kotlin.math.abs(movement)

private class ShelfFlipPlayer {
    private var pool: SoundPool? = null
    private var sample = 0
    private var ready = false
    private var variation = 0
    private var lastPlay = -1000L

    fun open() {
        if (pool != null) return
        val created = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = created
        created.setOnLoadCompleteListener { source, id, status ->
            if (source === pool && id == sample && status == 0) ready = true
        }
    }

    fun load(file: File) {
        sample = pool?.load(file.absolutePath, 1) ?: 0
    }

    fun play() {
        val sound = pool ?: return
        if (!ready || sample == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlay < 70L) return
        lastPlay = now
        // Short variations prevent identical mechanical repeats; no audio focus request.
        val rate = listOf(.97f, 1.08f, 1.02f, .92f)[variation++ and 3]
        sound.play(sample, .24f, .24f, 1, 0, rate)
    }

    fun close() {
        val sound = pool
        pool = null
        ready = false
        sample = 0
        variation = 0
        sound?.release()
    }
}

@Composable
internal fun rememberShelfFlipSound(enabled: Boolean): () -> Unit {
    val context: Context = LocalContext.current.applicationContext
    val player = remember(context) { ShelfFlipPlayer() }
    DisposableEffect(player, enabled) {
        if (enabled) runCatching { player.open() }
        onDispose { player.close() }
    }
    LaunchedEffect(player, enabled) {
        if (enabled) {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    File(context.cacheDir, "shelf-flip-v2.wav").apply {
                        val wave = shelfFlipWave()
                        if (!exists() || length() != wave.size.toLong()) writeBytes(wave)
                    }
                }.getOrNull()
            }
            if (file != null) runCatching { player.load(file) }
        }
    }
    return remember(player, enabled) { { if (enabled) player.play() } }
}
