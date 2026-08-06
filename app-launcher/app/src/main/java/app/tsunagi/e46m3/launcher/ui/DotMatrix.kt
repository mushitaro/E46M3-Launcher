package app.tsunagi.e46m3.launcher.ui

import android.graphics.Canvas
import android.graphics.Paint

/**
 * The design's `dots(text, d, g)` as a plain painter.
 *
 * It lives apart from [Font57] because that file is generated from the design by
 * `tools/gen_font57.py` and gets overwritten wholesale; and apart from
 * [DotMatrixView] because the tachometer paints the same glyphs inside its own
 * canvas rather than in a View of its own.
 *
 * The geometry, verbatim from the design:
 *
 *     pitch          = dot + gap
 *     pixel at (c,r) = square of `dot` px at (c * pitch, r * pitch)
 *     glyph box      = glyph.width * pitch - gap   wide
 *                      7 * pitch - gap             tall
 *     character gap  = pitch            <- a whole pitch, not `gap`
 *     bit test       = rows[r] and (0x10 shr col)  <- column 0 is the HIGH bit
 *
 * The inter-character gap being a whole pitch is what leaves one blank pixel
 * column between characters and gives the readout its segment-display look.
 */
object DotMatrix {

    fun pitch(dot: Int, gap: Int): Int = dot + gap

    /** Width `text` needs, in px. Varies with content: `:` `.` are 1 column wide. */
    fun measure(text: String, dot: Int, gap: Int): Int {
        if (text.isEmpty()) return 0
        val p = pitch(dot, gap)
        var w = 0
        for ((i, c) in text.withIndex()) {
            w += Font57.glyph(c).width * p - gap
            if (i < text.lastIndex) w += p
        }
        return w
    }

    /** Height of one line, in px. Constant regardless of content. */
    fun height(dot: Int, gap: Int): Int = Font57.ROWS * pitch(dot, gap) - gap

    /** Paints `text` with its top-left corner at (left, top). */
    fun draw(canvas: Canvas, text: String, left: Float, top: Float, dot: Int, gap: Int, paint: Paint) {
        if (text.isEmpty()) return
        val p = pitch(dot, gap)
        var x = left
        for (c in text) {
            val g = Font57.glyph(c)
            for (r in 0 until Font57.ROWS) {
                val bits = g.rows[r]
                for (col in 0 until g.width) {
                    if (bits and (0x10 shr col) != 0) {
                        val px = x + col * p
                        val py = top + r * p
                        canvas.drawRect(px, py, px + dot, py + dot, paint)
                    }
                }
            }
            x += g.width * p - gap + p
        }
    }
}
