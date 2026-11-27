package com.mohamed.audioshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

// Hash actual decoded cover pixels, not the URL: replaced artwork at the same URL
// must receive a fresh match. Bump the algorithm version when the matcher changes.
internal fun spineRecognitionKey(bitmap: Bitmap, title: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    hash.update("spine-v5:$title:${bitmap.width}:${bitmap.height}:".toByteArray(Charsets.UTF_8))
    val row = IntArray(bitmap.width)
    val bytes = ByteBuffer.allocate(bitmap.width * 4)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        bytes.clear()
        bytes.asIntBuffer().put(row)
        hash.update(bytes.array())
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

private fun cacheDir(context: Context) =
    File(context.filesDir, "spine_recognition_v5").apply { mkdirs() }

private const val RETRY_MS = 86400000L
private const val MAX_BYTES = 32L * 1024 * 1024

internal fun readSpineRecognition(context: Context, key: String): LetteringResult? = runCatching {
    val root = cacheDir(context)
    val png = File(root, "$key.png")
    val meta = File(root, "$key.txt")
    if (png.isFile && png.length() in 1..4000000) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.absolutePath, bounds)
        if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..256) {
            BitmapFactory.decodeFile(png.absolutePath)?.let {
                png.setLastModified(System.currentTimeMillis())
                return@runCatching LetteringResult(it)
            }
        }
    }
    if (!meta.isFile || meta.length() > 4096) return@runCatching null
    // Font matches are revisited periodically so newly available fonts can improve them.
    if (System.currentTimeMillis() - meta.lastModified() > RETRY_MS) return@runCatching null
    val fields = meta.readText().split('|')
    if (fields.size != 4) return@runCatching null
    val score = fields[2].toFloatOrNull() ?: return@runCatching null
    if (!score.isFinite() || score !in 0f..1f) return@runCatching null
    val match = if (fields[0].isBlank()) null else
        restoreCoverFont(context, fields[0], fields[1] == "true", score) ?: return@runCatching null
    val inks = fields[3].split(';').mapNotNull {
        val pair = it.split(':')
        if (pair.size != 2 || pair[0].isBlank()) null
        else pair[1].toIntOrNull()?.let { color -> pair[0] to color }
    }.toMap()
    if (match == null && inks.isEmpty()) return@runCatching null
    LetteringResult(null, match, inks)
}.getOrNull()

private fun atomicWrite(file: File, writer: (java.io.OutputStream) -> Unit) {
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    try {
        writer(out)
        atomic.finishWrite(out)
    } catch (failure: Exception) {
        atomic.failWrite(out)
        throw failure
    }
}

internal fun writeSpineRecognition(context: Context, key: String, result: LetteringResult) {
    runCatching {
        val root = cacheDir(context)
        val bitmap = result.bitmap
        val match = result.fontMatch
        when {
            bitmap != null -> atomicWrite(File(root, "$key.png")) {
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            match != null || result.wordInks.isNotEmpty() -> atomicWrite(File(root, "$key.txt")) {
                val inks = result.wordInks.entries.joinToString(";") { (word, color) -> "$word:$color" }
                it.write("${match?.family.orEmpty()}|${match?.upperCase ?: false}|${match?.score ?: 1f}|$inks".toByteArray(Charsets.UTF_8))
            }
            // Do not persist failed recognition: an expanded font catalog may help later.
            else -> return@runCatching
        }
        val files = root.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= MAX_BYTES && count <= 600) break
            val length = file.length()
            if (file.delete()) { bytes -= length; count-- }
        }
    }
}
