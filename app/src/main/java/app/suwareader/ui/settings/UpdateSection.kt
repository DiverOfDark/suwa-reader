package app.suwareader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.suwareader.data.prefs.collectAsState
import app.suwareader.ui.components.container
import app.suwareader.ui.components.formatBytes
import app.suwareader.update.InstallState
import app.suwareader.update.UpdateState
import kotlinx.coroutines.launch

@Composable
fun UpdateSection() {
    val c = container()
    val updates = c.updates
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by updates.state.collectAsState()
    val install by updates.install.collectAsState()
    val auto by c.prefs.autoInstallUpdates.collectAsState()

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "This build: ${updates.running.versionName} (${updates.running.versionCode})" + when (val s = state) {
                is UpdateState.Available -> " · ${s.versionName} available"
                UpdateState.UpToDate -> " · up to date"
                UpdateState.Checking -> " · checking…"
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        (state as? UpdateState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        (state as? UpdateState.Available)?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
        }

        when (val i = install) {
            is InstallState.Downloading -> {
                LinearProgressIndicator(progress = { i.fraction }, modifier = Modifier.fillMaxWidth())
                Text("Downloading ${formatBytes(i.bytesRead)} of ${formatBytes(i.totalBytes)}", style = MaterialTheme.typography.bodySmall)
            }
            InstallState.Verifying -> Text("Checking the download's checksum and signing key…", style = MaterialTheme.typography.bodySmall)
            InstallState.AwaitingConfirmation -> Text("Waiting for Android to install it…", style = MaterialTheme.typography.bodySmall)
            InstallState.Installed -> Text("Installed. Restart Suwa to use it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            InstallState.Declined -> Text("Install cancelled.", style = MaterialTheme.typography.bodySmall)
            is InstallState.Refused -> Text(i.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is InstallState.Failed -> Text(i.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            InstallState.PermissionRequired -> Text(
                "Android needs permission for Suwa to install updates. Allow it, then come back and tap Install again.",
                style = MaterialTheme.typography.bodySmall,
            )
            InstallState.Idle -> {}
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val available = state as? UpdateState.Available
            val busy = install is InstallState.Downloading || install == InstallState.Verifying || install == InstallState.AwaitingConfirmation
            when {
                install == InstallState.PermissionRequired -> Button(onClick = {
                    updates.dismissInstall()
                    context.startActivity(updates.permissionSettingsIntent())
                }) { Text("Allow installs") }
                available != null && !busy && install != InstallState.Installed -> Button(onClick = {
                    updates.dismissInstall()
                    updates.startInstall(available)
                }) { Text("Install ${available.versionName}") }
            }
            OutlinedButton(onClick = { scope.launch { updates.check() } }, enabled = !busy && state != UpdateState.Checking) {
                Text("Check for updates")
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { c.prefs.autoInstallUpdates.set(!auto) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Install updates automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Checked twice a day from GitHub releases. Every APK is checksum- and signature-verified first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(auto, onCheckedChange = { c.prefs.autoInstallUpdates.set(it) })
        }
    }
}
