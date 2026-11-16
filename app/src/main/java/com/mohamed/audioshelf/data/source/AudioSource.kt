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
    private val builtIn: Map<String, AudioSource> = listOf(
        LibriVoxSource(),
        InternetArchiveSource(),
        LocalFilesSource(),
        PublicRssSource(),
    ).associateBy { it.id }
    private val websiteImporter = ImportedWebsiteSource()
    private val rssImporter = PublicRssSource()
    @Volatile private var installed: Map<String, AudioSource> = emptyMap()

    fun replaceInstalled(providers: List<SourceProviderDefinition>) {
        installed = providers.map(::RepositoryAudioSource).associateBy { it.id }
    }

    fun all(): List<AudioSource> = builtIn.values + installed.values
    fun get(id: String): AudioSource = requireNotNull(
        if (id == websiteImporter.id) websiteImporter else builtIn[id] ?: installed[id]
    ) { "Unknown source: $id" }
    fun websiteImporter(): ImportedWebsiteSource = websiteImporter
    fun rssImporter(): PublicRssSource = rssImporter
    fun contains(id: String): Boolean = id == websiteImporter.id || id in builtIn || id in installed
}
