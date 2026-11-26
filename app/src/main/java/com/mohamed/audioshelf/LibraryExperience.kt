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

@Composable
fun AnimatedLibraryScreen(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    onBook: (AudioBook) -> Unit,
    onResumeBook: (AudioBook) -> Unit,
    onRefreshCovers: () -> Unit,
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
    MaterialTheme(colorScheme = walnutColors) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color(0xFFE1CEB7), Color(0xFFF3E5D2), Color(0xFFEBD8C1))))) {
            val columns = if (maxWidth < 320.dp) 2 else 3
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold, color = walnutColors.onSurface)
                            Box {
                                androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, "Library controls", tint = walnutColors.onSurface)
                                }
                                androidx.compose.material3.DropdownMenu(menuOpen, { menuOpen = false }) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (sortByTitle) "Sort by saved order" else "Sort by title") },
                                        onClick = { sortByTitle = !sortByTitle; menuOpen = false })
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Motion") }, trailingIcon = { Switch(motion, null) },
                                        onClick = {
                                            motion = !motion
                                            prefs.edit().putBoolean("motion_enabled", motion).apply()
                                            menuOpen = false
                                        })
                                    if (books.any { it.coverUrl.isBlank() }) {
                                        androidx.compose.material3.DropdownMenuItem(text = { Text("Find covers") },
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
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                            singleLine = true, placeholder = { Text("Search your books") },
                            leadingIcon = { Icon(Icons.Default.Search, null) })
                    }
                }
                if (filtered.isEmpty()) {
                    item { Text(if (books.isEmpty()) "Save a book from Browse to see it here"
                        else "No matching books", Modifier.padding(16.dp), color = walnutColors.onSurface) }
                } else if (search.isNotBlank()) {
                    items(filtered, key = { "${it.sourceId}:${it.id}" }) { book ->
                        Row(Modifier.fillMaxWidth().clickable { onBook(book) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            LibraryCover(book, Modifier.size(54.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(book.title, color = walnutColors.onSurface)
                                Text(book.authors, color = walnutColors.secondary)
                            }
                        }
                    }
                } else {
                    items(woodenShelfRowCount(filtered.size, columns)) { index ->
                        val start = index * columns
                        BookstoreShelfRow(
                            filtered.subList(start, minOf(start + columns, filtered.size)),
                            index, progressFor, animateVisuals, onBook,
                        )
                    }
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
