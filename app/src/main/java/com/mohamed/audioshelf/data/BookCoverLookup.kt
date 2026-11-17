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
