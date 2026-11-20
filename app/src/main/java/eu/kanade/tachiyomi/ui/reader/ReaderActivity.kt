package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.suwareader.SuwaApp
import app.suwareader.ui.theme.SuwaTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MANGA = "manga"
        const val EXTRA_CHAPTER = "chapter"

        fun newIntent(context: Context, mangaId: Long, chapterId: Long): Intent =
            Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA, mangaId)
                putExtra(EXTRA_CHAPTER, chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private val container by lazy { (application as SuwaApp).container }
    val readerPreferences: ReaderPreferences by lazy { container.readerPreferences }
    val downloadManager: DownloadManager by lazy { container.downloads }

    val viewModel by viewModels<ReaderViewModel>()
    val settingsViewModel by viewModels<ReaderSettingsViewModel> {
        viewModelFactory { initializer { ReaderSettingsViewModel(viewModel, readerPreferences) } }
    }

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }
    private var isLoadingViewer by mutableStateOf(true)
    private val viewerFlow = MutableStateFlow<Viewer?>(null)
    private val viewer: Viewer? get() = viewerFlow.value
    private var toast: Toast? = null

    var isScrollingThroughPages = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        super.onCreate(savedInstanceState)

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        setContent {
            SuwaTheme(forceDark = true) {
                val state by viewModel.state.collectAsState()
                val general by settingsViewModel.general.collectAsState()
                val showPageNumber by readerPreferences.showPageNumber.collectAsStatePref()
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                LaunchedEffect(general.keepScreenOn) {
                    if (general.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                val background = when (general.theme) {
                    ReaderPreferences.ReaderTheme.WHITE -> Color.White
                    ReaderPreferences.ReaderTheme.GRAY -> Color(0xFF202125)
                    else -> Color.Black
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(background)
                        .onPreviewKeyEvent(::onKeyEvent)
                        .focusRequester(focusRequester)
                        .focusable(),
                ) {
                    when {
                        state.initError != null -> InitError(state.initError!!)
                        else -> {
                            ReaderViewer()
                            if (isLoadingViewer) {
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            }
                            if (!state.menuVisible && showPageNumber && state.totalPages > 0) {
                                Text(
                                    text = "${state.currentPage} / ${state.totalPages}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(bottom = 6.dp)
                                        .background(Color(0x99000000), MaterialTheme.shapes.small)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            ReaderChrome(
                                state = state,
                                onBack = { finish() },
                                onBookmark = viewModel::toggleBookmark,
                                onPrev = { lifecycleScope.launch { viewModel.loadPreviousChapter() } },
                                onNext = { lifecycleScope.launch { viewModel.loadNextChapter() } },
                                onPageIndexChange = {
                                    isScrollingThroughPages = true
                                    moveToPageIndex(it)
                                },
                                onPageIndexChangeFinished = { isScrollingThroughPages = false },
                                readingMode = ReadingMode.fromPreference(viewModel.getMangaReadingMode(resolveDefault = false)),
                                onReadingMode = viewModel::openReadingModeSelect,
                                orientation = ReaderOrientation.fromPreference(viewModel.getMangaOrientation(resolveDefault = false)),
                                onOrientation = ::cycleOrientation,
                                onSettings = viewModel::openSettings,
                            )
                        }
                    }
                }

                if (state.isLoadingAdjacentChapter) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Text("Loading…")
                            }
                        },
                    )
                }

                when (state.dialog) {
                    ReaderViewModel.Dialog.Settings -> ReaderSettingsSheet(
                        settingsViewModel = settingsViewModel,
                        preferences = readerPreferences,
                        readingMode = ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
                        onDismiss = viewModel::closeDialog,
                    )
                    ReaderViewModel.Dialog.ReadingMode -> ReadingModeSheet(
                        current = ReadingMode.fromPreference(viewModel.getMangaReadingMode(resolveDefault = false)),
                        onSelect = {
                            viewModel.setMangaReadingMode(it)
                            viewModel.closeDialog()
                        },
                        onDismiss = viewModel::closeDialog,
                    )
                    null -> {}
                }
            }
        }

        setMenuVisibility(viewModel.state.value.menuVisible)

        viewModel.state
            .map { it.manga?.readingMode }
            .distinctUntilChanged()
            .onEach { if (viewModel.state.value.manga != null) updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> viewModel.state.value.viewerChapters?.let(::setChapters)
                    is ReaderViewModel.Event.SetOrientation -> setOrientation(event.orientation)
                    is ReaderViewModel.Event.Message -> showToast(event.text)
                }
            }
            .launchIn(lifecycleScope)
    }

    @Composable
    private fun ReaderViewer() {
        val v by viewerFlow.collectAsState()
        val general by settingsViewModel.general.collectAsState()
        val insets = when {
            !general.fullscreen -> WindowInsets.systemBars
            !general.drawUnderCutout -> WindowInsets.displayCutout
            else -> WindowInsets(0)
        }
        val colorFilter = remember(general.grayscale, general.invertedColors) {
            pageColorFilter(general.grayscale, general.invertedColors)
        }
        CompositionLocalProvider(LocalPageColorFilter provides colorFilter) {
            key(v) {
                v?.Content(modifier = Modifier.fillMaxSize().windowInsetsPadding(insets))
            }
        }
    }

    @Composable
    private fun InitError(error: Throwable) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Couldn't open this chapter",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                error.message ?: error.javaClass.simpleName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = .7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { finish() }) { Text("Back") }
                Button(onClick = { recreate() }) { Text("Retry") }
            }
        }
    }

    private fun onKeyEvent(event: KeyEvent): Boolean {
        val v = viewer ?: return false
        val isUp = event.type == KeyEventType.KeyUp
        val action = when (event.key) {
            Key.VolumeDown, Key.VolumeUp -> {
                val general = settingsViewModel.general.value
                if (!general.volumeKeysEnabled || viewModel.state.value.menuVisible) return false
                if ((event.key == Key.VolumeDown) != general.volumeKeysInverted) Action.FORWARD else Action.BACKWARD
            }
            Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> Action.FORWARD
            Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> Action.BACKWARD
            Key.Menu -> Action.MENU
            else -> return false
        }
        if (isUp) {
            when (action) {
                Action.MENU -> toggleMenu()
                Action.FORWARD -> v.moveForward()
                Action.BACKWARD -> v.moveBackward()
            }
        }
        return true
    }

    // region called by the viewers
    fun onViewerTap(pos: Offset) {
        when (settingsViewModel.navigation.value?.getAction(pos) ?: Action.MENU) {
            Action.MENU -> toggleMenu()
            Action.FORWARD -> viewer?.moveForward()
            Action.BACKWARD -> viewer?.moveBackward()
        }
    }

    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPageLongTap(page: ReaderPage) {
        toggleMenu()
    }

    fun requestPreloadChapter(chapter: ReaderChapter) {
        viewModel.requestPreload(chapter)
    }

    fun hideMenu() {
        if (viewModel.state.value.menuVisible) setMenuVisibility(false)
    }

    fun showMenu() {
        if (!viewModel.state.value.menuVisible) setMenuVisibility(true)
    }
    // endregion

    private fun toggleMenu() = setMenuVisibility(!viewModel.state.value.menuVisible)

    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateViewer() {
        val prev = viewer
        val mode = viewModel.getMangaReadingMode()
        settingsViewModel.setViewerReadingMode(ReadingMode.fromPreference(mode))
        val newViewer = ReadingMode.toViewer(mode, this)
        setOrientation(viewModel.getMangaOrientation())
        prev?.destroy()
        viewerFlow.value = newViewer
        isLoadingViewer = true
        viewModel.state.value.viewerChapters?.let { setChapters(it) }
    }

    private fun setChapters(chapters: ViewerChapters) {
        val v = viewer ?: return
        v.setChapters(chapters)
        isLoadingViewer = false
    }

    private fun moveToPageIndex(index: Int) {
        val v = viewer ?: return
        val chapter = viewModel.state.value.currentChapter ?: return
        val page = chapter.pages?.getOrNull(index) ?: return
        v.moveToPage(page)
    }

    private fun cycleOrientation() {
        val entries = ReaderOrientation.entries
        val current = ReaderOrientation.fromPreference(viewModel.getMangaOrientation(resolveDefault = false))
        val next = entries[(entries.indexOf(current) + 1) % entries.size]
        viewModel.setMangaOrientationType(next)
        showToast(next.stringRes.text)
    }

    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) requestedOrientation = newOrientation.flag
    }

    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onResume() {
        super.onResume()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setMenuVisibility(viewModel.state.value.menuVisible)
    }

    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewer?.destroy()
        toast?.cancel()
    }
}

private fun pageColorFilter(grayscale: Boolean, inverted: Boolean): ColorFilter? {
    if (!grayscale && !inverted) return null
    val matrix = ColorMatrix()
    if (grayscale) matrix.setToSaturation(0f)
    if (inverted) {
        matrix.timesAssign(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    return ColorFilter.colorMatrix(matrix)
}

@Composable
private fun <T> tachiyomi.core.common.preference.Preference<T>.collectAsStatePref() =
    remember(this) { changes() }.collectAsState(initial = get())
