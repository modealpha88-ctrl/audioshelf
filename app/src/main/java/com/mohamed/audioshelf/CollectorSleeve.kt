package com.mohamed.audioshelf

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.mohamed.audioshelf.model.AudioBook

// Some catalogues publish a full portrait jacket inside a wide white thumbnail.
// Trim only uniform margins around an inset rectangle with four distinct edges.
internal object CoverMatte : Transformation {
    override val cacheKey = "collector-matte-v1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 64 || h < 32 || w < h * 1.12f) return input
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        val bounds = coverMatteBounds(pixels, w, h) ?: return input
        return Bitmap.createBitmap(input, bounds.left, bounds.top, bounds.width, bounds.height)
    }
}

internal data class MatteBounds(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun coverMatteBounds(pixels: IntArray, w: Int, h: Int): MatteBounds? {
    if (w < 64 || h < 32 || w < h * 1.12f) return null
    fun ink(x: Int, y: Int): Boolean {
        val c = pixels[y * w + x]
        return c ushr 24 > 48 && minOf(c shr 16 and 255, c shr 8 and 255, c and 255) < 225
    }
    var left = w
    var right = -1
    var top = h
    var bottom = -1
    for (y in 0 until h) for (x in 0 until w) {
        if (ink(x, y)) {
            left = minOf(x, left)
            right = maxOf(x, right)
            top = minOf(y, top)
            bottom = maxOf(y, bottom)
        }
    }
    val cw = right - left + 1
    val ch = bottom - top + 1
    if (cw < 12 || ch < 16 || left < w / 12 || w - right < w / 12 ||
        top < 1 || bottom >= h - 1 || cw.toFloat() / ch !in .42f..1.12f) return null
    val inset = minOf(3, cw / 8, ch / 8)
    fun horizontal(y: Int) = (left..right).count { ink(it, y) } > cw * .72f
    fun vertical(x: Int) = (top..bottom).count { ink(x, it) } > ch * .72f
    return if (horizontal(top + inset) && horizontal(bottom - inset) &&
        vertical(left + inset) && vertical(right - inset))
        MatteBounds(left, top, cw, ch) else null
}

@Composable
internal fun CollectorSleeve(book: AudioBook, modifier: Modifier, glow: Color = Color.Transparent,
    detail: Boolean = false, motion: Boolean = false, onShelf: Boolean = false) {
    val context = LocalContext.current
    val request = remember(context, book.coverUrl) {
        ImageRequest.Builder(context).data(book.coverUrl.ifBlank { null }).size(480, 480)
            .scale(coil.size.Scale.FIT).allowHardware(false).transformations(CoverMatte).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    val ratio = if (drawable != null && drawable.intrinsicHeight > 0)
        drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else .67f
    val portrait = ratio < .86f
    var open by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(detail, motion, book.id) { open = detail }
    val reveal by animateFloatAsState(if (open) .22f else .095f,
        tween(if (motion) 420 else 0), label = "Record reveal")
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val extra = if (portrait) .10f else reveal
        val faceW = minOf(maxWidth / (1f + extra), maxHeight * ratio)
        val faceH = faceW / ratio
        Box(Modifier.width(faceW * (1f + extra)).height(faceH).drawBehind {
            if (onShelf) {
                val contact = Offset(size.width * .52f, size.height)
                val radius = size.width * .54f
                if (radius > 0f) withTransform({
                    scale(1f, .075f, contact)
                }) {
                    drawCircle(Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = .84f), Color.Transparent),
                        contact, radius), radius, contact)
                }
            }
        }) {
            if (!portrait) {
                Canvas(Modifier.align(Alignment.CenterEnd).size(minOf(faceW, faceH) * .96f)) {
                    val radius = size.minDimension / 2
                    drawCircle(Brush.linearGradient(listOf(Color.Black, Color.DarkGray, Color.Black)))
                    for (i in 6..23) drawCircle(Color.White.copy(alpha = .10f),
                        radius * i / 24f, style = Stroke(.45.dp.toPx()))
                }
            }
            Row(Modifier.align(Alignment.BottomStart).height(faceH)
                .shadow(2.dp, RoundedCornerShape(1.dp))) {
                if (portrait) Box(Modifier.width(faceW * .076f).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF46232A), Color(0xFF874451), Color(0xFF47282C)))))
                val face = Modifier.width(faceW).fillMaxHeight()
                    .border(.7.dp, glow, RoundedCornerShape(1.dp))
                if (drawable != null) Image(painter, book.title, face, contentScale = ContentScale.Fit)
                else ShelfTextCover(book, face)
                if (portrait) Box(Modifier.width(faceW * .024f).fillMaxHeight()
                    .background(Color(0xFFE4CFAB)))
            }
        }
    }
}
