package com.mohamed.audioshelf

import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A publisher's real jacket is usually NOT a scan of the physical spine.
 * Preserve the exact jacket on face-out books; derive a quiet, print-like cloth
 * color from it for spines rather than squeezing/cropping cover illustrations.
 */
internal data class BookstoreSpineColors(
    val cloth: Color,
    val ink: Color,
    val hasCoverPalette: Boolean,
)

internal fun bookstoreSpineMotif(title: String): Int =
    (title.lowercase(java.util.Locale.ROOT).hashCode() and Int.MAX_VALUE) % 5

internal fun bookstoreSpineColors(
    title: String,
    drawable: Drawable?,
    clothTexture: Int,
): BookstoreSpineColors {
    val fallback = when (clothTexture) {
        R.drawable.cloth_umber -> Color(0xFF78523B)
        R.drawable.cloth_green -> Color(0xFF304E40)
        R.drawable.cloth_burgundy -> Color(0xFF6A303C)
        else -> Color(0xFF233B54)
    }
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return BookstoreSpineColors(
        fallback, Color(0xFFF8E7C9), false,
    )
    return runCatching {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0)
        val hsv = FloatArray(3)
        var xSum = 0.0
        var ySum = 0.0
        var saturation = 0.0
        var weightSum = 0.0
        // Sparse sampling: no bitmap allocations or full-image scans on the UI thread.
        for (row in 1..7) for (col in 1..5) {
            val pixel = bitmap.getPixel(
                (w * col / 6).coerceIn(0, w - 1),
                (h * row / 8).coerceIn(0, h - 1),
            )
            if (AndroidColor.alpha(pixel) < 200) continue
            AndroidColor.colorToHSV(pixel, hsv)
            if (hsv[1] < .17f || hsv[2] < .13f || hsv[2] > .94f) continue
            val weight = (hsv[1] * (.5f + hsv[2])).toDouble()
            val radians = hsv[0] * PI / 180.0
            xSum += cos(radians) * weight
            ySum += sin(radians) * weight
            saturation += hsv[1] * weight
            weightSum += weight
        }
        if (weightSum < .22) return@runCatching BookstoreSpineColors(
            fallback, Color(0xFFF8E7C9), false,
        )
        val hue = ((atan2(ySum, xSum) * 180.0 / PI + 360.0) % 360.0).toFloat()
        val sat = (saturation / weightSum).toFloat().coerceIn(.26f, .65f)
        // A few soft paper bindings break up uniform dark rows, as on a real shelf.
        val lightBinding = (title.hashCode() and Int.MAX_VALUE) % 5 == 1
        val adjusted = floatArrayOf(
            hue,
            if (lightBinding) (sat * .45f).coerceIn(.16f, .31f) else sat,
            if (lightBinding) .80f else .36f,
        )
        BookstoreSpineColors(
            Color(AndroidColor.HSVToColor(adjusted)),
            if (lightBinding) Color(0xFF332B25) else Color(0xFFF8E7C9),
            true,
        )
    }.getOrElse {
        BookstoreSpineColors(fallback, Color(0xFFF8E7C9), false)
    }
}

/** Original decorative spine treatment: borders, flourishes and title remain readable. */
@Composable
internal fun BookstorePrintedSpine(
    title: String,
    texture: Int,
    palette: BookstoreSpineColors,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(palette.cloth)) {
        // Old cloth assets only contribute subtle woven grain, never a conflicting color.
        Image(
            painter = painterResource(texture),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = if (palette.hasCoverPalette) .09f else .20f,
        )
        val motif = bookstoreSpineMotif(title)
        Canvas(Modifier.fillMaxSize()) {
            val pad = 5.dp.toPx()
            if (size.width <= pad * 2 || size.height <= pad * 4) return@Canvas
            val ink = palette.ink
            val line = .8.dp.toPx()
            val center = size.width / 2
            val top = size.height * .13f
            val bottom = size.height * .87f

            drawRect(
                ink.copy(alpha = .37f),
                topLeft = Offset(pad, pad),
                size = Size(size.width - 2 * pad, size.height - 2 * pad),
                style = Stroke(width = line),
            )
            // A double-edged border gives cloth hardbacks a believable printed spine.
            drawLine(ink.copy(alpha = .25f), Offset(pad + 3.dp.toPx(), pad),
                Offset(pad + 3.dp.toPx(), size.height - pad), line)
            drawLine(ink.copy(alpha = .25f), Offset(size.width - pad - 3.dp.toPx(), pad),
                Offset(size.width - pad - 3.dp.toPx(), size.height - pad), line)

            listOf(top, bottom).forEach { y ->
                when (motif) {
                    0 -> {
                        // Eight-point star with a fine centre dot.
                        for (n in 0 until 4) {
                            val angle = n * PI / 4
                            val dx = cos(angle).toFloat() * 11.dp.toPx()
                            val dy = sin(angle).toFloat() * 11.dp.toPx()
                            drawLine(ink.copy(alpha = .76f),
                                Offset(center - dx, y - dy), Offset(center + dx, y + dy), line)
                        }
                        drawCircle(ink, radius = 2.dp.toPx(), center = Offset(center, y))
                    }
                    1 -> {
                        // Botanical flowers inspired by printed cloth classics.
                        for (n in 0 until 6) {
                            val angle = n * PI / 3
                            drawCircle(ink.copy(alpha = .60f), 3.dp.toPx(),
                                Offset(center + cos(angle).toFloat() * 7.dp.toPx(),
                                    y + sin(angle).toFloat() * 7.dp.toPx()),
                                style = Stroke(width = line))
                        }
                        drawCircle(ink.copy(alpha = .80f), 2.dp.toPx(), Offset(center, y))
                    }
                    2 -> {
                        // Art Deco diamonds, never taken from a publisher's trademark.
                        for (scale in listOf(1f, .54f)) {
                            val dx = 11.dp.toPx() * scale
                            val dy = 15.dp.toPx() * scale
                            val pts = listOf(Offset(center, y - dy), Offset(center + dx, y),
                                Offset(center, y + dy), Offset(center - dx, y))
                            for (n in 0..3) drawLine(ink.copy(alpha = .70f),
                                pts[n], pts[(n + 1) % 4], line)
                        }
                    }
                    3 -> {
                        // Leaves and an outlined stem.
                        drawLine(ink.copy(alpha = .76f), Offset(center, y - 15.dp.toPx()),
                            Offset(center, y + 15.dp.toPx()), line)
                        for (n in -1..1) {
                            val yy = y + n * 8.dp.toPx()
                            val dir = if (n % 2 == 0) 1 else -1
                            drawOval(ink.copy(alpha = .46f),
                                topLeft = Offset(center + (if (dir < 0) -11.dp.toPx() else 0f),
                                    yy - 3.dp.toPx()),
                                size = Size(11.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(width = line))
                        }
                    }
                    else -> {
                        // Small ornamental medallion with concentric rings.
                        drawCircle(ink.copy(alpha = .70f), 12.dp.toPx(), Offset(center, y),
                            style = Stroke(width = line))
                        drawCircle(ink.copy(alpha = .55f), 7.dp.toPx(), Offset(center, y),
                            style = Stroke(width = line))
                        drawCircle(ink.copy(alpha = .9f), 2.dp.toPx(), Offset(center, y))
                    }
                }
            }
        }
    }
}
