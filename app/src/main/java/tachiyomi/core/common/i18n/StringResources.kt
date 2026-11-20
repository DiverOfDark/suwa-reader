package tachiyomi.core.common.i18n

import android.content.Context
import dev.icerock.moko.resources.PluralsResource
import dev.icerock.moko.resources.StringResource

fun Context.stringResource(resource: StringResource): String = resource.text.fmt()

fun Context.stringResource(resource: StringResource, vararg args: Any): String = resource.text.fmt(*args)

fun Context.pluralStringResource(resource: PluralsResource, count: Int, vararg args: Any): String =
    resource.get(count).fmt(*args)

internal fun String.fmt(vararg args: Any): String =
    (if (args.isEmpty()) this else String.format(this, *args)).replace("\\'", "'").replace("\\\"", "\"")
