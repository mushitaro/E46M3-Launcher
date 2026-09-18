package app.tsunagi.e46m3.launcher.ota

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Answers one question, and it is the most important question in this
 * subsystem: **are we still the home screen?**
 *
 * ## Why it is not `resolveActivity`
 *
 * `docs/01` §4.3 records that `cmd package resolve-activity`, run immediately
 * after `set-home-activity`, still returned the old value. That observation is
 * about the shell command rather than about the in-process API, but the
 * conclusion it led to — check the table, not the resolver — is the right one
 * regardless, because the table is the thing `set-home-activity` actually wrote
 * and the thing `dumpsys package | grep 'Preferred Activities'` actually prints.
 *
 * `PackageManager.getPreferredActivities` reads exactly that table. It is the
 * in-process equivalent of the check `tools/deploy.sh` performs over ADB, which
 * means the launcher and the deploy script agree on what "still HOME" means.
 *
 * A second, independent reading comes from the hidden
 * `PackageManager.getHomeActivities`, reached by reflection — legal and
 * unrestricted here, because the non-SDK interface restrictions begin at API 28
 * and this unit is 27. It is used as a cross-check rather than as the answer:
 * the two disagreeing is itself worth a log line, because it would mean the
 * platform's own two views of HOME have diverged.
 *
 * ## What happens when the answer is no
 *
 * Nothing this class can do. Without device-owner privileges the HOME
 * preference cannot be set programmatically, so recovery is an ADB command —
 * and the launcher's job is to make sure that command is on screen, in a
 * notification, and in the log, rather than to pretend it can fix itself.
 */
internal object HomeGuard {

    const val HOME_ACTIVITY = "app.tsunagi.e46m3.launcher.HomeActivity"

    /**
     * The exact line to recover with.
     *
     * Kept next to the check it belongs to, and deliberately spelled out rather
     * than assembled from parts at the call site: somebody will be reading this
     * off a car dashboard and typing it into a terminal.
     */
    fun recoveryCommand(packageName: String): String =
        "adb shell cmd package set-home-activity $packageName/.HomeActivity"

    data class Verdict(
        val isHome: Boolean,
        /** What the platform's own hidden view says, when it could be read. */
        val platformSaysHome: Boolean?,
        /** The component currently preferred for HOME, when it could be read. */
        val preferred: ComponentName?,
    )

    fun check(context: Context): Verdict {
        val pm = context.packageManager
        val wanted = ComponentName(context.packageName, HOME_ACTIVITY)

        // The table dumpsys prints and set-home-activity writes.
        //
        // Deprecated upstream, and used anyway: the deprecation belongs to
        // platforms where preferred activities are no longer how HOME is
        // decided. On API 27 this IS how HOME is decided, and it is the same
        // table tools/deploy.sh greps out of dumpsys — so the launcher and the
        // deploy script give the same answer to the same question.
        @Suppress("DEPRECATION")
        val fromTable = try {
            val filters = ArrayList<IntentFilter>()
            val components = ArrayList<ComponentName>()
            pm.getPreferredActivities(filters, components, context.packageName)
            components.indices.any { i ->
                components[i] == wanted && filters[i].hasCategory(Intent.CATEGORY_HOME)
            }
        } catch (e: Exception) {
            Log.w(Ota.TAG, "could not read preferred activities", e)
            false
        }

        val platform = platformHome(pm)
        val fromPlatform = platform?.let { it == wanted }

        if (fromPlatform != null && fromPlatform != fromTable) {
            // Not fatal, but never silent: the two things that decide which app
            // receives the HOME intent do not agree, and that is worth knowing
            // before somebody spends an evening on it.
            Log.w(
                Ota.TAG,
                "HOME disagreement — preferred-activity table says $fromTable, " +
                    "platform says $platform",
            )
        }

        return Verdict(
            // Either reading being positive is taken as "we are home". The
            // consequence of a false negative is a scary banner on a working
            // unit; of a false positive, no banner on a broken one. Neither is
            // good, but the table is the authority and the platform view is
            // corroboration, so a disagreement resolves to "probably fine, and
            // logged" rather than "alarm".
            isHome = fromTable || fromPlatform == true,
            platformSaysHome = fromPlatform,
            preferred = platform,
        )
    }

    /**
     * `PackageManager.getHomeActivities(List<ResolveInfo>)` — public-hidden,
     * present since API 19, returns the currently preferred home component.
     *
     * Reflection is safe here in a way it would not be on a newer platform:
     * the non-SDK interface restrictions arrived in API 28 and this device is
     * 27. On anything newer this simply returns null and the table stands
     * alone, which is the intended degradation.
     */
    private fun platformHome(pm: PackageManager): ComponentName? = try {
        val method = PackageManager::class.java
            .getMethod("getHomeActivities", MutableList::class.java)
        @Suppress("UNCHECKED_CAST")
        method.invoke(pm, ArrayList<ResolveInfo>()) as? ComponentName
    } catch (e: Throwable) {
        Log.i(Ota.TAG, "getHomeActivities is not reachable here (${e.javaClass.simpleName})")
        null
    }
}
