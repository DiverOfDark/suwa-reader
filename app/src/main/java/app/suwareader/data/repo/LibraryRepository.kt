package app.suwareader.data.repo

import app.suwareader.data.JsonStore
import app.suwareader.data.api.SuwayomiApi
import app.suwareader.data.download.Downloads
import app.suwareader.data.sync.PendingProgress
import app.suwareader.data.sync.ProgressSync
import app.suwareader.domain.Chapter
import app.suwareader.domain.Library
import app.suwareader.domain.Manga
import app.suwareader.domain.MangaDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

sealed interface Connection {
    data object Unknown : Connection
    data object Online : Connection
    data class Offline(val reason: String) : Connection
}

/**
 * The server's library as last seen, kept on disk so the app works offline. Refreshing replaces it wholesale: the
 * server decides what's in the library. The only local overlay is [ProgressSync]'s unconfirmed reading progress.
 */
class LibraryRepository(
    private val api: SuwayomiApi,
    private val store: JsonStore,
    private val downloads: Downloads,
    private val scope: CoroutineScope,
) {
    lateinit var progressSync: ProgressSync

    private val _library = MutableStateFlow(store.read(LIBRARY_FILE, Library.serializer()))
    val library: StateFlow<Library?> = _library.asStateFlow()

    private val _connection = MutableStateFlow<Connection>(Connection.Unknown)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val details = ConcurrentHashMap<Long, MutableStateFlow<MangaDetails?>>()
    private val libraryMutex = Mutex()

    fun detailsFlow(mangaId: Long): StateFlow<MangaDetails?> = mutableDetails(mangaId)

    private fun mutableDetails(mangaId: Long): MutableStateFlow<MangaDetails?> = details.getOrPut(mangaId) {
        MutableStateFlow(store.read(detailsFile(mangaId), MangaDetails.serializer())?.withPending())
    }

    fun cachedManga(mangaId: Long): Manga? =
        detailsFlow(mangaId).value?.manga ?: _library.value?.manga?.find { it.id == mangaId }

    suspend fun <T> online(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            val r = block()
            _connection.value = Connection.Online
            Result.success(r)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is java.io.IOException && e !is app.suwareader.data.api.GraphQLException) {
                _connection.value = Connection.Offline(e.message ?: e.javaClass.simpleName)
            }
            Result.failure(e)
        }
    }

    suspend fun refreshLibrary(): Result<Library> = libraryMutex.withLock {
        _refreshing.value = true
        try {
            // Send local progress first so the fresh copy already includes it
            progressSync.flush()
            online { api.library() }.onSuccess { lib ->
                val adjusted = lib.withPendingUnread()
                store.write(LIBRARY_FILE, Library.serializer(), adjusted)
                _library.value = adjusted
                val ids = adjusted.manga.map { it.id }.toSet()
                // Manga gone from the server library lose their cached details and downloads.
                val keep = progressSync.pending.value.values.map { it.mangaId }.toSet()
                downloads.reconcileLibrary(ids, keep)
                details.keys.filter { it !in ids && it !in keep }.forEach { id ->
                    details.remove(id)
                    store.delete(detailsFile(id))
                }
                store.file("manga").listFiles()?.forEach { f ->
                    val id = f.nameWithoutExtension.toLongOrNull()
                    if (id != null && id !in ids && id !in keep) f.delete()
                }
            }
        } finally {
            _refreshing.value = false
        }
    }

    suspend fun refreshDetails(mangaId: Long): Result<MangaDetails> = online { api.mangaDetails(mangaId) }
        .onSuccess { d -> storeDetails(d) }

    private fun storeDetails(d: MangaDetails) {
        store.write(detailsFile(d.manga.id), MangaDetails.serializer(), d)
        val overlaid = d.withPending()
        mutableDetails(d.manga.id).value = overlaid
        if (d.manga.inLibrary) {
            val keep = progressSync.pending.value.keys
            downloads.reconcileChapters(d.manga.id, d.chapters.map { it.id }.toSet(), keep)
        }
        // Keep the library entry in step with what we just saw
        _library.update { lib ->
            lib?.copy(
                manga = lib.manga.map {
                    if (it.id == d.manga.id) {
                        overlaid.manga.copy(unreadCount = overlaid.chapters.count { c -> !c.isRead })
                    } else {
                        it
                    }
                },
            )
        }
    }

    suspend fun refreshFromSource(mangaId: Long): Result<MangaDetails> {
        online { api.refreshFromSource(mangaId) }.onFailure { return Result.failure(it) }
        return refreshDetails(mangaId)
    }

    // region progress
    /** Records progress locally (shown at once) and queues it for the server. */
    fun setProgress(
        manga: Manga,
        chapters: List<Chapter>,
        isRead: Boolean? = null,
        lastPageRead: Int? = null,
        isBookmarked: Boolean? = null,
        immediate: Boolean = false,
    ) {
        chapters.forEach { c ->
            progressSync.record(
                PendingProgress(
                    chapterId = c.id,
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterNumber = c.chapterNumber,
                    isRead = isRead,
                    lastPageRead = lastPageRead ?: if (isRead == false) 0 else null,
                    isBookmarked = isBookmarked,
                ),
                immediate = immediate,
            )
        }
        reapplyPending(manga.id)
    }

    fun reapplyPending(mangaId: Long) {
        val flow = mutableDetails(mangaId)
        val base = store.read(detailsFile(mangaId), MangaDetails.serializer()) ?: return
        val overlaid = base.withPending()
        flow.value = overlaid
        _library.update { lib ->
            lib?.copy(
                manga = lib.manga.map {
                    if (it.id == mangaId) it.copy(unreadCount = overlaid.chapters.count { c -> !c.isRead }) else it
                },
            )
        }
    }

    /** Called once the server accepted progress: pull its view of the affected manga. */
    suspend fun onProgressSynced(chapterIds: Set<Long>) {
        val mangaIds = details.values.mapNotNull { it.value }
            .filter { d -> d.chapters.any { it.id in chapterIds } }
            .map { it.manga.id }
        mangaIds.forEach { refreshDetails(it) }
    }

    /** Where a pending entry goes when its chapter is gone: same title elsewhere in the library, same number. */
    suspend fun remapPending(entry: PendingProgress): Long? {
        val lib = api.library()
        val title = entry.mangaTitle.normalizedTitle()
        val candidates = lib.manga.filter { it.title.normalizedTitle() == title }
        for (m in candidates) {
            val d = api.mangaDetails(m.id)
            val match = d.chapters.firstOrNull { abs(it.chapterNumber - entry.chapterNumber) < 0.001 && it.chapterNumber >= 0 }
            if (match != null && match.id != entry.chapterId) return match.id
        }
        return null
    }
    // endregion

    private fun MangaDetails.withPending(): MangaDetails {
        if (!::progressSync.isInitialized) return this
        val pending = progressSync.pending.value
        if (pending.isEmpty()) return this
        return copy(
            chapters = chapters.map { c ->
                val p = pending[c.id] ?: return@map c
                c.copy(
                    isRead = p.isRead ?: c.isRead,
                    lastPageRead = p.lastPageRead ?: c.lastPageRead,
                    isBookmarked = p.isBookmarked ?: c.isBookmarked,
                    lastReadAt = if (p.lastPageRead != null || p.isRead == true) p.updatedAt / 1000 else c.lastReadAt,
                )
            },
        )
    }

    private fun Library.withPendingUnread(): Library {
        if (!::progressSync.isInitialized || progressSync.pending.value.isEmpty()) return this
        val affected = progressSync.pending.value.values.map { it.mangaId }.toSet()
        return copy(
            manga = manga.map { m ->
                if (m.id !in affected) return@map m
                val d = detailsFlow(m.id).value ?: return@map m
                m.copy(unreadCount = d.chapters.count { !it.isRead })
            },
        )
    }

    fun launchRefreshLibrary() {
        scope.launch { refreshLibrary() }
    }

    companion object {
        private const val LIBRARY_FILE = "library.json"
        private fun detailsFile(id: Long) = "manga/$id.json"
    }
}

fun String.normalizedTitle(): String =
    lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
