package app.suwareader.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.suwareader.SuwaApp
import app.suwareader.data.JsonStore
import app.suwareader.data.api.SuwayomiApi
import app.suwareader.data.api.SuwayomiApi.ChapterPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.util.concurrent.TimeUnit

/**
 * Reading progress made on this device that the server hasn't confirmed yet. This is the only state the phone owns:
 * everything else is the server's. Entries are sent as soon as the server is reachable and removed once accepted.
 */
@Serializable
data class PendingProgress(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterNumber: Double,
    val isRead: Boolean? = null,
    val lastPageRead: Int? = null,
    val isBookmarked: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

class ProgressSync(
    private val context: Context,
    private val store: JsonStore,
    private val api: SuwayomiApi,
    private val scope: CoroutineScope,
    /** Finds the chapter a pending entry should move to when its chapter is gone from the server (migrations). */
    private val remap: suspend (PendingProgress) -> Long?,
    private val onSynced: suspend (Set<Long>) -> Unit,
) {
    private val serializer = MapSerializer(Long.serializer(), PendingProgress.serializer())
    private val _pending = MutableStateFlow(store.read(FILE, serializer).orEmpty())
    val pending: StateFlow<Map<Long, PendingProgress>> = _pending.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val mutex = Mutex()
    private var debounceJob: Job? = null

    fun record(entry: PendingProgress, immediate: Boolean = false) {
        _pending.update { current ->
            val existing = current[entry.chapterId]
            val merged = if (existing == null) {
                entry
            } else {
                entry.copy(
                    isRead = entry.isRead ?: existing.isRead,
                    lastPageRead = entry.lastPageRead ?: existing.lastPageRead,
                    isBookmarked = entry.isBookmarked ?: existing.isBookmarked,
                )
            }
            current + (entry.chapterId to merged)
        }
        persist()
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (!immediate) delay(1500)
            flush()
        }
    }

    fun flushSoon() {
        scope.launch { flush() }
    }

    /** Sends everything pending. Returns true when nothing is left. */
    suspend fun flush(): Boolean = mutex.withLock {
        val snapshot = _pending.value
        if (snapshot.isEmpty()) return@withLock true
        try {
            val failed = api.updateChapters(snapshot.values.map { it.toPatch(it.chapterId) })
            val remapped = mutableMapOf<Long, Long>()
            val dropped = mutableSetOf<Long>()
            for (id in failed) {
                val entry = snapshot[id] ?: continue
                val target = runCatching { remap(entry) }.getOrNull()
                if (target != null && target != id) {
                    val stillFailed = api.updateChapters(listOf(entry.toPatch(target)))
                    if (stillFailed.isEmpty()) remapped[id] = target else dropped += id
                } else {
                    dropped += id
                }
            }
            if (dropped.isNotEmpty()) {
                logcat(LogPriority.WARN) { "Dropping progress for chapters gone from the server: $dropped" }
            }
            _pending.update { current ->
                // Keep entries that changed while we were sending
                current.filter { (id, entry) -> snapshot[id]?.updatedAt != entry.updatedAt }
            }
            persist()
            _lastError.value = null
            onSynced(snapshot.keys + remapped.values)
            _pending.value.isEmpty()
        } catch (e: Exception) {
            logcat(LogPriority.INFO) { "Progress sync deferred: ${e.asLog()}" }
            _lastError.value = e.message
            scheduleRetry()
            false
        }
    }

    private fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun persist() {
        store.write(FILE, serializer, _pending.value)
    }

    private fun PendingProgress.toPatch(id: Long) = ChapterPatch(
        chapterId = id,
        isRead = isRead,
        lastPageRead = lastPageRead,
        isBookmarked = isBookmarked,
    )

    companion object {
        private const val FILE = "pending_progress.json"
        private const val WORK_NAME = "progress-sync"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as SuwaApp).container.progressSync
        return if (sync.flush()) Result.success() else Result.retry()
    }
}
