package app.tsunagi.e46m3.launcher.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.tsunagi.e46m3.launcher.BuildConfig
import app.tsunagi.e46m3.launcher.R

/**
 * Fires once, immediately after this package has been replaced.
 *
 * ## What it is for
 *
 * To find out, at the earliest possible moment, whether the unit still has a
 * home screen — and if it does not, to say so somewhere that survives this
 * screen not being visible.
 *
 * The preferred-activity record should survive a same-signature in-place
 * replace; AOSP keeps it as long as the component and its intent filter still
 * match, which is why `HomeActivity` may never be renamed and its HOME filter
 * may never change, and why `tools/release.sh` GATE-1 refuses to publish an APK
 * that has lost either. This receiver exists because "should" is not "did", and
 * the cost of being wrong is a car with no interface.
 *
 * ## Why a manifest receiver, when the app has none
 *
 * This is the app's first, and it has to be: the moment worth observing is the
 * one where the process has just been killed and recreated, and a runtime
 * registration does not exist then. `MY_PACKAGE_REPLACED` is on API 26's
 * implicit-broadcast exception list, so the O background limits do not suppress
 * it.
 *
 * It does three cheap things and stops. No network, no download, no install —
 * a receiver has ten seconds and this one is running at the busiest moment in
 * the device's day.
 */
class OtaReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val verdict = HomeGuard.check(context)
        Log.i(
            Ota.TAG,
            "replaced -> versionCode=${BuildConfig.VERSION_CODE} " +
                "isHome=${verdict.isHome} preferred=${verdict.preferred}",
        )
        if (verdict.isHome) {
            runCatching {
                notificationManager(context)?.cancel(NOTIFICATION_ID)
            }
            return
        }

        // A distinctive tag, so tools/deploy.sh can grep the log for it without
        // reading the whole ring.
        Log.e(
            Ota.TAG,
            "$LOST_HOME_TAG — recover with: ${HomeGuard.recoveryCommand(context.packageName)}",
        )
        notifyLostHome(context)
    }

    /**
     * The message has to outlive this receiver and this screen.
     *
     * If HOME is gone the launcher may never be visible again, so the banner it
     * would otherwise draw is not reachable. A notification is — the status bar
     * belongs to the system, and `FallbackHome` shows it.
     */
    private fun notifyLostHome(context: Context) {
        val manager = notificationManager(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.ota_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.ota_channel_desc) }
            )
        }

        val command = HomeGuard.recoveryCommand(context.packageName)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                builder
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(R.string.ota_lost_home_title))
                    .setContentText(command)
                    // The command is longer than one line and it is the only
                    // thing on this notification that matters.
                    .setStyle(Notification.BigTextStyle().bigText(command))
                    .setOngoing(true)
                    .build()
            )
        }.onFailure { Log.e(Ota.TAG, "could not post the lost-HOME notification", it) }
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        /** Greppable from `adb logcat`. Deliberately not a sentence. */
        const val LOST_HOME_TAG = "OTA_LOST_HOME"

        private const val CHANNEL = "ota"
        private const val NOTIFICATION_ID = 0x0747
    }
}
