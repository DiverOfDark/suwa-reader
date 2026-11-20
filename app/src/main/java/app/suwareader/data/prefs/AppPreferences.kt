package app.suwareader.data.prefs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class AppPreferences(store: PreferenceStore) {
    val serverUrl = store.getString("server_url", "")
    val authUser = store.getString("auth_user", "")
    val authPassword = store.getString("auth_password", "")

    val themeMode = store.getEnum("theme_mode", ThemeMode.SYSTEM)
    val pureBlack = store.getBoolean("pure_black", true)

    val libraryColumns = store.getInt("library_columns", 0) // 0 = adaptive
    val librarySort = store.getEnum("library_sort", LibrarySort.LAST_READ)
    val librarySortAscending = store.getBoolean("library_sort_asc", false)
    val libraryFilterUnread = store.getBoolean("library_filter_unread", false)
    val libraryFilterDownloaded = store.getBoolean("library_filter_downloaded", false)
    val libraryShowUnreadBadge = store.getBoolean("library_badge_unread", true)
    val libraryShowDownloadBadge = store.getBoolean("library_badge_download", true)
    val lastCategoryId = store.getInt("library_last_category", -1)

    val chapterSortDescending = store.getBoolean("chapter_sort_desc", true)
    val chapterHideRead = store.getBoolean("chapter_hide_read", false)

    val downloadOnlyOnWifi = store.getBoolean("download_only_wifi", false)
    val deleteAfterReading = store.getBoolean("download_delete_after_read", false)
    val autoDownloadAhead = store.getInt("download_ahead", 0) // chapters to keep downloaded ahead while reading

    val readerDefaultLoadAhead = store.getBoolean("reader_preload_next", true)

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    enum class LibrarySort(val label: String) {
        TITLE("Title"),
        LAST_READ("Last read"),
        UNREAD("Unread count"),
        LATEST_CHAPTER("Latest chapter"),
        DATE_ADDED("Date added"),
    }
}

@Composable
fun <T> Preference<T>.collectAsState(): State<T> {
    val flow = remember(this) { changes() }
    return flow.collectAsState(initial = get())
}
