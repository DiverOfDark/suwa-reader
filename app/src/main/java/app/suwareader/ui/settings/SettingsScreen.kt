package app.suwareader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.suwareader.BuildConfig
import app.suwareader.data.prefs.AppPreferences
import app.suwareader.data.prefs.collectAsState
import app.suwareader.ui.components.container
import app.suwareader.ui.components.formatBytes
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    var cacheInfo by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Section("Server")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerForm(saveLabel = "Test and save")
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Section("Downloads")
            SwitchRow("Only download on Wi-Fi", c.prefs.downloadOnlyOnWifi)
            Choice(
                "Chapters downloaded at the same time",
                c.prefs.parallelDownloads,
                (1..app.suwareader.data.download.DownloadWorker.MAX_PARALLEL).map { it to it.toString() },
            )
            SwitchRow("Delete chapters after reading them", c.prefs.deleteAfterReading)
            Choice(
                "While reading a downloaded chapter, download the next",
                c.prefs.autoDownloadAhead,
                listOf(0 to "Off", 2 to "2", 3 to "3", 5 to "5", 10 to "10"),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Section("Reader")
            Choice(
                "Default reading mode",
                c.readerPreferences.defaultReadingMode,
                ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.map { it.flagValue to it.stringRes.text },
            )
            Text(
                "More reader options are in the reader's settings button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Section("Appearance")
            val mode by c.prefs.themeMode.collectAsState()
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPreferences.ThemeMode.entries.forEach { m ->
                    FilterChip(mode == m, onClick = { c.prefs.themeMode.set(m) }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            SwitchRow("Pure black in dark mode", c.prefs.pureBlack)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Section("App updates")
            UpdateSection()
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Section("Storage")
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            val size = c.readerCacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                            c.readerCacheDir.deleteRecursively()
                            size
                        }
                        cacheInfo = "Cleared ${formatBytes(freed)}"
                    }
                }.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column {
                    Text("Clear reading cache", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        cacheInfo ?: "Pages of chapters read online. Downloads are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "Suwa ${BuildConfig.VERSION_NAME} · reader based on Mihon (Apache-2.0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun SwitchRow(title: String, pref: Preference<Boolean>) {
    val value by pref.collectAsState()
    Row(
        Modifier.fillMaxWidth().clickable { pref.set(!value) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(value, onCheckedChange = { pref.set(it) })
    }
}

@Composable
private fun Choice(title: String, pref: Preference<Int>, options: List<Pair<Int, String>>) {
    val value by pref.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> FilterChip(value == v, onClick = { pref.set(v) }, label = { Text(l) }) }
        }
    }
}
