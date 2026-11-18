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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Refresh
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

internal enum class LibraryVisualStyle(val title: String, val tagline: String) {
    Walnut("Listening Lounge", "Walnut & vinyl"),
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
    onResumeBook: (AudioBook) -> Unit,
    onRefreshCovers: () -> Unit,
    onStyleSelected: (String) -> Unit,
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
    // Keep a full bookcase visible on portrait phones even if only one book is saved.
    // Shelves are laid out lazily so larger libraries do not compose every cover.
    val shelfRows = remember(filtered) { filtered.chunked(LOUNGE_COLUMNS) }
    val shelfCount = woodenShelfRowCount(filtered.size)

    Box(Modifier.fillMaxSize().background(conceptSurface(style))) {
        if (style != LibraryVisualStyle.Walnut) {
            ConceptAtmosphere(style, motion, Modifier.fillMaxSize())
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (style == LibraryVisualStyle.Walnut || style == LibraryVisualStyle.Paper) 0.dp else 8.dp
        ),
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                if (style == LibraryVisualStyle.Cinema) {
                    ConceptHero(style, motion, Modifier.fillMaxWidth().height(74.dp))
                } else {
                    val art = when (style) {
                        LibraryVisualStyle.Walnut -> R.drawable.lounge_turntable
                        LibraryVisualStyle.Aurora -> R.drawable.aurora_sky
                        else -> R.drawable.paper_lamp
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 90.dp)) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art), null,
                            Modifier.align(Alignment.CenterEnd).fillMaxWidth(.56f).height(90.dp),
                            contentScale = ContentScale.Crop)
                        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                            0f to conceptSurface(style), .45f to conceptSurface(style),
                            .78f to Color.Transparent)))
                        Text(style.title, Modifier.align(Alignment.CenterStart),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            color = conceptInk(style))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${books.size} saved audiobooks", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = conceptInk(style))
                    if (books.any { it.coverUrl.isBlank() }) {
                        androidx.compose.material3.TextButton(
                            onClick = onRefreshCovers,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = conceptInk(style))
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Find covers")
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Library appearance", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = conceptInk(style),
                    fontFamily = if (style == LibraryVisualStyle.Paper)
                        androidx.compose.ui.text.font.FontFamily.Serif
                        else androidx.compose.ui.text.font.FontFamily.Default)
                Text("Motion", style = MaterialTheme.typography.labelMedium,
                    color = conceptInk(style))
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
                    androidx.compose.material3.FilterChip(
                        selected = style == option,
                        onClick = { onStyle(option); onStyleSelected(option.name) },
                        label = { Text(if (option == LibraryVisualStyle.Walnut) "Lounge" else option.name, maxLines = 1) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = if (style == LibraryVisualStyle.Paper)
                                androidx.compose.ui.graphics.Color(0xFFF7EAD8)
                                else androidx.compose.ui.graphics.Color.Transparent,
                            labelColor = conceptInk(style),
                            selectedContainerColor = if (style == LibraryVisualStyle.Paper)
                                androidx.compose.ui.graphics.Color(0xFFE2CDB7)
                                else androidx.compose.ui.graphics.Color(0xFF504768),
                            selectedLabelColor = conceptInk(style)
                        ),
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = conceptInk(style),
                    fontFamily = if (style == LibraryVisualStyle.Paper)
                        androidx.compose.ui.text.font.FontFamily.Serif
                        else androidx.compose.ui.text.font.FontFamily.Default),
                label = { Text("Search your books") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = conceptInk(style),
                    unfocusedTextColor = conceptInk(style),
                    focusedLabelColor = conceptInk(style),
                    unfocusedLabelColor = conceptInk(style).copy(alpha = .65f),
                    focusedLeadingIconColor = conceptInk(style),
                    unfocusedLeadingIconColor = conceptInk(style).copy(alpha = .7f),
                    focusedBorderColor = if (style == LibraryVisualStyle.Paper)
                        androidx.compose.ui.graphics.Color(0xFFB69379)
                        else androidx.compose.ui.graphics.Color(0xFF9E83C9),
                    unfocusedBorderColor = conceptInk(style).copy(alpha = .34f),
                    focusedContainerColor = if (style == LibraryVisualStyle.Paper)
                        androidx.compose.ui.graphics.Color(0xBBFFF8EC)
                        else androidx.compose.ui.graphics.Color(0x6617283C),
                    unfocusedContainerColor = if (style == LibraryVisualStyle.Paper)
                        androidx.compose.ui.graphics.Color(0xA6FFF8EC)
                        else androidx.compose.ui.graphics.Color(0x6617283C),
                    cursorColor = conceptInk(style),
                ),
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
        } else if (style == LibraryVisualStyle.Walnut) {
            // No gaps between rows: the wooden case continues down the screen.
            item { Spacer(Modifier.height(12.dp)) }
            items(shelfCount, key = { index -> "wooden-shelf-$index" }) { index ->
                WoodenShelfRow(
                    books = shelfRows.getOrElse(index) { emptyList() },
                    progressFor = progressFor,
                    motion = motion,
                    onBook = onBook,
                )
            }
        } else if (style == LibraryVisualStyle.Aurora) {
            items(filtered.chunked(2), key = { row -> row.joinToString("|") { "${it.sourceId}:${it.id}" } }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        AuroraConceptCard(book, progressFor(book), motion,
                            { onBook(book) }, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(filtered, key = { "${it.sourceId}:${it.id}" }) { book ->
                when (style) {
                    LibraryVisualStyle.Cinema -> CinemaConceptCard(
                        book, progressFor(book), motion,
                        onOpen = { onBook(book) }, onPlay = { onResumeBook(book) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    LibraryVisualStyle.Paper -> PaperConceptCard(
                        book = book, progress = progressFor(book), motion = motion,
                        onOpen = { onBook(book) }, onResume = { onResumeBook(book) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    else -> Unit
                }
            }
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
                BookProgressLine(progress, ink, if (style == LibraryVisualStyle.Paper) Color(0xFF6E4C86) else MaterialTheme.colorScheme.primary)
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
                    BookProgressLine(progress, ink, if (style == LibraryVisualStyle.Paper) Color(0xFF6E4C86) else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
internal fun LibraryCover(book: AudioBook, modifier: Modifier = Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0xFF445567), Color(0xFF262D43))
            )).padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AUDIOBOOK", color = Color(0xFFE1D0B9),
                    style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(7.dp))
                Text(book.title, color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (book.authors.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(book.authors, color = Color(0xFFDDDAE4),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
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
private fun BookProgressLine(progress: ListeningProgress, ink: Color, accent: Color) {
    if (progress.chapterCount <= 0) return
    Spacer(Modifier.height(5.dp))
    // A plain track has no end-stop dot (which looked like 100% progress at zero).
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp))
        .background(ink.copy(alpha = .19f))) {
        Box(Modifier.fillMaxWidth(fraction(progress)).fillMaxHeight()
            .background(accent))
    }
    Text("${(fraction(progress) * 100).toInt()}% listened · Chapter ${progress.chapterIndex + 1}",
        color = ink.copy(alpha = .82f),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (style == LibraryVisualStyle.Cinema) LibraryCover(book, Modifier.width(116.dp).aspectRatio(.72f))
            else CollectorSleeve(book, Modifier.width(116.dp).height(145.dp), detail = true, motion = motion)
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
