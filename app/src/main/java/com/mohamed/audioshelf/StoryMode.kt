package com.mohamed.audioshelf

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Tune
import com.mohamed.audioshelf.data.captions.FullBookSubtitleStore
import com.mohamed.audioshelf.data.captions.FullBookTranscriptionWorker
import com.mohamed.audioshelf.data.captions.CaptionOverlayPolicy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
    val uriHandler = LocalUriHandler.current
    val storyPreferences = remember(context) {
        context.getSharedPreferences("audio_shelf_story", 0)
    }
    var style by rememberSaveable(book.id) {
        mutableStateOf(StoryLicensedVideos.persistedStyle(
            storyPreferences.getString("style_" + book.id, null)
        ))
    }
    var dimBackground by rememberSaveable(book.id) {
        mutableStateOf(storyPreferences.getFloat("dim_" + book.id, .12f))
    }
    var captionMode by rememberSaveable(book.id) {
        mutableStateOf(storyPreferences.getString("caption_mode_" + book.id, "Phrase") ?: "Phrase")
    }
    var captionScale by rememberSaveable(book.id) {
        mutableStateOf(storyPreferences.getFloat("caption_scale_" + book.id, 1f).coerceIn(.75f, 1.35f))
    }
    var captionPosition by rememberSaveable(book.id) {
        mutableStateOf(storyPreferences.getFloat("caption_position_" + book.id, .5f).coerceIn(0f, 1f))
    }
    var videoStatus by remember(style) { mutableStateOf<String?>(null) }
    var videoFailed by remember(style) { mutableStateOf(false) }
    var videoRetry by remember(style) { mutableIntStateOf(0) }
    var reduceMotion by rememberSaveable(book.id) {
        mutableStateOf(storyPreferences.getBoolean("reduce_motion_" + book.id, false))
    }
    LaunchedEffect(book.id, style, dimBackground, reduceMotion,
        captionMode, captionScale, captionPosition) {
        storyPreferences.edit()
            .putString("style_" + book.id, style)
            .putFloat("dim_" + book.id, dimBackground)
            .putBoolean("reduce_motion_" + book.id, reduceMotion)
            .putString("caption_mode_" + book.id, captionMode)
            .putFloat("caption_scale_" + book.id, captionScale)
            .putFloat("caption_position_" + book.id, captionPosition)
            .apply()
    }
    var absoluteM4bTime by rememberSaveable { mutableStateOf(true) }
    var autoTranscribe by rememberSaveable(book.id) { mutableStateOf(false) }
    var chapterIndex by remember { mutableIntStateOf(controller.currentMediaItemIndex.coerceAtLeast(0)) }
    var position by remember { mutableLongStateOf(controller.currentPosition.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var liveDurationMs by remember(controller) { mutableLongStateOf(0L) }
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
    var controlsVisible by rememberSaveable(book.id) { mutableStateOf(true) }
    BackHandler(enabled = !controlsVisible) { controlsVisible = true }
    var toolsExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
    var captionsHaveShown by rememberSaveable(book.id) { mutableStateOf(false) }
    val captionStore = remember(context) { TimedCaptionStore(context) }
    LaunchedEffect(transcriptError) {
        if (transcriptError != null) toolsExpanded = true
    }
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
            liveDurationMs = controller.duration.takeIf { it > 0L } ?: 0L
            if (controller.mediaItemCount > 0 && pollCount++ % 9 == 0) onProgress(chapterIndex, position)
            delay(120)
        }
    }

    val safeIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    val chapterDurationMs = liveDurationMs.takeIf { it > 0L }
        ?: chapters.getOrNull(safeIndex)?.durationMs?.coerceAtLeast(0L) ?: 0L
    var scrubPositionMs by remember(book.id, safeIndex) { mutableStateOf<Long?>(null) }

    fun skipBy(deltaMs: Long) {
        if (chapters.isEmpty() || controller.mediaItemCount == 0) return
        val current = controller.currentMediaItemIndex.coerceIn(chapters.indices)
        val currentDuration = controller.duration.takeIf { it > 0L } ?: 0L
        val durations = chapters.mapIndexed { index, chapter ->
            if (index == current && currentDuration > 0) currentDuration else chapter.durationMs
        }
        val target = StorySeek.byOffset(
            durations, current, controller.currentPosition.coerceAtLeast(0L), deltaMs
        )
        controller.seekTo(target.chapterIndex, target.positionMs)
        chapterIndex = target.chapterIndex
        position = target.positionMs
        onProgress(target.chapterIndex, target.positionMs)
    }
    val embedded = book.sourceId == "local_files" && chapters.size > 1
    val filePositionMs = position + (if (embedded && absoluteM4bTime) chapters.getOrNull(safeIndex)?.startMs ?: 0 else 0)
    val captionBucket = filePositionMs.coerceAtLeast(0) / TimedCaptionStore.INDEX_SPAN_MS
    val captionSourceIndex = if (book.sourceId == "local_files") 0 else safeIndex
    val subtitleCues = remember(book.id, book.sourceId, captionSourceIndex,
        captionBucket, transcriptRevision, absoluteM4bTime) {
        captionStore.loadNear(book, safeIndex, filePositionMs)
    }
    val fullCues = remember(book.id, filePositionMs / FullBookSubtitleStore.WINDOW_MS,
        fullProgress.completedMs) {
        fullStore.loadMinute(book.id, filePositionMs)
    }
    val cue = TimedCaptionParser.active(subtitleCues, filePositionMs)
        ?: TimedCaptionParser.active(fullCues, filePositionMs)
    val displayWord = cue?.let { TimedCaptionParser.wordAt(it, filePositionMs).first }.orEmpty()
    // The active cue can be null during intentional gaps between spoken lines.
    // A saved transcript means the setup prompt must never reappear in those gaps.
    val captionsAvailable = remember(book.id, safeIndex, transcriptRevision,
        fullProgress.completedMs) {
        captionStore.hasCaptions(book, safeIndex) || fullStore.hasCaptions(book.id)
    }
    LaunchedEffect(book.id, cue?.startMs) {
        if (cue != null && displayWord.isNotBlank()) captionsHaveShown = true
    }
    val absoluteAudioMs = position + if (embedded) chapters.getOrNull(safeIndex)?.startMs ?: 0L else 0L
    LaunchedEffect(autoTranscribe, sttModelReady, sttBusy, safeIndex,
        fullProgress.paused, fullProgress.finished, absoluteAudioMs / 1000L) {
        if (autoTranscribe && sttModelReady && !sttBusy && !sttNativeUnavailable &&
            (fullProgress.paused || fullProgress.finished) &&
            book.sourceId == "local_files" && chapters.isNotEmpty()) {
            // Generate captions a little ahead of playback in short windows.
            onTranscribeNearby(safeIndex, absoluteAudioMs + 12_000L)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050A18))) {
        Crossfade(targetState = style, animationSpec = tween(450), label = "Change Story Mode background") { selected ->
            val clip = StoryLicensedVideos.find(selected)
            val animation = StoryAnimation.find(selected)
            when {
                animation != null -> StoryAnimatedBackground(
                    animation = animation,
                    modifier = Modifier.fillMaxSize(),
                    moving = selected == style && !reduceMotion,
                )
                selected == "My video" && videoUri != null ->
                    LoopingVideoBackground(videoUri, videoPlaying = !reduceMotion)
                clip != null -> StoryLicensedVideoBackground(
                    clip = clip,
                    reduceMotion = reduceMotion,
                    retryToken = videoRetry,
                    onStatus = { message, failed ->
                        if (selected == style) {
                            videoStatus = message
                            videoFailed = failed
                        }
                    },
                )
                else -> Box(Modifier.fillMaxSize().background(Color(0xFF061426)))
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimBackground)))
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = if (controlsVisible) "Hide story controls" else "Show story controls",
            ) {
                scrubPositionMs = null
                controlsVisible = !controlsVisible
            }
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0xA4071022), Color.Transparent, Color.Transparent, Color(0xBD061021))
            )))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)) + expandVertically(tween(240), expandFrom = Alignment.Top),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Top),
            ) {
                Column {
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
                    videoStatus?.let { message ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xDF071423),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    message,
                                    color = if (videoFailed) Color(0xFFFFD0B8) else Color(0xFFDBECF6),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f),
                                )
                                if (videoFailed) {
                                    androidx.compose.material3.TextButton(onClick = { videoRetry++ }) {
                                        Text("Retry")
                                    }
                                    androidx.compose.material3.TextButton(onClick = {
                                        style = "My video"
                                        onPickVideo()
                                    }) {
                                        Text("Local")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (controlsVisible || (cue != null && displayWord.isNotBlank())) Box(
                    modifier = Modifier.fillMaxWidth().height(158.dp)
                        .background(Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0x67000516), Color.Transparent)
                        )),
                )
                if (cue != null && displayWord.isNotBlank()) {
                    val lift = ((captionPosition - .5f) * 130f).dp
                    if (captionMode == "Word") {
                        AnimatedContent(
                            targetState = displayWord,
                            modifier = Modifier.offset(y = lift),
                            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                            label = "spoken word",
                        ) { word ->
                            Text(
                                word,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                color = Color.White,
                                style = TextStyle(
                                    fontSize = 38.sp * captionScale,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 45.sp * captionScale,
                                    shadow = Shadow(Color.Black, Offset(3f, 5f), 10f),
                                ),
                            )
                        }
                    } else {
                        // Retain context while emphasizing the timed word; show a short rolling
                        // window instead of flashing unrelated single-word captions.
                        val words = cue.text.split(Regex("""\s+""")).filter { it.isNotBlank() }
                        val active = TimedCaptionParser.wordAt(cue, filePositionMs).second
                        val start = (active - 3).coerceAtLeast(0)
                            .coerceAtMost((words.size - 8).coerceAtLeast(0))
                        val visible = words.drop(start).take(8)
                        val highlighted = buildAnnotatedString {
                            visible.forEachIndexed { index, word ->
                                if (index > 0) append(" ")
                                withStyle(SpanStyle(
                                    color = if (start + index == active) Color(0xFF8BF5DF)
                                        else Color.White.copy(alpha = .90f),
                                    fontWeight = if (start + index == active) FontWeight.Black
                                        else FontWeight.SemiBold,
                                )) { append(word) }
                            }
                        }
                        Text(
                            text = highlighted,
                            modifier = Modifier.offset(y = lift).padding(horizontal = 10.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            style = TextStyle(
                                fontSize = 30.sp * captionScale,
                                lineHeight = 39.sp * captionScale,
                                shadow = Shadow(Color.Black, Offset(2f, 4f), 9f),
                            ),
                        )
                    }
                } else if (controlsVisible && CaptionOverlayPolicy.showSetupHint(
                    hasSavedCaptions = captionsAvailable,
                    hasEverShownCue = captionsHaveShown,
                    autoTranscribing = autoTranscribe || sttBusy,
                    fullBookRunning = !fullProgress.paused && !fullProgress.finished,
                )) {
                    // This is a first-time setup hint only, not an overlay between cues.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ClosedCaption, null, tint = Color(0xFFDFE9F5),
                            modifier = Modifier.size(28.dp))
                        Text(
                            "Import captions to display spoken words",
                            modifier = Modifier.padding(top = 8.dp),
                            color = Color(0xFFDFE9F5),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(220)) + expandVertically(tween(240), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xF2081428),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chapters.getOrNull(safeIndex)?.title.orEmpty(),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(
                                enabled = safeIndex > 0,
                                onClick = { controller.seekToPreviousMediaItem() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Previous chapter", tint = Color.White)
                            }
                            IconButton(
                                enabled = safeIndex < chapters.lastIndex,
                                onClick = { controller.seekToNextMediaItem() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.SkipNext, "Next chapter", tint = Color.White)
                            }
                            if (captionsAvailable || captionsHaveShown) {
                                Text("CC READY", color = Color(0xFF80F0DA),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                        // Seek within the current logical chapter. M4B items are clipped,
                        // so this stays relative to the chapter and keeps captions aligned.
                        Slider(
                            value = if (chapterDurationMs > 0L)
                                ((scrubPositionMs ?: position).coerceIn(0L, chapterDurationMs)
                                    .toFloat() / chapterDurationMs.toFloat()).coerceIn(0f, 1f)
                            else 0f,
                            onValueChange = { fraction ->
                                if (chapterDurationMs > 0L) {
                                    scrubPositionMs = (fraction * chapterDurationMs).toLong()
                                        .coerceIn(0L, chapterDurationMs)
                                }
                            },
                            onValueChangeFinished = {
                                scrubPositionMs?.let { target ->
                                    controller.seekTo(safeIndex, target)
                                    position = target
                                    onProgress(safeIndex, target)
                                }
                                scrubPositionMs = null
                            },
                            enabled = chapterDurationMs > 0L && controller.mediaItemCount > 0,
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(StorySeek.clock(scrubPositionMs ?: position),
                                color = Color(0xFFCDDFEF),
                                style = MaterialTheme.typography.labelSmall)
                            Text(if (chapterDurationMs > 0L) StorySeek.clock(chapterDurationMs)
                                 else "--:--",
                                color = Color(0xFFCDDFEF),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StorySkipButton(-30, chapters.isNotEmpty()) { skipBy(-30_000L) }
                            StorySkipButton(-10, chapters.isNotEmpty()) { skipBy(-10_000L) }
                            FilledIconButton(
                                onClick = { if (playing) controller.pause() else controller.play() },
                                modifier = Modifier.size(52.dp),
                            ) {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playing) "Pause" else "Play")
                            }
                            StorySkipButton(10, chapters.isNotEmpty()) { skipBy(10_000L) }
                            StorySkipButton(30, chapters.isNotEmpty()) { skipBy(30_000L) }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onPickTranscript(safeIndex) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.ClosedCaption, null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (captionsAvailable) "Replace captions" else "Import captions",
                                    maxLines = 1)
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { toolsExpanded = !toolsExpanded },
                            ) {
                                Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (toolsExpanded) "Close" else "Tools")
                            }
                        }
                        AnimatedVisibility(
                            visible = toolsExpanded,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
                        ) {
                            Column {
                                androidx.compose.material3.HorizontalDivider(
                                    color = Color(0x335B7795),
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                                Column(
                                    Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("Captions", color = Color(0xFFD9E4F3),
                                        style = MaterialTheme.typography.titleSmall)
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf("Phrase", "Word").forEach { mode ->
                                            FilterChip(
                                                selected = captionMode == mode,
                                                onClick = { captionMode = mode },
                                                label = { Text(if (mode == "Phrase") "Rolling phrase" else "One word") },
                                            )
                                        }
                                    }
                                    Text("Caption size", color = Color.White,
                                        style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = captionScale,
                                        onValueChange = { captionScale = it },
                                        valueRange = .75f..1.35f,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text("Caption position", color = Color.White,
                                        style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = captionPosition,
                                        onValueChange = { captionPosition = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text("Scene · " + style, color = Color(0xFFD9E4F3),
                                        style = MaterialTheme.typography.titleSmall)
                                    StoryBackdropSelector(
                                        style = style,
                                        onSelect = { style = it },
                                        onPickVideo = onPickVideo,
                                    )
                                    Text("Background dimming", color = Color.White,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.align(Alignment.Start).padding(top = 8.dp))
                                    Slider(
                                        value = dimBackground,
                                        onValueChange = { dimBackground = it },
                                        valueRange = 0f..0.65f,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Switch(
                                            checked = reduceMotion, onCheckedChange = { reduceMotion = it },
                                        )
                                        Text("  Pause background motion", color = Color.White,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    StoryAnimation.find(style)?.let { animation ->
                                        androidx.compose.material3.TextButton(
                                            onClick = { uriHandler.openUri(animation.sourceUrl) },
                                        ) {
                                            Text("Inspiration · React Bits")
                                        }
                                    }
                                    StoryLicensedVideos.find(style)?.let { clip ->
                                        Text(
                                            "Licensed CC0 loop · " + clip.creator +
                                                " · Downloaded once for offline replay",
                                            color = Color(0xFFC3D3E5),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        androidx.compose.material3.TextButton(
                                            onClick = { uriHandler.openUri(clip.sourceUrl) },
                                        ) {
                                            Text("Video source and license · Wikimedia Commons")
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

                                    if (!captionsAvailable && !captionsHaveShown) {
                                        Text(
                                            "Import SRT, VTT or LRC, or generate captions offline.",
                                            color = Color(0xFFD3E3EF),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorySkipButton(seconds: Int, enabled: Boolean, onClick: () -> Unit) {
    val description = if (seconds > 0) "Skip forward $seconds seconds"
        else "Skip backward ${-seconds} seconds"
    val label = if (seconds > 0) "+${seconds}s" else "${seconds}s"
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x352E486A))
            .semantics { contentDescription = description },
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
internal fun LoopingVideoBackground(videoUri: Uri, videoPlaying: Boolean = true) {
    val context = LocalContext.current
    val player = remember(context, videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = videoPlaying
            prepare()
        }
    }
    LaunchedEffect(player, videoPlaying) { player.playWhenReady = videoPlaying }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { host ->
            (LayoutInflater.from(host).inflate(R.layout.story_video, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
