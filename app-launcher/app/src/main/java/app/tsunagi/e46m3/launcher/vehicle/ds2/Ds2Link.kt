package app.tsunagi.e46m3.launcher.vehicle.ds2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber

/** One poll of MSS54 live block 3. Every field is null when the DME did not answer. */
data class Mss54Sample(
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val oilC: Int? = null,
    val intakeC: Int? = null,
    /** Outside air. The DME relays it from the bus — see [Mss54Block3.ambientC]. */
    val ambientC: Int? = null,
    val batteryV: Float? = null,
)

/**
 * Polls the DME for live block 3.
 *
 * ## Two paces, because two readouts want different things
 *
 * The tachometer wants engine speed as fast as the link goes. The home screen's
 * outside-temperature slot wants one number that moves over minutes. Both come
 * out of the same 35-byte block, so the link runs at [POLL_MS] while the M
 * console is open and drops to [IDLE_POLL_MS] when it is not, rather than
 * stopping.
 *
 * The earlier version stopped outright, on the argument that holding a
 * conversation on a shared single wire to feed a gauge nobody is looking at is
 * traffic with no reader. That argument still holds for 11Hz and is why the
 * pace drops by a factor of 300 — but it stopped applying the moment the block
 * turned out to carry the outside temperature too, because then somebody *is*
 * looking, at the home screen.
 *
 * It still stops entirely in `onPause`. That matters more than it looks: TUNER
 * now reaches the DME over WebUSB from Chrome, and two processes cannot claim
 * the same USB device. Handing the cable back whenever this screen is not in
 * front is what keeps that possible.
 *
 * ## No reading is a normal state
 *
 * Ignition off, engine not running, cable half-seated, ECU asleep — all of these
 * produce silence, and silence is reported as `null` rather than as an error or,
 * worse, as a stale number. [FAILURES_BEFORE_DARK] consecutive silent polls
 * clear the instrument; a single dropped frame does not make the needle twitch.
 */
class Ds2Link(
    private val context: Context,
    private val onSample: (Mss54Sample) -> Unit,
) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val main = Handler(Looper.getMainLooper())

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var portOrNull: Ds2Port? = null
    private var running = false
    private var misses = 0

    /** True while the tachometer is on screen. See the class comment. */
    @Volatile
    private var fast = false

    /** A stable Runnable, so a queued poll can be cancelled — `::poll` cannot. */
    private val pollTask = Runnable { poll() }

    /**
     * Switches pace without restarting the link.
     *
     * Slowing down just lets the next poll land later. **Speeding up has to take
     * effect now**: the queued poll may be most of an idle period away, and the
     * gauge would sit blank for up to half a minute after the M console opened.
     *
     * The re-post is done *on the worker*, which is what makes it safe — no poll
     * can be part-way through while this runs, so cancelling the queued one
     * cannot leave two chains alive.
     */
    fun setPace(fast: Boolean) {
        if (this.fast == fast) return
        this.fast = fast
        if (!fast) return
        handler?.post {
            if (!running) return@post
            handler?.removeCallbacks(pollTask)
            poll()
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission ${if (granted) "granted" else "refused"}")
            if (granted) handler?.post(::openAndPoll) else main.post { onSample(Mss54Sample()) }
        }
    }

    fun start() {
        if (running) return
        running = true
        misses = 0
        worker = HandlerThread("ds2-link").apply { start() }
        handler = Handler(worker!!.looper)
        runCatching {
            context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        handler?.post(::openAndPoll)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(permissionReceiver) }
        handler?.removeCallbacksAndMessages(null)
        handler?.post {
            portOrNull?.close()
            portOrNull = null
            worker?.quitSafely()
        }
        worker = null
        handler = null
    }

    // ── worker thread from here down ─────────────────────────────────────────

    private fun openAndPoll() {
        if (!running) return
        val manager = usb ?: return report(Mss54Sample())

        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: return report(Mss54Sample())

        if (!manager.hasPermission(driver.device)) {
            // The system asks once. Until it is answered there is nothing to do
            // but wait — the receiver above resumes this.
            Log.i(TAG, "requesting USB permission for ${driver.device.deviceName}")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            runCatching { manager.requestPermission(driver.device, pending) }
            return
        }

        val connection = manager.openDevice(driver.device) ?: return report(Mss54Sample())
        val port = Ds2Port(driver.ports.first(), connection)
        val opened = runCatching { port.open() }
            .onFailure { Log.w(TAG, "could not open the cable", it) }
            .isSuccess
        if (!opened) {
            port.close()
            return report(Mss54Sample())
        }
        portOrNull = port
        Log.i(TAG, "K+DCAN open: ${driver.device.productName ?: driver.device.deviceName}")
        poll()
    }

    private fun poll() {
        if (!running) return
        val port = portOrNull ?: return

        val frame = port.transceive(
            Ds2.readBlock(Mss54Block3.SELECTION),
            Ds2.CTRL_READ_IO_STATUS,
        )
        val payload = frame?.payload
        if (payload == null || payload.size < Mss54Block3.PAYLOAD_LENGTH) {
            // A short payload is not a partial reading to be salvaged: the
            // offsets below would then point at whatever happened to follow.
            if (frame != null) Log.w(TAG, "block 3 was ${payload?.size} bytes, expected ${Mss54Block3.PAYLOAD_LENGTH}")
            if (++misses >= FAILURES_BEFORE_DARK) report(Mss54Sample())
        } else {
            misses = 0
            val sample = Mss54Sample(
                rpm = Mss54Block3.rpm(payload),
                coolantC = Mss54Block3.coolantC(payload),
                oilC = Mss54Block3.oilC(payload),
                intakeC = Mss54Block3.intakeC(payload),
                ambientC = Mss54Block3.ambientC(payload),
                batteryV = Mss54Block3.batteryV(payload),
            )
            traceOncePerSecond(sample)
            report(sample)
        }

        val next = when {
            misses > 0 -> RETRY_MS
            fast -> POLL_MS
            else -> IDLE_POLL_MS
        }
        handler?.postDelayed(pollTask, next)
    }

    private fun report(sample: Mss54Sample) {
        main.post { if (running) onSample(sample) }
    }

    /**
     * One line a second, not eleven.
     *
     * Only the engine speed is on screen, so the other four channels have no
     * other way to be checked — and they need checking: every offset in
     * [Mss54Block3] comes from a decompilation of MSS54DS0.prg, not from a
     * running car. Battery volts and coolant are the useful witnesses, because
     * a reader can tell at a glance whether 13.9 and 84 are the truth while
     * 139 and 8400 would not be.
     */
    private var lastTrace = 0L

    private fun traceOncePerSecond(s: Mss54Sample) {
        val now = System.currentTimeMillis()
        if (now - lastTrace < TRACE_MS) return
        lastTrace = now
        Log.i(
            TAG,
            "rpm=${s.rpm} coolant=${s.coolantC}C oil=${s.oilC}C intake=${s.intakeC}C ambient=${s.ambientC}C batt=${s.batteryV}V",
        )
    }

    companion object {
        private const val TAG = "Ds2Link"
        private const val ACTION_USB_PERMISSION = "app.tsunagi.e46m3.launcher.USB_PERMISSION"

        /**
         * 90ms, matching the rate the design's tachometer was drawn against. One
         * exchange is 5 bytes out and 39 back at 9600 8E1 — about 48ms of wire
         * time — so this is roughly as fast as the link goes.
         */
        private const val POLL_MS = 90L

        /**
         * The pace when the gauge is not on screen.
         *
         * The only reader then is the home screen's outside-temperature slot,
         * which shows whole degrees of air temperature — a quantity that cannot
         * move fast enough for this to be visible. One exchange per half minute
         * is about 0.3% of the wire.
         */
        private const val IDLE_POLL_MS = 30_000L

        /** Backed off while silent: nothing is moving, so nothing needs 11Hz. */
        private const val RETRY_MS = 1_000L

        /** One dropped frame is noise; three in a row is a link that is gone. */
        private const val FAILURES_BEFORE_DARK = 3

        /** Trace interval. The poll is 11Hz; a log line at 11Hz is not a log. */
        private const val TRACE_MS = 1_000L
    }
}
