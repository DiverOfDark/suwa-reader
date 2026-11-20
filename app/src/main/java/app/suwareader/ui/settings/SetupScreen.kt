package app.suwareader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.suwareader.ui.components.container
import kotlinx.coroutines.launch

/** Shown until a server address is saved. */
@Composable
fun SetupScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Suwa", style = MaterialTheme.typography.displaySmall)
            Text(
                "A reader for your Suwayomi server. Your library, categories and reading progress live on the server; this phone keeps a copy for offline reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerForm(saveLabel = "Connect")
        }
    }
}

@Composable
fun ServerForm(saveLabel: String = "Save", onSaved: () -> Unit = {}) {
    val c = container()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(c.prefs.serverUrl.get().ifBlank { "https://" }) }
    var user by remember { mutableStateOf(c.prefs.authUser.get()) }
    var pass by remember { mutableStateOf(c.prefs.authPassword.get()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(url, { url = it.trim() }, label = { Text("Server address") }, placeholder = { Text("https://suwayomi.example.com") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(user, { user = it }, label = { Text("Username (if basic auth is on)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
    Button(
        enabled = !testing && url.length > 8,
        onClick = {
            scope.launch {
                testing = true
                val normalized = url.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                val r = runCatching { c.testConnection(normalized, user, pass) }
                testing = false
                r.onSuccess {
                    result = "Connected to $it"
                    c.prefs.authUser.set(user)
                    c.prefs.authPassword.set(pass)
                    c.prefs.serverUrl.set(normalized)
                    c.repository.launchRefreshLibrary()
                    onSaved()
                }.onFailure {
                    result = "Couldn't connect: ${it.message}"
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (testing) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
        Text(saveLabel)
    }
}
