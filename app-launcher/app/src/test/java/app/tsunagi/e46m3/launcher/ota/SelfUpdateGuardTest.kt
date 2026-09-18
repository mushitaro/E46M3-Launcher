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
 * seat. When two conditions hold at once — the M console is open AND the cable
 * is in — the message has to name the one they are most likely to act on, and
 * that is a property of the ordering rather than of any single branch.
 *
 * These branches are otherwise almost untestable: reproducing "engine running,
 * cable in, resume armed" means a car, a laptop and a cold morning.
 */
class SelfUpdateGuardTest {

    /** Nothing happening: home screen up, nothing plugged in, nothing running. */
    private fun idle() = ConsoleState(
        resumed = true,
        mConsoleOpen = false,
        resumeArmed = false,
        appListOpen = false,
        cableAttached = false,
        ds2Live = false,
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
    fun `a plugged in diagnostic cable blocks it`() {
        // The blunt rule: a cable in the port means somebody is working on the
        // car, whether or not a session happens to be live this second.
        assertEquals(Block.CABLE, SelfUpdateGuard.check(idle().copy(cableAttached = true)))
    }

    @Test
    fun `a live DS2 link blocks it even with no cable flag`() {
        // Belt and braces: UsbWatch and Ds2Link answer different questions, and
        // a cable that enumerated oddly must not open a hole here.
        assertEquals(Block.DS2_LIVE, SelfUpdateGuard.check(idle().copy(ds2Live = true)))
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
        // Cable in, engine running, M console up. The owner can close the M
        // console in one tap; stopping the engine is a bigger ask, and pulling
        // the cable is a bigger one still. So the message names the console.
        val busy = idle().copy(mConsoleOpen = true, cableAttached = true, rpm = 3000)
        assertEquals(Block.M_CONSOLE, SelfUpdateGuard.check(busy))

        // With the console closed, the cable outranks the engine for the same
        // reason: it is the one that means somebody is mid-task.
        assertEquals(Block.CABLE, SelfUpdateGuard.check(busy.copy(mConsoleOpen = false)))

        // And with neither, the engine is what is left.
        assertEquals(
            Block.ENGINE_RUNNING,
            SelfUpdateGuard.check(busy.copy(mConsoleOpen = false, cableAttached = false)),
        )
    }

    @Test
    fun `being off screen outranks everything, including a running engine`() {
        // If this screen is not in front, the system install dialog would appear
        // over whatever is — and that is a worse surprise than any of the rest.
        val worst = idle().copy(
            resumed = false,
            mConsoleOpen = true,
            cableAttached = true,
            ds2Live = true,
            rpm = 4000,
        )
        assertEquals(Block.NOT_RESUMED, SelfUpdateGuard.check(worst))
    }
}
