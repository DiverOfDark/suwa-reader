package eu.kanade.tachiyomi.data.download

/** What the vendored reader needs to know about downloads. */
interface DownloadManager {
    fun isChapterDownloaded(chapterId: Long): Boolean
}
