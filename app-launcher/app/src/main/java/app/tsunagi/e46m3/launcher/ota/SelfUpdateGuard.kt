package app.tsunagi.e46m3.launcher.ota

/**
 * Whether now is a moment at which this launcher may replace itself.
 *
 * ## Why a self-update needs a gate at all
 *
 * Replacing this package kills the process with SIGKILL and restarts the home
 * screen. That is survivable, and mostly unremarkable — unless it happens while
 * something is depending on the console still being there. The list below is
 * that "unless", and each entry earns its place by describing a moment when a
 * restart would cost the owner something real rather than a second of black.
 *
 * ## Pure, and therefore proven
 *
 * Takes a snapshot of console state and returns the first reason to refuse. No
 * Android, no fields, no clock — so `SelfUpdateGuardTest` can walk every branch
 * without a car, which is the only way most of these will ever be exercised.
 */
internal object SelfUpdateGuard {

    fun check(state: ConsoleState): Block? = when {
        // Not on screen: whatever is in front of us did not ask for this, and a
        // system install dialog appearing over it would be a surprise at best.
        !state.resumed -> Block.NOT_RESUMED

        // The gauge is up. Somebody is watching the engine, which is the one
        // thing on this unit that is worth watching in real time.
        state.mConsoleOpen -> Block.M_CONSOLE

        // A tool is about to be reopened after a power cycle. Restarting now
        // would cancel it in a way that looks like the countdown lied.
        state.resumeArmed -> Block.RESUME_ARMED

        state.appListOpen -> Block.APP_LIST

        // No rule for the K+DCAN cable, and none for this screen's own DS2
        // poll. Both were here until 2026-10-29: "a cable in the port means
        // somebody is working on the car". On the car this runs in, the cable
        // never comes out — it feeds the tachometer — so the rule refused every
        // install, and the poll it would have interrupted is this launcher's
        // own, which stops on pause anyway. A datalog runs in Chrome, a
        // different process that replacing this package does not touch. And
        // an install only ever starts from the owner's own press.

        // Engine running. Not because the install is unsafe, but because a car
        // that is running is a car somebody is about to drive.
        (state.rpm ?: 0) > 0 -> Block.ENGINE_RUNNING

        else -> null
    }
}

/**
 * The console's state at the moment an update was asked for.
 *
 * Every field is something [app.tsunagi.e46m3.launcher.HomeActivity] already
 * knows. Nothing here required a new signal to be added to the vehicle link.
 */
internal data class ConsoleState(
    val resumed: Boolean,
    val mConsoleOpen: Boolean,
    val resumeArmed: Boolean,
    val appListOpen: Boolean,
    /** Null when the DME has said nothing; 0 when it has said "not running". */
    val rpm: Int?,
)

/** Each value is its own message. A refusal nobody can diagnose is a bug report. */
internal enum class Block {
    NOT_RESUMED,
    M_CONSOLE,
    RESUME_ARMED,
    APP_LIST,
    ENGINE_RUNNING,
}
