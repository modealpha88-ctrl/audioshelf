package com.mohamed.audioshelf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
            dampingRatio = .96f, stiffness = 85f,
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
            val ratios = remember { mutableStateMapOf<String, Float>() }
            val maxCoverWidth = maxWidth * .54f
            fun faceWidth(ratio: Float) = minOf(bookHeight * ratio, maxCoverWidth)
            fun ratioAt(index: Int) = ratios[visible[vhsWrap(index, visible.size)].coverUrl] ?: .67f
            val mid = floor(position.toDouble()).toInt()
            val weight = vhsSmoothstep(position - mid)
            fun caseWidth(ratio: Float) = faceWidth(ratio) + if (ratio < .86f) 4.dp else 2.dp
            val openingWidth = caseWidth(ratioAt(mid)) * (1f - weight) +
                caseWidth(ratioAt(mid + 1)) * weight
            val gap = bookHeight * .018f
            val stageWidth = maxWidth
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
                                target -= delta.coerceIn(-3, 3)
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
                for (virtualIndex in (mid - 10)..(mid + 11)) {
                    val book = visible[vhsWrap(virtualIndex, visible.size)]
                    val relative = virtualIndex - position
                    val openness = vhsSmoothstep(1f - abs(relative))
                    val x = vhsShelfPosition(relative, spineWidth.value,
                        openingWidth.value, gap.value).dp
                    // Only compose physical cases near the visible viewport.
                    // Repeated virtual copies stay off-screen and never trigger image loads.
                    if (abs(x.value) > stageWidth.value / 2f + maxCoverWidth.value) continue
                    key(virtualIndex, book.sourceId, book.id) {
                        val ratio = ratioAt(virtualIndex)
                        val width = faceWidth(ratio)
                        VhsPhysicalCase(
                            book = book, spineRevision = spineRevision,
                            height = width / ratio, spineWidth = spineWidth,
                            album = ratio >= .86f,
                            onRatio = { ratios[book.coverUrl] = it },
                            coverWidth = width,
                            openness = openness, motion = motion,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .offset(x = x, y = (-36).dp).zIndex(
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
                                slideInVertically(tween(850)) { it } togetherWith
                                    slideOutVertically(tween(850)) { -it }
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

    val edge = if (album) 2.dp else 4.dp
    val spineAngle = -90f * openness
    val coverAngle = 90f * (1f - openness)
    val tilt = -3.5f * openness

    Box(
        modifier.width(maxOf(spineWidth, coverWidth + edge)).height(height)
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
                    .shadow(5.dp, RoundedCornerShape(4.dp))
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
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
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
                                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(artHeight)) {
                                    Image(coverPainter, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                        0f to cloth.copy(alpha = .08f),
                                        .60f to Color.Transparent,
                                        1f to cloth,
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
                Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(
                        Color(0x66000000), Color.Transparent,
                    ))))
            }
        }

        if (openness > .001f) {
            Box(
                Modifier.width(coverWidth + edge).height(height)
                    .graphicsLayer {
                        rotationY = coverAngle
                        cameraDistance = 34f * density
                    }
                    .shadow(10.dp, RoundedCornerShape(4.dp))
                    .clickable(enabled = openness > .55f, role = Role.Button,
                        onClickLabel = "Open " + book.title, onClick = onOpen)
                    .semantics { contentDescription = "Open audiobook: " + book.title },
            ) {
                // Physical side of the box: paper grain, board bevel, and cast shadow.
                Box(Modifier.align(Alignment.CenterEnd).width(edge).fillMaxSize()
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
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF28252A))) {
                    if (coverDrawable != null) {
                        Image(coverPainter, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit)
                    } else {
                        ShelfTextCover(book, Modifier.fillMaxSize())
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(
                            Color(0x55000000), Color.Transparent,
                        ))))
                }
                PrintedEditionFinish(book.title, Modifier.width(coverWidth).height(height).align(Alignment.CenterStart))
                if (openness > .96f) {
                    Box(Modifier.align(Alignment.BottomStart)
                        .padding(4.dp)
                        .size(30.dp)
                        .shadow(5.dp, CircleShape)
                        .background(Paper.copy(alpha = .96f), CircleShape)
                        .border(1.dp, Cobalt, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Open audiobook details",
                            onClick = onOpen),
                        contentAlignment = Alignment.Center) {
                        Text("▶", color = Cobalt, fontSize = 13.sp,
                            modifier = Modifier.offset(x = 1.dp))
                    }
                }
            }
        }
    }
}

/** Subtle printed-stock patina and rounded edge highlights, stable for each edition. */
@Composable
private fun PrintedEditionFinish(seed: String, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        val random = kotlin.random.Random(seed.hashCode())
        val hairline = .65.dp.toPx()
        drawRoundRect(Color(0x65463B2D), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(Color(0x70EEE2CC), topLeft = Offset(hairline, hairline),
            size = Size((size.width - 2 * hairline).coerceAtLeast(0f), (size.height - 2 * hairline).coerceAtLeast(0f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(hairline))
        drawRect(Brush.horizontalGradient(0f to Color(0x37000000), .09f to Color.Transparent,
            .88f to Color.Transparent, 1f to Color(0x38000000)))
        repeat(180) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val edge = x < size.width * .07f || x > size.width * .93f || y < size.height * .025f || y > size.height * .975f
            drawCircle(if (edge) Color(0x48E9D9BC) else Color(0x0BE9D9BC),
                radius = (.2f + random.nextFloat() * if (edge) .9f else .35f).dp.toPx(), center = Offset(x, y))
        }
    }
}
