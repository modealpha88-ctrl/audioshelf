package com.mohamed.audioshelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun vhsVinylReveal(openness: Float): Float = vhsSmoothstep((openness - .68f) / .32f)

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

internal fun vhsIsAlbum(ratio: Float): Boolean = ratio in .90f..1.15f

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
    onBackdropCover: (String) -> Unit = {},
    flipSounds: Boolean = true,
) {
    val Ink = MaterialTheme.colorScheme.onBackground
    val Cobalt = MaterialTheme.colorScheme.primary
    val Muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = MaterialTheme.colorScheme.background == vhsDarkColors.background
    var filter by rememberSaveable { mutableStateOf(ArchiveFilter.ALL) }
    val visible = vhsFilterBooks(books, filter, progressFor)
    var target by rememberSaveable(filter, visible.size) {
        mutableIntStateOf(visible.size / 2)
    }
    val playFlip = rememberShelfFlipSound(flipSounds)
    val latestPlayFlip by androidx.compose.runtime.rememberUpdatedState(playFlip)
    fun moveTo(next: Int) {
        if (visible.size > 1 && next != target) {
            target = next
        }
    }
    val position by animateFloatAsState(
        target.toFloat(),
        animationSpec = if (motion) tween(1050, easing = FastOutSlowInEasing) else snap(),
        label = "Physical audiobook shelf",
    )
    // Sample actual animated travel, not the selected title. Accumulate distance
    // instead of reacting to recomposition or repeatedly crossing a jittery boundary.
    LaunchedEffect(filter, visible.size, flipSounds, motion) {
        if (!flipSounds || visible.size < 2) return@LaunchedEffect
        var previous = position
        var travel = .30f
        androidx.compose.runtime.snapshotFlow { position }.collect { current ->
            travel = shelfSoundTravel(travel, current - previous)
            previous = current
            if (travel >= .50f) {
                travel %= .50f
                latestPlayFlip()
            }
        }
    }
    val selected = visible.getOrNull(vhsWrap(position.roundToInt(), visible.size))
    val backdropUrl = visible.getOrNull(vhsWrap(target, visible.size))?.coverUrl.orEmpty()
    LaunchedEffect(backdropUrl) { onBackdropCover(backdropUrl) }
    val density = LocalDensity.current

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            val tabs = listOf(
                ArchiveFilter.ALL to "ALL / LIBRARY",
                ArchiveFilter.AM to "A-M",
                ArchiveFilter.NZ to "N-Z",
                ArchiveFilter.LISTENING to "LISTENING",
            )
            tabs.forEachIndexed { index, (kind, text) ->
                val active = kind == filter
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (active) if (dark) Color(0xFF48486D).copy(alpha = .9f) else Cobalt else MaterialTheme.colorScheme.surface.copy(alpha = .44f))
                        .border(.8.dp, Ink.copy(alpha = if (active) .65f else .30f), RoundedCornerShape(7.dp))
                        .clickable(role = Role.Tab) { filter = kind }
                        .padding(horizontal = if (index == 0) 8.dp else 11.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, color = if (active) Color.White else Ink,
                        fontFamily = FontFamily.Serif,
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
            val hardbackSpine = bookHeight * (142f / 840f)
            val ratios = remember { mutableStateMapOf<String, Float>() }
            val maxCoverWidth = maxWidth * .54f
            fun faceWidth(ratio: Float) = minOf(bookHeight * ratio, if (vhsIsAlbum(ratio)) maxWidth * .49f else maxCoverWidth)
            fun ratioAt(index: Int) = ratios[visible[vhsWrap(index, visible.size)].coverUrl] ?: .67f
            val mid = floor(position.toDouble()).toInt()
            fun spineAt(index: Int): Dp {
                val ratio = ratioAt(index)
                return if (vhsIsAlbum(ratio)) (faceWidth(ratio) / ratio * .043f).coerceAtLeast(7.dp)
                    else hardbackSpine
            }
            val gap = bookHeight * .018f
            fun slotAt(index: Int): Dp {
                val ratio = ratioAt(index)
                val open = vhsSmoothstep(1f - abs(index - position))
                val spine = spineAt(index)
                val face = faceWidth(ratio) + if (vhsIsAlbum(ratio)) 1.dp else 4.dp
                val reveal = if (vhsIsAlbum(ratio)) faceWidth(ratio) * .32f * vhsVinylReveal(open) else 0.dp
                return spine * (1f - open) + face * open + reveal
            }
            fun centre(index: Int): Dp {
                var distance = 0.dp
                if (index > mid) for (i in mid until index) {
                    distance += (slotAt(i) + slotAt(i + 1)) / 2f + gap
                }
                if (index < mid) for (i in index until mid) {
                    distance -= (slotAt(i) + slotAt(i + 1)) / 2f + gap
                }
                return distance - ((slotAt(mid) + slotAt(mid + 1)) / 2f + gap) * (position - mid)
            }
            val stageWidth = maxWidth
            val stepPx = with(density) { (hardbackSpine + gap).toPx() }
            var draggedPx by remember { mutableFloatStateOf(0f) }
            var dragSoundTravel by remember { mutableFloatStateOf(0f) }

            Box(
                Modifier.fillMaxWidth().height(bookHeight + 72.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .pointerInput(visible, stepPx, flipSounds) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                draggedPx = 0f
                                dragSoundTravel = stepPx * .34f
                            },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                draggedPx += dx
                                // Finger travel makes the shuffle audible before release,
                                // even when the shelf's selected book has not changed yet.
                                if (visible.size > 1 && stepPx > 0f) {
                                    dragSoundTravel = shelfSoundTravel(dragSoundTravel, dx)
                                    val interval = stepPx * .5f
                                    if (dragSoundTravel >= interval) {
                                        dragSoundTravel %= interval
                                        latestPlayFlip()
                                    }
                                }
                            },
                            onDragEnd = {
                                val steps = (draggedPx / stepPx).roundToInt()
                                val delta = if (steps == 0 && abs(draggedPx) > stepPx * .22f)
                                    if (draggedPx > 0) 1 else -1 else steps
                                moveTo(target - delta.coerceIn(-3, 3))
                                draggedPx = 0f
                                dragSoundTravel = 0f
                            },
                            onDragCancel = { draggedPx = 0f; dragSoundTravel = 0f },
                        )
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> { moveTo(target - 1); true }
                            Key.DirectionRight -> { moveTo(target + 1); true }
                            Key.Enter, Key.NumPadEnter -> {
                                selected?.let(onOpen)
                                true
                            }
                            else -> false
                        }
                    }.focusable(),
                contentAlignment = Alignment.Center,
            ) {
                // Continuous contact shadow and a restrained shelf lip.
                Box(Modifier.fillMaxWidth().height(38.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(
                        Color.Transparent, if (dark) Color(0x99000000) else Color(0x24231D17), Color.Transparent,
                    ))))
                if (dark) Box(Modifier.align(Alignment.BottomCenter).offset(y = (-28).dp)
                    .fillMaxWidth().height(8.dp).background(Brush.verticalGradient(
                        listOf(Color(0xFF5A4230), Color(0xFF2B211C), Color(0xFF100D0C)))))
                for (virtualIndex in (mid - 32)..(mid + 32)) {
                    val book = visible[vhsWrap(virtualIndex, visible.size)]
                    val relative = virtualIndex - position
                    val openness = vhsSmoothstep(1f - abs(relative))
                    val x = centre(virtualIndex)
                    // Only compose physical cases near the visible viewport.
                    // Repeated virtual copies stay off-screen and never trigger image loads.
                    if (abs(x.value) > stageWidth.value / 2f + maxCoverWidth.value) continue
                    key(virtualIndex, book.sourceId, book.id) {
                        val ratio = ratioAt(virtualIndex)
                        val width = faceWidth(ratio)
                        VhsPhysicalCase(
                            book = book, spineRevision = spineRevision,
                            height = width / ratio, spineWidth = spineAt(virtualIndex),
                            album = vhsIsAlbum(ratio),
                            onRatio = { ratios[book.coverUrl] = it },
                            coverWidth = width,
                            openness = openness, motion = motion,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .offset(x = x, y = (-36).dp).zIndex(
                                40f - abs(relative) + 100f * openness),
                            onSelect = { moveTo(virtualIndex) },
                            onOpen = { onOpen(book) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .55f))
                .border(.7.dp, Ink.copy(alpha = .23f), RoundedCornerShape(12.dp))
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { moveTo(target - 1) }, modifier = Modifier.size(48.dp)) {
                Text("←", fontSize = 23.sp, color = Ink)
            }
            Box(Modifier.weight(1f).height(124.dp), contentAlignment = Alignment.TopCenter) {
                if (selected != null) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            if (!motion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                slideInVertically(tween(850)) { it } togetherWith
                                    slideOutVertically(tween(850)) { -it }
                            }
                        },
                        label = "Masked audiobook caption",
                    ) { captionBook ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(captionBook.title, color = Ink, fontFamily = FontFamily.Serif,
                                fontSize = 26.sp, lineHeight = 27.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center)
                            Spacer(Modifier.height(5.dp))
                            Text(captionBook.authors.ifBlank { "UNKNOWN AUTHOR" }.uppercase(),
                                color = Cobalt, fontSize = 11.sp, fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, letterSpacing = 1.sp)
                            val progress = progressFor(captionBook)
                            val percent = (conceptProgress(progress) * 100f).roundToInt()
                            Text(if (percent > 0) "$percent% LISTENED" else "AUDIOBOOK · OPEN COVER",
                                color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            IconButton(onClick = { moveTo(target + 1) }, modifier = Modifier.size(48.dp)) {
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
internal fun rememberShelfCoverPainter(book: AudioBook): AsyncImagePainter {
    val context = LocalContext.current
    val request = remember(context, book.coverUrl) {
        ImageRequest.Builder(context).data(book.coverUrl.ifBlank { null })
            .size(840, 840).scale(coil.size.Scale.FIT).allowHardware(false).build()
    }
    return rememberAsyncImagePainter(request)
}

@Composable
private fun VhsPhysicalCase(
    book: AudioBook,
    spineRevision: Int,
    height: Dp,
    spineWidth: Dp,
    coverWidth: Dp,
    album: Boolean,
    onRatio: (Float) -> Unit,
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
    val coverPainter = rememberShelfCoverPainter(book)
    val coverDrawable = (coverPainter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    LaunchedEffect(book.coverUrl, coverDrawable) {
        if (coverDrawable != null && coverDrawable.intrinsicWidth > 0 && coverDrawable.intrinsicHeight > 0) {
            onRatio(coverDrawable.intrinsicWidth.toFloat() / coverDrawable.intrinsicHeight)
        }
    }
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

    val edge = if (album) 1.dp else 4.dp
    val corner = if (album) 1.dp else 4.dp
    val spineAngle = -90f * openness
    val coverAngle = 90f * (1f - openness)
    val tilt = if (album) 0f else -3.5f * openness
    val reveal = if (album) coverWidth * .32f * vhsVinylReveal(openness) else 0.dp

    Box(
        modifier.width(maxOf(spineWidth, coverWidth + edge + reveal)).height(height)
            .graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                rotationZ = tilt
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
                    .shadow(if (album) 2.dp else 5.dp, RoundedCornerShape(corner))
                    .clickable(enabled = openness < .50f, role = Role.Button,
                        onClickLabel = "Select " + book.title, onClick = onSelect)
                    .semantics { contentDescription = "Select audiobook: " + book.title },
            ) {
                Box(Modifier.align(Alignment.CenterEnd).width(if (album) 1.dp else 5.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF8F7D65), Color(0xFFE6D8B9), Color(0xFF655446),
                    ))))
                if (spineImage != null) {
                    coil.compose.AsyncImage(
                        model = spineImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else if (album) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner))
                        .background(palette.cloth)) {
                        if (coverDrawable != null) {
                            Image(coverPainter, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(palette.cloth.copy(alpha = .78f)))
                        }
                        Box(Modifier.align(Alignment.Center).layout { measurable, limits ->
                            val measured = measurable.measure(Constraints(
                                maxWidth = limits.maxHeight.coerceAtLeast(1),
                                maxHeight = limits.maxWidth.coerceAtLeast(1),
                            ))
                            layout(measured.height, measured.width) {
                                measured.placeWithLayer((measured.height - measured.width) / 2,
                                    (measured.width - measured.height) / 2) { rotationZ = 90f }
                            }
                        }) {
                            Text(bookstoreSpineTitle(book.title).uppercase(), color = palette.ink,
                                fontSize = (spineWidth.value * .68f).coerceIn(4f, 7f).sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(2.dp))) {
                        val illustrated = binding == SpineBindingMode.AUTOMATIC && coverDrawable != null
                        val artHeight = height * .31f
                        val cloth = palette.cloth
                        val ink = palette.ink
                        if (binding == SpineBindingMode.AUTOMATIC) {
                            Box(Modifier.fillMaxSize().background(cloth))
                            if (illustrated) {
                                Image(coverPainter, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(cloth.copy(alpha = .90f)))
                                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(artHeight)) {
                                    Image(coverPainter, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                        0f to cloth.copy(alpha = .08f),
                                        .60f to Color.Transparent,
                                        1f to cloth.copy(alpha = .94f),
                                    )))
                                }
                            }
                        } else {
                            BookstorePrintedSpine(book.title, texture, palette,
                                Modifier.fillMaxSize(), ornate = binding != SpineBindingMode.CLASSIC)
                        }
                        val title = bookstoreSpineTitle(book.title).uppercase()
                        val footerHeight = height * .15f
                        val topSpace = if (illustrated) artHeight else height * .12f
                        val titleFont = minOf(spineWidth.value * .34f,
                            (height - topSpace - footerHeight).value / (title.length.coerceAtLeast(1) * .58f))
                            .coerceIn(8f, 18f)
                        Box(Modifier.fillMaxSize().padding(top = topSpace, bottom = footerHeight),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.layout { measurable, limits ->
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
                            }) {
                                Text(title, fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold, fontSize = titleFont.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                            }
                        }
                        Column(Modifier.align(Alignment.BottomCenter)
                            .padding(horizontal = 3.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(book.authors.substringBefore(",").substringAfterLast(" ").uppercase(),
                                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                                fontSize = 6.sp, letterSpacing = .5.sp, color = ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text("AUDIO", Modifier.border(.6.dp, ink.copy(alpha = .75f),
                                RoundedCornerShape(1.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                                color = ink, fontFamily = FontFamily.Serif,
                                fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        PrintedEditionFinish(book.title, Modifier.fillMaxSize())

                    }
                }
                Box(Modifier.align(Alignment.CenterStart).width(if (album) 1.dp else 5.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0x66000000), Color.Transparent,
                    ))))
            }
        }

        if (openness > .001f) {
            Box(
                Modifier.width(coverWidth + edge + reveal).height(height)
                    .graphicsLayer {
                        rotationY = coverAngle
                        cameraDistance = 34f * density
                    }
                    .clickable(enabled = openness > .55f, role = Role.Button,
                        onClickLabel = "Open " + book.title, onClick = onOpen)
                    .semantics { contentDescription = "Open audiobook: " + book.title },
            ) {
                if (album && reveal > 0.dp) VinylDisc(palette.cloth,
                    Modifier.align(Alignment.CenterStart).offset(x = reveal + coverWidth * .02f)
                        .size(minOf(coverWidth, height) * .96f))
                // Physical side of the box: paper grain, board bevel, and cast shadow.
                Box(Modifier.align(Alignment.CenterStart).offset(x = coverWidth).width(edge).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF28231F), palette.cloth, Color(0xFF302A25),
                    )))) {
                    if (!album) Canvas(Modifier.fillMaxSize()) {
                        var y = 2.dp.toPx()
                        while (y < size.height) {
                            drawLine(Color(0x244A443C),
                                Offset(0f, y), Offset(size.width, y),
                                strokeWidth = .5.dp.toPx())
                            y += 2.dp.toPx()
                        }
                    }
                }
                Box(Modifier.width(coverWidth).height(height)
                    .align(Alignment.CenterStart)
                    .shadow(if (album) 4.dp else 10.dp, RoundedCornerShape(corner))
                    .clip(RoundedCornerShape(corner))
                    .background(Color(0xFF28252A))) {
                    if (coverDrawable != null) {
                        Image(coverPainter, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                    } else {
                        ShelfTextCover(book, Modifier.fillMaxSize())
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(if (album) .5.dp else 2.dp).fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(
                            Color(0x55000000), Color.Transparent,
                        ))))
                }
                PrintedEditionFinish(book.title,
                    Modifier.width(coverWidth).height(height).align(Alignment.CenterStart), album)
            }
        }
    }
}

/** Vinyl is drawn behind the paper jacket; the original cover is never distorted. */
@Composable
private fun VinylDisc(label: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFF080808), r, center)
        drawCircle(Brush.sweepGradient(listOf(Color(0xFF111113), Color(0xFF555256),
            Color(0xFF151517), Color(0xFF080808), Color(0xFF424043), Color(0xFF111113)), center), r * .99f, center)
        for (i in 0..54) {
            val radius = r * (.34f + i * .012f)
            drawCircle(if (i % 4 == 0) Color(0x605E5B5B) else Color(0x65000000),
                radius, center, style = Stroke(.45.dp.toPx()))
        }
        drawCircle(Color(0xFF807B70), r * .995f, center, style = Stroke(.6.dp.toPx()))
        drawCircle(label, r * .30f, center)
        drawCircle(Color(0x55F5E3C6), r * .27f, center, style = Stroke(.7.dp.toPx()))
        drawCircle(Color(0xFF111111), r * .028f, center)
    }
}

/** Repeatable paper fibre, edge abrasion and short creases, without modifying stored cover art. */
@Composable
private fun PrintedEditionFinish(seed: String, modifier: Modifier = Modifier, album: Boolean = false) {
    val corner = if (album) 1.dp else 4.dp
    Canvas(modifier.clip(RoundedCornerShape(corner))) {
        val random = kotlin.random.Random(seed.hashCode())
        val cr = androidx.compose.ui.geometry.CornerRadius(corner.toPx())
        val wear = if (album) .72f else 1f
        val inset = .65.dp.toPx()
        drawRoundRect(Color(0x6A342519), cornerRadius = cr, style = Stroke(1.8.dp.toPx()))
        drawRoundRect(Color(0x8BD8B88D).copy(alpha = .48f * wear), topLeft = Offset(inset, inset),
            size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = cr, style = Stroke(inset))
        drawRect(Brush.horizontalGradient(0f to Color(0x51000000), .065f to Color.Transparent,
            .92f to Color.Transparent, 1f to Color(0x47000000)))
        repeat(260) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            drawCircle(Color(if (it % 2 == 0) 0x16E9D9BC else 0x18000000),
                (.15f + random.nextFloat() * .40f).dp.toPx(), Offset(x, y))
        }
        repeat(90) {
            val along = random.nextFloat()
            val side = random.nextInt(4)
            val depth = random.nextFloat() * 2.1.dp.toPx()
            val point = when (side) {
                0 -> Offset(depth, along * size.height)
                1 -> Offset(size.width - depth, along * size.height)
                2 -> Offset(along * size.width, depth)
                else -> Offset(along * size.width, size.height - depth)
            }
            val end = if (side < 2) point + Offset(0f, (1f + random.nextFloat() * 4f).dp.toPx())
                else point + Offset((1f + random.nextFloat() * 4f).dp.toPx(), 0f)
            drawLine(Color(0xFFE2BD8B).copy(alpha = (.16f + random.nextFloat() * .4f) * wear),
                point, end, (.25f + random.nextFloat() * .6f).dp.toPx())
        }
        repeat(12) {
            val x = if (it % 2 == 0) random.nextFloat() * size.width * .12f
                else size.width * (.88f + random.nextFloat() * .12f)
            val y = if (it % 3 == 0) random.nextFloat() * size.height * .10f
                else size.height * (.90f + random.nextFloat() * .10f)
            val end = Offset((x + (random.nextFloat() - .5f) * 13.dp.toPx()).coerceIn(0f, size.width),
                (y + (random.nextFloat() - .5f) * 9.dp.toPx()).coerceIn(0f, size.height))
            drawLine(Color(0xFFD9C09A).copy(alpha = .28f * wear), Offset(x,y), end, .45.dp.toPx())
        }
    }
}
