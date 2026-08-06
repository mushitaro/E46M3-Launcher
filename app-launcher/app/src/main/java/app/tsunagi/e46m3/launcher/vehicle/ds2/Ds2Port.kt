package app.tsunagi.e46m3.launcher.vehicle.ds2

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.Closeable

/**
 * One DS2 exchange over the K+DCAN cable.
 *
 * ## Why a library sits under this
 *
 * The bytes of DS2 are ours ([Ds2]); the bytes of USB-serial are not. Two
 * details of the FTDI parts these cables carry corrupt data quietly rather than
 * loudly, and both are already solved upstream:
 *
 *  - **Every read packet begins with two modem-status bytes.** They are not
 *    payload. Feeding them to a frame parser shifts everything by two and the
 *    checksums simply stop matching, which reads as "the car is not answering".
 *  - **Baud divisors are encoded differently per chip family** (FT232AM vs
 *    BM/R vs H), and a clone cable may be a CH340 instead. Getting it wrong
 *    produces a link that opens successfully and returns nothing.
 *
 * ## 8E1
 *
 * DS2 is 9600 8**E**1. Even parity, not none — a detail worth stating because
 * the surrounding OBD world is overwhelmingly 8N1 and the mistake looks like
 * intermittent corruption rather than a configuration error.
 *
 * ## Read-only
 *
 * [Ds2] can only encode READ_IO_STATUS and a keep-alive, and this class only
 * transmits what [Ds2] builds. Nothing here can write to an ECU.
 */
class Ds2Port(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
) : Closeable {

    private val framer = Ds2Framer()
    private val readBuffer = ByteArray(READ_CHUNK)

    fun open() {
        port.open(connection)
        port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
        port.dtr = true
        port.rts = true
        setLatencyTimer()
        purge()
    }

    /**
     * FTDI buffers reads for 16ms by default before handing them over. On a
     * request/response protocol that is 16ms added to every single exchange, for
     * no benefit — the answers are short and we want them as they land.
     *
     * Reached by reflection because only the FTDI driver has it: a CH340 clone
     * cable exposes no such control, and a hard reference would not compile
     * against the other drivers. Failure is fine; it costs latency, not
     * correctness.
     */
    private fun setLatencyTimer() {
        runCatching {
            port.javaClass
                .getMethod("setLatencyTimer", Int::class.javaPrimitiveType)
                .invoke(port, LATENCY_MS)
        }.onFailure { Log.d(TAG, "no latency timer control on this cable (harmless)") }
    }

    /** Drops anything the bus left behind, so an old answer cannot look like a new one. */
    fun purge() {
        framer.reset()
        val until = System.currentTimeMillis() + PURGE_MS
        while (System.currentTimeMillis() < until) {
            val n = runCatching { port.read(readBuffer, PURGE_MS.toInt()) }.getOrDefault(0)
            if (n <= 0) break
        }
    }

    /**
     * Sends [request] and waits for the ECU's answer.
     *
     * @return the answering frame, or null on timeout — which is an ordinary
     *   event (ignition off, cable half-seated, ECU asleep), not an error.
     */
    fun transceive(request: ByteArray, control: Int, timeoutMs: Int = READ_TIMEOUT_MS): Ds2.Frame? {
        framer.reset()
        framer.expectEcho(request)

        runCatching { port.write(request, WRITE_TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "write failed", it); return null }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val n = runCatching { port.read(readBuffer, remaining) }
                .onFailure { Log.w(TAG, "read failed", it); return null }
                .getOrDefault(0)
            if (n > 0) framer.feed(readBuffer, n)

            while (true) {
                val frame = framer.next() ?: break
                // The echo, if suppression missed it because junk arrived first:
                // it carries the control byte we sent where a reply carries ACK.
                if (frame.status == control) continue
                if (frame.status == Ds2.STATUS_BUSY) continue
                return frame
            }
        }
        return null
    }

    override fun close() {
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "Ds2Port"

        /** DS2 on the E46 K-line. */
        const val BAUD = 9600

        private const val LATENCY_MS = 2
        private const val WRITE_TIMEOUT_MS = 300
        private const val READ_TIMEOUT_MS = 300
        private const val PURGE_MS = 30L
        private const val READ_CHUNK = 256
    }
}
