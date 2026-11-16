package com.mohamed.audioshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import com.mohamed.audioshelf.data.captions.FullBookSubtitleStore
import com.mohamed.audioshelf.data.captions.FullBookTranscriptionWorker
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mohamed.audioshelf.data.captions.TimedCaptionParser
import com.mohamed.audioshelf.data.captions.TimedCaptionStore
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * Optional focus player inspired by word-at-a-time subtitle videos:
 * large timed captions over ORIGINAL procedural visuals or a user-selected video.
 * No bundled unlicensed game clips or misleading automatic transcription claims.
 */
@Composable
internal fun StoryMode(
    book: AudioBook,
    chapters: List<AudioChapter>,
    controller: MediaController,
    transcriptRevision: Int,
    transcriptError: String?,
    videoRevision: Int,
    onClose: () -> Unit,
    onPickTranscript: (Int) -> Unit,
    onPickVideo: () -> Unit,
    sttBusy: Boolean,
    sttModelReady: Boolean,
    sttMessage: String?,
    sttNativeUnavailable: Boolean,
    onDownloadSttModel: () -> Unit,
    onTranscribeNearby: (Int, Long) -> Unit,
    onProgress: (Int, Long) -> Unit,
) {
    val context = LocalContext.current
    var style by rememberSaveable { mutableStateOf("Parkour") }
    var absoluteM4bTime by rememberSaveable { mutableStateOf(true) }
    var autoTranscribe by rememberSaveable(book.id) { mutableStateOf(false) }
    var chapterIndex by remember { mutableIntStateOf(controller.currentMediaItemIndex.coerceAtLeast(0)) }
    var position by remember { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(controller.isPlaying) }
    val fullStore = remember(context) { FullBookSubtitleStore(context) }
    var fullProgress by remember(book.id) { mutableStateOf(fullStore.progress(book.id)) }
    var fullError by remember(book.id) { mutableStateOf<String?>(null) }
    var exporting by remember(book.id) { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    fun exportTo(target: Uri?, format: String) {
        if (target == null || exporting) return
        exporting = true
        exportScope.launch {
            runCatching { withContext(Dispatchers.IO) { fullStore.export(book.id, target, format) } }
                .onSuccess { fullError = null }
                .onFailure { fullError = it.message }
            exporting = false
        }
    }
    var fullExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) {
        while (true) {
            fullProgress = fullStore.progress(book.id)
            delay(1200)
        }
    }
    val exportSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { target ->
        exportTo(target, "srt")
    }
    val exportVtt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vtt")
    ) { target ->
        exportTo(target, "vtt")
    }
    val videoUri = remember(videoRevision) {
        context.getSharedPreferences("audio_shelf_story", 0)
            .getString("video_uri", null)?.let(Uri::parse)
    }

    LaunchedEffect(controller) {
        var pollCount = 0
        while (true) {
            playing = controller.isPlaying
            chapterIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
            position = controller.currentPosition.coerceAtLeast(0L)
            if (controller.mediaItemCount > 0 && pollCount++ % 9 == 0) onProgress(chapterIndex, position)
            delay(120)
        }
    }

    val safeIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    val subtitleCues = remember(book.id, book.sourceId, safeIndex, transcriptRevision) {
        TimedCaptionStore(context).load(book, safeIndex)
    }
    val embedded = book.sourceId == "local_files" && chapters.size > 1
    val filePositionMs = position + (if (embedded && absoluteM4bTime) chapters.getOrNull(safeIndex)?.startMs ?: 0 else 0)
    val fullCues = remember(book.id, filePositionMs / FullBookSubtitleStore.WINDOW_MS,
        fullProgress.completedMs) {
        fullStore.loadMinute(book.id, filePositionMs)
    }
    val cue = TimedCaptionParser.active(subtitleCues, filePositionMs)
        ?: TimedCaptionParser.active(fullCues, filePositionMs)
    val displayWord = cue?.let { TimedCaptionParser.wordAt(it, filePositionMs).first }.orEmpty()
    val absoluteAudioMs = position + if (embedded) chapters.getOrNull(safeIndex)?.startMs ?: 0L else 0L
    LaunchedEffect(autoTranscribe, sttModelReady, sttBusy, safeIndex, absoluteAudioMs / 1000L) {
        if (autoTranscribe && sttModelReady && !sttBusy && !sttNativeUnavailable &&
            book.sourceId == "local_files" && chapters.isNotEmpty()) {
            // Generate captions a little ahead of playback in short windows.
            onTranscribeNearby(safeIndex, absoluteAudioMs + 12_000L)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050A18))) {
        when (style) {
            "My video" -> if (videoUri != null) LoopingVideoBackground(videoUri)
                else StoryMotionBackground("Parkour")
            else -> StoryMotionBackground(style)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xAA000815), Color(0x22000000), Color(0x66000A14))
        )))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Close story mode", tint = Color.White) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("STORY MODE", color = Color(0xFFE4F6FF), fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.labelMedium)
                    Text(book.title, color = Color.White, maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onPickVideo) {
                    Icon(Icons.Default.FolderOpen, "Pick background gameplay video", tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Parkour", "Starfield", "Orbs", "My video").forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = { style = option; if (option == "My video" && videoUri == null) onPickVideo() },
                        label = { Text(option) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                if (cue != null && displayWord.isNotBlank()) {
                    AnimatedContent(targetState = displayWord, label = "word caption") { word ->
                        Text(word, textAlign = TextAlign.Center, maxLines = 3,
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 37.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 43.sp,
                                shadow = Shadow(Color.Black, Offset(3f, 5f), 9f),
                            ))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ClosedCaption, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        Text(
                            if (subtitleCues.isEmpty()) "Add timed captions to show spoken words"
                            else "Captions appear here during spoken lines",
                            modifier = Modifier.padding(top = 12.dp),
                            color = Color.White, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xCC061121)) {
                Column(Modifier.padding(14.dp).heightIn(max = 375.dp)
                    .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(chapters.getOrNull(safeIndex)?.title.orEmpty(),
                        color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { controller.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, "Previous chapter", tint = Color.White)
                        }
                        FilledIconButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playing) "Pause" else "Play")
                        }
                        IconButton(onClick = { controller.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Next chapter", tint = Color.White)
                        }
                    }
                    if (book.sourceId == "local_files") {
                        if (!sttModelReady) {
                            OutlinedButton(
                                onClick = onDownloadSttModel, enabled = !sttBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download free offline speech model (~40 MB)") }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Switch(
                                    checked = autoTranscribe,
                                    onCheckedChange = { autoTranscribe = it },
                                    enabled = !sttNativeUnavailable,
                                )
                                Text("  Auto-transcribe as you listen", color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { onTranscribeNearby(safeIndex, absoluteAudioMs) },
                                enabled = !sttBusy && !sttNativeUnavailable, modifier = Modifier.fillMaxWidth(),
                            ) { Text("Generate next 30 seconds of captions") }
                        }
                        if (sttBusy) androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth())
                        if (!sttMessage.isNullOrBlank()) Text(sttMessage,
                            color = Color(0xFFDBF7E9),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (book.sourceId == "local_files") {
                        androidx.compose.material3.TextButton(
                            onClick = { fullExpanded = !fullExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (fullExpanded) "Hide full-book transcription"
                                 else "Full-book subtitles · Background processing")
                        }
                        if (fullExpanded) {
                            Text(
                                if (fullProgress.finished) "Complete · ${fullProgress.words} recognized words"
                                else "${fullProgress.percent}% complete · ${fullProgress.words} words saved",
                                color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { fullProgress.percent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!fullProgress.paused && !fullProgress.finished) {
                                    OutlinedButton(
                                        onClick = {
                                            FullBookTranscriptionWorker.pause(context, book.id)
                                            fullProgress = fullStore.progress(book.id)
                                        }, modifier = Modifier.weight(1f)
                                    ) { Text("Pause") }
                                } else {
                                    Button(
                                        enabled = sttModelReady && !sttNativeUnavailable &&
                                            !fullProgress.finished,
                                        onClick = {
                                            runCatching {
                                                FullBookTranscriptionWorker.resume(context, book.id)
                                                fullProgress = fullStore.progress(book.id)
                                                fullError = null
                                            }.onFailure { fullError = it.message }
                                        }, modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (fullProgress.completedMs > 0) "Resume" else "Start")
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = fullProgress.completedMs > 0L && !exporting,
                                    onClick = { exportSrt.launch("AudioShelf-transcript.srt") },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Export SRT") }
                                OutlinedButton(
                                    enabled = fullProgress.completedMs > 0L && !exporting,
                                    onClick = { exportVtt.launch("AudioShelf-transcript.vtt") },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Export VTT") }
                            }
                            if (exporting) Text("Writing subtitle file…",
                                color = Color.White, style = MaterialTheme.typography.bodySmall)
                            if (!sttModelReady) Text(
                                "Download the offline speech model above before starting.",
                                color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Text("The job keeps processing after Story Mode closes. " +
                                 "Keep the phone powered; Android may suspend long background jobs.",
                                color = Color(0xFFD3E3EF),
                                style = MaterialTheme.typography.bodySmall)
                            fullProgress.error?.let {
                                Text(it, color = Color(0xFFFFB8AF),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            fullError?.let {
                                Text(it, color = Color(0xFFFFB8AF),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(onClick = { onPickTranscript(safeIndex) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ClosedCaption, null)
                        Text("  Import SRT / VTT / LRC captions")
                    }
                    if (embedded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = absoluteM4bTime,
                                onCheckedChange = { absoluteM4bTime = it },
                            )
                            Text("M4B transcript uses full-book timestamps",
                                color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (transcriptError != null) Text(transcriptError,
                        color = Color(0xFFFFB8AF), style = MaterialTheme.typography.bodySmall)
                    if (subtitleCues.isEmpty()) Text(
                        "Turn on offline auto-transcription or import an SRT/VTT/LRC subtitle file.",
                        color = Color(0xFFD3E3EF), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    ) else Text(
                        "Words animate across each subtitle's duration. Word-level timing is approximate unless captions are word-timed.",
                        color = Color(0xFFD3E3EF), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryMotionBackground(style: String) {
    val infinite = rememberInfiniteTransition(label = "story backdrop motion")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "motion phase",
    )
    var tap by remember { mutableStateOf<Offset?>(null) }
    Canvas(Modifier.fillMaxSize().pointerInput(style) { detectTapGestures { tap = it } }) {
        val w = size.width
        val h = size.height
        drawRect(brush = Brush.verticalGradient(
            colors = if (style == "Orbs") listOf(Color(0xFF301258), Color(0xFF0D1031))
                else if (style == "Parkour") listOf(Color(0xFF23376A), Color(0xFF090D22), Color(0xFF052938))
                else listOf(Color(0xFF070D2C), Color(0xFF14062C))
        ))
        if (style == "Parkour") {
            // Original game-like looping floating platforms. No third-party gameplay assets.
            val horizon = h * .37f
            for (i in 0..35) {
                val progress = ((i / 36f + phase) % 1f)
                val depth = progress * progress
                val width = w * (.08f + depth * .72f)
                val height = h * (.01f + depth * .08f)
                val x = w * (.48f + sin(i * 1.93).toFloat() * (.1f + depth * .25f)) - width / 2
                val y = horizon + (h - horizon) * depth
                drawRoundRect(color = if (i % 3 == 0) Color(0xFF00E3C7)
                    else if (i % 3 == 1) Color(0xFF8B67EA) else Color(0xFF44A2E8),
                    topLeft = Offset(x, y), size = Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                    alpha = .8f)
            }
            drawCircle(Color(0xFFFCD998), w * .14f, Offset(w * .76f, h * .19f), alpha = .55f)
        } else {
            for (i in 0..90) {
                val seed = (i * .618034f) % 1f
                val y = (((i * .389f) + phase * (if (style == "Orbs") .28f else .75f)) % 1f) * h
                val x = (((i * .7549f) + sin((phase + seed) * 6.28).toFloat() * .06f) % 1f) * w
                val radius = if (style == "Orbs") 5f + seed * 22f else 1.5f + seed * 4f
                drawCircle(color = if (i % 3 == 0) Color(0xFF8EFAF6)
                    else if (i % 3 == 1) Color(0xFFA78BFA) else Color(0xFFFFFFFF),
                    radius = radius, center = Offset(x, y), alpha = .18f + seed * .45f)
            }
        }
        tap?.let { drawCircle(Color(0x99FFFFFF), radius = 42f + phase * 70f, center = it,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)) }
    }
}

@Composable
private fun LoopingVideoBackground(videoUri: Uri) {
    val context = LocalContext.current
    val player = remember(context, videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { host ->
            PlayerView(host).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
