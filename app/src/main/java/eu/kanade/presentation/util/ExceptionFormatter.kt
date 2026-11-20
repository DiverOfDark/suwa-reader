package eu.kanade.presentation.util

import android.content.Context
import java.net.UnknownHostException

context(context: Context)
val Throwable.formattedMessage: String
    get() {
        if (this is UnknownHostException) return "Can't reach the server (${message ?: "unknown host"})"
        return when (val className = this::class.simpleName) {
            "Exception", "IOException" -> message ?: className
            else -> "$className: $message"
        }
    }
