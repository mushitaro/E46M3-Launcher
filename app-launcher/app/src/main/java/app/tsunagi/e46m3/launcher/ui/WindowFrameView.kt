package app.tsunagi.e46m3.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator

/**
 * The display window's frame, drawn rather than laid out.
 *
 * The design moves and resizes the window with a CSS transition on
 * `left/top/width/height`. Doing that with LayoutParams would run measure+layout
 * on every frame of a 500ms animation — the single most reliable way to make a
 * transition stutter on an in-order Cortex-A7, and the reason the plan bans
 * ChangeBounds and margin animation outright.
 *
 * So the frame is not a laid-out box at all. It is one View spanning the screen
 * that draws a rounded rectangle wherever it is told, and the animation moves
 * four floats. Nothing is measured, nothing is laid out, and the display list is
 * two drawing ops long.
 *
 * ## Why one rectangle and not two
 *
 * The design casts the bezel as `box-shadow: 0 0 0 10px #000000` around a glass
 * whose own background is `#000000` at radius 9. Both are the same black, so the
 * visible shape is a single rounded rectangle: the glass inflated by 10px, at
 * radius 9+10 = 19. Drawing the glass separately would paint black on black.
 *
 * ## Why the content is not clipped to it
 *
 * `overflow: hidden` on the design's window never actually clips anything, and
 * the timing is what guarantees it: the clock has faded out by 140ms, the window
 * travels from 340ms to 840ms, and the tachometer only appears at 840ms. Nothing
 * is ever visible while the frame is in motion, and both readouts fit inside
 * their own end-state. A resizing clip would therefore cost a saveLayer per
 * frame to reproduce an effect that is never observable.
 */
class WindowFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rect = RectF()

    /** The glass rectangle. The bezel is derived from it. */
    private var glassLeft = 0f
    private var glassTop = 0f
    private var glassWidth = 0f
    private var glassHeight = 0f

    var bezel: Float = 10f
    var glassRadius: Float = 9f

    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    fun setGeometry(left: Float, top: Float, width: Float, height: Float) {
        glassLeft = left
        glassTop = top
        glassWidth = width
        glassHeight = height
        invalidate()
    }

    /**
     * Interpolates all four values at once. One animator, not four: the frame
     * has to stay a rectangle at every intermediate frame, and four independent
     * animators can be descheduled independently.
     */
    fun animateTo(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        durationMs: Long,
        delayMs: Long,
        interpolator: Interpolator,
    ) {
        animator?.cancel()
        val l0 = glassLeft
        val t0 = glassTop
        val w0 = glassWidth
        val h0 = glassHeight
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            setInterpolator(interpolator)
            addUpdateListener {
                val f = it.animatedValue as Float
                setGeometry(
                    l0 + (left - l0) * f,
                    t0 + (top - t0) * f,
                    w0 + (width - w0) * f,
                    h0 + (height - h0) * f,
                )
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (glassWidth <= 0f || glassHeight <= 0f) return
        rect.set(
            glassLeft - bezel,
            glassTop - bezel,
            glassLeft + glassWidth + bezel,
            glassTop + glassHeight + bezel,
        )
        val r = glassRadius + bezel
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
