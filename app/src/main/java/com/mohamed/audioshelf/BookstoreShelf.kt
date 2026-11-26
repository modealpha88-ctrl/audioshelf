package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress

/**
 * Concept C: a physical bookstore shelf, rather than two album covers on a plank.
 * The single face-out slot is assigned only to a book with existing artwork.
 * Empty or unknown cover URLs never cause an arbitrary fake edition to be shown.
 */
internal fun bookstoreFaceOutIndex(books: List<AudioBook>, faceOnLeft: Boolean): Int {
    if (books.isEmpty()) return -1
    val withArtwork = books.indices.filter { books[it].coverUrl.isNotBlank() }
    return if (faceOnLeft) withArtwork.firstOrNull() ?: -1
        else withArtwork.lastOrNull() ?: -1
}

/** Long metadata cannot fit on a physical spine; full titles remain in accessibility semantics. */
internal fun bookstoreSpineTitle(title: String): String =
    title.replace(Regex("""(?i)\s+(?:complete\s+)?audiobook\s+collection\b.*$"""), "")
        .replace(Regex("""(?i)\s*\((?:unabridged|audiobook)\)"""), "")
        .trim().ifBlank { title }

private fun bookstoreCloth(book: AudioBook): Int {
    val name = book.title.lowercase()
    return when {
        "harry potter" in name -> R.drawable.cloth_navy
        "peter pan" in name || "dracula" in name -> R.drawable.cloth_burgundy
        "frankenstein" in name -> R.drawable.cloth_green
        else -> listOf(R.drawable.cloth_navy, R.drawable.cloth_umber,
            R.drawable.cloth_green, R.drawable.cloth_burgundy)[(name.hashCode() and Int.MAX_VALUE) % 4]
    }
}

private fun bookstoreInk(texture: Int): Color =
    if (texture == R.drawable.cloth_umber) Color(0xFFF8E8C4) else Color(0xFFFFD99E)

private fun bookDescription(book: AudioBook): String =
    listOf(book.title, book.authors.takeUnless { it.isBlank() || it.startsWith("Local ", true) })
        .filterNotNull().joinToString(", ")

@Composable
internal fun BookstoreShelfRow(
    books: List<AudioBook>,
    rowIndex: Int,
    progressFor: (AudioBook) -> ListeningProgress,
    motion: Boolean,
    onBook: (AudioBook) -> Unit,
) {
    if (books.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val shelfHeight = (maxWidth * .555f).coerceIn(180.dp, 242.dp)
        val bookZoneHeight = shelfHeight - 20.dp
        val spineSlotWidth = (maxWidth * .13f).coerceIn(48.dp, 60.dp)
        val faceOnLeft = rowIndex % 2 == 1
        val faceIndex = bookstoreFaceOutIndex(books, faceOnLeft)
        val faceSlotWidth = (maxWidth * .41f).coerceIn(106.dp, 164.dp)
        val ordered = if (faceIndex < 0) books.indices.toList()
            else if (faceOnLeft) listOf(faceIndex) + books.indices.filterNot { it == faceIndex }
            else books.indices.filterNot { it == faceIndex } + faceIndex

        Box(Modifier.fillMaxWidth().height(shelfHeight)) {
            // One restrained shared contact shadow. No shadows that make books float.
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
                .fillMaxWidth().height(22.dp)
                .shadow(12.dp, RoundedCornerShape(3.dp))
                .background(Color(0x55442B1B)))

            Row(
                Modifier.fillMaxWidth().height(bookZoneHeight)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                for ((position, index) in ordered.withIndex()) {
                    val book = books[index]
                    key(book.sourceId, book.id) {
                        if (index == faceIndex) {
                            Box(Modifier.width(faceSlotWidth).height(bookZoneHeight)) {
                                BookstoreFaceOut(book, progressFor(book), motion) { onBook(book) }
                            }
                        } else {
                            Box(Modifier.width(spineSlotWidth).height(bookZoneHeight)) {
                                BookstoreSpine(book, progressFor(book), position, motion) { onBook(book) }
                            }
                        }
                    }
                }
            }
            // Continuous floating wood slab: its bevel is above the book's contact shadow.
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 9.dp)
                .fillMaxWidth().height(20.dp).shadow(5.dp, RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(
                    Color(0xFFAB7850), Color(0xFF74462B), Color(0xFF4B2B1E),
                )))) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp)
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF926140), Color(0xFFE1B485), Color(0xFF956243),
                    ))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                    .background(Color(0xFF3C2219).copy(alpha = .7f)))
            }
        }
    }
}

@Composable
private fun BookstoreSpine(
    book: AudioBook, progress: ListeningProgress, position: Int,
    motion: Boolean, onOpen: () -> Unit,
) {
    val texture = remember(book.title) { bookstoreCloth(book) }
    val ink = bookstoreInk(texture)
    val fraction = conceptProgress(progress)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressShift by animateFloatAsState(
        if (motion && pressed) -2f else 0f,
        tween(if (motion) 145 else 0), label = "Spine touch",
    )
    val heightFraction = when ((book.title.hashCode() and Int.MAX_VALUE) % 5) {
        0 -> .88f
        1 -> .91f
        2 -> .94f
        3 -> .96f
        else -> .90f
    }
    val lean = when (position % 4) {
        0 -> -2.2f
        1 -> .6f
        2 -> 2.1f
        else -> -.8f
    }

    Box(Modifier.fillMaxSize().clickable(
        interactionSource = interaction, indication = null,
        role = Role.Button, onClickLabel = "Open audiobook", onClick = onOpen,
    ).semantics(mergeDescendants = true) {
        contentDescription = bookDescription(book)
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }, contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.padding(horizontal = 3.dp).fillMaxWidth().fillMaxHeight(heightFraction)
            .graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                rotationZ = lean + pressShift
            }
            .shadow(5.dp, RoundedCornerShape(2.dp))
            .background(Color(0xFF30241F))) {
            Image(painterResource(texture), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color.Black.copy(alpha = .26f), Color.Transparent,
                Color.White.copy(alpha = .11f), Color.Black.copy(alpha = .22f),
            ))))
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 5.dp)
                .padding(top = 10.dp).height(1.dp).background(ink.copy(alpha = .65f)))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 5.dp)
                .padding(bottom = 11.dp).height(1.dp).background(ink.copy(alpha = .65f)))

            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.layout { measurable, limits ->
                    val text = measurable.measure(Constraints(
                        maxWidth = (limits.maxHeight - 38.dp.roundToPx()).coerceAtLeast(1),
                        maxHeight = (limits.maxWidth - 3.dp.roundToPx()).coerceAtLeast(1),
                    ))
                    layout(text.height, text.width) {
                        text.placeWithLayer(
                            (text.height - text.width) / 2,
                            (text.width - text.height) / 2,
                        ) { rotationZ = 90f }
                    }
                }.align(Alignment.Center)) {
                Text(bookstoreSpineTitle(book.title),
                    color = ink, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val author = book.authors.takeUnless {
                    it.isBlank() || it.startsWith("Local ", true)
                }
                if (author != null) {
                    Text(author, color = ink.copy(alpha = .83f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (fraction > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height(3.dp).background(Color(0x55301A0E))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .background(ink))
                }
            }
        }
    }
}

@Composable
private fun BookstoreFaceOut(
    book: AudioBook, progress: ListeningProgress, motion: Boolean, onOpen: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(context, book.coverUrl) {
        ImageRequest.Builder(context).data(book.coverUrl.ifBlank { null })
            .size(560).allowHardware(false).transformations(CoverMatte).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    // Do not crop the words off square audiobook art or stretch portrait jackets.
    val ratio = if (drawable != null && drawable.intrinsicHeight > 0)
        (drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight).coerceIn(.5f, 1.15f)
        else .70f
    val fraction = conceptProgress(progress)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val selectScale by animateFloatAsState(
        if (motion && pressed) .96f else 1f,
        tween(if (motion) 165 else 0), label = "Book cover touch",
    )

    BoxWithConstraints(Modifier.fillMaxSize().clickable(
        interactionSource = interaction, indication = null,
        role = Role.Button, onClickLabel = "Open audiobook", onClick = onOpen,
    ).semantics(mergeDescendants = true) {
        contentDescription = bookDescription(book)
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }, contentAlignment = Alignment.BottomCenter) {
        val coverHeight = minOf(maxHeight * .95f, (maxWidth - 8.dp) / ratio)
        val coverWidth = coverHeight * ratio
        Box(Modifier.width(coverWidth + 6.dp).height(coverHeight)
            .graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                scaleX = selectScale
                scaleY = selectScale
            }
            .shadow(8.dp, RoundedCornerShape(2.dp))
            .background(Color(0xFFE4D5B9))) {
            Box(Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(
                    Color(0xFF997B5A), Color(0xFFF0E1C5), Color(0xFFB29B79),
                ))))
            Box(Modifier.fillMaxHeight().width(coverWidth)
                .background(Color(0xFF463329))) {
                if (drawable != null) {
                    Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    ShelfTextCover(book, Modifier.fillMaxSize())
                }
                Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(
                        Color.Black.copy(alpha = .32f), Color.Transparent,
                    ))))
                if (fraction > 0f) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(4.dp).background(Color(0xA6221915))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                            .background(Color(0xFFFFD799)))
                    }
                }
            }
        }
    }
}
