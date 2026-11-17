package com.mohamed.audioshelf.data

import android.content.Context
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("audio_shelf", Context.MODE_PRIVATE)
    private val trustedLocalCovers = java.io.File(context.filesDir, "covers").canonicalPath +
        java.io.File.separator

    fun books(): List<AudioBook> = runCatching {
        val array = JSONArray(prefs.getString(KEY_BOOKS, "[]"))
        buildList { for (i in 0 until array.length()) add(array.getJSONObject(i).toBook()) }
    }.getOrDefault(emptyList())

    fun contains(book: AudioBook) = books().any { it.id == book.id && it.sourceId == book.sourceId }

    fun toggle(book: AudioBook): Boolean {
        val current = books().toMutableList()
        val index = current.indexOfFirst { it.id == book.id && it.sourceId == book.sourceId }
        val added = index < 0
        if (added) current.add(0, book) else current.removeAt(index)
        val array = JSONArray().apply { current.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
        return added
    }

    /** Persist matched artwork without changing the saved book's identity or progress. */
    fun updateCover(book: AudioBook, coverUrl: String) {
        val trustedOpenLibrary = coverUrl.matches(
            Regex("""https://covers\\.openlibrary\\.org/b/id/\\d+-L\\.jpg"""))
        val trustedLocal = if (coverUrl.startsWith("file://")) runCatching {
            val file = java.io.File(requireNotNull(android.net.Uri.parse(coverUrl).path))
            file.canonicalPath.startsWith(trustedLocalCovers) && file.isFile
        }.getOrDefault(false) else false
        if (!trustedOpenLibrary && !trustedLocal) return
        val items = books().toMutableList()
        val index = items.indexOfFirst { it.id == book.id && it.sourceId == book.sourceId }
        if (index < 0 || items[index].coverUrl.isNotBlank()) return
        items[index] = items[index].copy(coverUrl = coverUrl)
        val array = JSONArray().apply { items.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    fun progress(book: AudioBook): ListeningProgress {
        val key = "${book.sourceId}:${book.id}"
        val json = runCatching { JSONObject(prefs.getString(KEY_PROGRESS, "{}")) }.getOrDefault(JSONObject())
        val item = json.optJSONObject(key) ?: return ListeningProgress()
        return ListeningProgress(
            chapterIndex = item.optInt("chapter"),
            positionMs = item.optLong("position"),
            updatedAt = item.optLong("updated"),
            chapterCount = item.optInt("chapterCount"),
            chapterDurationMs = item.optLong("chapterDurationMs"),
        )
    }

    fun saveProgress(
        book: AudioBook,
        chapter: Int,
        positionMs: Long,
        chapterCount: Int,
        chapterDurationMs: Long,
    ) {
        val key = "${book.sourceId}:${book.id}"
        val json = runCatching { JSONObject(prefs.getString(KEY_PROGRESS, "{}")) }.getOrDefault(JSONObject())
        json.put(key, JSONObject()
            .put("chapter", chapter)
            .put("position", positionMs)
            .put("updated", System.currentTimeMillis())
            .put("chapterCount", chapterCount)
            .put("chapterDurationMs", chapterDurationMs))
        prefs.edit().putString(KEY_PROGRESS, json.toString()).apply()
    }

    private fun AudioBook.toJson() = JSONObject()
        .put("id", id).put("sourceId", sourceId).put("title", title).put("authors", authors)
        .put("narrators", narrators).put("description", description).put("coverUrl", coverUrl)
        .put("feedUrl", feedUrl).put("language", language).put("externalUrl", externalUrl)

    private fun JSONObject.toBook() = AudioBook(
        id = getString("id"), sourceId = getString("sourceId"), title = getString("title"),
        authors = optString("authors"), narrators = optString("narrators"),
        description = optString("description"), coverUrl = optString("coverUrl"),
        feedUrl = optString("feedUrl"), language = optString("language"), externalUrl = optString("externalUrl"),
    )

    private companion object {
        const val KEY_BOOKS = "library_books"
        const val KEY_PROGRESS = "listening_progress"
    }
}
