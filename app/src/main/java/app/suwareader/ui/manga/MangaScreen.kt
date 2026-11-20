package app.suwareader.ui.manga

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.suwareader.data.download.QueuedDownload
import app.suwareader.data.prefs.collectAsState
import app.suwareader.data.repo.Connection
import app.suwareader.domain.Chapter
import app.suwareader.domain.Manga
import app.suwareader.ui.components.Cover
import app.suwareader.ui.components.EmptyState
import app.suwareader.ui.components.OfflineBanner
import app.suwareader.ui.components.container
import app.suwareader.ui.components.formatDate
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaScreen(mangaId: Long, onBack: () -> Unit) {
    val c = container()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val details by c.repository.detailsFlow(mangaId).collectAsState()
    val library by c.repository.library.collectAsState()
    val connection by c.repository.connection.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val queue by c.downloads.queue.collectAsState()
    val sortDesc by c.prefs.chapterSortDescending.collectAsState()
    val hideRead by c.prefs.chapterHideRead.collectAsState()

    var refreshing by remember { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var downloadMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }

    // Basic info while details load, e.g. from the library grid
    val manga: Manga? = details?.manga ?: c.repository.cachedManga(mangaId)
    val chapters = details?.chapters.orEmpty()

    suspend fun refresh(fromSource: Boolean = false) {
        refreshing = true
        val result = if (fromSource) c.repository.refreshFromSource(mangaId) else c.repository.refreshDetails(mangaId)
        refreshing = false
        result.onFailure { snackbar.showSnackbar(it.message ?: "Refresh failed") }
    }

    LaunchedEffect(mangaId) { refresh() }

    val readingOrder = remember(chapters) { chapters.sortedWith(compareBy<Chapter> { it.sourceOrder }.thenBy { it.chapterNumber }) }
    val nextChapter = remember(readingOrder) {
        readingOrder.firstOrNull { !it.isRead && it.lastPageRead > 0 }
            ?: readingOrder.dropWhile { !it.isRead }.firstOrNull { !it.isRead }.takeIf { readingOrder.any { c -> c.isRead } }
            ?: readingOrder.firstOrNull { !it.isRead }
    }
    val shown = remember(chapters, sortDesc, hideRead) {
        val base = if (sortDesc) readingOrder.asReversed() else readingOrder
        if (hideRead) base.filter { !it.isRead } else base
    }
    val queueById = remember(queue) { queue.associateBy { it.chapter.id } }

    fun read(chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, mangaId, chapter.id))
    }

    val inSelection = selection.isNotEmpty()
    BackHandler(inSelection) { selection = emptySet() }
    val selectedChapters = chapters.filter { it.id in selection }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (inSelection) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { selection = shown.map { it.id }.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(manga?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        Box {
                            IconButton(onClick = { downloadMenu = true }) { Icon(Icons.Outlined.ArrowCircleDown, "Download") }
                            DropdownMenu(downloadMenu, onDismissRequest = { downloadMenu = false }) {
                                val unread = readingOrder.filter { !it.isRead }
                                val fromNext = nextChapter?.let { n -> readingOrder.dropWhile { it.id != n.id } }.orEmpty().filter { !it.isRead }
                                listOf(1, 5, 10, 25).forEach { n ->
                                    DropdownMenuItem(text = { Text("Next $n unread") }, onClick = {
                                        downloadMenu = false
                                        manga?.let { c.downloads.enqueue(it, fromNext.take(n)) }
                                    })
                                }
                                DropdownMenuItem(text = { Text("All unread (${unread.size})") }, onClick = {
                                    downloadMenu = false
                                    manga?.let { c.downloads.enqueue(it, unread) }
                                })
                                DropdownMenuItem(text = { Text("Everything (${chapters.size})") }, onClick = {
                                    downloadMenu = false
                                    manga?.let { c.downloads.enqueue(it, readingOrder) }
                                })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Delete read downloads") }, onClick = {
                                    downloadMenu = false
                                    c.downloads.delete(chapters.filter { it.isRead && it.id in downloaded }.map { it.id })
                                })
                                DropdownMenuItem(text = { Text("Delete all downloads") }, onClick = {
                                    downloadMenu = false
                                    c.downloads.deleteManga(mangaId)
                                })
                            }
                        }
                        Box {
                            IconButton(onClick = { filterMenu = true }) {
                                Icon(Icons.Filled.FilterList, "Filter", tint = if (hideRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(filterMenu, onDismissRequest = { filterMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (hideRead) "Show read chapters" else "Hide read chapters") },
                                    onClick = {
                                        filterMenu = false
                                        c.prefs.chapterHideRead.set(!hideRead)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (sortDesc) "Oldest first" else "Newest first") },
                                    onClick = {
                                        filterMenu = false
                                        c.prefs.chapterSortDescending.set(!sortDesc)
                                    },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Re-fetch from source") }, onClick = {
                                    menu = false
                                    scope.launch { refresh(fromSource = true) }
                                })
                                if (manga?.inLibrary == true) {
                                    DropdownMenuItem(text = { Text("Edit categories") }, onClick = {
                                        menu = false
                                        categoryDialog = true
                                    })
                                }
                                manga?.realUrl?.let { url ->
                                    DropdownMenuItem(
                                        text = { Text("Open in browser") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                        onClick = {
                                            menu = false
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (inSelection && manga != null) {
                BottomAppBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val anyUnread = selectedChapters.any { !it.isRead }
                        IconButton(onClick = {
                            c.repository.setProgress(manga, selectedChapters, isRead = anyUnread)
                            selection = emptySet()
                        }) { Icon(if (anyUnread) Icons.Filled.DoneAll else Icons.Filled.RemoveDone, if (anyUnread) "Mark read" else "Mark unread") }
                        IconButton(onClick = {
                            val first = selectedChapters.minByOrNull { it.sourceOrder }
                            if (first != null) {
                                val before = readingOrder.takeWhile { it.id != first.id }.filter { !it.isRead }
                                c.repository.setProgress(manga, before, isRead = true)
                            }
                            selection = emptySet()
                        }) { Icon(Icons.Filled.VerticalAlignBottom, "Mark previous as read") }
                        val anyUnbookmarked = selectedChapters.any { !it.isBookmarked }
                        IconButton(onClick = {
                            c.repository.setProgress(manga, selectedChapters, isBookmarked = anyUnbookmarked)
                            selection = emptySet()
                        }) { Icon(if (anyUnbookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark") }
                        IconButton(onClick = {
                            c.downloads.enqueue(manga, selectedChapters)
                            selection = emptySet()
                        }) { Icon(Icons.Filled.Download, "Download") }
                        IconButton(onClick = {
                            c.downloads.delete(selectedChapters.map { it.id })
                            selection = emptySet()
                        }) { Icon(Icons.Filled.Delete, "Delete download") }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!inSelection && nextChapter != null) {
                val started = readingOrder.any { it.isRead || it.lastPageRead > 0 }
                ExtendedFloatingActionButton(
                    onClick = { read(nextChapter) },
                    icon = { Icon(Icons.Filled.PlayArrow, null) },
                    text = { Text(if (started) "Continue" else "Start") },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refresh() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (manga == null) {
                if (refreshing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EmptyState("Couldn't load this manga", "Pull down to retry.")
                return@PullToRefreshBox
            }
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                (connection as? Connection.Offline)?.let { item { OfflineBanner(it.reason) } }
                item {
                    Header(
                        manga = manga,
                        chapterCount = chapters.size,
                        downloadedCount = chapters.count { it.id in downloaded },
                        isInLibrary = manga.inLibrary || library?.manga?.any { it.id == mangaId } == true,
                        onToggleLibrary = {
                            if (manga.inLibrary) {
                                confirmRemove = true
                            } else {
                                scope.launch {
                                    c.repository.online { c.api.setInLibrary(mangaId, true) }
                                        .onSuccess {
                                            refresh()
                                            c.repository.refreshLibrary()
                                        }
                                        .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                                }
                            }
                        },
                    )
                }
                item {
                    Text(
                        "${chapters.size} chapters" + if (hideRead) " · read hidden" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(shown, key = { it.id }) { chapter ->
                    val selected = chapter.id in selection
                    ChapterRow(
                        chapter = chapter,
                        selected = selected,
                        downloaded = chapter.id in downloaded,
                        queued = queueById[chapter.id],
                        onClick = {
                            if (inSelection) {
                                selection = if (selected) selection - chapter.id else selection + chapter.id
                            } else {
                                read(chapter)
                            }
                        },
                        onLongClick = {
                            selection = if (inSelection) {
                                // Range-select from the last selected chapter
                                val ids = shown.map { it.id }
                                val last = ids.indexOfLast { it in selection }
                                val here = ids.indexOf(chapter.id)
                                if (last >= 0) selection + ids.subList(minOf(last, here), maxOf(last, here) + 1) else selection + chapter.id
                            } else {
                                setOf(chapter.id)
                            }
                        },
                        onDownload = { c.downloads.enqueue(manga, listOf(chapter)) },
                        onCancel = { c.downloads.cancel(listOf(chapter.id)) },
                        onDelete = { c.downloads.delete(listOf(chapter.id)) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (confirmRemove && manga != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from library?") },
            text = { Text("It's removed on the server, so every device loses it. Downloads on this phone are deleted too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        c.repository.online { c.api.setInLibrary(mangaId, false) }
                            .onSuccess {
                                c.downloads.deleteManga(mangaId)
                                c.repository.refreshLibrary()
                                refresh()
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    if (categoryDialog && manga != null) {
        val categories = library?.categories.orEmpty().filter { !it.isDefault }
        var chosen by remember { mutableStateOf(manga.categoryIds.toSet()) }
        AlertDialog(
            onDismissRequest = { categoryDialog = false },
            title = { Text("Categories") },
            text = {
                Column {
                    if (categories.isEmpty()) Text("No categories on the server. Create them in the Suwayomi web UI.")
                    categories.forEach { cat ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cat.id in chosen, onCheckedChange = { chosen = if (it) chosen + cat.id else chosen - cat.id })
                            Text(cat.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    categoryDialog = false
                    scope.launch {
                        c.repository.online { c.api.setMangaCategories(mangaId, chosen.toList(), manga.categoryIds) }
                            .onSuccess {
                                refresh()
                                c.repository.refreshLibrary()
                            }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Failed") }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { categoryDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(
    manga: Manga,
    chapterCount: Int,
    downloadedCount: Int,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        Cover(
            manga.thumbnailUrl,
            manga.thumbnailLastFetched,
            Modifier.fillMaxWidth().height(220.dp).blur(24.dp).alpha(0.35f),
        )
        Box(
            Modifier.fillMaxWidth().height(220.dp).background(
                Brush.verticalGradient(0f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface),
            ),
        )
        Column(Modifier.padding(16.dp)) {
            Row {
                Cover(manga.thumbnailUrl, manga.thumbnailLastFetched, Modifier.width(110.dp).height(160.dp), manga.title)
                Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(manga.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    listOfNotNull(manga.author, manga.artist?.takeIf { it != manga.author }).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        listOfNotNull(manga.status?.lowercase()?.replaceFirstChar { it.uppercase() }?.replace('_', ' '), manga.sourceName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (downloadedCount > 0) {
                        Text("$downloadedCount of $chapterCount on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            OutlinedButton(onClick = onToggleLibrary, modifier = Modifier.padding(top = 12.dp)) {
                Icon(if (isInLibrary) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, modifier = Modifier.size(18.dp))
                Text(if (isInLibrary) "In library" else "Add to library", modifier = Modifier.padding(start = 8.dp))
            }
            manga.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .animateContentSize()
                        .combinedClickable(onClick = { expanded = !expanded }),
                )
            }
            if (manga.genre.isNotEmpty() && expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    manga.genre.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    selected: Boolean,
    downloaded: Boolean,
    queued: QueuedDownload?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val alpha = if (chapter.isRead) 0.45f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(alpha)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chapter.isBookmarked) {
                    Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                }
                Text(chapter.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                formatDate(chapter.uploadDate).takeIf { it.isNotEmpty() },
                chapter.lastPageRead.takeIf { !chapter.isRead && it > 0 }?.let { "Page ${it + 1}" },
                chapter.scanlator?.takeIf { it.isNotBlank() && it != "Unknown" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        DownloadIndicator(downloaded, queued, onDownload, onCancel, onDelete)
    }
}

@Composable
fun DownloadIndicator(
    downloaded: Boolean,
    queued: QueuedDownload?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        when {
            downloaded -> IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.tertiary)
            }
            queued?.state == QueuedDownload.State.ERROR -> IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.ErrorOutline, "Download failed", tint = MaterialTheme.colorScheme.error)
            }
            queued != null -> IconButton(onClick = { menu = true }) {
                val progress by remember(queued) {
                    derivedStateOf { if (queued.pagesTotal > 0) queued.pagesDone.toFloat() / queued.pagesTotal else 0f }
                }
                if (queued.state == QueuedDownload.State.DOWNLOADING && progress > 0f) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
            }
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.ArrowCircleDown, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (downloaded) {
                DropdownMenuItem(text = { Text("Delete download") }, onClick = {
                    menu = false
                    onDelete()
                })
            } else {
                if (queued?.state == QueuedDownload.State.ERROR) {
                    Text(queued.error.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp).width(240.dp))
                    DropdownMenuItem(text = { Text("Retry") }, onClick = {
                        menu = false
                        onCancel()
                        onDownload()
                    })
                }
                DropdownMenuItem(text = { Text("Cancel download") }, onClick = {
                    menu = false
                    onCancel()
                })
            }
        }
    }
}
