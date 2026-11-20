package app.suwareader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suwareader.data.repo.Connection
import app.suwareader.domain.ChapterWithManga
import app.suwareader.domain.Manga
import app.suwareader.ui.manga.DownloadIndicator
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * A server-paged list of chapters across the library (updates, history). The first page is kept on disk so the list
 * still shows something offline.
 */
@Composable
fun ChapterFeed(
    title: String,
    cacheName: String,
    emptyText: String,
    groupKey: (ChapterWithManga) -> Long,
    subtitle: (ChapterWithManga) -> String,
    load: suspend (offset: Int) -> Pair<List<ChapterWithManga>, Boolean>,
    onOpenManga: (Long) -> Unit,
) {
    val c = container()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val connection by c.repository.connection.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val queue by c.downloads.queue.collectAsState()
    val pending by c.progressSync.pending.collectAsState()
    val serializer = remember { ListSerializer(ChapterWithManga.serializer()) }
    val items = remember { mutableStateListOf<ChapterWithManga>().apply { addAll(c.store.read(cacheName, serializer).orEmpty()) } }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        val offset = if (reset) 0 else items.size
        c.repository.online { load(offset) }
            .onSuccess { (page, more) ->
                if (reset) {
                    items.clear()
                    c.store.write(cacheName, serializer, page)
                }
                items.addAll(page)
                hasMore = more
            }
            .onFailure {
                hasMore = false
                snackbar.showSnackbar(it.message ?: "Couldn't load")
            }
        loading = false
    }

    LaunchedEffect(Unit) { loadPage(reset = true) }

    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 > items.size - 10 } }
    LaunchedEffect(nearEnd, hasMore) { if (nearEnd && hasMore && items.isNotEmpty()) loadPage(reset = false) }

    val queued = remember(queue) { queue.associateBy { it.chapter.id } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    loadPage(reset = true)
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (items.isEmpty()) {
                if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EmptyState(emptyText)
                return@PullToRefreshBox
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                (connection as? Connection.Offline)?.let { item { OfflineBanner(it.reason) } }
                var lastGroup = ""
                val rows = items.toList()
                rows.forEachIndexed { index, entry ->
                    val group = formatDate(groupKey(entry))
                    if (group != lastGroup) {
                        lastGroup = group
                        item(key = "h$index$group") {
                            Text(
                                group,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = entry.chapter.id) {
                        val p = pending[entry.chapter.id]
                        val isRead = p?.isRead ?: entry.chapter.isRead
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { context.startActivity(ReaderActivity.newIntent(context, entry.mangaId, entry.chapter.id)) }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(
                                entry.mangaThumbnailUrl,
                                entry.mangaThumbnailLastFetched,
                                Modifier.width(44.dp).height(60.dp).clickable { onOpenManga(entry.mangaId) },
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp).alpha(if (isRead) 0.5f else 1f)) {
                                Text(entry.mangaTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.chapter.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                subtitle(entry).takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            DownloadIndicator(
                                downloaded = entry.chapter.id in downloaded,
                                queued = queued[entry.chapter.id],
                                onDownload = {
                                    val manga = c.repository.cachedManga(entry.mangaId) ?: Manga(entry.mangaId, entry.mangaTitle)
                                    c.downloads.enqueue(manga, listOf(entry.chapter))
                                },
                                onCancel = { c.downloads.cancel(listOf(entry.chapter.id)) },
                                onDelete = { c.downloads.delete(listOf(entry.chapter.id)) },
                            )
                        }
                    }
                }
                if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}
