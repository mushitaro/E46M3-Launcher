package app.tsunagi.e46m3.launcher.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.tsunagi.e46m3.launcher.R

/**
 * Puts this launcher's carbon field behind the boot screen.
 *
 * ## What is actually on screen there
 *
 * Between the boot animation ending and this launcher drawing its first frame,
 * the unit shows `com.android.settings/.FallbackHome` — the lowest-priority
 * HOME on the device (`android:priority="-1000"` on its MAIN/HOME filter) that
 * ActivityManager falls back to while the boot finishes. Read off this unit's
 * own `MtkSettings.apk` rather than assumed, its theme is
 *
 *     style/FallbackHome
 *       android:windowBackground          @…
 *       android:windowNoTitle             true
 *       android:windowShowWallpaper       true      <-- this one
 *       android:colorBackgroundCacheHint  null
 *
 * so what fills that screen is **the Android system wallpaper** — which is also
 * what the old home screen showed, hence "the original wallpaper". The spinner
 * over it is that same APK's `layout/fallback_home_finishing_boot`.
 *
 * Setting the system wallpaper therefore changes it, and the same one line
 * covers the other candidate without having to tell them apart: the OEM
 * launcher is still installed and its theme descends from the framework's
 * `Theme.Wallpaper.NoTitleBar`, so if that is what flashes up instead, it draws
 * the same wallpaper.
 *
 * ## What this cannot reach, and why
 *
 *  - **The spinner.** It is FallbackHome's own view inside its own process.
 *  - **The boot logo.** That is the logo partition, before Android exists. The
 *    vendor's own `com.ts.logoset` changes it and is installed on this unit —
 *    and it now appears in our app list, since the MYLAUNCHER fix.
 *  - **The boot animation.** `/system/media/bootanimation.zip`, and /system is
 *    read-only without root, which this project does not assume.
 *
 * ## Once per install, off the main thread
 *
 * Writing a wallpaper re-encodes it and broadcasts a system-wide change, so it
 * is not something to do on every start. It is keyed on the package's own
 * `lastUpdateTime`, which re-applies exactly when a new build lands and
 * therefore whenever `carbon_bg.png` has been regenerated — no constant to
 * remember to bump.
 *
 * The day weave is used, not the night one. This is a second or two of a boot,
 * a wallpaper is one image rather than a pair, and both are dark enough that
 * the difference does not read at a glance.
 *
 * This is a system-wide setting and outlives the app. It is reversible from the
 * unit's own wallpaper picker (`com.android.wallpaperpicker` is installed).
 */
object BootWallpaper {

    fun applyIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (stamp != 0L && prefs.getLong(KEY_APPLIED, -1L) == stamp) return

        val manager = WallpaperManager.getInstance(context) ?: return
        // ARGB_8888, unlike the on-screen copy in HomeActivity, which takes
        // RGB_565 to halve a bitmap it holds for the life of the process. This
        // one is decoded once per install on a worker thread and handed
        // straight to the system, so the 2.4MB is momentary — and 565 is a bad
        // trade here for a second reason: five and six bits quantise a weave
        // made almost entirely of near-blacks into visible bands, and the
        // system re-encodes whatever it is given. Measured against the source,
        // 565 cost a mean of 2.7 levels per channel; on tones this dark that is
        // the difference between a weave and steps.
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.carbon_bg, opts)
        }.getOrNull() ?: return

        val ok = runCatching {
            // Without this the system keeps whatever size it was asked for
            // previously — typically twice the screen width, so a scrolling
            // launcher can pan across it — and ours would be stretched 2:1.
            // The panel is 1024x600 and so is the image; say so and it lands
            // pixel for pixel, which is the whole reason the weave was baked at
            // full size in the first place.
            manager.suggestDesiredDimensions(bitmap.width, bitmap.height)
            manager.setBitmap(bitmap)
        }.onFailure { Log.w(TAG, "could not set the boot wallpaper", it) }.isSuccess

        if (ok) {
            Log.i(TAG, "boot wallpaper set to the carbon field")
            prefs.edit().putLong(KEY_APPLIED, stamp).apply()
        }
    }

    private const val TAG = "BootWallpaper"
    private const val PREFS = "boot_wallpaper"
    private const val KEY_APPLIED = "applied_for_update"
}
