package app.tsunagi.e46m3.launcher.ota

import java.io.File

/**
 * How a verified APK becomes an installed package.
 *
 * There is one implementation today, [ConfirmInstaller], which streams the APK
 * into a `PackageInstaller` session and lets the system ask the owner to
 * confirm. A second one is possible and is written down here rather than built:
 * a **device-owner** installer would commit silently and could also set and
 * restore the HOME preference programmatically.
 *
 * That one is not built because it has a price this car has not agreed to pay.
 * On API 27 `dpm set-device-owner` refuses while any account exists on the
 * device, and this unit has a Google account on user 0 — so silent updates cost
 * the Play Store. The seam is here so that decision stays reversible; it is not
 * a stub pretending to be a feature.
 */
internal interface Installer {

    /** For logs and for the pane, so a failure names which path produced it. */
    val id: String

    /**
     * False when this installer cannot act right now — a missing permission, a
     * revoked app-op. The pane removes the button and prints the reason rather
     * than offering something that will fail.
     */
    fun isAvailable(): Boolean

    /**
     * Streams [apk] into a session and commits it.
     *
     * Everything about this is asynchronous and some of it is fatal to this
     * process: replacing our own package kills us with SIGKILL somewhere after
     * [InstallEvent.Committed], so **nothing may be left to do after the commit
     * returns**. Anything that must survive is written, with `commit()` rather
     * than `apply()`, before the session is committed.
     *
     * @param onEvent delivered on the main thread.
     */
    fun install(apk: File, onEvent: (InstallEvent) -> Unit)

    /**
     * Requests removal of [packageName].
     *
     * Implementations must refuse to uninstall this app itself through this
     * path: losing HOME strands the unit on `com.android.settings/.FallbackHome`
     * and recovery needs ADB with the car powered (docs/04 §12). Relinquishing
     * HOME is a separate, gated flow.
     */
    fun uninstall(packageName: String, onEvent: (InstallEvent) -> Unit)
}

internal sealed class InstallEvent {
    /**
     * The bytes are in a session and the session is committed. The system owns
     * it from here; for a self-update this process may stop existing at any
     * moment after this.
     */
    data object Committed : InstallEvent()

    /** The system's confirmation dialog is up. Nothing happens until it is answered. */
    data object AwaitingUser : InstallEvent()

    /**
     * Reported only for packages that are not us. A self-update succeeds by
     * killing this process, so its success is observed on the next launch —
     * see the pending-version record in [OtaState].
     */
    data object Succeeded : InstallEvent()

    /** Refused before anything was committed. Nothing changed on the device. */
    data class Refused(val reason: String) : InstallEvent()

    /** @param code a `PackageInstaller.STATUS_FAILURE_*` value. */
    data class Failed(val code: Int, val message: String?) : InstallEvent()
}
