package eu.kanade.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.suwareader.SuwaApp
import app.suwareader.ui.theme.SuwaTheme
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/** Reader content (transitions, errors) is drawn on the reader background, so match its darkness. */
@Composable
fun TachiyomiTheme(content: @Composable () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as SuwaApp).container.readerPreferences
    val white = ReaderPreferences.ReaderTheme.fromValue(prefs.readerTheme.get()) == ReaderPreferences.ReaderTheme.WHITE
    SuwaTheme(forceDark = !white, forceLight = white, content = content)
}

@Composable
fun TachiyomiPreviewTheme(content: @Composable () -> Unit) = SuwaTheme(content = content)
