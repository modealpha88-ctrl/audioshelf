package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class InternetArchiveSource : AudioSource {
    override val id = "internet_archive"
    override val name = "Internet Archive"
    override val attribution = "Open and public-domain audiobook recordings"

    override suspend fun browse(page: Int): List<AudioBook> = fetchBooks("", page)

    override suspend fun search(query: String, page: Int): List<AudioBook> =
        fetchBooks(query.trim(), page)

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        val metadata = JSONObject(read("https://archive.org/metadata/${encodePath(book.id)}"))
        val files = metadata.optJSONArray("files") ?: return@withContext emptyList()
        val candidates = buildList {
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val name = file.optString("name")
                val format = file.optString("format")
                if (name.endsWith(".mp3", ignoreCase = true) &&
                    !name.contains("sample", ignoreCase = true) &&
                    (format.contains("VBR MP3", true) || format.contains("64Kbps MP3", true) || format.contains("MP3", true))
                ) add(file)
            }
        }.distinctBy { it.optString("name") }.sortedBy { it.optString("name") }

        candidates.map { file ->
            val name = file.optString("name")
            AudioChapter(
                id = "${book.id}:$name",
                title = file.optString("title").ifBlank {
                    name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
                },
                audioUrl = "https://archive.org/download/${encodePath(book.id)}/${encodePath(name)}",
                durationMs = parseDuration(file.optString("length")),
            )
        }
    }

    private suspend fun fetchBooks(query: String, page: Int): List<AudioBook> = withContext(Dispatchers.IO) {
        val openAudio = "mediatype:audio AND (subject:audiobook OR subject:audiobooks) AND (licenseurl:* OR collection:librivoxaudio)"
        val terms = if (query.isBlank()) openAudio else "$openAudio AND (title:\"${escapeQuery(query)}\" OR creator:\"${escapeQuery(query)}\")"
        val endpoint = "https://archive.org/advancedsearch.php?q=${encode(terms)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=description&fl%5B%5D=language&rows=30&page=${page.coerceAtLeast(1)}&output=json&sort%5B%5D=downloads+desc"
        val docs = JSONObject(read(endpoint)).getJSONObject("response").optJSONArray("docs")
            ?: return@withContext emptyList()
        buildList {
            for (index in 0 until docs.length()) {
                val item = docs.optJSONObject(index) ?: continue
                val identifier = item.optString("identifier")
                if (identifier.isBlank()) continue
                add(
                    AudioBook(
                        id = identifier,
                        sourceId = id,
                        title = item.optString("title").ifBlank { "Untitled" },
                        authors = item.textValue("creator").ifBlank { "Unknown creator" },
                        description = item.textValue("description"),
                        coverUrl = "https://archive.org/services/img/${encodePath(identifier)}",
                        feedUrl = "https://archive.org/metadata/${encodePath(identifier)}",
                        language = item.textValue("language"),
                    )
                )
            }
        }
    }

    private fun JSONObject.textValue(key: String): String = when (val value = opt(key)) {
        is JSONArray -> buildList { for (i in 0 until value.length()) value.optString(i).takeIf { it.isNotBlank() }?.let(::add) }.joinToString(", ")
        is String -> value
        else -> ""
    }

    private fun read(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "AudioShelf/0.2 (+Android; open-audio client)")
        inputStream.bufferedReader().use { it.readText() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    private fun encodePath(value: String) = value.split('/').joinToString("/") { encode(it).replace("+", "%20") }
    private fun escapeQuery(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseDuration(raw: String): Long {
        val parts = raw.trim().split(':').mapNotNull(String::toDoubleOrNull)
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0.0
        }
        return (seconds * 1000).toLong()
    }
}
