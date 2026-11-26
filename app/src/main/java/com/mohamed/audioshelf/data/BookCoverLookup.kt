package com.mohamed.audioshelf.data

import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.Dispatchers
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
            delay(750) // Limit requests to this public community API.
            withContext(Dispatchers.IO) {
                runCatching { lookup(book) }.getOrNull()
            }
        }
    }


    /**
     * Show real Open Library jackets for user selection. A collection title may suggest
     * its individual volumes, but those are NEVER applied automatically to the set.
     */
    suspend fun suggestions(book: AudioBook): List<CoverSuggestion> {
        if (book.title.isBlank()) return emptyList()
        return requestMutex.withLock {
            delay(750)
            withContext(Dispatchers.IO) {
                runCatching { lookupSuggestions(book) }.getOrDefault(emptyList())
            }
        }
    }

    private fun lookupSuggestions(book: AudioBook): List<CoverSuggestion> {
        val variants = coverSuggestionSearchTitles(book.title)
        val found = linkedMapOf<String, Pair<Int, CoverSuggestion>>()
        val author = canonicalCoverText(book.authors)
        val usefulAuthor = author.isNotBlank() && author !in setOf(
            "local audiobook", "local torrent audiobook", "unknown", "unknown author",
            "various", "anonymous",
        )
        for ((qIndex, query) in variants.withIndex()) {
            if (qIndex > 0) Thread.sleep(750)
            val encoded = URLEncoder.encode(query.take(150), "UTF-8")
            val address = "https://openlibrary.org/search.json?title=$encoded&fields=title,author_name,cover_i,first_publish_year&limit=20"
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6500
                connection.readTimeout = 6500
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent",
                    "AudioShelf/0.5 (https://github.com/modealpha88-ctrl/audioshelf)")
                if (connection.responseCode !in 200..299) continue
                val docs = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optJSONArray("docs") ?: continue
                for (i in 0 until minOf(docs.length(), 20)) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val id = doc.optLong("cover_i", 0L)
                    if (id <= 0L) continue
                    val title = doc.optString("title").trim()
                    val authorsJson = doc.optJSONArray("author_name")
                    val candidates = if (authorsJson == null) emptyList() else
                        (0 until authorsJson.length()).map { authorsJson.optString(it) }
                    val score = scoreCoverCandidate(book.title, book.authors, title, candidates)
                    // A deliberately broad collection search is allowed only in the picker.
                    // Keep the published title visible so users can identify the right volume.
                    val prefix = canonicalCoverText(query)
                    val titleText = canonicalCoverText(title)
                    val seriesRelated = prefix.length >= 6 &&
                        (titleText == prefix || titleText.startsWith("$prefix "))
                    if (score == 0 && !seriesRelated) continue
                    if (usefulAuthor && score == 0) {
                        val words = author.split(' ').filter { it.length >= 3 }
                        if (candidates.none { candidate ->
                            val normalized = canonicalCoverText(candidate)
                            normalized == author ||
                                (words.isNotEmpty() && words.last() in normalized.split(' '))
                        }) continue
                    }
                    val url = "https://covers.openlibrary.org/b/id/$id-L.jpg"
                    val year = doc.optInt("first_publish_year", 0).takeIf { it in 1400..2100 }
                    val suggestion = CoverSuggestion(title, candidates.joinToString(", "), url, year)
                    val rank = score + if (seriesRelated) 20 else 0
                    if (rank > (found[url]?.first ?: -1)) found[url] = rank to suggestion
                }
            } finally {
                connection.disconnect()
            }
        }
        return found.values.sortedByDescending { it.first }.map { it.second }.take(12)
    }

    private fun lookup(book: AudioBook): String? {
        // Search canonical book titles first rather than edition marketing labels
        // such as "The Final Empire: Mistborn, Book 1 (Unabridged)".
        val searches = coverSearchTitles(book.title).take(2)
        for ((index, title) in searches.withIndex()) {
            if (index > 0) Thread.sleep(750)
            val encoded = URLEncoder.encode(title.take(150), "UTF-8")
            val address = "https://openlibrary.org/search.json?title=$encoded&fields=title,author_name,cover_i&limit=12"
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6500
                connection.readTimeout = 6500
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent",
                    "AudioShelf/0.5 (https://github.com/modealpha88-ctrl/audioshelf)")
                if (connection.responseCode !in 200..299) continue
                val docs = JSONObject(
                    connection.inputStream.bufferedReader().use { it.readText() }
                ).optJSONArray("docs") ?: continue
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
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}


/** A real catalogued book jacket. This is an edition choice, not an inferred audiobook cover. */
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
