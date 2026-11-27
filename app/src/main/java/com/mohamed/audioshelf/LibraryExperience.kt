package com.mohamed.audioshelf

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

internal const val PREF_LIBRARY_VISUALS = "audio_shelf_library_visuals"

@Composable
internal fun systemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal class WalnutTransition(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

internal val LocalWalnutTransition = staticCompositionLocalOf<WalnutTransition?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun walnutSharedSleeve(book: AudioBook, enabled: Boolean): Modifier {
    val scopes = LocalWalnutTransition.current
    if (!enabled || scopes == null) return Modifier
    return with(scopes.shared) {
        Modifier.sharedElement(
            rememberSharedContentState("walnut-sleeve:${book.sourceId}:${book.id}"),
            animatedVisibilityScope = scopes.visibility,
            boundsTransform = { _, _ -> tween(410, easing = coverSettle) },
        )
    }
}

/** The original filmography brief's warm paper, near-black ink and cobalt accent. */
internal val vhsPaperColors = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF1F3FB0), secondary = Color(0xFF6D6A63),
    background = Color(0xFFEDEBE6), surface = Color(0xFFF4F2ED),
    surfaceVariant = Color(0xFFE4E0D8),
    onBackground = Color(0xFF141414), onSurface = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF57534D),
    outline = Color(0xFF6D6A63),
)

@Composable
fun AnimatedLibraryScreen(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    onBook: (AudioBook) -> Unit,
    onResumeBook: (AudioBook) -> Unit,
    onRefreshCovers: () -> Unit,
    spineRevision: Int = 0,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
    }
    var motion by rememberSaveable { mutableStateOf(prefs.getBoolean("motion_enabled", true)) }
    val animateVisuals = systemMotionEnabled() && motion
    var search by rememberSaveable { mutableStateOf("") }
    var sortByTitle by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val filtered = remember(search, books, sortByTitle) {
        val matched = books.filter {
            it.title.contains(search, ignoreCase = true) ||
                it.authors.contains(search, ignoreCase = true)
        }
        if (sortByTitle) matched.sortedBy { it.title.lowercase() } else matched
    }
    val resumeBook = books.firstOrNull { book ->
        val progress = progressFor(book)
        book.externalUrl.isBlank() && (progress.chapterIndex > 0 || progress.positionMs > 0L)
    }
    MaterialTheme(colorScheme = vhsPaperColors) {
        Box(Modifier.fillMaxSize().background(vhsPaperColors.background)) {
            // Static paper-fibre grain: not a downloaded or hardcoded book cover.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                repeat(340) { i ->
                    val x = ((i * 757 + 17) % 997) / 997f * size.width
                    val y = ((i * 499 + 79) % 991) / 991f * size.height
                    drawCircle(
                        Color(0xFF685B4C).copy(alpha = .08f),
                        radius = if (i % 3 == 0) .9f else .45f,
                        center = androidx.compose.ui.geometry.Offset(x, y),
                    )
                }
            }
            Column(Modifier.fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 17.dp, end = 8.dp, top = 15.dp,
                        bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AUDIO / SHELF", color = Color(0xFF1F3FB0),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("LIBRARY", color = Color(0xFF141414),
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Library settings",
                                tint = Color(0xFF141414))
                        }
                        androidx.compose.material3.DropdownMenu(menuOpen, { menuOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (sortByTitle) "Saved order" else "Sort by title") },
                                onClick = { sortByTitle = !sortByTitle; menuOpen = false })
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Motion") },
                                trailingIcon = { Switch(motion, null) },
                                onClick = {
                                    motion = !motion
                                    prefs.edit().putBoolean("motion_enabled", motion).apply()
                                    menuOpen = false
                                })
                            if (books.any { it.coverUrl.isBlank() }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Find missing covers") },
                                    onClick = { menuOpen = false; onRefreshCovers() })
                            }
                            if (resumeBook != null) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Resume " + resumeBook.title, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis) },
                                    onClick = { menuOpen = false; onResumeBook(resumeBook) })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp),
                    singleLine = true, placeholder = { Text("Search your audiobooks") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(2.dp),
                )
                Spacer(Modifier.height(14.dp))
                VhsAudiobookShelf(
                    books = filtered,
                    progressFor = progressFor,
                    motion = animateVisuals,
                    spineRevision = spineRevision,
                    onOpen = onBook,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("© AUDIOSHELF", color = Color(0xFF6D6A63),
                        fontSize = 9.sp, letterSpacing = .9.sp)
                    Text("SAVED / \${books.size}", color = Color(0xFF6D6A63),
                        fontSize = 9.sp, letterSpacing = .9.sp)
                }
            }
        }
    }
}

@Composable
internal fun LibraryCover(book: AudioBook, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        ShelfTextCover(book, Modifier.fillMaxSize())
    }
    if (book.coverUrl.isBlank()) {
        Box(modifier.clip(RoundedCornerShape(12.dp))) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = book.coverUrl,
            contentDescription = "${book.title} cover",
            contentScale = ContentScale.Fit,
            loading = { fallback() },
            error = { fallback() },
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    }
}

internal fun conceptProgress(progress: ListeningProgress): Float {
    val total = progress.chapterCount
    if (total <= 0) return 0f
    val chapter = progress.chapterIndex.coerceIn(0, total)
    val within = if (progress.chapterDurationMs > 0)
        progress.positionMs.coerceIn(0L, progress.chapterDurationMs).toFloat() /
            progress.chapterDurationMs else 0f
    return ((chapter + within) / total).coerceIn(0f, 1f)
}

/** A larger book-detail card opens when a library item is tapped. */
@Composable
fun LibraryBookDetailCard(book: AudioBook, progress: ListeningProgress) {
    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), color = walnutColors.surface,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Concept C uses a physical book jacket in details too: no vinyl behind square art.
            Box(Modifier.width(116.dp).height(145.dp)
                .shadow(5.dp, RoundedCornerShape(3.dp))
                .background(Color(0xFFE6D5B7))
                .padding(start = 5.dp, end = 3.dp, top = 3.dp, bottom = 3.dp)) {
                LibraryCover(book, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(17.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("AUDIOBOOK", color = walnutColors.onSurface.copy(alpha = .75f),
                    style = MaterialTheme.typography.labelSmall)
                Text(book.title, style = MaterialTheme.typography.titleLarge,
                    color = walnutColors.onSurface, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.authors, style = MaterialTheme.typography.bodyMedium,
                    color = walnutColors.onSurface.copy(alpha = .87f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (progress.chapterCount > 0) {
                    LinearProgressIndicator(
                        progress = { conceptProgress(progress) },
                        modifier = Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Text("${(conceptProgress(progress) * 100).toInt()}% listened",
                        color = walnutColors.onSurface, style = MaterialTheme.typography.labelMedium)
                } else if (book.language.isNotBlank()) {
                    Text(book.language, style = MaterialTheme.typography.labelMedium,
                        color = walnutColors.onSurface)
                }
            }
        }
    }
}
