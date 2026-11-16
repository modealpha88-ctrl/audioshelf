package com.mohamed.audioshelf.data.captions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mohamed.audioshelf.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.vosk.Model
import kotlin.coroutines.coroutineContext

/** Persisted WorkManager foreground job; one independently saved minute at a time. */
class FullBookTranscriptionWorker(
    context: Context, params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val INPUT_URI = "audio_uri"
        private const val CHANNEL = "audiobook_transcription"
        private const val NOTIFICATION_ID = 24281
        fun resume(context: Context, bookId: String) {
            require(bookId.startsWith("content://")) { "Full-book transcription requires a local M4B file." }
            val store = FullBookSubtitleStore(context)
            val progress = store.progress(bookId)
            if (progress.finished) return
            store.setPaused(bookId, false)
            store.setError(bookId, null)
            val request = OneTimeWorkRequestBuilder<FullBookTranscriptionWorker>()
                .setInputData(workDataOf(INPUT_URI to bookId))
                .addTag(store.workName(bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                store.workName(bookId), ExistingWorkPolicy.REPLACE, request)
        }
        fun pause(context: Context, bookId: String) {
            FullBookSubtitleStore(context).setPaused(bookId, true)
            WorkManager.getInstance(context).cancelUniqueWork(
                FullBookSubtitleStore(context).workName(bookId))
        }
    }

    private val store = FullBookSubtitleStore(applicationContext)
    private val bookId = inputData.getString(INPUT_URI).orEmpty()

    private fun notification(progress: FullBookSubtitleStore.Progress): Notification {
        val service = applicationContext.getSystemService(NotificationManager::class.java)
        service.createNotificationChannel(NotificationChannel(
            CHANNEL, "Audiobook transcription", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("AudioShelf · Offline transcription")
            .setContentText("Processed ${progress.percent}% of this audiobook")
            .setProgress(100, progress.percent, progress.durationMs == 0L)
            .setOngoing(!progress.finished)
            .setOnlyAlertOnce(true)
            .build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, notification(store.progress(bookId)), type)
    }

    override suspend fun doWork(): Result {
        if (!bookId.startsWith("content://")) return Result.failure()
        if (!OfflineSpeechModel(applicationContext).isInstalled()) return Result.failure()
        if (store.progress(bookId).paused) return Result.success()
        val uri = Uri.parse(bookId)
        try {
            val progress = store.progress(bookId)
            val duration = if (progress.durationMs > 0) progress.durationMs else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(applicationContext, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally { retriever.release() }
            }
            require(duration > 0L) { "Unable to determine the audiobook duration." }
            store.configure(bookId, duration)
            setForeground(getForegroundInfo())
            val modelPath = OfflineSpeechModel(applicationContext).directory.absolutePath
            val decoder = OnDeviceTranscriber(applicationContext)
            Model(modelPath).use { model ->
                while (true) {
                    coroutineContext.ensureActive()
                    if (isStopped || store.progress(bookId).paused) return Result.success()
                    val before = store.progress(bookId)
                    if (before.completedMs >= duration) break
                    val start = before.completedMs
                    val end = (start + FullBookSubtitleStore.WINDOW_MS).coerceAtMost(duration)
                    val recognized = decoder.transcribe(
                        uri, start, end - start, sharedModel = model)
                    coroutineContext.ensureActive()
                    if (store.progress(bookId).paused || isStopped) return Result.success()
                    store.commit(bookId, start, end, recognized)
                    setForeground(getForegroundInfo())
                }
            }
            if (!store.progress(bookId).paused) store.markFinished(bookId)
            return Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            store.setError(bookId, error.message ?: "Transcription stopped unexpectedly.")
            store.setPaused(bookId, true)
            return Result.failure()
        } catch (error: LinkageError) {
            store.setError(bookId, "The speech engine could not load on this device.")
            store.setPaused(bookId, true)
            return Result.failure()
        }
    }
}
