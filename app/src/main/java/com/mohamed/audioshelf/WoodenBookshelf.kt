package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

internal fun shelfSleeveTilt(slot: Int): Float = if (slot % 2 == 0) -5.5f else 5.5f

internal fun woodenShelfRowCount(bookCount: Int, columns: Int = 3): Int =
    if (bookCount <= 0) 0 else 1 + (bookCount - 1) / columns

// Outline of the plank in the 960 × 540 shelf photograph. Clipping the
// row to the wood lets the library's continuous wall show through.
private val floatingPlank = GenericShape { size, _ ->
    val x = size.width / 960f
    val y = size.height / 540f
    moveTo(52 * x, 369 * y)
    lineTo(907 * x, 369 * y)
    lineTo(940 * x, 397 * y)
    lineTo(940 * x, 417 * y)
    lineTo(20 * x, 417 * y)
    lineTo(20 * x, 397 * y)
    close()
}

internal val coverSettle = CubicBezierEasing(.16f, 1f, .3f, 1f)
@Composable
fun WoodenShelfRow(
    books: List<AudioBook>,
    coverOnLeft: Boolean,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    onBook: (AudioBook) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sceneHeight = (maxWidth * .72f).coerceIn(186.dp, 320.dp)
        // The photographed plank occupies 68–77% of the scene. Place the
        // bottom edges midway across its top surface, in front of the wall.
        val baseline = sceneHeight * .722f
        val spineWidth = (maxWidth * .124f).coerceIn(48.dp, 68.dp)
        Box(Modifier.fillMaxWidth().height(sceneHeight)) {
            Image(painterResource(R.drawable.floating_walnut), null,
                Modifier.fillMaxSize().shadow(9.dp, floatingPlank, clip = true),
                contentScale = ContentScale.FillBounds)
            Row(Modifier.fillMaxWidth().height(baseline).padding(start = 20.dp, end = 14.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                val slots = if (coverOnLeft) books.indices.reversed() else books.indices
                for (index in slots) {
                    val book = books[index]
                    androidx.compose.runtime.key(book.sourceId, book.id) {
                        if (index == books.lastIndex) {
                            Box(Modifier.width(baseline * 1.12f).fillMaxHeight(.93f)) {
                                AlbumSleeve(book, progressFor(book), motion, index) { onBook(book) }
                            }
                        } else {
                            Box(Modifier.width(spineWidth).fillMaxHeight(if (index == 0) .91f else .96f)) {
                                WalnutSpine(book, progressFor(book), index, coverOnLeft, motion) { onBook(book) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WalnutSpine(book: AudioBook, progress: ListeningProgress, slot: Int,
    coverOnLeft: Boolean, motion: Boolean, onOpen: () -> Unit) {
    val cloth = remember(book.title) {
        val title = book.title.lowercase()
        when {
            "harry potter" in title -> R.drawable.cloth_navy
            "frankenstein" in title -> R.drawable.cloth_green
            "dracula" in title -> R.drawable.cloth_burgundy
            else -> listOf(R.drawable.cloth_umber, R.drawable.cloth_burgundy, R.drawable.cloth_green,
                R.drawable.cloth_navy)[title.hashCode() and 3]
        }
    }
    val fraction = conceptProgress(progress)
    val foil = when (cloth) {
        R.drawable.cloth_burgundy -> Color(0xFFFFF1DA)
        R.drawable.cloth_green -> Color(0xFFEDD190)
        else -> Color(0xFFFFDF96)
    }
    val lettering = remember(book.title) {
        val split = Regex("""(?i)\s+(?=(?:complete|unabridged) audiobook collection\b)|:\s+""")
        split.split(book.title, limit = 2)
    }
    Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onOpen)
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(book.title, book.authors).filter { it.isNotBlank() }.joinToString(", ")
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }.then(walnutSharedSleeve(book, motion))
        .graphicsLayer {
            transformOrigin = TransformOrigin(.5f, 1f)
            rotationZ = (if (slot == 0) 5f else 1.7f) * (if (coverOnLeft) -1f else 1f)
        }.padding(horizontal = 1.dp)
        .shadow(3.dp, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center) {
        Image(painterResource(cloth), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.layout { measurable, limits ->
            val label = measurable.measure(androidx.compose.ui.unit.Constraints(
                maxWidth = (limits.maxHeight - (if (book.authors.isBlank()) 40.dp else 86.dp).roundToPx()).coerceAtLeast(0),
                maxHeight = limits.maxWidth))
            layout(label.height, label.width) {
                label.placeWithLayer((label.height - label.width) / 2, (label.width - label.height) / 2) {
                    rotationZ = 90f
                }
            }
        }) {
            Text(lettering.first(), color = foil, style = MaterialTheme.typography.titleMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                maxLines = if (lettering.size == 1 && book.authors.isBlank()) 2 else 1,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            lettering.getOrNull(1)?.let {
                Text(it, color = foil, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (book.authors.isNotBlank()) {
            Text(book.authors, Modifier.align(Alignment.BottomCenter).padding(horizontal = 3.dp, vertical = 12.dp),
                color = foil, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
        if (fraction > 0f && fraction < 1f) {
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.TopEnd)
                .padding(end = 5.dp).width(7.dp).height(15.dp)) {
                val ribbon = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2, size.height * .73f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(ribbon, Color(0xFFD6A552))
            }
        }
    }
}

@Composable
private fun AlbumSleeve(
    book: AudioBook,
    progress: ListeningProgress,
    motion: Boolean,
    slot: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val settle by animateFloatAsState(
        if (motion && pressed) 1f else 0f,
        tween(if (!motion) 0 else if (pressed) 110 else 290, easing = coverSettle),
        label = "Sleeve selection",
    )
    val fraction = conceptProgress(progress)
    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "Open audiobook", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(book.title, book.authors).filter { it.isNotBlank() }.joinToString(", ")
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        CollectorSleeve(
            book, Modifier.fillMaxSize(), motion = motion, onShelf = true, listened = fraction, pressed = pressed,
            pose = Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                val scale = 1f - settle * .035f
                scaleX = scale
                scaleY = scale
                cameraDistance = 160.dp.toPx()
                rotationY = shelfSleeveTilt(slot) * (1f - settle * .6f)
                rotationX = -2f + 4f * settle
            },
        )
        if (focused || pressed) {
            Box(Modifier.matchParentSize().padding(2.dp)
                .border(1.dp, Color(0xFFD6AD77).copy(alpha = if (focused) .95f else .42f)))
        }

    }
}

@Composable
internal fun ShelfTextCover(book: AudioBook, modifier: Modifier = Modifier) {
    val tones = when ((book.title.hashCode().toLong() and 0x7fffffffL) % 4L) {
        0L -> listOf(Color(0xFF4D5B5A), Color(0xFF1B343E))
        1L -> listOf(Color(0xFF725750), Color(0xFF352833))
        2L -> listOf(Color(0xFF465170), Color(0xFF242B44))
        else -> listOf(Color(0xFF68715D), Color(0xFF343E36))
    }
    Box(modifier.background(Brush.verticalGradient(tones))
        .padding(horizontal = 6.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(book.title, color = Color(0xFFFFF2DD),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(book.authors, color = Color(0xFFE1CEB4),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
