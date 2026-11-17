package com.mohamed.audioshelf

import android.net.Uri
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Built-in animated effects, licensed video loops, and the local video picker. */
@Composable
internal fun StoryBackdropSelector(
    style: String,
    onSelect: (String) -> Unit,
    onPickVideo: () -> Unit,
) {
    Row(
        modifier = Modifier.selectableGroup().horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StoryAnimation.entries.forEach { animation ->
            BackdropChoiceCard(
                name = animation.title,
                label = animation.subtitle,
                tint = animation.tint,
                animation = animation,
                selected = style == animation.title,
                onClick = { onSelect(animation.title) },
            )
        }
        StoryLicensedVideos.clips.forEach { clip ->
            BackdropChoiceCard(
                name = clip.title,
                label = clip.subtitle,
                tint = Color(clip.color),
                clip = clip,
                selected = style == clip.title,
                onClick = { onSelect(clip.title) },
            )
        }
        BackdropChoiceCard(
            name = "My video",
            label = "Your local clip",
            tint = Color(0xFF8959C0),
            clip = null,
            selected = style == "My video",
            onClick = {
                onSelect("My video")
                onPickVideo()
            },
        )
    }
}

@Composable
private fun BackdropChoiceCard(
    name: String,
    label: String,
    tint: Color,
    clip: StoryLicensedClip? = null,
    animation: StoryAnimation? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    val context = LocalContext.current
    // No thumbnail network calls; extract a frame from each downloaded local file.
    val preview by produceState<Bitmap?>(initialValue = null, clip?.id, selected) {
        if (clip != null) {
            val cache = StoryVideoCache(context)
            val tries = if (selected) 25 else 1
            repeat(tries) {
                val file = cache.cached(clip)
                if (file != null) {
                    value = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(500_000L)
                        } catch (_: Exception) {
                            null
                        } finally {
                            retriever.release()
                        }
                    }
                    return@produceState
                }
                if (selected) delay(2000)
            }
        }
    }
    Box(
        Modifier.width(126.dp).height(98.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint, Color(0xFF071424))))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color(0xFFE5C6FF) else Color(0x887C91AD),
                shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        if (animation != null) {
            StoryAnimatedBackground(animation, Modifier.fillMaxSize())
        }
        if (preview != null) {
            Image(
                bitmap = preview!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (animation != null || preview != null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0x22000000), Color(0xDD050A18))
            )))
        }
        Icon(
            if (name == "My video") Icons.Default.VideoLibrary
            else Icons.Default.PlayCircleOutline,
            contentDescription = null,
            tint = Color.White.copy(alpha = .67f),
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Text(name, color = Color.White, fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = Color(0xFFE6EAF1), fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private sealed interface ClipLoad {
    data class Downloading(val percent: Int) : ClipLoad
    data class Ready(val uri: Uri) : ClipLoad
    data class Failed(val message: String) : ClipLoad
}

/**
 * Downloads once to private offline storage, then loops video silently.
 * The audiobook's separate MediaController remains untouched throughout.
 */
@Composable
internal fun StoryLicensedVideoBackground(
    clip: StoryLicensedClip,
    reduceMotion: Boolean,
    retryToken: Int,
    onStatus: (String?, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val cache = remember(context) { StoryVideoCache(context) }
    val state by produceState<ClipLoad>(
        initialValue = ClipLoad.Downloading(0),
        clip.id, retryToken,
    ) {
        value = ClipLoad.Downloading(0)
        try {
            val file = cache.acquire(clip) { progress ->
                withContext(Dispatchers.Main) { value = ClipLoad.Downloading(progress) }
            }
            value = ClipLoad.Ready(Uri.fromFile(file))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            value = ClipLoad.Failed(error.message ?: "Unable to load licensed video.")
        }
    }
    // Report status to the top HUD. Never draw failures over timed words.
    LaunchedEffect(state) {
        when (val current = state) {
            is ClipLoad.Ready -> onStatus(null, false)
            is ClipLoad.Downloading -> onStatus(
                "Downloading " + clip.title + " · " + current.percent + "%", false
            )
            is ClipLoad.Failed -> onStatus(current.message, true)
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(clip.color), Color(0xFF11192E), Color(0xFF060D18))
            )
        )
    ) {
        (state as? ClipLoad.Ready)?.let { ready ->
            LoopingVideoBackground(ready.uri, videoPlaying = !reduceMotion)
        }
    }
}
