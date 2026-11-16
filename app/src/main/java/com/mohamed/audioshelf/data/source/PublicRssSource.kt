package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.net.URI

/**
 * User-chosen public RSS audiobook/podcast feed. Playback uses its published
 * HTTPS enclosures; this adapter never extracts paid Scribl player audio.
 */
class PublicRssSource : AudioSource {
    override val id = "public_rss"
    override val name = "My podcast audiobooks"
    override val attribution = "Publisher-provided public RSS audio feeds"

    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    suspend fun importFeed(rawUrl: String): AudioBook = withContext(Dispatchers.IO) {
        val feedUrl = validateUrl(rawUrl)
        val result = readFeed(feedUrl)
        require(result.chapters.isNotEmpty()) {
            "This feed does not publish playable HTTPS audio enclosures."
        }
        AudioBook(
            id = feedUrl, sourceId = id,
            title = result.title,
            authors = result.author,
            description = result.description,
            coverUrl = result.cover,
            feedUrl = feedUrl,
        )
    }

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        readFeed(validateUrl(book.feedUrl.ifBlank { book.id })).chapters
    }

    private data class Feed(
        val title: String,
        val author: String,
        val description: String,
        val cover: String,
        val chapters: List<AudioChapter>,
    )

    private fun readFeed(feedUrl: String): Feed {
        // Reject uninspected cross-domain redirects and cap response size.
        val response = Jsoup.connect(feedUrl)
            .userAgent("AudioShelf/0.5 (Android; user-selected public RSS)")
            .timeout(30_000).maxBodySize(2_000_000)
            .ignoreContentType(true).followRedirects(false)
            .execute()
        require(response.statusCode() in 200..299) { "RSS feed returned HTTP " + response.statusCode() }
        val doc = Jsoup.parse(response.body(), "", Parser.xmlParser())
        val channel = doc.selectFirst("channel") ?: throw IllegalArgumentException("Enter a podcast RSS 2.0 feed URL.")
        val title = channel.selectFirst("> title")?.text()?.trim().orEmpty()
        require(title.isNotBlank()) { "The RSS feed has no title." }
        val coverRaw = channel.selectFirst("image > url")?.text().orEmpty().ifBlank {
            channel.selectFirst("itunes|image")?.attr("href").orEmpty()
        }
        val cover = mediaUrl(coverRaw, feedUrl).orEmpty()
        val author = channel.selectFirst("itunes|author")?.text().orEmpty().ifBlank {
            channel.selectFirst("managingEditor")?.text().orEmpty()
        }.ifBlank { "Unknown author" }
        val chapters = channel.select("item").take(500).mapIndexedNotNull { index, item ->
            val enclosure = item.selectFirst("enclosure[url]") ?: return@mapIndexedNotNull null
            val audio = mediaUrl(enclosure.attr("url"), feedUrl) ?: return@mapIndexedNotNull null
            val mime = enclosure.attr("type")
            if (mime.isNotBlank() && !mime.startsWith("audio/") &&
                mime != "application/octet-stream" && mime != "video/mp4") return@mapIndexedNotNull null
            val episode = item.selectFirst("title")?.text()?.trim().orEmpty()
            AudioChapter(
                id = item.selectFirst("guid")?.text()?.takeIf { it.isNotBlank() } ?: "$feedUrl:$index",
                title = episode.ifBlank { "Episode " + (index + 1) },
                audioUrl = audio,
            )
        }
        return Feed(
            title = title,
            author = author,
            description = channel.selectFirst("description")?.text().orEmpty(),
            cover = cover,
            chapters = chapters,
        )
    }

    private fun mediaUrl(raw: String, base: String): String? = runCatching {
        val candidate = URI(base).resolve(raw.trim()).toString()
        validateUrl(candidate)
    }.getOrNull()

    private fun validateUrl(raw: String): String {
        val uri = URI(raw.trim()).normalize()
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)) {
            "Only public HTTPS RSS and audio URLs are supported."
        }
        val host = uri.host.lowercase().trimEnd('.')
        require(host.contains('.') && host != "localhost" &&
            listOf(".local", ".localhost", ".internal", ".home.arpa", ".test", ".invalid")
                .none(host::endsWith)) { "Local-network addresses are not allowed." }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all {
            val bytes = it.address
            val cgNat = bytes.size == 4 && (bytes[0].toInt() and 255) == 100 &&
                ((bytes[1].toInt() and 192) == 64)
            val uniqueLocal = bytes.size == 16 && ((bytes[0].toInt() and 254) == 252)
            !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                !it.isSiteLocalAddress && !it.isMulticastAddress && !cgNat && !uniqueLocal
        }) { "Private-network addresses are not allowed." }
        return uri.toASCIIString()
    }
}
