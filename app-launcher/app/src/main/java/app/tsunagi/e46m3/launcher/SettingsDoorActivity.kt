package app.tsunagi.e46m3.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Opens the head unit's Wi-Fi or date-and-time settings for a web tool, and does nothing else.
 *
 * ## Why the launcher, and not the page
 *
 * The owner asked for a button in MONITORING's header that goes straight to the
 * Wi-Fi settings (the phone's tether drops) and one to the clock (the unit wakes
 * in 2006, and a page whose clock is years out cannot sign in). A page cannot
 * do this: Chrome launches only activities that declare themselves BROWSABLE,
 * and the Settings app declares none of its screens so. This activity is
 * BROWSABLE, takes `manifold://settings/wifi` and `manifold://settings/datetime`
 * — which a page reaches as
 * `intent://settings/wifi#Intent;scheme=manifold;package=app.tsunagi.e46m3.launcher;end`
 * — and starts the settings screen itself.
 *
 * A tool offers the buttons only when the launcher that opened it puts
 * `doors=settings` on its URL ([launch.TargetLauncher]), so a unit still on an
 * older launcher never shows a button that does nothing.
 *
 * Any page could open this link. All it does is show a settings screen, which
 * the person in the seat then uses or leaves; nothing is changed from here.
 *
 * No window, gone before it would resume, out of Recents — like [UsbAttachActivity].
 */
class SettingsDoorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val door = intent?.data?.path?.trim('/')
        val action = when (door) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "datetime" -> Settings.ACTION_DATE_SETTINGS
            else -> null
        }
        if (action == null) {
            Log.w(TAG, "no such door: $door")
        } else {
            open(action) || open(Settings.ACTION_SETTINGS)
        }
        finish()
    }

    /** Starts one settings screen; false when this unit's firmware has none for it. */
    private fun open(action: String): Boolean = try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for $action", e)
        false
    }

    private companion object {
        const val TAG = "SettingsDoor"
    }
}
