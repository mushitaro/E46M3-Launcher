package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.os.SystemClock

/**
 * Everything the OTA subsystem has to remember between runs.
 *
 * Plain `SharedPreferences`, like `launch_targets` and `resume_after_restart`
 * already in this app. No database, no serialisation format: half a dozen
 * scalars, and the one thing that matters about them is that they survive a
 * process that is about to be killed by a package replace.
 *
 * ## No wall-clock arithmetic lives here
 *
 * Nothing in this class compares `System.currentTimeMillis()` values. The RTC is
 * the value this whole subsystem is built not to trust (see [OtaClock]), so the
 * throttle runs on [SystemClock.elapsedRealtime], which is monotonic within a
 * boot and resets at key-off. That makes the throttle mean "once per boot, at
 * least [Ota.CHECK_INTERVAL_MS] apart", reached by a route a bad clock cannot
 * break.
 */
internal class OtaState(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True once per process. The first check of a boot should happen even if a
     * previous boot's elapsed reading happened to be large.
     */
    var checkedThisProcess: Boolean = false
        private set

    // ── Throttle ────────────────────────────────────────────────────────────

    fun isCheckDue(): Boolean {
        if (!checkedThisProcess) return true
        val last = prefs.getLong(KEY_LAST_CHECK_ELAPSED, Long.MIN_VALUE)
        val now = SystemClock.elapsedRealtime()
        // A `now` below `last` means the device rebooted since that write, which
        // is also a reason to check rather than a reason not to.
        if (now < last) return true
        return now - last >= Ota.CHECK_INTERVAL_MS
    }

    fun noteCheckStarted() {
        checkedThisProcess = true
        prefs.edit().putLong(KEY_LAST_CHECK_ELAPSED, SystemClock.elapsedRealtime()).apply()
    }

    /**
     * Milliseconds since the last check *in this boot*, or null.
     *
     * Deliberately not "since the last check, ever". The pane says "SINCE BOOT"
     * because that is what this number is, and printing a plausible-looking
     * interval that is actually measuring something else is the kind of
     * fabricated state docs/04 §9 rules out.
     */
    fun sinceLastCheckMs(): Long? {
        val last = prefs.getLong(KEY_LAST_CHECK_ELAPSED, Long.MIN_VALUE)
        if (last == Long.MIN_VALUE) return null
        val now = SystemClock.elapsedRealtime()
        return if (now >= last) now - last else null
    }

    // ── Replay protection ───────────────────────────────────────────────────

    /**
     * The highest manifest serial ever accepted.
     *
     * Only ever moves up. Withdrawing a release means publishing a higher serial
     * that omits it; lowering one would be ignored by exactly the devices the
     * withdrawal is for.
     */
    var maxSerialSeen: Int
        get() = prefs.getInt(KEY_MAX_SERIAL, 0)
        set(value) {
            if (value > maxSerialSeen) prefs.edit().putInt(KEY_MAX_SERIAL, value).apply()
        }

    // ── Connectivity patience ───────────────────────────────────────────────

    /** See [Connectivity]: this has to outlive a reboot to ever reach its limit. */
    var unvalidatedStreak: Int
        get() = prefs.getInt(KEY_UNVALIDATED_STREAK, 0)
        set(value) = prefs.edit().putInt(KEY_UNVALIDATED_STREAK, value).apply()

    // ── The self-update record ──────────────────────────────────────────────

    /**
     * What happened to the version we last tried to install.
     *
     * A self-update kills this process, so the only place its outcome can be
     * observed is the next launch. That makes this record the whole feedback
     * loop, and it has two properties it cannot lose:
     *
     * **It is written with `commit()`, not `apply()`.** A package replace is a
     * SIGKILL; `apply()`'s background flush through `QueuedWork` never runs, and
     * the record would simply not exist.
     *
     * **It is consumed before it is acted on.** The same discipline
     * `ResumeAfterRestart.consume()` uses, for the same reason: an update that
     * crashes on first run must not be able to do so twice. After
     * [ATTEMPT_LIMIT] attempts a versionCode is poisoned and never offered
     * again without somebody deliberately clearing it.
     */
    sealed class InstallOutcome {
        data object None : InstallOutcome()
        data class Succeeded(val versionCode: Int) : InstallOutcome()
        data class Failed(val versionCode: Int, val attempts: Int, val poisoned: Boolean) :
            InstallOutcome()
    }

    /**
     * Records the attempt. Call this IMMEDIATELY before committing the session —
     * after it, this process may not get another instruction.
     */
    fun beginInstall(versionCode: Int) {
        val attempts = attempts(versionCode) + 1
        prefs.edit()
            .putInt(KEY_PENDING, versionCode)
            .putInt(keyAttempts(versionCode), attempts)
            .commit()
    }

    /**
     * Reads and clears the record. Called once, early, on every launch.
     *
     * @param currentVersionCode what is actually running now. If it matches what
     *        we were installing, the install worked.
     */
    fun consumeInstallOutcome(currentVersionCode: Int): InstallOutcome {
        val pending = prefs.getInt(KEY_PENDING, 0)
        if (pending == 0) return InstallOutcome.None

        // Cleared first, whatever the verdict.
        prefs.edit().remove(KEY_PENDING).commit()

        if (pending == currentVersionCode) {
            prefs.edit().remove(keyAttempts(pending)).apply()
            return InstallOutcome.Succeeded(pending)
        }

        val attempts = attempts(pending)
        val poisoned = attempts >= ATTEMPT_LIMIT
        if (poisoned) prefs.edit().putBoolean(keyPoison(pending), true).apply()
        return InstallOutcome.Failed(pending, attempts, poisoned)
    }

    /**
     * Two failed attempts at the same versionCode means a build that does not
     * run on this unit. Offering it a third time would be a loop, and the loop
     * would be on the car's home screen.
     */
    fun isPoisoned(versionCode: Int): Boolean =
        prefs.getBoolean(keyPoison(versionCode), false)

    fun attempts(versionCode: Int): Int = prefs.getInt(keyAttempts(versionCode), 0)

    /** For the deliberate override: the owner insisting on a poisoned version. */
    fun clearPoison(versionCode: Int) {
        prefs.edit()
            .remove(keyPoison(versionCode))
            .remove(keyAttempts(versionCode))
            .apply()
    }

    private fun keyAttempts(versionCode: Int) = "attempts_$versionCode"
    private fun keyPoison(versionCode: Int) = "poisoned_$versionCode"

    private companion object {
        const val PREFS = "ota"
        const val KEY_LAST_CHECK_ELAPSED = "last_check_elapsed"
        const val KEY_MAX_SERIAL = "max_serial"
        const val KEY_UNVALIDATED_STREAK = "unvalidated_streak"
        const val KEY_PENDING = "pending_version"

        /**
         * Two. One failure can be a power cut at the wrong moment; two is the
         * build.
         */
        const val ATTEMPT_LIMIT = 2
    }
}
