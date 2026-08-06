package app.tsunagi.e46m3.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import app.tsunagi.e46m3.launcher.R

/**
 * Renders text the way the design's LCD does: every character is drawn as
 * discrete square pixels from the 5x7 table, not as a font. The geometry lives
 * in [DotMatrix], which the tachometer shares.
 *
 * Everything is measured in real device pixels, deliberately. The design is
 * authored at 1024x600, which is exactly this panel's physical resolution, so
 * working in px reproduces it 1:1 with no rounding drift. Density independence
 * would buy nothing here: there is one panel and it never changes.
 */
class DotMatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint().apply { isAntiAlias = false }

    var dot: Int = 4
        set(v) { field = v; requestLayout(); invalidate() }

    var gap: Int = 1
        set(v) { field = v; requestLayout(); invalidate() }

    var litColor: Int = DEFAULT_LIT
        set(v) { field = v; invalidate() }

    var text: String = ""
        set(v) {
            val n = v.uppercase()
            if (field != n) { field = n; requestLayout(); invalidate() }
        }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.DotMatrixView)
            dot = a.getInt(R.styleable.DotMatrixView_dotSize, dot)
            gap = a.getInt(R.styleable.DotMatrixView_dotGap, gap)
            litColor = a.getColor(R.styleable.DotMatrixView_litColor, litColor)
            a.getString(R.styleable.DotMatrixView_android_text)?.let { t -> text = t }
            a.recycle()
        }
    }

    /** Width the current text needs, in px. */
    fun contentWidth(): Int = DotMatrix.measure(text, dot, gap)

    /** Height of a line, in px. Constant regardless of content. */
    fun contentHeight(): Int = DotMatrix.height(dot, gap)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(contentWidth() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = litColor
        DotMatrix.draw(canvas, text, paddingLeft.toFloat(), paddingTop.toFloat(), dot, gap, paint)
    }

    companion object {
        /** The design's default `lcdColor`. */
        val DEFAULT_LIT = Color.parseColor("#FF9A2E")
    }
}
