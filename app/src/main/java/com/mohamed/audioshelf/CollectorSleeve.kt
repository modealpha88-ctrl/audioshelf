package com.mohamed.audioshelf

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawWithCache
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
    val conceptPrint = remember(drawable, book.title, onShelf) {
        if (onShelf) (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
            matchingLoungePrint(book.title, it)
        } else null
    }
    val portrait = ratio < .86f
    var open by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(detail, motion, book.id) { open = detail }
    val allowMotion = motion && ValueAnimator.areAnimatorsEnabled()
    val reveal by animateFloatAsState(if (open) .22f else if (onShelf) .20f else .095f,
        tween(if (allowMotion) 420 else 0), label = "Record reveal")
    // Sweep the same studio light over cover art AND approved edition artwork.
    // No bitmap edits: all reflections are composited on top of the real art.
    val lightTravel = if (onShelf && allowMotion) {
        val transition = rememberInfiniteTransition(label = "Collector studio reflection")
        val position by transition.animateFloat(
            initialValue = -.65f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 5800, easing = LinearEasing),
            ),
            label = "Cover reflection travel",
        )
        position
    } else .24f
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val spine = if (onShelf) .18f else .076f
        val pages = if (onShelf) .052f else .024f
        val extra = if (portrait) spine + pages else reveal
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
            if (onShelf && !portrait) {
                Image(painterResource(R.drawable.lounge_record), null,
                    Modifier.align(Alignment.CenterEnd).width(faceW * reveal).height(faceH),
                    contentScale = ContentScale.FillBounds)
            } else if (!portrait) {
                Canvas(Modifier.align(Alignment.CenterEnd).size(minOf(faceW, faceH) * .96f)) {
                    val radius = size.minDimension / 2
                    drawCircle(Brush.linearGradient(listOf(Color.Black, Color.DarkGray, Color.Black)))
                    for (i in 6..23) drawCircle(Color.White.copy(alpha = .10f),
                        radius * i / 24f, style = Stroke(.45.dp.toPx()))
                }
            }
            Row(Modifier.align(Alignment.BottomStart).height(faceH)
                .shadow(2.dp, RoundedCornerShape(1.dp))) {
                if (portrait && onShelf) Image(painterResource(R.drawable.lounge_binding), null,
                    Modifier.width(faceW * spine).fillMaxHeight(), contentScale = ContentScale.FillBounds)
                else if (portrait) Box(Modifier.width(faceW * spine).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF46232A), Color(0xFF874451), Color(0xFF47282C)))))
                val face = Modifier.width(faceW).fillMaxHeight()
                    .border(.7.dp, glow, RoundedCornerShape(1.dp))
                    .then(if (onShelf) Modifier.loungePrintLight(portrait, lightTravel) else Modifier)
                if (conceptPrint != null) Image(painterResource(conceptPrint), book.title, face,
                    contentScale = ContentScale.FillBounds)
                else if (drawable != null) Image(painter, book.title, face, contentScale = ContentScale.Fit)
                else ShelfTextCover(book, face)
                if (portrait && onShelf) Image(painterResource(R.drawable.lounge_pages), null,
                    Modifier.width(faceW * pages).fillMaxHeight(), contentScale = ContentScale.FillBounds)
                else if (portrait) Box(Modifier.width(faceW * pages).fillMaxHeight()
                    .background(Color(0xFFE4CFAB)))
            }
        }
    }
}

/** A soft grazing light and a moving satin sheen, drawn over the actual cover. */
private fun Modifier.loungePrintLight(portrait: Boolean, position: Float) = drawWithCache {
    val ambient = Brush.linearGradient(
        0f to Color(0xFFFFD399).copy(alpha = if (portrait) .14f else .21f),
        .32f to Color(0xFFFFDDAD).copy(alpha = if (portrait) .025f else .06f),
        .68f to Color.Transparent,
        1f to Color.Black.copy(alpha = .14f),
        start = Offset(size.width * .24f, 0f),
        end = Offset(size.width * .76f, size.height),
    )
    val specular = Brush.linearGradient(
        0f to Color.Transparent,
        .38f to Color.Transparent,
        .50f to Color(0xFFFFF6DE).copy(alpha = if (portrait) .11f else .19f),
        .61f to Color.Transparent,
        1f to Color.Transparent,
        start = Offset(size.width * (position - .35f), 0f),
        end = Offset(size.width * (position + .35f), size.height),
    )
    val hinge = Brush.horizontalGradient(
        listOf(Color.Black.copy(alpha = .30f), Color.Transparent),
        endX = (size.width * .033f).coerceAtLeast(1f),
    )
    onDrawWithContent {
        drawContent()
        drawRect(ambient)
        drawRect(specular)
        if (portrait) drawRect(hinge)
        drawLine(Color(0xFFFFDFB1).copy(alpha = .63f), Offset.Zero,
            Offset(size.width, 0f), .85.dp.toPx())
        drawLine(Color.Black.copy(alpha = .55f), Offset(0f, size.height),
            Offset(size.width, size.height), 1.dp.toPx())
    }
}

// These two reference prints are specific to the editions shown in the approved
// concept. A title alone must never replace artwork chosen for another edition.
private fun matchingLoungePrint(title: String, bitmap: Bitmap): Int? {
    val (print, swatches) = when {
        title.equals("Frankenstein", true) -> R.drawable.lounge_frankenstein_print to intArrayOf(
            0x37171a, 0x1e0a13, 0x5c2721, 0x1f0a19, 0xd4a168, 0xb6583c, 0xb25738, 0x551c22,
            0x813831, 0x330913, 0x6a2e2d, 0x563735, 0x0e0309, 0x2e2a2b, 0x1c0e0d, 0x080309)
        title.equals("Dracula", true) -> R.drawable.lounge_dracula_print to intArrayOf(
            0xc8c3c0, 0xa09c9b, 0x696854, 0x574946, 0x55493d, 0x736950, 0xb98b5a, 0x976a57,
            0x34292d, 0x272528, 0x222025, 0x271d25, 0x29272a, 0x2f2629, 0x6c4c3f, 0x372426)
        else -> return null
    }
    val ratio = bitmap.width.toFloat() / bitmap.height
    if (print == R.drawable.lounge_dracula_print && ratio !in .94f..1.06f ||
        print == R.drawable.lounge_frankenstein_print && ratio !in .60f.. .73f) return null
    var difference = 0
    for (i in swatches.indices) {
        val sample = bitmap.getPixel(bitmap.width * (2 * (i % 4) + 1) / 8,
            bitmap.height * (2 * (i / 4) + 1) / 8)
        for (shift in 0..16 step 8) {
            difference += kotlin.math.abs((sample shr shift and 255) - (swatches[i] shr shift and 255))
        }
    }
    return print.takeIf { difference <= swatches.size * 3 * 19 }
}
