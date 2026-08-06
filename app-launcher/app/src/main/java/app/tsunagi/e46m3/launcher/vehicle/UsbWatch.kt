package app.tsunagi.e46m3.launcher.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * What is currently plugged into the head unit's USB host port.
 *
 * Three facts come off the same enumeration, so they come off one watcher.
 */
data class UsbBus(
    /** A K+DCAN diagnostic cable. Gates the M key. */
    val diagnosticCable: Boolean = false,
    /** An Apple device. Stands in for "CarPlay" — see [UsbWatch]. */
    val applePhone: Boolean = false,
    /** A phone the OEM link app recognises, Apple excluded. Stands in for "Android link". */
    val androidPhone: Boolean = false,
)

/**
 * Watches the USB host bus and reports what is on it.
 *
 * ## The cable, and why its match is deliberately loose
 *
 * The M key is gated on a K+DCAN cable being present: with no cable there is no
 * diagnostic link, so the menu behind it is covered by a blanking plate rather
 * than sitting there inert. It reports that **a cable is present**, not that the
 * car is awake, not that the DME answers, and not that anything has been read.
 *
 * The two failure directions are not symmetric. A false negative hides the M
 * menu with the cable plugged in — the owner loses access to their own tools and
 * has no way to tell why. A false positive shows the button because some other
 * FTDI device is attached, and pressing it opens a menu whose contents are
 * harmless without a link. So this accepts FTDI's whole vendor ID rather than a
 * PID whitelist, plus the CH340 that clone cables use.
 *
 * ## The phones, and exactly what the two lamps therefore mean
 *
 * The CarPlay and Android-link keys carry status lamps that are supposed to
 * report whether that link is up. **This unit exposes no such state, and that
 * was established rather than assumed:**
 *
 *  - `com.ts.carplayapp` (CarPlay_ts.apk) declares two actions, both of them
 *    outbound media/phone controls. No connection state. **[V]**
 *  - `CarplayService.apk` has the state internally — its strings include
 *    `getConnectionState currentState:` and a `CarplaySessionStateMachine` —
 *    but publishes nothing, and its `CarplayManager` lives in the vendor
 *    framework, which a normal app cannot load. **[V]**
 *  - `EasyConnect.apk` (the Android link) broadcasts nothing about its session
 *    either; the only relevant thing it registers for is USB attach/detach. **[V]**
 *  - MainUI itself does not track a CarPlay connection: it calls
 *    `openApplication("com.ts.carplayapp")` and forwards media keys, nothing
 *    more. **[V]**
 *
 * So the honest observable is the bus. These two flags mean **"a phone of that
 * kind is plugged in"**, which on this unit is the precondition for the link and
 * in practice the thing that starts it — the head unit brings CarPlay up on
 * connect. It is one step short of "the session is established", and it will be
 * lit by a phone that is only charging. That is the known limit of the signal,
 * and it is written down here rather than implied by a lamp.
 *
 * The vendor list is not invented: it is `res/xml/device_filter.xml` out of
 * EasyConnect.apk, verbatim — 64 vendor IDs, the OEM link app's own definition
 * of a phone it can talk to. Apple is in that list and is split out, because on
 * this unit an iPhone means CarPlay (`ro.atc.carplay.support=1`) rather than the
 * Android link.
 *
 * FTDI (0x0403) and WCH (0x1A86) are not in the phone list, so the diagnostic
 * cable can never light a link lamp.
 */
class UsbWatch(
    private val context: Context,
    private val onChange: (UsbBus) -> Unit,
) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    var bus: UsbBus = UsbBus()
        private set

    val isCableAttached: Boolean get() = bus.diagnosticCable

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    fun start() {
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            })
        }.onFailure { Log.w(TAG, "USB attach broadcasts unavailable", it) }
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Re-reads the bus. Cheap, and safe to call on the main thread. */
    fun refresh() {
        val now = runCatching { read() }.getOrElse {
            // No USB host support, or the service is unavailable. Absent, not
            // an error: the M key stays covered and both link lamps stay dark.
            Log.w(TAG, "could not enumerate USB devices", it)
            UsbBus()
        }
        if (now == bus) return
        bus = now
        Log.i(TAG, "USB bus: $now")
        onChange(now)
    }

    private fun read(): UsbBus {
        val devices = usb?.deviceList?.values ?: return UsbBus()
        return UsbBus(
            diagnosticCable = devices.any { isDiagnosticCable(it.vendorId, it.productId) },
            applePhone = devices.any { it.vendorId == VID_APPLE },
            androidPhone = devices.any { isLinkablePhone(it.vendorId) },
        )
    }

    companion object {
        private const val TAG = "UsbWatch"

        /** FTDI — FT232R and friends. K+DCAN cables are usually FT232RL. */
        const val VID_FTDI = 0x0403

        /** WCH CH340/CH341, what most clone cables carry instead. */
        const val VID_WCH = 0x1A86

        const val VID_APPLE = 0x05AC

        @Suppress("UNUSED_PARAMETER")
        fun isDiagnosticCable(vendorId: Int, productId: Int): Boolean =
            vendorId == VID_FTDI || vendorId == VID_WCH

        /** In the OEM link app's list, and not Apple (which means CarPlay here). */
        fun isLinkablePhone(vendorId: Int): Boolean =
            vendorId != VID_APPLE && vendorId in PHONE_VENDORS

        /**
         * EasyConnect.apk `res/xml/device_filter.xml`, transcribed whole.
         *
         * Kept as the vendor's list rather than curated down: it is the OEM's
         * own answer to "is this a phone I can link to", and second-guessing it
         * would only produce a launcher that disagrees with the app it is
         * reporting on.
         */
        private val PHONE_VENDORS: Set<Int> = setOf(
            0x03FC, 0x0408, 0x0409, 0x0414, 0x0451, 0x0471, 0x0482, 0x0489,
            0x04C5, 0x04DA, 0x04DC, 0x04E8, 0x0502, 0x054C, 0x05AC, 0x05C6,
            0x091E, 0x0955, 0x0B05, 0x0BB4, 0x0E79, 0x0E8D, 0x0F1C, 0x0FCE,
            0x1004, 0x109B, 0x10A9, 0x1219, 0x12D1, 0x1662, 0x16D5, 0x17EF,
            0x18D1, 0x1949, 0x19A5, 0x19D1, 0x19D2, 0x1BBB, 0x1D45, 0x1D4D,
            0x1EBF, 0x1F53, 0x2006, 0x201E, 0x2080, 0x2116, 0x2237, 0x2257,
            0x22B8, 0x22D9, 0x2314, 0x2340, 0x2420, 0x24E3, 0x25E3, 0x2717,
            0x2836, 0x2A45, 0x2A70, 0x2B0E, 0x2D95, 0x413C, 0x8087, 0xE040,
        )
    }
}
