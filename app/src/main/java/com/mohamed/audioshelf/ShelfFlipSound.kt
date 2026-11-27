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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/** Seamless, continuous soft friction bed: no impact, tone, transient or repeated attack. */
internal fun shelfFlipWave(): ByteArray {
    val rate = 22050
    val count = rate * 2
    val random = Random(617)
    val samples = DoubleArray(count)
    var slow = 0.0
    var band = 0.0
    var texture = 0.0
    for (i in 0 until count) {
        val noise = random.nextDouble(-1.0, 1.0)
        slow += .12 * (noise - slow)
        band += .37 * ((noise - slow) - band)
        texture += .0008 * (random.nextDouble(-1.0, 1.0) - texture)
        samples[i] = band * (.11 + texture * .10)
    }
    // Cross-blend the join, producing a loop without an audible click.
    val overlap = rate / 12
    for (i in 0 until overlap) {
        val mix = i.toDouble() / overlap
        samples[i] = samples[count - overlap + i] * (1 - mix) + samples[i] * mix
    }
    val length = count - overlap
    val out = ByteBuffer.allocate(44 + length * 2).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(s: String) { out.put(s.toByteArray(Charsets.US_ASCII)) }
    tag("RIFF"); out.putInt(36 + length * 2); tag("WAVE")
    tag("fmt "); out.putInt(16); out.putShort(1); out.putShort(1)
    out.putInt(rate); out.putInt(rate * 2); out.putShort(2); out.putShort(16)
    tag("data"); out.putInt(length * 2)
    for (i in 0 until length) out.putShort((samples[i] * 32767).toInt().coerceIn(-5000, 5000).toShort())
    return out.array()
}

/** Movement level is bounded and stationary frames are silent. */
internal fun shelfMovementLevel(velocity: Float): Float =
    ((abs(velocity) - .06f) / 2.5f).coerceIn(0f, 1f)

private class ShelfFlipPlayer {
    private var pool: SoundPool? = null
    private var sample = 0
    private var stream = 0
    private var ready = false
    private var lastMovement = -1000L
    private var desired = 0f
    private var volume = 0f

    fun open() {
        if (pool != null) return
        val created = SoundPool.Builder().setMaxStreams(1)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = created
        created.setOnLoadCompleteListener { source, id, status ->
            if (source === pool && id == sample && status == 0) ready = true
        }
    }

    fun load(file: File) { sample = pool?.load(file.absolutePath, 1) ?: 0 }

    fun movement(velocity: Float) {
        val level = shelfMovementLevel(velocity)
        if (level > 0f) {
            desired = level
            lastMovement = SystemClock.elapsedRealtime()
        }
    }

    fun tick() {
        val sound = pool ?: return
        if (!ready || sample == 0) return
        val moving = SystemClock.elapsedRealtime() - lastMovement < 85L
        val target = if (moving) .26f * desired else 0f
        if (stream == 0 && target > 0f) {
            stream = sound.play(sample, 0f, 0f, 1, -1, 1f)
        }
        // Ramp both ways; the sample never starts or stops at audible volume.
        val smoothing = if (target > volume) .14f else .12f
        volume += (target - volume) * smoothing
        if (stream != 0) {
            sound.setVolume(stream, volume, volume)
            if (!moving && volume < .0015f) {
                sound.stop(stream)
                stream = 0
                volume = 0f
            }
        }
    }

    fun close() {
        val sound = pool
        pool = null
        ready = false
        sample = 0
        stream = 0
        lastMovement = -1000L
        desired = 0f
        volume = 0f
        sound?.release()
    }
}

@Composable
internal fun rememberShelfFlipSound(enabled: Boolean): (Float) -> Unit {
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
                    File(context.cacheDir, "shelf-friction-v3.wav").apply {
                        val wave = shelfFlipWave()
                        if (!exists() || length() != wave.size.toLong()) writeBytes(wave)
                    }
                }.getOrNull()
            }
            if (file != null) runCatching { player.load(file) }
            while (true) {
                player.tick()
                delay(16)
            }
        }
    }
    return remember(player, enabled) { { velocity -> if (enabled) player.movement(velocity) } }
}
