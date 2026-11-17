package com.mohamed.audioshelf

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

private enum class LibraryVisualStyle(val title: String, val tagline: String) {
    Walnut("Walnut Shelf", "Warm wood & amber"),
    Aurora("Aurora Gallery", "Glowing cover gallery"),
    Cinema("Cinema Stack", "Cinematic listening cards"),
    Paper("Paper Library", "Editorial & calm"),
}

private fun palette(style: LibraryVisualStyle): List<Color> = when (style) {
    LibraryVisualStyle.Walnut -> listOf(Color(0xFF4C3023), Color(0xFF1A1211))
    LibraryVisualStyle.Aurora -> listOf(Color(0xFF214C57), Color(0xFF231A46))
    LibraryVisualStyle.Cinema -> listOf(Color(0xFF333642), Color(0xFF11131B))
    LibraryVisualStyle.Paper -> listOf(Color(0xFFF1E7D6), Color(0xFFDECFB7))
}

private fun foreground(style: LibraryVisualStyle): Color =
    if (style == LibraryVisualStyle.Paper) Color(0xFF302A28) else Color.White

private const val PREF_LIBRARY_VISUALS = "audio_shelf_library_visuals"

@Composable
private fun rememberedVisuals(): Triple<LibraryVisualStyle, Boolean, (LibraryVisualStyle) -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
    }
    var current by rememberSaveable {
        mutableStateOf(prefs.getString("visual_style", LibraryVisualStyle.Aurora.name)
            ?: LibraryVisualStyle.Aurora.name)
    }
    val style = LibraryVisualStyle.entries.firstOrNull { it.name == current }
        ?: LibraryVisualStyle.Aurora
    return Triple(style, prefs.getBoolean("motion_enabled", true)) {
        current = it.name
        prefs.edit().putString("visual_style", it.name).apply()
    }
}

@Composable
fun AnimatedLibraryScreen(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    onBook: (AudioBook) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
    }
    val (style, _, onStyle) = rememberedVisuals()
    var motion by rememberSaveable { mutableStateOf(prefs.getBoolean("motion_enabled", true)) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(search, books) {
        books.filter {
            it.title.contains(search, ignoreCase = true) ||
                it.authors.contains(search, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                Text("Your library", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("${books.size} saved audiobooks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            AnimatedLibraryBackdrop(style, motion, Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp).height(136.dp)) {
                Column(Modifier.padding(19.dp)) {
                    Text("A story worth returning to", style = MaterialTheme.typography.titleLarge,
                        color = foreground(style), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(style.tagline, style = MaterialTheme.typography.bodyMedium,
                        color = foreground(style).copy(alpha = 0.82f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Library appearance", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                Text("Motion", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = motion, onCheckedChange = {
                    motion = it
                    prefs.edit().putBoolean("motion_enabled", it).apply()
                })
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(LibraryVisualStyle.entries) { option ->
                    OutlinedCard(
                        onClick = { onStyle(option) },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (style == option) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.width(145.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Box(Modifier.fillMaxWidth().height(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Brush.horizontalGradient(palette(option))))
                            Spacer(Modifier.height(8.dp))
                            Text(option.title, style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (style == option) FontWeight.Bold else FontWeight.Medium)
                            Text(option.tagline, style = MaterialTheme.typography.labelSmall,
                                maxLines = 2)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, label = { Text("Search your books") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
        }
        if (filtered.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Headphones, null, Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (books.isEmpty()) "No saved audiobooks yet" else "No matching books",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (books.isEmpty()) "Save a book from Browse to see it here"
                        else "Try a different title or author",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (style == LibraryVisualStyle.Aurora) {
            items(filtered.chunked(2), key = { row -> row.joinToString("|") { "${it.sourceId}:${it.id}" } }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        AnimatedLibraryTile(book, progressFor(book), style, motion,
                            { onBook(book) }, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(filtered, key = { "${it.sourceId}:${it.id}" }) { book ->
                AnimatedLibraryTile(
                    book, progressFor(book), style, motion,
                    { onBook(book) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun AnimatedLibraryBackdrop(
    style: LibraryVisualStyle,
    motion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val glow = if (motion) {
        val transition = rememberInfiniteTransition(label = "Library ambient motion")
        val fraction by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse),
            label = "Subtle light drift",
        )
        fraction
    } else 0.5f
    Box(modifier.clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(palette(style)))) {
        Box(Modifier.align(Alignment.TopEnd)
            .offset(x = (25 - glow * 60).dp, y = (-75 + glow * 28).dp)
            .size(170.dp).background(
                Brush.radialGradient(listOf(
                    if (style == LibraryVisualStyle.Paper) Color.White.copy(alpha = .36f)
                    else Color(0xFF76DDE1).copy(alpha = .38f),
                    Color.Transparent,
                )), RoundedCornerShape(90.dp)
            ))
        Box(Modifier.align(Alignment.BottomStart)
            .offset(x = (-30 + glow * 25).dp, y = (75 - glow * 22).dp)
            .size(150.dp).background(
                Brush.radialGradient(listOf(Color(0xFFFFB678).copy(alpha = .26f),
                    Color.Transparent)), RoundedCornerShape(80.dp)
            ))
        content()
    }
}

@Composable
private fun AnimatedLibraryTile(
    book: AudioBook,
    progress: ListeningProgress,
    style: LibraryVisualStyle,
    motion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var appeared by remember(book.id, style) { mutableStateOf(!motion) }
    LaunchedEffect(book.id, style, motion) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f, animationSpec = tween(350),
        label = "Cover appearance")
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else .96f, animationSpec = tween(350),
        label = "Book opening")
    val bg = if (style == LibraryVisualStyle.Paper) Color(0xFFF6F0E6)
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (style == LibraryVisualStyle.Paper) Color(0xFF322820)
        else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = modifier.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(if (style == LibraryVisualStyle.Cinema) 22.dp else 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (style == LibraryVisualStyle.Walnut) 5.dp else 2.dp),
    ) {
        if (style == LibraryVisualStyle.Aurora) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                LibraryCover(book, Modifier.fillMaxWidth().aspectRatio(.76f))
                Spacer(Modifier.height(9.dp))
                Text(book.title, color = ink, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.authors, color = ink.copy(alpha = .7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                BookProgressLine(progress)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LibraryCover(book, Modifier.width(if (style == LibraryVisualStyle.Cinema) 100.dp else 83.dp)
                    .aspectRatio(.72f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(book.title, color = ink, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (book.authors.isNotBlank()) {
                        Text(book.authors, color = ink.copy(alpha = .7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (style == LibraryVisualStyle.Walnut) {
                        Text("IN YOUR COLLECTION", color = Color(0xFFAE8058),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    BookProgressLine(progress)
                }
            }
        }
    }
}

@Composable
private fun LibraryCover(book: AudioBook, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Headphones, "Cover not available",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (book.coverUrl.isBlank()) {
        Box(modifier.clip(RoundedCornerShape(12.dp))) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = book.coverUrl,
            contentDescription = "${book.title} cover",
            contentScale = ContentScale.Crop,
            loading = { fallback() },
            error = { fallback() },
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    }
}

private fun fraction(progress: ListeningProgress): Float {
    val total = progress.chapterCount
    if (total <= 0) return 0f
    val chapter = progress.chapterIndex.coerceIn(0, total)
    val within = if (progress.chapterDurationMs > 0)
        progress.positionMs.coerceIn(0L, progress.chapterDurationMs).toFloat() /
            progress.chapterDurationMs else 0f
    return ((chapter + within) / total).coerceIn(0f, 1f)
}

@Composable
private fun BookProgressLine(progress: ListeningProgress) {
    if (progress.chapterCount <= 0) return
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { fraction(progress) },
        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
    )
    Text("${(fraction(progress) * 100).toInt()}% listened · Chapter ${progress.chapterIndex + 1}",
        style = MaterialTheme.typography.labelSmall, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

/** A larger book-detail card opens when a library item is tapped. */
@Composable
fun LibraryBookDetailCard(book: AudioBook, progress: ListeningProgress) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_LIBRARY_VISUALS, Context.MODE_PRIVATE)
    }
    val style = LibraryVisualStyle.entries.firstOrNull {
        it.name == prefs.getString("visual_style", LibraryVisualStyle.Aurora.name)
    } ?: LibraryVisualStyle.Aurora
    val motion = prefs.getBoolean("motion_enabled", true)
    AnimatedLibraryBackdrop(
        style, motion, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 190.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryCover(book, Modifier.width(116.dp).aspectRatio(.72f))
            Spacer(Modifier.width(17.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("AUDIOBOOK", color = foreground(style).copy(alpha = .75f),
                    style = MaterialTheme.typography.labelSmall)
                Text(book.title, style = MaterialTheme.typography.titleLarge,
                    color = foreground(style), fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.authors, style = MaterialTheme.typography.bodyMedium,
                    color = foreground(style).copy(alpha = .87f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (progress.chapterCount > 0) {
                    LinearProgressIndicator(
                        progress = { fraction(progress) },
                        modifier = Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Text("${(fraction(progress) * 100).toInt()}% listened",
                        color = foreground(style), style = MaterialTheme.typography.labelMedium)
                } else if (book.language.isNotBlank()) {
                    Text(book.language, style = MaterialTheme.typography.labelMedium,
                        color = foreground(style))
                }
            }
        }
    }
}
