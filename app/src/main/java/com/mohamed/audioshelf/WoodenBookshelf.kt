package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

/** Show a minimum of four shelves while preserving room for larger libraries. */
internal fun woodenShelfRowCount(bookCount: Int): Int =
    maxOf(4, (bookCount.coerceAtLeast(0) / 3) + if (bookCount % 3 == 0) 0 else 1)

/**
 * A portrait-friendly, actual three-dimensional bookshelf rather than a banner
 * over ordinary list cards. Each book remains independently clickable.
 * Rendered with native Compose; no remote decorative assets or extra permissions.
 */
@Composable
fun WoodenShelfRow(
    books: List<AudioBook>,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    onBook: (AudioBook) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF54372A), Color(0xFFB58B62),
                        Color(0xFFD1AD83), Color(0xFF7C5338), Color(0xFF3D271F))
                )
            )
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                // Dark recess and a softly-lit wood back panel.
                drawRect(Brush.verticalGradient(listOf(
                    Color(0xC91B1010), Color(0x1D160B06), Color(0x130E0604),
                    Color(0x602B160D)
                )))
                val grain = listOf(.08f, .15f, .26f, .41f, .57f, .69f, .82f, .92f)
                grain.forEachIndexed { index, x ->
                    val stroke = if (index % 3 == 0) 1.7.dp.toPx() else .7.dp.toPx()
                    drawLine(
                        color = Color(0x55F5D9B8),
                        start = androidx.compose.ui.geometry.Offset(size.width * x, 0f),
                        end = androidx.compose.ui.geometry.Offset(
                            size.width * (x + .018f), size.height
                        ),
                        strokeWidth = stroke
                    )
                }
            }
            Row(
                Modifier.fillMaxSize()
                    .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 0.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val book = books.getOrNull(index)
                        if (book != null) {
                            AlbumSleeve(
                                book = book, progress = progressFor(book),
                                motion = motion, onClick = { onBook(book) }
                            )
                        }
                    }
                }
            }
        }
        // Top bevel, front lip, and underside shadow give each shelf depth.
        Box(Modifier.fillMaxWidth().height(5.dp)
            .background(Brush.verticalGradient(listOf(
                Color(0xFFFAE4BF), Color(0xFFAD805C)
            ))))
        Box(Modifier.fillMaxWidth().height(15.dp)
            .background(Brush.verticalGradient(listOf(
                Color(0xFFBA8D61), Color(0xFF946542), Color(0xFF583A2B)
            )))) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color(0x55F7D9A7),
                    androidx.compose.ui.geometry.Offset(0f, size.height * .18f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height * .18f),
                    strokeWidth = 1.dp.toPx())
            }
        }
        Box(Modifier.fillMaxWidth().height(9.dp)
            .background(Brush.verticalGradient(listOf(
                Color(0xBF170A07), Color(0x0020100B)
            ))))
    }
}

@Composable
private fun AlbumSleeve(
    book: AudioBook,
    progress: ListeningProgress,
    motion: Boolean,
    onClick: () -> Unit,
) {
    var isVisible by remember(book.id) { mutableStateOf(!motion) }
    LaunchedEffect(book.id, motion) { isVisible = true }
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else .92f,
        animationSpec = tween(durationMillis = 420),
        label = "Book rising onto shelf",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "Shelf book fade",
    )
    Box(
        Modifier.widthIn(max = 136.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .shadow(8.dp, RoundedCornerShape(topStart = 3.dp, topEnd = 4.dp))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 4.dp))
            .clickable(onClick = onClick)
            .background(Color(0xFF3B2B2C)),
    ) {
        if (book.coverUrl.isBlank()) {
            ShelfTextCover(book, Modifier.fillMaxSize())
        } else {
            SubcomposeAsyncImage(
                model = book.coverUrl,
                contentDescription = "${book.title} cover; open audiobook",
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
                loading = { ShelfTextCover(book, Modifier.fillMaxSize()) },
                error = { ShelfTextCover(book, Modifier.fillMaxSize()) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val fraction = if (progress.chapterCount > 0) {
            val within = if (progress.chapterDurationMs > 0) {
                progress.positionMs.coerceIn(0L, progress.chapterDurationMs).toFloat() /
                    progress.chapterDurationMs
            } else 0f
            ((progress.chapterIndex + within) / progress.chapterCount).coerceIn(0f, 1f)
        } else 0f
        if (fraction > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
                .height(2.dp).background(Color(0xFFFFCA8B)))
        }
    }
}

@Composable
private fun ShelfTextCover(book: AudioBook, modifier: Modifier = Modifier) {
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
