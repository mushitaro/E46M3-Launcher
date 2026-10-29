package app.tsunagi.e46m3.launcher.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every reason this launcher refuses to replace itself, and the order they are
 * checked in.
 *
 * ## Why the order is tested and not just the set
 *
 * A refusal the owner cannot diagnose is a bug report filed from a driver's
 * seat. When two conditions hold at once — the M console is open AND the engine
 * is running — the message has to name the one they are most likely to act on, and
 * that is a property of the ordering rather than of any single branch.
 *
 * These branches are otherwise almost untestable: reproducing "engine running,
 * resume armed" means a car, a laptop and a cold morning.
 */
class SelfUpdateGuardTest {

    /** Nothing happening: home screen up, nothing plugged in, nothing running. */
    private fun idle() = ConsoleState(
        resumed = true,
        mConsoleOpen = false,
        resumeArmed = false,
        appListOpen = false,
        rpm = null,
    )

    @Test
    fun `an idle home screen allows an update`() {
        assertNull(SelfUpdateGuard.check(idle()))
    }

    @Test
    fun `not being on screen blocks it`() {
        assertEquals(Block.NOT_RESUMED, SelfUpdateGuard.check(idle().copy(resumed = false)))
    }

    @Test
    fun `the tachometer being up blocks it`() {
        // Somebody is watching the engine, which is the one thing on this unit
        // worth watching in real time.
        assertEquals(Block.M_CONSOLE, SelfUpdateGuard.check(idle().copy(mConsoleOpen = true)))
    }

    @Test
    fun `an armed resume countdown blocks it`() {
        assertEquals(Block.RESUME_ARMED, SelfUpdateGuard.check(idle().copy(resumeArmed = true)))
    }

    @Test
    fun `the app list being open blocks it`() {
        assertEquals(Block.APP_LIST, SelfUpdateGuard.check(idle().copy(appListOpen = true)))
    }

    @Test
    fun `a running engine blocks it`() {
        assertEquals(Block.ENGINE_RUNNING, SelfUpdateGuard.check(idle().copy(rpm = 820)))
    }

    @Test
    fun `a stopped engine does not block it`() {
        // The DME says 0 when the key is on and the engine is not running. That
        // is a perfectly good moment to update, and treating "we have a reading"
        // as "it is running" would block every update on a car with the cable
        // left in and the ignition on.
        assertNull(SelfUpdateGuard.check(idle().copy(rpm = 0)))
    }

    @Test
    fun `a silent DME does not block it`() {
        assertNull(SelfUpdateGuard.check(idle().copy(rpm = null)))
    }

    @Test
    fun `when several conditions hold, the most actionable one is named`() {
        // Engine running and M console up. The owner can close the M console in
        // one tap; stopping the engine is a bigger ask. So the message names
        // the console.
        val busy = idle().copy(mConsoleOpen = true, rpm = 3000)
        assertEquals(Block.M_CONSOLE, SelfUpdateGuard.check(busy))

        // With the console closed, the engine is what is left.
        assertEquals(Block.ENGINE_RUNNING, SelfUpdateGuard.check(busy.copy(mConsoleOpen = false)))
    }

    @Test
    fun `being off screen outranks everything, including a running engine`() {
        // If this screen is not in front, the system install dialog would appear
        // over whatever is — and that is a worse surprise than any of the rest.
        val worst = idle().copy(
            resumed = false,
            mConsoleOpen = true,
            rpm = 4000,
        )
        assertEquals(Block.NOT_RESUMED, SelfUpdateGuard.check(worst))
    }
}
