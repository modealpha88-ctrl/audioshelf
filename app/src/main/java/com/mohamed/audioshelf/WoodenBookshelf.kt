package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Canvas
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
    // The cabinet is 2:1. The deck spans 83–89% of its height.
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(2f)) {
        Image(painterResource(R.drawable.lounge_bay), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        val sideInset = maxWidth * .057f
        val topInset = maxHeight * .13f
        val deckInset = maxHeight * .147f
        Row(
            Modifier.fillMaxSize().padding(start = sideInset, end = sideInset,
                top = topInset, bottom = deckInset),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { index ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val book = books.getOrNull(index)
                    if (book != null) AlbumSleeve(book, progressFor(book), motion) { onBook(book) }
                }
            }
        }
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
        Modifier.fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clickable(onClick = onClick),
    ) {
        CollectorSleeve(book, Modifier.fillMaxSize(), onShelf = true)
        val fraction = conceptProgress(progress)
        if (fraction > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
                .height(2.dp).background(Color(0xFFFFCA8B)))
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
