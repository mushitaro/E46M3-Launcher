package app.tsunagi.e46m3.launcher.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Installs through `PackageInstaller`, with the system asking the owner to
 * confirm.
 *
 * ## No FileProvider, no content:// URI
 *
 * The obvious route — `ACTION_INSTALL_PACKAGE` with a `content://` URI — needs a
 * `<provider>` in the manifest, a paths XML, and a grant on every intent.
 * Writing the bytes straight into a session avoids that entire apparatus, works
 * the same on API 27 as on anything newer, and gives a status callback the
 * intent route does not.
 *
 * ## The one-time app-op
 *
 * `REQUEST_INSTALL_PACKAGES` is declared in the manifest, but on API 26+ it is
 * backed by an app-op that starts un-granted. Until it is allowed,
 * `canRequestPackageInstalls()` is false and this installer reports itself
 * unavailable rather than committing a session that cannot complete. Granting it
 * once, over the channel that already exists:
 *
 * ```
 * adb shell appops set app.tsunagi.e46m3.launcher REQUEST_INSTALL_PACKAGES allow
 * ```
 *
 * or on the unit: Settings → Apps → Special access → Install unknown apps.
 * [unknownSourcesSettings] is the in-app shortcut to that screen, which is `[U]`
 * on this vendor build.
 *
 * ## Why the receiver is registered at runtime
 *
 * The status `IntentSender` only has to exist for the length of one session, and
 * a manifest receiver would be a permanently exported entry point for something
 * with no reason to be reachable from outside. The `PendingIntent` carries an
 * intent with an explicit package, so nothing else can deliver to it — the same
 * shape `Ds2Link` already uses for USB permission.
 */
internal class ConfirmInstaller(context: Context) : Installer {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val id: String get() = "confirm"

    override fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            app.packageManager.canRequestPackageInstalls()

    /** The Settings screen that grants the app-op, or null if it does not resolve. */
    fun unknownSourcesSettings(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // MtkSettings is a vendor build; this is not guaranteed to exist.
        return if (intent.resolveActivity(app.packageManager) != null) intent else null
    }

    override fun install(apk: File, onEvent: (InstallEvent) -> Unit) =
        install(apk, allowDowngrade = false, onEvent = onEvent)

    /**
     * @param allowDowngrade for a rollback to a kept earlier APK. Refused up
     *        front when the platform will not accept the flag, rather than
     *        committing a session that can only end in
     *        INSTALL_FAILED_VERSION_DOWNGRADE.
     */
    fun install(apk: File, allowDowngrade: Boolean, onEvent: (InstallEvent) -> Unit) {
        if (!isAvailable()) {
            post(onEvent, InstallEvent.Refused("APPOP"))
            return
        }
        if (!apk.isFile || apk.length() == 0L) {
            post(onEvent, InstallEvent.Refused("NO_APK"))
            return
        }

        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(apk.length())
        }

        if (allowDowngrade && !Downgrade.allow(params)) {
            post(onEvent, InstallEvent.Refused("NO_DOWNGRADE"))
            return
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            Log.e(Ota.TAG, "could not create an install session", e)
            post(onEvent, InstallEvent.Refused("SESSION_${e.javaClass.simpleName}"))
            return
        }

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(WRITE_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out, BUFFER) }
                    // Flushed to storage before the session is handed over. The
                    // ignition can cut at any point, and a session committed
                    // over a partially written file is a failure the system
                    // reports much later and much less clearly.
                    session.fsync(out)
                }
                session.commit(statusSender(sessionId, onEvent))
            }
        } catch (e: Exception) {
            Log.e(Ota.TAG, "install session $sessionId failed before commit", e)
            runCatching { installer.abandonSession(sessionId) }
            post(onEvent, InstallEvent.Refused("WRITE_${e.javaClass.simpleName}"))
            return
        }

        // Past this line the system owns it, and if this is our own package the
        // process can be killed at any moment. Nothing may be left to do here.
        post(onEvent, InstallEvent.Committed)
    }

    override fun uninstall(packageName: String, onEvent: (InstallEvent) -> Unit) {
        if (packageName == app.packageName) {
            // Structural, not advisory. Removing the home screen without first
            // handing HOME back leaves the unit on FallbackHome, and getting out
            // of that needs the car powered and the right WiFi.
            post(onEvent, InstallEvent.Refused("SELF_UNINSTALL_BLOCKED"))
            return
        }
        try {
            app.packageManager.packageInstaller.uninstall(
                packageName,
                statusSender(packageName.hashCode(), onEvent),
            )
        } catch (e: Exception) {
            Log.e(Ota.TAG, "could not request uninstall of $packageName", e)
            post(onEvent, InstallEvent.Refused("UNINSTALL_${e.javaClass.simpleName}"))
        }
    }

    /**
     * A one-shot receiver plus the `IntentSender` that feeds it.
     *
     * @param requestCode must differ per session, or two overlapping operations
     *        would share one `PendingIntent` and the second would silently
     *        replace the first's extras.
     */
    private fun statusSender(requestCode: Int, onEvent: (InstallEvent) -> Unit): android.content.IntentSender {
        val action = "${app.packageName}.OTA_INSTALL_STATUS.$requestCode"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")  // getParcelableExtra(String, Class) is API 33
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm == null) {
                            finish(this, onEvent, InstallEvent.Failed(status, "no confirmation intent"))
                            return
                        }
                        // Started from a receiver, so it needs its own task.
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { app.startActivity(confirm) }
                            .onSuccess { onEvent(InstallEvent.AwaitingUser) }
                            .onFailure {
                                Log.e(Ota.TAG, "could not show the install dialog", it)
                                finish(this, onEvent, InstallEvent.Failed(status, it.message))
                            }
                        // Deliberately still registered: the real outcome
                        // arrives on this same receiver once the dialog is
                        // answered.
                    }

                    PackageInstaller.STATUS_SUCCESS ->
                        finish(this, onEvent, InstallEvent.Succeeded)

                    else -> {
                        Log.w(Ota.TAG, "install status=$status message=$message")
                        finish(this, onEvent, InstallEvent.Failed(status, message))
                    }
                }
            }
        }

        app.registerReceiver(receiver, IntentFilter(action))

        val intent = Intent(action).setPackage(app.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // PackageInstaller fills in the extras, so this one has to stay
            // mutable. Required from API 31; harmless to omit below it, and the
            // constant does not exist there.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(app, requestCode, intent, flags).intentSender
    }

    /**
     * Unregisters and delivers, in that order.
     *
     * The callback is the one the receiver closed over, so there is no lookup
     * table to keep in step — a session's outcome cannot be delivered to the
     * wrong caller, and a receiver cannot outlive the callback it belongs to.
     */
    private fun finish(
        receiver: BroadcastReceiver,
        onEvent: (InstallEvent) -> Unit,
        event: InstallEvent,
    ) {
        runCatching { app.unregisterReceiver(receiver) }
        main.post { onEvent(event) }
    }

    private fun post(onEvent: (InstallEvent) -> Unit, event: InstallEvent) {
        main.post { onEvent(event) }
    }

    private companion object {
        const val WRITE_NAME = "ota.apk"
        const val BUFFER = 64 * 1024
    }
}
