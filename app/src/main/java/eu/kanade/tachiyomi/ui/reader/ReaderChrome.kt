package eu.kanade.tachiyomi.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.core.common.preference.Preference
import kotlin.math.roundToInt

private val BarColor = Color(0xE6121216)

@Composable
fun ReaderChrome(
    state: ReaderViewModel.State,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    readingMode: ReadingMode,
    onReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onOrientation: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = BarColor, contentColor = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.manga?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.currentChapter?.chapter?.name.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = .7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onBookmark) {
                        Icon(
                            if (state.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            "Bookmark",
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(color = BarColor, contentColor = Color.White, shape = RoundedCornerShape(28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        val rtl = readingMode == ReadingMode.RIGHT_TO_LEFT
                        IconButton(
                            onClick = if (rtl) onNext else onPrev,
                            enabled = if (rtl) state.viewerChapters?.nextChapter != null else state.viewerChapters?.prevChapter != null,
                        ) { Icon(Icons.Filled.SkipPrevious, if (rtl) "Next chapter" else "Previous chapter") }
                        val total = state.totalPages
                        if (total > 1) {
                            var dragging by remember { mutableFloatStateOf(-1f) }
                            val current = (state.currentPage - 1).coerceAtLeast(0)
                            Text("${state.currentPage}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
                            Slider(
                                value = if (dragging >= 0) dragging else current.toFloat(),
                                onValueChange = {
                                    dragging = it
                                    val idx = it.roundToInt()
                                    onPageIndexChange(if (rtl) total - 1 - idx else idx)
                                },
                                onValueChangeFinished = {
                                    dragging = -1f
                                    onPageIndexChangeFinished()
                                },
                                valueRange = 0f..(total - 1).toFloat(),
                                steps = (total - 2).coerceAtLeast(0),
                                modifier = Modifier.weight(1f).graphicsLayerRtl(rtl),
                            )
                            Text("$total", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp).padding(start = 6.dp))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        IconButton(
                            onClick = if (rtl) onPrev else onNext,
                            enabled = if (rtl) state.viewerChapters?.prevChapter != null else state.viewerChapters?.nextChapter != null,
                        ) { Icon(Icons.Filled.SkipNext, if (rtl) "Previous chapter" else "Next chapter") }
                    }
                }
                Surface(color = BarColor, contentColor = Color.White, shape = RoundedCornerShape(28.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = onReadingMode) { Icon(readingMode.icon, "Reading mode") }
                        IconButton(onClick = onOrientation) { Icon(orientation.icon, "Rotation") }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Reader settings") }
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerRtl(rtl: Boolean): Modifier =
    if (rtl) this.graphicsLayer(scaleX = -1f) else this

@Composable
fun ReadingModeSheet(current: ReadingMode, onSelect: (ReadingMode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Reading mode for this series", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ReadingMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(mode.icon, null, tint = if (mode == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (mode == ReadingMode.DEFAULT) "Default (from settings)" else mode.stringRes.text,
                        modifier = Modifier.padding(start = 16.dp),
                        color = if (mode == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderSettingsSheet(
    settingsViewModel: ReaderSettingsViewModel,
    preferences: ReaderPreferences,
    readingMode: ReadingMode,
    seriesMode: ReadingMode,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            val isWebtoon = readingMode.type == ReadingMode.ViewerType.Webtoon
            SectionTitle(if (isWebtoon) "Long strip" else "Paged")
            if (isWebtoon) {
                IntSlider("Side padding", preferences.webtoonSidePadding, 0..25, suffix = "%")
                SwitchPref("Crop borders", preferences.cropBordersWebtoon)
                SwitchPref("Double tap to zoom", preferences.webtoonDoubleTapZoomEnabled)
                SwitchPref("Disable zoom out", preferences.webtoonDisableZoomOut)
                SwitchPref("Split wide pages", preferences.dualPageSplitWebtoon)
                ChoicePref(
                    "Tap zones",
                    preferences.navigationModeWebtoon,
                    ReaderPreferences.NavigationMode.entries.map { it.value to navLabel(it) },
                )
                EnumChoice("Invert tap zones", preferences.webtoonNavInverted, ReaderPreferences.TappingInvertMode.entries) { it.titleRes.text }
            } else {
                ChoicePref(
                    "Scale type",
                    preferences.imageScaleType,
                    ReaderPreferences.ScaleType.entries.mapIndexed { i, t -> t.value to ReaderPreferences.ImageScaleType[i].text },
                )
                ChoicePref(
                    "Zoom start position",
                    preferences.zoomStart,
                    ReaderPreferences.ZoomStartType.entries.mapIndexed { i, t -> t.value to ReaderPreferences.ZoomStart[i].text },
                )
                SwitchPref("Crop borders", preferences.cropBorders)
                SwitchPref("Zoom landscape images", preferences.landscapeZoom)
                SwitchPref("Split wide pages", preferences.dualPageSplitPaged)
                SwitchPref("Rotate wide pages to fit", preferences.dualPageRotateToFit)
                ChoicePref(
                    "Tap zones",
                    preferences.navigationModePager,
                    ReaderPreferences.NavigationMode.entries.map { it.value to navLabel(it) },
                )
                EnumChoice("Invert tap zones", preferences.pagerNavInverted, ReaderPreferences.TappingInvertMode.entries) { it.titleRes.text }
                SwitchPref("Page transitions", preferences.pageTransitions)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("General")
            ChoicePref(
                "Default reading mode",
                preferences.defaultReadingMode,
                ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.map { it.flagValue to it.stringRes.text },
            )
            if (seriesMode != ReadingMode.DEFAULT) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "This series has its own mode (${seriesMode.stringRes.text}), so the default doesn't apply here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = onUseDefault) { Text("Use default") }
                }
            }
            ChoicePref(
                "Background",
                preferences.readerTheme,
                listOf(1 to "Black", 2 to "Gray", 0 to "White", 3 to "Automatic"),
            )
            SwitchPref("Show page number", preferences.showPageNumber)
            SwitchPref("Fullscreen", preferences.fullscreen)
            SwitchPref("Draw under the camera cutout", preferences.drawUnderCutout)
            SwitchPref("Keep screen on", preferences.keepScreenOn)
            SwitchPref("Always show chapter transition", preferences.alwaysShowChapterTransition)
            SwitchPref("Volume keys turn pages", preferences.readWithVolumeKeys)
            SwitchPref("Invert volume keys", preferences.readWithVolumeKeysInverted)
            SwitchPref("Long tap opens menu", preferences.readWithLongTap)
            SwitchPref("Grayscale", preferences.grayscale)
            SwitchPref("Inverted colors", preferences.invertedColors)
            SwitchPref("Preload only on Wi-Fi", preferences.loadAheadOnlyOverWifi)
        }
    }
}

private fun navLabel(mode: ReaderPreferences.NavigationMode) = when (mode) {
    ReaderPreferences.NavigationMode.DEFAULT -> "Default"
    ReaderPreferences.NavigationMode.L_SHAPED -> "L shaped"
    ReaderPreferences.NavigationMode.KINDLISH -> "Kindle-ish"
    ReaderPreferences.NavigationMode.EDGE -> "Edge"
    ReaderPreferences.NavigationMode.RIGHT_AND_LEFT -> "Right and left"
    ReaderPreferences.NavigationMode.DISABLED -> "Disabled"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun <T> Preference<T>.state() = remember(this) { changes() }.collectAsState(initial = get())

@Composable
private fun SwitchPref(title: String, pref: Preference<Boolean>) {
    val value by pref.state()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { pref.set(!value) }
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = { pref.set(it) })
    }
}

@Composable
private fun ChoicePref(title: String, pref: Preference<Int>, options: List<Pair<Int, String>>) {
    val value by pref.state()
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowChips(options.map { it.second }, options.indexOfFirst { it.first == value }) { pref.set(options[it].first) }
    }
}

@Composable
private fun <E> EnumChoice(title: String, pref: Preference<E>, entries: List<E>, label: (E) -> String) {
    val value by pref.state()
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowChips(entries.map(label), entries.indexOf(value)) { pref.set(entries[it]) }
    }
}

@Composable
private fun FlowChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(l) })
        }
    }
}

@Composable
private fun IntSlider(title: String, pref: Preference<Int>, range: IntRange, suffix: String = "") {
    val value by pref.state()
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value$suffix", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { pref.set(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Suppress("unused")
private val unusedSize = Modifier.size(0.dp).clip(RoundedCornerShape(0.dp))
