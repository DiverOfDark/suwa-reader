package tachiyomi.presentation.core.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

const val SECONDARY_ALPHA = .78f

fun Modifier.secondaryItemAlpha(): Modifier = this.alpha(SECONDARY_ALPHA)
