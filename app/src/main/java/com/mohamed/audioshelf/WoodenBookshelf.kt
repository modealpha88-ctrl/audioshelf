package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

internal const val LOUNGE_COLUMNS = 2

/** Opposing, subtle jacket angles make the shelves read as physical objects. */
internal fun shelfSleeveTilt(slot: Int): Float = if (slot % LOUNGE_COLUMNS == 0) -5.5f else 5.5f

/** Show a minimum of four shelves while preserving room for larger libraries. */
internal fun woodenShelfRowCount(bookCount: Int): Int =
    maxOf(4, (bookCount.coerceAtLeast(0) / LOUNGE_COLUMNS) + if (bookCount % LOUNGE_COLUMNS == 0) 0 else 1)

internal val coverSettle = CubicBezierEasing(.16f, 1f, .3f, 1f)
@Composable
fun WoodenShelfRow(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    onBook: (AudioBook) -> Unit,
) {
    // The cabinet is 2:1. The deck spans 83–89% of its height.
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(2f)) {
        Image(painterResource(R.drawable.lounge_bay), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        // Light falls onto the rear wall before the jackets, giving each bay depth.
        Box(Modifier.fillMaxSize().drawWithCache {
            val shade = Brush.verticalGradient(
                0f to Color(0x18080006),
                .44f to Color.Transparent,
                1f to Color(0x38120A09),
            )
            val key = Brush.radialGradient(
                colors = listOf(Color(0x38FFE3B6), Color.Transparent),
                center = Offset(size.width * .43f, size.height * .20f),
                radius = size.width * .55f,
            )
            onDrawBehind {
                drawRect(shade)
                drawRect(key)
            }
        })
        val sideInset = maxWidth * .06f
        val topInset = maxHeight * .134f
        val deckInset = maxHeight * .128f
        Row(
            Modifier.fillMaxSize().padding(start = sideInset, end = sideInset,
                top = topInset, bottom = deckInset),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(LOUNGE_COLUMNS) { index ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val book = books.getOrNull(index)
                    if (book != null) AlbumSleeve(book, progressFor(book), motion, index) { onBook(book) }
                }
            }
        }
        // Preserve the walnut grain while lighting the front bevel separately.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * .113f)
            .drawWithCache {
                val bevel = Brush.verticalGradient(
                    0f to Color(0x45FFCC91),
                    .09f to Color(0x16FFC78D),
                    .23f to Color.Transparent,
                    1f to Color(0x70100604),
                )
                onDrawBehind {
                    val inset = size.width * .022f
                    drawRect(bevel, topLeft = Offset(inset, 0f),
                        size = Size(size.width - inset * 2f, size.height))
                }
            })

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
