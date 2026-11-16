package com.mohamed.audioshelf.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class M4bChapterParserTest {
    private fun u32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
    private fun join(vararg bytes: ByteArray): ByteArray = bytes.fold(ByteArray(0)) { a, b -> a + b }
    private fun atom(type: String, data: ByteArray) =
        join(u32(data.size.toLong() + 8), type.toByteArray(Charsets.US_ASCII), data)
    private fun fullBox(type: String, body: ByteArray) = atom(type, join(u32(0), body))
    private fun parse(data: ByteArray): List<M4bChapterParser.Marker> {
        val file = Files.createTempFile("test-chapters", ".m4b")
        return try {
            Files.write(file, data)
            Files.newByteChannel(file).use { M4bChapterParser.read(it) }
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun neroChapterNamesAndTimes() {
        fun entry(start: Long, title: String): ByteArray {
            val label = title.toByteArray(Charsets.UTF_8)
            return join(u64(start * 10000), byteArrayOf(label.size.toByte()), label)
        }
        val chapters = atom("chpl", join(u32(0), u32(0), byteArrayOf(3),
            entry(0, "Opening"), entry(62000, "Chapter One"), entry(240000, "Chapter Two")))
        val file = join(atom("ftyp", byteArrayOf(1, 2, 3, 4)),
            atom("moov", atom("udta", chapters)), atom("mdat", ByteArray(8)))
        val result = parse(file)
        assertEquals(listOf(0L, 62000L, 240000L), result.map { it.startMs })
        assertEquals(listOf("Opening", "Chapter One", "Chapter Two"), result.map { it.title })
    }

    @Test fun quickTimeTextTrackChapters() {
        fun sample(title: String): ByteArray {
            val text = title.toByteArray(Charsets.UTF_8)
            return join(byteArrayOf(0, text.size.toByte()), text)
        }
        val first = sample("Introduction")
        val second = sample("Chapter 1")
        val ftyp = atom("ftyp", byteArrayOf(0, 0, 0, 0))
        val mdat = atom("mdat", first + second)
        fun track(id: Long, handler: String, reference: Boolean, table: ByteArray = ByteArray(0)): ByteArray {
            val tkhd = fullBox("tkhd", join(u32(0), u32(0), u32(id)))
            val mdhd = fullBox("mdhd", join(u32(0), u32(0), u32(1000)))
            val hdlr = fullBox("hdlr", join(u32(0), handler.toByteArray(Charsets.US_ASCII)))
            val mdia = atom("mdia", join(mdhd, hdlr, atom("minf", atom("stbl", table))))
            return atom("trak", join(tkhd,
                if (reference) atom("tref", atom("chap", u32(2))) else ByteArray(0), mdia))
        }
        val stts = fullBox("stts", join(u32(1), u32(2), u32(5000)))
        val stsc = fullBox("stsc", join(u32(1), u32(1), u32(2), u32(1)))
        val stsz = fullBox("stsz", join(u32(0), u32(2),
            u32(first.size.toLong()), u32(second.size.toLong())))
        val stco = fullBox("stco", join(u32(1), u32((ftyp.size + 8).toLong())))
        val file = join(ftyp, mdat, atom("moov", join(
            track(1, "soun", true), track(2, "text", false, join(stts, stsc, stsz, stco)))))
        val result = parse(file)
        assertEquals(listOf("Introduction", "Chapter 1"), result.map { it.title })
        assertEquals(listOf(0L, 5000L), result.map { it.startMs })
    }

    @Test fun malformedAndMissingChapterTables() {
        assertTrue(parse(atom("mdat", byteArrayOf(1, 2, 3))).isEmpty())
        assertTrue(parse(atom("moov", atom("udta", atom("chpl", byteArrayOf(0, 0))))).isEmpty())
        assertTrue(parse(byteArrayOf(127, 127, 127, 127, 1, 2, 3, 4)).isEmpty())
    }
}
