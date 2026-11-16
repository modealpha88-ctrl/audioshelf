package com.mohamed.audioshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.audioshelf.data.LibraryStore
import com.mohamed.audioshelf.data.source.SourceRegistry
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioShelfState(
    val browse: List<AudioBook> = emptyList(),
    val library: List<AudioBook> = emptyList(),
    val selectedBook: AudioBook? = null,
    val chapters: List<AudioChapter> = emptyList(),
    val query: String = "",
    val sourceId: String = "all",
    val loading: Boolean = false,
    val error: String? = null,
    val sleepEndsAt: Long? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val _state = MutableStateFlow(AudioShelfState(library = store.books()))
    val state: StateFlow<AudioShelfState> = _state.asStateFlow()
    private var sleepJob: Job? = null
    private var browseJob: Job? = null

    init { browse() }

    fun browse() = loadBooks { loadFromSources("") }

    fun search(query: String) {
        _state.value = _state.value.copy(query = query)
        loadBooks { loadFromSources(query) }
    }

    fun selectSource(sourceId: String) {
        if (sourceId == _state.value.sourceId) return
        _state.value = _state.value.copy(sourceId = sourceId)
        loadBooks { loadFromSources(_state.value.query) }
    }

    private suspend fun loadFromSources(query: String): List<AudioBook> = coroutineScope {
        val selected = _state.value.sourceId
        val sources = if (selected == "all") SourceRegistry.all() else listOf(SourceRegistry.get(selected))
        sources.map { source ->
            async { if (query.isBlank()) source.browse() else source.search(query) }
        }.awaitAll().flatten().distinctBy { "${it.sourceId}:${it.id}" }
    }

    private fun loadBooks(block: suspend () -> List<AudioBook>) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { block() }
                .onSuccess { _state.value = _state.value.copy(browse = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.friendlyMessage()) }
        }
    }

    fun select(book: AudioBook) {
        _state.value = _state.value.copy(selectedBook = book, chapters = emptyList(), loading = true, error = null)
        viewModelScope.launch {
            runCatching { SourceRegistry.get(book.sourceId).chapters(book) }
                .onSuccess { _state.value = _state.value.copy(chapters = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.friendlyMessage()) }
        }
    }

    fun closeBook() { _state.value = _state.value.copy(selectedBook = null, chapters = emptyList()) }

    fun toggleLibrary(book: AudioBook) {
        store.toggle(book)
        _state.value = _state.value.copy(library = store.books())
    }

    fun isSaved(book: AudioBook) = _state.value.library.any { it.id == book.id && it.sourceId == book.sourceId }
    fun progress(book: AudioBook) = store.progress(book)
    fun saveProgress(book: AudioBook, chapter: Int, positionMs: Long) = store.saveProgress(book, chapter, positionMs)

    fun setSleepTimer(minutes: Int, onElapsed: () -> Unit) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepEndsAt = null)
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepEndsAt = end)
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            onElapsed()
            _state.value = _state.value.copy(sleepEndsAt = null)
        }
    }

    private fun Throwable.friendlyMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Could not load this content. Check your connection and try again."
}
