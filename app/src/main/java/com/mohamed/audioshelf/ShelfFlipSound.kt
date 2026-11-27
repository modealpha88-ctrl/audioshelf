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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Original, deterministic paper-rustle and soft binding-contact sound. No recording or network needed. */
internal fun shelfFlipWave(): ByteArray {
    val sampleRate = 22050
    val count = sampleRate * 185 / 1000
    val out = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(value: String) { out.put(value.toByteArray(Charsets.US_ASCII)) }
    tag("RIFF"); out.putInt(36 + count * 2); tag("WAVE")
    tag("fmt "); out.putInt(16); out.putShort(1); out.putShort(1)
    out.putInt(sampleRate); out.putInt(sampleRate * 2); out.putShort(2); out.putShort(16)
    tag("data"); out.putInt(count * 2)
    val random = Random(617)
    var low = 0.0
    for (i in 0 until count) {
        val t = i.toDouble() / sampleRate
        val noise = random.nextDouble(-1.0, 1.0)
        low += (noise - low) * .19
        val rustle = (noise - low) * .22 *
            exp(-((t - .053) / .032) * ((t - .053) / .032)) *
            (.65 + .35 * sin(2 * PI * 43 * t))
        val contactTime = (t - .098).coerceAtLeast(0.0)
        val contact = if (t >= .098) (.12 * sin(2 * PI * 155 * contactTime) + low * .10) *
            exp(-contactTime / .013) else 0.0
        val fade = (t / .008).coerceIn(0.0, 1.0) * ((.185 - t) / .02).coerceIn(0.0, 1.0)
        out.putShort(((rustle + contact) * fade * 32767).toInt().coerceIn(-32767, 32767).toShort())
    }
    return out.array()
}

private class ShelfFlipPlayer {
    private var pool: SoundPool? = null
    private var sample = 0
    private var ready = false
    private var stream = 0
    private var lastPlay = -1000L

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

    fun load(file: File) {
        sample = pool?.load(file.absolutePath, 1) ?: 0
    }

    fun play() {
        val sound = pool ?: return
        if (!ready || sample == 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlay < 105L) return
        lastPlay = now
        if (stream != 0) sound.stop(stream)
        // Deliberately quiet and no audio-focus request: audiobook narration keeps playing.
        stream = sound.play(sample, .30f, .30f, 1, 0, 1f)
    }

    fun close() {
        val sound = pool
        pool = null
        ready = false
        sample = 0
        stream = 0
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
                    File(context.cacheDir, "shelf-flip-v1.wav").apply {
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
