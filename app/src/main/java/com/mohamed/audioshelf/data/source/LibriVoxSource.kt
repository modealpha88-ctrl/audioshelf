package com.mohamed.audioshelf.data.source

import android.util.Xml
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LibriVoxSource : AudioSource {
    override val id = "librivox"
    override val name = "LibriVox"
    override val attribution = "Public-domain recordings from LibriVox volunteers"

    override suspend fun browse(page: Int): List<AudioBook> = fetchBooks(page = page)

    override suspend fun search(query: String, page: Int): List<AudioBook> =
        fetchBooks(query = query.trim(), page = page)

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        require(book.feedUrl.startsWith("https://")) { "Only secure feeds are supported" }
        open(book.feedUrl).use { input ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            val chapters = mutableListOf<AudioChapter>()
            var inItem = false
            var title = ""
            var url = ""
            var duration = 0L
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item" -> { inItem = true; title = ""; url = ""; duration = 0L }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "enclosure" -> if (inItem) url = parser.getAttributeValue(null, "url").orEmpty()
                        "itunes:duration", "duration" -> if (inItem) duration = parseDuration(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            chapters += AudioChapter(
                                id = "${book.id}:${chapters.size}",
                                title = title.ifBlank { "Chapter ${chapters.size + 1}" },
                                audioUrl = url,
                                durationMs = duration,
                            )
                        }
                        inItem = false
                    }
                }
                parser.next()
            }
            chapters
        }
    }

    private suspend fun fetchBooks(query: String = "", page: Int): List<AudioBook> = withContext(Dispatchers.IO) {
        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val title = if (query.isBlank()) "" else "&title=${encode(query)}"
        val endpoint = "https://librivox.org/api/feed/audiobooks?format=json&extended=1&coverart=1&sort_order=desc&limit=$PAGE_SIZE&offset=$offset$title"
        val root = JSONObject(open(endpoint).bufferedReader().use { it.readText() })
        val books = root.optJSONArray("books") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until books.length()) {
                val item = books.getJSONObject(index)
                val authors = item.optJSONArray("authors")
                val authorText = buildList {
                    if (authors != null) for (i in 0 until authors.length()) {
                        val author = authors.getJSONObject(i)
                        add(listOf(author.optString("first_name"), author.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "))
                    }
                }.filter { it.isNotBlank() }.joinToString(", ")
                val archiveId = item.optString("url_iarchive").trimEnd('/').substringAfterLast('/')
                add(
                    AudioBook(
                        id = item.getString("id"),
                        sourceId = id,
                        title = item.optString("title", "Untitled"),
                        authors = authorText.ifBlank { "Unknown author" },
                        description = item.optString("description").replace(Regex("<[^>]+>"), "").trim(),
                        coverUrl = item.optString("coverart_thumbnail").ifBlank {
                            if (archiveId.isBlank()) "" else "https://archive.org/services/img/$archiveId"
                        },
                        feedUrl = item.optString("url_rss"),
                        language = item.optString("language"),
                    )
                )
            }
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "AudioShelf/0.1 (+Android; LibriVox client)")
        inputStream
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun parseDuration(raw: String): Long {
        val parts = raw.trim().split(':').mapNotNull(String::toLongOrNull)
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
        return seconds * 1000
    }

    private companion object { const val PAGE_SIZE = 30 }
}
