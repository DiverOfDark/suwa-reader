package app.suwareader.domain

import kotlinx.serialization.Serializable

@Serializable
data class Manga(
    val id: Long,
    val title: String,
    val thumbnailUrl: String? = null,
    val thumbnailLastFetched: Long = 0,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: String? = null,
    val realUrl: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val inLibrary: Boolean = false,
    val inLibraryAt: Long = 0,
    val unreadCount: Int = 0,
    val serverDownloadCount: Int = 0,
    val bookmarkCount: Int = 0,
    val lastReadAt: Long = 0,
    val latestFetchedAt: Long = 0,
    val latestUploadedAt: Long = 0,
    val chapterCount: Int = 0,
    val categoryIds: List<Int> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
)

@Serializable
data class Chapter(
    val id: Long,
    val mangaId: Long,
    val name: String,
    val chapterNumber: Double,
    val scanlator: String? = null,
    val sourceOrder: Int = 0,
    val uploadDate: Long = 0,
    val fetchedAt: Long = 0,
    val pageCount: Int = 0,
    val realUrl: String? = null,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val lastPageRead: Int = 0,
    val lastReadAt: Long = 0,
    val isDownloadedOnServer: Boolean = false,
)

@Serializable
data class Category(
    val id: Int,
    val name: String,
    val order: Int,
    val isDefault: Boolean,
)

@Serializable
data class Library(
    val manga: List<Manga> = emptyList(),
    val categories: List<Category> = emptyList(),
    val fetchedAt: Long = 0,
)

@Serializable
data class MangaDetails(
    val manga: Manga,
    val chapters: List<Chapter>,
    val fetchedAt: Long = 0,
)

/** A chapter with the manga it belongs to, as listed by updates and history. */
@Serializable
data class ChapterWithManga(
    val chapter: Chapter,
    val mangaId: Long,
    val mangaTitle: String,
    val mangaThumbnailUrl: String?,
    val mangaThumbnailLastFetched: Long = 0,
)

@Serializable
data class Source(
    val id: String,
    val name: String,
    val displayName: String,
    val lang: String,
    val iconUrl: String?,
    val supportsLatest: Boolean,
)
