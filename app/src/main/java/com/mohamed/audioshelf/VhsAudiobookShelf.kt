package com.mohamed.audioshelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.mohamed.audioshelf.data.SpineBindingMode
import com.mohamed.audioshelf.data.SpineBindingStore
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private val Paper = Color(0xFFEDEBE6)
private val Ink = Color(0xFF141414)
private val Cobalt = Color(0xFF1F3FB0)
private val Muted = Color(0xFF6D6A63)

internal fun vhsWrap(index: Int, count: Int): Int =
    if (count <= 0) 0 else ((index % count) + count) % count

internal fun vhsSmoothstep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Continuous displacement keeps the open cover from overlapping the next spine. */
internal fun vhsShelfPosition(relative: Float, spine: Float, depth: Float, gap: Float): Float {
    val bend = when {
        relative <= -1f -> -.5f
        relative >= 1f -> .5f
        else -> (relative + sin((PI * relative).toFloat()) / PI.toFloat()) / 2f
    }
    return relative * (spine + gap) + (depth - spine + 1.6f * gap) * bend
}

internal enum class ArchiveFilter { ALL, AM, NZ, LISTENING }

/** Retains real user metadata; films, years, and fictional asset sheets are never substituted. */
internal fun vhsFilterBooks(
    books: List<AudioBook>,
    filter: ArchiveFilter,
    progressFor: (AudioBook) -> ListeningProgress,
): List<AudioBook> = when (filter) {
    ArchiveFilter.ALL -> books
    ArchiveFilter.AM -> books.filter {
        it.title.trim().firstOrNull()?.uppercaseChar()?.let { c -> c in 'A'..'M' } == true
    }
    ArchiveFilter.NZ -> books.filter {
        it.title.trim().firstOrNull()?.uppercaseChar()?.let { c -> c in 'N'..'Z' } == true
    }
    ArchiveFilter.LISTENING -> books.filter {
        val progress = progressFor(it)
        progress.chapterIndex > 0 || progress.positionMs > 0L
    }
}

/**
 * Leon-Vasari-style physical media stage for the actual AudioShelf library.
 * Horizontal navigation, spring positions, cuboid turn, active caption and the
 * detail action are native Compose; no WebView or fictional film assets.
 */
@Composable
internal fun VhsAudiobookShelf(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    spineRevision: Int,
    onOpen: (AudioBook) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ArchiveFilter.ALL) }
    val visible = vhsFilterBooks(books, filter, progressFor)
    var target by rememberSaveable(filter, visible.size) {
        mutableIntStateOf(visible.size / 2)
    }
    val position by animateFloatAsState(
        target.toFloat(),
        animationSpec = if (motion) spring(
            dampingRatio = .84f, stiffness = Spring.StiffnessLow,
        ) else snap(),
        label = "Physical audiobook shelf",
    )
    val selected = visible.getOrNull(vhsWrap(position.roundToInt(), visible.size))
    val density = LocalDensity.current

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center) {
            val tabs = listOf(
                ArchiveFilter.ALL to "ALL / LIBRARY",
                ArchiveFilter.AM to "A-M",
                ArchiveFilter.NZ to "N-Z",
                ArchiveFilter.LISTENING to "LISTENING",
            )
            tabs.forEachIndexed { index, (kind, text) ->
                val active = kind == filter
                Box(
                    Modifier.border(1.dp, Ink.copy(alpha = .84f))
                        .background(if (active) Cobalt else Color.Transparent)
                        .clickable(role = Role.Tab) { filter = kind }
                        .padding(horizontal = if (index == 0) 8.dp else 11.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, color = if (active) Color.White else Ink,
                        fontSize = 9.sp, letterSpacing = .5.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(265.dp), contentAlignment = Alignment.Center) {
                Text("No audiobooks in this selection", color = Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bookHeight = minOf(maxWidth * .60f, 260.dp).coerceAtLeast(150.dp)
            val spineWidth = bookHeight * (142f / 840f)
            val coverWidth = bookHeight * (474f / 900f)
            val gap = bookHeight * .030f
            val stepPx = with(density) { (spineWidth + gap).toPx() }
            var draggedPx by remember { mutableFloatStateOf(0f) }

            Box(
                Modifier.fillMaxWidth().height(bookHeight + 72.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .pointerInput(visible.size, stepPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { draggedPx = 0f },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                draggedPx += dx
                            },
                            onDragEnd = {
                                val steps = (draggedPx / stepPx).roundToInt()
                                val delta = if (steps == 0 && abs(draggedPx) > stepPx * .22f)
                                    if (draggedPx > 0) 1 else -1 else steps
                                target -= delta.coerceIn(-6, 6)
                                draggedPx = 0f
                            },
                            onDragCancel = { draggedPx = 0f },
                        )
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> { target--; true }
                            Key.DirectionRight -> { target++; true }
                            Key.Enter, Key.NumPadEnter -> {
                                selected?.let(onOpen)
                                true
                            }
                            else -> false
                        }
                    }.focusable(),
                contentAlignment = Alignment.Center,
            ) {
                // Subtle grounding of the row, without the old wooden shelf planks.
                Box(Modifier.fillMaxWidth().height(38.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(
                        Color.Transparent, Color(0x14231D17), Color.Transparent,
                    ))))
                val mid = floor(position.toDouble()).toInt()
                for (virtualIndex in (mid - 10)..(mid + 11)) {
                    val book = visible[vhsWrap(virtualIndex, visible.size)]
                    val relative = virtualIndex - position
                    val openness = vhsSmoothstep(1f - abs(relative))
                    val x = vhsShelfPosition(relative, spineWidth.value,
                        coverWidth.value, gap.value).dp
                    // Only compose physical cases near the visible viewport.
                    // Repeated virtual copies stay off-screen and never trigger image loads.
                    if (abs(x.value) > maxWidth.value / 2f + coverWidth.value * 1.4f) continue
                    key(virtualIndex, book.sourceId, book.id) {
                        VhsPhysicalCase(
                            book = book, spineRevision = spineRevision,
                            height = bookHeight, spineWidth = spineWidth,
                            coverWidth = coverWidth, relative = relative,
                            openness = openness, motion = motion,
                            modifier = Modifier.offset(x = x).zIndex(
                                40f - abs(relative) + 100f * openness),
                            onSelect = { target = virtualIndex },
                            onOpen = { onOpen(book) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { target-- }, modifier = Modifier.size(48.dp)) {
                Text("←", fontSize = 23.sp, color = Ink)
            }
            Box(Modifier.weight(1f).height(96.dp), contentAlignment = Alignment.TopCenter) {
                if (selected != null) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            if (!motion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                slideInVertically(tween(650)) { it } togetherWith
                                    slideOutVertically(tween(650)) { -it }
                            }
                        },
                        label = "Masked audiobook caption",
                    ) { captionBook ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(captionBook.title, color = Ink, fontFamily = FontFamily.Serif,
                                fontSize = 26.sp, lineHeight = 27.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center)
                            Spacer(Modifier.height(5.dp))
                            Text(captionBook.authors.ifBlank { "UNKNOWN AUTHOR" }.uppercase(),
                                color = Cobalt, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, letterSpacing = 1.sp)
                            val progress = progressFor(captionBook)
                            val percent = (conceptProgress(progress) * 100f).roundToInt()
                            Text(if (percent > 0) "$percent% LISTENED" else "AUDIOBOOK · OPEN COVER",
                                color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            IconButton(onClick = { target++ }, modifier = Modifier.size(48.dp)) {
                Text("→", fontSize = 23.sp, color = Ink)
            }
        }
        Text("DRAG TO BROWSE   ·   TAP THE COVER TO OPEN",
            fontSize = 9.sp, letterSpacing = 1.15.sp,
            fontWeight = FontWeight.SemiBold, color = Muted,
            modifier = Modifier.padding(top = 7.dp, bottom = 8.dp))
    }
}

@Composable
private fun VhsPhysicalCase(
    book: AudioBook,
    spineRevision: Int,
    height: Dp,
    spineWidth: Dp,
    coverWidth: Dp,
    relative: Float,
    openness: Float,
    motion: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { SpineBindingStore(context) }
    val binding = remember(book.sourceId, book.id, spineRevision) { store.mode(book) }
    var spineImage by remember(book.sourceId, book.id, spineRevision) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(book.sourceId, book.id, binding, spineRevision) {
        spineImage = withContext(Dispatchers.IO) {
            runCatching { store.displayedArtwork(book, binding) }.getOrNull()
        }
    }
    val coverRequest = remember(context, book.coverUrl) {
        ImageRequest.Builder(context).data(book.coverUrl.ifBlank { null })
            .size(420, 840).allowHardware(false).build()
    }
    val coverPainter = rememberAsyncImagePainter(coverRequest)
    val coverDrawable = (coverPainter.state as? AsyncImagePainter.State.Success)
        ?.result?.drawable
    val texture = remember(book.title, book.authors) { bookstoreCloth(book) }
    val savedPalette = remember(book.id, book.coverUrl, spineRevision) { store.palette(book) }
    val palette = remember(book.title, coverDrawable, savedPalette, binding, texture) {
        when {
            binding == SpineBindingMode.CLASSIC ->
                bookstoreSpineColors(book.title, null, texture)
            coverDrawable != null -> bookstoreSpineColors(book.title, coverDrawable, texture)
            savedPalette != null -> BookstoreSpineColors(
                Color(savedPalette.first), Color(savedPalette.second), true,
            )
            else -> bookstoreSpineColors(book.title, null, texture)
        }
    }
    LaunchedEffect(book.id, book.coverUrl, palette) {
        if (coverDrawable != null && palette.hasCoverPalette) {
            withContext(Dispatchers.IO) {
                store.savePalette(book, palette.cloth.toArgb(), palette.ink.toArgb())
            }
        }
    }

    val lift = (openness * 11f).dp
    val spineAngle = -90f * openness
    val coverAngle = 90f * (1f - openness)
    val tilt = if (motion) (-4.5f * openness) else 0f

    Box(
        modifier.width(coverWidth + 12.dp).height(height + 26.dp)
            .graphicsLayer {
                translationY = -lift.toPx()
                rotationZ = tilt
                rotationX = if (motion) 2.5f * openness else 0f
                cameraDistance = 35f * density
            },
        contentAlignment = Alignment.Center,
    ) {
        if (openness < .999f) {
            Box(
                Modifier.width(spineWidth).height(height)
                    .graphicsLayer {
                        rotationY = spineAngle
                        cameraDistance = 34f * density
                        transformOrigin = TransformOrigin(.5f, .5f)
                    }
                    .shadow(9.dp, RoundedCornerShape(2.dp))
                    .clickable(enabled = openness < .50f, role = Role.Button,
                        onClickLabel = "Select " + book.title, onClick = onSelect)
                    .semantics { contentDescription = "Select audiobook: " + book.title },
            ) {
                Box(Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF8F7D65), Color(0xFFE6D8B9), Color(0xFF655446),
                    ))))
                if (spineImage != null) {
                    coil.compose.AsyncImage(
                        model = spineImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(2.dp)),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Box(Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(2.dp))) {
                        BookstorePrintedSpine(
                            title = book.title, texture = texture, palette = palette,
                            modifier = Modifier.fillMaxSize(),
                            ornate = binding != SpineBindingMode.CLASSIC,
                        )
                        Box(
                            Modifier.align(Alignment.Center)
                                .layout { measurable, limits ->
                                    val measured = measurable.measure(Constraints(
                                        maxWidth = limits.maxHeight.coerceAtLeast(1),
                                        maxHeight = limits.maxWidth.coerceAtLeast(1),
                                    ))
                                    layout(measured.height, measured.width) {
                                        measured.placeWithLayer(
                                            (measured.height - measured.width) / 2,
                                            (measured.width - measured.height) / 2,
                                        ) { rotationZ = 90f }
                                    }
                                },
                        ) {
                            Text(bookstoreSpineTitle(book.title).uppercase(),
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.ink)
                        }
                        Text("AUDIO", Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                            color = palette.ink, fontSize = 7.sp,
                            letterSpacing = .4.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0x66000000), Color.Transparent,
                    ))))
            }
        }

        if (openness > .001f) {
            Box(
                Modifier.width(coverWidth + 11.dp).height(height)
                    .graphicsLayer {
                        rotationY = coverAngle
                        cameraDistance = 34f * density
                    }
                    .shadow(22.dp, RoundedCornerShape(2.dp))
                    .clickable(enabled = openness > .55f, role = Role.Button,
                        onClickLabel = "Open " + book.title, onClick = onOpen)
                    .semantics { contentDescription = "Open audiobook: " + book.title },
            ) {
                // Physical side of the box: paper grain, board bevel, and cast shadow.
                Box(Modifier.align(Alignment.CenterEnd).width(11.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF3A332C), Color(0xFFE7DCC5), Color(0xFF514638),
                    )))) {
                    Canvas(Modifier.fillMaxSize()) {
                        var y = 2.dp.toPx()
                        while (y < size.height) {
                            drawLine(Color(0x5C4A443C),
                                Offset(0f, y), Offset(size.width, y),
                                strokeWidth = .5.dp.toPx())
                            y += 3.dp.toPx()
                        }
                    }
                }
                Box(Modifier.width(coverWidth).height(height)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF28252A))) {
                    if (coverDrawable != null) {
                        Image(coverPainter, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                    } else {
                        ShelfTextCover(book, Modifier.fillMaxSize())
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(
                            Color(0xB5000000), Color.Transparent,
                        ))))
                }
                if (openness > .96f) {
                    Box(Modifier.align(Alignment.BottomStart)
                        .offset(x = (-9).dp, y = 6.dp)
                        .size(42.dp)
                        .shadow(5.dp, CircleShape)
                        .background(Paper.copy(alpha = .96f), CircleShape)
                        .border(1.dp, Cobalt, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Open audiobook details",
                            onClick = onOpen),
                        contentAlignment = Alignment.Center) {
                        Text("▶", color = Cobalt, fontSize = 18.sp,
                            modifier = Modifier.offset(x = 1.dp))
                    }
                }
            }
        }
    }
}
