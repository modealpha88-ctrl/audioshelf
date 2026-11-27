package com.mohamed.audioshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import com.mohamed.audioshelf.model.AudioBook
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A native rendition of the reference's ragged ink-blob poster reveal.
 * The actual audiobook cover, not an unrelated film image, is revealed.
 * Does not alter the cover file, embedded media, or progress.
 */
@Composable
internal fun VhsInkRevealCover(book: AudioBook, modifier: Modifier = Modifier) {
    val motion = systemMotionEnabled()
    var started by remember(book.sourceId, book.id) { mutableStateOf(false) }
    LaunchedEffect(book.sourceId, book.id) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = if (motion) 1350 else 0,
            delayMillis = if (motion) 180 else 0),
        label = "Original cover ink reveal",
    )
    val blobs = remember {
        listOf(
            .32f to .37f, .72f to .68f, .53f to .13f,
            .19f to .85f, .85f to .28f, .55f to .97f, .49f to .53f,
        )
    }
    val delays = remember { listOf(0f, .12f, .22f, .30f, .36f, .42f, .18f) }
    val painter = rememberShelfCoverPainter(book)
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    val ratio = if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else .67f
    val width = minOf(116.dp, 145.dp * ratio)
    Box(modifier.size(width, width / ratio).shadow(5.dp, RoundedCornerShape(2.dp)).drawWithContent {
        if (reveal >= .998f) {
            drawContent()
        } else {
            val path = Path()
            for (i in blobs.indices) {
                val fraction = ((reveal * 1.35f - delays[i]) /
                    (1.35f - delays[i])).coerceIn(0f, 1f)
                val eased = 1f - (1f - fraction) * (1f - fraction) *
                    (1f - fraction)
                val radius = max(size.width, size.height) * 1.2f * eased
                if (radius < 1f) continue
                val cx = blobs[i].first * size.width
                val cy = blobs[i].second * size.height
                for (step in 0..64) {
                    val angle = (2.0 * PI * step / 64.0).toFloat()
                    // Deterministic fractal-like edge instead of a circular wipe.
                    val wobble = 1f + .075f * sin(angle * 7f + i.toFloat()) +
                        .035f * sin(angle * 13f + i.toFloat() * .8f)
                    val x = cx + radius * wobble * cos(angle)
                    val y = cy + radius * wobble * sin(angle)
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            val content = this
            clipPath(path) { content.drawContent() }
        }
    }) {
        if (drawable != null) {
            Image(painter, "${book.title} cover", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            ShelfTextCover(book, Modifier.fillMaxSize())
        }
    }
}
