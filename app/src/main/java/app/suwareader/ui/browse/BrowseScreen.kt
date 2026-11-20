package app.suwareader.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.suwareader.domain.Source
import app.suwareader.ui.components.Cover
import app.suwareader.ui.components.EmptyState
import app.suwareader.ui.components.container
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun BrowseScreen(onOpenSource: (String, String) -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<Source>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        refreshing = true
        c.repository.online { c.api.sources() }
            .onSuccess {
                sources = it.sortedWith(compareBy({ it.lang }, { it.name }))
                error = null
            }
            .onFailure { error = it.message }
        refreshing = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = { TopAppBar(title = { Text("Browse sources") }) }) { padding ->
        PullToRefreshBox(refreshing, onRefresh = { scope.launch { load() } }, modifier = Modifier.padding(padding).fillMaxSize()) {
            val list = sources
            when {
                list == null && error != null -> EmptyState("Couldn't load sources", error)
                list == null -> {}
                list.isEmpty() -> EmptyState("No sources installed", "Install extensions in the Suwayomi web UI.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    list.groupBy { it.lang }.forEach { (lang, group) ->
                        item(key = "lang-$lang") {
                            Text(
                                languageName(lang),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group, key = { it.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenSource(s.id, s.displayName) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(s.iconUrl, 0, Modifier.size(36.dp))
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(s.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun languageName(code: String): String = when (code) {
    "all" -> "Multi-language"
    "localsourcelang" -> "Local"
    else -> Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).ifBlank { code }
}
