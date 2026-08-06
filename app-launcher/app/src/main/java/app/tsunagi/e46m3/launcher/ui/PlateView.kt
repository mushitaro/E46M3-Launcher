package app.tsunagi.e46m3.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R

/**
 * A carbon blanking plate: what fills the two outer sockets once their keys have
 * sunk away in M mode.
 *
 * The design gives each plate its own rotated weave layer. This takes the weave
 * straight out of the panel bitmap at the plate's own screen position instead,
 * which is both cheaper (no second field to generate) and truer to the object
 * being depicted — a blank cut from the same sheet of carbon as the fascia
 * around it, so the twill runs continuously through the recess.
 */
class PlateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint()
    private val clip = Path()
    private val rect = RectF()
    private val radius = resources.getDimension(R.dimen.d_plate_r)

    private val hi = ContextCompat.getColor(context, R.color.d_plate_hi)
    private val lo = ContextCompat.getColor(context, R.color.d_plate_lo)

    private var srcX = 0
    private var srcY = 0

    /**
     * @param source the full-panel carbon bitmap
     * @param x,y where this plate sits on it, so the weave lines up with the fascia
     */
    fun setSource(source: Bitmap, x: Int, y: Int) {
        srcX = x
        srcY = y
        setSource(source)
    }

    /** Re-crops from a new panel bitmap — the day/night swap. */
    fun setSource(source: Bitmap) {
        paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(-srcX.toFloat(), -srcY.toFloat()) })
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader == null || width == 0) return
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, paint)
        // inset 0 1px 0 rgba(255,255,255,.07) / inset 0 -1px 0 rgba(0,0,0,.6)
        band.color = hi
        canvas.drawRect(0f, 0f, width.toFloat(), 1f, band)
        band.color = lo
        canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), band)
        canvas.restore()
    }
}
