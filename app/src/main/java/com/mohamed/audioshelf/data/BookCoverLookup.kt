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
 * Resolves missing cover art using Open Library's public metadata API.
 *
 * A valid provider cover is always preferred. Never guess the artwork from a
 * remote URL: only verified numeric cover IDs returned by Open Library are used.
 * Search errors must not interrupt playback, the library, or offline browsing.
 */
class BookCoverLookup {
    private val requestMutex = Mutex()

    suspend fun find(book: AudioBook): String? {
        if (book.coverUrl.isNotBlank() || book.title.isBlank()) return null
        return requestMutex.withLock {
            // Respect the shared public API rather than bulk-spamming parallel searches.
            delay(750)
            withContext(Dispatchers.IO) {
                runCatching { lookup(book) }.getOrNull()
            }
        }
    }

    private fun lookup(book: AudioBook): String? {
        val title = book.title
            .substringBefore(" (")
            .replace('_', ' ')
            .trim()
            .take(150)
        if (title.isBlank()) return null
        val encoded = URLEncoder.encode(title, "UTF-8")
        val address = "https://openlibrary.org/search.json?title=$encoded&fields=title,author_name,cover_i&limit=10"
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AudioShelf/0.5 (https://github.com/modealpha88-ctrl/audioshelf)")
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val docs = JSONObject(response).optJSONArray("docs") ?: return null
            var bestScore = 0
            var coverId: Long? = null
            for (i in 0 until minOf(docs.length(), 10)) {
                val doc = docs.optJSONObject(i) ?: continue
                val id = doc.optLong("cover_i", 0)
                if (id <= 0) continue
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
            coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Exact title matching is required when there is no trustworthy author.
 * Prefix/subtitle matching is only permitted when the author also matches.
 * This prevents unrelated books receiving misleading cover art.
 */
internal fun scoreCoverCandidate(
    bookTitle: String,
    bookAuthor: String,
    candidateTitle: String,
    candidateAuthors: List<String>,
): Int {
    val requested = canonicalCoverText(bookTitle.substringBefore(" ("))
    val actual = canonicalCoverText(candidateTitle)
    if (requested.isBlank() || actual.isBlank()) return 0
    val exact = requested == actual
    val sameCoreTitle = minOf(requested.length, actual.length) >= 11 &&
        (requested.startsWith("$actual ") || actual.startsWith("$requested "))
    if (!exact && !sameCoreTitle) return 0

    val author = canonicalCoverText(bookAuthor)
    val authorIsUseful = author.isNotBlank() &&
        author !in setOf("local audiobook", "unknown", "unknown author", "various", "anonymous")
    if (!authorIsUseful) return if (exact) 100 else 0

    val authorTokens = author.split(' ').filter { it.length >= 3 }
    val matched = candidateAuthors.any { candidate ->
        val normalized = canonicalCoverText(candidate)
        normalized == author || (authorTokens.size >= 2 &&
            authorTokens.last() in normalized.split(' '))
    }
    return if (matched) (if (exact) 120 else 90) else 0
}

private fun canonicalCoverText(text: String): String =
    text.lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
