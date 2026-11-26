package com.mohamed.audioshelf.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.mohamed.audioshelf.data.torrent.TorrentDownloads
import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** User override is opt-in. Every newly imported title gets an automatic binding by default. */
enum class SpineBindingMode {
    AUTOMATIC, ORIGINAL, DECORATIVE, CLASSIC, CUSTOM;

    companion object {
        fun fromStored(raw: String?): SpineBindingMode =
            entries.firstOrNull { it.name == raw } ?: AUTOMATIC
    }
}

/** Only an explicitly named spine from the audiobook package counts as original spine art. */
internal fun isNamedSpineImage(filename: String): Boolean {
    val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in setOf("jpg", "jpeg", "png", "webp")) return false
    val stem = filename.substringBeforeLast('.').lowercase(Locale.ROOT)
        .replace(Regex("[_ -]+"), " ").trim()
    return stem in setOf("spine", "book spine", "spine artwork", "spine art", "jacket spine")
}

class SpineBindingStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("audio_shelf_spine_bindings", Context.MODE_PRIVATE)
    private val artDirectory = File(context.filesDir, "spine_art").apply { mkdirs() }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun bookKey(book: AudioBook): String = digest("${book.sourceId}:${book.id}")

    fun mode(book: AudioBook): SpineBindingMode =
        SpineBindingMode.fromStored(prefs.getString("mode-${bookKey(book)}", null))

    fun setMode(book: AudioBook, mode: SpineBindingMode) {
        require(mode != SpineBindingMode.CUSTOM || customArtwork(book) != null) {
            "Select a spine image before enabling custom artwork."
        }
        require(mode != SpineBindingMode.ORIGINAL || bundledArtwork(book) != null) {
            "No publisher spine image was supplied with this audiobook."
        }
        prefs.edit().putString("mode-${bookKey(book)}", mode.name).apply()
    }

    fun customArtwork(book: AudioBook): String? {
        val uri = prefs.getString("custom-${bookKey(book)}", null) ?: return null
        if (!uri.startsWith("file://")) return null
        return runCatching {
            val file = File(requireNotNull(Uri.parse(uri).path))
            if (file.isFile && file.length() in 100..6_000_000 &&
                file.canonicalPath.startsWith(artDirectory.canonicalPath + File.separator)
            ) uri else null
        }.getOrNull()
    }

    /**
     * A torrent may supply spine.jpg. Never assume a cropped front cover is an official
     * spine, and never pick an arbitrary volume when a collection has multiple spines.
     */
    fun bundledArtwork(book: AudioBook): String? {
        if (book.sourceId != "torrent_files" ||
            !book.id.matches(Regex("[a-fA-F0-9]{32}"))) return null
        val root = TorrentDownloads.directory(context, book.id)
        if (!root.isDirectory) return null
        val canonicalRoot = root.canonicalPath + File.separator
        val candidates = root.walkTopDown().maxDepth(8).filter { file ->
            file.isFile && file.length() in 1_024L..6_000_000L &&
                file.canonicalPath.startsWith(canonicalRoot) &&
                isNamedSpineImage(file.name)
        }.take(3).toList()
        if (candidates.size != 1) return null
        val file = candidates.single()
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, info)
        // Reject unrelated thumbnail/cover shapes masquerading as spine artwork.
        if (info.outWidth !in 50..5000 || info.outHeight !in 150..12000 ||
            info.outHeight < info.outWidth * 1.5f) return null
        return Uri.fromFile(file).toString()
    }

    fun displayedArtwork(book: AudioBook, mode: SpineBindingMode = mode(book)): String? =
        when (mode) {
            SpineBindingMode.AUTOMATIC, SpineBindingMode.ORIGINAL -> bundledArtwork(book)
            SpineBindingMode.CUSTOM -> customArtwork(book)
            SpineBindingMode.DECORATIVE, SpineBindingMode.CLASSIC -> null
        }

    /** Use a private, content-addressed file; never modify the original cover or audio. */
    suspend fun importCustomArtwork(book: AudioBook, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select a picture with the Android file picker." }
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot read the selected image.")
        val data = source.use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                require(bytes.size() + read <= 6_000_000) { "Spine image must be smaller than 6 MB." }
                bytes.write(chunk, 0, read)
            }
            bytes.toByteArray()
        }
        val extension = when {
            data.size >= 3 && data[0] == 0xFF.toByte() &&
                data[1] == 0xD8.toByte() -> "jpg"
            data.size >= 8 && data[0] == 0x89.toByte() &&
                data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() -> "png"
            data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> throw IllegalArgumentException("Choose a JPG, PNG or WebP image.")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 50..12000 && bounds.outHeight in 100..12000) {
            "Spine artwork dimensions are invalid."
        }
        // Image bytes are hashed directly; a changed image gets a different cache key.
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
            .take(10).joinToString("") { "%02x".format(it) }
        val image = File(artDirectory, "${bookKey(book)}-$hash.$extension")
        val temporary = File.createTempFile("spine-", ".tmp", artDirectory)
        try {
            temporary.writeBytes(data)
            if (!temporary.renameTo(image)) temporary.copyTo(image, overwrite = true)
        } finally {
            temporary.delete()
        }
        prefs.edit()
            .putString("custom-${bookKey(book)}", Uri.fromFile(image).toString())
            .putString("mode-${bookKey(book)}", SpineBindingMode.CUSTOM.name)
            .apply()
        Uri.fromFile(image).toString()
    }

    /** Cached palette is retained when the cover loader temporarily has no network access. */
    fun palette(book: AudioBook): Pair<Int, Int>? {
        if (book.coverUrl.isBlank()) return null
        val key = "palette-${bookKey(book)}-${digest(book.coverUrl).take(12)}"
        val stored = prefs.getString(key, null)?.split(':') ?: return null
        return if (stored.size == 2) runCatching {
            (stored[0].toInt() to stored[1].toInt())
        }.getOrNull() else null
    }

    fun savePalette(book: AudioBook, clothArgb: Int, inkArgb: Int) {
        if (book.coverUrl.isBlank()) return
        val key = "palette-${bookKey(book)}-${digest(book.coverUrl).take(12)}"
        val serialized = "$clothArgb:$inkArgb"
        if (prefs.getString(key, null) != serialized)
            prefs.edit().putString(key, serialized).apply()
    }
}
