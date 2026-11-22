package com.mohamed.audioshelf

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal enum class StoryAnimation(
    val title: String,
    val subtitle: String,
    private val sourceSlug: String,
    val base: Color,
    val tint: Color,
    val accent: Color,
) {
    Aurora("Aurora", "Teal and violet curtains", "aurora",
        Color(0xFF07091B), Color(0xFF34CFBB), Color(0xFF8B52D4)),
    Particles("Particles", "Drifting amber embers", "particles",
        Color(0xFF110A16), Color(0xFFFF9B62), Color(0xFFBE5EDA)),
    Silk("Silk", "Flowing indigo folds", "silk",
        Color(0xFF0D0B1D), Color(0xFF624FCE), Color(0xFFB675E0)),
    LightRays("Light Rays", "Soft blue shafts of light", "light-rays",
        Color(0xFF060D1C), Color(0xFF50AFE7), Color(0xFF767CE1));

    val sourceUrl: String get() = "https://reactbits.dev/backgrounds/$sourceSlug"

    companion object {
        fun find(title: String?): StoryAnimation? = entries.firstOrNull { it.title == title }
    }
}

@Composable
internal fun StoryAnimatedBackground(
    animation: StoryAnimation,
    modifier: Modifier,
    moving: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time = remember(animation) { mutableFloatStateOf(2f) }
    val painter = remember(animation) { StoryAnimationPainter(animation) }

    LaunchedEffect(animation, moving, lifecycle) {
        if (!moving) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!ValueAnimator.areAnimatorsEnabled()) return@repeatOnLifecycle
            var lastFrame = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                if (frame - lastFrame >= 16_666_667L) {
                    time.floatValue += (frame - lastFrame).coerceAtMost(100_000_000L) / 1_000_000_000f
                    lastFrame = frame
                }
            }
        }
    }

    Canvas(modifier) { with(painter) { paint(time.floatValue) } }
}

/** Native interpretations of the React Bits effects using bounded Canvas drawing. */
private class StoryAnimationPainter(private val animation: StoryAnimation) {
    private val path = Path()
    private val backdrop = Brush.verticalGradient(listOf(animation.base, Color(0xFF050812)))
    private val ribbons = List(7) { index ->
        val color = if (index % 3 == 0) animation.accent else animation.tint
        Brush.verticalGradient(
            0f to Color.Transparent,
            .22f to color.copy(alpha = .39f),
            .48f to color.copy(alpha = .16f),
            .76f to Color.Transparent,
        )
    }
    private val rays = List(9) { index ->
        val color = if (index % 3 == 0) animation.accent else animation.tint
        Brush.verticalGradient(listOf(color.copy(alpha = .24f), Color.Transparent))
    }
    private val sparks = if (animation == StoryAnimation.Particles) {
        val random = Random(53)
        List(92) {
            Spark(random.nextFloat(), random.nextFloat(), random.nextFloat())
        }
    } else emptyList()

    fun DrawScope.paint(time: Float) {
        drawRect(backdrop)
        when (animation) {
            StoryAnimation.Aurora -> drawFolds(time, aurora = true)
            StoryAnimation.Silk -> drawFolds(time, aurora = false)
            StoryAnimation.Particles -> drawSparks(time)
            StoryAnimation.LightRays -> drawRays(time)
        }
    }

    private fun DrawScope.drawFolds(time: Float, aurora: Boolean) {
        val width = size.width
        val height = size.height
        for (layer in 0 until (if (aurora) 5 else ribbons.size)) {
            fun edge(x: Float): Float {
                val phase = x * (if (aurora) 5.2f else 3.4f)
                val wave = sin(phase + time * .22f + layer * .66f) +
                    .32f * sin(phase * 2f - time * .17f + layer)
                val base = if (aurora) .18f + layer * .043f else .18f + layer * .071f + .26f * x
                return height * (base + wave * (if (aurora) .067f else .10f))
            }

            path.rewind()
            val samples = 32
            for (sample in 0..samples) {
                val x = sample.toFloat() / samples
                val y = edge(x)
                if (sample == 0) path.moveTo(0f, y) else path.lineTo(x * width, y)
            }
            for (sample in samples downTo 0) {
                val x = sample.toFloat() / samples
                val thickness = if (aurora) .17f + .06f * sin(x * 4f + layer) else .08f
                path.lineTo(x * width, edge(x) + height * thickness)
            }
            path.close()
            drawPath(path, ribbons[layer])
        }
    }

    private fun DrawScope.drawSparks(time: Float) {
        val scale = size.minDimension
        for (spark in sparks) {
            val progress = spark.y - time * (.009f + spark.depth * .018f)
            val y = (progress % 1f + 1f) % 1f
            val x = spark.x + .032f * sin(time * .32f + spark.y * 11f)
            val pulse = .65f + .35f * sin(time * (.55f + spark.depth) + spark.x * 16f)
            val fade = sin(y * PI.toFloat())
            val radius = scale * (.0015f + spark.depth * .0045f)
            val center = Offset(x * size.width, y * size.height)
            val color = if (spark.depth > .78f) animation.accent else animation.tint
            drawCircle(color, radius * 2.9f, center, alpha = .13f * pulse * fade)
            drawCircle(color, radius, center, alpha = .81f * pulse * fade)
        }
    }

    private fun DrawScope.drawRays(time: Float) {
        val origin = Offset(size.width * (.52f + .10f * sin(time * .17f)), -.09f * size.height)
        for (index in rays.indices) {
            val swing = sin(time * .25f + index * .81f)
            val end = size.width * (-.58f + index * .28f + swing * .12f)
            val spread = size.width * (.05f + .018f * (index % 3))
            for (layer in 0..2) {
                val width = spread * (1f - layer * .28f)
                path.rewind()
                path.moveTo(origin.x, origin.y)
                path.lineTo(end - width, size.height)
                path.lineTo(end + width, size.height)
                path.close()
                drawPath(path, rays[index], alpha = .27f + .08f * swing)
            }
        }
    }

    private data class Spark(val x: Float, val y: Float, val depth: Float)
}
