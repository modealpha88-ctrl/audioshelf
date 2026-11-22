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
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
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
import com.mohamed.audioshelf.data.torrent.TorrentDownloads
import com.mohamed.audioshelf.data.torrent.TorrentJob
import com.mohamed.audioshelf.data.source.SourceRegistry
import com.mohamed.audioshelf.data.source.SourceProviderDefinition
import com.mohamed.audioshelf.data.source.SourceRepository
import com.mohamed.audioshelf.player.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** An Android deep link or shared torrent is only a draft until the user confirms. */
private sealed interface IncomingTorrent {
    data class Link(val url: String) : IncomingTorrent
    data class Document(val uri: Uri) : IncomingTorrent
}

private fun incomingTorrent(intent: Intent?): IncomingTorrent? {
    if (intent == null) return null
    fun fromLink(text: String?): IncomingTorrent.Link? {
        val value = text?.trim().orEmpty()
        val candidate = if (value.startsWith("magnet:?", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)) value else {
            Regex("""magnet:\?[^\s<>]+|https://[^\s<>]+\.torrent(?:\?[^\s<>]+)?""",
                RegexOption.IGNORE_CASE).find(value)?.value.orEmpty()
        }
        if (candidate.length !in 1..4096) return null
        val uri = Uri.parse(candidate)
        val valid = (uri.scheme.equals("magnet", true) &&
            Regex("(?:[?&])xt=urn:(?:btih|btmh):", RegexOption.IGNORE_CASE)
                .containsMatchIn(candidate)) ||
            (uri.scheme.equals("https", true) &&
                uri.path.orEmpty().endsWith(".torrent", ignoreCase = true))
        return candidate.takeIf { valid }?.let { IncomingTorrent.Link(it) }
    }
    return when (intent.action) {
        Intent.ACTION_VIEW -> {
            val uri = intent.data ?: return null
            when {
                uri.scheme.equals("magnet", true) ||
                    uri.scheme.equals("https", true) -> fromLink(uri.toString())
                (uri.scheme == "content" || uri.scheme == "file") &&
                    intent.type.equals("application/x-bittorrent", true) ->
                    IncomingTorrent.Document(uri)
                else -> null
            }
        }
        Intent.ACTION_SEND -> {
            fromLink(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: run {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.takeIf { (it.scheme == "content" || it.scheme == "file") &&
                    intent.type.equals("application/x-bittorrent", true) }
                    ?.let { IncomingTorrent.Document(it) }
            }
        }
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    private var externalTorrent by mutableStateOf<IncomingTorrent?>(null)
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

    private val torrentFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { TorrentDownloads.enqueueDocument(this, uri) }
                .onFailure { android.widget.Toast.makeText(this, it.message ?: "Invalid torrent file", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalTorrent = incomingTorrent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AudioShelfTheme {
                AudioShelfApp(
                    viewModel,
                    onExit = { finish() },
                    onPickLocalAudio = { localAudioPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onPickTorrentFile = { torrentFilePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                    incomingTorrent = externalTorrent,
                    onIncomingTorrentHandled = {
                        externalTorrent = null
                        // Do not display a dismissed link again after Activity recreation.
                        setIntent(Intent(Intent.ACTION_MAIN))
                    },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalTorrent = incomingTorrent(intent)
    }
}

private enum class HomeTab { Browse, Library, Sources }

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun AudioShelfApp(
    viewModel: MainViewModel,
    onExit: () -> Unit,
    onPickLocalAudio: () -> Unit,
    onPickTorrentFile: () -> Unit,
    incomingTorrent: IncomingTorrent?,
    onIncomingTorrentHandled: () -> Unit,
    onPickTranscript: (AudioBook, Int) -> Unit,
    onPickVideo: () -> Unit,
    videoRevision: Int,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberMediaController()
    val torrentScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            viewModel.refreshLibrary()
        }
    }
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
        mutableStateOf(context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
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
            incomingTorrent != null -> onIncomingTorrentHandled()
            state.selectedBook != null -> closeBook()
            tabHistory.size > 1 -> tabHistory = ArrayList(tabHistory.dropLast(1))
            else -> confirmExit = true
        }
    }
    LaunchedEffect(incomingTorrent) {
        if (incomingTorrent != null) {
            closeBook()
            selectTab(HomeTab.Sources)
        }
    }
    var torrentPermission by remember(incomingTorrent) { mutableStateOf(false) }
    var torrentSubmitting by remember(incomingTorrent) { mutableStateOf(false) }
    var torrentImportError by remember(incomingTorrent) { mutableStateOf<String?>(null) }
    if (incomingTorrent != null) {
        val request = incomingTorrent
        AlertDialog(
            onDismissRequest = { if (!torrentSubmitting) onIncomingTorrentHandled() },
            title = { Text("Open torrent in AudioShelf?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Download audiobook audio into your AudioShelf library using the built-in BitTorrent engine.")
                    Text(
                        when (request) {
                            is IncomingTorrent.Link -> request.url.take(150)
                            is IncomingTorrent.Document -> "Torrent file received from another app"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = torrentPermission, enabled = !torrentSubmitting,
                            onCheckedChange = { torrentPermission = it })
                        Text("I have permission to download and share this recording.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    torrentImportError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = torrentPermission && !torrentSubmitting,
                    onClick = {
                        torrentSubmitting = true
                        torrentScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    when (request) {
                                        is IncomingTorrent.Link -> TorrentDownloads.enqueue(context, request.url)
                                        is IncomingTorrent.Document -> TorrentDownloads.enqueueDocument(context, request.uri)
                                    }
                                }
                            }
                            torrentSubmitting = false
                            result.onSuccess { onIncomingTorrentHandled() }
                                .onFailure { torrentImportError = it.message ?: "Could not import torrent." }
                        }
                    },
                ) { Text(if (torrentSubmitting) "Adding…" else "Download audiobook") }
            },
            dismissButton = {
                TextButton(onClick = onIncomingTorrentHandled, enabled = !torrentSubmitting) {
                    Text("Cancel")
                }
            },
        )
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
                Column {
                    CompactMiniPlayer(controller, libraryStyle, onOpen = {
                        val title = controller?.mediaMetadata?.albumTitle?.toString()
                        val playingBook = (state.library + state.browse)
                            .firstOrNull { it.title == title && it.externalUrl.isBlank() }
                        if (playingBook != null) {
                            autoResumeKey = null
                            showPlayerFor = "${playingBook.sourceId}:${playingBook.id}"
                            viewModel.select(playingBook)
                        }
                    })
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
            }
        },
    ) { padding ->
        val systemMotion = systemMotionEnabled()
        SharedTransitionLayout(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = tab to state,
                modifier = Modifier.fillMaxSize(),
                contentKey = { (pageTab, pageState) ->
                    pageState.selectedBook?.let { "${it.sourceId}:${it.id}" } ?: pageTab
                },
                transitionSpec = {
                    val libraryNavigation = targetState.first == HomeTab.Library &&
                        (targetState.second.selectedBook != null || initialState.second.selectedBook != null)
                    val motion = systemMotion && (!libraryNavigation ||
                        context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
                            .getBoolean("motion_enabled", true))
                    if (!motion) {
                        return@AnimatedContent androidx.compose.animation.EnterTransition.None togetherWith
                            androidx.compose.animation.ExitTransition.None
                    }
                    if (libraryNavigation && libraryStyle == LibraryVisualStyle.Walnut.name) {
                        return@AnimatedContent fadeIn(tween(220)) togetherWith fadeOut(tween(170))
                    }
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
                val transition = if (pageTab == HomeTab.Library && libraryStyle == LibraryVisualStyle.Walnut.name)
                    WalnutTransition(this@SharedTransitionLayout, this) else null
                CompositionLocalProvider(LocalWalnutTransition provides transition) {
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
                            onTorrent = {
                                runCatching {
                                    val identifier = Uri.encode(selected.id)
                                    TorrentDownloads.enqueue(
                                        context,
                                        "https://archive.org/download/$identifier/${identifier}_archive.torrent",
                                        selected.title,
                                    )
                                }.onSuccess {
                                    closeBook()
                                    selectTab(HomeTab.Sources)
                                }.onFailure {
                                    android.widget.Toast.makeText(context, it.message ?: "Could not queue torrent",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
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
                            onPickTorrentFile = onPickTorrentFile,
                            onRefresh = viewModel::refreshRepository,
                            onRemove = viewModel::removeRepository,
                            onToggleProvider = viewModel::toggleProvider,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMiniPlayer(
    controller: MediaController?,
    libraryStyle: String,
    onOpen: () -> Unit,
) {
    if (controller == null) return
    var bookTitle by remember(controller) {
        mutableStateOf(controller.mediaMetadata.albumTitle?.toString().orEmpty())
    }
    var chapterTitle by remember(controller) {
        mutableStateOf(controller.mediaMetadata.title?.toString().orEmpty())
    }
    var playing by remember(controller) { mutableStateOf(controller.isPlaying) }
    DisposableEffect(controller) {
        fun refresh() {
            bookTitle = controller.mediaMetadata.albumTitle?.toString().orEmpty()
            chapterTitle = controller.mediaMetadata.title?.toString().orEmpty()
            playing = controller.isPlaying
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { refresh() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { refresh() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { refresh() }
        }
        controller.addListener(listener)
        refresh()
        onDispose { controller.removeListener(listener) }
    }
    if (bookTitle.isBlank()) return
    val light = libraryStyle == "Paper"
    val ink = if (light) Color(0xFF3B3027) else Color(0xFFF9ECDB)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open current audiobook") {
            onOpen()
        },
        color = if (light) Color(0xFFFFF5E5) else Color(0xFF302831),
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (light) Color(0xFFE2C7AA) else Color(0xFF5A3D51)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null, tint = ink)
            }
            Column(Modifier.weight(1f)) {
                Text(bookTitle, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text(chapterTitle.ifBlank { "Tap to open player" },
                    color = ink.copy(alpha = .7f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause audiobook" else "Resume audiobook",
                    tint = ink)
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
    onPickTorrentFile: () -> Unit,
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
                TorrentPanel(onPickTorrentFile)
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
private fun TorrentPanel(onPickTorrentFile: () -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var permitted by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var jobs by remember { mutableStateOf(TorrentDownloads.jobs(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            jobs = TorrentDownloads.jobs(context)
            delay(1500)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 14.dp))
    Text("BitTorrent audiobook downloads", style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold)
    Text("Download permitted MP3/M4B audiobooks using BitTorrent. Only audio files are selected; completed books automatically appear in Library. Peer connections share your network IP.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = link, onValueChange = { link = it },
        label = { Text("Magnet link or HTTPS .torrent URL") },
        placeholder = { Text("magnet:?xt=urn:btih:…") },
        modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = permitted, onCheckedChange = { permitted = it })
        Text("I have permission to download and share this audiobook.",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = permitted && link.isNotBlank(),
            onClick = {
                runCatching { TorrentDownloads.enqueue(context, link) }
                    .onSuccess { link = ""; error = null; jobs = TorrentDownloads.jobs(context) }
                    .onFailure { error = it.message ?: "Could not queue torrent." }
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(6.dp))
            Text("Start torrent")
        }
        OutlinedButton(onClick = onPickTorrentFile, enabled = permitted) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(6.dp))
            Text(".torrent")
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
    if (jobs.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Torrent downloads", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold)
        jobs.forEach { job ->
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when (job.state) {
                        "completed" -> "Saved to Library · ${job.audioFiles} audio files"
                        "metadata" -> "Finding torrent metadata and peers…"
                        "queued" -> "Queued, waiting for connection…"
                        "downloading" -> "Downloading · ${job.progress}% · ${job.speedBytes / 1024} KiB/s"
                        "paused" -> "Paused · ${job.progress}%"
                        "failed" -> "Failed · ${job.error}"
                        else -> job.state
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = if (job.state == "failed") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth())
                    if (job.state != "completed") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.state == "paused" || job.state == "failed") {
                                TextButton(onClick = {
                                    TorrentDownloads.resume(context, job.id)
                                    jobs = TorrentDownloads.jobs(context)
                                }) { Icon(Icons.Default.PlayArrow, null); Text("Resume") }
                            } else {
                                TextButton(onClick = {
                                    TorrentDownloads.pause(context, job.id)
                                    jobs = TorrentDownloads.jobs(context)
                                }) { Icon(Icons.Default.Pause, null); Text("Pause") }
                            }
                        }
                    }
                }
            }
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
                contentScale = ContentScale.Fit,
                loading = { CoverFallback(book) },
                error = { CoverFallback(book) },
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
private fun CoverFallback(book: AudioBook? = null) =
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (book != null) {
            Text(book.title.take(2).uppercase(), fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

private fun bookDetailColors(style: String): ColorScheme = when (style) {
    "Walnut" -> darkColorScheme(
        primary = Color(0xFFF7C58B), secondary = Color(0xFFDEB58E),
        background = Color(0xFF1D1512), surface = Color(0xFF2A211E),
        onSurface = Color(0xFFF9E9D8), onBackground = Color(0xFFF9E9D8),
    )
    "Cinema" -> darkColorScheme(
        primary = Color(0xFF91BEDA), secondary = Color(0xFFBDD5E8),
        background = Color(0xFF0B1724), surface = Color(0xFF1B2B3B),
        onSurface = Color(0xFFEDF5FA), onBackground = Color(0xFFEDF5FA),
    )
    "Paper" -> lightColorScheme(
        primary = Color(0xFF845E3F), secondary = Color(0xFF9F7F65),
        background = Color(0xFFF7ECD9), surface = Color(0xFFFFF8EB),
        onSurface = Color(0xFF3B3027), onBackground = Color(0xFF3B3027),
    )
    else -> darkColorScheme(
        primary = Color(0xFF77E6E1), secondary = Color(0xFFC3A8F7),
        background = Color(0xFF081425), surface = Color(0xFF17243B),
        onSurface = Color(0xFFF7F3FF), onBackground = Color(0xFFF7F3FF),
    )
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
    val detailContext = LocalContext.current
    val detailStyle = remember(book.id) {
        detailContext.getSharedPreferences("audio_shelf_library_visuals", Context.MODE_PRIVATE)
            .getString("visual_style", "Aurora") ?: "Aurora"
    }
    val detailColors = remember(detailStyle) { bookDetailColors(detailStyle) }
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
            MaterialTheme(colorScheme = detailColors) {
            Column(Modifier.fillMaxSize().background(detailColors.background)) {
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
                                if (book.externalUrl.isBlank() && book.sourceId != "local_files") {
                                    OutlinedButton(onClick = onDownload, enabled = chapters.isNotEmpty()) {
                                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download all")
                                    }
                                } else if (book.sourceId == "local_files") {
                                    Text("Stored on this device · No download required",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (book.sourceId == "internet_archive") {
                                    OutlinedButton(onClick = onTorrent) {
                                        Icon(Icons.Default.CloudDownload, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Download torrent in AudioShelf")
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
                    if (controller != null) PlayerPanel(controller, book, chapters, sleepEndsAt, onProgress, onSleep)
                }
            }
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    controller: MediaController,
    book: AudioBook,
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
    var bookmarkMenu by remember { mutableStateOf(false) }
    val bookmarkContext = LocalContext.current
    val bookmarkStore = remember(bookmarkContext, book.id) { PlaybackBookmarkStore(bookmarkContext) }
    var savedPositions by remember(book.id) { mutableStateOf(bookmarkStore.list(book)) }
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
                    IconButton({ bookmarkMenu = true }, enabled = canSeek) {
                        Icon(Icons.Default.BookmarkBorder, "Save or revisit audiobook bookmarks")
                    }
                    DropdownMenu(bookmarkMenu, { bookmarkMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Save current position") },
                            onClick = {
                                val currentChapter = controller.currentMediaItemIndex
                                if (currentChapter in chapters.indices) {
                                    savedPositions = bookmarkStore.save(book, currentChapter,
                                        controller.currentPosition.coerceAtLeast(0L),
                                        chapters[currentChapter].title)
                                }
                                bookmarkMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                        )
                        if (savedPositions.isEmpty()) {
                            DropdownMenuItem(text = { Text("No bookmarks yet") },
                                enabled = false, onClick = {})
                        }
                        savedPositions.takeLast(12).asReversed().forEach { saved ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(saved.chapterTitle, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text(StorySeek.clock(saved.positionMs),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    if (saved.chapterIndex in 0 until controller.mediaItemCount) {
                                        controller.seekTo(saved.chapterIndex, saved.positionMs)
                                        chapterIndex = saved.chapterIndex
                                        position = saved.positionMs
                                        onProgress(saved.chapterIndex, saved.positionMs)
                                    }
                                    bookmarkMenu = false
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        savedPositions = bookmarkStore.remove(book, saved)
                                    }) {
                                        Icon(Icons.Default.Close, "Remove this bookmark")
                                    }
                                },
                            )
                        }
                    }
                }
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
    val embeddedM4b = (book.sourceId == "local_files" || book.sourceId == "torrent_files") &&
        chapters.size > 1 && chapters.all { it.audioUrl == chapters.first().audioUrl }
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
