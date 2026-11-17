package com.mohamed.audioshelf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Licensed CC0, portrait video loops. Source URLs are available in Story Mode. */
internal data class StoryLicensedClip(
    val id: String,
    val title: String,
    val subtitle: String,
    val creator: String,
    val fileUrl: String,
    val sourceUrl: String,
    val expectedBytes: Long,
    val color: Long,
) {
    val licenseName: String get() = "CC0 1.0"
}

internal object StoryLicensedVideos {
    val clips = listOf(
        StoryLicensedClip(
            id = "rainy_city",
            title = "Rainy City",
            subtitle = "Neon streets in the rain · 6 seconds",
            creator = "Andy Li",
            // VP9 720p transcode: avoids source AV1 on older Android devices.
            fileUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/3/36/" +
                "Rainy_night_in_Sham_Shui_Po.webm/Rainy_night_in_Sham_Shui_Po.webm.720p.vp9.webm",
            sourceUrl = "https://commons.wikimedia.org/wiki/File:Rainy_night_in_Sham_Shui_Po.webm",
            expectedBytes = 400_000,
            color = 0xFF594A81,
        ),
        StoryLicensedClip(
            id = "waterfall",
            title = "Waterfall",
            subtitle = "Flowing water · 16 seconds",
            creator = "Pohled 111",
            fileUrl = "https://upload.wikimedia.org/wikipedia/commons/0/05/" +
                "Vodop%C3%A1d_Hejlovka_%28%C5%BDelivka%29_video.webm",
            sourceUrl = "https://commons.wikimedia.org/wiki/File:Vodop%C3%A1d_Hejlovka_%28%C5%BDelivka%29_video.webm",
            expectedBytes = 5_610_000,
            color = 0xFF2C817A,
        ),
        StoryLicensedClip(
            id = "lake_drift",
            title = "Lake Drift",
            subtitle = "Gentle lakeside motion · 6 seconds",
            creator = "Sdsecret",
            fileUrl = "https://upload.wikimedia.org/wikipedia/commons/6/64/" +
                "Meikarta_Lake_%28video%29.webm",
            sourceUrl = "https://commons.wikimedia.org/wiki/File:Meikarta_Lake_%28video%29.webm",
            expectedBytes = 540_000,
            color = 0xFF497C9A,
        ),
    )

    fun find(name: String): StoryLicensedClip? =
        clips.firstOrNull { it.title == name }

    fun persistedStyle(name: String?): String =
        if (name == "My video" || clips.any { it.title == name }) name!! else clips.first().title
}

/** Cache in private filesDir, rather than cacheDir, to preserve downloaded loops. */
internal class StoryVideoCache(private val context: Context) {
    companion object { private const val MAX_BYTES = 24L * 1024L * 1024L }
    private val folder = File(context.filesDir, "licensed_story_videos")
    fun cached(clip: StoryLicensedClip): File? {
        val file = File(folder, clip.id + ".webm")
        return file.takeIf { it.isFile && it.length() in 1_024..MAX_BYTES }
    }

    suspend fun acquire(clip: StoryLicensedClip, onProgress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            cached(clip)?.let { return@withContext it }
            folder.mkdirs()
            val destination = File(folder, clip.id + ".webm")
            val pending = File(folder, clip.id + ".partial")
            pending.delete()
            try {
                val url = URL(clip.fileUrl)
                require(url.protocol == "https") { "Only HTTPS video downloads are supported." }
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AudioShelf licensed background video (Wikimedia Commons)")
                }
                try {
                    connection.connect()
                    check(connection.responseCode in 200..299) {
                        "Video download failed (HTTP " + connection.responseCode + ")."
                    }
                    require(connection.contentLengthLong <= MAX_BYTES) {
                        "This video exceeds the 24 MB size limit."
                    }
                    var received = 0L
                    val expected = connection.contentLengthLong.takeIf { it > 0 }
                        ?: clip.expectedBytes
                    connection.inputStream.use { input ->
                        pending.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var lastPercent = -1
                            while (true) {
                                coroutineContext.ensureActive()
                                val bytes = input.read(buffer)
                                if (bytes == -1) break
                                received += bytes
                                require(received <= MAX_BYTES) {
                                    "This video exceeds the 24 MB size limit."
                                }
                                output.write(buffer, 0, bytes)
                                val percent = ((received * 100 / expected).toInt()).coerceIn(0, 99)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    val magic = pending.inputStream().use { input ->
                        ByteArray(4).also { header ->
                            if (input.read(header) != 4) throw IllegalStateException("Video is empty.")
                        }
                    }
                    check(magic.contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) {
                        "The download is not a compatible WebM video."
                    }
                    check(received > 1024) { "Downloaded video was empty." }
                    if (destination.exists()) destination.delete()
                    check(pending.renameTo(destination)) { "Could not save video locally." }
                    onProgress(100)
                    destination
                } finally {
                    connection.disconnect()
                }
            } finally {
                pending.delete()
            }
        }
}
