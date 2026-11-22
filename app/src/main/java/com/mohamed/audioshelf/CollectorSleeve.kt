package com.mohamed.audioshelf

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    detail: Boolean = false, motion: Boolean = false, onShelf: Boolean = false,
    walnut: Boolean = onShelf, pose: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, book.coverUrl) {
        ImageRequest.Builder(context).data(book.coverUrl.ifBlank { null }).size(480, 480)
            .scale(coil.size.Scale.FIT).allowHardware(false).transformations(CoverMatte).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    val ratio = if (drawable != null && drawable.intrinsicHeight > 0)
        drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else .67f
    val conceptPrint = remember(drawable, book.title, walnut) {
        if (walnut) (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
            matchingLoungePrint(book.title, it)
        } else null
    }
    val portrait = ratio < .86f
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val allowMotion = motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val extension = if (detail) .24f else if (onShelf) .23f else .095f
    val reveal = remember(book.sourceId, book.id, allowMotion) {
        Animatable(if (detail && allowMotion) .10f else extension)
    }
    LaunchedEffect(reveal, extension) {
        if (allowMotion) reveal.animateTo(extension, tween(430, easing = coverSettle))
        else reveal.snapTo(extension)
    }
    val lightTravel: State<Float>? = if (walnut && allowMotion) {
        rememberInfiniteTransition(label = "Collector studio reflection").animateFloat(
            initialValue = -.45f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(tween(8200, easing = LinearEasing)),
            label = "Cover reflection travel",
        )
    } else null
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val spine = if (walnut) .18f else .076f
        val pages = if (walnut) .052f else .024f
        val extra = if (portrait) spine + pages else extension
        val faceW = minOf(maxWidth / (1f + extra), maxHeight * ratio)
        val faceH = faceW / ratio
        Box(Modifier.width(faceW * (1f + extra)).height(faceH)
            .then(if (onShelf) Modifier.sleeveContactShadow().clearAndSetSemantics {} else Modifier)
            .then(walnutSharedSleeve(book, walnut && motion))
            .then(pose)) {
            if (!portrait && walnut) {
                // The covered part of the disc is drawn under the jacket.
                Canvas(Modifier.matchParentSize()) {
                    val radius = minOf(faceW.toPx(), size.height) * .48f
                    val uncovered = faceW.toPx() * reveal.value
                    val center = Offset(faceW.toPx() + uncovered - radius, size.height * .5f)
                    drawCircle(Brush.sweepGradient(
                        colors = listOf(Color(0xFF101014), Color(0xFF555051), Color(0xFF141317),
                            Color(0xFF252122), Color(0xFF101014)), center = center), radius, center)
                    for (groove in 9..32) {
                        drawCircle(Color.White.copy(alpha = if (groove % 5 == 0) .20f else .09f),
                            radius * groove / 33f, center, style = Stroke(.45.dp.toPx()))
                    }
                    drawCircle(Color(0xFF827164), radius, center, style = Stroke(.65.dp.toPx()))
                    drawCircle(Color(0xFF755343), radius * .32f, center)
                    drawCircle(Color.Black, radius * .037f, center)
                }
            } else if (!portrait) {
                Canvas(Modifier.align(Alignment.CenterEnd).size(minOf(faceW, faceH) * .96f)) {
                    val radius = size.minDimension / 2
                    drawCircle(Brush.linearGradient(listOf(Color.Black, Color.DarkGray, Color.Black)))
                    for (i in 6..23) drawCircle(Color.White.copy(alpha = .10f),
                        radius * i / 24f, style = Stroke(.45.dp.toPx()))
                }
            }
            if (portrait && walnut) {
                Canvas(Modifier.matchParentSize()) {
                    val foreEdge = faceW.toPx() * (1f + spine)
                    val pageStack = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(4.dp.toPx(), -3.dp.toPx())
                        lineTo(foreEdge, -3.dp.toPx())
                        lineTo(size.width, 0f)
                        close()
                    }
                    drawPath(pageStack, Brush.horizontalGradient(
                        listOf(Color(0xFF523626), Color(0xFFD2AA75), Color(0xFF947354)),
                        endX = size.width.coerceAtLeast(1f),
                    ))
                }
            }
            Row(Modifier.align(Alignment.BottomStart).height(faceH)
                .shadow(2.dp, RoundedCornerShape(1.dp))) {
                if (portrait && walnut) Image(painterResource(R.drawable.lounge_binding), null,
                    Modifier.width(faceW * spine).fillMaxHeight().bindingLight(), contentScale = ContentScale.FillBounds)
                else if (portrait) Box(Modifier.width(faceW * spine).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF46232A), Color(0xFF874451), Color(0xFF47282C)))))
                val face = Modifier.width(faceW).fillMaxHeight()
                    .border(.7.dp, glow, RoundedCornerShape(1.dp))
                    .then(if (walnut) Modifier.loungePrintLight(portrait, lightTravel) else Modifier)
                if (conceptPrint != null) Image(painterResource(conceptPrint), book.title, face,
                    contentScale = ContentScale.Fit)
                else if (drawable != null) Image(painter, book.title, face, contentScale = ContentScale.Fit)
                else ShelfTextCover(book, face)
                if (portrait && walnut) Image(painterResource(R.drawable.lounge_pages), null,
                    Modifier.width(faceW * pages).fillMaxHeight().pageEdge(), contentScale = ContentScale.FillBounds)
                else if (portrait) Box(Modifier.width(faceW * pages).fillMaxHeight()
                    .background(Color(0xFFE4CFAB)))
            }
        }
    }
}

private fun Modifier.sleeveContactShadow() = drawWithCache {
    val center = Offset(size.width * .51f, size.height - 1.dp.toPx())
    val radius = size.width * .64f
    val shadow = Brush.radialGradient(
        0f to Color(0xF0000000), .60f to Color(0x8C0A0300), 1f to Color.Transparent,
        center = center, radius = radius.coerceAtLeast(1f),
    )
    onDrawBehind {
        withTransform({ scale(1f, .095f, center) }) { drawCircle(shadow, radius, center) }
    }
}

private fun Modifier.bindingLight() = drawWithCache {
    val bands = floatArrayOf(.09f, .12f, .86f, .89f)
    val light = Brush.horizontalGradient(
        0f to Color(0x990B0706), .37f to Color(0x27FFE3B5), 1f to Color(0x950B0605),
    )
    onDrawWithContent {
        drawContent()
        drawRect(light)
        for (position in bands) {
            val y = size.height * position
            drawLine(Color(0x9ADCB988), Offset(0f, y), Offset(size.width, y), .65.dp.toPx())
        }
    }
}

private fun Modifier.pageEdge() = drawWithCache {
    val shade = Brush.horizontalGradient(
        0f to Color(0x99140802), .36f to Color(0x23FFE6B2), 1f to Color(0x660B0704),
    )
    onDrawWithContent {
        drawContent()
        drawRect(shade)
    }
}

/** A soft grazing light and a moving satin sheen, drawn over the actual cover. */
private fun Modifier.loungePrintLight(portrait: Boolean, position: State<Float>?) = drawWithCache {
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
        start = Offset(-size.width * .35f, 0f),
        end = Offset(size.width * .35f, size.height),
    )
    val hinge = Brush.horizontalGradient(
        listOf(Color.Black.copy(alpha = .30f), Color.Transparent),
        endX = (size.width * .033f).coerceAtLeast(1f),
    )
    onDrawWithContent {
        drawContent()
        drawRect(ambient)
        clipRect {
            val travel = ((position?.value ?: .24f) - .5f) * size.width * 2f
            translate(left = travel) {
                drawRect(specular, topLeft = Offset(-size.width * 2f, 0f),
                    size = DrawSize(size.width * 5f, size.height))
            }
        }
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
