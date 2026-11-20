package app.suwareader.data.sync

import android.content.Context
import app.suwareader.data.api.SuwayomiApi
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.preference.Preference

/**
 * Keeps reading-related reader settings in the server's global meta so every device reads the same way. Settings that
 * belong to the device (screen on, volume keys, fullscreen, cutout, rotation) stay local.
 *
 * A change made here is marked dirty until the server accepts it, so an offline change isn't overwritten by the
 * server's older value on the next pull.
 */
class ReaderSettingsSync(
    context: Context,
    private val api: SuwayomiApi,
    private val prefs: ReaderPreferences,
    private val scope: CoroutineScope,
) {
    private class Synced<T>(val pref: Preference<T>, val encode: (T) -> String, val decode: (String) -> T?) {
        val key = PREFIX + pref.key()
        fun current() = encode(pref.get())
        fun apply(value: String): Boolean {
            val v = decode(value) ?: return false
            if (v == pref.get()) return false
            pref.set(v)
            return true
        }
    }

    private fun int(p: Preference<Int>) = Synced(p, { it.toString() }, { it.toIntOrNull() })
    private fun bool(p: Preference<Boolean>) = Synced(p, { it.toString() }, { it.toBooleanStrictOrNull() })
    private fun invert(p: Preference<ReaderPreferences.TappingInvertMode>) =
        Synced(p, { it.name }, { v -> ReaderPreferences.TappingInvertMode.entries.find { it.name == v } })

    private val synced: List<Synced<*>> = listOf(
        int(prefs.defaultReadingMode),
        int(prefs.readerTheme),
        bool(prefs.showPageNumber),
        bool(prefs.alwaysShowChapterTransition),
        bool(prefs.readWithLongTap),
        bool(prefs.grayscale),
        bool(prefs.invertedColors),
        bool(prefs.pageTransitions),
        // Paged
        int(prefs.imageScaleType),
        int(prefs.zoomStart),
        bool(prefs.cropBorders),
        bool(prefs.landscapeZoom),
        bool(prefs.dualPageSplitPaged),
        bool(prefs.dualPageRotateToFit),
        int(prefs.navigationModePager),
        invert(prefs.pagerNavInverted),
        // Long strip
        int(prefs.webtoonSidePadding),
        bool(prefs.cropBordersWebtoon),
        bool(prefs.webtoonDoubleTapZoomEnabled),
        bool(prefs.webtoonDisableZoomOut),
        bool(prefs.dualPageSplitWebtoon),
        int(prefs.navigationModeWebtoon),
        invert(prefs.webtoonNavInverted),
    )

    private val state = context.getSharedPreferences("reader_settings_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The value each setting had when it last matched the server; a change back to it isn't a local edit. */
    private fun lastSynced(key: String): String? = state.getString("v_$key", null)
    private fun setLastSynced(key: String, value: String) = state.edit().putString("v_$key", value).apply()

    private var dirty: Set<String>
        get() = state.getStringSet("dirty", emptySet()).orEmpty()
        set(value) = state.edit().putStringSet("dirty", value).apply()

    fun start() {
        synced.forEach { s ->
            scope.launch {
                s.pref.changes().drop(1).collect {
                    if (s.current() == lastSynced(s.key)) return@collect
                    dirty = dirty + s.key
                    push()
                }
            }
        }
    }

    /** Sends local changes, then takes the server's values for everything not changed here. */
    suspend fun sync() {
        push()
        pull()
    }

    private suspend fun push() = mutex.withLock {
        val pending = dirty
        if (pending.isEmpty()) return@withLock
        val values = synced.filter { it.key in pending }.associate { it.key to it.current() }
        try {
            api.setGlobalMetas(values)
            values.forEach { (k, v) -> setLastSynced(k, v) }
            // Only clear what we sent; anything changed meanwhile stays dirty
            dirty = dirty - synced.filter { it.key in values && it.current() == values[it.key] }.map { it.key }.toSet()
        } catch (e: Exception) {
            logcat(LogPriority.INFO) { "Reader settings not sent yet: ${e.message}" }
        }
    }

    private suspend fun pull() = mutex.withLock {
        val server = try {
            api.globalMetas(PREFIX)
        } catch (e: Exception) {
            return@withLock
        }
        val pending = dirty
        synced.forEach { s ->
            if (s.key in pending) return@forEach
            val value = server[s.key]
            if (value != null) {
                setLastSynced(s.key, value)
                s.apply(value)
            } else {
                // First device to sync: publish what this phone has
                dirty = dirty + s.key
            }
        }
        if (dirty.isNotEmpty()) scope.launch { push() }
    }

    companion object {
        /** Debug builds keep their own copy so testing doesn't change the settings real devices use. */
        val PREFIX = if (app.suwareader.BuildConfig.DEBUG) "suwa_reader_debug." else "suwa_reader."
    }
}
