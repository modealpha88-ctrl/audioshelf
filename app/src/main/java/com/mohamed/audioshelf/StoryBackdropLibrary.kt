package com.mohamed.audioshelf

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The selector contains exclusively verified CC0 video loops and My Video. */
@Composable
internal fun StoryBackdropSelector(
    style: String,
    onSelect: (String) -> Unit,
    onPickVideo: () -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StoryLicensedVideos.clips.forEach { clip ->
            VideoChoiceCard(
                name = clip.title,
                label = clip.subtitle,
                tint = Color(clip.color),
                selected = style == clip.title,
                onClick = { onSelect(clip.title) },
            )
        }
        VideoChoiceCard(
            name = "My video",
            label = "Your local clip",
            tint = Color(0xFF8959C0),
            selected = style == "My video",
            onClick = {
                onSelect("My video")
                onPickVideo()
            },
        )
    }
}

@Composable
private fun VideoChoiceCard(
    name: String,
    label: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier.width(126.dp).height(98.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint, Color(0xFF071424))))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color(0xFFE5C6FF) else Color(0x887C91AD),
                shape,
            )
            .clickable(onClick = onClick)
    ) {
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
) {
    val context = LocalContext.current
    val cache = remember(context) { StoryVideoCache(context) }
    var retry by remember(clip.id) { mutableIntStateOf(0) }
    val state by produceState<ClipLoad>(
        initialValue = ClipLoad.Downloading(0),
        clip.id, retry,
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
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(clip.color), Color(0xFF11192E), Color(0xFF060D18))
            )
        ), contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is ClipLoad.Ready -> LoopingVideoBackground(
                current.uri, videoPlaying = !reduceMotion
            )
            is ClipLoad.Downloading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.PlayCircleOutline, null,
                    tint = Color.White.copy(alpha = .70f), modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(8.dp))
                Text("Downloading " + clip.title + " · " + current.percent + "%",
                    color = Color.White, fontSize = 13.sp)
                Text("CC0 video · one-time download", color = Color(0xFFDAEAF4),
                    fontSize = 11.sp)
            }
            is ClipLoad.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(28.dp),
            ) {
                Text("Couldn't load " + clip.title,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(current.message, color = Color(0xFFDAEAF4), fontSize = 12.sp)
                Button(onClick = { retry++ }) { Text("Retry video download") }
            }
        }
    }
}
