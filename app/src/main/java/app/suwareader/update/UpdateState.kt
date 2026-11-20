package app.suwareader.update

import kotlinx.serialization.Serializable

/** The build running in this process. */
data class RunningVersion(val versionCode: Long, val versionName: String)

/** `update.json`, published as an asset of every GitHub release by CI. */
@Serializable
data class ReleaseManifest(
    val versionCode: Long,
    val versionName: String,
    val notes: String = "",
    val apks: Map<String, ReleaseApk>,
)

@Serializable
data class ReleaseApk(
    val url: String,
    val sha256: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val notes: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    ) : UpdateState
    data class Failed(val message: String) : UpdateState
}

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : InstallState {
        val fraction: Float get() = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : InstallState
    data object AwaitingConfirmation : InstallState
    data object Installed : InstallState
    data object Declined : InstallState
    data class Refused(val reason: String) : InstallState
    data class Failed(val reason: String) : InstallState
    data object PermissionRequired : InstallState
}

/**
 * The rule that decides whether an update is offered (ported from Phos). Strictly newer or nothing: equal is noise,
 * older would walk the phone backwards. An advertisement that can't be verified is a failure, never "up to date".
 */
object UpdateCheck {
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val TRAILER = Regex("^[A-Za-z-]+-[Bb]y: .*$")

    fun decide(running: RunningVersion, manifest: ReleaseManifest?, abis: List<String>): UpdateState {
        if (manifest == null || manifest.versionCode <= running.versionCode) return UpdateState.UpToDate
        val apk = abis.firstNotNullOfOrNull { manifest.apks[it] } ?: manifest.apks["universal"]
            ?: return UpdateState.Failed("Release ${manifest.versionName} has no APK for this phone (${abis.joinToString()}).")
        val why = when {
            !SHA256.matches(apk.sha256) -> "its sha256 is not a digest"
            apk.size <= 0L -> "it declares no size"
            !apk.url.startsWith(ALLOWED_PREFIX) -> "its download isn't from this app's GitHub releases"
            manifest.versionName.isBlank() -> "it has no version name"
            else -> null
        }
        if (why != null) return UpdateState.Failed("Release ${manifest.versionName} can't be verified: $why. Not installing it.")
        val notes = manifest.notes.lines().filterNot { TRAILER.matches(it) }.joinToString("\n").trim()
        return UpdateState.Available(manifest.versionName, manifest.versionCode, notes, apk.url, apk.sha256, apk.size)
    }

    const val REPO = "DiverOfDark/suwa-reader"
    const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/update.json"
    const val ALLOWED_PREFIX = "https://github.com/$REPO/releases/download/"
}
