package com.mohamed.audioshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.audioshelf.data.LibraryStore
import com.mohamed.audioshelf.data.source.ProviderRepositoryStore
import com.mohamed.audioshelf.data.source.SourceProviderDefinition
import com.mohamed.audioshelf.data.source.SourceRepository
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
    val repositories: List<SourceRepository> = emptyList(),
    val repositoryBusy: Boolean = false,
    val repositoryError: String? = null,
    val importBusy: Boolean = false,
    val importError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val repositoryStore = ProviderRepositoryStore(application)
    private val _state = MutableStateFlow(
        AudioShelfState(
            library = store.books(),
            repositories = repositoryStore.repositories(),
        )
    )
    val state: StateFlow<AudioShelfState> = _state.asStateFlow()
    private var sleepJob: Job? = null
    private var browseJob: Job? = null

    init {
        syncInstalledSources()
        browse()
    }

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

    fun addRepository(url: String) {
        if (_state.value.repositoryBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(repositoryBusy = true, repositoryError = null)
            runCatching { repositoryStore.addOrRefresh(url) }
                .onSuccess {
                    syncInstalledSources()
                    _state.value = _state.value.copy(
                        repositories = repositoryStore.repositories(),
                        repositoryBusy = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        repositoryBusy = false,
                        repositoryError = it.repositoryMessage(),
                    )
                }
        }
    }

    fun importWebsiteBook(url: String) {
        if (_state.value.importBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importBusy = true, importError = null)
            runCatching { SourceRegistry.websiteImporter().importUrl(url) }
                .onSuccess { book ->
                    if (!store.contains(book)) store.toggle(book)
                    _state.value = _state.value.copy(
                        importBusy = false,
                        library = store.books(),
                    )
                    select(book)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        importBusy = false,
                        importError = it.importMessage(),
                    )
                }
        }
    }

    fun refreshRepository(repository: SourceRepository) = addRepository(repository.url)

    fun removeRepository(repository: SourceRepository) {
        repositoryStore.remove(repository)
        syncInstalledSources()
        val selected = _state.value.sourceId
        _state.value = _state.value.copy(
            repositories = repositoryStore.repositories(),
            sourceId = if (selected != "all" && !SourceRegistry.contains(selected)) "all" else selected,
            repositoryError = null,
        )
        browse()
    }

    fun toggleProvider(provider: SourceProviderDefinition) {
        repositoryStore.setInstalled(provider, !repositoryStore.isInstalled(provider))
        syncInstalledSources()
        val selected = _state.value.sourceId
        _state.value = _state.value.copy(
            repositories = repositoryStore.repositories(),
            sourceId = if (selected != "all" && !SourceRegistry.contains(selected)) "all" else selected,
            repositoryError = null,
        )
        browse()
    }

    fun isProviderInstalled(provider: SourceProviderDefinition): Boolean = repositoryStore.isInstalled(provider)

    private fun syncInstalledSources() {
        SourceRegistry.replaceInstalled(repositoryStore.installedProviders())
    }

    private suspend fun loadFromSources(query: String): List<AudioBook> = coroutineScope {
        val selected = _state.value.sourceId
        val sources = if (selected == "all") SourceRegistry.all() else listOf(SourceRegistry.get(selected))
        val attempts = sources.map { source ->
            async { runCatching { if (query.isBlank()) source.browse() else source.search(query) } }
        }.awaitAll()
        if (attempts.all { it.isFailure }) throw requireNotNull(attempts.firstNotNullOfOrNull { it.exceptionOrNull() })
        attempts.flatMap { it.getOrDefault(emptyList()) }.distinctBy { "${it.sourceId}:${it.id}" }
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
        if (book.externalUrl.isNotBlank()) {
            _state.value = _state.value.copy(selectedBook = book, chapters = emptyList(), loading = false, error = null)
            return
        }
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

    private fun Throwable.friendlyMessage(): String = when (this) {
        is IllegalArgumentException -> message ?: "Invalid audiobook page."
        is java.net.UnknownHostException -> "No internet connection. Check your network and try again."
        is java.net.SocketTimeoutException -> "The audiobook service took too long to respond. Try again shortly."
        is java.io.IOException -> "Could not reach the audiobook service. Try again shortly."
        else -> "Could not load this content. Try again."
    }

    private fun Throwable.importMessage(): String = when (this) {
        is IllegalArgumentException -> message ?: "Invalid audiobook page."
        is java.net.UnknownHostException -> "Could not find the audiobook website."
        is java.net.SocketTimeoutException -> "The audiobook page took too long to respond."
        is java.io.IOException -> "Could not read the audiobook page. Check the link and site access."
        else -> "Unable to import audiobook chapters from this page."
    }

    private fun Throwable.repositoryMessage(): String = when (this) {
        is IllegalArgumentException -> message ?: "The repository is invalid."
        is org.json.JSONException -> "The repository does not contain valid provider JSON."
        is java.net.UnknownHostException -> "Could not find the repository host."
        is java.net.SocketTimeoutException -> "The repository took too long to respond."
        is java.io.IOException -> message ?: "Could not download the repository."
        else -> "Could not add this repository."
    }
}
