package com.mohamed.audioshelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.mohamed.audioshelf.data.SpineBindingMode
import com.mohamed.audioshelf.model.AudioBook
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class ShelfSnapshot(val visual: StableSpineVisual, val ratio: Float, val hash: String, val complete: Boolean)

/** One atomic, versioned record per appearance. All access runs on the IO dispatcher. */
internal class ShelfSnapshotStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "shelf_snapshots_v1")

    private fun file(book: AudioBook, binding: SpineBindingMode): File {
        val identity = listOf(book.sourceId, book.id, book.title, book.authors, book.coverUrl, binding.name)
            .joinToString("") { "${it.length}:$it" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return File(directory, bytes.joinToString("") { "%02x".format(it) })
    }

    private fun DataInputStream.bitmap(maxWidth: Int, maxHeight: Int): Bitmap? {
        val length = readInt()
        if (length == 0) return null
        require(length in 1..2_000_000)
        val bytes = ByteArray(length).also { readFully(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
        require(bounds.outWidth in 1..maxWidth && bounds.outHeight in 1..maxHeight)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, length))
    }

    private fun DataOutputStream.bitmap(bitmap: Bitmap?) {
        if (bitmap == null) { writeInt(0); return }
        val buffer = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) throw IOException("PNG encoding failed")
        writeInt(buffer.size())
        buffer.writeTo(this)
    }

    fun read(book: AudioBook, binding: SpineBindingMode): ShelfSnapshot? = synchronized(lock) {
        try {
            val path = file(book, binding)
            AtomicFile(path).openRead().use { stream ->
                val input = DataInputStream(stream.buffered())
                val hash = input.readUTF()
                val ratio = input.readFloat()
                require(ratio.isFinite() && ratio > 0f)
                val complete = input.readBoolean()
                val palette = BookstoreSpineColors(Color(input.readInt()), Color(input.readInt()), input.readBoolean())
                val jacket = requireNotNull(input.bitmap(384, 512)).asImageBitmap()
                val lettering = input.bitmap(1024, 128)?.asImageBitmap()
                path.setLastModified(System.currentTimeMillis())
                ShelfSnapshot(StableSpineVisual(palette, jacket, lettering), ratio, hash, complete)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
    }

    fun write(book: AudioBook, binding: SpineBindingMode, snapshot: ShelfSnapshot) = synchronized(lock) {
        try {
            directory.mkdirs()
            val path = file(book, binding)
            val atomic = AtomicFile(path)
            val stream = atomic.startWrite()
            try {
                val output = DataOutputStream(stream.buffered())
                with(snapshot) {
                    output.writeUTF(hash)
                    output.writeFloat(ratio)
                    output.writeBoolean(complete)
                    output.writeInt(visual.palette.cloth.toArgb())
                    output.writeInt(visual.palette.ink.toArgb())
                    output.writeBoolean(visual.palette.hasCoverPalette)
                    output.bitmap(visual.preview?.asAndroidBitmap())
                    output.bitmap(visual.lettering?.asAndroidBitmap())
                }
                output.flush()
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
                throw failure
            }
            val files = directory.listFiles().orEmpty()
            var size = files.sumOf { it.length() }
            if (size > 64L * 1024 * 1024) {
                for (old in files.sortedBy { it.lastModified() }) {
                    if (size <= 60L * 1024 * 1024) break
                    val bytes = old.length()
                    if (old != path && old.delete()) size -= bytes
                }
            }
        } catch (_: IOException) { /* Storage pressure must not interrupt shelf rendering. */ }
        Unit
    }

    private companion object { val lock = Any() }
}
