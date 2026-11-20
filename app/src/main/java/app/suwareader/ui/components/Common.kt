package app.suwareader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.suwareader.AppContainer
import app.suwareader.SuwaApp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.text.DateFormat
import java.util.Date

@Composable
fun container(): AppContainer = (LocalContext.current.applicationContext as SuwaApp).container

/** Cover image from the server, keyed by its last-fetched time so a new cover replaces the cached one. */
@Composable
fun Cover(
    path: String?,
    lastFetched: Long,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val c = container()
    val context = LocalContext.current
    val url = remember(path, lastFetched) { c.absoluteUrl(path)?.let { "$it?v=$lastFetched" } }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url).build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun OfflineBanner(reason: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, modifier = Modifier.size(18.dp))
            Text(
                "Offline — showing what's saved on this phone. ($reason)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 2,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, message: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
fun Badge(text: String, color: Color, contentColor: Color, modifier: Modifier = Modifier) {
    Surface(color = color, contentColor = contentColor, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** Suwayomi reports some times in seconds and others in milliseconds. */
fun Long.toMillisGuess(): Long = if (this in 1..99_999_999_999) this * 1000 else this

fun formatDate(epoch: Long): String {
    if (epoch <= 0) return ""
    val millis = epoch.toMillisGuess()
    val days = (System.currentTimeMillis() - millis) / 86_400_000
    return when {
        days < 1 && System.currentTimeMillis() >= millis -> "Today"
        days < 2 -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}

fun formatChapterNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
