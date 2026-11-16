package com.mohamed.audioshelf.data.captions

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-initiated one-time download of a free Apache-2.0 Vosk model, on-device only. */
class OfflineSpeechModel(private val context: Context) {
    companion object {
        const val NAME = "vosk-model-small-en-us-0.15"
        const val SOURCE = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    }

    val directory: File get() = File(context.filesDir, "speech_models/" + NAME)
    fun isInstalled(): Boolean = File(directory, ".download_complete").isFile &&
        File(directory, "am/final.mdl").isFile &&
        File(directory, "conf/model.conf").isFile

    suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext directory
        val cache = File(context.cacheDir, NAME + ".zip.download")
        val staging = File(context.filesDir, "speech_models/" + NAME + "_unpacking")
        val target = directory
        target.parentFile?.mkdirs()
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            try {
                require(connection.responseCode in 200..299) {
                    "Model download failed (HTTP " + connection.responseCode + ")."
                }
                val hash = MessageDigest.getInstance("SHA-256")
                var bytesRead = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(cache).use { output ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val count = input.read(buf)
                            if (count < 0) break
                            bytesRead += count
                            require(bytesRead <= 110L * 1024 * 1024) { "Model archive exceeds allowed size." }
                            hash.update(buf, 0, count)
                            output.write(buf, 0, count)
                        }
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(SHA256, ignoreCase = true)) {
                    "Model integrity check failed; download was discarded."
                }
            } finally { connection.disconnect() }

            var expanded = 0L
            var entries = 0
            ZipInputStream(cache.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 25000) { "Model archive contains too many files." }
                    val prefix = NAME + "/"
                    require(entry.name.startsWith(prefix)) { "Unexpected model archive layout." }
                    val relative = entry.name.removePrefix(prefix)
                    if (relative.isNotBlank()) {
                        val file = File(staging, relative)
                        require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Invalid model archive entry."
                        }
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val count = zip.read(buf)
                                    if (count < 0) break
                                    expanded += count
                                    require(expanded <= 350L * 1024 * 1024) {
                                        "Extracted model exceeds allowed size."
                                    }
                                    out.write(buf, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(staging, "am/final.mdl").isFile && File(staging, "conf/model.conf").isFile) {
                "The archive did not contain a valid Vosk model."
            }
            target.deleteRecursively()
            require(staging.renameTo(target)) { "Could not finish model installation." }
            File(target, ".download_complete").writeText(SHA256)
            target
        } finally {
            cache.delete()
            staging.deleteRecursively()
        }
    }
}
