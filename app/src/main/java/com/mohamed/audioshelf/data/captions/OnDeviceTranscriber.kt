package com.mohamed.audioshelf.data.captions

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free on-device English recognizer with word-aligned timestamps.
 * Decodes a short window of the SELECTED local audio file via Android MediaCodec
 * into 16 kHz, mono, signed 16-bit little-endian PCM.
 *
 * Does NOT open the microphone, capture calls or send the recording over the network.
 */
class OnDeviceTranscriber(private val context: Context) {
    suspend fun transcribe(
        audioUri: Uri,
        windowStartMs: Long,
        windowLengthMs: Long = 30_000,
        onWords: ((List<TimedCue>) -> Unit)? = null,
    ): List<TimedCue> = withContext(Dispatchers.IO) {
        val modelFolder = OfflineSpeechModel(context)
        require(modelFolder.isInstalled()) {
            "Download the free English speech model before enabling transcription."
        }
        require(audioUri.scheme == "content" || audioUri.scheme == "file") {
            "On-device transcription currently requires a locally stored MP3/M4B file."
        }
        require(windowStartMs >= 0 && windowLengthMs in 1000..60_000) {
            "Invalid transcription window."
        }

        Model(modelFolder.directory.absolutePath).use { model ->
            Recognizer(model, 16000f).use { recognizer ->
                recognizer.setWords(true)
                val recognized = ArrayList<TimedCue>()
                val extract = MediaExtractor()
                var decoder: MediaCodec? = null
                try {
                    extract.setDataSource(context, audioUri, null)
                    val index = (0 until extract.trackCount).firstOrNull {
                        extract.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    } ?: throw IllegalArgumentException("Selected file has no supported audio track.")
                    extract.selectTrack(index)
                    val inputFormat = extract.getTrackFormat(index)
                    val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                        ?: throw IllegalArgumentException("Cannot identify audio format.")
                    val codec = MediaCodec.createDecoderByType(mime)
                    decoder = codec
                    codec.configure(inputFormat, null, null, 0)
                    codec.start()
                    val seekMs = (windowStartMs - 250).coerceAtLeast(0)
                    extract.seekTo(seekMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    val info = MediaCodec.BufferInfo()
                    val endUs = (windowStartMs + windowLengthMs) * 1000
                    var nextSampleUs = windowStartMs * 1000
                    var outputSampleCount = 0L
                    var firstSampleMs: Long? = null
                    var inputDone = false
                    var outputDone = false
                    var stalls = 0
                    val pending = ByteArrayOutputStream()
                    var loops = 0

                    fun absorbWords(json: String) {
                        val words = JSONObject(json).optJSONArray("result") ?: return
                        val batch = ArrayList<TimedCue>()
                        val anchor = firstSampleMs ?: windowStartMs
                        for (n in 0 until words.length()) {
                            val item = words.optJSONObject(n) ?: continue
                            val word = item.optString("word").trim()
                            if (word.isEmpty()) continue
                            val start = anchor + (item.optDouble("start", 0.0) * 1000).toLong()
                            val end = anchor + (item.optDouble("end", 0.0) * 1000).toLong()
                            if (end > start) batch += TimedCue(start, end, word)
                        }
                        if (batch.isNotEmpty()) {
                            recognized += batch
                            onWords?.invoke(recognized.toList())
                        }
                    }

                    fun feed(force: Boolean = false) {
                        if (pending.size() < 4096 && !force) return
                        val audio = pending.toByteArray()
                        pending.reset()
                        if (audio.isNotEmpty() && recognizer.acceptWaveForm(audio, audio.size)) {
                            absorbWords(recognizer.result)
                        }
                    }

                    while (!outputDone && loops++ < 35000) {
                        if (!inputDone) {
                            val slot = codec.dequeueInputBuffer(10000)
                            if (slot >= 0) {
                                val input = codec.getInputBuffer(slot)
                                    ?: throw IllegalStateException("Audio decoder input is missing.")
                                input.clear()
                                val timeUs = extract.sampleTime
                                val n = if (timeUs < 0 || timeUs > endUs + 500_000) -1
                                    else extract.readSampleData(input, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(slot, 0, n, timeUs, 0)
                                    extract.advance()
                                }
                            }
                        }

                        when (val slot = codec.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                stalls++
                                if (stalls > 150) throw IllegalStateException("Audio decoder timed out.")
                            }
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val pcm = codec.outputFormat
                                if (pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                    pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) !in listOf(
                                        AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT
                                    )) throw IllegalArgumentException("Unsupported decoded PCM encoding.")
                            }
                            else -> if (slot >= 0) {
                                stalls = 0
                                if (info.size > 0) {
                                    val output = codec.getOutputBuffer(slot)
                                        ?: throw IllegalStateException("Audio decoder output is missing.")
                                    val format = codec.outputFormat
                                    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    else AudioFormat.ENCODING_PCM_16BIT
                                    require(rate > 0 && channels in 1..8) { "Unexpected audio decoder format." }
                                    require(encoding == AudioFormat.ENCODING_PCM_16BIT ||
                                        encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        "Unsupported PCM sample type."
                                    }
                                    val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    val frameBytes = channels * sampleBytes
                                    val frameCount = info.size / frameBytes
                                    output.order(ByteOrder.nativeOrder())
                                    val firstUs = info.presentationTimeUs
                                    val limit = info.offset + info.size
                                    while (nextSampleUs < endUs) {
                                        val frame = ((nextSampleUs - firstUs).toDouble() * rate /
                                            1_000_000.0).toInt()
                                        if (frame < 0) {
                                            outputSampleCount++
                                            nextSampleUs = windowStartMs * 1000 + outputSampleCount * 1_000_000L / 16_000L
                                            continue
                                        }
                                        if (frame >= frameCount) break
                                        val base = info.offset + frame * frameBytes
                                        if (base < 0 || base + frameBytes > limit) break
                                        var sum = 0.0
                                        for (ch in 0 until channels) {
                                            val offset = base + ch * sampleBytes
                                            sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT)
                                                (output.getFloat(offset) * 32767.0) else
                                                output.getShort(offset).toDouble()
                                        }
                                        val sample = (sum / channels).toInt().coerceIn(-32768, 32767)
                                        if (firstSampleMs == null) firstSampleMs = nextSampleUs / 1000
                                        pending.write(sample and 255)
                                        pending.write(sample shr 8 and 255)
                                        outputSampleCount++
                                        nextSampleUs = windowStartMs * 1000 + outputSampleCount * 1_000_000L / 16_000L
                                        feed()
                                    }
                                }
                                codec.releaseOutputBuffer(slot, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                                    nextSampleUs >= endUs) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                    feed(force = true)
                    absorbWords(recognizer.finalResult)
                    if (loops >= 35000) throw IllegalStateException("Audio decoding did not finish.")
                    recognized.distinctBy { it.startMs to it.text }.sortedBy { it.startMs }
                } finally {
                    runCatching { decoder?.stop() }
                    runCatching { decoder?.release() }
                    extract.release()
                }
            }
        }
    }
}
