package eu.kanade.tachiyomi.ui.reader.loader

import app.suwareader.data.api.SuwayomiApi
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads a chapter's pages from the Suwayomi server, caching each image on disk for the reading session. Page
 * prioritisation (current page, its neighbours, then outwards in growing chunks) follows Mihon's HttpPageLoader.
 */
internal class SuwayomiPageLoader(
    private val chapter: ReaderChapter,
    private val api: SuwayomiApi,
    private val client: OkHttpClient,
    private val absoluteUrl: (String) -> String,
    private val cacheDir: File,
    private val canLoadAhead: () -> Boolean,
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunkProcessLock = Any()
    private var chunkProcessJob: Job? = null
    private val downloadJobs = ConcurrentHashMap<Int, Job>()

    override var isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        val urls = api.chapterPages(chapter.chapter.id)
        cacheDir.mkdirs()
        cacheDir.setLastModified(System.currentTimeMillis())
        return urls.mapIndexed { index, url ->
            val abs = absoluteUrl(url)
            ReaderPage(index, abs, abs).also { it.chapter = chapter }
        }.also { it.loadByRollingChunked(0, 1, 1) }
    }

    override suspend fun loadPage(page: ReaderPage) = withContext(Dispatchers.IO) {
        if (page.status == Page.State.Ready && !fileFor(page).exists()) {
            page.status = Page.State.Queue
        }
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        launchLoadOnce(page)

        val pages = page.chapter.pages.orEmpty()
        if (page.index !in pages.indices) return@withContext
        pages.loadByRollingChunked(page.index, 3, 5) { chunks ->
            val continueLoading = chunks.take(2).flatten().map { it.index }.toSet()
            downloadJobs.keys.filter { it !in continueLoading }.forEach { downloadJobs.remove(it)?.cancel() }
        }
    }

    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) page.status = Page.State.Queue
        downloadJobs.remove(page.index)?.cancel()
        val job = scope.launch { internalLoadPage(page, force = true) }
        downloadJobs[page.index] = job
        job.invokeOnCompletion { downloadJobs.remove(page.index, job) }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        downloadJobs.clear()
    }

    private fun fileFor(page: ReaderPage) = File(cacheDir, page.index.toString())

    private fun launchLoadOnce(page: ReaderPage): Job? {
        val pageIndex = page.index
        downloadJobs[pageIndex]?.takeIf { it.isActive }?.let { return it }
        if (page.status !is Page.State.Queue && page.status !is Page.State.Error) return null
        val job = scope.launch(start = CoroutineStart.LAZY) { internalLoadPage(page) }
        val running = downloadJobs.putIfAbsent(pageIndex, job)
        if (running != null) {
            job.cancel()
            return running
        }
        job.invokeOnCompletion { downloadJobs.remove(pageIndex, job) }
        job.start()
        return job
    }

    private fun List<ReaderPage>.loadByRollingChunked(
        index: Int,
        chunkStartSize: Int,
        chunkEndSize: Int,
        onNewChunks: (List<List<ReaderPage>>) -> Unit = {},
    ) {
        val items = this
        val window = if (canLoadAhead()) items.indices else (index - 1)..(index + LIMITED_PAGES_AHEAD)
        val chunks = buildList {
            items.getOrNull(index)?.let(::add)
            items.getOrNull(index - 1)?.let(::add)
            items.getOrNull(index + 1)?.let(::add)
            if (index < items.lastIndex - 1) addAll(items.subList(index + 2, items.size))
            if (index > 1) addAll(items.subList(0, index - 1).reversed())
        }
            .filter { it.index in window }
            .rollingChunked(chunkStartSize, chunkEndSize)
            .also(onNewChunks)

        synchronized(chunkProcessLock) {
            chunkProcessJob?.cancel()
            chunkProcessJob = scope.launch {
                for (chunk in chunks) {
                    chunk.mapNotNull { launchLoadOnce(it) }.joinAll()
                }
            }
        }
    }

    private fun <T> List<T>.rollingChunked(startSize: Int, endSize: Int): List<List<T>> {
        val result = ArrayList<List<T>>()
        var chunkSize = startSize
        var i = 0
        while (i < size) {
            val n = chunkSize.coerceAtMost(size - i)
            result.add(List(n) { this[it + i] })
            i += chunkSize
            if (chunkSize < endSize) chunkSize += 1
        }
        return result
    }

    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean = false) {
        try {
            val file = fileFor(page)
            if (force || !file.exists()) {
                page.status = Page.State.DownloadImage
                val live = page.downloadStream
                try {
                    download(page, file, live)
                    live?.finish()
                } catch (e: Throwable) {
                    live?.finish(e)
                    throw e
                }
            }
            page.downloadStream = null
            page.stream = {
                try {
                    file.inputStream()
                } catch (e: FileNotFoundException) {
                    page.status = Page.State.Queue
                    launchLoadOnce(page)
                    throw e
                }
            }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            page.downloadStream = null
            if (e is CancellationException) {
                page.status = Page.State.Queue
                throw e
            }
            page.status = Page.State.Error(e)
        }
    }

    private suspend fun download(page: ReaderPage, file: File, live: eu.kanade.tachiyomi.ui.reader.model.DownloadStream?) {
        val request = Request.Builder().url(page.imageUrl!!).build()
        val call = client.newCall(request)
        val ctx = kotlin.coroutines.coroutineContext
        ctx[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val length = body.contentLength()
            if (length > 0) live?.reserve(length)
            val tmp = File(file.parentFile, file.name + ".tmp")
            var read = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        live?.append(buf, 0, n)
                        read += n
                        page.update(read, length, false)
                    }
                }
            }
            page.update(read, length, true)
            if (!tmp.renameTo(file)) throw IOException("Couldn't cache page")
        }
    }
}

private const val LIMITED_PAGES_AHEAD = 4

/** Pages of a chapter downloaded to the phone. */
internal class LocalPageLoader(private val files: List<File>) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = files.mapIndexed { i, f ->
        ReaderPage(i).apply {
            stream = { f.inputStream() }
            status = Page.State.Ready
        }
    }
}
