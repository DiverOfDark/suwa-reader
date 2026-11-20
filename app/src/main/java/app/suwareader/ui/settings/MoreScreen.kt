package app.suwareader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.suwareader.data.prefs.collectAsState
import app.suwareader.data.repo.Connection
import app.suwareader.ui.components.container
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(onOpenDownloads: () -> Unit, onOpenSettings: () -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    val connection by c.repository.connection.collectAsState()
    val pending by c.progressSync.pending.collectAsState()
    val queue by c.downloads.queue.collectAsState()
    val server by c.prefs.serverUrl.collectAsState()
    val lastError by c.progressSync.lastError.collectAsState()
    val update by c.updates.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val (icon, status) = when (val conn = connection) {
                Connection.Online -> Icons.Filled.CloudDone to "Connected"
                is Connection.Offline -> Icons.Filled.CloudOff to "Offline: ${conn.reason}"
                Connection.Unknown -> Icons.Filled.CloudSync to "Connecting…"
            }
            Entry(icon, server, status)
            if (pending.isNotEmpty()) {
                Entry(
                    Icons.Filled.CloudSync,
                    "${pending.size} reading updates waiting for the server",
                    lastError?.let { "Last try: $it · tap to retry" } ?: "Tap to send now",
                ) { scope.launch { c.progressSync.flush() } }
            }
            (update as? app.suwareader.update.UpdateState.Available)?.let {
                Entry(Icons.Filled.SystemUpdate, "Suwa ${it.versionName} is available", "Tap to see it in Settings", onOpenSettings)
            }
            Entry(Icons.Filled.Download, "Downloads", if (queue.isEmpty()) "Queue empty" else "${queue.size} in queue", onOpenDownloads)
            Entry(Icons.Filled.Settings, "Settings", "Server, downloads, appearance", onOpenSettings)
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
