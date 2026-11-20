package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition

class TransitionDownloadState(
    val currChapterDownloaded: Boolean,
    goingToChapterDownloaded: Boolean,
    private val isGoingToChapterOnDisk: (suspend () -> Boolean)?,
) {
    var goingToChapterDownloaded by mutableStateOf(goingToChapterDownloaded)
        private set

    suspend fun checkDisk() {
        isGoingToChapterOnDisk?.let { goingToChapterDownloaded = it() }
    }
}

fun DownloadManager.transitionDownloadState(
    transition: ChapterTransition,
    @Suppress("UNUSED_PARAMETER") manga: Any?,
    @Suppress("UNUSED_PARAMETER") source: Any?,
): TransitionDownloadState? {
    val goingToChapter = transition.to?.chapter
    return TransitionDownloadState(
        currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
        goingToChapterDownloaded = goingToChapter?.let { isChapterDownloaded(it.id) } ?: false,
        isGoingToChapterOnDisk = null,
    )
}
