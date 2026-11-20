package app.suwareader.data.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.suwareader.R
import app.suwareader.SuwaApp
import app.suwareader.util.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as SuwaApp).container
    private val downloads = container.downloads

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(notification("Preparing downloads", 0, 0))

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Couldn't start downloads in the foreground: ${e.message}" }
        }
        var failures = 0
        while (!isStopped) {
            val item = downloads.nextQueued() ?: break
            val id = item.chapter.id
            downloads.updateItem(id) { it.copy(state = QueuedDownload.State.DOWNLOADING, error = null, pagesDone = 0) }
            try {
                download(item)
                downloads.finishItem(id)
                failures = 0
            } catch (e: CancellationException) {
                downloads.updateItem(id) { it.copy(state = QueuedDownload.State.QUEUED) }
                downloads.persistQueue()
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Download of ${item.mangaTitle} / ${item.chapter.name} failed: ${e.asLog()}" }
                downloads.updateItem(id) { it.copy(state = QueuedDownload.State.ERROR, error = e.message ?: e.javaClass.simpleName) }
                downloads.persistQueue()
                failures++
                // The server is probably gone: stop and let WorkManager try again on the next network change.
                if (failures >= 3 && e is IOException) {
                    downloads.retryFailedLater()
                    return Result.retry()
                }
            }
        }
        Notifications.cancel(applicationContext, Notifications.ID_DOWNLOAD)
        return Result.success()
    }

    private suspend fun download(item: QueuedDownload) = withContext(Dispatchers.IO) {
        val base = container.prefs.serverUrl.get().trimEnd('/')
        val pages = container.api.chapterPages(item.chapter.id)
        if (pages.isEmpty()) throw IOException("The server returned no pages")
        val dir = downloads.chapterDir(item.mangaId, item.chapter.id).apply { mkdirs() }
        val done = AtomicInteger(dir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0)
        downloads.updateItem(item.chapter.id) { it.copy(pagesTotal = pages.size, pagesDone = done.get()) }
        setProgressNotification(item, done.get(), pages.size)
        val semaphore = Semaphore(3)
        val client = container.httpClient
        coroutineScope {
            pages.mapIndexed { index, url ->
                async {
                    semaphore.withPermit {
                        val prefix = "%04d".format(index)
                        if (dir.listFiles()?.any { it.name.startsWith("$prefix.") && !it.name.endsWith(".tmp") } == true) return@withPermit
                        if (!downloads.isQueued(item.chapter.id)) throw CancellationException("Removed from queue")
                        val request = Request.Builder().url(if (url.startsWith("http")) url else base + url).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Page ${index + 1}: HTTP ${response.code}")
                            val ext = when (response.body.contentType()?.subtype?.lowercase()) {
                                "png" -> "png"
                                "webp" -> "webp"
                                "gif" -> "gif"
                                "avif" -> "avif"
                                "jxl" -> "jxl"
                                "heif", "heic" -> "heif"
                                else -> "jpg"
                            }
                            val tmp = File(dir, "$prefix.$ext.tmp")
                            tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                            tmp.renameTo(File(dir, "$prefix.$ext"))
                        }
                        val n = done.incrementAndGet()
                        downloads.updateItem(item.chapter.id) { it.copy(pagesDone = n) }
                        setProgressNotification(item, n, pages.size)
                    }
                }
            }.awaitAll()
        }
        if (!downloads.isQueued(item.chapter.id)) {
            dir.deleteRecursively()
            return@withContext
        }
        val size = dir.listFiles()?.sumOf { it.length() } ?: 0
        downloads.markDownloaded(
            DownloadedChapter(
                chapter = item.chapter.copy(pageCount = pages.size),
                mangaId = item.mangaId,
                mangaTitle = item.mangaTitle,
                pageCount = pages.size,
                sizeBytes = size,
            ),
        )
    }

    private suspend fun setProgressNotification(item: QueuedDownload, done: Int, total: Int) {
        val remaining = downloads.queue.value.size
        val title = if (remaining > 1) "Downloading · $remaining left" else "Downloading"
        val n = notification("$title\n${item.mangaTitle} · ${item.chapter.name}", done, total)
        try {
            setForeground(foregroundInfo(n))
        } catch (e: Exception) {
            Notifications.notify(applicationContext, Notifications.ID_DOWNLOAD, n)
        }
    }

    private fun notification(text: String, progress: Int, max: Int): Notification {
        val lines = text.split("\n")
        return NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(lines.first())
            .setContentText(lines.getOrNull(1))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(max, progress, max == 0)
            .setContentIntent(Notifications.openAppIntent(applicationContext))
            .build()
    }

    private fun foregroundInfo(n: Notification) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(Notifications.ID_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(Notifications.ID_DOWNLOAD, n)
    }
}
