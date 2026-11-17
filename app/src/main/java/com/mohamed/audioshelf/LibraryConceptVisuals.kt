package com.mohamed.audioshelf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val CYAN = Color(0xFF49E8E1)
private val VIOLET = Color(0xFFBE91F8)
private val INK = Color(0xFF3F2C23)

internal fun conceptSurface(style: LibraryVisualStyle): Color = when (style) {
    LibraryVisualStyle.Paper -> Color(0xFFF7EAD8)
    LibraryVisualStyle.Aurora -> Color(0xFF071121)
    LibraryVisualStyle.Cinema -> Color(0xFF091725)
    LibraryVisualStyle.Walnut -> Color(0xFF141017)
}
internal fun conceptInk(style: LibraryVisualStyle): Color =
    if (style == LibraryVisualStyle.Paper) INK else Color(0xFFF4F1FC)

internal fun conceptProgress(progress: ListeningProgress): Float {
    if (progress.chapterCount <= 0) return 0f
    val index = progress.chapterIndex.coerceIn(0, progress.chapterCount)
    val intra = if (progress.chapterDurationMs > 0L)
        progress.positionMs.coerceIn(0L, progress.chapterDurationMs).toFloat() /
            progress.chapterDurationMs else 0f
    return ((index + intra) / progress.chapterCount).coerceIn(0f, 1f)
}

/** Native GPU-drawn atmosphere. Animated only when the Motion toggle is enabled. */
@Composable
internal fun ConceptAtmosphere(style: LibraryVisualStyle, motion: Boolean,
    modifier: Modifier = Modifier) {
    val drift = if (motion) {
        val t = rememberInfiniteTransition(label = "Library atmosphere")
        val f by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
            label = "Atmospheric movement")
        f
    } else .5f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (style) {
            LibraryVisualStyle.Aurora -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF06101E),
                    Color(0xFF10152E), Color(0xFF071121))))
                drawCircle(Brush.radialGradient(listOf(CYAN.copy(alpha = .28f), Color.Transparent)),
                    w * .78f, Offset(w * (.16f + drift * .22f), h * .34f))
                drawCircle(Brush.radialGradient(listOf(VIOLET.copy(alpha = .3f), Color.Transparent)),
                    w * .85f, Offset(w * (.92f - drift * .15f), h * .8f))
                val wave = Path().apply {
                    moveTo(-w * .1f, h * .87f)
                    cubicTo(w * .28f, h * .7f, w * .65f, h * .96f, w * 1.15f, h * .83f)
                }
                drawPath(wave, Brush.horizontalGradient(listOf(CYAN, VIOLET)),
                    style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))
                drawPath(wave, Color.White.copy(alpha = .35f),
                    style = Stroke(width = .75.dp.toPx()))
                for (i in 0..20) {
                    drawCircle(Color.White.copy(alpha = .14f),
                        .85.dp.toPx(), Offset(w * ((i * 41 % 97) / 100f),
                            h * ((i * 37 % 83) / 100f)))
                }
            }
            LibraryVisualStyle.Cinema -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF081322),
                    Color(0xFF152A3C), Color(0xFF07101D))))
                drawCircle(Brush.radialGradient(listOf(
                    Color(0xFF7EBCD7).copy(alpha = .22f), Color.Transparent)),
                    w, Offset(w * .7f, h * (.22f + drift * .06f)))
            }
            LibraryVisualStyle.Paper -> {
                drawRect(Brush.verticalGradient(listOf(
                    Color(0xFFFFF2E0), Color(0xFFF7E8D2), Color(0xFFF1E0C7))))
                for (i in 0..150) {
                    drawCircle(Color(0xFF94745C).copy(alpha = .05f),
                        .65.dp.toPx(),
                        Offset(w * ((i * 71 % 151) / 151f),
                            h * ((i * 43 % 149) / 149f)))
                }
                drawCircle(Brush.radialGradient(listOf(
                    Color.White.copy(alpha = .6f), Color.Transparent)),
                    w, Offset(w * .16f, h * .28f))
            }
            LibraryVisualStyle.Walnut -> drawRect(Color(0xFF151018))
        }
    }
}

@Composable
internal fun ConceptHero(style: LibraryVisualStyle, motion: Boolean,
    modifier: Modifier = Modifier) {
    val art = when (style) {
        LibraryVisualStyle.Aurora -> R.drawable.library_aurora_hero
        LibraryVisualStyle.Cinema -> R.drawable.library_cinema_hero
        LibraryVisualStyle.Paper -> R.drawable.library_paper_hero
        LibraryVisualStyle.Walnut -> null
    }
    Box(modifier.clip(RoundedCornerShape(25.dp))) {
        if (art == null) {
            ConceptHeroIllustration(style, motion, Modifier.fillMaxSize())
        } else {
            // Assets were extracted from the user's approved concept renders.
            // Only the banner is rasterized; text, cards and controls below remain live.
            Image(painter = painterResource(art),
                contentDescription = "${style.title} — ${style.tagline}",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (motion && style == LibraryVisualStyle.Aurora) {
                val transition = rememberInfiniteTransition(label = "Aurora banner glow")
                val shift by transition.animateFloat(0f, 1f,
                    infiniteRepeatable(tween(8500, easing = LinearEasing),
                        RepeatMode.Reverse), label = "Light drift")
                Box(Modifier.align(Alignment.TopEnd)
                    .offset(x = (-20 + shift * 30).dp, y = (-58 + shift * 15).dp)
                    .size(118.dp)
                    .background(Brush.radialGradient(listOf(
                        CYAN.copy(alpha = .17f), Color.Transparent
                    )), RoundedCornerShape(80.dp)))
            }
        }
    }
}

@Composable
private fun ConceptHeroIllustration(style: LibraryVisualStyle, motion: Boolean,
    modifier: Modifier) {
    val glow = if (motion) {
        val t = rememberInfiniteTransition(label = "Hero motion")
        val f by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
            label = "Glow drift")
        f
    } else .5f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        when (style) {
            LibraryVisualStyle.Aurora -> {
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF075E68),
                    Color(0xFF103952), Color(0xFF341A59))))
                drawCircle(Brush.radialGradient(listOf(
                    CYAN.copy(alpha = .7f), Color.Transparent)),
                    w * .4f, Offset(w * (.33f + glow * .2f), h * .98f))
                drawCircle(Brush.radialGradient(listOf(
                    VIOLET.copy(alpha = .9f), Color.Transparent)),
                    w * .4f, Offset(w * (.95f - glow * .12f), h * .52f))
                val arc = Path().apply {
                    moveTo(w * .5f, h * 1.2f)
                    cubicTo(w * .74f, h * .4f, w * .83f, -h * .18f, w * 1.18f, h * .08f)
                }
                drawPath(arc, Brush.linearGradient(listOf(CYAN, VIOLET)),
                    style = Stroke(3.dp.toPx()))
                for (i in 0..11)
                    drawCircle(Color.White.copy(alpha = .26f), .8.dp.toPx(),
                        Offset(w * (.57f + (i * 13 % 41) / 100f),
                            h * ((i * 27 % 76) / 100f)))
            }
            LibraryVisualStyle.Cinema -> {
                drawRect(Brush.horizontalGradient(listOf(
                    Color(0xFF29435A), Color(0xFF173047), Color(0xFF071927))))
                val source = Offset(w * .83f, h * .62f)
                val beam = Path().apply {
                    moveTo(source.x, source.y)
                    lineTo(w * .25f, -h * .5f); lineTo(w * .22f, h * 1.5f); close()
                }
                drawPath(beam, Brush.horizontalGradient(listOf(
                    Color(0x008EC0D5), Color(0x6687B5CC), Color(0x008EC0D5))))
                drawCircle(Brush.radialGradient(listOf(
                    Color(0xAAF5D8A6), Color.Transparent)), w * .23f, source)
                for (center in listOf(Offset(w * .78f, h * .32f), Offset(w * .93f, h * .34f))) {
                    drawCircle(Color(0xFFA6A39A), h * .27f, center)
                    drawCircle(Color(0xFF243441), h * .22f, center)
                    for (i in 0..4) {
                        val theta = 2.0 * PI * i / 5.0
                        drawCircle(Color(0xFF5D6E7D), h * .055f,
                            Offset(center.x + cos(theta).toFloat() * h * .12f,
                                center.y + sin(theta).toFloat() * h * .12f))
                    }
                    drawCircle(Color(0xFFA1AFB6), h * .04f, center)
                }
                drawRoundRect(Color(0xFF283943), Offset(w * .78f, h * .53f),
                    Size(w * .18f, h * .38f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                drawCircle(Color(0xFFFFE4B4), h * .06f, source)
            }
            LibraryVisualStyle.Paper -> {
                drawRect(Brush.horizontalGradient(listOf(
                    Color(0xFFF9EAD6), Color(0xFFDEC5A4), Color(0xFF8E6D50))))
                // Reading-table vignette: brass lamp, books and a floral vase.
                drawRoundRect(Color(0xFF664D3A), Offset(w * .69f, h * .7f),
                    Size(w * .29f, h * .17f))
                drawLine(Color(0xFFF4C67B), Offset(w * .73f, h * .72f),
                    Offset(w * .73f, h * .84f), 2.dp.toPx())
                drawRoundRect(Color(0xFF4B5146), Offset(w * .67f, h * .55f),
                    Size(w * .3f, h * .15f))
                drawRoundRect(Color(0xFF845743), Offset(w * .72f, h * .4f),
                    Size(w * .26f, h * .15f))
                drawRoundRect(Color(0xFFF1E1C8), Offset(w * .58f, h * .6f),
                    Size(w * .11f, h * .3f))
                for (i in 0..7) {
                    val flower = Offset(w * (.64f + .05f * cos(i * 1.7).toFloat()),
                        h * (.2f + (i % 3) * .09f))
                    drawLine(Color(0xFFB7A17C),
                        Offset(w * .635f, h * .62f), flower, 1.dp.toPx())
                    drawCircle(Color(0xFFFFEBD4), h * .021f, flower)
                }
                drawLine(Color(0xFFCCA56F), Offset(w * .86f, 0f),
                    Offset(w * .86f, h * .28f), 4.dp.toPx())
                drawOval(Color(0xFF664631), Offset(w * .74f, h * .19f),
                    Size(w * .26f, h * .14f))
                drawCircle(Brush.radialGradient(listOf(
                    Color(0x90FFF1C2), Color.Transparent)),
                    w * .24f, Offset(w * .87f, h * .3f))
            }
            LibraryVisualStyle.Walnut -> drawRect(Brush.horizontalGradient(listOf(
                Color(0xFF593522), Color(0xFF1A1210))))
        }
    }
}

@Composable
private fun rememberConceptScale(book: AudioBook, motion: Boolean): Float {
    var appeared by remember(book.id, motion) { mutableStateOf(!motion) }
    LaunchedEffect(book.id, motion) { appeared = true }
    val v by animateFloatAsState(if (appeared) 1f else .97f,
        animationSpec = tween(360), label = "Book appears")
    return v
}

@Composable
internal fun ConceptProgressBar(progress: ListeningProgress, accent: Color,
    textColor: Color, paper: Boolean = false) {
    if (progress.chapterCount <= 0) {
        Text("Not started", color = textColor.copy(alpha = .75f),
            style = MaterialTheme.typography.labelSmall)
        return
    }
    val fraction = conceptProgress(progress)
    Box(Modifier.fillMaxWidth().height(5.dp)
        .clip(RoundedCornerShape(5.dp))
        .background(textColor.copy(alpha = .22f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
    }
    Text("${(fraction * 100).toInt()}% listened · Chapter ${progress.chapterIndex + 1}",
        color = textColor.copy(alpha = .88f),
        fontFamily = if (paper) FontFamily.Serif else FontFamily.Default,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun AuroraConceptCard(book: AudioBook, progress: ListeningProgress,
    motion: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scale = rememberConceptScale(book, motion)
    Card(
        onClick = onOpen,
        modifier = modifier.graphicsLayer {
            scaleX = scale; scaleY = scale
        },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(CYAN, VIOLET, CYAN))),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF152744)),
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp),
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
            Color(0xFF0F394B), Color(0xFF242146), Color(0xFF35204C))))
            .padding(10.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(CYAN, VIOLET)))) {
                LibraryCover(book, Modifier.fillMaxWidth().aspectRatio(.76f)
                    .padding(2.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(book.title, color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(book.authors, color = Color(0xFFDDD9ED),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(11.dp))
            ConceptProgressBar(progress, VIOLET, Color.White)
        }
    }
}

@Composable
internal fun CinemaConceptCard(book: AudioBook, progress: ListeningProgress,
    motion: Boolean, onOpen: () -> Unit, onPlay: () -> Unit,
    modifier: Modifier = Modifier) {
    val scale = rememberConceptScale(book, motion)
    Card(
        onClick = onOpen,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(.8.dp, Color(0xFF68839B)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1826)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            CinemaBackdrop(book, motion, Modifier.fillMaxSize())
            val conceptScene = when {
                book.title.contains("dracula", ignoreCase = true) ->
                    R.drawable.library_cinema_dracula
                book.title.contains("final empire", ignoreCase = true) ||
                    book.title.contains("mistborn", ignoreCase = true) ->
                    R.drawable.library_cinema_empire
                else -> null
            }
            if (conceptScene != null) {
                Image(painter = painterResource(conceptScene), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight().fillMaxWidth(.61f))
            } else if (book.coverUrl.isNotBlank()) {
                AsyncImage(model = book.coverUrl, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight().fillMaxWidth(.5f)
                        .graphicsLayer { alpha = .24f })
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color(0xF70C1929), Color(0xDE132437),
                Color(0x55122435), Color(0x1510212C)))))
            Column(Modifier.align(Alignment.CenterStart)
                .fillMaxWidth(.78f).padding(start = 18.dp, end = 8.dp)) {
                Text(book.title, color = Color(0xFFFFF8F0),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(book.authors.uppercase(), color = Color(0xFFD5D8E6),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                ConceptProgressBar(progress, VIOLET, Color.White)
            }
            IconButton(
                onClick = onPlay,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xB3223046)),
            ) {
                Icon(Icons.Default.PlayArrow,
                    contentDescription = "Resume ${book.title}",
                    tint = Color.White)
            }
        }
    }
}

@Composable
internal fun PaperConceptCard(book: AudioBook, progress: ListeningProgress,
    motion: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scale = rememberConceptScale(book, motion)
    Card(onClick = onOpen,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(.8.dp, Color.White),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E9)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LibraryCover(book, Modifier.width(94.dp).aspectRatio(.73f))
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(book.title, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = INK, fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.MoreVert, null, Modifier.size(17.dp),
                        tint = INK.copy(alpha = .6f))
                }
                Text(book.authors, color = Color(0xFF805D47),
                    fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(9.dp))
                ConceptProgressBar(progress, Color(0xFF9266A6), INK, paper = true)
            }
        }
    }
}

@Composable
private fun CinemaBackdrop(book: AudioBook, motion: Boolean, modifier: Modifier) {
    val fog = if (motion) {
        val t = rememberInfiniteTransition(label = "Cinema mist")
        val v by t.animateFloat(0f, 1f,
            infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Reverse),
            label = "City fog drift")
        v
    } else .5f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(
            Color(0xFF173A50), Color(0xFF425D70), Color(0xFF0D2133))))
        val moon = Offset(w * .77f, h * .27f)
        drawCircle(Brush.radialGradient(listOf(
            Color(0xB9EAF0F2), Color(0x334C91B4), Color.Transparent)),
            h * .48f, moon)
        drawCircle(Color(0xFFC9E0E6).copy(alpha = .62f), h * .16f, moon)
        val castle = Color(0xFF101D2C)
        for (i in 0..7) {
            val x = w * (.40f + i * .078f)
            val towerH = h * (.22f + ((i * 7) % 5) * .065f)
            drawRect(castle, Offset(x, h * .9f - towerH),
                Size(w * .046f, towerH))
            val spire = Path().apply {
                moveTo(x - w * .012f, h * .9f - towerH)
                lineTo(x + w * .024f, h * .9f - towerH * 1.58f)
                lineTo(x + w * .06f, h * .9f - towerH)
                close()
            }
            drawPath(spire, castle)
            if (i % 2 == 0)
                drawCircle(Color(0xFFC3A374), 1.2.dp.toPx(),
                    Offset(x + w * .023f, h * .9f - towerH * .5f))
        }
        val ground = Path().apply {
            moveTo(0f, h); lineTo(w * .27f, h * .89f)
            lineTo(w * .47f, h * .99f); lineTo(w, h * .76f)
            lineTo(w, h); close()
        }
        drawPath(ground, Color(0xFF0B1926))
        val mist = Path().apply {
            moveTo(w * .32f, h * .83f)
            cubicTo(w * .55f, h * (.62f + fog * .08f),
                w * .78f, h * .95f, w * 1.07f, h * .7f)
        }
        drawPath(mist, Brush.horizontalGradient(listOf(
            Color.Transparent, Color(0xA57BC9E4), Color.Transparent)),
            style = Stroke(19.dp.toPx(), cap = StrokeCap.Round))
        if (book.title.contains("dracula", ignoreCase = true)) {
            for (i in 0..3) {
                val x = w * (.61f + .085f * i); val y = h * (.14f + .07f * (i % 2))
                drawLine(castle, Offset(x, y), Offset(x + w * .018f, y + h * .015f),
                    1.7.dp.toPx())
                drawLine(castle, Offset(x + w * .018f, y + h * .015f),
                    Offset(x + w * .036f, y), 1.7.dp.toPx())
            }
        }
    }
}
