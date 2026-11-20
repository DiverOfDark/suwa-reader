package app.suwareader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suwareader.data.api.SuwayomiApi.BrowseType
import app.suwareader.domain.Manga
import app.suwareader.ui.components.Cover
import app.suwareader.ui.components.EmptyState
import app.suwareader.ui.components.container
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(sourceId: String, sourceName: String, onBack: () -> Unit, onOpenManga: (Long) -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(BrowseType.POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val items = remember { mutableStateListOf<Manga>() }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }

    suspend fun next() {
        if (loading || !hasMore) return
        loading = true
        val gen = generation
        val p = page + 1
        c.repository.online { c.api.browseSource(sourceId, type, p, submitted.takeIf { type == BrowseType.SEARCH }) }
            .onSuccess { (list, more) ->
                if (gen == generation) {
                    val seen = items.map { it.id }.toSet()
                    items.addAll(list.filter { it.id !in seen })
                    page = p
                    hasMore = more && list.isNotEmpty()
                    error = null
                }
            }
            .onFailure { if (gen == generation) error = it.message; hasMore = false }
        loading = false
    }

    fun reset(newType: BrowseType) {
        type = newType
        generation++
        items.clear()
        page = 0
        hasMore = true
        loading = false
        error = null
        scope.launch { next() }
    }

    LaunchedEffect(Unit) { if (items.isEmpty()) next() }

    val gridState = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > items.size - 9 } }
    LaunchedEffect(nearEnd) { if (nearEnd && items.isNotEmpty()) next() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search $sourceName") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    submitted = query.trim()
                    reset(if (submitted.isEmpty()) BrowseType.POPULAR else BrowseType.SEARCH)
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == BrowseType.POPULAR, onClick = { reset(BrowseType.POPULAR) }, label = { Text("Popular") })
                FilterChip(type == BrowseType.LATEST, onClick = { reset(BrowseType.LATEST) }, label = { Text("Latest") })
                if (submitted.isNotEmpty()) FilterChip(type == BrowseType.SEARCH, onClick = { reset(BrowseType.SEARCH) }, label = { Text("“$submitted”") })
            }
            if (items.isEmpty()) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> EmptyState("The source returned an error", error)
                    else -> EmptyState("Nothing found")
                }
                return@Column
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { m ->
                    Column(Modifier.clickable { onOpenManga(m.id) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                            Cover(m.thumbnailUrl, m.thumbnailLastFetched, Modifier.fillMaxSize(), m.title)
                            if (m.inLibrary) {
                                Icon(Icons.Filled.Favorite, "In library", tint = Color(0xFFFF6B8A), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                        }
                        Text(m.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
