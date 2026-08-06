package app.tsunagi.e46m3.launcher.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The home ⇄ M-mode transition: one console sinks into the fascia while the
 * other rises out of it, and the display window travels between them.
 *
 * ## How the recede is reproduced
 *
 * The design moves each key in Z — `transform: perspective(520px)
 * translateZ(-300px)` about the key's own centre — and dims it with
 * `filter: brightness(.05)`.
 *
 * A CSS perspective applied in the same transform uses the element's own
 * transform-origin as the vanishing point, so `translateZ(-300px)` there is
 * *exactly* a uniform scale about the centre by 520 / (520 + 300) = 0.6341.
 * That is [SINK_SCALE], and scaleX/scaleY are RenderNode properties: no
 * measure, no layout, no display-list re-record, which is the whole reason the
 * plan bans ChangeBounds and margin animation on this hardware.
 *
 * The dimming is a black overlay (see d_key_scrim.xml) — arithmetically
 * identical over an opaque key face, and likewise a pure RenderNode property.
 *
 * ## Timings
 *
 * Straight out of the design's applyVars(), which drives everything from one
 * flag. The rule that is easy to miss: **the set that is leaving snaps invisible
 * at 390ms and the set that is arriving snaps visible at 400ms** — opacity uses
 * a 0.01s transition, so it is a cut, not a fade, and the 10ms gap means the two
 * consoles are never on screen together. That is also why no z-order swap is
 * needed here even though the two sets overlap in three columns.
 *
 * One deliberate deviation: the design enables pointer events on the arriving
 * set immediately, which would leave keys tappable for 400ms while invisible.
 * Here interactivity flips with visibility.
 */
class ModeSwitch(
    private val window: WindowFrameView,
    private val clockLayer: View,
    private val tach: View,
    homeSet: List<Panel>,
    private val mSet: List<Panel>,
    /** The carbon blanks. They ride with the M set but never snap out. */
    plates: List<Panel>,
    private val homeGeometry: Geometry,
    private val mGeometry: Geometry,
) {
    /**
     * Mutable, because a key can be fitted or removed while the console is on
     * screen: the M key exists only for as long as the K+DCAN cable does. See
     * [fit]. Everything else about the console is fixed at construction.
     */
    private val homeSet = homeSet.toMutableList()
    private val plates = plates.toMutableList()

    /** A key face or a plate: the thing that moves, and its dimming veil. */
    class Panel(val view: View, val scrim: View, val staggerMs: Long)

    /** The display window's glass rectangle in one of the two modes. */
    data class Geometry(val x: Float, val y: Float, val w: Float, val h: Float)

    private val handler = Handler(Looper.getMainLooper())

    var isMOpen: Boolean = false
        private set

    /** No animation — used to establish the initial state before the first frame. */
    fun applyInstant(mOpen: Boolean) {
        isMOpen = mOpen
        handler.removeCallbacksAndMessages(null)

        for (p in homeSet) settle(p, raised = !mOpen, visible = !mOpen)
        for (p in mSet) settle(p, raised = mOpen, visible = mOpen)
        for (p in plates) settle(p, raised = mOpen, visible = true)

        parkWithheld()

        clockLayer.animate().cancel()
        tach.animate().cancel()
        clockLayer.alpha = if (mOpen) 0f else 1f
        tach.alpha = if (mOpen) 1f else 0f
        clockLayer.visibility = if (mOpen) View.INVISIBLE else View.VISIBLE
        tach.visibility = if (mOpen) View.VISIBLE else View.INVISIBLE
        val g = if (mOpen) mGeometry else homeGeometry
        window.setGeometry(g.x, g.y, g.w, g.h)
    }

    /**
     * A key that is not fitted, and the plate standing in for its socket.
     *
     * Both sit outside [homeSet] and [plates] and take no part in a mode
     * transition, so every state application has to park them by hand. Leaving
     * that to [fit]'s own animation was not enough: a mode change cancels
     * pending work, so a key caught mid-removal — unplug the cable, then press M
     * within 390ms — would have been left sunk but still visible, a shrunken
     * ghost sitting on the M console until something else reset it.
     */
    private class Withheld(val key: Panel, val blank: Panel)

    private var withheld: Withheld? = null

    private fun parkWithheld() {
        val w = withheld ?: return
        settle(w.key, raised = false, visible = false)
        settle(w.blank, raised = true, visible = true)
    }

    /** @return false if already in that state or mid-transition. */
    fun animateTo(mOpen: Boolean): Boolean {
        if (mOpen == isMOpen) return false
        isMOpen = mOpen
        handler.removeCallbacksAndMessages(null)

        val leaving = if (mOpen) homeSet else mSet
        val arriving = if (mOpen) mSet else homeSet

        // Leaving: sinks immediately. Arriving: waits out the sink, then rises.
        for (p in leaving) move(p, raised = false, baseDelay = 0L)
        for (p in arriving) move(p, raised = true, baseDelay = RISE_DELAY_MS)
        for (p in plates) move(p, raised = mOpen, baseDelay = if (mOpen) RISE_DELAY_MS else 0L)

        // The cut. Nothing fades; the two consoles simply never coexist.
        snap(leaving, visible = false, atMs = LEAVE_SNAP_MS)
        snap(arriving, visible = true, atMs = ARRIVE_SNAP_MS)

        // Anything mid-swap is in neither set and would otherwise be abandoned
        // wherever the cancelled animation left it.
        parkWithheld()

        val g = if (mOpen) mGeometry else homeGeometry
        window.animateTo(
            g.x, g.y, g.w, g.h,
            WINDOW_MS,
            if (mOpen) WINDOW_DELAY_OPEN_MS else WINDOW_DELAY_CLOSE_MS,
            WINDOW,
        )

        // The two readouts overlap on screen, so whichever is fading out has to
        // stop being VISIBLE at the end of its fade — otherwise it keeps
        // intercepting touches for the other one at alpha 0.
        fade(clockLayer, to = !mOpen, CLOCK_MS, if (mOpen) 0L else CLOCK_DELAY_CLOSE_MS, LINEAR)
        fade(tach, to = mOpen, TACH_MS, if (mOpen) TACH_DELAY_OPEN_MS else 0L, TACH)

        return true
    }

    /**
     * Fits or removes one key while the console is on screen, swapping it with
     * the blanking plate that occupies the same socket.
     *
     * The choreography is the console's own — the departing panel sinks at once,
     * the arriving one waits that sink out and rises — so plugging the K+DCAN
     * cable in looks like the fascia rearranging itself rather than like a
     * redraw. That is the whole point: the earlier version rebuilt this object
     * and applied the new state instantly, and a key appearing between two
     * frames reads as a glitch even when it is correct.
     *
     * [key] is cut in and out of visibility like any console key. [blank] never
     * is: a plate is always present, and in home mode it simply sits sunk in its
     * socket underneath an opaque key. Cutting it away would leave the socket
     * empty the next time M mode raised the plates.
     *
     * In M mode nothing moves. Both panels are already where the swap would put
     * them — the home key sunk and cut away, the plate raised — so only the
     * membership changes, which is what should happen when a cable is pulled out
     * from under a menu somebody is reading.
     */
    fun fit(key: Panel, blank: Panel, fitted: Boolean, animate: Boolean = true) {
        if (fitted) {
            if (key !in homeSet) homeSet += key
            if (blank !in plates) plates += blank
            withheld = null
        } else {
            homeSet -= key
            plates -= blank
            withheld = Withheld(key, blank)
        }

        if (isMOpen) return

        if (!animate) {
            settle(key, raised = fitted, visible = fitted)
            settle(blank, raised = !fitted, visible = true)
            return
        }

        move(key, raised = fitted, baseDelay = if (fitted) RISE_DELAY_MS else 0L)
        move(blank, raised = !fitted, baseDelay = if (fitted) 0L else RISE_DELAY_MS)
        snap(
            listOf(key),
            visible = fitted,
            atMs = if (fitted) ARRIVE_SNAP_MS else LEAVE_SNAP_MS,
        )
    }

    private fun fade(
        view: View,
        to: Boolean,
        durationMs: Long,
        delayMs: Long,
        interpolator: android.view.animation.Interpolator,
    ) {
        view.animate().cancel()
        if (to) view.visibility = View.VISIBLE
        view.animate()
            .alpha(if (to) 1f else 0f)
            .setDuration(durationMs)
            .setStartDelay(delayMs)
            .setInterpolator(interpolator)
            .withEndAction { if (!to) view.visibility = View.INVISIBLE }
    }

    private fun move(p: Panel, raised: Boolean, baseDelay: Long) {
        val delay = baseDelay + p.staggerMs
        val scale = if (raised) 1f else SINK_SCALE
        p.view.animate().cancel()
        p.view.animate()
            .scaleX(scale).scaleY(scale)
            .setDuration(KEY_MOVE_MS)
            .setStartDelay(delay)
            .setInterpolator(KEY_MOVE)

        p.scrim.animate().cancel()
        p.scrim.animate()
            .alpha(if (raised) 0f else SCRIM_ALPHA)
            .setDuration(KEY_DIM_MS)
            .setStartDelay(delay)
            .setInterpolator(LINEAR)
    }

    private fun snap(panels: List<Panel>, visible: Boolean, atMs: Long) {
        handler.postDelayed({
            for (p in panels) setVisible(p, visible)
        }, atMs)
    }

    private fun settle(p: Panel, raised: Boolean, visible: Boolean) {
        p.view.animate().cancel()
        p.scrim.animate().cancel()
        val scale = if (raised) 1f else SINK_SCALE
        p.view.scaleX = scale
        p.view.scaleY = scale
        p.scrim.alpha = if (raised) 0f else SCRIM_ALPHA
        setVisible(p, visible)
    }

    /**
     * INVISIBLE, never GONE: GONE would trigger a layout pass on a parent
     * holding ten absolutely-positioned children, for a View whose position is
     * about to matter again.
     *
     * This is also the whole of the interactivity story. ViewGroup only offers a
     * touch to a child when `getVisibility() == VISIBLE`, so the sunk console
     * stops accepting presses at exactly the moment it stops being visible — no
     * separate enabled/pointer-events flag to keep in step with it.
     */
    private fun setVisible(p: Panel, visible: Boolean) {
        p.view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    companion object {
        /** translateZ(-300px) seen through perspective(520px). */
        const val SINK_SCALE = 520f / (520f + 300f)

        /** brightness(.05) as a black overlay. */
        const val SCRIM_ALPHA = 0.95f

        private const val KEY_MOVE_MS = 300L
        private const val KEY_DIM_MS = 220L
        private const val RISE_DELAY_MS = 360L
        private const val LEAVE_SNAP_MS = 390L
        private const val ARRIVE_SNAP_MS = 400L

        private const val WINDOW_MS = 500L
        private const val WINDOW_DELAY_OPEN_MS = 340L
        private const val WINDOW_DELAY_CLOSE_MS = 260L

        private const val CLOCK_MS = 140L
        private const val CLOCK_DELAY_CLOSE_MS = 780L

        private const val TACH_MS = 420L
        private const val TACH_DELAY_OPEN_MS = 840L

        /** Longest tail of either direction, for callers that need to gate input. */
        const val TOTAL_MS = TACH_DELAY_OPEN_MS + TACH_MS

        private val LINEAR = LinearInterpolator()
        private val KEY_MOVE = PathInterpolator(0.33f, 0f, 0.15f, 1f)
        private val WINDOW = PathInterpolator(0.5f, 0f, 0.15f, 1f)
        private val TACH = PathInterpolator(0.7f, 0f, 0.4f, 1f)
    }
}
