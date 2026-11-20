package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.suwareader.SuwaApp
import app.suwareader.domain.MangaDetails
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.loader.SuwayomiPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import kotlin.time.Instant
import app.suwareader.domain.Chapter as AppChapter
import app.suwareader.domain.Manga as AppManga
import tachiyomi.domain.chapter.model.Chapter as ReaderDbChapter

/** What the vendored viewers know about the manga being read. */
data class ReaderManga(
    val id: Long,
    val title: String,
    val source: Long?,
    val readingMode: Int?,
    val orientation: Int?,
)

class ReaderViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val container = (application as SuwaApp).container
    private val repository = container.repository
    private val downloads = container.downloads
    private val readerPreferences = container.readerPreferences
    private val mangaPrefs = application.getSharedPreferences("reader_manga", Context.MODE_PRIVATE)

    val mangaId: Long = savedState.get<Long>(ReaderActivity.EXTRA_MANGA) ?: -1L
    private var chapterId: Long = savedState.get<Long>(ReaderActivity.EXTRA_CHAPTER) ?: -1L
    val hasValidArgs get() = mangaId != -1L && chapterId != -1L

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>(Channel.UNLIMITED)
    val eventFlow = eventChannel.receiveAsFlow()

    val manga: ReaderManga? get() = state.value.manga

    /** The viewers only pass this along; there is no per-source behaviour here. */
    val source: Any? get() = state.value.manga

    private var appManga: AppManga? = null
    private var appChapters: Map<Long, AppChapter> = emptyMap()
    private var chapterList: List<ReaderChapter> = emptyList()
    private val toDeleteOnExit = mutableSetOf<Long>()

    init {
        if (hasValidArgs) viewModelScope.launch(Dispatchers.IO) { init() }
    }

    private suspend fun init() {
        try {
            val details: MangaDetails = repository.detailsFlow(mangaId).value
                ?.takeIf { d -> d.chapters.any { it.id == chapterId } }
                ?: repository.refreshDetails(mangaId).getOrThrow()
            setDetails(details)
            val start = chapterList.firstOrNull { it.chapter.id == chapterId }
                ?: error("This chapter is no longer on the server")
            loadChapter(start)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR) { e.asLog() }
            mutableState.update { it.copy(initError = e) }
        }
    }

    private fun setDetails(details: MangaDetails) {
        val m = details.manga
        // A change made on this phone that the server hasn't confirmed yet wins over the server's copy
        val localNewer = mangaPrefs.getLong("ts_${m.id}", 0) > details.fetchedAt
        appManga = m
        appChapters = details.chapters.associateBy { it.id }
        chapterList = details.chapters
            .sortedWith(compareBy<AppChapter> { it.sourceOrder }.thenBy { it.chapterNumber })
            .map { ReaderChapter(it.toReaderChapter()) }
        mutableState.update {
            it.copy(
                manga = ReaderManga(
                    id = m.id,
                    title = m.title,
                    source = m.sourceId?.toLongOrNull(),
                    readingMode = if (localNewer) mangaPrefs.getInt("mode_${m.id}", 0) else m.meta[META_MODE]?.toIntOrNull() ?: 0,
                    orientation = if (localNewer) mangaPrefs.getInt("orientation_${m.id}", 0) else m.meta[META_ORIENTATION]?.toIntOrNull() ?: 0,
                ),
            )
        }
    }

    private fun AppChapter.toReaderChapter() = ReaderDbChapter(
        id = id,
        mangaId = mangaId,
        read = isRead,
        bookmark = isBookmarked,
        lastPageRead = lastPageRead,
        dateFetch = Instant.fromEpochSeconds(fetchedAt),
        sourceOrder = sourceOrder.toLong(),
        url = "",
        name = name,
        dateUpload = uploadDate.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) },
        chapterNumber = chapterNumber,
        scanlator = scanlator?.takeUnless { it.isBlank() || it == "Unknown" },
        memo = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    // region chapter loading
    private suspend fun loadPages(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null) return
        chapter.state = ReaderChapter.State.Loading
        withContext(Dispatchers.IO) {
            try {
                val loader = pageLoaderFor(chapter)
                chapter.pageLoader = loader
                val pages = loader.getPages().onEach { it.chapter = chapter }
                if (pages.isEmpty()) throw Exception("The server returned no pages for this chapter")
                if (!chapter.chapter.read) chapter.requestedPage = chapter.chapter.lastPageRead
                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    private fun pageLoaderFor(chapter: ReaderChapter): PageLoader {
        val files = downloads.pageFiles(chapter.chapter.id)
        if (!files.isNullOrEmpty()) return LocalPageLoader(files)
        return SuwayomiPageLoader(
            chapter = chapter,
            api = container.api,
            client = container.httpClient,
            absoluteUrl = { container.absoluteUrl(it) ?: it },
            cacheDir = File(container.readerCacheDir, chapter.chapter.id.toString()),
            canLoadAhead = ::canLoadAhead,
        )
    }

    private suspend fun loadChapter(chapter: ReaderChapter): ViewerChapters {
        loadPages(chapter)
        val pos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(chapter, chapterList.getOrNull(pos - 1), chapterList.getOrNull(pos + 1))
        preloadNextChapterAfter(newChapters)
        withContext(Dispatchers.Main) {
            mutableState.update {
                newChapters.ref()
                it.viewerChapters?.unref()
                it.copy(viewerChapters = newChapters, bookmarked = chapter.chapter.bookmark)
            }
        }
        return newChapters
    }

    private fun loadNewChapter(chapter: ReaderChapter) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadChapter(chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR) { e.asLog() }
            }
        }
    }

    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withContext(Dispatchers.IO) { loadChapter(chapter) }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR) { e.asLog() }
            eventChannel.trySend(Event.Message("Couldn't open chapter: ${e.message}"))
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    suspend fun loadNextChapter() {
        val next = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(next)
    }

    suspend fun loadPreviousChapter() {
        val prev = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prev)
    }

    private var nextChapterPreloadJob: Job? = null

    private fun preloadNextChapterAfter(chapters: ViewerChapters) {
        val next = chapters.nextChapter ?: return
        nextChapterPreloadJob?.cancel()
        nextChapterPreloadJob = viewModelScope.launch(Dispatchers.IO) {
            if (!canLoadAhead()) return@launch
            val current = chapters.currChapter
            if (current.pageLoader is SuwayomiPageLoader) {
                current.pages.orEmpty().forEach { page ->
                    page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
            }
            preload(next)
        }
    }

    private fun canLoadAhead(): Boolean {
        if (!readerPreferences.loadAheadOnlyOverWifi.get()) return true
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) return
        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) return
        try {
            loadPages(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun requestPreload(chapter: ReaderChapter) {
        viewModelScope.launch { preload(chapter) }
    }
    // endregion

    // region progress
    fun onPageSelected(page: ReaderPage) {
        if (page is InsertPage) return
        val selected = page.chapter
        val pages = selected.pages ?: return
        updateChapterProgress(selected, page)
        if (selected != state.value.viewerChapters?.currChapter) loadNewChapter(selected)
        if (page.number.toDouble() / pages.size > 0.25) downloadAhead(selected)
    }

    private fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val index = page.index
        mutableState.update { it.copy(currentPage = index + 1) }
        readerChapter.requestedPage = index
        if (page.status is Page.State.Error) return

        val isLast = readerChapter.pages?.lastIndex == index
        val wasRead = readerChapter.chapter.read
        // Nothing changed (e.g. the first page of the next chapter peeking in): don't touch the server
        if (!isLast && index == readerChapter.chapter.lastPageRead) return
        readerChapter.update { it.copy(lastPageRead = index, read = it.read || isLast) }
        val manga = appManga ?: return
        val chapter = appChapters[readerChapter.chapter.id] ?: return
        repository.setProgress(
            manga = manga,
            chapters = listOf(chapter),
            isRead = if (isLast && !wasRead) true else null,
            lastPageRead = index,
            immediate = isLast,
        )
        if (isLast && container.prefs.deleteAfterReading.get() && downloads.isChapterDownloaded(chapter.id)) {
            toDeleteOnExit += chapter.id
        }
    }

    private var downloadAheadDoneFor: Long = -1

    private fun downloadAhead(current: ReaderChapter) {
        val amount = container.prefs.autoDownloadAhead.get()
        if (amount <= 0 || downloadAheadDoneFor == current.chapter.id) return
        if (!downloads.isChapterDownloaded(current.chapter.id)) return
        downloadAheadDoneFor = current.chapter.id
        val manga = appManga ?: return
        val pos = chapterList.indexOf(current)
        val next = chapterList.drop(pos + 1)
            .filter { !it.chapter.read }
            .take(amount)
            .mapNotNull { appChapters[it.chapter.id] }
        downloads.enqueue(manga, next)
    }

    fun toggleBookmark() {
        val current = state.value.viewerChapters?.currChapter ?: return
        val manga = appManga ?: return
        val chapter = appChapters[current.chapter.id] ?: return
        val value = !current.chapter.bookmark
        current.update { it.copy(bookmark = value) }
        repository.setProgress(manga, listOf(chapter), isBookmarked = value, immediate = true)
        mutableState.update { it.copy(bookmarked = value) }
    }
    // endregion

    // region per-manga settings
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val mode = state.value.manga?.readingMode ?: 0
        return if (resolveDefault && ReadingMode.fromPreference(mode) == ReadingMode.DEFAULT) default else mode
    }

    fun setMangaReadingMode(mode: ReadingMode) {
        val m = state.value.manga ?: return
        mangaPrefs.edit().putInt("mode_${m.id}", mode.flagValue).apply()
        saveMeta(m.id, META_MODE, mode.flagValue)
        mutableState.update { it.copy(manga = m.copy(readingMode = mode.flagValue)) }
        // Re-set chapters on the new viewer
        viewModelScope.launch {
            val current = state.value.viewerChapters?.currChapter ?: return@launch
            eventChannel.send(Event.ReloadViewerChapters)
            current.requestedPage = current.chapter.lastPageRead
        }
    }

    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val o = state.value.manga?.orientation ?: 0
        return if (resolveDefault && ReaderOrientation.fromPreference(o) == ReaderOrientation.DEFAULT) default else o
    }

    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val m = state.value.manga ?: return
        mangaPrefs.edit().putInt("orientation_${m.id}", orientation.flagValue).apply()
        saveMeta(m.id, META_ORIENTATION, orientation.flagValue)
        mutableState.update { it.copy(manga = m.copy(orientation = orientation.flagValue)) }
        eventChannel.trySend(Event.SetOrientation(getMangaOrientation()))
    }
    // endregion

    /** Per-series reader settings live on the server too, so every device opens a series the same way. */
    private fun saveMeta(mangaId: Long, key: String, value: Int) {
        mangaPrefs.edit().putLong("ts_$mangaId", System.currentTimeMillis()).apply()
        container.appScope.launch {
            repository.online { container.api.setMangaMeta(mangaId, key, value.toString()) }
                .onSuccess { repository.refreshDetails(mangaId) }
        }
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun openSettings() = mutableState.update { it.copy(dialog = Dialog.Settings) }
    fun openReadingModeSelect() = mutableState.update { it.copy(dialog = Dialog.ReadingMode) }
    fun closeDialog() = mutableState.update { it.copy(dialog = null) }

    fun onActivityFinish() {
        container.progressSync.flushSoon()
    }

    override fun onCleared() {
        state.value.viewerChapters?.unref()
        if (toDeleteOnExit.isNotEmpty()) {
            val ids = toDeleteOnExit.toList()
            container.appScope.launch(Dispatchers.IO) { downloads.delete(ids) }
        }
        container.progressSync.flushSoon()
    }

    data class State(
        val manga: ReaderManga? = null,
        val viewerChapters: ViewerChapters? = null,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,
        val menuVisible: Boolean = false,
        val initError: Throwable? = null,
        val bookmarked: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val currentChapter: ReaderChapter? get() = viewerChapters?.currChapter
        val totalPages: Int get() = currentChapter?.pages?.size ?: -1
    }

    companion object {
        const val META_MODE = "suwa_readingMode"
        const val META_ORIENTATION = "suwa_orientation"
    }

    sealed interface Dialog {
        data object Settings : Dialog
        data object ReadingMode : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data class SetOrientation(val orientation: Int) : Event
        data class Message(val text: String) : Event
    }
}
