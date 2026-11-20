package app.suwareader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import app.suwareader.data.prefs.AppPreferences.LibrarySort
import app.suwareader.data.prefs.collectAsState
import app.suwareader.data.repo.Connection
import app.suwareader.domain.Category
import app.suwareader.domain.Manga
import app.suwareader.ui.components.Badge
import app.suwareader.ui.components.Cover
import app.suwareader.ui.components.EmptyState
import app.suwareader.ui.components.OfflineBanner
import app.suwareader.ui.components.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpenManga: (Long) -> Unit) {
    val c = container()
    val library by c.repository.library.collectAsState()
    val connection by c.repository.connection.collectAsState()
    val refreshing by c.repository.refreshing.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val sort by c.prefs.librarySort.collectAsState()
    val ascending by c.prefs.librarySortAscending.collectAsState()
    val filterUnread by c.prefs.libraryFilterUnread.collectAsState()
    val filterDownloaded by c.prefs.libraryFilterDownloaded.collectAsState()
    val columns by c.prefs.libraryColumns.collectAsState()
    val showUnread by c.prefs.libraryShowUnreadBadge.collectAsState()
    val showDownloads by c.prefs.libraryShowDownloadBadge.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val downloadsPerManga = remember(downloaded) { downloaded.values.groupingBy { it.mangaId }.eachCount() }

    val lib = library
    val categories: List<Category> = remember(lib) {
        val cats = lib?.categories.orEmpty()
        val hasUncategorized = lib?.manga?.any { it.categoryIds.isEmpty() } == true
        cats.filter { !it.isDefault || hasUncategorized }.ifEmpty { listOf(Category(0, "Default", 0, true)) }
    }

    fun mangaFor(category: Category): List<Manga> {
        var list = lib?.manga.orEmpty().filter {
            if (category.isDefault) it.categoryIds.isEmpty() else category.id in it.categoryIds
        }
        if (query.isNotBlank()) list = list.filter { it.title.contains(query, ignoreCase = true) || (it.author?.contains(query, true) == true) }
        if (filterUnread) list = list.filter { it.unreadCount > 0 }
        if (filterDownloaded) list = list.filter { (downloadsPerManga[it.id] ?: 0) > 0 }
        val comparator: Comparator<Manga> = when (sort) {
            LibrarySort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            LibrarySort.LAST_READ -> compareBy { it.lastReadAt }
            LibrarySort.UNREAD -> compareBy { it.unreadCount }
            LibrarySort.LATEST_CHAPTER -> compareBy { maxOf(it.latestFetchedAt, it.latestUploadedAt / 1000) }
            LibrarySort.DATE_ADDED -> compareBy { it.inLibraryAt }
        }
        return list.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    LaunchedEffect(Unit) {
        // Keep the library in step with the server while it's on screen
        while (true) {
            delay(5 * 60 * 1000L)
            c.repository.refreshLibrary()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search library") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Library" + (lib?.manga?.size?.let { " ($it)" } ?: ""))
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            query = ""
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    if (searching && query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Filled.FilterList, "Filter and sort", tint = if (filterUnread || filterDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Check sources for new chapters") },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        c.repository.online { c.api.startLibraryUpdate() }
                                            .onSuccess {
                                                snackbar.showSnackbar("Server is checking for new chapters")
                                                // Poll until done, then pull the result
                                                while (true) {
                                                    delay(3000)
                                                    val s = c.repository.online { c.api.libraryUpdateStatus() }.getOrNull() ?: break
                                                    if (!s.isRunning) break
                                                }
                                                c.repository.refreshLibrary()
                                                snackbar.showSnackbar("Library updated")
                                            }
                                            .onFailure { snackbar.showSnackbar("Couldn't start update: ${it.message}") }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh from server") },
                                onClick = {
                                    menu = false
                                    scope.launch { c.repository.refreshLibrary().onFailure { snackbar.showSnackbar(it.message ?: "Refresh failed") } }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            (connection as? Connection.Offline)?.let { OfflineBanner(it.reason) }
            if (lib == null) {
                if (refreshing) {
                    EmptyState("Loading library…")
                } else {
                    EmptyState(
                        "No library yet",
                        "Couldn't reach the server. Pull down to retry, or check the address in More → Settings.",
                    )
                }
                return@Column
            }
            val pagerState = rememberPagerState(
                initialPage = categories.indexOfFirst { it.id == c.prefs.lastCategoryId.get() }.coerceAtLeast(0),
            ) { categories.size }
            LaunchedEffect(pagerState.currentPage) {
                categories.getOrNull(pagerState.currentPage)?.let { c.prefs.lastCategoryId.set(it.id) }
            }
            if (categories.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage.coerceIn(0, categories.lastIndex), edgePadding = 12.dp) {
                    categories.forEachIndexed { i, cat ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text("${cat.name} (${mangaFor(cat).size})") },
                        )
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { c.repository.refreshLibrary().onFailure { snackbar.showSnackbar(it.message ?: "Refresh failed") } } },
                modifier = Modifier.fillMaxSize(),
            ) {
                HorizontalPager(pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val items = mangaFor(categories[page])
                    if (items.isEmpty()) {
                        EmptyState(if (query.isNotBlank() || filterUnread || filterDownloaded) "Nothing matches" else "This category is empty")
                    } else {
                        LazyVerticalGrid(
                            columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(112.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items, key = { it.id }) { manga ->
                                LibraryItem(
                                    manga = manga,
                                    unread = if (showUnread) manga.unreadCount else 0,
                                    downloads = if (showDownloads) downloadsPerManga[manga.id] ?: 0 else 0,
                                    onClick = { onOpenManga(manga.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Filter", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                ToggleRow("Unread only", filterUnread) { c.prefs.libraryFilterUnread.set(it) }
                ToggleRow("Downloaded on this phone only", filterDownloaded) { c.prefs.libraryFilterDownloaded.set(it) }
                Text("Sort", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibrarySort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { c.prefs.librarySort.set(s) }, label = { Text(s.label) })
                    }
                }
                ToggleRow("Ascending", ascending) { c.prefs.librarySortAscending.set(it) }
                Text("Display", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Auto", 2 to "2", 3 to "3", 4 to "4", 5 to "5").forEach { (v, l) ->
                        FilterChip(selected = columns == v, onClick = { c.prefs.libraryColumns.set(v) }, label = { Text(l) })
                    }
                }
                ToggleRow("Unread badge", showUnread) { c.prefs.libraryShowUnreadBadge.set(it) }
                ToggleRow("Downloaded badge", showDownloads) { c.prefs.libraryShowDownloadBadge.set(it) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun LibraryItem(manga: Manga, unread: Int, downloads: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clickable(onClick = onClick),
    ) {
        Cover(manga.thumbnailUrl, manga.thumbnailLastFetched, Modifier.fillMaxSize(), manga.title)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xDD000000)),
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ),
        )
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (downloads > 0) Badge("$downloads", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
            if (unread > 0) Badge("$unread", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        }
        Text(
            manga.title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
    }
}

@Suppress("unused")
@OptIn(ExperimentalFoundationApi::class)
private fun unused() = Unit
