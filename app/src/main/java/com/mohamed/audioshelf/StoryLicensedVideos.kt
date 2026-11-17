package com.mohamed.audioshelf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    val posterUrl: String,
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
            posterUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/36/" +
                "Rainy_night_in_Sham_Shui_Po.webm/500px--Rainy_night_in_Sham_Shui_Po.webm.jpg",
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
            posterUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/05/" +
                "Vodop%C3%A1d_Hejlovka_%28%C5%BDelivka%29_video.webm/" +
                "500px--Vodop%C3%A1d_Hejlovka_%28%C5%BDelivka%29_video.webm.jpg",
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
            posterUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/" +
                "Meikarta_Lake_%28video%29.webm/500px--Meikarta_Lake_%28video%29.webm.jpg",
            expectedBytes = 540_000,
            color = 0xFF497C9A,
        ),
    )

    fun find(name: String): StoryLicensedClip? =
        clips.firstOrNull { it.title == name }

    fun persistedStyle(name: String?): String =
        if (name == "My video" || clips.any { it.title == name }) name!! else clips.first().title
}

/** Respect server-provided Retry-After instead of hammering Wikimedia on HTTP 429. */
internal object StoryVideoRetryPolicy {
    fun delayMs(httpCode: Int, retryAfter: String?): Long {
        val defaultDelay = if (httpCode == 429) 600_000L else 120_000L
        val seconds = retryAfter?.trim()?.toLongOrNull()
        val parsed = seconds?.coerceIn(5, 3600)?.times(1000L)
        return (parsed ?: defaultDelay).coerceIn(5_000L, 3_600_000L)
    }

    fun waitMessage(remainingMs: Long): String {
        val minutes = ((remainingMs.coerceAtLeast(0) + 59_999L) / 60_000L).coerceAtLeast(1)
        return "Wikimedia download limit reached. Try again in about " +
            minutes + " min, or use My video."
    }
}

/** Cache in private filesDir, rather than cacheDir, to preserve downloaded loops. */
internal class StoryVideoCache(private val context: Context) {
    companion object { private const val MAX_BYTES = 24L * 1024L * 1024L }
    private val folder = File(context.filesDir, "licensed_story_videos")
    private val networkPreferences = context.getSharedPreferences(
        "licensed_video_download_limits", Context.MODE_PRIVATE
    )
    private fun cooldownRemaining(): Long =
        (networkPreferences.getLong("next_allowed_download", 0L) -
            System.currentTimeMillis()).coerceAtLeast(0L)

    fun cached(clip: StoryLicensedClip): File? {
        val file = File(folder, clip.id + ".webm")
        return file.takeIf { it.isFile && it.length() in 1_024..MAX_BYTES }
    }

    suspend fun acquire(clip: StoryLicensedClip, onProgress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            cached(clip)?.let { return@withContext it }
            val remaining = cooldownRemaining()
            if (remaining > 0L) throw IOException(StoryVideoRetryPolicy.waitMessage(remaining))
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
                    setRequestProperty("User-Agent",
                        "AudioShelf/0.5 (https://github.com/modealpha88-ctrl/audioshelf)")
                    setRequestProperty("Referer", clip.sourceUrl)
                    setRequestProperty("Accept", "video/webm,video/*;q=0.9,*/*;q=0.2")
                }
                try {
                    connection.connect()
                    val response = connection.responseCode
                    if (response == 429 || response == 503) {
                        val delay = StoryVideoRetryPolicy.delayMs(
                            response, connection.getHeaderField("Retry-After")
                        )
                        networkPreferences.edit()
                            .putLong("next_allowed_download", System.currentTimeMillis() + delay)
                            .apply()
                        throw IOException(StoryVideoRetryPolicy.waitMessage(delay))
                    }
                    check(response in 200..299) {
                        "Video download failed (HTTP " + response + ")."
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
