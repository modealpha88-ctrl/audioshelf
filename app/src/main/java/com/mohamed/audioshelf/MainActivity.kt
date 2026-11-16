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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.mohamed.audioshelf.data.OfflineDownloads
import com.mohamed.audioshelf.data.source.SourceRegistry
import com.mohamed.audioshelf.player.PlaybackService
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AudioShelfTheme { AudioShelfApp(viewModel) } }
    }
}

private enum class HomeTab { Browse, Library }

@Composable
private fun AudioShelfApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberMediaController()
    var tab by remember { mutableStateOf(HomeTab.Browse) }

    Scaffold(
        bottomBar = {
            if (state.selectedBook == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == HomeTab.Browse,
                        onClick = { tab = HomeTab.Browse },
                        icon = { Icon(Icons.Default.Explore, null) },
                        label = { Text("Browse") },
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.Library,
                        onClick = { tab = HomeTab.Library },
                        icon = { Icon(Icons.Default.LibraryBooks, null) },
                        label = { Text("Library") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val selected = state.selectedBook
            if (selected != null) {
                BookScreen(
                    book = selected,
                    chapters = state.chapters,
                    loading = state.loading,
                    error = state.error,
                    saved = viewModel.isSaved(selected),
                    controller = controller,
                    sleepEndsAt = state.sleepEndsAt,
                    onBack = viewModel::closeBook,
                    onToggleSaved = { viewModel.toggleLibrary(selected) },
                    onPlay = { index ->
                        val progress = viewModel.progress(selected)
                        playBook(context, controller, selected, state.chapters, index, if (index == progress.chapterIndex) progress.positionMs else 0)
                    },
                    onDownload = { OfflineDownloads(context).downloadBook(selected, state.chapters) },
                    onTorrent = { openArchiveTorrent(context, selected) },
                    onProgress = { index, position -> viewModel.saveProgress(selected, index, position) },
                    onSleep = { minutes -> viewModel.setSleepTimer(minutes) { controller?.pause() } },
                )
            } else when (tab) {
                HomeTab.Browse -> BrowseScreen(state, viewModel::search, viewModel::selectSource, viewModel::browse, viewModel::select)
                HomeTab.Library -> LibraryScreen(state.library, viewModel::select)
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
        LargeTopAppBar(title = { Column { Text("AudioShelf"); Text("Public-domain audiobooks", style = MaterialTheme.typography.labelMedium) } })
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
                    placeholder = { Text("Search LibriVox titles") },
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
    val options = remember { listOf("all" to "All sources") + SourceRegistry.all().map { it.id to it.name } }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(options.first { it.first == selectedId }.second, fontWeight = FontWeight.Bold)
                    Text("Authorized open and public-domain catalogues", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (id, label) ->
                    FilterChip(selected = selectedId == id, onClick = { onSource(id) }, label = { Text(label) })
                }
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
private fun LibraryScreen(books: List<AudioBook>, onBook: (AudioBook) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LargeTopAppBar(title = { Text("Your library") })
        if (books.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Headphones, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp)); Text("No saved audiobooks yet", style = MaterialTheme.typography.titleMedium)
                Text("Add books from Browse to keep them here", style = MaterialTheme.typography.bodyMedium)
            }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books.size, key = { "${books[it].sourceId}:${books[it].id}" }) { BookRow(books[it], onBook) }
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
    chapters: List<AudioChapter>,
    loading: Boolean,
    error: String?,
    saved: Boolean,
    controller: MediaController?,
    sleepEndsAt: Long?,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onPlay: (Int) -> Unit,
    onDownload: () -> Unit,
    onTorrent: () -> Unit,
    onProgress: (Int, Long) -> Unit,
    onSleep: (Int) -> Unit,
) {
    var showPlayer by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = { IconButton(onToggleSaved) { Icon(if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null) } },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Row(Modifier.padding(20.dp)) {
                    AsyncImage(book.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.width(120.dp).aspectRatio(.7f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp)); Text(book.authors)
                        if (book.language.isNotBlank()) Text(book.language, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { if (chapters.isNotEmpty()) { onPlay(0); showPlayer = true } }, enabled = chapters.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                        }
                        OutlinedButton(onClick = onDownload, enabled = chapters.isNotEmpty()) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download all")
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
                Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
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
        if (showPlayer && controller != null) PlayerPanel(controller, chapters, sleepEndsAt, onProgress, onSleep)
    }
}

@Composable
private fun PlayerPanel(controller: MediaController, chapters: List<AudioChapter>, sleepEndsAt: Long?, onProgress: (Int, Long) -> Unit, onSleep: (Int) -> Unit) {
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var position by remember { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableLongStateOf(controller.duration.coerceAtLeast(0)) }
    var chapterIndex by remember { mutableIntStateOf(controller.currentMediaItemIndex.coerceAtLeast(0)) }
    var speed by remember { mutableFloatStateOf(controller.playbackParameters.speed) }
    var sleepMenu by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { chapterIndex = controller.currentMediaItemIndex.coerceAtLeast(0) }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    LaunchedEffect(controller, playing, chapterIndex) {
        while (true) {
            position = controller.currentPosition.coerceAtLeast(0)
            duration = controller.duration.coerceAtLeast(0)
            if (controller.mediaItemCount > 0) onProgress(controller.currentMediaItemIndex, position)
            delay(1000)
        }
    }

    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(chapters.getOrNull(chapterIndex)?.title ?: "Now playing", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Slider(
                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { position = it.roundToLong() },
                onValueChangeFinished = { controller.seekTo(position) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ speed = if (speed >= 2f) .5f else speed + .25f; controller.setPlaybackSpeed(speed) }) { Text("${speed}x") }
                IconButton({ controller.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null) }
                FilledIconButton({ if (playing) controller.pause() else controller.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                IconButton({ controller.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null) }
                Box {
                    IconButton({ sleepMenu = true }) { Icon(Icons.Default.Bedtime, null, tint = if (sleepEndsAt != null) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
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
    val mediaItems = chapters.mapIndexed { chapterIndex, chapter ->
        MediaItem.Builder()
            .setMediaId(chapter.id)
            .setUri(downloads.playableUri(book, chapterIndex, chapter.audioUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(chapter.title).setArtist(book.authors).setAlbumTitle(book.title).build())
            .build()
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
