package app.suwareader.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.suwareader.data.JsonStore
import app.suwareader.data.prefs.AppPreferences
import app.suwareader.domain.Chapter
import app.suwareader.domain.Manga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import java.io.File
import eu.kanade.tachiyomi.data.download.DownloadManager as ReaderDownloadManager

@Serializable
data class DownloadedChapter(
    val chapter: Chapter,
    val mangaId: Long,
    val mangaTitle: String,
    val pageCount: Int,
    val sizeBytes: Long = 0,
    val completedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class QueuedDownload(
    val chapter: Chapter,
    val mangaId: Long,
    val mangaTitle: String,
    val state: State = State.QUEUED,
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val error: String? = null,
) {
    enum class State { QUEUED, DOWNLOADING, ERROR }
}

/**
 * Chapters kept on the phone for offline reading. Layout: downloads/<mangaId>/<chapterId>/<page files> plus a
 * meta.json written last, so a chapter only counts as downloaded once every page is on disk.
 */
class Downloads(
    private val context: Context,
    private val store: JsonStore,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) : ReaderDownloadManager {

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val queueSerializer = ListSerializer(QueuedDownload.serializer())

    private val _downloaded = MutableStateFlow<Map<Long, DownloadedChapter>>(emptyMap())
    val downloaded: StateFlow<Map<Long, DownloadedChapter>> = _downloaded.asStateFlow()

    private val _queue = MutableStateFlow(
        store.read(QUEUE_FILE, queueSerializer).orEmpty().map {
            if (it.state == QueuedDownload.State.DOWNLOADING) it.copy(state = QueuedDownload.State.QUEUED) else it
        },
    )
    val queue: StateFlow<List<QueuedDownload>> = _queue.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { scan() }
        if (_queue.value.any { it.state == QueuedDownload.State.QUEUED }) start()
    }

    override fun isChapterDownloaded(chapterId: Long): Boolean = chapterId in _downloaded.value

    fun chapterDir(mangaId: Long, chapterId: Long) = File(File(root, mangaId.toString()), chapterId.toString())

    fun pageFiles(chapterId: Long): List<File>? {
        val d = _downloaded.value[chapterId] ?: return null
        val dir = chapterDir(d.mangaId, chapterId)
        return dir.listFiles()
            ?.filter { it.isFile && it.name != META && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
    }

    private fun scan() {
        val found = mutableMapOf<Long, DownloadedChapter>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { mangaDir ->
            mangaDir.listFiles()?.filter { it.isDirectory }?.forEach { chapterDir ->
                val meta = File(chapterDir, META)
                if (meta.exists()) {
                    runCatching { json.decodeFromString(DownloadedChapter.serializer(), meta.readText()) }
                        .onSuccess { found[it.chapter.id] = it }
                        .onFailure { chapterDir.deleteRecursively() }
                } else if (_queue.value.none { it.chapter.id.toString() == chapterDir.name }) {
                    // Leftover of an interrupted download that's no longer queued
                    chapterDir.deleteRecursively()
                }
            }
        }
        _downloaded.value = found
    }

    internal fun markDownloaded(entry: DownloadedChapter) {
        val dir = chapterDir(entry.mangaId, entry.chapter.id)
        File(dir, META).writeText(json.encodeToString(DownloadedChapter.serializer(), entry))
        _downloaded.update { it + (entry.chapter.id to entry) }
    }

    fun enqueue(manga: Manga, chapters: List<Chapter>) {
        val existing = _queue.value.map { it.chapter.id }.toSet()
        val toAdd = chapters
            .filter { it.id !in existing && it.id !in _downloaded.value }
            .sortedWith(compareBy({ it.sourceOrder }))
            .map { QueuedDownload(chapter = it, mangaId = manga.id, mangaTitle = manga.title) }
        if (toAdd.isEmpty()) return
        _queue.update { it + toAdd }
        persistQueue()
        start()
    }

    fun cancel(chapterIds: Collection<Long>) {
        val ids = chapterIds.toSet()
        val removed = _queue.value.filter { it.chapter.id in ids }
        _queue.update { q -> q.filterNot { it.chapter.id in ids } }
        persistQueue()
        removed.forEach { chapterDir(it.mangaId, it.chapter.id).takeIf { d -> it.chapter.id !in _downloaded.value }?.deleteRecursively() }
    }

    fun clearQueue() = cancel(_queue.value.map { it.chapter.id })

    fun retry(chapterId: Long) {
        _queue.update { q -> q.map { if (it.chapter.id == chapterId && it.state == QueuedDownload.State.ERROR) it.copy(state = QueuedDownload.State.QUEUED, error = null) else it } }
        persistQueue()
        start()
    }

    fun retryFailed() {
        _queue.update { q -> q.map { if (it.state == QueuedDownload.State.ERROR) it.copy(state = QueuedDownload.State.QUEUED, error = null) else it } }
        persistQueue()
        start()
    }

    /** Errors from a lost connection go back in the queue so they retry with the worker. */
    internal fun retryFailedLater() {
        _queue.update { q -> q.map { if (it.state == QueuedDownload.State.ERROR) it.copy(state = QueuedDownload.State.QUEUED) else it } }
        persistQueue()
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
        if (!paused) start() else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun move(chapterId: Long, toTop: Boolean) {
        _queue.update { q ->
            val item = q.find { it.chapter.id == chapterId } ?: return@update q
            val rest = q - item
            if (toTop) listOf(item) + rest else rest + item
        }
        persistQueue()
    }

    fun delete(chapterIds: Collection<Long>) {
        cancel(chapterIds)
        val current = _downloaded.value
        chapterIds.forEach { id ->
            val d = current[id] ?: return@forEach
            chapterDir(d.mangaId, id).deleteRecursively()
        }
        _downloaded.update { it - chapterIds.toSet() }
        cleanEmptyMangaDirs()
    }

    fun deleteManga(mangaId: Long) {
        cancel(_queue.value.filter { it.mangaId == mangaId }.map { it.chapter.id })
        File(root, mangaId.toString()).deleteRecursively()
        _downloaded.update { m -> m.filterValues { it.mangaId != mangaId } }
    }

    /** Library is the server's: downloads of manga no longer in it go away. */
    fun reconcileLibrary(libraryMangaIds: Set<Long>, keep: Set<Long>) {
        val stale = (_downloaded.value.values.map { it.mangaId } + _queue.value.map { it.mangaId })
            .toSet() - libraryMangaIds - keep
        stale.forEach {
            logcat { "Manga $it left the server library, deleting its downloads" }
            deleteManga(it)
        }
    }

    /** Chapters the server no longer lists for this manga (e.g. after a re-fetch) are removed from the phone too. */
    fun reconcileChapters(mangaId: Long, serverChapterIds: Set<Long>, keep: Set<Long>) {
        val stale = _downloaded.value.values.filter { it.mangaId == mangaId && it.chapter.id !in serverChapterIds && it.chapter.id !in keep }
        if (stale.isNotEmpty()) delete(stale.map { it.chapter.id })
        val staleQueued = _queue.value.filter { it.mangaId == mangaId && it.chapter.id !in serverChapterIds }
        if (staleQueued.isNotEmpty()) cancel(staleQueued.map { it.chapter.id })
    }

    fun totalSizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun cleanEmptyMangaDirs() {
        root.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
    }

    // region worker API
    /** Takes the next queued chapter and marks it downloading, so parallel workers never pick the same one. */
    @Synchronized
    internal fun claimNext(): QueuedDownload? {
        if (_paused.value) return null
        val next = _queue.value.firstOrNull { it.state == QueuedDownload.State.QUEUED } ?: return null
        val claimed = next.copy(state = QueuedDownload.State.DOWNLOADING, error = null, pagesDone = 0)
        _queue.update { q -> q.map { if (it.chapter.id == next.chapter.id) claimed else it } }
        return claimed
    }

    internal fun updateItem(chapterId: Long, transform: (QueuedDownload) -> QueuedDownload) {
        _queue.update { q -> q.map { if (it.chapter.id == chapterId) transform(it) else it } }
    }

    internal fun finishItem(chapterId: Long) {
        _queue.update { q -> q.filterNot { it.chapter.id == chapterId } }
        persistQueue()
    }

    internal fun persistQueue() {
        store.write(QUEUE_FILE, queueSerializer, _queue.value)
    }

    internal fun isQueued(chapterId: Long) = _queue.value.any { it.chapter.id == chapterId }
    // endregion

    fun start() {
        if (_paused.value) return
        val network = if (prefs.downloadOnlyOnWifi.get()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val META = "meta.json"
        private const val QUEUE_FILE = "download_queue.json"
        const val WORK_NAME = "downloads"

        @Suppress("unused")
        private val log = LogPriority.DEBUG
    }
}
