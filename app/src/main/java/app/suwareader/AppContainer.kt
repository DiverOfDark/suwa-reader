package app.suwareader

import android.content.Context
import app.suwareader.data.JsonStore
import app.suwareader.data.api.GraphQLClient
import app.suwareader.data.api.SuwayomiApi
import app.suwareader.data.download.Downloads
import app.suwareader.data.prefs.AppPreferences
import app.suwareader.data.repo.LibraryRepository
import app.suwareader.data.sync.ProgressSync
import app.suwareader.data.sync.ReaderSettingsSync
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Credentials
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val preferenceStore = AndroidPreferenceStore(context)
    val prefs = AppPreferences(preferenceStore)
    val readerPreferences = ReaderPreferences(preferenceStore)

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            val user = prefs.authUser.get()
            val request = if (user.isNotBlank()) {
                chain.request().newBuilder()
                    .header("Authorization", Credentials.basic(user, prefs.authPassword.get()))
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .build()

    /** Shared client: carries the server login. */
    val httpClient: OkHttpClient get() = baseClient

    val store = JsonStore(File(context.filesDir, "cache-data"))
    val api = SuwayomiApi(GraphQLClient({ baseClient }, { prefs.serverUrl.get() }))
    val downloads = Downloads(context, store, prefs, appScope)
    val repository = LibraryRepository(api, store, downloads, appScope)
    val progressSync = ProgressSync(
        context = context,
        store = store,
        api = api,
        scope = appScope,
        remap = { repository.remapPending(it) },
        onSynced = { repository.onProgressSynced(it) },
    ).also { repository.progressSync = it }

    val readerSettingsSync = ReaderSettingsSync(context, api, readerPreferences, appScope).also {
        repository.readerSettingsSync = it
    }

    val updates = app.suwareader.update.Updates(context, prefs)

    val readerCacheDir = File(context.cacheDir, "reader")

    /** Checks a server address and login without saving them. Returns the server's name and version. */
    suspend fun testConnection(url: String, user: String, password: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val r = if (user.isNotBlank()) {
                    chain.request().newBuilder().header("Authorization", Credentials.basic(user, password)).build()
                } else {
                    chain.request()
                }
                chain.proceed(r)
            }
            .build()
        return SuwayomiApi(GraphQLClient({ client }, { url })).aboutServer()
    }

    fun absoluteUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        val base = prefs.serverUrl.get().trimEnd('/')
        if (base.isBlank()) return null
        return base + path
    }
}
