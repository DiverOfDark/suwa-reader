package app.suwareader.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File

/** Small atomic JSON file store for the offline cache. */
class JsonStore(private val root: File) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun file(name: String) = File(root, name)

    fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val f = file(name)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(serializer, f.readText())
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Dropping unreadable cache $name: ${e.asLog()}" }
            f.delete()
            null
        }
    }

    @Synchronized
    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val f = file(name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun delete(name: String) {
        file(name).delete()
    }
}
