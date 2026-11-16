package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * User-initiated, single-page HTML audio importer, with patterns adapted from:
 * https://github.com/aviiciii/audiobook-downloader
 *
 * Works on public HTTPS pages exposing playable audio links, regardless of domain.
 * Does not execute scripts, crawl websites, or bypass protected media endpoints.
 */
class ImportedWebsiteSource : AudioSource {
    override val id = "imported_website"
    override val name = "Imported website audiobook"
    override val attribution = "User-supplied public page; verify recording rights"

    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    suspend fun importUrl(rawUrl: String): AudioBook = withContext(Dispatchers.IO) {
        val pageUrl = validatePublicHttpsUrl(rawUrl)
        val parsed = parse(pageUrl)
        require(parsed.chapters.isNotEmpty()) {
            "No public audio files or streams were found. This page may require JavaScript, a login, or an unsupported player."
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
        parse(validatePublicHttpsUrl(book.id)).chapters
    }

    private data class Parsed(
        val title: String,
        val author: String = "",
        val description: String = "",
        val cover: String = "",
        val chapters: List<AudioChapter>,
    )

    private fun parse(url: String): Parsed {
        if (isAudioFile(url)) {
            val title = humanizeFileName(URI(url).path.substringAfterLast('/'))
            return Parsed(title = title, chapters = listOf(AudioChapter(url, title, url)))
        }

        // HTTP redirects are inspected one at a time, with the same public-HTTPS
        // restrictions applied to every destination.
        var currentUrl = url
        var doc: Document? = null
        for (hop in 0..MAX_REDIRECTS) {
            val response = Jsoup.connect(currentUrl)
                .userAgent("AudioShelf/0.4 (Android; public single-page importer)")
                .timeout(30_000)
                .maxBodySize(2_000_000)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            when (response.statusCode()) {
                in 200..299 -> {
                    val contentType = response.contentType().orEmpty().lowercase()
                    require(contentType.isBlank() || "html" in contentType || "xml" in contentType) {
                        "Link is not an HTML audiobook page or a supported audio file."
                    }
                    doc = response.parse()
                    break
                }
                in 300..399 -> {
                    val location = response.header("Location").orEmpty()
                    require(location.isNotBlank() && hop < MAX_REDIRECTS) { "Too many website redirects." }
                    currentUrl = validatePublicHttpsUrl(URI(currentUrl).resolve(location).toString())
                }
                else -> throw java.io.IOException("Website returned HTTP " + response.statusCode())
            }
        }
        val html = requireNotNull(doc) { "Could not read the audiobook page." }
        val host = URI(currentUrl).host.lowercase().removePrefix("www.")
        val headline = html.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank { html.selectFirst("h1.entry-title, h1.title-page, h1[itemprop=headline], h1.page-title, h1")?.text().orEmpty() }
            .ifBlank { html.title() }
            .ifBlank { "Untitled audiobook" }
        val (title, author) = splitTitle(headline.trim(), host)
        val image = html.selectFirst(
            "meta[property=og:image], meta[name=twitter:image], figure.wp-caption img, .wp-caption img, article img"
        )
        val cover = image?.let {
            safeMediaUrl(it.attr("content").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }, currentUrl)
        }.orEmpty()

        val tracks = linkedMapOf<String, String>()
        fun add(raw: String, label: String = "") {
            val resolved = safeMediaUrl(raw, currentUrl) ?: return
            if (!isAudioFile(resolved)) return
            if (tracks.size < MAX_CHAPTERS) tracks.putIfAbsent(resolved, label.trim())
        }

        // Native HTML5 players, including players outside fixed article CSS classes.
        html.select("audio[src], audio[data-src], audio source[src], source[type*=audio][src]").forEach {
            add(it.attr("src").ifBlank { it.attr("data-src") }, it.attr("title").ifBlank { it.parent()?.attr("title").orEmpty() })
        }
        // Explicit audio links and download buttons on the supplied book page.
        html.select("a[href], a[data-href], a[data-src]").forEach {
            add(it.attr("href").ifBlank { it.attr("data-href") }.ifBlank { it.attr("data-src") }, it.text())
        }
        // OpenGraph audio or straightforward player metadata.
        html.select("meta[property=og:audio], meta[property=og:audio:url], meta[name=twitter:player:stream]").forEach {
            add(it.attr("content"))
        }
        // Simple audio URLs embedded as static JSON/JavaScript string literals.
        // No JavaScript is evaluated or used to retrieve protected tokens.
        val scripts = html.select("script:not([src])").joinToString("\n") { it.data().take(50_000) }.take(200_000)
        val quotedFiles = Regex(
            """['"]((?:https://|/|//|[A-Za-z0-9_.-]+/)[^'"]{1,700}\.(?:mp3|m4a|mp4|aac|ogg|opus|flac|wav|m3u8)(?:\?[^'"]{0,250})?)['"]""",
            RegexOption.IGNORE_CASE
        )
        quotedFiles.findAll(scripts).take(MAX_CHAPTERS).forEach { add(it.groupValues[1].replace("\\/", "/")) }

        // ZAudiobooks' publicly rendered JavaScript track structure (upstream adapter).
        if (host == "zaudiobooks.com") {
            val block = Regex("""\btracks\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(scripts)?.groupValues?.get(1).orEmpty()
            val objects = Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL)
            val names = Regex("""\bname\s*:\s*['"]([^'"]+)['"]""")
            val paths = Regex("""\bchapter_link_dropbox\s*:\s*['"]([^'"]+)['"]""")
            objects.findAll(block).take(MAX_CHAPTERS).forEach { match ->
                val name = names.find(match.value)?.groupValues?.get(1).orEmpty()
                if (!name.equals("welcome", ignoreCase = true)) {
                    val path = paths.find(match.value)?.groupValues?.get(1)?.replace("\\/", "/")
                    if (!path.isNullOrBlank()) {
                        val media = safeMediaUrl(path, "https://files01.freeaudiobooks.top/audio/")
                        if (media != null && isAudioFile(media) && tracks.size < MAX_CHAPTERS) {
                            tracks.putIfAbsent(media, name)
                        }
                    }
                }
            }
        }

        return Parsed(
            title = title,
            author = author,
            description = html.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content").orEmpty(),
            cover = cover,
            chapters = tracks.entries.mapIndexed { index, entry ->
                val label = entry.value.takeUnless { it.isBlank() || it.startsWith("http") || it.length > 100 }
                    ?: "Chapter " + (index + 1).toString().padStart(3, '0')
                AudioChapter(
                    id = url + ":" + index,
                    title = label,
                    audioUrl = entry.key,
                )
            },
        )
    }

    private fun splitTitle(raw: String, host: String): Pair<String, String> {
        val knownAuthorFirst = host in setOf(
            "zaudiobooks.com", "fulllengthaudiobooks.net", "bigaudiobooks.net",
            "goldenaudiobook.com", "goldenaudiobook.net"
        )
        val cleaned = raw.replace(Regex("""\s*(?:audiobook(?:\s+free)?|audio book online)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        val parts = cleaned.split(Regex("""\s+[-–]\s+"""), limit = 2)
        if (parts.size != 2) return cleaned to ""
        return when {
            knownAuthorFirst -> parts[1] to parts[0]
            host == "hdaudiobooks.net" -> parts[0] to parts[1]
            else -> cleaned to ""
        }
    }

    private fun humanizeFileName(file: String): String {
        val decoded = URLDecoder.decode(file, StandardCharsets.UTF_8.toString())
        return decoded.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').ifBlank { "Audiobook" }
    }

    private fun isAudioFile(url: String): Boolean = runCatching {
        val path = URI(url).path.orEmpty()
        AUDIO_EXTENSIONS.any { path.endsWith("." + it, ignoreCase = true) }
    }.getOrDefault(false)

    private fun safeMediaUrl(raw: String, base: String): String? = runCatching {
        if (raw.isBlank()) return@runCatching null
        val normalized = raw.trim().replace("\\/", "/").replace(" ", "%20")
        val candidate = URI(base).resolve(normalized).toString()
        validatePublicHttpsUrl(candidate)
    }.getOrNull()

    private fun validatePublicHttpsUrl(raw: String): String {
        val uri = URI(raw.trim()).normalize()
        require(uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)) {
            "Only public HTTPS website and audio links are supported."
        }
        val host = uri.host.lowercase().trimEnd('.')
        require(host.contains('.') && !host.endsWith(".localhost") &&
            !host.endsWith(".local") && !host.endsWith(".internal") &&
            !host.endsWith(".home.arpa") && !host.endsWith(".test") &&
            !host.endsWith(".invalid") && host != "localhost") {
            "Local and private-network addresses are not allowed."
        }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all { address ->
            val bytes = address.address
            val excluded = bytes.size == 4 &&
                (bytes[0].toInt() and 255) == 100 &&
                (bytes[1].toInt() and 0xC0) == 64
            val uniqueLocalV6 = bytes.size == 16 &&
                ((bytes[0].toInt() and 0xFE) == 0xFC)
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress && !excluded && !uniqueLocalV6
        }) { "Local and private-network addresses are not allowed." }
        return uri.toASCIIString()
    }

    private companion object {
        const val MAX_CHAPTERS = 500
        const val MAX_REDIRECTS = 4
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "mp4", "aac", "ogg", "opus", "flac", "wav", "m3u8")
    }
}
