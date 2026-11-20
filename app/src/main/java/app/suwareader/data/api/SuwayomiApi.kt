package app.suwareader.data.api

import app.suwareader.domain.Category
import app.suwareader.domain.Chapter
import app.suwareader.domain.ChapterWithManga
import app.suwareader.domain.Library
import app.suwareader.domain.Manga
import app.suwareader.domain.MangaDetails
import app.suwareader.domain.Source
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Typed access to the Suwayomi GraphQL API. The server is the source of truth for everything returned here. */
class SuwayomiApi(private val gql: GraphQLClient) {

    suspend fun aboutServer(): String {
        val data = gql.execute("query { aboutServer { name version buildType } }")
        val about = data.obj("aboutServer")
        return "${about.str("name") ?: "Suwayomi"} ${about.str("version") ?: ""}".trim()
    }

    suspend fun library(): Library {
        val data = gql.execute(
            """
            query Library {
              mangas(condition: {inLibrary: true}) { nodes { ...M } }
              categories { nodes { id name order default } }
            }
            $MANGA_FRAGMENT
            """.trimIndent(),
        )
        val manga = data.obj("mangas").arr("nodes").map { it.jsonObject.toManga() }
        val categories = data.obj("categories").arr("nodes").map {
            val o = it.jsonObject
            Category(o.int("id"), o.str("name").orEmpty(), o.int("order"), o.bool("default"))
        }.sortedBy { it.order }
        return Library(manga, categories, System.currentTimeMillis())
    }

    suspend fun mangaDetails(mangaId: Long): MangaDetails {
        val data = gql.execute(
            """
            query Manga(${'$'}id: Int!) {
              manga(id: ${'$'}id) { ...M }
              chapters(condition: {mangaId: ${'$'}id}, order: [{by: SOURCE_ORDER, byType: DESC}]) { nodes { ...C } }
            }
            $MANGA_FRAGMENT
            $CHAPTER_FRAGMENT
            """.trimIndent(),
            vars { put("id", mangaId) },
        )
        return MangaDetails(
            manga = data.obj("manga").toManga(),
            chapters = data.obj("chapters").arr("nodes").map { it.jsonObject.toChapter() },
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** Asks the server to re-fetch the manga info and chapter list from its source. */
    suspend fun refreshFromSource(mangaId: Long) {
        gql.execute(
            """
            mutation(${'$'}id: Int!) {
              fetchManga(input: {id: ${'$'}id}) { manga { id } }
              fetchChapters(input: {mangaId: ${'$'}id}) { chapters { id } }
            }
            """.trimIndent(),
            vars { put("id", mangaId) },
        )
    }

    suspend fun chapterPages(chapterId: Long): List<String> {
        val data = gql.execute(
            """
            mutation(${'$'}id: Int!) { fetchChapterPages(input: {chapterId: ${'$'}id}) { pages } }
            """.trimIndent(),
            vars { put("id", chapterId) },
        )
        return data.obj("fetchChapterPages").arr("pages").map { it.jsonPrimitive.content }
    }

    data class ChapterPatch(
        val chapterId: Long,
        val isRead: Boolean? = null,
        val lastPageRead: Int? = null,
        val isBookmarked: Boolean? = null,
    )

    /**
     * Applies each patch in one request. Returns the ids the server didn't accept (e.g. chapters that no longer
     * exist), so the caller can decide what to do with them.
     */
    suspend fun updateChapters(patches: List<ChapterPatch>): Set<Long> {
        if (patches.isEmpty()) return emptySet()
        val failed = mutableSetOf<Long>()
        patches.chunked(40).forEach { chunk ->
            val body = chunk.mapIndexed { i, p ->
                val fields = buildList {
                    p.isRead?.let { add("isRead: $it") }
                    p.lastPageRead?.let { add("lastPageRead: $it") }
                    p.isBookmarked?.let { add("isBookmarked: $it") }
                }.joinToString(", ")
                "u$i: updateChapter(input: {id: ${p.chapterId}, patch: {$fields}}) { chapter { id } }"
            }.joinToString("\n")
            val data = try {
                gql.execute("mutation {\n$body\n}")
            } catch (e: GraphQLException) {
                // The whole batch failed: retry one by one to find the bad ones.
                if (chunk.size == 1) {
                    failed += chunk[0].chapterId
                    return@forEach
                }
                chunk.forEach { p -> failed += updateChapters(listOf(p)) }
                return@forEach
            }
            chunk.forEachIndexed { i, p ->
                val result = data["u$i"]
                if (result == null || result == JsonNull) failed += p.chapterId
            }
        }
        return failed
    }

    suspend fun setInLibrary(mangaId: Long, inLibrary: Boolean) {
        gql.execute(
            """
            mutation(${'$'}id: Int!, ${'$'}v: Boolean!) { updateManga(input: {id: ${'$'}id, patch: {inLibrary: ${'$'}v}}) { manga { id } } }
            """.trimIndent(),
            vars {
                put("id", mangaId)
                put("v", inLibrary)
            },
        )
    }

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Int>, current: List<Int>) {
        val add = categoryIds - current.toSet()
        val remove = current - categoryIds.toSet()
        gql.execute(
            """
            mutation(${'$'}id: Int!, ${'$'}add: [Int!], ${'$'}rm: [Int!]) {
              updateMangaCategories(input: {id: ${'$'}id, patch: {addToCategories: ${'$'}add, removeFromCategories: ${'$'}rm}}) { manga { id } }
            }
            """.trimIndent(),
            vars {
                put("id", mangaId)
                putJsonArray("add") { add.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("rm") { remove.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    suspend fun setMangaMeta(mangaId: Long, key: String, value: String) {
        gql.execute(
            """
            mutation(${'$'}id: Int!, ${'$'}k: String!, ${'$'}v: String!) { setMangaMeta(input: {meta: {mangaId: ${'$'}id, key: ${'$'}k, value: ${'$'}v}}) { meta { key } } }
            """.trimIndent(),
            vars {
                put("id", mangaId)
                put("k", key)
                put("v", value)
            },
        )
    }

    suspend fun globalMetas(prefix: String): Map<String, String> {
        val data = gql.execute("query { metas { nodes { key value } } }")
        return data.obj("metas").arr("nodes")
            .map { it.jsonObject }
            .filter { it.str("key").orEmpty().startsWith(prefix) }
            .associate { it.str("key").orEmpty() to it.str("value").orEmpty() }
    }

    suspend fun setGlobalMetas(values: Map<String, String>) {
        if (values.isEmpty()) return
        val body = values.entries.mapIndexed { i, (k, v) ->
            "m$i: setGlobalMeta(input: {meta: {key: ${JsonPrimitive(k)}, value: ${JsonPrimitive(v)}}}) { meta { key } }"
        }.joinToString("\n")
        gql.execute("mutation {\n$body\n}")
    }

    /** Starts the server's library update (fetches new chapters from every source). */
    suspend fun startLibraryUpdate() {
        gql.execute("mutation { updateLibrary(input: {}) { updateStatus { jobsInfo { isRunning } } } }")
    }

    data class UpdateStatus(val isRunning: Boolean, val finished: Int, val total: Int)

    suspend fun libraryUpdateStatus(): UpdateStatus {
        val data = gql.execute("query { libraryUpdateStatus { jobsInfo { isRunning totalJobs finishedJobs } } }")
        val info = data.obj("libraryUpdateStatus").obj("jobsInfo")
        return UpdateStatus(info.bool("isRunning"), info.int("finishedJobs"), info.int("totalJobs"))
    }

    suspend fun recentUpdates(offset: Int, limit: Int): Pair<List<ChapterWithManga>, Boolean> {
        val data = gql.execute(
            """
            query(${'$'}o: Int!, ${'$'}l: Int!) {
              chapters(filter: {inLibrary: {equalTo: true}}, order: [{by: FETCHED_AT, byType: DESC}, {by: SOURCE_ORDER, byType: DESC}], offset: ${'$'}o, first: ${'$'}l) {
                pageInfo { hasNextPage }
                nodes { ...C manga { id title thumbnailUrl thumbnailUrlLastFetched } }
              }
            }
            $CHAPTER_FRAGMENT
            """.trimIndent(),
            vars {
                put("o", offset)
                put("l", limit)
            },
        )
        return data.obj("chapters").chaptersWithManga() to data.obj("chapters").obj("pageInfo").bool("hasNextPage")
    }

    suspend fun history(offset: Int, limit: Int): Pair<List<ChapterWithManga>, Boolean> {
        val data = gql.execute(
            """
            query(${'$'}o: Int!, ${'$'}l: Int!) {
              chapters(filter: {lastReadAt: {greaterThan: "0"}, inLibrary: {equalTo: true}}, order: [{by: LAST_READ_AT, byType: DESC}], offset: ${'$'}o, first: ${'$'}l) {
                pageInfo { hasNextPage }
                nodes { ...C manga { id title thumbnailUrl thumbnailUrlLastFetched } }
              }
            }
            $CHAPTER_FRAGMENT
            """.trimIndent(),
            vars {
                put("o", offset)
                put("l", limit)
            },
        )
        return data.obj("chapters").chaptersWithManga() to data.obj("chapters").obj("pageInfo").bool("hasNextPage")
    }

    suspend fun sources(): List<Source> {
        val data = gql.execute(
            "query { sources { nodes { id name displayName lang iconUrl supportsLatest } } }",
        )
        return data.obj("sources").arr("nodes").map {
            val o = it.jsonObject
            Source(
                id = o.str("id").orEmpty(),
                name = o.str("name").orEmpty(),
                displayName = o.str("displayName").orEmpty(),
                lang = o.str("lang").orEmpty(),
                iconUrl = o.str("iconUrl"),
                supportsLatest = o.bool("supportsLatest"),
            )
        }.filter { it.id != "0" }
    }

    enum class BrowseType { POPULAR, LATEST, SEARCH }

    suspend fun browseSource(sourceId: String, type: BrowseType, page: Int, query: String?): Pair<List<Manga>, Boolean> {
        val data = gql.execute(
            """
            mutation(${'$'}s: LongString!, ${'$'}t: FetchSourceMangaType!, ${'$'}p: Int!, ${'$'}q: String) {
              fetchSourceManga(input: {source: ${'$'}s, type: ${'$'}t, page: ${'$'}p, query: ${'$'}q}) {
                hasNextPage
                mangas { id title thumbnailUrl thumbnailUrlLastFetched user { inLibrary } }
              }
            }
            """.trimIndent(),
            vars {
                put("s", sourceId)
                put("t", type.name)
                put("p", page)
                if (query != null) put("q", query)
            },
        )
        val result = data.obj("fetchSourceManga")
        val manga = result.arr("mangas").map {
            val o = it.jsonObject
            Manga(
                id = o.long("id"),
                title = o.str("title").orEmpty(),
                thumbnailUrl = o.str("thumbnailUrl"),
                thumbnailLastFetched = o.long("thumbnailUrlLastFetched"),
                inLibrary = o.objOrNull("user")?.bool("inLibrary") ?: false,
            )
        }
        return manga to result.bool("hasNextPage")
    }

    private fun JsonObject.chaptersWithManga(): List<ChapterWithManga> = arr("nodes").map {
        val o = it.jsonObject
        val m = o.obj("manga")
        ChapterWithManga(
            chapter = o.toChapter(),
            mangaId = m.long("id"),
            mangaTitle = m.str("title").orEmpty(),
            mangaThumbnailUrl = m.str("thumbnailUrl"),
            mangaThumbnailLastFetched = m.long("thumbnailUrlLastFetched"),
        )
    }

    private fun JsonObject.toManga(): Manga {
        val user = objOrNull("user")
        return Manga(
            id = long("id"),
            title = str("title").orEmpty(),
            thumbnailUrl = str("thumbnailUrl"),
            thumbnailLastFetched = long("thumbnailUrlLastFetched"),
            author = str("author"),
            artist = str("artist"),
            description = str("description"),
            genre = (this["genre"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            status = str("status"),
            realUrl = str("realUrl"),
            sourceId = str("sourceId"),
            sourceName = objOrNull("source")?.str("displayName"),
            inLibrary = user?.bool("inLibrary") ?: false,
            inLibraryAt = user?.long("inLibraryAt") ?: 0,
            unreadCount = user?.int("unreadCount") ?: 0,
            serverDownloadCount = user?.int("downloadCount") ?: 0,
            bookmarkCount = user?.int("bookmarkCount") ?: 0,
            lastReadAt = user?.objOrNull("lastReadChapter")?.objOrNull("user")?.long("lastReadAt") ?: 0,
            latestFetchedAt = objOrNull("latestFetchedChapter")?.long("fetchedAt") ?: 0,
            latestUploadedAt = objOrNull("latestUploadedChapter")?.long("uploadDate") ?: 0,
            chapterCount = objOrNull("chapters")?.int("totalCount") ?: 0,
            categoryIds = objOrNull("categories")?.arr("nodes")?.map { it.jsonObject.int("id") }.orEmpty(),
            meta = arr("meta").associate { val o = it.jsonObject; o.str("key").orEmpty() to o.str("value").orEmpty() },
        )
    }

    private fun JsonObject.toChapter(): Chapter {
        val user = objOrNull("user")
        return Chapter(
            id = long("id"),
            mangaId = long("mangaId"),
            name = str("name").orEmpty(),
            chapterNumber = (this["chapterNumber"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: -1.0,
            scanlator = str("scanlator"),
            sourceOrder = int("sourceOrder"),
            uploadDate = long("uploadDate"),
            fetchedAt = long("fetchedAt"),
            pageCount = int("pageCount"),
            realUrl = str("realUrl"),
            isRead = user?.bool("isRead") ?: false,
            isBookmarked = user?.bool("isBookmarked") ?: false,
            lastPageRead = user?.int("lastPageRead") ?: 0,
            lastReadAt = user?.long("lastReadAt") ?: 0,
            isDownloadedOnServer = user?.bool("isDownloaded") ?: false,
        )
    }

    companion object {
        private val MANGA_FRAGMENT = """
            fragment M on MangaType {
              id title thumbnailUrl thumbnailUrlLastFetched author artist description genre status realUrl sourceId
              source { displayName }
              categories { nodes { id } }
              meta { key value }
              user { inLibrary inLibraryAt unreadCount downloadCount bookmarkCount lastReadChapter { id user { lastReadAt } } }
              latestFetchedChapter { fetchedAt }
              latestUploadedChapter { uploadDate }
              chapters { totalCount }
            }
        """.trimIndent()

        private val CHAPTER_FRAGMENT = """
            fragment C on ChapterType {
              id mangaId name chapterNumber scanlator sourceOrder uploadDate fetchedAt pageCount realUrl
              user { isRead isBookmarked lastPageRead lastReadAt isDownloaded }
            }
        """.trimIndent()

        private fun vars(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    }
}

internal fun JsonObject.objOrNull(key: String): JsonObject? = (this[key] as? JsonObject)
internal fun JsonObject.obj(key: String): JsonObject = objOrNull(key) ?: throw GraphQLException("Missing '$key' in response")
internal fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal fun JsonObject.long(key: String): Long = str(key)?.toLongOrNull() ?: str(key)?.toDoubleOrNull()?.toLong() ?: 0
internal fun JsonObject.int(key: String): Int = long(key).toInt()
internal fun JsonObject.bool(key: String): Boolean = str(key)?.toBooleanStrictOrNull() ?: false

@Suppress("unused")
private fun JsonElement.ignore() = Unit
