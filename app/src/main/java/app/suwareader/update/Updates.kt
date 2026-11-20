package app.suwareader.update

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.suwareader.BuildConfig
import app.suwareader.R
import app.suwareader.SuwaApp
import app.suwareader.data.prefs.AppPreferences
import app.suwareader.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * In-app updates from this app's GitHub releases (flow ported from Phos): read `update.json` from the latest
 * release, download the APK for this phone's ABI, check its sha256 and signing key, then install it.
 */
class Updates(
    private val context: Context,
    private val prefs: AppPreferences,
) {
    // Plain client: GitHub doesn't need the Suwayomi login, and must never be sent it
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val verifier = ApkVerifier(context)
    private val installer = ApkInstaller(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    private var lastCheckedAt = 0L

    val running = RunningVersion(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)

    /** Local builds (versionCode 1) never offer updates over themselves unless asked. */
    val isDevBuild get() = BuildConfig.DEBUG

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    var pendingVersionName: String? = null
        private set

    suspend fun checkQuietly() {
        if (isDevBuild) return
        if (_state.value !is UpdateState.Unknown && System.currentTimeMillis() - lastCheckedAt < 60 * 60 * 1000L) return
        check()
    }

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = try {
            val request = Request.Builder().url(UpdateCheck.MANIFEST_URL).header("Accept", "application/json").build()
            val manifest = client.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> null // no release yet
                    !r.isSuccessful -> throw IOException("GitHub answered HTTP ${r.code}")
                    else -> json.decodeFromString(ReleaseManifest.serializer(), r.body.string())
                }
            }
            UpdateCheck.decide(running, manifest, Build.SUPPORTED_ABIS.toList())
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Update check failed: ${e.asLog()}" }
            UpdateState.Failed(describe(e))
        }
        lastCheckedAt = System.currentTimeMillis()
        _state.value = result
        result
    }

    fun startInstall(available: UpdateState.Available) {
        if (installJob?.isActive == true) return
        installJob = scope.launch { downloadAndInstall(available) }
    }

    suspend fun downloadAndInstall(available: UpdateState.Available) {
        if (!installer.canInstall()) {
            _install.value = InstallState.PermissionRequired
            return
        }
        pendingVersionName = available.versionName
        _install.value = InstallState.Downloading(0, available.sizeBytes)
        val file = try {
            download(available)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Downloading ${available.versionName} failed: ${e.asLog()}" }
            _install.value = InstallState.Failed(describe(e))
            return
        }
        _install.value = InstallState.Verifying
        val verdict = verifier.verify(file, available.sha256)
        if (verdict != ApkVerdict.Ok) {
            logcat(LogPriority.ERROR) { "Refusing to install ${available.versionName}: $verdict" }
            file.delete()
            _install.value = InstallState.Refused(verdict.refusalMessage() ?: "Refused to install.")
            return
        }
        try {
            _install.value = InstallState.AwaitingConfirmation
            installer.install(file)
        } catch (e: Exception) {
            _install.value = InstallState.Failed(describe(e))
        }
    }

    private suspend fun download(available: UpdateState.Available): File = withContext(Dispatchers.IO) {
        require(available.url.startsWith(UpdateCheck.ALLOWED_PREFIX)) { "Unexpected download location" }
        // Inside the app sandbox for its whole life, so it can't be swapped between the check and the install
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "suwa-${available.versionCode}.apk")
        client.newCall(Request.Builder().url(available.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered HTTP ${r.code} for the APK")
            if (!r.request.url.isHttps) throw IOException("The download was redirected off HTTPS")
            val total = available.sizeBytes
            var written = 0L
            r.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > total) throw IOException("Download exceeded the advertised $total bytes")
                        output.write(buffer, 0, read)
                        _install.value = InstallState.Downloading(written, total)
                    }
                }
            }
            if (written != total) throw IOException("Download stopped at $written of $total bytes")
        }
        target
    }

    fun onInstallOutcome(state: InstallState) {
        // A result for an install started by an earlier process (e.g. the one this update replaced) isn't ours
        if (pendingVersionName == null) return
        _install.value = state
    }

    fun dismissInstall() {
        _install.value = InstallState.Idle
    }

    fun permissionSettingsIntent() = installer.permissionSettingsIntent()

    fun canInstallSilently() = installer.canInstall()

    /** Background check: install on its own when allowed, otherwise tell the user. */
    suspend fun backgroundCheck() {
        if (isDevBuild) return
        val state = check()
        if (state !is UpdateState.Available) return
        if (prefs.autoInstallUpdates.get() && installer.canInstall()) {
            downloadAndInstall(state)
            if (_install.value is InstallState.Failed || _install.value is InstallState.Refused) notifyAvailable(state)
        } else {
            notifyAvailable(state)
        }
    }

    private fun notifyAvailable(state: UpdateState.Available) {
        Notifications.notify(
            context,
            Notifications.ID_UPDATE,
            NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Suwa ${state.versionName} is available")
                .setContentText("Open Suwa → More to install it.")
                .setContentIntent(Notifications.openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "GitHub didn't answer in time."
        is IOException -> "Couldn't reach GitHub (${e.message ?: "no connection"})."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val WORK_NAME = "update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as SuwaApp).container.updates.backgroundCheck()
        return Result.success()
    }
}
