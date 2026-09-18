package app.tsunagi.e46m3.launcher.launch

import android.content.Context
import android.os.SystemClock

/**
 * Remembers that a web tool was on screen when the unit lost power, so the next
 * boot can put it back.
 *
 * ## The problem this exists for
 *
 * Finish a datalog, turn the key off, turn it on again — and the head unit cold
 * boots. Chrome dies with it, and the tool comes back as a fresh page with no
 * idea what was on screen a moment ago. The recorded data survives (it is in
 * the tool's own IndexedDB), so nothing is lost; what is lost is the place. The
 * way back is a recovery flow, which is the right thing to have and the wrong
 * thing to need every time.
 *
 * A web app cannot fix the first half of that by itself: once the process is
 * gone it cannot ask to be started again. The launcher is the only thing on
 * this unit that runs at boot by definition — it is HOME — so it is the only
 * thing that can reopen the tool. What happens *inside* the tool after that is
 * the tool's business; see `docs/05-tuner-resume-spec.md` for the other half.
 *
 * ## Why a boot window, and not just a flag
 *
 * A flag alone says "the tool was in front when we stopped running", and that
 * is true of two very different events:
 *
 *  1. the unit lost power — reopening is exactly right
 *  2. **this launcher was killed for memory while the tool was in front** —
 *     reopening is exactly wrong, because the next thing that starts this
 *     process is the user pressing HOME to *leave* the tool, and they would be
 *     thrown straight back into it
 *
 * The second one is not hypothetical on a unit with 1.1GB free and a 192MB heap
 * limit; HOME apps get killed. The two are told apart by how long the system
 * has been up: a boot puts this launcher on screen within the first minute or
 * so, and case 2 can happen at any point after that.
 */
class ResumeAfterRestart(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called when a web tool has actually been started. */
    fun remember(targetId: String) {
        prefs.edit().putString(KEY_TARGET, targetId).apply()
    }

    /**
     * Called whenever this console is visible, which means the tool is not.
     *
     * Deliberately generous: any moment the owner is looking at the launcher is
     * a moment they are not mid-session in the tool, so there is nothing to
     * return to.
     */
    fun forget() {
        if (prefs.contains(KEY_TARGET)) prefs.edit().remove(KEY_TARGET).apply()
    }

    /**
     * The target to reopen, or null.
     *
     * **Consumes the record either way.** A resume that crashes the tool must
     * not be able to happen twice: the second key cycle has to arrive at a
     * working console, not at the same broken screen. That is also why this is
     * called before anything is launched rather than after it succeeds.
     */
    fun consume(): String? {
        val target = prefs.getString(KEY_TARGET, null) ?: return null
        prefs.edit().remove(KEY_TARGET).apply()
        return if (SystemClock.elapsedRealtime() <= BOOT_WINDOW_MS) target else null
    }

    private companion object {
        const val PREFS = "resume_after_restart"
        const val KEY_TARGET = "target"

        /**
         * How long after a boot this still counts as "the unit restarted under
         * the tool".
         *
         * Three minutes, which is loose on purpose. This unit boots slowly — a
         * load average above 30 for the first minute is normal — and the cost
         * of the window being too long is that a memory kill within three
         * minutes of boot could reopen the tool once. The cost of it being too
         * short is that the feature silently does nothing, which is the failure
         * nobody would report.
         */
        const val BOOT_WINDOW_MS = 180_000L
    }
}
