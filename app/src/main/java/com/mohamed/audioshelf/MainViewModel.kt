package com.mohamed.audioshelf

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohamed.audioshelf.data.LibraryStore
import com.mohamed.audioshelf.data.captions.TimedCaptionStore
import com.mohamed.audioshelf.data.captions.OfflineSpeechModel
import com.mohamed.audioshelf.data.captions.OnDeviceTranscriber
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
    val transcriptRevision: Int = 0,
    val transcriptError: String? = null,
    val sttModelReady: Boolean = false,
    val sttBusy: Boolean = false,
    val sttMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val repositoryStore = ProviderRepositoryStore(application)
    private val _state = MutableStateFlow(
        AudioShelfState(
            library = store.books(),
            repositories = repositoryStore.repositories(),
            sttModelReady = OfflineSpeechModel(application).isInstalled(),
        )
    )
    val state: StateFlow<AudioShelfState> = _state.asStateFlow()
    private var sleepJob: Job? = null
    private var browseJob: Job? = null

    init {
        SourceRegistry.bindLocalFiles(application)
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

    fun importRssFeed(rawUrl: String) {
        if (_state.value.importBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importBusy = true, importError = null)
            runCatching { SourceRegistry.rssImporter().importFeed(rawUrl) }
                .onSuccess { book ->
                    if (!store.contains(book)) store.toggle(book)
                    _state.value = _state.value.copy(importBusy = false, library = store.books())
                    select(book)
                }
                .onFailure {
                    _state.value = _state.value.copy(importBusy = false, importError = it.importMessage())
                }
        }
    }

    fun importLocalAudio(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            require(uri.scheme == "content") { "Select an audio file using the Android file picker." }
            val displayName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }.orEmpty()
            val type = app.contentResolver.getType(uri).orEmpty().lowercase()
            val extension = displayName.substringAfterLast('.', "").lowercase()
            require(extension in setOf("mp3", "m4a", "m4b", "mp4", "aac", "ogg", "opus", "flac", "wav") ||
                type.startsWith("audio/")) { "Select a supported MP3, M4B, M4A or other audio file." }
            val name = displayName.substringBeforeLast('.', displayName).ifBlank { "Imported audiobook" }
            val retriever = MediaMetadataRetriever()
            val metadata = try {
                retriever.setDataSource(app, uri)
                Pair(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                )
            } catch (_: Exception) {
                Pair("", "")
            } finally {
                retriever.release()
            }
            val book = AudioBook(
                id = uri.toString(),
                sourceId = "local_files",
                title = metadata.first.ifBlank { name },
                authors = metadata.second.ifBlank { "Local audiobook" },
                description = "DRM-free audio file selected from your device. AudioShelf retains permission to play it locally.",
            )
            if (!store.contains(book)) store.toggle(book)
            _state.value = _state.value.copy(library = store.books(), importError = null)
            select(book)
        }.onFailure {
            _state.value = _state.value.copy(importError = it.importMessage())
        }
    }


    /** Explicit, opt-in download; audio is processed offline. */
    fun downloadSttModel() {
        if (_state.value.sttModelReady || _state.value.sttBusy) return
        _state.value = _state.value.copy(sttBusy = true,
            sttMessage = "Downloading free offline English model (~40 MB)…")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { OfflineSpeechModel(getApplication()).ensureInstalled() }
                .onSuccess {
                    _state.value = _state.value.copy(sttBusy = false, sttModelReady = true,
                        sttMessage = "Offline speech recognition is ready.")
                }
                .onFailure {
                    _state.value = _state.value.copy(sttBusy = false,
                        sttMessage = it.message ?: "Could not download the speech model.")
                }
        }
    }

    /** Caches 30 seconds of word-aligned captions for local audio. */
    fun transcribeNearby(book: AudioBook, chapterIndex: Int, absolutePositionMs: Long) {
        if (book.sourceId != "local_files" || !_state.value.sttModelReady || _state.value.sttBusy) return
        val startMs = (absolutePositionMs.coerceAtLeast(0) / 30_000L) * 30_000L
        val app = getApplication<Application>()
        val captions = TimedCaptionStore(app)
        if (captions.isRecognizedWindow(book, chapterIndex, startMs)) return
        _state.value = _state.value.copy(sttBusy = true,
            sttMessage = "Recognizing 30 seconds of speech offline…")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val words = OnDeviceTranscriber(app).transcribe(Uri.parse(book.id), startMs, 30_000L) { partial ->
                    captions.saveRecognizedWords(book, chapterIndex, partial)
                    _state.value = _state.value.copy(
                        transcriptRevision = _state.value.transcriptRevision + 1)
                }
                captions.saveRecognizedWords(book, chapterIndex, words)
                captions.markRecognizedWindow(book, chapterIndex, startMs)
                words.size
            }.onSuccess { count ->
                _state.value = _state.value.copy(sttBusy = false,
                    transcriptRevision = _state.value.transcriptRevision + 1,
                    sttMessage = if (count > 0) "Generated " + count + " timed words."
                        else "No speech detected in this section.")
            }.onFailure {
                _state.value = _state.value.copy(sttBusy = false,
                    sttMessage = it.message ?: "Speech recognition failed.")
            }
        }
    }

    fun importTimedCaptions(book: AudioBook, chapterIndex: Int, uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { TimedCaptionStore(getApplication()).import(book, chapterIndex, uri) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        transcriptRevision = _state.value.transcriptRevision + 1,
                        transcriptError = null,
                    )
                }.onFailure {
                    _state.value = _state.value.copy(
                        transcriptError = it.message ?: "Unable to import captions.",
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
                .onSuccess { chapters ->
                    // Pre-chapter versions stored the absolute file position as chapter 0.
                    // Convert that position to the corresponding clipped chapter once.
                    if (book.sourceId == "local_files" && chapters.size > 1) {
                        val old = store.progress(book)
                        if (old.chapterIndex == 0 &&
                            old.positionMs >= chapters.first().durationMs &&
                            chapters.first().durationMs > 0) {
                            val target = chapters.indexOfLast { it.startMs <= old.positionMs }
                            if (target > 0) {
                                store.saveProgress(book, target, old.positionMs - chapters[target].startMs)
                            }
                        }
                    }
                    if (_state.value.selectedBook?.id == book.id &&
                        _state.value.selectedBook?.sourceId == book.sourceId) {
                        _state.value = _state.value.copy(chapters = chapters, loading = false)
                    }
                }
                .onFailure { if (_state.value.selectedBook?.id == book.id) {
                    _state.value = _state.value.copy(loading = false, error = it.friendlyMessage())
                } }
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
