package app.suwareader.ui.updates

import androidx.compose.runtime.Composable
import app.suwareader.ui.components.ChapterFeed
import app.suwareader.ui.components.container

@Composable
fun UpdatesScreen(onOpenManga: (Long) -> Unit) {
    val c = container()
    ChapterFeed(
        title = "Updates",
        cacheName = "updates.json",
        emptyText = "No recent chapters",
        groupKey = { it.chapter.fetchedAt },
        subtitle = { e -> if (!e.chapter.isRead && e.chapter.lastPageRead > 0) "Page ${e.chapter.lastPageRead + 1}" else "" },
        load = { offset -> c.api.recentUpdates(offset, 50) },
        onOpenManga = onOpenManga,
    )
}
