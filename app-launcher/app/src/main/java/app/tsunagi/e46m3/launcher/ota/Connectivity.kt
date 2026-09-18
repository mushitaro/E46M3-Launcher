package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Whether there is a link worth spending a connection attempt on.
 *
 * ## The trap this is built around
 *
 * `NET_CAPABILITY_VALIDATED` is the right signal: it means the platform reached
 * the internet, so a captive portal does not look like connectivity. But it is
 * only set after the portal probe against `connectivitycheck.gstatic.com`
 * succeeds, and on a vendor MediaTek build that probe may be disabled, patched
 * or blocked outright.
 *
 * If that is the case here, requiring VALIDATED means the OTA check **never
 * runs on a perfectly good garage WiFi**, while every log line reads "waiting
 * for a validated network". That is the same failure shape as docs/04 §10.5: a
 * component reporting a sensible-sounding state forever while doing nothing.
 *
 * So VALIDATED is preferred, not required. After [UNVALIDATED_PATIENCE]
 * observations of "connected, never validated", the check goes ahead anyway and
 * records which path it took. The worst case is one wasted request against a
 * captive portal, whose response fails the signature check like any other
 * garbage.
 *
 * ## Why the streak is passed in rather than held here
 *
 * It has to survive a reboot. The throttle allows roughly one check per boot
 * and this unit cold-boots on every key cycle, so a counter living in this
 * object would reset before it ever reached three — and the patience fallback,
 * whose entire job is to rescue us from a silent stall, would itself be a
 * silent stall. It is persisted by [OtaState] instead.
 *
 * `adb shell dumpsys connectivity | grep -i valid` on the car settles which
 * world this is, and that is worth doing once.
 */
internal class Connectivity(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    sealed class State {
        /** No usable transport at all. Nothing to do but wait. */
        data object Offline : State()

        /** Connected, but the platform has not confirmed reachability. Try later. */
        data class Unvalidated(val streak: Int) : State()

        data class Usable(val metered: Boolean, val validated: Boolean) : State()
    }

    /**
     * @param priorUnvalidatedStreak how many consecutive earlier checks saw a
     *        connected-but-unvalidated network, across reboots.
     */
    fun assess(priorUnvalidatedStreak: Int): State {
        val manager = cm ?: return State.Offline

        // getActiveNetwork/getNetworkCapabilities are API 23. minSdk is 21 only
        // because nothing forced it higher; the target unit is 27. Below 23 the
        // OTA subsystem simply does not run, which is honest — there is no
        // device in this fleet it would apply to.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.i(Ota.TAG, "OTA needs API 23+; this is ${Build.VERSION.SDK_INT}")
            return State.Offline
        }

        val network = manager.activeNetwork ?: return State.Offline
        val caps = manager.getNetworkCapabilities(network) ?: return State.Offline
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return State.Offline

        val metered = manager.isActiveNetworkMetered

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return State.Usable(metered = metered, validated = true)
        }

        val streak = priorUnvalidatedStreak + 1
        if (streak >= UNVALIDATED_PATIENCE) {
            Log.i(
                Ota.TAG,
                "connected but never validated, $streak checks running — trying anyway. " +
                    "If this is the normal state here, the captive-portal probe is " +
                    "probably disabled on this build.",
            )
            return State.Usable(metered = metered, validated = false)
        }
        return State.Unvalidated(streak)
    }

    private companion object {
        /**
         * Three checks — which, at roughly one check per boot, means it spans
         * more than one drive before giving up on waiting for validation. Long
         * enough that a genuinely slow probe gets its chance; short enough that
         * a device whose probe never fires is not stuck for a season.
         */
        const val UNVALIDATED_PATIENCE = 3
    }
}
