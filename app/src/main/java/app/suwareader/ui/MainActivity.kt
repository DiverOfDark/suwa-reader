package app.suwareader.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.suwareader.SuwaApp
import app.suwareader.data.prefs.collectAsState
import app.suwareader.ui.browse.BrowseScreen
import app.suwareader.ui.browse.SourceScreen
import app.suwareader.ui.downloads.DownloadsScreen
import app.suwareader.ui.history.HistoryScreen
import app.suwareader.ui.library.LibraryScreen
import app.suwareader.ui.manga.MangaScreen
import app.suwareader.ui.settings.MoreScreen
import app.suwareader.ui.settings.SettingsScreen
import app.suwareader.ui.settings.SetupScreen
import app.suwareader.ui.theme.SuwaTheme
import app.suwareader.ui.updates.UpdatesScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SuwaTheme {
                val prefs = (application as SuwaApp).container.prefs
                val server by prefs.serverUrl.collectAsState()
                if (server.isBlank()) {
                    SetupScreen()
                } else {
                    NotificationPermission()
                    AppNavigation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val c = (application as SuwaApp).container
        if (c.prefs.serverUrl.get().isNotBlank()) {
            lifecycleScope.launch { c.repository.refreshLibrary() }
        }
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("updates", "Updates", Icons.Filled.NewReleases),
    Tab("history", "History", Icons.Filled.History),
    Tab("browse", "Browse", Icons.Filled.Explore),
    Tab("more", "More", Icons.Filled.MoreHoriz),
)

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val bottom = Modifier.padding(bottom = if (showBar) padding.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))
        NavHost(nav, startDestination = "library", modifier = bottom) {
            composable("library") { LibraryScreen(onOpenManga = { nav.navigate("manga/$it") }) }
            composable("updates") { UpdatesScreen(onOpenManga = { nav.navigate("manga/$it") }) }
            composable("history") { HistoryScreen(onOpenManga = { nav.navigate("manga/$it") }) }
            composable("browse") { BrowseScreen(onOpenSource = { id, name -> nav.navigate("source/$id?name=${android.net.Uri.encode(name)}") }) }
            composable("more") {
                MoreScreen(
                    onOpenDownloads = { nav.navigate("downloads") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(
                "manga/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { e ->
                MangaScreen(mangaId = e.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
            composable(
                "source/{id}?name={name}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                SourceScreen(
                    sourceId = e.arguments!!.getString("id")!!,
                    sourceName = e.arguments!!.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenManga = { nav.navigate("manga/$it") },
                )
            }
            composable("downloads") { DownloadsScreen(onBack = { nav.popBackStack() }, onOpenManga = { nav.navigate("manga/$it") }) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
