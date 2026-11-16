package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter

interface AudioSource {
    val id: String
    val name: String
    val attribution: String
    suspend fun browse(page: Int = 1): List<AudioBook>
    suspend fun search(query: String, page: Int = 1): List<AudioBook>
    suspend fun chapters(book: AudioBook): List<AudioChapter>
}

object SourceRegistry {
    private val sources: Map<String, AudioSource> = listOf(LibriVoxSource()).associateBy { it.id }
    fun all(): List<AudioSource> = sources.values.toList()
    fun get(id: String): AudioSource = requireNotNull(sources[id]) { "Unknown source: $id" }
}
