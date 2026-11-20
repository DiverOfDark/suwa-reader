package app.suwareader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.suwareader.SuwaApp
import app.suwareader.data.prefs.AppPreferences
import app.suwareader.data.prefs.collectAsState

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC9B8FF),
    onPrimary = Color(0xFF2F1F66),
    primaryContainer = Color(0xFF46368A),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFFFB1C0),
    tertiary = Color(0xFF8FD5C9),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5D4CA3),
    secondary = Color(0xFF9C3D55),
    tertiary = Color(0xFF00695C),
)

@Composable
fun SuwaTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as SuwaApp).container.prefs
    val mode by prefs.themeMode.collectAsState()
    val pureBlack by prefs.pureBlack.collectAsState()
    val dark = forceDark || when (mode) {
        AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppPreferences.ThemeMode.DARK -> true
        AppPreferences.ThemeMode.LIGHT -> false
    }
    var scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    if (dark && pureBlack) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0C0C0E),
            surfaceContainer = Color(0xFF121215),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
