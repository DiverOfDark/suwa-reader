package app.suwareader.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.suwareader.R
import app.suwareader.SuwaApp
import app.suwareader.util.Notifications

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val updates = (context.applicationContext as SuwaApp).container.updates
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        android.util.Log.i("SuwaUpdate", "Install status $status ($message)")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: run {
                    updates.onInstallOutcome(InstallState.Failed("The installer didn't return a confirmation screen."))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                updates.onInstallOutcome(InstallState.AwaitingConfirmation)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                // Try to open the prompt either way; Android may refuse when we're in the background, so then also
                // leave it one tap away in a notification
                runCatching { context.startActivity(confirm) }
                if (!foreground) {
                    // Android won't let a background app open the confirmation; leave it one tap away instead
                    val pi = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifications.notify(
                        context,
                        Notifications.ID_UPDATE,
                        NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
                            .setSmallIcon(R.drawable.ic_stat_download)
                            .setContentTitle("Suwa update ready")
                            .setContentText("Tap to finish installing ${updates.pendingVersionName ?: "the update"}")
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.onInstallOutcome(InstallState.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.onInstallOutcome(InstallState.Declined)
            else -> {
                val head = when (status) {
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "The installed Suwa conflicts with this build (different signing key?)."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "That build isn't compatible with this phone."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "The installer rejected the package as invalid."
                    else -> "The install didn't complete."
                }
                updates.onInstallOutcome(InstallState.Failed(message?.takeIf { it.isNotBlank() }?.let { "$head ($it)" } ?: head))
            }
        }
    }

    companion object {
        const val ACTION = "app.suwareader.INSTALL_STATUS"
    }
}
