package app.tsunagi.e46m3.launcher.ota

import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Asks a `PackageInstaller` session to accept a lower `versionCode`.
 *
 * ## Why this is in its own file
 *
 * So it can be deleted in one commit. Everything here is reflection into hidden
 * platform internals, and whether it works on this vendor build is `[U]` — see
 * docs/07 §13. If the on-car test says no, this file goes and
 * `rollbackSupported` becomes false, and nothing else changes.
 *
 * ## Why reflection is acceptable here in particular
 *
 * The non-SDK interface restrictions arrived in API 28. This unit is API 27, so
 * `@hide` members are ordinarily reachable — the greylist/blacklist machinery
 * that would block this on anything newer simply is not present. On a newer
 * platform every call below fails and returns false, which is the intended
 * degradation rather than a crash.
 *
 * ## What is NOT relied on
 *
 * Rollback does not depend on this working. The primary route is to publish the
 * older source as a **higher** versionCode (`tools/release.sh --bump patch`),
 * which keeps versionCode monotonic and travels the ordinary, proven update
 * path. This is the fast local alternative, and `adb install -r -d` is the
 * escape hatch behind both.
 */
internal object Downgrade {

    /** `PackageManager.INSTALL_ALLOW_DOWNGRADE`, @hide and stable since Lollipop. */
    private const val INSTALL_ALLOW_DOWNGRADE = 0x00000080

    /**
     * Marks [params] as permitting a downgrade.
     *
     * @return false when neither route was available. The caller must then NOT
     *         commit the session: it would fail with
     *         `INSTALL_FAILED_VERSION_DOWNGRADE` after doing all the work,
     *         which on a home-screen app is the most expensive possible way to
     *         find out.
     */
    fun allow(params: PackageInstaller.SessionParams): Boolean {
        // The documented-in-AOSP method first. Present on O; @SystemApi rather
        // than fully public, hence the reflection.
        runCatching {
            PackageInstaller.SessionParams::class.java
                .getMethod("setAllowDowngrade", Boolean::class.javaPrimitiveType)
                .invoke(params, true)
            Log.i(Ota.TAG, "downgrade allowed via setAllowDowngrade")
            return true
        }

        // The field the method writes to. Reached directly when the method is
        // absent — some vendor trees ship one without the other.
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getField("installFlags")
            field.isAccessible = true
            field.setInt(params, field.getInt(params) or INSTALL_ALLOW_DOWNGRADE)
            Log.i(Ota.TAG, "downgrade allowed via installFlags")
            return true
        }

        Log.w(
            Ota.TAG,
            "neither setAllowDowngrade nor installFlags is reachable — " +
                "a downgrade would be refused by the package manager",
        )
        return false
    }

    /**
     * Whether a local rollback can even be offered.
     *
     * Probed against a throwaway `SessionParams`, so asking costs nothing and
     * changes nothing. This is what keeps a ROLLBACK button off the pane on a
     * device where pressing it could only fail — the console does not show
     * controls that cannot act.
     */
    fun isSupported(): Boolean = runCatching {
        allow(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
    }.getOrDefault(false)
}
