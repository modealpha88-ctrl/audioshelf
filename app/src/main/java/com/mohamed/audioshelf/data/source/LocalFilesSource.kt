package com.mohamed.audioshelf.data.source

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter

/**
 * Plays a file selected through Android's Storage Access Framework. The picker
 * grants a persistable read permission; no file copies or account credentials.
 */
class LocalFilesSource : AudioSource {
    override val id = "local_files"
    override val name = "My audio files"
    override val attribution = "Audio files selected from your device"

    override suspend fun browse(page: Int): List<AudioBook> = emptyList()
    override suspend fun search(query: String, page: Int): List<AudioBook> = emptyList()

    override suspend fun chapters(book: AudioBook): List<AudioChapter> {
        require(book.id.startsWith("content://")) { "Local audio must use an Android document URI." }
        return listOf(AudioChapter(
            id = book.id,
            title = book.title,
            audioUrl = book.id,
        ))
    }
}
