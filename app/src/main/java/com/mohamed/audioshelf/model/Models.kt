package com.mohamed.audioshelf.model

data class AudioBook(
    val id: String,
    val sourceId: String,
    val title: String,
    val authors: String,
    val narrators: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val feedUrl: String = "",
    val language: String = "",
    val externalUrl: String = "",
)

data class AudioChapter(
    val id: String,
    val title: String,
    val audioUrl: String,
    val durationMs: Long = 0,
)

data class ListeningProgress(
    val chapterIndex: Int = 0,
    val positionMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
