package app.suwareader.ui.history

import androidx.compose.runtime.Composable
import app.suwareader.ui.components.ChapterFeed
import app.suwareader.ui.components.container

@Composable
fun HistoryScreen(onOpenManga: (Long) -> Unit) {
    val c = container()
    ChapterFeed(
        title = "History",
        cacheName = "history.json",
        emptyText = "Nothing read yet",
        groupKey = { it.chapter.lastReadAt },
        subtitle = { e -> if (e.chapter.isRead) "Finished" else "Page ${e.chapter.lastPageRead + 1}" },
        load = { offset -> c.api.history(offset, 50) },
        onOpenManga = onOpenManga,
    )
}
