@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohamed.audioshelf

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import com.mohamed.audioshelf.model.ListeningProgress
import com.mohamed.audioshelf.data.OfflineDownloads
import com.mohamed.audioshelf.data.source.SourceRegistry
import com.mohamed.audioshelf.data.source.SourceProviderDefinition
import com.mohamed.audioshelf.data.source.SourceRepository
import com.mohamed.audioshelf.player.PlaybackService
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var captionTarget: Pair<AudioBook, Int>? = null
    private var videoRevision by mutableIntStateOf(0)
    private val captionPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) captionTarget?.let { (book, index) ->
            viewModel.importTimedCaptions(book, index, uri)
        }
        captionTarget = null
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            getSharedPreferences("audio_shelf_story", Context.MODE_PRIVATE)
                .edit().putString("video_uri", it.toString()).apply()
            videoRevision++
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val localAudioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importLocalAudio(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AudioShelfTheme {
                AudioShelfApp(
                    viewModel,
                    onExit = { finish() },
                    onPickLocalAudio = { localAudioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onPickTranscript = { book, index ->
                        captionTarget = book to index
                        captionPicker.launch(arrayOf("*/*"))
                    },
                    onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                    videoRevision = videoRevision,
                )
            }
        }
    }
}

private enum class HomeTab { Browse, Library, Sources }

@Composable
private fun AudioShelfApp(
    viewModel: MainViewModel,
    onExit: () -> Unit,
    onPickLocalAudio: () -> Unit,
    onPickTranscript: (AudioBook, Int) -> Unit,
    onPickVideo: () -> Unit,
    videoRevision: Int,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberMediaController()
    var tabHistory by rememberSaveable { mutableStateOf(arrayListOf(HomeTab.Browse)) }
    val tab = tabHistory.last()
    fun selectTab(next: HomeTab) {
        if (tab == next) return
        val existing = tabHistory.indexOf(next)
        tabHistory = if (existing >= 0) ArrayList(tabHistory.take(existing + 1))
            else ArrayList(tabHistory + next)
    }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var libraryStyle by remember {
        mutableStateOf(context.getSharedPreferences("audio_shelf_library_visuals", Context.MODE_PRIVATE)
            .getString("visual_style", "Aurora") ?: "Aurora")
    }
    var autoResumeKey by remember { mutableStateOf<String?>(null) }
    var showPlayerFor by remember { mutableStateOf<String?>(null) }
    fun closeBook() {
        autoResumeKey = null
        showPlayerFor = null
        viewModel.closeBook()
    }
    BackHandler {
        when {
            state.selectedBook != null -> closeBook()
            tabHistory.size > 1 -> tabHistory = ArrayList(tabHistory.dropLast(1))
            else -> confirmExit = true
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave AudioShelf?") },
            confirmButton = { TextButton(onClick = onExit) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
        )
    }
    val paperLibrary = tab == HomeTab.Library && state.selectedBook == null &&
        libraryStyle == "Paper"
    val loungeLibrary = tab == HomeTab.Library && state.selectedBook == null && libraryStyle == "Walnut"
    val conceptLibrary = tab == HomeTab.Library && state.selectedBook == null &&
        (libraryStyle == "Aurora" || libraryStyle == "Cinema")
    val navInk = if (paperLibrary) Color(0xFF4B3225) else
        if (loungeLibrary) Color(0xFFF3E2CC) else if (conceptLibrary) Color(0xFFF1ECFD) else MaterialTheme.colorScheme.onSurfaceVariant
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = navInk, selectedTextColor = navInk,
        unselectedIconColor = navInk.copy(alpha = .8f),
        unselectedTextColor = navInk.copy(alpha = .8f),
        indicatorColor = if (paperLibrary) Color(0xFFE0CAD2) else
            if (loungeLibrary) Color(0xFF67472D) else if (conceptLibrary) Color(0xFF51416C)
            else MaterialTheme.colorScheme.secondaryContainer,
    )
    LaunchedEffect(autoResumeKey, state.selectedBook?.id,
        state.selectedBook?.sourceId, state.chapters, controller) {
        val book = state.selectedBook ?: return@LaunchedEffect
        val key = "${book.sourceId}:${book.id}"
        if (autoResumeKey != key || state.chapters.isEmpty() ||
            controller == null || book.externalUrl.isNotBlank()) return@LaunchedEffect
        val progress = viewModel.progress(book)
        val index = progress.chapterIndex.coerceIn(state.chapters.indices)
        autoResumeKey = null
        playBook(context, controller, book, state.chapters, index, progress.positionMs)
    }

    Scaffold(
        bottomBar = {
            if (state.selectedBook == null) {
                NavigationBar(
                    containerColor = when {
                        paperLibrary -> Color(0xFFF5E7D4)
                        loungeLibrary -> Color(0xFF201815)
                        conceptLibrary -> Color(0xFF0E192B)
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    },
                ) {
                    NavigationBarItem(
                        colors = navColors,
                        selected = tab == HomeTab.Browse,
                        onClick = { selectTab(HomeTab.Browse) },
                        icon = { Icon(Icons.Default.Explore, null) },
                        label = { Text("Browse") },
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.Library,
                        colors = navColors,
                        onClick = { selectTab(HomeTab.Library) },
                        icon = { Icon(Icons.Default.LibraryBooks, null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.Sources,
                        colors = navColors,
                        onClick = { selectTab(HomeTab.Sources) },
                        icon = { Icon(Icons.Default.Extension, null) },
                        label = { Text("Sources") },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab to state,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentKey = { (pageTab, pageState) -> pageState.selectedBook?.id ?: pageTab },
            transitionSpec = {
                val direction = when {
                    targetState.second.selectedBook != null -> 1
                    initialState.second.selectedBook != null -> -1
                    targetState.first.ordinal > initialState.first.ordinal -> 1
                    else -> -1
                }
                (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 10 * direction })
                    .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -it / 10 * direction })
            },
            label = "AudioShelf pages",
        ) { (pageTab, pageState) ->
            val selected = pageState.selectedBook
            if (selected != null) {
                BookScreen(
                    book = selected,
                    listeningProgress = viewModel.progress(selected),
                    openPlayerInitially = showPlayerFor ==
                        "${selected.sourceId}:${selected.id}",
                    chapters = pageState.chapters,
                    loading = pageState.loading,
                    error = pageState.error,
                    saved = viewModel.isSaved(selected),
                    controller = controller,
                    sleepEndsAt = pageState.sleepEndsAt,
                    transcriptRevision = pageState.transcriptRevision,
                    transcriptError = pageState.transcriptError,
                    sttBusy = pageState.sttBusy,
                    sttModelReady = pageState.sttModelReady,
                    sttMessage = pageState.sttMessage,
                    sttNativeUnavailable = pageState.sttNativeUnavailable,
                    onDownloadSttModel = viewModel::downloadSttModel,
                    onTranscribeNearby = { index, ms -> viewModel.transcribeNearby(selected, index, ms) },
                    videoRevision = videoRevision,
                    onPickTranscript = { index -> onPickTranscript(selected, index) },
                    onPickVideo = onPickVideo,
                    onBack = ::closeBook,
                    onToggleSaved = { viewModel.toggleLibrary(selected) },
                    onOpenExternal = {
                        if (selected.externalUrl.startsWith("https://")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.externalUrl))) }
                        }
                    },
                    onPlay = { index ->
                        val progress = viewModel.progress(selected)
                        playBook(context, controller, selected, pageState.chapters, index, if (index == progress.chapterIndex) progress.positionMs else 0)
                    },
                    onResume = {
                        val progress = viewModel.progress(selected)
                        val index = progress.chapterIndex.coerceIn(pageState.chapters.indices)
                        playBook(context, controller, selected, pageState.chapters, index, progress.positionMs)
                    },
                    onDownload = { OfflineDownloads(context).downloadBook(selected, pageState.chapters) },
                    onTorrent = { openArchiveTorrent(context, selected) },
                    onProgress = { index, position -> viewModel.saveProgress(selected, pageState.chapters, index, position) },
                    onSleep = { minutes -> viewModel.setSleepTimer(minutes) { controller?.pause() } },
                )
            } else when (pageTab) {
                HomeTab.Browse -> BrowseScreen(pageState, viewModel::search, viewModel::selectSource, viewModel::browse, viewModel::select)
                HomeTab.Library -> AnimatedLibraryScreen(
                    books = pageState.library,
                    progressFor = viewModel::progress,
                    onBook = { book ->
                        autoResumeKey = null
                        showPlayerFor = null
                        viewModel.select(book)
                    },
                    onResumeBook = { book ->
                        if (book.externalUrl.isBlank()) {
                            val key = "${book.sourceId}:${book.id}"
                            autoResumeKey = key
                            showPlayerFor = key
                        }
                        viewModel.select(book)
                    },
                    onRefreshCovers = viewModel::refreshMissingCovers,
                    onStyleSelected = { libraryStyle = it },
                )
                HomeTab.Sources -> SourcesScreen(
                    state = pageState,
                    isInstalled = viewModel::isProviderInstalled,
                    onAdd = viewModel::addRepository,
                    onImport = viewModel::importWebsiteBook,
                    onImportFeed = viewModel::importRssFeed,
                    onPickLocalAudio = onPickLocalAudio,
                    onRefresh = viewModel::refreshRepository,
                    onRemove = viewModel::removeRepository,
                    onToggleProvider = viewModel::toggleProvider,
                )
            }
        }
    }
}

@Composable
private fun BrowseScreen(state: AudioShelfState, onSearch: (String) -> Unit, onSource: (String) -> Unit, onRetry: () -> Unit, onBook: (AudioBook) -> Unit) {
    var query by rememberSaveable { mutableStateOf(state.query) }

    LaunchedEffect(query) {
        delay(400)
        if (query != state.query) onSearch(query)
    }

    Column(Modifier.fillMaxSize()) {
        LargeTopAppBar(title = { Column { Text("AudioShelf"); Text("Browse and import audiobooks", style = MaterialTheme.typography.labelMedium) } })
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        query = it
                        onSearch(it)
                    },
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text("Search audiobook catalogues") },
                    leadingIcon = {
                        IconButton(onClick = { onSearch(query) }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; onSearch("") }) { Icon(Icons.Default.Close, null) } },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {}
        SourceSelector(state.sourceId, onSource)
        ContentList(state.browse, state.loading, state.error, query, onRetry, onBook)
    }
}

@Composable
private fun SourceSelector(selectedId: String, onSource: (String) -> Unit) {
    val options = listOf("all" to "All sources") + SourceRegistry.all().map { it.id to it.name }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(options.first { it.first == selectedId }.second, fontWeight = FontWeight.Bold)
                    Text("Open audio and official audiobook services", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.first }) { (id, label) ->
                    FilterChip(selected = selectedId == id, onClick = { onSource(id) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun SourcesScreen(
    state: AudioShelfState,
    isInstalled: (SourceProviderDefinition) -> Boolean,
    onAdd: (String) -> Unit,
    onImport: (String) -> Unit,
    onImportFeed: (String) -> Unit,
    onPickLocalAudio: () -> Unit,
    onRefresh: (SourceRepository) -> Unit,
    onRemove: (SourceRepository) -> Unit,
    onToggleProvider: (SourceProviderDefinition) -> Unit,
) {
    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    var bookUrl by rememberSaveable { mutableStateOf("") }
    var podcastUrl by rememberSaveable { mutableStateOf("") }
    var hasRights by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val canOpenWebsite = runCatching {
        val uri = Uri.parse(bookUrl.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Source repositories")
                        Text("Add and install audiobook providers", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    label = { Text("Repository URL") },
                    placeholder = { Text("https://example.org/audioshelf-repo.json") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onAdd(repositoryUrl.trim()) },
                    enabled = repositoryUrl.isNotBlank() && !state.repositoryBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.repositoryBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.AddLink, null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.repositoryBusy) "Loading repository…" else "Add repository")
                }
                state.repositoryError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only add repositories you trust. AudioShelf accepts HTTPS JSON provider definitions; it does not install executable APK extensions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 18.dp))
                Text("Import audiobook by URL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Paste an individual audiobook page or direct audio URL, not a website homepage. Only sites allowing third-party access are supported.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bookUrl,
                    onValueChange = { bookUrl = it },
                    label = { Text("Audiobook page URL") },
                    placeholder = { Text("https://example.org/audiobook-or-chapter.mp3") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasRights, onCheckedChange = { hasRights = it })
                    Text("I have permission to access and play this recording.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onImport(bookUrl.trim()) },
                    enabled = bookUrl.isNotBlank() && hasRights && !state.importBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.importBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.LibraryAdd, null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.importBusy) "Importing…" else "Import into library")
                }
                TextButton(
                    onClick = {
                        if (canOpenWebsite) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bookUrl.trim())))
                            }
                        }
                    },
                    enabled = canOpenWebsite,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open website in browser")
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text("Import free podcast audiobook", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Paste the public HTTPS RSS feed for a free Podiobooks or other openly distributed audiobook podcast. Only publisher-provided audio enclosures are used.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = podcastUrl,
                    onValueChange = { podcastUrl = it },
                    label = { Text("Public audiobook RSS URL") },
                    placeholder = { Text("https://example.org/audiobook/feed.xml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onImportFeed(podcastUrl.trim()) },
                    enabled = podcastUrl.startsWith("https://") && !state.importBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Podcasts, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.importBusy) "Importing…" else "Import RSS audiobook")
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text("Import DRM-free audio from device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Choose a purchased Libro.fm MP3 or M4B file (or another audio file you are allowed to play). Files stay on your device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPickLocalAudio, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose MP3 or M4B file")
                }
                state.importError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.repositories.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxHeight(.55f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.ExtensionOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("No repositories added", style = MaterialTheme.typography.titleMedium)
                        Text("Paste a compatible repository URL above", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        items(state.repositories, key = SourceRepository::url) { repository ->
            RepositoryCard(repository, isInstalled, onRefresh, onRemove, onToggleProvider)
        }
    }
}

@Composable
private fun RepositoryCard(
    repository: SourceRepository,
    isInstalled: (SourceProviderDefinition) -> Boolean,
    onRefresh: (SourceRepository) -> Unit,
    onRemove: (SourceRepository) -> Unit,
    onToggleProvider: (SourceProviderDefinition) -> Unit,
) {
    ElevatedCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(repository.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (repository.description.isNotBlank()) Text(repository.description, style = MaterialTheme.typography.bodySmall)
                    Text(repository.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ onRefresh(repository) }) { Icon(Icons.Default.Refresh, "Refresh repository") }
                IconButton({ onRemove(repository) }) { Icon(Icons.Default.DeleteOutline, "Remove repository") }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            repository.providers.forEachIndexed { index, provider ->
                val installed = isInstalled(provider)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(44.dp),
                    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Headphones, null) } }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            provider.description.ifBlank { provider.attribution.ifBlank { "Audiobook source provider" } },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (installed) {
                        OutlinedButton({ onToggleProvider(provider) }) { Text("Remove") }
                    } else {
                        Button({ onToggleProvider(provider) }) { Text("Install") }
                    }
                }
                if (index != repository.providers.lastIndex) HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun ContentList(books: List<AudioBook>, loading: Boolean, error: String?, query: String, onRetry: () -> Unit, onBook: (AudioBook) -> Unit) {
    when {
        loading && books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null && books.isEmpty() -> ErrorState(error, onRetry)
        !loading && query.isNotBlank() && books.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("No audiobooks found", style = MaterialTheme.typography.titleMedium)
                Text("Try another title or author", style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books.size, key = { "${books[it].sourceId}:${books[it].id}" }) { BookRow(books[it], onBook) }
        }
    }
}

@Composable
private fun LibraryScreen(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    onBook: (AudioBook) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LargeTopAppBar(title = { Text("Your library") })
        if (books.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Headphones, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp)); Text("No saved audiobooks yet", style = MaterialTheme.typography.titleMedium)
                Text("Add books from Browse to keep them here", style = MaterialTheme.typography.bodyMedium)
            }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books, key = { "${it.sourceId}:${it.id}" }) { book ->
                val progress = progressFor(book)
                if (book.externalUrl.isBlank() && (progress.chapterIndex > 0 || progress.positionMs > 0)) {
                    ListeningCard(book, progress) { onBook(book) }
                } else {
                    BookRow(book, onBook)
                }
            }
        }
    }
}

@Composable
private fun ListeningCard(book: AudioBook, progress: ListeningProgress, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val count = progress.chapterCount
    val chapter = progress.chapterIndex.coerceAtLeast(0)
    val duration = progress.chapterDurationMs
    val chapterFraction = if (duration > 0L) {
        progress.positionMs.coerceIn(0L, duration).toFloat() / duration
    } else 0f

    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(colors.secondaryContainer, colors.surfaceContainer)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (book.authors.isNotBlank()) {
                        Text(book.authors, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(16.dp))
                SubcomposeAsyncImage(
                    model = book.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = { CoverFallback() },
                    error = { CoverFallback() },
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant),
                )
            }
            if (chapter in 0 until count && (duration > 0L || chapter > 0)) {
                val progressFraction = (chapter + chapterFraction) / count
                val transition = remember { Animatable(0f) }
                LaunchedEffect(progressFraction) { transition.animateTo(progressFraction, tween(350)) }
                val segmentCount = count.coerceAtMost(12)
                Row(
                    Modifier.fillMaxWidth().clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(segmentCount) { segment ->
                        val fill = (transition.value * segmentCount - segment).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(colors.onSurface.copy(alpha = .15f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(colors.primary))
                        }
                    }
                }
            }
            val chapterLabel = if (chapter in 0 until count) "Chapter ${chapter + 1} of $count"
                else "Chapter ${chapter + 1}"
            Text("$chapterLabel · ${formatTime(progress.positionMs.coerceAtLeast(0L))}",
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BookRow(book: AudioBook, onBook: (AudioBook) -> Unit) {
    ElevatedCard(onClick = { onBook(book) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SubcomposeAsyncImage(
                model = book.coverUrl,
                contentDescription = "${book.title} cover",
                contentScale = ContentScale.Crop,
                loading = { CoverFallback() },
                error = { CoverFallback() },
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.authors, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (book.language.isNotBlank()) Text(book.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (book.externalUrl.isNotBlank()) Text("Official website · External access", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun CoverFallback() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BookScreen(
    book: AudioBook,
    listeningProgress: ListeningProgress,
    openPlayerInitially: Boolean,
    chapters: List<AudioChapter>,
    loading: Boolean,
    error: String?,
    saved: Boolean,
    controller: MediaController?,
    sleepEndsAt: Long?,
    transcriptRevision: Int,
    transcriptError: String?,
    sttBusy: Boolean,
    sttModelReady: Boolean,
    sttMessage: String?,
    sttNativeUnavailable: Boolean,
    onDownloadSttModel: () -> Unit,
    onTranscribeNearby: (Int, Long) -> Unit,
    videoRevision: Int,
    onPickTranscript: (Int) -> Unit,
    onPickVideo: () -> Unit,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onOpenExternal: () -> Unit,
    onPlay: (Int) -> Unit,
    onResume: () -> Unit,
    onDownload: () -> Unit,
    onTorrent: () -> Unit,
    onProgress: (Int, Long) -> Unit,
    onSleep: (Int) -> Unit,
) {
    var showPlayer by remember(book.id) { mutableStateOf(openPlayerInitially) }
    var storyOpen by remember(book.id) { mutableStateOf(false) }
    val navigateBack: () -> Unit = {
        when {
            storyOpen -> storyOpen = false
            showPlayer -> showPlayer = false
            else -> onBack()
        }
    }
    BackHandler(enabled = storyOpen || showPlayer, onBack = navigateBack)
    val chapterListState = rememberLazyListState()
    AnimatedContent(
        targetState = storyOpen && controller != null,
        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
        label = "Story Mode page",
    ) { showStory ->
        if (showStory && controller != null) {
            StoryMode(
                book = book,
                chapters = chapters,
                controller = controller,
                transcriptRevision = transcriptRevision,
                transcriptError = transcriptError,
                sttBusy = sttBusy,
                sttModelReady = sttModelReady,
                sttMessage = sttMessage,
                sttNativeUnavailable = sttNativeUnavailable,
                onDownloadSttModel = onDownloadSttModel,
                onTranscribeNearby = onTranscribeNearby,
                videoRevision = videoRevision,
                onClose = { storyOpen = false },
                onPickTranscript = onPickTranscript,
                onPickVideo = onPickVideo,
                onProgress = onProgress,
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(navigateBack) { Icon(Icons.Default.ArrowBack, null) } },
                    actions = { IconButton(onToggleSaved) { Icon(if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null) } },
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = chapterListState,
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item {
                        LibraryBookDetailCard(book, listeningProgress)
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                if (book.externalUrl.isNotBlank()) {
                                    Button(onClick = onOpenExternal) {
                                        Icon(Icons.Default.OpenInBrowser, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open website")
                                    }
                                    Text(
                                        "This opens a third-party website. AudioShelf does not verify ownership or provide its audio. Check licensing, availability, and any purchase or library terms before listening.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Button(onClick = { if (chapters.isNotEmpty()) { onResume(); showPlayer = true } }, enabled = chapters.isNotEmpty()) {
                                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { onResume(); storyOpen = true },
                                        enabled = chapters.isNotEmpty() && controller != null,
                                    ) {
                                        Icon(Icons.Default.ClosedCaption, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Story mode")
                                    }
                                }
                                if (book.externalUrl.isBlank()) {
                                    OutlinedButton(onClick = onDownload, enabled = chapters.isNotEmpty()) {
                                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download all")
                                    }
                                }
                                if (book.sourceId == "internet_archive") {
                                    OutlinedButton(onClick = onTorrent) {
                                        Icon(Icons.Default.CloudDownload, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open torrent")
                                    }
                                    Text("Open/public-domain item", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (book.description.isNotBlank()) Text(book.description, Modifier.padding(horizontal = 20.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (book.externalUrl.isBlank()) Text(if (book.sourceId == "local_files" && chapters.size > 1) "Embedded chapters" else "Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                    }
                    if (loading) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
                    itemsIndexed(chapters, key = { _, item -> item.id }) { index, chapter ->
                        ListItem(
                            headlineContent = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (chapter.durationMs > 0) Text(formatTime(chapter.durationMs)) },
                            leadingContent = { IconButton({ onPlay(index); showPlayer = true }) { Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary) } },
                            modifier = Modifier.clickable { onPlay(index); showPlayer = true },
                        )
                        HorizontalDivider()
                    }
                }
                AnimatedVisibility(
                    visible = showPlayer && controller != null,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 4 },
                ) {
                    if (controller != null) PlayerPanel(controller, chapters, sleepEndsAt, onProgress, onSleep)
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    controller: MediaController,
    chapters: List<AudioChapter>,
    sleepEndsAt: Long?,
    onProgress: (Int, Long) -> Unit,
    onSleep: (Int) -> Unit,
) {
    var playing by remember(controller) { mutableStateOf(controller.isPlaying) }
    var position by remember(controller) { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(controller) { mutableLongStateOf(controller.duration.coerceAtLeast(0L)) }
    var chapterIndex by remember(controller) { mutableIntStateOf(controller.currentMediaItemIndex.coerceAtLeast(0)) }
    var speed by remember(controller) { mutableFloatStateOf(controller.playbackParameters.speed) }
    var sleepMenu by remember { mutableStateOf(false) }
    var scrubPosition by remember(controller) { mutableStateOf<Long?>(null) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                chapterIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
                scrubPosition = null
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    LaunchedEffect(controller, playing, chapterIndex) {
        while (true) {
            if (scrubPosition == null) position = controller.currentPosition.coerceAtLeast(0L)
            duration = controller.duration.takeIf { it > 0L }
                ?: chapters.getOrNull(controller.currentMediaItemIndex)?.durationMs?.coerceAtLeast(0L)
                ?: 0L
            if (controller.mediaItemCount > 0) onProgress(controller.currentMediaItemIndex, controller.currentPosition.coerceAtLeast(0L))
            delay(1000)
        }
    }

    fun seekBy(seconds: Int) {
        if (chapters.isEmpty() || controller.mediaItemCount == 0) return
        val index = controller.currentMediaItemIndex.coerceIn(0, minOf(chapters.lastIndex, controller.mediaItemCount - 1))
        val durations = chapters.mapIndexed { i, chapter ->
            if (i == index && controller.duration > 0L) controller.duration else chapter.durationMs
        }
        val target = StorySeek.byOffset(
            durationsMs = durations,
            currentIndex = index,
            currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
            offsetMs = seconds * 1000L,
        )
        val nextIndex = target.chapterIndex.coerceIn(0, controller.mediaItemCount - 1)
        controller.seekTo(nextIndex, target.positionMs)
        chapterIndex = nextIndex
        position = target.positionMs
        scrubPosition = null
        onProgress(nextIndex, target.positionMs)
    }

    val canSeek = chapters.isNotEmpty() && controller.mediaItemCount > 0
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                chapters.getOrNull(chapterIndex)?.title ?: "Now playing",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = if (duration > 0L) (scrubPosition ?: position).toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { scrubPosition = it.roundToLong().coerceIn(0L, duration) },
                onValueChangeFinished = {
                    scrubPosition?.let { target ->
                        if (canSeek) {
                            val index = controller.currentMediaItemIndex.coerceIn(0, controller.mediaItemCount - 1)
                            controller.seekTo(index, target)
                            chapterIndex = index
                            position = target
                            onProgress(index, target)
                        }
                    }
                    scrubPosition = null
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                enabled = canSeek && duration > 0L,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(StorySeek.clock(scrubPosition ?: position), style = MaterialTheme.typography.labelSmall)
                Text(if (duration > 0L) StorySeek.clock(duration) else "--:--", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PlayerSeekButton(-30, canSeek) { seekBy(-30) }
                PlayerSeekButton(-10, canSeek) { seekBy(-10) }
                FilledIconButton(
                    onClick = { if (playing) controller.pause() else controller.play() },
                    enabled = canSeek,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play")
                }
                PlayerSeekButton(10, canSeek) { seekBy(10) }
                PlayerSeekButton(30, canSeek) { seekBy(30) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ speed = if (speed >= 2f) .5f else speed + .25f; controller.setPlaybackSpeed(speed) }) { Text("${speed}x") }
                IconButton({ controller.seekToPreviousMediaItem() }, enabled = canSeek) { Icon(Icons.Default.SkipPrevious, "Previous chapter") }
                IconButton({ controller.seekToNextMediaItem() }, enabled = canSeek) { Icon(Icons.Default.SkipNext, "Next chapter") }
                Box {
                    IconButton({ sleepMenu = true }) { Icon(Icons.Default.Bedtime, "Sleep timer", tint = if (sleepEndsAt != null) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(15, 30, 45, 60).forEach { minutes -> DropdownMenuItem({ Text("$minutes minutes") }, { onSleep(minutes); sleepMenu = false }) }
                        DropdownMenuItem({ Text("Cancel timer") }, { onSleep(0); sleepMenu = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekButton(seconds: Int, enabled: Boolean, onClick: () -> Unit) {
    val description = if (seconds > 0) "Skip forward $seconds seconds" else "Skip backward ${-seconds} seconds"
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Text(if (seconds > 0) "+${seconds}s" else "${seconds}s", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp)); Text(message); Spacer(Modifier.height(16.dp)); Button(retry) { Text("Try again") }
    }
}

@Composable
private fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it } }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(future); controller = null }
    }
    return controller
}

private fun playBook(context: Context, controller: MediaController?, book: AudioBook, chapters: List<AudioChapter>, index: Int, positionMs: Long) {
    if (controller == null || chapters.isEmpty()) return
    val downloads = OfflineDownloads(context)
    val embeddedM4b = book.sourceId == "local_files" && chapters.size > 1
    val mediaItems = chapters.mapIndexed { chapterIndex, chapter ->
        val item = MediaItem.Builder()
            .setMediaId(chapter.id)
            .setUri(downloads.playableUri(book, chapterIndex, chapter.audioUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(chapter.title).setArtist(book.authors).setAlbumTitle(book.title).build())
        if (embeddedM4b) {
            // Clip each logical chapter out of the SAME local M4B URI: no copies are made.
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(chapter.startMs)
            if (chapter.durationMs > 0) clip.setEndPositionMs(chapter.startMs + chapter.durationMs)
            item.setClippingConfiguration(clip.build())
        }
        item.build()
    }
    controller.setMediaItems(mediaItems, index.coerceIn(chapters.indices), positionMs.coerceAtLeast(0))
    controller.prepare()
    controller.play()
}

private fun openArchiveTorrent(context: Context, book: AudioBook) {
    if (book.sourceId != "internet_archive") return
    val identifier = Uri.encode(book.id)
    val torrent = Uri.parse("https://archive.org/download/$identifier/${identifier}_archive.torrent")
    val intent = Intent(Intent.ACTION_VIEW, torrent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun AudioShelfTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFFD0BCFF), secondary = Color(0xFFCCC2DC)) else lightColorScheme(primary = Color(0xFF6750A4), secondary = Color(0xFF625B71)),
        content = content,
    )
}
