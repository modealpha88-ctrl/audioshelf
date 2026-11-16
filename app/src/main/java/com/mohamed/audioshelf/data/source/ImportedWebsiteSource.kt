package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Android adaptation of the HTML extraction patterns in
 * https://github.com/aviiciii/audiobook-downloader/tree/main/scrapers
 *
 * This is a user-initiated single-page importer, NOT a remote code/plugin loader or
 * a crawler. Import only recordings for which the listener has access rights.
 */
class ImportedWebsiteSource : AudioSource {
    override val id = "imported_website"
    override val name = "Imported website audiobook"
    override val attribution = "User-supplied audiobook page; check rights and source terms"

    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    suspend fun importUrl(rawUrl: String): AudioBook = withContext(Dispatchers.IO) {
        val pageUrl = approvedPage(rawUrl)
        val parsed = parse(pageUrl)
        require(parsed.chapters.isNotEmpty()) {
            "No directly playable audio chapters found on this page."
        }
        AudioBook(
            id = pageUrl,
            sourceId = id,
            title = parsed.title,
            authors = parsed.author.ifBlank { "Unknown author" },
            description = parsed.description,
            coverUrl = parsed.cover,
        )
    }

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        val pageUrl = approvedPage(book.id)
        parse(pageUrl).chapters
    }

    private data class Parsed(
        val title: String,
        val author: String,
        val description: String,
        val cover: String,
        val chapters: List<AudioChapter>,
    )

    private fun approvedPage(raw: String): String {
        val uri = URI(raw.trim()).normalize()
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.host != null &&
            uri.path.orEmpty().trim('/').isNotBlank() &&
            uri.port == -1 && uri.fragment == null) {
            "Enter a full HTTPS link to an audiobook page."
        }
        val host = uri.host.lowercase().removePrefix("www.")
        require(host in SUPPORTED_HOSTS) {
            "This site is not supported by the URL importer."
        }
        require(host != "tokybook.com") {
            "TokyBook uses protected playlist and streaming tokens; the HTML importer cannot play its streams."
        }
        return uri.toASCIIString()
    }

    private fun parse(pageUrl: String): Parsed {
        // Reject cross-site redirects: a pasted link cannot make the importer fetch
        // an arbitrary internal or third-party host.
        val response = Jsoup.connect(pageUrl)
            .userAgent("AudioShelf/0.4 (Android; user-initiated audiobook-page import)")
            .timeout(30_000)
            .maxBodySize(2_000_000)
            .followRedirects(false)
            .execute()
        require(response.statusCode() == 200) { "Audiobook page returned HTTP " + response.statusCode() }
        val doc = response.parse()
        val host = URI(pageUrl).host.lowercase().removePrefix("www.")

        val selector = when (host) {
            "zaudiobooks.com" -> "h1.page-title, h1"
            "fulllengthaudiobooks.net" -> "h1.entry-title.post-title, h1"
            "hdaudiobooks.net" -> "h1[itemprop=headline], h1"
            "bigaudiobooks.net", "goldenaudiobook.com", "goldenaudiobook.net" -> "h1.title-page, h1"
            else -> "h1"
        }
        val headline = doc.selectFirst(selector)?.text()?.trim().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { doc.title() }
            .ifBlank { "Untitled" }
        val (title, author) = splitTitle(headline, host)

        val imageSelector = when (host) {
            "zaudiobooks.com" -> ".inner-article-content img, meta[property=og:image]"
            "hdaudiobooks.net" -> "img[itemprop=image], meta[property=og:image]"
            else -> ".wp-caption img, figure.wp-caption img, meta[property=og:image]"
        }
        val image = doc.selectFirst(imageSelector)
        val cover = image?.let { tag ->
            val address = tag.attr("data-lazy-src").ifBlank { tag.attr("src") }
                .ifBlank { tag.attr("content") }
            safeMediaUrl(address, pageUrl).orEmpty()
        }.orEmpty()

        val audioUrls = if (host == "zaudiobooks.com") parseZaudiobooks(doc)
        else {
            val audioSelector = when (host) {
                "fulllengthaudiobooks.net" -> ".entry source[type*=audio], .entry audio source"
                "hdaudiobooks.net" -> ".entry source[type*=audio], .entry-box source[type*=audio]"
                "bigaudiobooks.net" -> ".post-single source[type*=audio], .post-single audio source"
                else -> "audio.wp-audio-shortcode source[src]"
            }
            doc.select(audioSelector).mapNotNull { safeMediaUrl(it.attr("src"), pageUrl) }
        }
        return Parsed(
            title = title,
            author = author,
            description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
            cover = cover,
            chapters = audioUrls.distinct().take(500).mapIndexed { index, url ->
                AudioChapter(
                    id = pageUrl + ":" + index,
                    title = "Chapter " + (index + 1).toString().padStart(3, '0'),
                    audioUrl = url,
                )
            },
        )
    }

    private fun parseZaudiobooks(doc: Document): List<String> {
        // The site's player populates a JavaScript tracks array. The Python
        // scraper reads name and chapter_link_dropbox within each track object.
        val html = doc.select("script").joinToString("\n") { it.data() }
        val trackBlock = Regex("""\btracks\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val objects = Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL)
        val names = Regex("""\bname\s*:\s*['"]([^'"]+)['"]""")
        val paths = Regex("""\bchapter_link_dropbox\s*:\s*['"]([^'"]+)['"]""")
        return objects.findAll(trackBlock).mapNotNull { match ->
            val text = match.value
            if (names.find(text)?.groupValues?.get(1)?.equals("welcome", true) == true) return@mapNotNull null
            val link = paths.find(text)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@mapNotNull null
            safeMediaUrl(link, "https://files01.freeaudiobooks.top/audio/")
        }.take(500).toList()
    }

    private fun splitTitle(raw: String, host: String): Pair<String, String> {
        val cleaned = raw.replace(
            Regex("""\s*(?:\(?audiobook\)?(?:\s+free)?|audio book online|free)\s*$""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        val parts = cleaned.split(Regex("""\s+[-–]\s+"""), limit = 2)
        if (parts.size < 2) return cleaned to ""
        return if (host == "hdaudiobooks.net") parts[0] to parts[1]
        else parts[1] to parts[0]
    }

    private fun safeMediaUrl(raw: String, pageUrl: String): String? = runCatching {
        if (raw.isBlank()) return@runCatching null
        val address = URI(pageUrl).resolve(raw.trim().replace(" ", "%20")).normalize()
        if (address.scheme.equals("https", ignoreCase = true) &&
            !address.host.isNullOrBlank() && address.userInfo == null &&
            address.port == -1) address.toASCIIString() else null
    }.getOrNull()

    private companion object {
        val SUPPORTED_HOSTS = setOf(
            "tokybook.com", "zaudiobooks.com", "fulllengthaudiobooks.net",
            "hdaudiobooks.net", "bigaudiobooks.net",
            "goldenaudiobook.com", "goldenaudiobook.net",
        )
    }
}
