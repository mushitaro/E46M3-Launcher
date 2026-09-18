package app.tsunagi.e46m3.launcher.ota

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Handing HOME back to the OEM launcher, so this one can be removed.
 *
 * ## Why this is a gated procedure and not a button
 *
 * Uninstalling the home screen while it is still the home screen leaves the
 * unit on `com.android.settings/.FallbackHome`, and getting out of that needs
 * the car powered, the right WiFi, and ADB (docs/04 §12). So the removal is
 * refused until the platform has been observed to prefer somebody else — not
 * until we have asked it to, until it says it does.
 *
 * ## The one thing that does not need a permission
 *
 * `PackageManager.clearPackagePreferredActivities` normally requires
 * `SET_PREFERRED_APPLICATIONS`, which is a system permission — but
 * `PackageManagerService` short-circuits that check when the package being
 * cleared is the caller's own:
 *
 * ```java
 * if (pkg == null || pkg.applicationInfo.uid != uid) { ...enforce... }
 * ```
 *
 * An app may therefore always give up its own preferred-activity records, and
 * that record is exactly the one `cmd package set-home-activity` wrote. What a
 * device-owner privilege would buy is silently *choosing* the replacement
 * without a chooser — not the ability to let go.
 *
 * Every step here is `[U]` on this vendor build. They are meant to be rehearsed
 * with ADB connected and the recovery line already typed into another terminal,
 * exactly as docs/04 §12 says to rehearse a rollback before needing one.
 */
internal object Relinquish {

    /**
     * The launcher this unit shipped with —
     * `/system/priv-app/Launcher/8227LTsLauncher2_xrc04_2_81.apk`, per
     * docs/01 §4. It is the only other HOME candidate on the device apart from
     * `FallbackHome`, whose priority is -1000.
     */
    const val OEM_PACKAGE = "com.android.launcher"
    const val OEM_ACTIVITY = "com.android.launcher2.Launcher"

    sealed class Step {
        /** Our own HOME preference is gone. */
        data object Cleared : Step()

        /** The platform now prefers the OEM launcher. Removal may be offered. */
        data object Handed : Step()

        data class Failed(val why: String) : Step()
    }

    /**
     * Drops this app's preferred-activity records.
     *
     * After this the only HOME candidates left are the OEM launcher and
     * `FallbackHome`, so the next HOME intent raises the system chooser.
     */
    fun clearOurPreference(context: Context): Step = try {
        @Suppress("DEPRECATION")  // see the class docs: on API 27 this IS the mechanism
        context.packageManager.clearPackagePreferredActivities(context.packageName)
        Log.i(Ota.TAG, "cleared our own preferred-activity records")
        Step.Cleared
    } catch (e: Exception) {
        Log.e(Ota.TAG, "could not clear our preferred activities", e)
        Step.Failed("CLEAR_${e.javaClass.simpleName}")
    }

    /**
     * The screen on which another launcher can be chosen.
     *
     * `ACTION_HOME_SETTINGS` when MtkSettings carries it; otherwise a plain HOME
     * intent, which with no preference set raises the resolver's own chooser.
     * The second route is the one that cannot be missing, because it is the
     * system's default behaviour rather than a screen somebody has to have
     * shipped.
     */
    fun picker(context: Context): Intent {
        val settings = Intent(Settings.ACTION_HOME_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (settings.resolveActivity(context.packageManager) != null) return settings

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Both halves, and both are required.
     *
     * "We are not preferred" alone is not enough: that state also describes a
     * unit with no home screen at all. The OEM launcher having actually taken
     * over is what makes removal safe, so it is checked rather than assumed.
     */
    fun verify(context: Context): Step {
        val verdict = HomeGuard.check(context)
        if (verdict.isHome) return Step.Failed("STILL_HOME")

        val preferred = verdict.preferred
        // The class is fully qualified and its package is NOT the app's: the
        // OEM ships com.android.launcher2.Launcher inside com.android.launcher.
        val oem = ComponentName(OEM_PACKAGE, OEM_ACTIVITY)

        // The platform view may be unreadable on a build where the hidden method
        // is gone. Fall back to asking whether the OEM launcher is even present
        // and enabled, which is weaker but is not nothing.
        if (preferred == null) {
            val present = runCatching {
                context.packageManager.getPackageInfo(OEM_PACKAGE, 0) != null
            }.getOrDefault(false)
            return if (present) Step.Handed else Step.Failed("NO_OTHER_HOME")
        }

        return if (preferred.packageName == OEM_PACKAGE || preferred == oem) {
            Step.Handed
        } else {
            Log.w(Ota.TAG, "HOME went to $preferred, not $OEM_PACKAGE")
            Step.Failed("UNEXPECTED_HOME_${preferred.packageName}")
        }
    }

    /**
     * What to type when the flow cannot finish.
     *
     * Two lines, in the order they have to be run. Kept together because the
     * second without the first is the failure this whole procedure exists to
     * prevent.
     */
    fun recoveryCommands(packageName: String): List<String> = listOf(
        "adb shell cmd package set-home-activity $OEM_PACKAGE/$OEM_ACTIVITY",
        "adb uninstall $packageName",
    )

    /** Is the OEM launcher even installed? If not, nothing here should start. */
    fun oemLauncherPresent(pm: PackageManager): Boolean = runCatching {
        pm.getPackageInfo(OEM_PACKAGE, 0) != null
    }.getOrDefault(false)
}
