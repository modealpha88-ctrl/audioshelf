package com.mohamed.audioshelf.data.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import com.mohamed.audioshelf.data.LibraryStore
import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** App-private torrent downloads. Never delete existing media or listening progress. */
data class TorrentJob(
    val id: String,
    val title: String,
    val input: String,
    val state: String = "queued",
    val progress: Int = 0,
    val speedBytes: Int = 0,
    val audioFiles: Int = 0,
    val error: String = "",
) {
    fun toJson() = JSONObject().put("id", id).put("title", title).put("input", input)
        .put("state", state).put("progress", progress).put("speedBytes", speedBytes)
        .put("audioFiles", audioFiles).put("error", error)

    companion object {
        fun fromJson(obj: JSONObject) = TorrentJob(
            id = obj.getString("id"), title = obj.optString("title"),
            input = obj.getString("input"), state = obj.optString("state", "queued"),
            progress = obj.optInt("progress"), speedBytes = obj.optInt("speedBytes"),
            audioFiles = obj.optInt("audioFiles"), error = obj.optString("error"),
        )
    }
}

object TorrentDownloads {
    private const val PREF = "audioshelf_torrent_jobs"
    private const val KEY = "jobs"
    private const val MAX_TORRENT_BYTES = 4 * 1024 * 1024
    private val lock = Any()
    private val audioExtensions = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")

    fun isAudioPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || '\\' in path) return false
        if (path.split('/').any { it == "." || it == ".." || it.isBlank() }) return false
        return path.substringAfterLast('.', "").lowercase() in audioExtensions
    }

    fun directory(context: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid torrent ID." }
        val music = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(File(music, "Torrents"), id)
    }

    fun jobs(context: Context): List<TorrentJob> = synchronized(lock) {
        runCatching {
            val array = JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"))
            (0 until array.length()).map { TorrentJob.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String): TorrentJob? = jobs(context).firstOrNull { it.id == id }

    fun update(context: Context, id: String, change: (TorrentJob) -> TorrentJob) = synchronized(lock) {
        val jobs = jobs(context).toMutableList()
        val index = jobs.indexOfFirst { it.id == id }
        if (index >= 0) {
            jobs[index] = change(jobs[index])
            persist(context, jobs)
        }
    }

    private fun persist(context: Context, items: List<TorrentJob>) {
        val data = JSONArray().apply { items.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, data.toString()).commit()
    }

    fun enqueue(context: Context, raw: String, title: String = "Torrent audiobook"): TorrentJob {
        val input = raw.trim()
        require(input.startsWith("magnet:?xt=urn:btih:", true) ||
            input.startsWith("magnet:?xt=urn:btmh:", true) ||
            (input.startsWith("https://", true) && Uri.parse(input).path.orEmpty().endsWith(".torrent", true))
        ) { "Paste a magnet link or an HTTPS .torrent file URL." }
        require(input.length <= 4096) { "Torrent link is too long." }
        val id = digest(input.toByteArray())
        return add(context, id, input, title)
    }

    fun enqueueDocument(context: Context, uri: Uri): TorrentJob {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            readLimited(input, MAX_TORRENT_BYTES)
        } ?: throw IllegalArgumentException("Cannot open the selected torrent file.")
        require(data.isNotEmpty()) { "Empty torrent file." }
        // Parse before queueing; rejects invalid .torrent documents.
        TorrentInfo(data)
        val id = digest(data)
        val metadataDir = File(context.filesDir, "torrent_metadata").also { it.mkdirs() }
        File(metadataDir, "$id.torrent").writeBytes(data)
        return add(context, id, "file:$id", "Imported audiobook")
    }

    private fun add(context: Context, id: String, input: String, title: String): TorrentJob {
        val app = context.applicationContext
        val item = synchronized(lock) {
            val existing = get(app, id)
            if (existing?.state == "completed") return@synchronized existing
            val candidate = (existing ?: TorrentJob(id, title.trim().take(140).ifBlank { "Audiobook" }, input))
                .copy(state = "queued", error = "")
            persist(app, listOf(candidate) + jobs(app).filterNot { it.id == id })
            candidate
        }
        if (item.state != "completed") schedule(app, id)
        return item
    }

    fun pause(context: Context, id: String) {
        update(context, id) { if (it.state == "completed") it else it.copy(state = "paused", speedBytes = 0) }
        WorkManager.getInstance(context).cancelUniqueWork("torrent-$id")
    }

    fun resume(context: Context, id: String) {
        val job = get(context, id) ?: return
        if (job.state == "completed") return
        update(context, id) { it.copy(state = "queued", error = "") }
        schedule(context.applicationContext, id)
    }

    private fun schedule(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<TorrentDownloadWorker>()
            .setInputData(workDataOf("torrent_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("audioshelf-torrent")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "torrent-$id", ExistingWorkPolicy.REPLACE, request,
        )
    }

    fun finish(context: Context, job: TorrentJob, audioFiles: Int) {
        val book = AudioBook(
            id = job.id,
            sourceId = "torrent_files",
            title = job.title,
            authors = "Local torrent audiobook",
            description = "Audio stored in AudioShelf's private music folder. Downloaded via BitTorrent.",
        )
        val library = LibraryStore(context)
        if (!library.contains(book)) library.toggle(book)
        update(context, job.id) { it.copy(state = "completed", progress = 100, speedBytes = 0, audioFiles = audioFiles, error = "") }
    }

    fun importExisting(context: Context, id: String): Boolean {
        val job = get(context, id) ?: return false
        if (job.state != "completed") return false
        val count = com.mohamed.audioshelf.data.source.TorrentAudioSource.audioFiles(context, id).size
        if (count == 0) return false
        finish(context, job, count)
        return true
    }

    internal fun readLimited(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            require(output.size() + n <= maxBytes) { "Torrent metadata exceeds 4 MB." }
            output.write(chunk, 0, n)
        }
        return output.toByteArray()
    }

    internal fun metadata(context: Context, input: String): ByteArray {
        if (input.startsWith("file:")) {
            val id = input.removePrefix("file:")
            require(id.matches(Regex("[a-f0-9]{32}")))
            return File(File(context.filesDir, "torrent_metadata"), "$id.torrent").readBytes()
        }
        var current = URL(input)
        repeat(4) {
            require(current.protocol == "https") { "Torrent metadata must use HTTPS." }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AudioShelf/0.5 (Android torrent importer)")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    current = URL(current, connection.getHeaderField("Location")
                        ?: throw IllegalArgumentException("Torrent redirect is missing a target."))
                } else {
                    require(status == 200) { "Cannot fetch torrent metadata: HTTP $status" }
                    return connection.inputStream.use { readLimited(it, MAX_TORRENT_BYTES) }
                }
            } finally { connection.disconnect() }
        }
        throw IllegalArgumentException("Too many torrent redirects.")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).take(16).joinToString("") { "%02x".format(it) }
}

/** Each download runs as cancellable, user-started foreground work. Partial pieces remain for recheck. */
class TorrentDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id = inputData.getString("torrent_id").orEmpty()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val job = TorrentDownloads.get(applicationContext, id) ?: return@withContext Result.failure()
        if (job.state == "completed" || job.state == "paused") return@withContext Result.success()
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(
            "torrent_downloads", "Audiobook torrent downloads", NotificationManager.IMPORTANCE_LOW
        ))
        fun foreground(percent: Int): ForegroundInfo {
            val notification = NotificationCompat.Builder(applicationContext, "torrent_downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("AudioShelf · " + job.title)
                .setContentText(if (percent == 0) "Finding peers and audiobook files…" else "Downloading audiobook · $percent%")
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(
                id.hashCode().and(0x7fffffff).coerceAtLeast(1), notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else ForegroundInfo(id.hashCode().and(0x7fffffff).coerceAtLeast(1), notification)
        }
        var session: SessionManager? = null
        try {
            setForeground(foreground(job.progress))
            val destination = TorrentDownloads.directory(applicationContext, id).apply { mkdirs() }
            val native = SessionManager(false).also { session = it; it.start() }
            val fromMagnet = job.input.startsWith("magnet:", true)
            var checked = false
            if (fromMagnet) {
                native.download(job.input, destination, torrent_flags_t())
            } else {
                val ti = TorrentInfo(TorrentDownloads.metadata(applicationContext, job.input))
                val priorities = selectAudio(ti, destination)
                native.download(ti, destination, null, priorities, null, torrent_flags_t())
                checked = true
            }
            var unseen = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (TorrentDownloads.get(applicationContext, id)?.state == "paused") break
                val handle = native.getTorrentHandles().firstOrNull()
                if (handle == null) {
                    if (++unseen > 120) throw IllegalStateException("The torrent engine could not start this download.")
                    delay(1000)
                    continue
                }
                if (!checked) {
                    val info = handle.torrentFile()
                    if (info != null) {
                        handle.prioritizeFiles(selectAudio(info, destination))
                        checked = true
                    }
                }
                if (checked) {
                    val status = handle.status()
                    val percent = (status.progress() * 100).toInt().coerceIn(0, 100)
                    TorrentDownloads.update(applicationContext, id) {
                        if (it.state == "paused") it else it.copy(
                            state = "downloading", progress = percent, speedBytes = status.downloadRate().coerceAtLeast(0),
                        )
                    }
                    if (percent % 5 == 0) setForeground(foreground(percent))
                    if (status.isFinished()) {
                        val count = com.mohamed.audioshelf.data.source.TorrentAudioSource
                            .audioFiles(applicationContext, id).size
                        require(count > 0) { "Torrent completed without playable audiobook audio." }
                        TorrentDownloads.finish(applicationContext, job, count)
                        break
                    }
                } else {
                    TorrentDownloads.update(applicationContext, id) { it.copy(state = "metadata", speedBytes = 0) }
                }
                delay(1200)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            TorrentDownloads.update(applicationContext, id) {
                if (it.state == "paused") it else it.copy(
                    state = "failed", speedBytes = 0, error = error.message.orEmpty().take(220)
                        .ifBlank { "Download failed. Check torrent peers and storage." },
                )
            }
            Result.failure()
        } finally {
            runCatching { session?.stop() }
        }
    }

    private fun selectAudio(info: TorrentInfo, folder: File): Array<Priority> {
        val fs = info.files()
        require(fs.numFiles() in 1..5000) { "Unsupported torrent file count." }
        var audioBytes = 0L
        var audioCount = 0
        val base = folder.canonicalPath + File.separator
        val selected = Array(fs.numFiles()) { index ->
            val path = fs.filePath(index)
            val allowed = TorrentDownloads.isAudioPath(path) &&
                File(folder, path).canonicalPath.startsWith(base)
            if (allowed) {
                audioCount++
                audioBytes += fs.fileSize(index)
                Priority.NORMAL
            } else Priority.IGNORE
        }
        require(audioCount > 0) { "This torrent contains no supported audiobook audio (MP3, M4B, M4A, AAC, OGG, FLAC or WAV)." }
        require(audioBytes <= 20L * 1024 * 1024 * 1024) { "Selected audio is larger than 20 GB." }
        return selected
    }
}
