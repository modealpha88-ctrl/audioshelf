package com.mohamed.audioshelf.data

import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Looks up public Open Library book-cover metadata, not copyrighted audio.
 * Existing publisher/provider artwork always wins. A network failure never
 * prevents library browsing or audio playback.
 */
class BookCoverLookup {
    private val requestMutex = Mutex()

    suspend fun find(book: AudioBook): String? {
        if (book.coverUrl.isNotBlank() || book.title.isBlank()) return null
        return requestMutex.withLock {
            delay(1100) // Limit requests to this public community API.
            withContext(Dispatchers.IO) {
                runCatching { lookup(book) }.getOrNull()
            }
        }
    }


    /**
     * Show real Open Library jackets for user selection. A collection title may suggest
     * its individual volumes, but those are NEVER applied automatically to the set.
     */
    suspend fun suggestions(book: AudioBook): List<CoverSuggestion> =
        searchSuggestions(book).covers

    suspend fun searchSuggestions(book: AudioBook, searchTitle: String = book.title,
        searchAuthor: String = book.authors): CoverSearchResult {
        if (searchTitle.isBlank()) return CoverSearchResult(emptyList(), false)
        return requestMutex.withLock {
            delay(1100)
            withContext(Dispatchers.IO) {
                lookupSuggestions(book.copy(title = searchTitle, authors = searchAuthor))
            }
        }
    }

    private fun lookupSuggestions(book: AudioBook): CoverSearchResult {
        val variants = coverSuggestionSearchTitles(book.title)
        val found = linkedMapOf<String, Pair<Int, CoverSuggestion>>()
        val author = canonicalCoverText(book.authors)
        val usefulAuthor = author.isNotBlank() && author !in setOf(
            "local audiobook", "local torrent audiobook", "unknown", "unknown author", "various", "anonymous")
        var failed = false
        var requested = false
        val works = linkedMapOf<String, Pair<Int, CoverSuggestion>>()
        fun add(id: Long, title: String, authors: String, year: Int?, rank: Int) {
            if (id <= 0) return
            val url = "https://covers.openlibrary.org/b/id/$id-L.jpg"
            if (rank > (found[url]?.first ?: -1))
                found[url] = rank to CoverSuggestion(title, authors, url, year)
        }
        fun request(address: String): JSONObject? {
            if (requested) Thread.sleep(1100)
            requested = true
            return try { readCoverJson(address) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; null }
        }
        for ((index, query) in variants.withIndex()) {
            if (found.size >= 12) break
            val encoded = URLEncoder.encode(query.take(150), "UTF-8")
            val authorParam = if (usefulAuthor && index == 0)
                "&author=" + URLEncoder.encode(book.authors.take(100), "UTF-8") else ""
            val base = "https://openlibrary.org/search.json?title=$encoded"
            val fields = "&fields=key,title,author_name,cover_i,first_publish_year&limit=30"
            val initial = request(base + authorParam + fields)
            val docs = initial?.optJSONArray("docs")
                ?: (if (authorParam.isNotEmpty()) request(base + fields)?.optJSONArray("docs") else null)
                ?: continue
            for (i in 0 until minOf(docs.length(), 30)) {
                val doc = docs.optJSONObject(i) ?: continue
                val title = doc.optString("title").trim()
                val candidates = doc.optJSONArray("author_name")?.let { names ->
                    (0 until names.length()).map { names.optString(it) }
                } ?: emptyList()
                val score = scoreCoverCandidate(book.title, book.authors, title, candidates)
                val prefix = canonicalCoverText(query)
                val actual = canonicalCoverText(title)
                val related = prefix.length >= 6 &&
                    (actual == prefix || actual.startsWith("$prefix ") || actual.endsWith(" $prefix"))
                val authorMatches = !usefulAuthor || candidates.any {
                    canonicalCoverText(it) == author ||
                        author.split(' ').lastOrNull()?.let { last -> last.length >= 3 &&
                            last in canonicalCoverText(it).split(' ') } == true
                }
                if (score == 0 && (!related || !authorMatches)) continue
                val rank = score + if (related) 20 else 0
                val year = doc.optInt("first_publish_year", 0).takeIf { it in 1400..2100 }
                val names = candidates.joinToString(", ")
                add(doc.optLong("cover_i", 0), title, names, year, rank)
                val key = doc.optString("key")
                if (key.matches(Regex("/works/OL[0-9]+W"))) {
                    works[key] = rank to CoverSuggestion(title, names, "", year)
                }
            }
        }
        // Search results give one representative cover per work. Fetch a bounded
        // number of matching editions for actual cover choices, including alternate jackets.
        for ((key, pair) in works.entries.sortedByDescending { it.value.first }.take(2)) {
            if (found.size >= 12) break
            val (rank, work) = pair
            val editions = request("https://openlibrary.org$key/editions.json?limit=30")
                ?.optJSONArray("entries") ?: continue
            for (i in 0 until editions.length()) {
                val edition = editions.optJSONObject(i) ?: continue
                val title = edition.optString("title").ifBlank { work.title }
                val ids = edition.optJSONArray("covers") ?: continue
                for (j in 0 until ids.length()) {
                    add(ids.optLong(j, 0), title, work.authors, work.firstPublished, rank - 5)
                }
            }
        }
        return CoverSearchResult(found.values.sortedByDescending { it.first }.map { it.second }.take(12), failed)
    }

    private fun readCoverJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent",
                "AudioShelf/0.6 (https://github.com/modealpha88-ctrl/audioshelf)")
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("Open Library HTTP " + connection.responseCode)
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun lookup(book: AudioBook): String? {
        // Search canonical book titles first rather than edition marketing labels
        // such as "The Final Empire: Mistborn, Book 1 (Unabridged)".
        val searches = coverSearchTitles(book.title).take(2)
        for ((index, title) in searches.withIndex()) {
            if (index > 0) Thread.sleep(1100)
            val encoded = URLEncoder.encode(title.take(150), "UTF-8")
            val address = "https://openlibrary.org/search.json?title=$encoded&fields=title,author_name,cover_i&limit=12"
            val response = try { readCoverJson(address) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { continue }
            val docs = response.optJSONArray("docs") ?: continue
                var bestScore = 0
                var coverId: Long? = null
                for (i in 0 until minOf(docs.length(), 12)) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val id = doc.optLong("cover_i", 0L)
                    if (id <= 0L) continue
                    val authors = doc.optJSONArray("author_name")
                    val candidateAuthors = if (authors == null) emptyList() else
                        (0 until authors.length()).map { authors.optString(it) }
                    val score = scoreCoverCandidate(
                        book.title, book.authors, doc.optString("title"), candidateAuthors
                    )
                    if (score > bestScore) {
                        bestScore = score
                        coverId = id
                    }
                }
                if (bestScore >= 90 && coverId != null) {
                    return "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
                }
        }
        return null
    }
}


/** A real catalogued book jacket. This is an edition choice, not an inferred audiobook cover. */
data class CoverSearchResult(val covers: List<CoverSuggestion>, val requestFailed: Boolean)

data class CoverSuggestion(
    val title: String,
    val authors: String,
    val coverUrl: String,
    val firstPublished: Int? = null,
)

/** Remove collection advertising only for interactive suggestions; never auto-apply a volume cover. */
internal fun coverSuggestionSearchTitles(rawTitle: String): List<String> {
    val precise = coverSearchTitles(rawTitle)
    val collection = rawTitle.replace(
        Regex("""(?i)\s*(?:the\s+)?(?:complete|full|entire)?\s*(?:audiobook|audio\s*book|book)?\s*(?:collection|series|box\s*set|bundle)\s*$"""),
        "",
    ).trim()
    return (precise + collection).map { it.trim() }
        .filter { it.length >= 4 }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(3)
}

/** Title variations are used only for bibliographic matching, never for audio access. */
internal fun coverSearchTitles(rawTitle: String): List<String> {
    val withoutEdition = rawTitle
        .replace(Regex("""\s*\((unabridged|abridged|audiobook|audio edition|dramatized)\)""",
            RegexOption.IGNORE_CASE), "")
        .replace('_', ' ')
        .trim()
    // A ":" often separates the published title from the series description.
    val primary = withoutEdition.substringBefore(':').trim()
        .replace(Regex("""\s*,?\s*(book|volume|vol\.?)\s*#?\d+\s*$""",
            RegexOption.IGNORE_CASE), "").trim()
    val secondary = withoutEdition.substringAfter(':', "").trim()
        .replace(Regex("""\s*,?\s*(book|volume|vol\.?)\s*#?\d+.*$""",
            RegexOption.IGNORE_CASE), "").trim()

    return listOf(primary, secondary, withoutEdition)
        .map { it.trim() }
        .filter { canonicalCoverText(it).length >= 4 }
        .distinctBy { canonicalCoverText(it) }
}

/**
 * Match by the core title AND author. A series name alone is not an acceptable
 * cover match when the author differs.
 */
internal fun scoreCoverCandidate(
    bookTitle: String,
    bookAuthor: String,
    candidateTitle: String,
    candidateAuthors: List<String>,
): Int {
    val variants = coverSearchTitles(bookTitle).map(::canonicalCoverText)
    val actual = canonicalCoverText(candidateTitle)
    if (variants.isEmpty() || actual.isBlank()) return 0

    val primary = variants.first()
    val exact = actual == primary
    val otherExact = actual in variants
    val sameCoreTitle = variants.any { variant ->
        minOf(variant.length, actual.length) >= 11 &&
            (variant.startsWith("$actual ") || actual.startsWith("$variant "))
    }
    if (!otherExact && !sameCoreTitle) return 0

    val author = canonicalCoverText(bookAuthor)
    val authorIsUseful = author.isNotBlank() &&
        author !in setOf("local audiobook", "unknown", "unknown author",
            "various", "anonymous")
    if (!authorIsUseful) return if (exact) 100 else 0

    val authorTokens = author.split(' ').filter { it.length >= 3 }
    val matched = candidateAuthors.any { candidate ->
        val normalized = canonicalCoverText(candidate)
        normalized == author || (authorTokens.size >= 2 &&
            authorTokens.last() in normalized.split(' '))
    }
    if (!matched) return 0
    return when {
        exact -> 120
        otherExact -> 110
        else -> 90
    }
}

private fun canonicalCoverText(text: String): String =
    text.lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
