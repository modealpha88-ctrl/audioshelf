package com.mohamed.audioshelf.data.source

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/** Reads chapter tables without copying an M4B into memory or modifying its audio.
 * Supports Nero (moov/udta/chpl) and Apple/QuickTime text chapter tracks (tref/chap).
 * Deliberately ignores malformed boxes and caps allocations/sample counts.
 */
internal object M4bChapterParser {
    data class Marker(val startMs: Long, val title: String)
    private data class Box(val type: String, val data: Long, val end: Long)
    private data class Track(val id: Long, val kind: String, val timeScale: Long,
                             val refs: List<Long>, val stbl: Box?)
    private const val MAX_CHAPTERS = 4096
    private const val MAX_TITLE_BYTES = 16384

    fun read(channel: SeekableByteChannel): List<Marker> {
        val io = Reader(channel)
        val moov = io.boxes(0, channel.size()).firstOrNull { it.type == "moov" }
            ?: return emptyList()
        val moovChildren = io.boxes(moov.data, moov.end)
        val udta = moovChildren.firstOrNull { it.type == "udta" }
        val nero = udta?.let { io.boxes(it.data, it.end).firstOrNull { box -> box.type == "chpl" } }
            ?.let(io::neroChapters).orEmpty()
        if (nero.isNotEmpty()) return clean(nero)

        val trakBoxes = moovChildren.filter { it.type == "trak" }
        val tracks = trakBoxes.mapNotNull(io::track)
        val chapterIds = tracks.flatMap { it.refs }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val textTrack = tracks.firstOrNull {
            it.id in chapterIds && it.kind in setOf("text", "sbtl", "subt", "clcp") && it.stbl != null
        } ?: return emptyList()
        return clean(io.quickTimeChapters(textTrack))
    }

    private fun clean(input: List<Marker>): List<Marker> = input
        .asSequence().filter { it.startMs >= 0 && it.title.isNotBlank() }
        .sortedBy { it.startMs }.distinctBy { it.startMs }.take(MAX_CHAPTERS).toList()

    private class Reader(private val channel: SeekableByteChannel) {
        private val size = channel.size()

        fun bytes(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || count > 1024 * 1024 ||
                offset > size || count.toLong() > size - offset) return null
            val buffer = ByteBuffer.allocate(count)
            channel.position(offset)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return null
            }
            return buffer.array()
        }
        private fun int(data: ByteArray, at: Int): Long {
            if (at < 0 || at + 4 > data.size) return -1
            return (data[at].toLong() and 255 shl 24) or
                ((data[at + 1].toLong() and 255) shl 16) or
                ((data[at + 2].toLong() and 255) shl 8) or
                (data[at + 3].toLong() and 255)
        }
        private fun uint(at: Long): Long = bytes(at, 4)?.let { int(it, 0) } ?: -1
        private fun ulong(at: Long): Long = bytes(at, 8)?.let {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).long.takeIf { value -> value >= 0 }
        } ?: -1

        fun boxes(start: Long, end: Long): List<Box> {
            if (start < 0 || end > size || end < start) return emptyList()
            val found = ArrayList<Box>()
            var pos = start
            while (end - pos >= 8 && found.size < 20000) {
                val h = bytes(pos, 8) ?: break
                val size32 = int(h, 0)
                val kind = String(h, 4, 4, Charsets.ISO_8859_1)
                val header = if (size32 == 1L) 16 else 8
                val atomSize = when (size32) {
                    0L -> end - pos
                    1L -> ulong(pos + 8)
                    else -> size32
                }
                if (atomSize < header || atomSize > end - pos) break
                found += Box(kind, pos + header, pos + atomSize)
                pos += atomSize
            }
            return found
        }

        fun neroChapters(box: Box): List<Marker> {
            // chpl: version/flags(4), reserved(4), count(1),
            // then [start time in 100 ns units(8), UTF-8 title length(1), title].
            if (box.end - box.data < 9) return emptyList()
            val head = bytes(box.data, 9) ?: return emptyList()
            val count = head[8].toInt() and 255
            val result = ArrayList<Marker>()
            var at = box.data + 9
            repeat(count) {
                if (at + 9 > box.end) return result
                val tick = ulong(at)
                val len = bytes(at + 8, 1)?.get(0)?.toInt()?.and(255) ?: return result
                at += 9
                if (tick < 0 || at + len > box.end) return result
                val text = bytes(at, len)?.toString(Charsets.UTF_8)?.trim() ?: return result
                at += len
                if (text.isNotBlank()) result += Marker(tick / 10_000, text)
            }
            return result
        }

        fun track(trak: Box): Track? {
            val children = boxes(trak.data, trak.end)
            val tkhd = children.firstOrNull { it.type == "tkhd" } ?: return null
            val version = bytes(tkhd.data, 1)?.get(0)?.toInt()?.and(255) ?: return null
            val id = uint(tkhd.data + if (version == 1) 20 else 12)
            if (id <= 0) return null
            val tref = children.firstOrNull { it.type == "tref" }
            val refs = tref?.let { boxes(it.data, it.end) }
                ?.filter { it.type == "chap" }
                ?.flatMap { box ->
                    val count = ((box.end - box.data) / 4).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()
                    (0 until count).mapNotNull { n ->
                        uint(box.data + n * 4L).takeIf { v -> v > 0 }
                    }
                }.orEmpty()
            val mdia = children.firstOrNull { it.type == "mdia" } ?: return null
            val media = boxes(mdia.data, mdia.end)
            val mdhd = media.firstOrNull { it.type == "mdhd" } ?: return null
            val mdhdVersion = bytes(mdhd.data, 1)?.get(0)?.toInt()?.and(255) ?: return null
            val scale = uint(mdhd.data + if (mdhdVersion == 1) 20 else 12)
            val hdlr = media.firstOrNull { it.type == "hdlr" }
            val kind = hdlr?.let { bytes(it.data + 8, 4)?.toString(Charsets.ISO_8859_1) }.orEmpty()
            val minf = media.firstOrNull { it.type == "minf" }
            val stbl = minf?.let { boxes(it.data, it.end).firstOrNull { b -> b.type == "stbl" } }
            return Track(id, kind, scale, refs, stbl)
        }

        fun quickTimeChapters(track: Track): List<Marker> {
            val stbl = track.stbl ?: return emptyList()
            if (track.timeScale <= 0) return emptyList()
            val boxes = boxes(stbl.data, stbl.end)
            val stts = boxes.firstOrNull { it.type == "stts" } ?: return emptyList()
            val stsz = boxes.firstOrNull { it.type == "stsz" } ?: return emptyList()
            val stsc = boxes.firstOrNull { it.type == "stsc" } ?: return emptyList()
            val stco = boxes.firstOrNull { it.type == "stco" || it.type == "co64" } ?: return emptyList()

            val sampleCount = uint(stsz.data + 8).toInt()
            if (sampleCount !in 1..MAX_CHAPTERS) return emptyList()
            val commonSize = uint(stsz.data + 4)
            val sampleSizes = (0 until sampleCount).map { n ->
                if (commonSize > 0) commonSize else uint(stsz.data + 12 + n * 4L)
            }
            if (sampleSizes.any { it <= 0 || it > MAX_TITLE_BYTES }) return emptyList()

            val times = ArrayList<Long>()
            val sttsCount = uint(stts.data + 4)
            if (sttsCount !in 1..MAX_CHAPTERS.toLong()) return emptyList()
            var tick = 0L
            for (n in 0 until sttsCount.toInt()) {
                val count = uint(stts.data + 8 + n * 8L)
                val delta = uint(stts.data + 12 + n * 8L)
                if (count <= 0 || count > sampleCount || delta < 0) return emptyList()
                repeat(count.toInt()) {
                    if (times.size < sampleCount) times += tick * 1000 / track.timeScale
                    if (tick > Long.MAX_VALUE - delta) return emptyList()
                    tick += delta
                }
                if (times.size >= sampleCount) break
            }
            if (times.size != sampleCount) return emptyList()

            val chunkCount = uint(stco.data + 4)
            if (chunkCount !in 1..100000) return emptyList()
            val offsetSize = if (stco.type == "co64") 8 else 4
            val chunkOffsets = (0 until chunkCount.toInt()).map { n ->
                val offset = stco.data + 8 + n * offsetSize.toLong()
                if (offsetSize == 8) ulong(offset) else uint(offset)
            }
            val mapCount = uint(stsc.data + 4)
            if (mapCount !in 1..MAX_CHAPTERS.toLong()) return emptyList()
            val chunkMap = (0 until mapCount.toInt()).map { n ->
                Pair(uint(stsc.data + 8 + n * 12L), uint(stsc.data + 12 + n * 12L))
            }
            if (chunkMap.any { it.first <= 0 || it.second <= 0 || it.second > MAX_CHAPTERS } ||
                chunkMap.first().first != 1L || chunkMap.zipWithNext().any { it.first.first >= it.second.first })
                return emptyList()

            val result = ArrayList<Marker>()
            var sampleIndex = 0
            for (chunk in chunkOffsets.indices) {
                if (sampleIndex == sampleCount) break
                var offset = chunkOffsets[chunk]
                if (offset < 0) return emptyList()
                val samplesInChunk = chunkMap.lastOrNull { it.first <= chunk + 1L }?.second ?: return emptyList()
                repeat(samplesInChunk.toInt()) {
                    if (sampleIndex < sampleCount) {
                        val sampleSize = sampleSizes[sampleIndex].toInt()
                        val bytes = bytes(offset, sampleSize) ?: return emptyList()
                        val text = decodeTitle(bytes)
                        if (text.isNotBlank()) result += Marker(times[sampleIndex], text)
                        offset += sampleSize
                        sampleIndex++
                    }
                }
            }
            return if (sampleIndex == sampleCount) result else emptyList()
        }

        private fun decodeTitle(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val length = if (bytes.size >= 2) (bytes[0].toInt() and 255 shl 8) or
                (bytes[1].toInt() and 255) else 0
            val textBytes = if (length in 1..(bytes.size - 2)) bytes.copyOfRange(2, 2 + length)
                else bytes
            val title = if (textBytes.size >= 2 &&
                ((textBytes[0] == 0xfe.toByte() && textBytes[1] == 0xff.toByte()) ||
                 (textBytes[0] == 0xff.toByte() && textBytes[1] == 0xfe.toByte()))) {
                String(textBytes, Charsets.UTF_16)
            } else String(textBytes, Charsets.UTF_8)
            return title.trim('\u0000', '\n', '\r', ' ', '\t').filter { !it.isISOControl() }
        }
    }
}
