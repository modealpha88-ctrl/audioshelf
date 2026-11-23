package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
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

internal val coverSettle = CubicBezierEasing(.16f, 1f, .3f, 1f)
@Composable
fun WoodenShelfRow(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    onBook: (AudioBook) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        val bookHeight = (maxWidth * .52f).coerceIn(136.dp, 228.dp)
        val spineWidth = (maxWidth * .14f).coerceIn(48.dp, 64.dp)
        Column {
            Row(Modifier.fillMaxWidth().height(bookHeight).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center) {
                books.dropLast(1).forEachIndexed { index, book ->
                    Box(Modifier.width(spineWidth).fillMaxHeight(if (index == 0) .97f else 1f)) {
                        WalnutSpine(book, progressFor(book), index, motion) { onBook(book) }
                    }
                }
                books.lastOrNull()?.let { book ->
                    Box(Modifier.width(bookHeight * 1.12f).fillMaxHeight()) {
                        AlbumSleeve(book, progressFor(book), motion, books.lastIndex) { onBook(book) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(12.dp).shadow(5.dp, RoundedCornerShape(2.dp))) {
                Image(painterResource(R.drawable.walnut_plank), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds)
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x66FFCE91)))
            }
        }
    }
}

@Composable
private fun WalnutSpine(book: AudioBook, progress: ListeningProgress, slot: Int,
    motion: Boolean, onOpen: () -> Unit) {
    val cloth = when (book.title.hashCode() and 3) {
        0 -> Color(0xFF234050)
        1 -> Color(0xFF624136)
        2 -> Color(0xFF65313A)
        else -> Color(0xFF415040)
    }
    val fraction = conceptProgress(progress)
    val foil = Color(0xFFFFE5B6)
    Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onOpen)
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(book.title, book.authors).filter { it.isNotBlank() }.joinToString(", ")
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        }.then(walnutSharedSleeve(book, motion))
        .graphicsLayer {
            transformOrigin = TransformOrigin(.5f, 1f)
            rotationZ = if (slot == 0) 3f else 1f
        }.padding(horizontal = 1.dp)
        .shadow(2.dp, RoundedCornerShape(2.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF261E1A), cloth, cloth, Color(0xFF332522)))),
        contentAlignment = Alignment.Center) {
        for (edge in listOf(Alignment.TopCenter, Alignment.BottomCenter)) {
            Box(Modifier.align(edge).padding(vertical = 8.dp).fillMaxWidth(.86f)
                .height(1.dp).background(foil))
        }
        Text(book.title, modifier = Modifier.layout { measurable, limits ->
            val label = measurable.measure(androidx.compose.ui.unit.Constraints(
                maxWidth = (limits.maxHeight - 24.dp.roundToPx()).coerceAtLeast(0),
                maxHeight = limits.maxWidth))
            layout(label.height, label.width) {
                label.placeWithLayer((label.height - label.width) / 2, (label.width - label.height) / 2) {
                    rotationZ = 90f
                }
            }
        }, color = foil, style = MaterialTheme.typography.titleSmall,
            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (fraction > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
                .height(3.dp).background(foil))
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
            book, Modifier.fillMaxSize(), motion = motion, onShelf = true,
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
        if (fraction > 0f) {
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp)
                .fillMaxWidth().height(3.dp).background(Color(0xFF3E2518))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFFFFCA8B)))
            }
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
