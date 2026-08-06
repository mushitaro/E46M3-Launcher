package app.tsunagi.e46m3.launcher.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the USB watcher that can be checked without a car: which vendor
 * IDs mean a diagnostic cable, an iPhone, or a phone the OEM link app knows.
 *
 * The asymmetry these tests pin down for the cable is that a miss hides the
 * owner's M menu with the cable plugged in and gives no clue why, whereas an
 * over-match shows a button whose menu is harmless without a link. So the whole
 * FTDI vendor is accepted rather than a PID whitelist.
 *
 * For the phones the risk is different and the tests are aimed at it: the two
 * link lamps and the M key read the same bus, so a vendor that lands in two
 * buckets would light a lamp for a cable, or hide the M key behind a phone.
 */
class UsbWatchTest {

    @Test
    fun `accepts the FT232R most K+DCAN cables carry`() {
        assertTrue(UsbWatch.isDiagnosticCable(0x0403, 0x6001))
    }

    @Test
    fun `accepts other FTDI parts, because the PID is not worth guessing`() {
        assertTrue(UsbWatch.isDiagnosticCable(0x0403, 0x6010))
        assertTrue(UsbWatch.isDiagnosticCable(0x0403, 0x6014))
        assertTrue(UsbWatch.isDiagnosticCable(0x0403, 0x6015))
    }

    @Test
    fun `accepts the CH340 that clone cables use`() {
        assertTrue(UsbWatch.isDiagnosticCable(0x1A86, 0x7523))
    }

    @Test
    fun `ignores everything else on the bus`() {
        assertFalse(UsbWatch.isDiagnosticCable(0x18D1, 0x4EE1))   // a Google phone
        assertFalse(UsbWatch.isDiagnosticCable(0x090C, 0x1000))   // a USB stick
        assertFalse(UsbWatch.isDiagnosticCable(0x0000, 0x0000))
    }

    @Test
    fun `recognises phones from the OEM link app's own list`() {
        assertTrue(UsbWatch.isLinkablePhone(0x18D1))    // Google
        assertTrue(UsbWatch.isLinkablePhone(0x04E8))    // Samsung
        assertTrue(UsbWatch.isLinkablePhone(0x12D1))    // Huawei
        assertTrue(UsbWatch.isLinkablePhone(0x22B8))    // Motorola
        assertTrue(UsbWatch.isLinkablePhone(0x2717))    // Xiaomi
    }

    /**
     * Apple is in EasyConnect's list but must NOT light the Android-link lamp:
     * on this unit an iPhone means CarPlay. Both lamps lighting for one phone
     * would be the clearest possible way to say the lamps mean nothing.
     */
    @Test
    fun `an iPhone is CarPlay and not the Android link`() {
        assertTrue(0x05AC == UsbWatch.VID_APPLE)
        assertFalse(UsbWatch.isLinkablePhone(UsbWatch.VID_APPLE))
    }

    /**
     * The M key and the link lamps read the same enumeration, so an overlap
     * would mean plugging in the diagnostic cable also claimed a phone link.
     */
    @Test
    fun `the diagnostic cable is not a phone`() {
        assertFalse(UsbWatch.isLinkablePhone(UsbWatch.VID_FTDI))
        assertFalse(UsbWatch.isLinkablePhone(UsbWatch.VID_WCH))
    }

    @Test
    fun `an unknown vendor is neither`() {
        assertFalse(UsbWatch.isLinkablePhone(0x090C))              // a USB stick
        assertFalse(UsbWatch.isDiagnosticCable(0x090C, 0x1000))
    }
}
