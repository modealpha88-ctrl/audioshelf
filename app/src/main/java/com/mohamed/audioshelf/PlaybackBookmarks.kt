package com.mohamed.audioshelf

import android.content.Context
import com.mohamed.audioshelf.model.AudioBook
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded chapter-position bookmarks; independent of the playback progress store. */
internal data class SavedPosition(
    val chapterIndex: Int,
    val positionMs: Long,
    val chapterTitle: String,
)

internal class PlaybackBookmarkStore(context: Context) {
    private val preferences = context.getSharedPreferences("audio_shelf_bookmarks", Context.MODE_PRIVATE)

    private fun key(book: AudioBook): String = "${book.sourceId}:${book.id}"

    fun list(book: AudioBook): List<SavedPosition> {
        val value = preferences.getString(key(book), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until minOf(array.length(), 30)) {
                    val entry = array.optJSONObject(index) ?: continue
                    val chapter = entry.optInt("chapter", -1)
                    val position = entry.optLong("position", -1)
                    if (chapter >= 0 && position >= 0L) {
                        add(SavedPosition(chapter, position,
                            entry.optString("title").take(100)))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Repeated taps near the same position update rather than duplicate the bookmark. */
    fun save(book: AudioBook, chapterIndex: Int, positionMs: Long, chapterTitle: String): List<SavedPosition> {
        if (chapterIndex < 0 || positionMs < 0L) return list(book)
        val entries = list(book).filterNot {
            it.chapterIndex == chapterIndex && kotlin.math.abs(it.positionMs - positionMs) < 15_000L
        }.takeLast(29) + SavedPosition(chapterIndex, positionMs, chapterTitle.take(100))
        persist(book, entries)
        return entries
    }

    fun remove(book: AudioBook, entry: SavedPosition): List<SavedPosition> {
        val entries = list(book).filterNot { it == entry }
        persist(book, entries)
        return entries
    }

    private fun persist(book: AudioBook, entries: List<SavedPosition>) {
        val array = JSONArray()
        entries.takeLast(30).forEach { position ->
            array.put(JSONObject().apply {
                put("chapter", position.chapterIndex)
                put("position", position.positionMs)
                put("title", position.chapterTitle)
            })
        }
        preferences.edit().putString(key(book), array.toString()).apply()
    }
}
