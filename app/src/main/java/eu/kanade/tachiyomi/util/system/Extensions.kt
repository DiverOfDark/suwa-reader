package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.res.Resources
import android.provider.Settings

val Int.dpToPx: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

val Float.dpToPx: Float
    get() = (this * Resources.getSystem().displayMetrics.density)

val Context.animatorDurationScale: Float
    get() = Settings.Global.getFloat(this.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** The reader always uses a dark themed context; pages draw on the reader background. */
fun Context.createReaderThemeContext(): Context = this
