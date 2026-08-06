package app.tsunagi.e46m3.launcher

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Claims the K+DCAN cable's attach intent, and does nothing else.
 *
 * ## Why this exists at all
 *
 * Torque Pro also registers for `USB_DEVICE_ATTACHED` on FTDI devices, and it
 * had been chosen as the default, so plugging the diagnostic cable in launched
 * Torque over whatever was on screen. Whoever holds that intent decides what
 * happens on connect, so the fix is to hold it.
 *
 * It is also how USB permission stops being a dialog. The system grants the
 * package that handles a device's attach intent persistent access to it, which
 * removes the "allow this app to access the USB device?" prompt that would
 * otherwise appear every time the cable goes in — on a screen the driver is
 * meant to glance at, not answer questions from.
 *
 * ## Why it is not HomeActivity
 *
 * Putting the filter on the home screen would work, but the home screen would
 * then be brought to the front on every connect. Plugging in a diagnostic cable
 * should not eject you from the radio. This activity has no window, finishes
 * before it is ever composed, and stays out of Recents.
 *
 * Nothing is signalled to the launcher from here: KdcanLink already listens for
 * the same attach and detach broadcasts and updates the M key by itself.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "diagnostic cable attached")
        // A NoDisplay activity must be gone before it would resume, or the
        // framework kills the process for showing nothing.
        finish()
    }

    private companion object {
        const val TAG = "UsbAttach"
    }
}
