package app.tsunagi.e46m3.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The M-mode tachometer, transcribed from the design's `paintTach()`.
 *
 * The whole instrument is one View covering the enlarged display window
 * (700x180), drawn in the design's own window-local coordinates. That keeps
 * every number below identical to the source, and it is one display list of
 * about forty ops instead of thirty-odd child Views.
 *
 * ## Showing nothing is a state, not a failure
 *
 * The design drives this from a random-walk simulator. **That simulator is not
 * reproduced.** [rpm] and [temps] carry whatever the DME actually said, and null
 * when it said nothing — ignition off, engine stopped, cable unseated, ECU
 * asleep. Then every segment sits in its unlit outline and the readouts show
 * `----` and `--°C`. Painting a plausible needle instead would be putting an
 * invented number on an instrument.
 *
 * Engine speed, coolant, oil and intake air come from MSS54 live block 3 over
 * the K+DCAN cable and are confirmed against the running car. Outside air comes
 * from the head unit's own CAN service, which is a different path with a
 * different failure mode, so it is quite normal for that one slot to be filled
 * while the other three are empty.
 *
 * ## Peak hold is timed, not counted
 *
 * The design holds the peak marker for 26 ticks and then decays it 0.34 segments
 * per tick, on a fixed 90ms simulator interval. Those constants are expressed
 * here as the durations they actually mean (2.34s hold, 3.78 segments/second),
 * because a real DS2 link will not deliver samples at 90ms and the observable
 * behaviour, not the tick count, is what the design specifies.
 */
class TachView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private val dots = Paint().apply { isAntiAlias = false }
    private val path = Path()

    // The ribbon's two edge curves, and the tangent at each boundary.
    private val outX = FloatArray(SEGMENTS + 1)
    private val outY = FloatArray(SEGMENTS + 1)
    private val inX = FloatArray(SEGMENTS + 1)
    private val inY = FloatArray(SEGMENTS + 1)
    private val ux = FloatArray(SEGMENTS + 1)
    private val uy = FloatArray(SEGMENTS + 1)

    init {
        // Fixed geometry in a fixed-size window: computed once, never again.
        buildBand()
    }

    private val unlit = ContextCompat.getColor(context, R.color.d_tach_unlit)
    private val unlitRed = ContextCompat.getColor(context, R.color.d_tach_unlit_red)
    private val unlitDay = ContextCompat.getColor(context, R.color.d_tach_unlit_day)
    private val unlitRedDay = ContextCompat.getColor(context, R.color.d_tach_unlit_red_day)
    private val red = ContextCompat.getColor(context, R.color.d_tach_red)
    private val redFlash = ContextCompat.getColor(context, R.color.d_tach_red_flash)
    private val shiftColor = ContextCompat.getColor(context, R.color.d_shift_light)

    /** The design's `lcdColor`; dimmed to 80% at night by the caller. */
    var litColor: Int = DotMatrixView.DEFAULT_LIT
        set(v) { field = v; invalidate() }

    /**
     * Which set of unlit-outline tones to use.
     *
     * The design's 20% amber is right at night and invisible by day: sunlight on
     * the glass adds the same veiling luminance to the outline and to the black
     * behind it, and 0.02 does not survive that. So the empty part of the sweep
     * gets a heavier outline in daylight — the lit part never needed one.
     */
    var night: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * The four auxiliary temperatures, in whole degrees Celsius.
     *
     * Null is "no reading" and is drawn as such. The three engine channels come
     * from MSS54 live block 3 over the K+DCAN cable while the outside air comes
     * from the head unit's own CAN service, so one being present while the
     * others are not is the ordinary case, not a fault.
     */
    data class Temps(
        val coolantC: Int? = null,
        val oilC: Int? = null,
        val intakeC: Int? = null,
        val outsideC: Int? = null,
    )

    var temps: Temps = Temps()
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * Engine speed, or null for "no reading".
     *
     * Null is not an error state and not a zero: it is an instrument correctly
     * reporting that nothing is connected. Setting 0 would claim a stalled
     * engine, which is a different and false statement.
     */
    var rpm: Int? = null
        set(v) {
            field = v
            updatePeak()
            invalidate()
        }

    // Peak-hold state, in segments.
    private var peak = -1f
    private var peakSetAt = 0L
    private var lastPeakUpdate = 0L

    private val flashTick = Runnable { invalidate() }
    private var selfTest: ValueAnimator? = null

    /**
     * A full-scale sweep and return, like a cluster's power-on needle test.
     *
     * This is the only way to see the red zone, the peak marker and the shift
     * lights before the DS2 transport exists, so it is worth the twenty lines.
     * It is a *display* test and reads as one — a smooth 0→8000→0 ramp is not
     * something an engine does — and it ends by putting the instrument back to
     * no-reading rather than leaving a number on the glass.
     */
    fun runSelfTest() {
        selfTest?.cancel()
        peak = -1f
        selfTest = ValueAnimator.ofFloat(0f, 2f).apply {
            duration = SELF_TEST_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                rpm = (RPM_MAX * (if (f <= 1f) f else 2f - f)).toInt()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rpm = null
                    peak = -1f
                }
            })
            start()
        }
    }

    private fun litSegments(): Int {
        val r = rpm ?: return 0
        return (r.toFloat() / RPM_MAX * SEGMENTS).roundToInt().coerceIn(0, SEGMENTS)
    }

    private fun updatePeak() {
        val now = SystemClock.uptimeMillis()
        val on = litSegments().toFloat()
        if (peak < 0f || on > peak) {
            peak = on
            peakSetAt = now
        } else if (now - peakSetAt > PEAK_HOLD_MS && peak > on) {
            val dt = (now - lastPeakUpdate).coerceAtMost(500L)
            peak = (peak - PEAK_FALL_PER_S * dt / 1000f).coerceAtLeast(on)
        }
        lastPeakUpdate = now
    }

    override fun onDraw(canvas: Canvas) {
        val on = litSegments()
        val inRed = on > RED_FROM
        // 8 simulator ticks of 90ms = a 720ms cycle, half lit.
        val flash = inRed && (SystemClock.uptimeMillis() / (FLASH_MS / 2)) % 2 == 0L
        val pk = peak.roundToInt()

        drawBar(canvas, on, flash, pk)
        drawNumber(canvas)
        drawTemps(canvas)
        drawUnitAndShiftLights(canvas, inRed && flash)

        // Only the red-zone flash needs a clock. Below the limiter — and with no
        // data at all — this View repaints exactly when its value changes.
        removeCallbacks(flashTick)
        if (inRed) postDelayed(flashTick, FLASH_MS / 2)
    }

    /**
     * The sweep, as one continuous ribbon rather than 29 separate blocks.
     *
     * The design drew each segment as an axis-aligned rounded rect rotated about
     * its own centre. Adjacent segments got *different* rotations, so their
     * facing edges were never parallel and could not meet: the band necessarily
     * broke into overlapping scales, worst at the left where the rake changed
     * fastest. Building it as a quad strip off a single centreline (see
     * [buildBand]) makes every boundary an edge that two segments share exactly.
     *
     * Lit segments are filled with a hairline of background between them, which
     * is what makes the ribs read. Unlit ones are drawn as one continuous
     * outline — two edge polylines plus a rib per boundary — so that a shared
     * boundary is a single line rather than two lines a gap apart.
     */
    private fun drawBar(canvas: Canvas, on: Int, flash: Boolean, pk: Int) {
        // Lit run, filled.
        for (i in 0 until on) {
            fill.color = if (i >= RED_FROM) (if (flash) redFlash else red) else litColor
            segmentPath(i, path)
            canvas.drawPath(path, fill)
        }
        // Unlit run, outlined. Split at the red-zone boundary because the two
        // halves have different outline colours; these are literal in the design
        // (they do NOT follow lcdColor), so they are literal here too.
        strokeRun(canvas, on, RED_FROM, if (night) unlit else unlitDay)
        strokeRun(canvas, maxOf(on, RED_FROM), SEGMENTS, if (night) unlitRed else unlitRedDay)

        // Peak hold: one segment outlined at 2px, over the top of the outline.
        if (pk > 0 && pk >= on && pk < SEGMENTS) {
            stroke.color = if (pk >= RED_FROM) red else litColor
            stroke.strokeWidth = 2f
            segmentPath(pk, path)
            canvas.drawPath(path, stroke)
        }
    }

    /** Segment [i] as a quad, pulled back along the tangent to leave its ribs. */
    private fun segmentPath(i: Int, out: Path) {
        val g = RIB_GAP / 2f
        val a = i
        val b = i + 1
        out.reset()
        out.moveTo(outX[a] + ux[a] * g, outY[a] + uy[a] * g)
        out.lineTo(outX[b] - ux[b] * g, outY[b] - uy[b] * g)
        out.lineTo(inX[b] - ux[b] * g, inY[b] - uy[b] * g)
        out.lineTo(inX[a] + ux[a] * g, inY[a] + uy[a] * g)
        out.close()
    }

    /** Segments [from, to) as a continuous outline: two edges plus the ribs. */
    private fun strokeRun(canvas: Canvas, from: Int, to: Int, color: Int) {
        if (to <= from) return
        path.reset()
        path.moveTo(outX[from], outY[from])
        for (j in from + 1..to) path.lineTo(outX[j], outY[j])
        path.moveTo(inX[from], inY[from])
        for (j in from + 1..to) path.lineTo(inX[j], inY[j])
        for (j in from..to) {
            path.moveTo(outX[j], outY[j])
            path.lineTo(inX[j], inY[j])
        }
        stroke.color = color
        stroke.strokeWidth = 1f
        canvas.drawPath(path, stroke)
    }

    /**
     * The 30 segment boundaries, computed once.
     *
     * ## The design's curve, cut vertically
     *
     * The band's *outline* is exactly the design's: its centreline and thickness
     * are the four formulas from `paintTach()`, unaltered —
     *
     *     t    = i / 28
     *     cx   = 12 + 568t                      (within the bar box)
     *     cy   = 20 + 62 · e^(-6t)
     *     h    = 24 + 52 · e^(-3.4t)
     *     rot  = atan2(-372 · e^(-6t), 568)     the tangent angle
     *
     * — with `h` offset perpendicular to the centreline, since `rot` is that
     * centreline's tangent and `h` therefore measures across it.
     *
     * What changes is only where the band is *cut into segments*. The design
     * rotates each block to the local tangent, so adjacent blocks are never
     * parallel and cannot meet; the ribs run at 57 degrees at the left, 90 at the
     * right, and the tail ends on a diagonal. Cutting on vertical lines instead
     * gives blocks that stand plumb, share their edges exactly, and square the
     * tail off.
     *
     * The two edges begin and end at different x — the perpendicular offset leans
     * them apart where the curve is steep — so the first and last cuts are taken
     * where both edges exist. That is what squares the ends rather than leaving a
     * sliver.
     */
    private fun buildBand() {
        val n = EDGE_SAMPLES
        val upperX = FloatArray(n + 1); val upperY = FloatArray(n + 1)
        val lowerX = FloatArray(n + 1); val lowerY = FloatArray(n + 1)
        for (i in 0..n) {
            // The design's own parameter, including the half-block of overhang
            // its end segments had.
            val t = T_LO + (T_HI - T_LO) * i / n
            val e6 = exp(-6f * t)
            val cx = BAR_X + 12f + 568f * t
            val cy = BAR_Y + 20f + 62f * e6
            val half = (24f + 52f * exp(-3.4f * t)) / 2f

            // Tangent (568, -372·e^-6t); normal is that turned a quarter.
            val dx = 568f
            val dy = -372f * e6
            val len = hypot(dx, dy)
            val nx = -dy / len
            val ny = dx / len

            upperX[i] = cx - nx * half; upperY[i] = cy - ny * half
            lowerX[i] = cx + nx * half; lowerY[i] = cy + ny * half
        }

        val left = maxOf(upperX[0], lowerX[0])
        val right = minOf(upperX[n], lowerX[n])
        for (j in 0..SEGMENTS) {
            val x = left + (right - left) * j / SEGMENTS
            outX[j] = x; outY[j] = interpolateY(upperX, upperY, x)
            inX[j] = x; inY[j] = interpolateY(lowerX, lowerY, x)
            // The rib gap is measured horizontally now that the ribs are vertical.
            ux[j] = 1f; uy[j] = 0f
        }
    }

    /** Height of an edge polyline at [x]. Both edges are monotone in x. */
    private fun interpolateY(xs: FloatArray, ys: FloatArray, x: Float): Float {
        if (x <= xs[0]) return ys[0]
        for (i in 0 until xs.size - 1) {
            if (x <= xs[i + 1]) {
                val span = xs[i + 1] - xs[i]
                if (span <= 0f) return ys[i]
                return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / span
            }
        }
        return ys[ys.size - 1]
    }

    /**
     * Four dot-matrix characters, rounded to the nearest 10 as the design does.
     *
     * Left-aligned at [NUM_LEFT] rather than centred in the window. The design
     * centred them because nothing else shared the line; the four temperatures
     * do now, and they had to sit level with the engine speed rather than above
     * it. Moving the digits left is what buys the room — everything on this line
     * is one instrument row, reading `7480 RPM ▮▮▮  WTR 92°C  OIL 104°C`.
     */
    private fun drawNumber(canvas: Canvas) {
        val text = rpm?.let {
            ((it / 10) * 10).coerceIn(0, 9999).toString().padStart(4, ' ')
        } ?: NO_READING
        dots.color = litColor
        DotMatrix.draw(canvas, text, NUM_LEFT, NUM_TOP, NUM_DOT, NUM_GAP, dots)
    }

    /**
     * The four temperatures, two to a row, on the engine speed's own line.
     *
     * ## They are level with the digits, not stacked above them
     *
     * The two rows are 20px tall on a 28px pitch, which is 48px overall — the
     * exact height of the four dot-matrix digits beside them. Both blocks start
     * at [NUM_TOP], so their tops and their bottoms line up and the whole thing
     * reads as one instrument row rather than two things at two heights.
     *
     * ## Why the values are right-aligned and the labels are not
     *
     * Each cell is a fixed slot: the label sits at its left edge and the value
     * hangs off its right edge. Nothing moves when a reading changes width — 88
     * to 102, or 102 to `--` — which matters more here than anywhere else on the
     * screen, because these four update while somebody is watching them. Padding
     * with spaces would not achieve it: the space glyph is three columns wide
     * and a digit is five, so a padded string is not the same width.
     */
    private fun drawTemps(canvas: Canvas) {
        val labelColor = fade(litColor, LABEL_ALPHA)
        for ((i, cell) in TEMP_CELLS.withIndex()) {
            val x = TEMP_X + (i % 2) * (TEMP_CELL_W + TEMP_GUTTER)
            val y = TEMP_Y + (i / 2) * TEMP_ROW_PITCH

            dots.color = labelColor
            DotMatrix.draw(canvas, cell.label, x, y, TEMP_DOT, TEMP_GAP, dots)

            val text = cell.read(temps)?.let { "$it$UNIT" } ?: NO_TEMP
            dots.color = litColor
            DotMatrix.draw(
                canvas, text,
                x + TEMP_CELL_W - DotMatrix.measure(text, TEMP_DOT, TEMP_GAP), y,
                TEMP_DOT, TEMP_GAP, dots,
            )
        }
    }

    /** [color] with its alpha scaled — so the label follows the night dim too. */
    private fun fade(color: Int, factor: Float): Int =
        (color and 0x00FFFFFF) or
            ((Color.alpha(color) * factor).toInt().coerceIn(0, 255) shl 24)

    /**
     * The `RPM` caption and the shift lights, stacked in one narrow column.
     *
     * They used to sit side by side, which cost 60px of the row and — because
     * the lamps only appear near the limiter — left a hole in the middle of it
     * for all the rest of the time. Stacking them puts the caption on the
     * digits' own baseline and the lamps on the upper line, where they are
     * nearer the red end of the sweep they are warning about.
     */
    private fun drawUnitAndShiftLights(canvas: Canvas, lampsOn: Boolean) {
        dots.color = litColor
        DotMatrix.draw(canvas, "RPM", UNIT_X, UNIT_TOP, UNIT_DOT, UNIT_GAP, dots)

        if (!lampsOn) return
        fill.color = shiftColor
        var x = UNIT_X
        repeat(SHIFT_LAMPS) {
            canvas.drawRect(x, LAMP_TOP, x + LAMP_W, LAMP_TOP + LAMP_H, fill)
            x += LAMP_W + LAMP_GAP
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flashTick)
        selfTest?.cancel()
        selfTest = null
    }

    companion object {
        /** S54 redline territory: the design's scale ends at 8000. */
        const val RPM_MAX = 8000
        const val SEGMENTS = 29
        const val RED_FROM = 26

        private const val NO_READING = "----"

        /**
         * Spelt in full rather than as a bare degree sign.
         *
         * An earlier layout could not fit it — the digits were centred in the
         * window and left only 244px to their right — and dropped the C. Putting
         * the readouts on the engine speed's own line freed the width, so the
         * unit says what it is and matches the home screen's `--.-°C`.
         */
        internal const val UNIT = "°C"
        internal const val NO_TEMP = "--$UNIT"

        /** One temperature slot: its fixed label and where its value comes from. */
        private class TempCell(val label: String, val read: (Temps) -> Int?)

        /**
         * Row-major: engine fluids on top, air temperatures below.
         *
         * All four labels are three characters so that every value slot starts
         * at the same offset within its cell and the block reads as a table
         * rather than four separate readouts.
         */
        private val TEMP_CELLS = listOf(
            TempCell("WTR") { it.coolantC },
            TempCell("OIL") { it.oilC },
            TempCell("IAT") { it.intakeC },
            TempCell("OUT") { it.outsideC },
        )

        private const val PEAK_HOLD_MS = 2_340L      // 26 ticks x 90ms
        private const val PEAK_FALL_PER_S = 3.78f    // 0.34 segments / 90ms
        private const val FLASH_MS = 720L            // 8 ticks x 90ms
        private const val SELF_TEST_MS = 2_600L      // up and back down

        // Window-local geometry, all from the design's absolute positions inside
        // the 700x180 M-mode window.
        internal const val WIN_W = 700f
        private const val BAR_X = 26f                // left:26px
        private const val BAR_Y = 4f                 // bottom:52px, height:124px
        /**
         * The design's parameter range, including the half-block of overhang its
         * end segments had: centres sat at i/28, so the band's ends are half a
         * step beyond 0 and 1.
         */
        private const val T_LO = -0.5f / 28f
        private const val T_HI = 28.5f / 28f

        /**
         * Resolution of the two edge polylines the vertical cuts are taken
         * against. 600 puts a vertex roughly every pixel of band length, so the
         * linear interpolation between them is well under half a pixel out.
         */
        private const val EDGE_SAMPLES = 600

        /**
         * The dark hairline left between lit segments.
         *
         * The design's blocks were 19px on a ~20.3px pitch, so it had a ~1.3px
         * gap; this keeps that reading while the two sides of the gap are now
         * parallel, which is the whole difference.
         */
        private const val RIB_GAP = 1.6f

        /**
         * The instrument row, in window-local px. One line, left to right:
         *
         *     116       273 285 333       369                         665
         *                   | ▮▮▮ |       | WTR  92°C     OIL 104°C |
         *     |  7 4 8 0  | | RPM |       | IAT  47°C     OUT  21°C |
         *     +-- y 118 ---------------------------------------- y 166 --+
         *                  ^12^     ^--36--^
         *
         * Everything on it starts at y=118 and ends at y=166: the digits are 48
         * tall, and the two temperature rows are 20 + 8 + 20, which is the same
         * 48. That equality is the layout — it is what makes the readouts sit
         * level with the engine speed instead of above it.
         *
         * ## The two gaps are deliberately unequal
         *
         * `7480 RPM` is one reading and the temperatures are another, so the gap
         * inside the pair is 12px and the gap between the pair and the block is
         * 36px. They were both 20 for one revision and the row read as
         * `7480 | RPM WTR | ...` — the caption had joined the wrong neighbour.
         * The 36 also has to beat the 28px gutter inside the block, or the block
         * stops being one object.
         *
         * The digits were at x=72 for a revision before that, hard against the
         * sweep's descending tail. What bought them back their 44px was stacking
         * the caption over the shift lamps rather than running them side by
         * side: that is 60px of row width, and it also closed the hole that sat
         * in the middle of the line whenever the lamps were dark, which is
         * nearly always. The floor is the sweep — its lower-left edge passes
         * (51, 125) and climbs going right, so anything much left of x=70 runs
         * into the gauge at y=118.
         */
        internal const val NUM_DOT = 6
        internal const val NUM_GAP = 1
        internal const val NUM_LEFT = 116f
        internal const val NUM_TOP = 118f

        /**
         * 134px per cell = a 48px three-character label, 10px, and the 76px the
         * widest possible reading needs. `-48°C` and `207°C` are both 76: the
         * DS2 channels are a byte less 48, and the outside air is clamped to
         * ±60 before it gets here, so nothing wider can arrive.
         *
         * The 28px gutter is wider than it looks like it needs to be, and that
         * is the point: the two columns are two separate readouts, and at 16px
         * `92°C OIL` ran together into one string on the panel. The gap between
         * columns has to beat the widest gap that occurs *inside* a cell —
         * 25px, when a value falls back to `--°C` — or the eye groups the wrong
         * pairs.
         */
        internal const val TEMP_DOT = 2
        internal const val TEMP_GAP = 1
        internal const val TEMP_X = 369f
        internal const val TEMP_Y = NUM_TOP
        internal const val TEMP_CELL_W = 134f
        internal const val TEMP_GUTTER = 28f
        internal const val TEMP_ROW_PITCH = 28f

        /** The label is subordinate to its value, and says so by being dimmer. */
        private const val LABEL_ALPHA = 0.72f

        private const val UNIT_DOT = 2
        private const val UNIT_GAP = 1
        internal const val UNIT_X = 285f
        /** Bottom-aligned with the digits, not floating above their baseline. */
        internal const val UNIT_TOP = 146f

        /** The lamps share the caption's column and take the upper line. */
        private const val SHIFT_LAMPS = 3
        private const val LAMP_W = 11f
        private const val LAMP_H = 20f
        private const val LAMP_GAP = 4f
        private const val LAMP_TOP = NUM_TOP
    }
}
