package app.tsunagi.e46m3.launcher.vehicle

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.ts.can.carinfo.ICarInfoService
import com.ts.main.common.ITsCommon

/** One sample of what the vehicle stack will tell us. Null means "not available". */
data class VehicleData(
    val outsideTempC: Float? = null,
    val speedKmh: Float? = null,
    val reverse: Boolean? = null,
    /** From ICarInfoService. Not wired to the tachometer — see [VehicleLink]. */
    val rpm: Int? = null,
    /**
     * Whether a phone is connected over Bluetooth.
     *
     * The one link on this unit whose state is genuinely published: the OEM
     * stack answers it directly. Both other links (CarPlay, the Android link)
     * have to be inferred from the USB bus — see UsbWatch.
     */
    val btConnected: Boolean = false,
)

/**
 * Reads vehicle state from the OEM stack over its own binder interface.
 *
 * How this was found: the OEM launcher (`com.android.launcher`, uid 10015 — an
 * ORDINARY app, not system) binds `android.intent.action.MAIN_UI` and casts the
 * result to `com.ts.main.common.ITsCommon`. That is existence proof the
 * interface is reachable without system privileges or root, and `ITsCommon`
 * carries `GetTemp()`, `GetSpeed()`, `GetReverState()` and `GetBrakeState()`.
 *
 * Two things the OEM launcher does that we must NOT copy:
 *
 *  1. It binds with an **implicit** intent. That is illegal from API 21 onward
 *     and throws IllegalArgumentException; the OEM app gets away with it only
 *     because it targets SDK 15. We set the package explicitly.
 *  2. It calls binder methods on whatever thread it happens to be on. Binder
 *     transactions are synchronous IPC into another process — if MainUI is busy
 *     or wedged, that blocks the caller. On a HOME app that means a frozen home
 *     screen, so every call here runs on a dedicated worker thread.
 *
 * ## Two interfaces, not one
 *
 * `ITsCommon` is not the only way into MainUI, and reading only it produced a
 * wrong conclusion that stood for a while: that this unit has no outside
 * temperature at all. `ITsCommon.GetTemp()` reads `Can.mOutTemp`, a dedicated
 * `CAN_OutTmp` store filled by `CanJni.GetOutTemp()`, and on this car that store
 * is never filled.
 *
 * `ICarInfoService` is a **second exported service** over a **different** store —
 * `CanFunc.mCarInfo`, a `CAN_Msg` filled by `CanJni.GetCarInfoAidl()` — and it
 * carries `OutTemp`, `Rpm`, `WaterTemp`, `OilTemp`, `BatV`, speed, doors, lights
 * and the VIN in one `int[69]`. MainUI's own BMW screens read that structure
 * (`CanBMWLzYbxxView` uses `mCanMsg.OutTemp`), which makes it the channel BMW
 * data actually travels on.
 *
 * One store being empty says nothing about the other. Both are bound here.
 */
class VehicleLink(
    private val context: Context,
    private val onData: (VehicleData) -> Unit,
) {
    private var service: ITsCommon? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    private var carInfo: ICarInfoService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = runCatching { ITsCommon.Stub.asInterface(binder) }.getOrNull()
            Log.i(TAG, "bound to MainUI: ${service != null}")
            repoll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            // The stack went away; say so rather than leaving a stale reading on
            // screen claiming to be live.
            main.post { onData(VehicleData()) }
        }
    }

    /**
     * The OEM Bluetooth stack announcing that a connection came or went.
     *
     * `BtExe.sendConnectStateChange()` is a plain `sendBroadcast` with no
     * permission attached **[V]**, so this is receivable by anyone — and it has
     * to be received, because a 15-second poll is far too slow for a lamp that
     * is supposed to answer "is my phone connected". The poll still runs; this
     * only pulls the next one forward.
     *
     * Note the standard `BluetoothAdapter` is NOT an option here: this unit sets
     * `ro.atc.disable_android_bt=1` **[V]** and runs Bluetooth on its own module
     * behind the MCU, so the framework adapter knows nothing about it.
     */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            repoll()
        }
    }

    /** Polls now, keeping exactly one chain alive rather than starting a second. */
    private fun repoll() {
        handler?.removeCallbacksAndMessages(null)
        handler?.post(::poll)
    }

    private val carInfoConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            carInfo = runCatching { ICarInfoService.Stub.asInterface(binder) }.getOrNull()
            Log.i(TAG, "bound to CarInfoService: ${carInfo != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carInfo = null
        }
    }

    fun start() {
        if (running) return
        running = true
        worker = HandlerThread("vehicle-link").apply { start() }
        handler = Handler(worker!!.looper)

        runCatching {
            context.registerReceiver(btReceiver, IntentFilter(ACTION_BT_CONNECT_STATE))
        }.onFailure { Log.w(TAG, "BT state broadcast unavailable", it) }

        // The second interface. Separate service, separate data store; see
        // CarInfo.kt for why one being empty says nothing about the other.
        runCatching {
            context.bindService(
                Intent(ACTION_CAR_INFO).setPackage(PKG_MAIN_UI),
                carInfoConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure { Log.w(TAG, "CarInfoService bind threw", it) }

        val intent = Intent(ACTION_MAIN_UI).setPackage(PKG_MAIN_UI)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "bindService threw", it); false
        }
        if (!ok) {
            // No MainUI on this build (an emulator, say). Not an error — the
            // readouts simply stay empty, which is the honest display.
            Log.i(TAG, "MainUI not available; telemetry stays empty")
            stop()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(btReceiver) }
        runCatching { context.unbindService(connection) }
        runCatching { context.unbindService(carInfoConnection) }
        worker?.quitSafely()
        worker = null
        handler = null
        service = null
        carInfo = null
    }

    private fun poll() {
        if (!running) return

        val car = readCarInfo()
        val svc = service
        if (svc != null || car != null) {
            val data = VehicleData(
                // CarInfoService first. ITsCommon.GetTemp() reads a different
                // store that the CAN layer never fills on this unit, so it is
                // the fallback rather than the source.
                outsideTempC = car?.outsideTempC ?: svc?.let { readTemp(it) },
                speedKmh = svc?.let { runCatching { it.GetSpeed() }.getOrNull() },
                reverse = svc?.let { runCatching { it.GetReverState() != 0 }.getOrNull() },
                rpm = car?.rpm,
                btConnected = svc?.let { runCatching { it.BtIsConnect() }.getOrNull() } ?: false,
            )
            main.post { onData(data) }
        }
        handler?.postDelayed(::poll, POLL_MS)
    }

    /**
     * One `requestCarBaseInfo()` call, plus a raw dump of the array.
     *
     * The dump is deliberate and stays: the array has 69 slots, this app reads
     * five of them, and there is no other way to find out which of the rest this
     * particular car actually populates. It is one line every 15 seconds.
     */
    private fun readCarInfo(): CarInfo? {
        val svc = carInfo ?: return null
        val raw = runCatching { svc.requestCarBaseInfo() }
            .onFailure { Log.w(TAG, "requestCarBaseInfo threw", it) }
            .getOrNull() ?: return null
        Log.i(TAG, "carBaseInfo=${raw.joinToString(",")}")
        return CarInfo.parse(raw)
    }

    /**
     * `GetTemp()` returns a formatted, localised String — never a bare number.
     * From MainUI's own implementation and its string resources:
     *
     *     "Out temp: 12℃"   "车外温度: 12℃"   "車外溫度: 12℃"
     *     unit resources: °C  °F  ℃  ℉
     *
     * and it returns **null** when the CAN stack has never delivered a value
     * (`mOutTemp.UpdateOnce == 0`). null is a normal answer, not a failure.
     *
     * The trap: MainUI has a `DW` flag that switches the unit, so this string
     * can legitimately be in FAHRENHEIT. Parsing out the digits and calling the
     * result Celsius would silently show 68°F as "68°C" on a instrument that
     * claims to read °C. So the unit is read from the string and converted.
     */
    private fun readTemp(svc: ITsCommon): Float? {
        val call = runCatching { svc.GetTemp() }
        // Logged verbatim because the failure modes are indistinguishable on
        // screen: a null return (CAN has never delivered a value), an empty
        // string, a string with no digits, or a plausible-range rejection all
        // show `--.-`. On the car this is the only way to tell which.
        Log.i(TAG, "GetTemp() -> ${call.exceptionOrNull()?.let { "threw $it" } ?: "\"${call.getOrNull()}\""}")

        val raw = call.getOrNull()?.trim() ?: return null
        val number = NUMBER.find(raw.replace(",", "."))?.value?.toFloatOrNull() ?: return null
        val celsius = if (raw.contains('℉') || raw.contains("°F", ignoreCase = true)) {
            (number - 32f) * 5f / 9f
        } else {
            number
        }
        // Refuse an implausible reading rather than putting a garbage number on
        // the instrument. A wrong number is worse than no number.
        return if (celsius in PLAUSIBLE_TEMP) celsius else null
    }

    companion object {
        private const val TAG = "VehicleLink"
        private const val ACTION_MAIN_UI = "android.intent.action.MAIN_UI"
        private const val ACTION_CAR_INFO = "com.ts.can.carinfo.CarInfoService"
        private const val ACTION_BT_CONNECT_STATE = "com.ts.bt.CONNECT_STATE_CHANGE"
        private const val PKG_MAIN_UI = "com.ts.MainUI"
        private const val POLL_MS = 15_000L
        private val PLAUSIBLE_TEMP = -60f..90f
        private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    }
}
