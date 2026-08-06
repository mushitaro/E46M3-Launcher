package app.tsunagi.e46m3.launcher.ui

import android.widget.TextView
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R

/**
 * One reserved telemetry slot in the LCD: a value and its provenance tag.
 *
 * Two rules this exists to enforce, both of which were got wrong when the layout
 * baked its colours in statically:
 *
 *  1. **A placeholder is not a reading.** `--.-` must not be painted in the
 *     colour reserved for a measurement — doing so claims a value that does not
 *     exist. The semantic colour is applied only when real data arrives.
 *  2. **The slot keeps its geometry either way.** Width is fixed by the caller
 *     to the widest possible string, and the source line reserves its height
 *     even when empty, so a value appearing later moves nothing.
 *
 * @param placeholder what to show with no data, e.g. "--.-" or "----"
 * @param valueColorRes the colour for a REAL value (temperature gets red-300;
 *        RPM stays neutral, because it is neither a temperature nor a status)
 */
class LcdReadout(
    private val valueView: TextView,
    private val sourceView: TextView,
    private val placeholder: String,
    private val valueColorRes: Int,
) {
    init {
        clear()
    }

    /** A real reading, from a named source (CAN / NET / OBD). */
    fun set(value: String, source: String) {
        valueView.text = value
        valueView.setTextColor(ContextCompat.getColor(valueView.context, valueColorRes))
        sourceView.text = source
    }

    /** No reading. Not an error state — an instrument correctly reporting nothing. */
    fun clear() {
        valueView.text = placeholder
        valueView.setTextColor(
            ContextCompat.getColor(valueView.context, R.color.m_text_muted)
        )
        // Empty, not an em-dash: at 8dp the dash reads as noise, and provenance
        // only means anything when there is something to attribute.
        sourceView.text = ""
    }
}
