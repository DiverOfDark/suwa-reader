package app.suwareader.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.suwareader.data.download.QueuedDownload
import app.suwareader.ui.components.EmptyState
import app.suwareader.ui.components.container
import app.suwareader.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenManga: (Long) -> Unit) {
    val c = container()
    val queue by c.downloads.queue.collectAsState()
    val downloaded by c.downloads.downloaded.collectAsState()
    val paused by c.downloads.paused.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var totalSize by remember { mutableLongStateOf(0) }
    var confirmDeleteManga by remember { mutableStateOf<Pair<Long, String>?>(null) }
    LaunchedEffect(downloaded.size) { totalSize = withContext(Dispatchers.IO) { c.downloads.totalSizeBytes() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { c.downloads.setPaused(!paused) }) {
                            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Retry failed") }, onClick = {
                                menu = false
                                c.downloads.retryFailed()
                            })
                            DropdownMenuItem(text = { Text("Clear queue") }, onClick = {
                                menu = false
                                c.downloads.clearQueue()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Queue (${queue.size})") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("On this phone (${downloaded.size})") })
            }
            if (tab == 0) {
                if (queue.isEmpty()) {
                    EmptyState("Nothing queued", "Download chapters from a manga's page to read them offline.")
                } else {
                    if (paused) Text("Paused", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(queue, key = { it.chapter.id }) { item -> QueueRow(item, onTop = { c.downloads.move(item.chapter.id, true) }, onCancel = { c.downloads.cancel(listOf(item.chapter.id)) }) }
                    }
                }
            } else {
                val byManga = downloaded.values.groupBy { it.mangaId }.toList().sortedBy { it.second.first().mangaTitle.lowercase() }
                Text("${formatBytes(totalSize)} used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                if (byManga.isEmpty()) {
                    EmptyState("No downloads yet")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(byManga, key = { it.first }) { (mangaId, chapters) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenManga(mangaId) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(chapters.first().mangaTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${chapters.size} chapters · ${formatBytes(chapters.sumOf { it.sizeBytes })}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { confirmDeleteManga = mangaId to chapters.first().mangaTitle }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDeleteManga?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { confirmDeleteManga = null },
            title = { Text("Delete downloads?") },
            text = { Text("All downloaded chapters of $title are removed from this phone. Nothing changes on the server.") },
            confirmButton = { TextButton(onClick = { c.downloads.deleteManga(id); confirmDeleteManga = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeleteManga = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueRow(item: QueuedDownload, onTop: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.mangaTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.chapter.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (item.state) {
                QueuedDownload.State.DOWNLOADING -> LinearProgressIndicator(
                    progress = { if (item.pagesTotal > 0) item.pagesDone.toFloat() / item.pagesTotal else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                QueuedDownload.State.ERROR -> Text(item.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                QueuedDownload.State.QUEUED -> {}
            }
        }
        IconButton(onClick = onTop) { Icon(Icons.Filled.VerticalAlignTop, "Move to top") }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
    }
}
