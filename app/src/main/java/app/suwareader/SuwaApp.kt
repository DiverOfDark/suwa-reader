package app.suwareader

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import app.suwareader.util.Notifications
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class SuwaApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch(Dispatchers.IO) { cleanReaderCache() }
        container.progressSync.flushSoon()
        container.readerSettingsSync.start()
        app.suwareader.update.Updates.schedule(this)
        container.appScope.launch { container.updates.checkQuietly() }
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient }))
            add(ImageDecoder.Factory())
            add(BufferedSourceFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(this, 0.25).build() }
        // Covers live in files/, not cache/, so the library still has them offline after Android trims caches.
        .diskCache { DiskCache.Builder().directory(File(filesDir, "covers")).maxSizeBytes(400L * 1024 * 1024).build() }
        .crossfade(true)
        .allowRgb565(true)
        .build()

    /** Pages read online are cached per chapter; drop chapters not opened for two days. */
    private fun cleanReaderCache() {
        val cutoff = System.currentTimeMillis() - 2 * 24 * 3600 * 1000L
        container.readerCacheDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }
}
