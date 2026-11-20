package app.suwareader.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.suwareader.BuildConfig
import app.suwareader.R
import app.suwareader.util.Notifications

/** Silent self-updates close the app; say what happened so it doesn't look like a crash. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifications.createChannels(context)
        Notifications.cancel(context, Notifications.ID_UPDATE)
        Notifications.notify(
            context,
            Notifications.ID_UPDATED,
            NotificationCompat.Builder(context, Notifications.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Suwa updated to ${BuildConfig.VERSION_NAME}")
                .setContentText("Tap to open it.")
                .setContentIntent(Notifications.openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }
}
