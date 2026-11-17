package com.mohamed.audioshelf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlin.math.sin

/**
 * Selected official Kenney CC0 Background Elements Remastered artwork.
 * The PNGs are mirrored in the publicly accessible CC0 game-assets library.
 * https://kenney.nl/assets/background-elements-remastered
 * https://github.com/eturner58/game-assets/tree/main/kenney/2D%20assets/Background%20Elements%20Remastered
 *
 * Artwork is fetched only when the user opens Story Mode, with local disk cache.
 * If offline on first load, the original gradient backdrop remains visible.
 * No unlicensed gameplay footage or restricted third-party video is bundled.
 */
internal data class StoryBackdropPreset(
    val name: String,
    val sceneImage: String? = null,
    val accent: Color = Color(0xFF8B84D9),
)

internal object StoryBackdropLibrary {
    private const val BASE =
        "https://raw.githubusercontent.com/eturner58/game-assets/main/" +
        "kenney/2D%20assets/Background%20Elements%20Remastered/Backgrounds/"

    val presets = listOf(
        StoryBackdropPreset("Parkour", accent = Color(0xFF26CDBF)),
        StoryBackdropPreset("Forest", BASE + "backgroundColorForest.png", Color(0xFF7CDDA5)),
        StoryBackdropPreset("Castle", BASE + "backgroundCastles.png", Color(0xFFD0A8FC)),
        StoryBackdropPreset("Desert", BASE + "backgroundColorDesert.png", Color(0xFFF5B481)),
        StoryBackdropPreset("Autumn", BASE + "backgroundColorFall.png", Color(0xFFF0A68D)),
        StoryBackdropPreset("Starfield", accent = Color(0xFF8AA8F1)),
        StoryBackdropPreset("Orbs", accent = Color(0xFFB09BED)),
        StoryBackdropPreset("My video", accent = Color(0xFFBD9EEF)),
    )
    fun find(name: String) = presets.firstOrNull { it.name == name }
}

@Composable
internal fun StoryBackdropSelector(
    style: String,
    onSelect: (String) -> Unit,
    onPickVideo: () -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        StoryBackdropLibrary.presets.forEach { preset ->
            val selected = preset.name == style
            val shape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier.width(104.dp).height(85.dp)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(preset.accent.copy(alpha = .6f), Color(0xFF111329))
                        )
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color(0xFFE9D3FF) else Color(0x667C91AD),
                        shape = shape,
                    )
                    .clickable {
                        onSelect(preset.name)
                        if (preset.name == "My video") onPickVideo()
                    },
            ) {
                if (preset.sceneImage != null) {
                    StoryRemoteArtwork(preset.sceneImage, Modifier.fillMaxSize())
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        repeat(12) { i ->
                            drawCircle(
                                preset.accent.copy(alpha = .12f + i % 3 * .10f),
                                2f + (i % 4) * 5f,
                                Offset(
                                    (i * 73 % 110).toFloat() / 110f * size.width,
                                    (i * 43 % 90).toFloat() / 90f * size.height,
                                ),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xEE030B18))
                    )
                ))
                Text(
                    preset.name,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 9.dp, bottom = 8.dp),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StoryRemoteArtwork(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = "Free CC0 game scenery by Kenney",
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
internal fun StoryKenneyBackdrop(preset: StoryBackdropPreset, reduceMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "licensed landscape parallax")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(35_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "slow parallax",
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF131F30))) {
        // The gradients are the offline fallback: Kenney's image is cached
        // after the first successful online visit.
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(preset.accent.copy(alpha = .72f), Color(0xFF0C1B2B), Color(0xFF071027))
            )
        ))
        preset.sceneImage?.let { imageUrl ->
            StoryRemoteArtwork(
                imageUrl,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.24f
                    scaleY = 1.24f
                    translationX = if (reduceMotion) 0f else (phase - .5f) * size.width * .17f
                    translationY = if (reduceMotion) 0f else
                        sin(phase.toDouble() * 6.28318).toFloat() * size.height * .018f
                },
            )
        }
        // Subtle atmospheric flecks animate independently of the static art.
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 28) {
                val fx = ((i * .6180339f + if (reduceMotion) 0f else phase * .12f) % 1f)
                val fy = ((i * .381966f + if (reduceMotion) 0f else phase * .22f) % 1f)
                drawCircle(
                    color = preset.accent,
                    radius = 1.3f + i % 4,
                    center = Offset(fx * size.width, fy * size.height),
                    alpha = .14f + .06f * (i % 3),
                )
            }
        }
    }
}
