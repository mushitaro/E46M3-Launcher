package app.tsunagi.e46m3.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M-mode instrument row is laid out by hand-computed constants, and the only
 * place it can be seen is a car. These are the checks that would otherwise be
 * made by looking at it.
 *
 * They are worth having because the numbers are not independent. The row runs
 *
 *     [ engine speed ] [ RPM ] [ shift lamps ] [ WTR .. OIL .. ]
 *                                              [ IAT .. OUT .. ]
 *
 * as one line, so each block's origin is the previous block's end plus a gap,
 * and the cell width comes from the widest string the DS2 and CAN sources can
 * produce. Change any one and another is silently wrong — as text overlapping
 * text, 800km from a debugger.
 *
 * Only [DotMatrix] and [Font57] are exercised, both of which are plain Kotlin;
 * the geometry constants are compile-time, so no Android class is loaded.
 */
class TachLayoutTest {

    private fun width(text: String): Float =
        DotMatrix.measure(text, TachView.TEMP_DOT, TachView.TEMP_GAP).toFloat()

    private val labelW get() = width("WTR")
    private val lineH get() = DotMatrix.height(TachView.TEMP_DOT, TachView.TEMP_GAP)

    private val digitsRight
        get() = TachView.NUM_LEFT + DotMatrix.measure("8888", TachView.NUM_DOT, TachView.NUM_GAP)

    /**
     * The caption column: `RPM` on the lower line, the three shift lamps on the
     * upper one, sharing an origin. Whichever is wider sets the column.
     */
    private val captionRight
        get() = TachView.UNIT_X + maxOf(width("RPM"), 3 * 11f + 2 * 4f)

    private val blockRight
        get() = TachView.TEMP_X + 2 * TachView.TEMP_CELL_W + TachView.TEMP_GUTTER

    @Test
    fun `all four labels are the same width`() {
        val widths = listOf("WTR", "OIL", "IAT", "OUT").map { width(it) }.distinct()
        assertTrue("labels must align: $widths", widths.size == 1)
    }

    /**
     * Not a sample of likely readings — the whole range either source can emit.
     *
     * The three engine channels are a byte less 48 (`Mss54Block3`), so they span
     * -48..207 and nothing outside it exists. The outside air is dropped unless
     * it lands in -60..90 (`CarInfo.PLAUSIBLE_TEMP`) before it is rounded. So
     * -60..207 is every value that can reach this block, and a slot that holds
     * all of them is a slot that never reflows.
     *
     * Written as an enumeration rather than a handful of extremes on purpose:
     * an earlier version of this test picked the extremes by hand and picked a
     * wrong one, which is the same mistake in the same place as sizing the cell
     * by hand.
     */
    @Test
    fun `every producible reading clears its label`() {
        // Right-aligned in the cell, so what matters is that the value's left
        // edge never reaches the label's right edge.
        for (v in -60..207) {
            val text = "$v${TachView.UNIT}"
            val gap = TachView.TEMP_CELL_W - labelW - width(text)
            assertTrue("'$text' leaves only ${gap}px after the label", gap >= 4f)
        }
        val blank = TachView.TEMP_CELL_W - labelW - width(TachView.NO_TEMP)
        assertTrue("'${TachView.NO_TEMP}' leaves only ${blank}px", blank >= 4f)
    }

    /**
     * The point of the whole layout: the readouts are level with the engine
     * speed, so the two blocks must start and end on the same scan lines.
     */
    @Test
    fun `the two rows span exactly the height of the engine-speed digits`() {
        val digitsH = DotMatrix.height(TachView.NUM_DOT, TachView.NUM_GAP)
        val blockH = TachView.TEMP_ROW_PITCH + lineH
        assertEquals("tops differ", TachView.NUM_TOP, TachView.TEMP_Y, 0f)
        assertEquals("bottoms differ", digitsH.toFloat(), blockH, 0f)
    }

    @Test
    fun `the row reads left to right with nothing overlapping`() {
        assertTrue(
            "digits end at $digitsRight, caption starts at ${TachView.UNIT_X}",
            TachView.UNIT_X >= digitsRight + 8f,
        )
        assertTrue(
            "caption column ends at $captionRight, temperatures start at ${TachView.TEMP_X}",
            TachView.TEMP_X >= captionRight + 12f,
        )
        assertTrue("block runs to $blockRight of ${TachView.WIN_W}", blockRight <= TachView.WIN_W - 8f)
    }

    /**
     * `7480 RPM` is one reading; the temperatures are another. So the caption
     * has to be nearer the number it belongs to than the block it does not.
     *
     * With both gaps at 20px the row read as `7480 | RPM WTR |` on the panel —
     * the caption had joined the wrong neighbour. Proximity is the only grouping
     * cue available here; there are no rules, boxes or weight differences.
     */
    @Test
    fun `the caption belongs to the digits, not to the block`() {
        val toDigits = TachView.UNIT_X - digitsRight
        val toBlock = TachView.TEMP_X - captionRight
        assertTrue("caption sits $toDigits from the digits and $toBlock from the block", toBlock >= 2 * toDigits)
        // And it must clear the block's own internal gutter, or the block stops
        // reading as a single object.
        assertTrue("$toBlock does not beat the ${TachView.TEMP_GUTTER} gutter", toBlock > TachView.TEMP_GUTTER)
    }

    /**
     * The gutter has to beat the widest gap that occurs *inside* a cell, which
     * is the one a `--°C` fallback leaves between label and value. Otherwise the
     * eye groups `92°C OIL` as a pair and the table stops reading as two
     * columns — which is what it did on the panel at a 16px gutter.
     */
    @Test
    fun `the columns are further apart than the gap inside one`() {
        val widestInternalGap = TachView.TEMP_CELL_W - labelW - width(TachView.NO_TEMP)
        assertTrue(
            "gutter ${TachView.TEMP_GUTTER} does not beat an internal $widestInternalGap",
            TachView.TEMP_GUTTER > widestInternalGap,
        )
    }

    @Test
    fun `the rows do not touch each other`() {
        assertTrue("rows overlap", TachView.TEMP_ROW_PITCH >= lineH + 4f)
    }

    /**
     * Two bounds on the digits, from opposite directions.
     *
     * Below: the sweep's lower-left edge passes (51, 125) and the digits' top
     * line is y=118, so anything much left of 70 runs into the gauge.
     *
     * Above: they sat at 72 for one revision and read as shoved into the corner.
     * The row is 541px of content in a 700px window, so this is not free — it
     * was bought by stacking the caption over the shift lamps, and a change that
     * spends that width again will show up here rather than on the panel.
     */
    @Test
    fun `the digits sit clear of the sweep without hugging it`() {
        assertTrue("digits at ${TachView.NUM_LEFT} are back in the corner", TachView.NUM_LEFT >= 110f)
        // The upper bound is not a number here: `the row reads left to right`
        // already stops them reaching the caption.
    }
}
