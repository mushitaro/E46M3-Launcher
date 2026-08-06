package app.tsunagi.e46m3.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R

/**
 * Builds the console keys, their sockets and the M-mode blanking plates from one
 * table.
 *
 * The design draws thirty-odd near-identical absolutely-positioned boxes across
 * two key sets; repeating that in XML would be thirty chances to mistype a
 * coordinate, and the two sets have to agree on the grid exactly or the swap
 * animation shows it. Here the grid is data, and the only things that vary per
 * key are position, icon, face treatment and what it does.
 *
 * Layout, straight from the design (device px):
 *   keys      148x52, radius 7
 *   columns   x = 54, 246, 438, 630, 822   (192px pitch)
 *   rows      y = 315 and 391
 *   socket    the key inflated by 7px on every side
 *
 * The home set fills all five columns; the M set fills the middle three, and the
 * outer two sockets get carbon plates instead.
 */
object ConsoleKeys {

    /**
     * How a key face is painted.
     *
     * There used to be a third treatment, DEAD: a dimmed, unclickable key that
     * held the final geometry of something not yet built. It is gone. The two
     * keys that wore it (DIAG, 整備履歴) are now blanking plates, which says the
     * same thing more plainly — the socket is empty, not the switch broken —
     * and means the console never shows a control that cannot act.
     */
    enum class Face {
        /** The ordinary console key; follows the day/night gradient. */
        NORMAL,

        /** The M key: fixed ///M red, never dimmed for night, never animated. */
        BRAND,
    }

    /**
     * @param targetId matches an entry in launch/Targets.kt.
     * @param staggerMs the design's per-column delay within its own set.
     */
    data class Spec(
        val targetId: String,
        val col: Int,
        val row: Int,
        val iconRes: Int,
        val staggerMs: Long,
        val face: Face = Face.NORMAL,
        /**
         * The key carries a status lamp reporting whether its link is up.
         *
         * Only for keys that name a **connection**. Everything else on this
         * console hands the screen to something and holds no state, so a lamp
         * on it would be reporting nothing. The radio lost its lamp for exactly
         * that reason: a tuner is not connected to anything.
         */
        val hasLamp: Boolean = false,
    )

    /**
     * The home console: five columns, two rows. Stagger is 30ms per column.
     *
     * Three keys carry a status lamp, and they are the three that name a link:
     * Bluetooth, the Android link and CarPlay. Nothing else does. The radio had
     * one and lost it — a tuner is not connected to a device, so the lamp had
     * nothing to report and only looked as though it did.
     */
    val HOME: List<Spec> = listOf(
        Spec("radio", 0, 0, R.drawable.d_ic_radio, 0),
        Spec("video", 1, 0, R.drawable.d_ic_video, 30),
        Spec("eq", 2, 0, R.drawable.d_ic_eq, 60),
        Spec("maps", 3, 0, R.drawable.d_ic_map, 90),
        Spec("apps", 4, 0, R.drawable.d_ic_apps, 120),
        Spec("bluetooth", 0, 1, R.drawable.d_ic_bt, 0, hasLamp = true),
        // The two link keys wear the real marks, not the design's line icons:
        // these name a specific product, so an approximation would be a lie about
        // which one. See d_brand_*.xml for provenance.
        Spec("android_link", 1, 1, R.drawable.d_brand_androidauto, 30, hasLamp = true),
        Spec("carplay", 2, 1, R.drawable.d_brand_carplay, 60, hasLamp = true),
        Spec("settings", 3, 1, R.drawable.d_ic_settings, 90),
        Spec("m", 4, 1, R.drawable.d_m_logo, 120, face = Face.BRAND),
    )

    /**
     * The M console. Four keys, and four blanked sockets.
     *
     * DIAG and 整備履歴 are not built, so their sockets carry carbon rather than
     * a switch. They keep their positions — (2,0) and (3,0) — so that fitting
     * them later is a one-line change to this table and moves nothing else, and
     * they rise and sink with the console like every other plate.
     *
     * TUNER carries no lamp: it opens a web tool, not a link to a device.
     */
    val M_MODE: List<Spec> = listOf(
        Spec("tuner", 1, 0, R.drawable.d_ic_tuner, 0),
        Spec("settings", 1, 1, R.drawable.d_ic_settings_m, 0),
        Spec("apps", 2, 1, R.drawable.d_ic_apps_m, 30),
        Spec("m_home", 3, 1, R.drawable.d_ic_back, 60),
    )

    /** Sockets exist under every key position, in both modes. */
    val SOCKETS: List<Pair<Int, Int>> =
        (0..4).flatMap { col -> listOf(col to 0, col to 1) }

    /**
     * Every socket the M console leaves empty: the two outer columns, plus the
     * two the unbuilt apps will one day take.
     *
     * All of them are ordinary plates — sunk under an opaque home key in home
     * mode, risen in M mode — so nothing here is a special case.
     */
    val PLATES: List<Pair<Int, Int>> =
        listOf(0 to 0, 0 to 1, 4 to 0, 4 to 1, 2 to 0, 3 to 0)

    /**
     * One built key.
     *
     * [scrim] is the black veil that reproduces the design's
     * `filter: brightness(.05)` while the key is sunk — see d_key_scrim.xml for
     * why it is an overlay rather than a colour filter.
     */
    class Key(
        val spec: Spec,
        val face: FrameLayout,
        val icon: ImageView,
        val scrim: View,
        val activeMark: View?,
    ) {
        /** The design's applyVars() night swap, for this one key. */
        fun applyNight(night: Boolean) {
            when (spec.face) {
                Face.NORMAL -> {
                    face.setBackgroundResource(
                        if (night) R.drawable.d_key_face_night else R.drawable.d_key_face
                    )
                    icon.setColorFilter(
                        ContextCompat.getColor(
                            icon.context,
                            if (night) R.color.d_icon_night else R.color.d_icon
                        ),
                        PorterDuff.Mode.SRC_IN,
                    )
                }
                // The brand mark is not a lamp that dims with the cabin.
                Face.BRAND -> Unit
            }
        }
    }

    fun columnX(c: Context, col: Int): Int = c.resources.getDimensionPixelSize(
        when (col) {
            0 -> R.dimen.d_col1_x
            1 -> R.dimen.d_col2_x
            2 -> R.dimen.d_col3_x
            3 -> R.dimen.d_col4_x
            else -> R.dimen.d_col5_x
        }
    )

    fun rowY(c: Context, row: Int): Int = c.resources.getDimensionPixelSize(
        if (row == 0) R.dimen.d_row1_y else R.dimen.d_row2_y
    )

    fun addSocket(parent: FrameLayout, col: Int, row: Int) {
        val r = parent.resources
        val pad = r.getDimensionPixelSize(R.dimen.d_socket_pad)
        val v = View(parent.context).apply { setBackgroundResource(R.drawable.d_socket) }
        parent.addView(v, FrameLayout.LayoutParams(
            r.getDimensionPixelSize(R.dimen.d_socket_w),
            r.getDimensionPixelSize(R.dimen.d_socket_h),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = columnX(parent.context, col) - pad
            topMargin = rowY(parent.context, row) - pad
        })
    }

    /**
     * A carbon plate sitting in a socket, cropped from [carbon] at its own screen
     * position so the twill runs continuously through the recess.
     *
     * Built with the same face/scrim pair as a key so that [ModeSwitch] can move
     * it with the same code. Plates ride up with the M console but never snap
     * out: in home mode they are simply an opaque key's worth of black, sunk
     * inside a black socket, underneath an opaque key.
     */
    fun addPlate(parent: FrameLayout, col: Int, row: Int, carbon: Bitmap?): Plate {
        val ctx = parent.context
        val r = parent.resources
        val x = columnX(ctx, col)
        val y = rowY(ctx, row)

        val container = FrameLayout(ctx)
        val plate = PlateView(ctx).also { carbon?.let { bmp -> it.setSource(bmp, x, y) } }
        container.addView(plate, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val scrim = View(ctx).apply {
            setBackgroundResource(R.drawable.d_plate_scrim)
            alpha = ModeSwitch.SCRIM_ALPHA
        }
        container.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        parent.addView(container, FrameLayout.LayoutParams(
            r.getDimensionPixelSize(R.dimen.d_key_w),
            r.getDimensionPixelSize(R.dimen.d_key_h),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = x
            topMargin = y
        })

        // Stagger 0: the design gives the plates --m3-pl-d with no per-column
        // offset, so both pairs rise together while the keys cascade.
        return Plate(col, row, plate, ModeSwitch.Panel(container, scrim, 0L))
    }

    class Plate(
        val col: Int,
        val row: Int,
        val view: PlateView,
        val panel: ModeSwitch.Panel,
    )

    fun addKey(parent: FrameLayout, spec: Spec): Key {
        val ctx = parent.context
        val r = parent.resources

        val face = FrameLayout(ctx).apply {
            setBackgroundResource(
                when (spec.face) {
                    Face.NORMAL -> R.drawable.d_key_face
                    Face.BRAND -> R.drawable.d_key_face_m
                }
            )
            isClickable = true
            isFocusable = true
        }

        // The M key carries the brand mark at its own aspect; every other key
        // carries a square icon.
        val brand = spec.face == Face.BRAND
        val icon = ImageView(ctx).apply {
            setImageResource(spec.iconRes)
            if (brand) adjustViewBounds = true
        }
        val iconSize = r.getDimensionPixelSize(R.dimen.d_icon_size)
        face.addView(icon, FrameLayout.LayoutParams(
            if (brand) r.getDimensionPixelSize(R.dimen.d_m_logo_w) else iconSize,
            if (brand) FrameLayout.LayoutParams.WRAP_CONTENT else iconSize,
        ).apply { gravity = Gravity.CENTER })

        // The status lamp, top-left inside the key. Present in both states — dark
        // when out, lit when on — so nothing shifts and the key never looks like
        // a different key depending on its state.
        var mark: View? = null
        if (spec.hasLamp) {
            mark = View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.d_act_off))
            }
            face.addView(mark, FrameLayout.LayoutParams(
                r.getDimensionPixelSize(R.dimen.d_act_w),
                r.getDimensionPixelSize(R.dimen.d_act_h),
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = r.getDimensionPixelSize(R.dimen.d_act_x)
                topMargin = r.getDimensionPixelSize(R.dimen.d_act_y)
            })
        }

        // Last child, so it veils the icon and the mark as well as the face.
        val scrim = View(ctx).apply {
            setBackgroundResource(R.drawable.d_key_scrim)
            alpha = 0f
        }
        face.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        parent.addView(face, FrameLayout.LayoutParams(
            r.getDimensionPixelSize(R.dimen.d_key_w),
            r.getDimensionPixelSize(R.dimen.d_key_h),
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = columnX(ctx, spec.col)
            topMargin = rowY(ctx, spec.row)
        })

        return Key(spec, face, icon, scrim, mark)
    }

}
