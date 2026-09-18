package app.tsunagi.e46m3.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.apps.AppCatalog
import app.tsunagi.e46m3.launcher.apps.AppEntry
import app.tsunagi.e46m3.launcher.launch.LaunchOutcome
import app.tsunagi.e46m3.launcher.launch.LaunchTarget
import app.tsunagi.e46m3.launcher.launch.ResumeAfterRestart
import app.tsunagi.e46m3.launcher.launch.Step
import app.tsunagi.e46m3.launcher.launch.TargetLauncher
import app.tsunagi.e46m3.launcher.launch.Targets
import app.tsunagi.e46m3.launcher.ota.ConsoleState
import app.tsunagi.e46m3.launcher.ota.HomeGuard
import app.tsunagi.e46m3.launcher.ota.Relinquish
import app.tsunagi.e46m3.launcher.ota.Ota
import app.tsunagi.e46m3.launcher.ota.OtaController
import app.tsunagi.e46m3.launcher.ota.OtaSnapshot
import app.tsunagi.e46m3.launcher.ota.OtaState
import app.tsunagi.e46m3.launcher.ota.OtaStatus
import app.tsunagi.e46m3.launcher.ui.AppListLayer
import app.tsunagi.e46m3.launcher.ui.BootWallpaper
import app.tsunagi.e46m3.launcher.ui.ConsoleKeys
import app.tsunagi.e46m3.launcher.ui.DotMatrixView
import app.tsunagi.e46m3.launcher.ui.ModeSwitch
import app.tsunagi.e46m3.launcher.ui.OtaLayer
import app.tsunagi.e46m3.launcher.ui.RemoveLayer
import app.tsunagi.e46m3.launcher.ui.TachView
import app.tsunagi.e46m3.launcher.ui.WindowFrameView
import app.tsunagi.e46m3.launcher.vehicle.UsbBus
import app.tsunagi.e46m3.launcher.vehicle.UsbWatch
import app.tsunagi.e46m3.launcher.vehicle.ds2.Ds2Link
import app.tsunagi.e46m3.launcher.vehicle.ds2.Mss54Sample
import app.tsunagi.e46m3.launcher.vehicle.VehicleData
import app.tsunagi.e46m3.launcher.vehicle.VehicleLink
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The only Activity, implementing `E46M3 Launcher v2.dc.html`.
 *
 * Everything on screen is derived from a live query at render time, which is
 * what makes `android:stateNotNeeded="true"` honest: there is no state worth
 * saving, so a kill costs a sub-second restart rather than a blank home screen.
 *
 * Three faces, one window: the home console, the M console (the same fascia
 * with a different set of keys raised out of it), and the app list. None of them
 * is a second Activity, and none of them replaces the carbon field.
 */
class HomeActivity : Activity() {

    /**
     * Whether to hide the system bars.
     *
     * Off until the car can be checked. If the device has a navigation bar we
     * lose 48dp of 400 (12%), which is annoying. If it has no nav bar and no
     * physical Back key and we hide it anyway, a driver who opens Settings
     * cannot get back — which is a real problem in a moving car. The cheap
     * failure is the safe default.
     *
     *   adb shell dumpsys window | grep -i NavigationBar
     *   adb shell getevent -pl   | grep -iE "KEY_HOME|KEY_BACK"
     */
    private val immersive = false

    private lateinit var launcher: TargetLauncher
    private lateinit var vehicle: VehicleLink

    private lateinit var console: View
    private lateinit var carbonView: ImageView
    private lateinit var lcd: View
    private lateinit var tach: TachView
    private lateinit var lcdClock: DotMatrixView
    private lateinit var lcdTemp: DotMatrixView
    private lateinit var lcdDate: DotMatrixView
    private lateinit var lcdSource: DotMatrixView

    private val homeKeys = mutableListOf<ConsoleKeys.Key>()
    private val mKeys = mutableListOf<ConsoleKeys.Key>()
    private val plates = mutableListOf<ConsoleKeys.Plate>()

    private lateinit var modeSwitch: ModeSwitch
    private var appList: AppListLayer? = null

    /** The one key that comes and goes at runtime, and the blank behind it. */
    private lateinit var mKeyPanel: ModeSwitch.Panel
    private lateinit var mBlankPanel: ModeSwitch.Panel

    /** The USB host bus: gates the M key, and lights two of the three lamps. */
    private lateinit var usb: UsbWatch

    /** Engine speed over DS2. Only alive while the M console is showing. */
    private lateinit var ds2: Ds2Link

    private var carbon: Bitmap? = null
    private var night = false

    private val ui = Handler(Looper.getMainLooper())

    /**
     * The app catalogue, loaded off the main thread.
     *
     * One thread, not a pool: resolving a label opens the target APK's
     * resources, and doing four of those at once on four in-order cores with a
     * 192MB heap limit competes with the very screen we are trying to keep
     * responsive.
     */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "catalogue").apply { priority = Thread.MIN_PRIORITY }
    }
    private var catalogue: List<AppEntry> = emptyList()
    private var catalogueLoading = false

    /**
     * What the LCD's bottom-right line names: the last thing successfully
     * started from this console.
     *
     * This is NOT what lights the status lamps. It used to be, and it was the
     * wrong model — pressing a key made its lamp the only lit one, so the lamps
     * described the console's history rather than the car's state. See
     * [renderActiveMarks].
     */
    private var activeSource: String? = null

    /** Live link state. The lamps are a view of these two and nothing else. */
    private var btConnected = false
    private var bus = UsbBus()

    /** A transient message, shown in the LCD's source line as the design does. */
    private var notice: String? = null
    private val clearNotice = Runnable { notice = null; renderSourceLine() }

    /** See [ResumeAfterRestart] and [armResume]. */
    private lateinit var resumeAfterRestart: ResumeAfterRestart
    private var resumeTarget: String? = null
    private var resumeTicks = 0

    /**
     * When the DME last answered, on the monotonic clock.
     *
     * Only the self-update guard reads it. elapsedRealtime rather than the wall
     * clock for the usual reason on this unit: the RTC cannot be trusted, and
     * "was this within the last minute" must not become "was this before 2006".
     */
    private var lastEngineSampleAt: Long = 0L

    /** Finds published updates and verifies them. See [app.tsunagi.e46m3.launcher.ota.Ota]. */
    private lateinit var ota: OtaController

    /**
     * The delayed update check, held as a named Runnable so it can be taken
     * back.
     *
     * An anonymous lambda posted to [ui] survives onDestroy, and by then both
     * OTA executors have been shut down — so it would land on a
     * RejectedExecutionException, on the main thread, in the app that IS the
     * home screen. The crash handler here deliberately lets the process die,
     * which is the right call for a corrupted UI state and the wrong outcome
     * entirely for a missed update check.
     */
    private val otaCheck = Runnable { ota.checkIfDue() }

    /** Inflated only if this launcher ever stops being HOME. See [showLostHome]. */
    private var lostHome: View? = null

    /** The removal procedure. Inflated only if somebody long-presses for it. */
    private var removeLayer: RemoveLayer? = null
    private var removeState = RemoveLayer.State(done = 0)
    private var otaLayer: OtaLayer? = null

    /**
     * The OTA download's own thread, created only if a download ever starts.
     *
     * Deliberately not [io]. A three-megabyte transfer sitting in front of a
     * catalogue reload triggered by [packageReceiver] is a visible bug — the app
     * list would simply stop updating for the length of a download — and the two
     * jobs have nothing to say to each other. Minimum priority for the same
     * reason [io] is: neither may compete with the screen.
     */
    private val otaIo: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ota").apply { priority = Thread.MIN_PRIORITY }
        }
    }

    /**
     * The sticky half of the LCD source line: set while a verified update is
     * waiting, cleared when there is none.
     *
     * It outranks [activeSource], and that is a deliberate trade. The slot's
     * original job is to name the last thing launched from this console, which
     * is pleasant but not actionable; an update that is downloaded, verified and
     * waiting IS actionable, and the LCD is the one-tap way to reach it. A
     * transient [notice] still wins over both, so pressing a key still reports
     * what it did, and the update line returns a couple of seconds later.
     */
    private var otaNotice: String? = null

    /**
     * Outside air, from the head unit's own CAN service. Null on this car — see
     * docs §6.1.6 — and kept only because the code should not assume that.
     */
    private var outsideFromHeadUnit: Float? = null

    /**
     * Outside air, from the DME. `tumg`, relayed to it over the bus, and on this
     * car the one that actually arrives.
     */
    private var outsideFromDme: Int? = null

    /** Whichever source has a reading. The DME wins: it is the live one. */
    private val outsideTempC: Float?
        get() = outsideFromDme?.toFloat() ?: outsideFromHeadUnit

    /**
     * The M console's three engine temperatures, held here rather than in the
     * gauge because they and the outside air arrive from two different links at
     * two different rates and the gauge is handed all four at once.
     */
    private var coolantC: Int? = null
    private var oilC: Int? = null
    private var intakeC: Int? = null

    private val dateFormat = SimpleDateFormat("MM.dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dowFormat = SimpleDateFormat("EEE", Locale.US)

    /** Framework-driven, once a minute — cheaper and steadier than a 1 Hz timer. */
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderClock()
            refreshNightMode()
            // ACTION_TIME_CHANGED is what AlarmManagerService broadcasts after
            // an NTP sync, and on this unit that is usually the moment an RTC
            // reading 2006 becomes usable. A check parked for exactly that
            // reason restarts here — no second receiver, no polling.
            if (::ota.isInitialized && intent?.action == Intent.ACTION_TIME_CHANGED) {
                ota.onTimeChanged()
            }
        }
    }

    /** The app list is enumerated, not hard-coded, so it has to follow installs. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            catalogueLoading = false
            loadCatalogue()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home)

        console = findViewById(R.id.console)
        carbonView = findViewById(R.id.carbon)
        lcd = findViewById(R.id.lcd)
        tach = findViewById(R.id.tach)
        lcdClock = findViewById(R.id.lcd_clock)
        lcdTemp = findViewById(R.id.lcd_temp)
        lcdDate = findViewById(R.id.lcd_date)
        lcdSource = findViewById(R.id.lcd_source)

        night = isNight()
        loadCarbon()

        launcher = TargetLauncher(this, ::showNotice)
        vehicle = VehicleLink(this, ::onVehicleData)
        usb = UsbWatch(this, ::onUsbChanged)
        usb.refresh()            // before the console is laid out, so it opens correct
        ds2 = Ds2Link(this, ::onEngineSample)

        buildConsole()
        buildModeSwitch()
        applyNightStyling()

        renderClock()
        renderTemp()
        renderSourceLine()

        // Read before anything else can clear it — onStart, moments from now,
        // forgets unconditionally.
        resumeAfterRestart = ResumeAfterRestart(this)
        armResume(resumeAfterRestart.consume())

        // Constructed here, but it touches neither storage nor the network
        // until something asks it to, and everything it does is on a worker.
        ota = OtaController(this, io, ui, otaIo, ::onOtaChanged)

        // Whether the last self-update worked can only be observed here: the
        // install killed the process that started it. Consumed before it is
        // acted on, so a version that crashes on first run cannot loop.
        ota.consumeStartupOutcome(::onInstallOutcome)
    }

    /**
     * Offers to reopen the web tool that was on screen when the unit lost power.
     *
     * ## It counts down in the open, and any touch stops it
     *
     * Turning the key and being handed a full-screen analysis tool, when all you
     * wanted was the radio, is the kind of surprise a car should never produce.
     * So the console draws normally and says what it is about to do in the LCD's
     * own notice line — a slot that is already reserved, so nothing moves — and
     * [onUserInteraction] cancels it. Touch anything at all and you simply stay
     * on the console.
     *
     * The delay is not only manners. A Trusted Web Activity needs a warm Chrome
     * session or it degrades to a Custom Tab with a toolbar, and the binding
     * takes a moment; starting it now and launching in three seconds is what
     * makes the resumed tool come back full screen.
     */
    private fun armResume(targetId: String?) {
        if (targetId == null) return
        if (Targets.byId(targetId)?.steps?.none { it is Step.Web } != false) return

        resumeTarget = targetId
        resumeTicks = RESUME_SECONDS
        launcher.warmUp()
        ui.post(resumeTick)
    }

    private val resumeTick = object : Runnable {
        override fun run() {
            val id = resumeTarget ?: return
            if (resumeTicks <= 0) {
                resumeTarget = null
                notice = null
                Targets.byId(id)?.let { target ->
                    val outcome = launcher.launch(target, resume = true)
                    // Remembered again: a second key cycle during the same
                    // session should come back to the same place.
                    if (outcome is LaunchOutcome.Started) resumeAfterRestart.remember(target.id)
                }
                return
            }
            notice = "${id.uppercase()} IN $resumeTicks"
            renderSourceLine()
            resumeTicks--
            ui.postDelayed(this, 1_000L)
        }
    }

    private fun cancelResume() {
        if (resumeTarget == null) return
        resumeTarget = null
        ui.removeCallbacks(resumeTick)
        notice = null
        renderSourceLine()
    }

    /**
     * Any touch or key at all, before the console has even decided what was
     * touched. That is deliberately the widest possible net: the countdown is
     * something the owner did not ask for, so anything that shows they are
     * present should call it off.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        cancelResume()
    }

    /**
     * The carbon field is a full-panel 1024x600 image (a 45-degree-rotated 20px
     * lattice has a non-integer screen period, so no small tile repeats
     * seamlessly). It is fully opaque, so RGB_565 costs half of ARGB_8888 —
     * 1.2 MB instead of 2.4 — and loses nothing.
     *
     * The blanking plates crop their weave straight out of this bitmap, so they
     * are re-pointed at it here rather than carrying a copy.
     *
     * The previous bitmap is left to the collector rather than recycled: the
     * ImageView may still hold it for another frame, and this runs twice a day.
     */
    private fun loadCarbon() {
        val res = if (night) R.drawable.carbon_bg_night else R.drawable.carbon_bg
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = runCatching { BitmapFactory.decodeResource(resources, res, opts) }
            .onFailure { Log.w(TAG, "carbon background unavailable; staying flat black", it) }
            .getOrNull() ?: return

        carbon = bitmap
        carbonView.setImageBitmap(bitmap)
        for (plate in plates) plate.view.setSource(bitmap)
    }

    private fun buildConsole() {
        val sockets: FrameLayout = findViewById(R.id.sockets)
        for ((col, row) in ConsoleKeys.SOCKETS) ConsoleKeys.addSocket(sockets, col, row)

        val plateLayer: FrameLayout = findViewById(R.id.plates)
        for ((col, row) in ConsoleKeys.PLATES) {
            plates += ConsoleKeys.addPlate(plateLayer, col, row, carbon)
        }

        val homeLayer: FrameLayout = findViewById(R.id.keys)
        for (spec in ConsoleKeys.HOME) {
            val key = ConsoleKeys.addKey(homeLayer, spec)
            key.face.setOnClickListener { onKeyPressed(key) }
            homeKeys += key
        }

        val mLayer: FrameLayout = findViewById(R.id.keys_m)
        for (spec in ConsoleKeys.M_MODE) {
            val key = ConsoleKeys.addKey(mLayer, spec)
            key.face.setOnClickListener { onKeyPressed(key) }
            mKeys += key
        }

        // Hidden diagnostic: long-press the display window to dump the launch
        // matrix. This is how the exported question gets settled — ADB cannot do
        // it, because the shell holds START_ANY_ACTIVITY.
        lcd.setOnLongClickListener {
            if (modeSwitch.isMOpen) false else { dumpMatrix(); true }
        }
        // A tap on the display window opens the update pane. This is the whole
        // one-tap path: an update announces itself in the source line directly
        // below, and the thing it is written on is the control. No console key
        // is spent, no existing key moves, and the M console is not involved —
        // which matters because deploy.sh taps fixed coordinates.
        lcd.setOnClickListener {
            if (!modeSwitch.isMOpen) openOta()
        }
        // The M-mode counterpart: a full-scale sweep, so the tachometer can be
        // checked at all before there is anything to read.
        tach.setOnLongClickListener {
            if (modeSwitch.isMOpen) { tach.runSelfTest(); true } else false
        }

        refreshAvailability()
        renderActiveMarks()
    }

    /**
     * Builds the mode switch. Once, for the life of the Activity.
     *
     * It is handed every key and every plate; whether the M key is currently
     * fitted is a runtime state the switch owns, not a shape it is constructed
     * with. The earlier version rebuilt this object on every cable event and
     * applied the new state instantly, which was correct and looked wrong — a
     * button appearing between two frames reads as a glitch.
     *
     * Nothing here touches `isClickable`. A key stops accepting presses because
     * it stops being VISIBLE, which the switch already guarantees, and keeping a
     * second flag in step with the first is what produced the M key that was
     * there, was lit, and did nothing at all when pressed.
     */
    private fun buildModeSwitch() {
        val homePanels = homeKeys.map {
            it to ModeSwitch.Panel(it.face, it.scrim, it.spec.staggerMs)
        }
        mKeyPanel = homePanels.first { it.first.spec.targetId == "m" }.second
        mBlankPanel = plates.first { it.col == M_KEY_COL && it.row == M_KEY_ROW }.panel

        modeSwitch = ModeSwitch(
            window = findViewById<WindowFrameView>(R.id.window_frame),
            clockLayer = lcd,
            tach = tach,
            homeSet = homePanels.map { it.second },
            mSet = mKeys.map { ModeSwitch.Panel(it.face, it.scrim, it.spec.staggerMs) },
            plates = plates.map { it.panel },
            homeGeometry = geometry(R.dimen.d_win_x, R.dimen.d_win_y, R.dimen.d_win_w, R.dimen.d_win_h),
            mGeometry = geometry(R.dimen.d_win_m_x, R.dimen.d_win_m_y, R.dimen.d_win_m_w, R.dimen.d_win_m_h),
        )
        modeSwitch.applyInstant(mOpen = false)
        // The cable state at cold start is not news, so it does not get an
        // animation: the console simply opens the way it already is.
        modeSwitch.fit(mKeyPanel, mBlankPanel, fitted = usb.isCableAttached, animate = false)
    }

    /**
     * Something came or went on the USB bus: the diagnostic cable, a phone, or
     * both.
     *
     * The M console is deliberately NOT torn down if the cable is pulled while
     * it is open: yanking the screen out from under a hand that is reaching for
     * it is worse than a menu that outlives its prerequisite by one press, and
     * TUNER is a viewer that works without a link anyway. [ModeSwitch.fit]
     * handles that case by moving nothing.
     */
    private fun onUsbChanged(now: UsbBus) {
        bus = now
        // The first read happens during onCreate, before there is a switch to
        // tell; buildModeSwitch runs moments later and picks the state up.
        if (!::modeSwitch.isInitialized) return
        renderActiveMarks()
        modeSwitch.fit(mKeyPanel, mBlankPanel, fitted = now.diagnosticCable)

        // The link follows the cable, not just the M console: the outside
        // temperature is on the home screen and comes off the same block.
        if (now.diagnosticCable) {
            ds2.setPace(fast = modeSwitch.isMOpen)
            ds2.start()
        } else {
            ds2.stop()
            outsideFromDme = null
            coolantC = null
            oilC = null
            intakeC = null
            tach.rpm = null
            renderEngineTemps()
            renderTemp()
        }
    }

    private fun geometry(x: Int, y: Int, w: Int, h: Int) = ModeSwitch.Geometry(
        resources.getDimensionPixelSize(x).toFloat(),
        resources.getDimensionPixelSize(y).toFloat(),
        resources.getDimensionPixelSize(w).toFloat(),
        resources.getDimensionPixelSize(h).toFloat(),
    )

    // ── Behaviour ────────────────────────────────────────────────────────────

    private fun onKeyPressed(key: ConsoleKeys.Key) {
        when (key.spec.targetId) {
            "m" -> return setMode(mOpen = true)
            "m_home" -> return setMode(mOpen = false)
            "apps" -> return openAppList()
        }

        val target: LaunchTarget = Targets.byId(key.spec.targetId) ?: return
        if (target.isInternal) {
            showNotice(key.spec.targetId.uppercase())
            return
        }
        // The readout follows the launch, not the press. Naming it first meant a
        // key that failed to start still claimed to be the active source — seen
        // on the car, where CarPlay wrote CARPLAY to the readout while every one
        // of its steps had reported NO_INTENT.
        //
        // The lamps are not touched here at all. They report links, and pressing
        // a button does not connect anything.
        val outcome = launcher.launch(target)
        if (outcome is LaunchOutcome.Started) {
            activeSource = key.spec.targetId
            // Only a web tool: it is the only thing here that holds a session
            // worth returning to, and the only thing that cannot restart itself.
            if (outcome.step is Step.Web) resumeAfterRestart.remember(target.id)
        }
        renderSourceLine()
    }

    private fun setMode(mOpen: Boolean) {
        if (!modeSwitch.animateTo(mOpen)) return

        // The DME is asked either way now; only the pace changes, because the
        // same block feeds the home screen's outside-temperature slot. See
        // Ds2Link. Chrome is bound on the M console's own trigger: the TUNER key
        // is one press away and a Trusted Web Activity needs a live session.
        ds2.setPace(fast = mOpen)
        if (mOpen) {
            launcher.warmUp()
        } else {
            launcher.release()
            // The gauge is leaving the screen, so the needle goes with it.
            // The temperatures are NOT cleared: the link is still running, they
            // are still true, and one of them is on the screen behind this.
            tach.rpm = null
        }
        // A notice belongs to the readout that raised it, and that readout is
        // about to leave the screen.
        ui.removeCallbacks(clearNotice)
        notice = null
        renderSourceLine()
    }

    private fun openAppList() {
        val list = appList ?: inflateAppList()
        if (catalogue.isEmpty()) loadCatalogue() else list.submit(catalogue)
        // The console is hidden rather than covered: the overlay carries no
        // carbon of its own, so the field behind it is the same View that was
        // already there. "背景固定" by construction rather than by matching.
        console.visibility = View.INVISIBLE
        list.show()
    }

    private fun closeAppList() {
        appList?.hide()
        console.visibility = View.VISIBLE
    }

    private fun inflateAppList(): AppListLayer =
        AppListLayer(
            root = findViewById<ViewStub>(R.id.app_list_stub).inflate(),
            onPick = ::launchApp,
            onClose = ::closeAppList,
        ).also {
            it.hide()
            appList = it
        }

    // ── Update pane ──────────────────────────────────────────────────────

    private fun openOta() {
        val pane = otaLayer ?: inflateOta()
        console.visibility = View.INVISIBLE
        pane.show(ota.snapshot())
    }

    private fun closeOta() {
        if (otaLayer?.isVisible != true) return
        otaLayer?.hide()
        console.visibility = View.VISIBLE
    }

    private fun inflateOta(): OtaLayer =
        OtaLayer(
            root = findViewById<ViewStub>(R.id.ota_stub).inflate(),
            onCheck = { ota.checkNow() },
            onDownload = { ota.downloadNow() },
            onInstall = { ota.installNow(consoleState()) },
            onRollback = { ota.rollbackNow(consoleState()) },
            onRemove = ::openRemove,
            onGrantInstallPermission = ::openUnknownSourcesSettings,
            onClose = ::closeOta,
        ).also {
            it.hide()
            otaLayer = it
        }

    /**
     * What the console looks like right now, for [ota] to refuse an update
     * against.
     *
     * Assembled here rather than read inside the OTA package because every one
     * of these facts already lives on this screen. Two readers of the same state
     * eventually disagree, and the disagreement would show up as an update that
     * went ahead during a datalog.
     */
    private fun consoleState(): ConsoleState = ConsoleState(
        resumed = otaResumed,
        mConsoleOpen = ::modeSwitch.isInitialized && modeSwitch.isMOpen,
        resumeArmed = resumeTarget != null,
        appListOpen = appList?.isVisible == true,
        cableAttached = ::usb.isInitialized && usb.isCableAttached,
        ds2Live = lastEngineSampleAt != 0L &&
            SystemClock.elapsedRealtime() - lastEngineSampleAt <= DS2_LIVE_MS,
        rpm = tach.rpm,
    )

    /**
     * The Settings screen that grants REQUEST_INSTALL_PACKAGES.
     *
     * `[U]` on this vendor build — MtkSettings may not carry it. When it does
     * not resolve the pane's message still names the ADB route, which always
     * works, so this is a shortcut rather than the only way through.
     */
    private fun openUnknownSourcesSettings() {
        val intent = ota.installer.unknownSourcesSettings()
        if (intent == null) {
            showNotice("SETTINGS UNAVAILABLE")
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { showNotice("SETTINGS UNAVAILABLE") }
    }

    /**
     * The one place OTA state reaches the screen. Always on the main thread —
     * [OtaController] posts to [ui] and nothing in that package touches a View.
     */
    private fun onOtaChanged(snapshot: OtaSnapshot) {
        // Only the two states the owner can act on take the slot. A check in
        // progress, a wait for the clock and a failure all stay in the pane —
        // the source line is a standing indicator, not a log.
        otaNotice = when (val s = snapshot.status) {
            is OtaStatus.Available -> s.entry.versionName
            is OtaStatus.Ready -> s.entry.versionName
            else -> null
        }?.let { getString(R.string.ota_lcd_available, it) }
        renderSourceLine()
        // Only if it is actually up; binding a hidden pane would be wasted work
        // on a thread budget this screen does not have to spare.
        if (otaLayer?.isVisible == true) otaLayer?.bind(snapshot)
    }

    /**
     * What became of the version we were installing when this process ended.
     *
     * Both messages use the transient notice line rather than the sticky OTA
     * slot: they describe something that has already finished, and a standing
     * indicator for a completed event is how a console ends up shouting about
     * last Tuesday.
     */
    private fun onInstallOutcome(
        outcome: OtaState.InstallOutcome,
        home: HomeGuard.Verdict?,
    ) {
        // Checked first and independently of the outcome: an update that
        // "succeeded" and left the unit without a home screen is the worst of
        // the two results, not the better one.
        if (home != null && !home.isHome) showLostHome()

        when (outcome) {
            is OtaState.InstallOutcome.Succeeded ->
                showNotice(getString(R.string.ota_headline_updated, BuildConfig.VERSION_NAME))

            is OtaState.InstallOutcome.Failed -> {
                // Loud in the log, quiet on the console: the owner cannot act on
                // this, and the next check will simply offer it again unless it
                // has been poisoned.
                Log.w(
                    TAG,
                    "install of versionCode=${outcome.versionCode} did not take " +
                        "(attempt ${outcome.attempts}, poisoned=${outcome.poisoned})",
                )
                if (outcome.poisoned) showNotice("UPDATE FAILED")
            }

            is OtaState.InstallOutcome.None -> Unit
        }
    }

    /**
     * The launcher is installed and running, and the unit no longer treats it
     * as HOME.
     *
     * Not dismissible, and deliberately drawn over everything: one power cycle
     * from here the system falls back to
     * `com.android.settings/.FallbackHome`, and getting out of that needs the
     * car powered and on the right WiFi. The recovery command is on screen and
     * in a notification (see OtaReplacedReceiver) because the screen may not be
     * reachable either.
     */
    private fun showLostHome() {
        val root = lostHome ?: findViewById<ViewStub>(R.id.lost_home_stub).inflate().also {
            lostHome = it
            it.findViewById<TextView>(R.id.lost_home_command).text =
                HomeGuard.recoveryCommand(packageName)
            it.findViewById<View>(R.id.lost_home_settings).setOnClickListener {
                val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
                    .onFailure { showNotice("HOME SETTINGS UNAVAILABLE") }
            }
        }
        root.visibility = View.VISIBLE
        root.bringToFront()
    }

    // ── Removal ──────────────────────────────────────────────────────────
    //
    // Four steps, each gating the next, with the two recovery commands on
    // screen throughout. The gating is the point: uninstalling the home screen
    // while it is still the home screen leaves the unit on FallbackHome, and
    // recovery from there needs the car powered and ADB (docs/04 §12).

    private fun openRemove() {
        if (!Relinquish.oemLauncherPresent(packageManager)) {
            // Nothing to hand HOME to. Starting the procedure would walk
            // somebody to the edge of a state nobody can get out of on the unit.
            showNotice("NO OEM LAUNCHER")
            Log.w(TAG, "removal refused: ${Relinquish.OEM_PACKAGE} is not installed")
            return
        }
        removeState = RemoveLayer.State(done = 0)
        val pane = removeLayer ?: inflateRemove()
        closeOta()
        console.visibility = View.INVISIBLE
        pane.show(removeState, Relinquish.recoveryCommands(packageName))
    }

    private fun closeRemove() {
        if (removeLayer?.isVisible != true) return
        removeLayer?.hide()
        console.visibility = View.VISIBLE
    }

    private fun inflateRemove(): RemoveLayer =
        RemoveLayer(
            root = findViewById<ViewStub>(R.id.remove_stub).inflate(),
            onAdvance = ::advanceRemove,
            onClose = ::closeRemove,
        ).also {
            it.hide()
            removeLayer = it
        }

    private fun advanceRemove() {
        when (removeState.done) {
            0 -> {
                removeState = when (val step = Relinquish.clearOurPreference(this)) {
                    is Relinquish.Step.Failed ->
                        RemoveLayer.State(done = 0, failedAt = 0, message = step.why)
                    else ->
                        RemoveLayer.State(done = 1, message = getString(R.string.remove_cleared))
                }
            }

            1 -> {
                // The chooser is somebody else's screen, so there is nothing to
                // observe from here. The step advances on opening it, and step 3
                // is what decides whether it worked.
                runCatching { startActivity(Relinquish.picker(this)) }
                    .onSuccess {
                        removeState = RemoveLayer.State(
                            done = 2,
                            message = getString(R.string.remove_pick_hint),
                        )
                    }
                    .onFailure {
                        removeState = RemoveLayer.State(
                            done = 1,
                            failedAt = 1,
                            message = getString(R.string.remove_no_oem),
                        )
                    }
            }

            2 -> {
                // Both halves: not us, AND the OEM launcher. "Not us" alone also
                // describes a unit with no home screen at all.
                removeState = when (Relinquish.verify(this)) {
                    is Relinquish.Step.Handed ->
                        RemoveLayer.State(done = 3, message = getString(R.string.remove_handed))
                    else -> RemoveLayer.State(
                        done = 2,
                        failedAt = 2,
                        message = getString(R.string.remove_verify_failed),
                    )
                }
            }

            else -> {
                // ConfirmInstaller refuses to uninstall this package through its
                // ordinary path, structurally. Reaching the system dialog is
                // therefore this flow's own business, and it only happens after
                // step 3 said HOME has actually moved.
                uninstallSelf()
                removeState = RemoveLayer.State(done = 4)
            }
        }
        removeLayer?.bind(removeState)
    }

    /**
     * The last step. Asks the system to remove this package; the system shows
     * its own confirmation, and if the owner says yes this process ends.
     */
    private fun uninstallSelf() {
        val result = runCatching {
            packageManager.packageInstaller.uninstall(
                packageName,
                android.app.PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("$packageName.OTA_SELF_UNINSTALL").setPackage(packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            android.app.PendingIntent.FLAG_MUTABLE
                        } else 0,
                ).intentSender,
            )
        }
        result.onFailure {
            Log.e(TAG, "self-uninstall request failed", it)
            showNotice("UNINSTALL FAILED")
        }
    }

    private fun launchApp(entry: AppEntry) {
        closeAppList()
        try {
            startActivity(AppCatalog.intentFor(entry))
        } catch (e: Exception) {
            // Enumerated out of the launcher bucket, so it existed a moment ago;
            // a failure here means a permission, or a package that went away.
            Log.w(TAG, "could not start ${entry.component.flattenToShortString()}", e)
            showNotice(entry.label.uppercase())
        }
    }

    private fun loadCatalogue() {
        if (catalogueLoading) return
        catalogueLoading = true
        io.execute {
            val entries = AppCatalog.load(this)
            ui.post {
                catalogue = entries
                catalogueLoading = false
                appList?.submit(entries)
            }
        }
    }

    /**
     * A button that cannot work must not claim it can — but it must not move
     * either, so this only changes alpha.
     *
     * This answers "is it installed?", never "are we allowed to start it?":
     * resolveActivity ignores exported and permission checks. The second
     * question is answered at press time by TargetLauncher's catch blocks.
     */
    private fun refreshAvailability() {
        for (key in homeKeys + mKeys) {
            val target = Targets.byId(key.spec.targetId) ?: continue
            key.face.alpha = if (launcher.isAvailable(target)) 1f else 0.40f
        }
    }

    /**
     * Lamps on, lamps off. Never absent — the off state is a dark window, so a
     * key that has a lamp always looks like a key that has a lamp.
     *
     * ## Each lamp reports its own link, and several can be lit at once
     *
     * This used to light exactly one lamp: whichever key was last pressed. That
     * described the console, not the car. A phone can be on Bluetooth for calls
     * while CarPlay runs over the cable, and the old model could not say so —
     * it could only ever claim one.
     *
     * What each lamp actually means, and how well:
     *
     *  - **BT** — `ITsCommon.BtIsConnect()`, the OEM stack's own answer. A true
     *    connection state, updated on the vendor's own broadcast.
     *  - **CarPlay** — an Apple device is on the USB bus.
     *  - **Android link** — a phone the OEM link app recognises is on the bus.
     *
     * The last two are one step short of "the session is up", because this unit
     * publishes no session state at all — see UsbWatch for what was checked. A
     * phone that is only charging will light its lamp.
     */
    private fun renderActiveMarks() {
        val lit = litColor()
        val off = ContextCompat.getColor(this, R.color.d_act_off)
        for (key in homeKeys) {
            if (!key.spec.hasLamp) continue
            val connected = when (key.spec.targetId) {
                "bluetooth" -> btConnected
                "carplay" -> bus.applePhone
                "android_link" -> bus.androidPhone
                else -> false
            }
            key.activeMark?.setBackgroundColor(if (connected) lit else off)
        }
    }

    private fun renderClock() {
        val now = Date()
        lcdClock.text = timeFormat.format(now)
        lcdDate.text = "${dateFormat.format(now)} ${dowFormat.format(now).uppercase()}"
    }

    /**
     * The design shows a fixed 21.5; a real instrument has to admit when it has
     * no reading. `--.-` keeps the slot the same width either way, so a value
     * arriving later moves nothing.
     */
    private fun renderTemp() {
        val t = outsideTempC
        lcdTemp.text = if (t == null) "--.-°C"
        else String.format(Locale.US, "%.1f°C", t)
    }

    /**
     * The LCD's bottom-right line. The design puts transient notices here rather
     * than adding a separate row, and so does this.
     *
     * The design's per-source strings ("FM1 87.9 MHZ", "BT M3-PHONE") are mockup
     * values. Inventing a frequency we have not read would be putting a made-up
     * number on an instrument, so the source name ships plain until the real
     * value is available — ITsCommon exposes GetBand/GetFreq and BtIsConnect for
     * exactly this, and they can fill it in once wired.
     */
    private fun renderSourceLine() {
        notice?.let { lcdSource.text = it; return }
        otaNotice?.let { lcdSource.text = it; return }
        lcdSource.text = when (activeSource) {
            "radio" -> "RADIO"
            "video" -> "VIDEO"
            "eq" -> "EQ"
            "maps" -> "MAP"
            "bluetooth" -> "BT"
            "android_link" -> "ANDROID LINK"
            "carplay" -> "CARPLAY"
            else -> ""
        }
    }

    /** The M console's four-up temperature block. Composed from both links. */
    private fun renderEngineTemps() {
        tach.temps = TachView.Temps(
            coolantC = coolantC,
            oilC = oilC,
            intakeC = intakeC,
            outsideC = outsideTempC?.roundToInt(),
        )
    }

    private fun onVehicleData(data: VehicleData) {
        outsideFromHeadUnit = data.outsideTempC
        btConnected = data.btConnected
        renderTemp()
        renderEngineTemps()
        renderActiveMarks()
    }

    /**
     * A poll of the DME. A null field is silence, and silence darkens that
     * reading rather than freezing the last number on it.
     */
    private fun onEngineSample(sample: Mss54Sample) {
        // The one new signal the OTA guard needed, and it costs a field
        // assignment on a path that was already running. Ds2Link is untouched:
        // "a reply arrived recently" is something this screen can observe, and
        // the link has no business knowing why anyone is asking.
        lastEngineSampleAt = SystemClock.elapsedRealtime()
        tach.rpm = sample.rpm
        coolantC = sample.coolantC
        oilC = sample.oilC
        intakeC = sample.intakeC
        outsideFromDme = sample.ambientC
        renderEngineTemps()
        // The home screen's slot is fed from here too. That is why this link no
        // longer stops when the M console closes — it only slows down.
        renderTemp()
    }

    private fun showNotice(message: String) {
        notice = message
        renderSourceLine()
        ui.removeCallbacks(clearNotice)
        ui.postDelayed(clearNotice, NOTICE_MS)
    }

    // ── Night mode ───────────────────────────────────────────────────────────

    /** The design's isNight(): before 06:00 or from 18:00. */
    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < DAY_FROM_HOUR || hour >= NIGHT_FROM_HOUR
    }

    private fun refreshNightMode() {
        val now = isNight()
        if (now == night) return
        night = now
        loadCarbon()
        applyNightStyling()
    }

    private fun applyNightStyling() {
        val lit = litColor()
        lcdClock.litColor = lit
        lcdTemp.litColor = lit
        lcdDate.litColor = lit
        lcdSource.litColor = lit
        tach.litColor = lit
        tach.night = night
        for (key in homeKeys + mKeys) key.applyNight(night)
        renderActiveMarks()
        applyBrightness()
    }

    /**
     * Full backlight by day; the system's own setting after dark.
     *
     * This is half of the answer to a screen that could not be read in sunlight,
     * and it is the half that does the least. `screenBrightness` overrides the
     * backlight only while this window is in front, so it is a local, reversible
     * request rather than a write to Settings.System — no permission, nothing
     * left behind, and the radio or CarPlay coming forward restores whatever the
     * unit was doing before. If the panel is already at maximum it changes
     * nothing at all, which is why the day palette was lifted as well: contrast
     * is the half that survives a backlight that is already flat out.
     *
     * At night the override is dropped rather than set low. Fighting the unit's
     * own dimming would be the same mistake in the opposite direction.
     */
    private fun applyBrightness() {
        val lp = window.attributes
        val wanted =
            if (night) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else DAY_BRIGHTNESS
        if (lp.screenBrightness == wanted) return
        lp.screenBrightness = wanted
        window.attributes = lp
    }

    private fun litColor(): Int = ContextCompat.getColor(
        this, if (night) R.color.d_lit_night else R.color.d_lit
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        otaResumed = true
        applySystemUi()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)     // manifest registration is not allowed
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        renderClock()
        refreshNightMode()
        refreshAvailability()
        vehicle.start()
        usb.start()
        // The DME is asked whenever this screen is in front and a cable is in:
        // the outside temperature on the home readout comes out of the same
        // block as the tachometer, so the link runs slowly rather than not at
        // all. It is only fast while the gauge is up.
        if (usb.isCableAttached) {
            ds2.setPace(fast = ::modeSwitch.isInitialized && modeSwitch.isMOpen)
            ds2.start()
        }

        // After the first traversal, never during it: enumerating every launcher
        // activity resolves a label out of each target APK, and this screen's
        // cold start is paid on every boot and every low-memory kill. The
        // wallpaper rides along for the same reason — it is a decode and a
        // system-wide write, and it is for the *next* boot, not this one.
        window.decorView.post {
            loadCatalogue()
            io.execute { BootWallpaper.applyIfNeeded(this) }
            // Last, and later still: a few KB over a link that may not exist.
            // Everything it touches — SharedPreferences, the network — happens
            // on `io`, so this post only costs a queued Runnable.
            ui.postDelayed(otaCheck, Ota.CHECK_DELAY_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        otaResumed = false
        // Taken back rather than left to fire: onResume schedules it again, and
        // a check that lands after onDestroy would reach a shut-down executor.
        ui.removeCallbacks(otaCheck)
        // Something else has the screen. Whatever it is, it is not what the
        // countdown was about to open.
        cancelResume()
        runCatching { unregisterReceiver(clockReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        vehicle.stop()
        usb.stop()
        // Something else is on screen: nothing here is being read, and the cable
        // has to go back. TUNER reaches the DME over WebUSB from Chrome now, and
        // two processes cannot claim one USB device — so this release is what
        // lets the key we launched actually work.
        ds2.stop()
        // Deliberately NOT released here. onPause fires when the Custom Tab
        // itself comes forward, and dropping the binding at that moment would
        // unbind the very session the page is running on. It is released when
        // the M console closes, and by the process ending.
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(otaCheck)
        io.shutdownNow()
        otaIo.shutdownNow()
        launcher.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi()
    }

    /**
     * True once another screen has actually covered us. See [onNewIntent].
     */
    private var wasStopped = false

    /**
     * Whether this screen is between onResume and onPause.
     *
     * Read by the self-update guard. The Activity's own lifecycle state is not
     * queryable below API 29 without dragging in a LifecycleOwner, and this is
     * two assignments.
     */
    private var otaResumed = false

    /**
     * This console being visible means the tool is not, so there is nothing to
     * come back to. Unconditional on purpose — every moment the owner is looking
     * at the launcher is a moment they are not mid-session somewhere else.
     *
     * Safe next to [armResume], which reads the record at the end of onCreate,
     * before this ever runs.
     */
    override fun onStart() {
        super.onStart()
        resumeAfterRestart.forget()
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    /**
     * singleTask: a HOME intent while we are already home lands here.
     *
     * ## Not every HOME intent is somebody pressing HOME
     *
     * This unit runs a vendor service called IdleScreen which re-fires the HOME
     * intent at us on an inactivity timer:
     *
     *     IdleScreen: activityIdleScreen: idleIntent: Intent { act=MAIN
     *       cat=[HOME] flg=0x10800000 cmp=app.tsunagi.e46m3.launcher/.HomeActivity }
     *
     * Resetting the UI on every one of those meant the tachometer folded itself
     * away after a few seconds of not being touched — which is precisely when
     * somebody is watching it rather than prodding it.
     *
     * So the reset is gated on having genuinely been away. Coming back from the
     * radio or from Settings goes through onStop, and *that* deserves a clean
     * home screen; a nudge delivered while we are already the visible screen
     * does not, because there is nothing to come back from.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        notice = null
        renderSourceLine()
        if (!wasStopped) return
        wasStopped = false
        closeAppList()
        closeOta()
        closeRemove()
        // This path closes the M console without going through setMode, so its
        // two tear-downs are done by hand. Missing them would leave Chrome bound
        // for the rest of the session.
        if (modeSwitch.isMOpen) launcher.release()
        modeSwitch.applyInstant(mOpen = false)
    }

    /**
     * BACK closes one layer at a time and never closes the home screen itself.
     *
     * Deprecated upstream in favour of OnBackPressedDispatcher, which arrived in
     * API 33 — irrelevant on an API 27 device, and adopting it would pull in
     * AppCompat for no benefit.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            removeLayer?.isVisible == true -> closeRemove()
            otaLayer?.isVisible == true -> closeOta()
            appList?.isVisible == true -> closeAppList()
            modeSwitch.isMOpen -> setMode(mOpen = false)
            else -> Unit   // Nothing sits above the home screen. Stay put.
        }
    }

    private fun applySystemUi() {
        if (!immersive) return
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun dumpMatrix() {
        val out = File(filesDir, "target-matrix.md")
        runCatching { out.writeText(launcher.dumpMatrix()) }
            .onSuccess { showNotice("MATRIX SAVED") }
            .onFailure { showNotice("MATRIX FAILED") }
        Log.i(TAG, launcher.dumpMatrix())
    }

    /**
     * Records the crash, then hands off to the previous handler so the process
     * still dies.
     *
     * Swallowing a main-thread exception in a HOME app leaves the framework in
     * an undefined state and typically produces a frozen black screen with no
     * way out short of ADB — strictly worse than the clean restart that
     * stateNotNeeded="true" buys. Ordinary launch failures never reach here;
     * they are caught individually in TargetLauncher.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(filesDir, "crash.log").appendText(
                    "\n=== ${Date()} thread=${thread.name} ===\n$trace\n"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "HomeActivity"
        private const val NOTICE_MS = 2_600L

        private const val DAY_FROM_HOUR = 6
        private const val NIGHT_FROM_HOUR = 18

        /** See [applyBrightness]. 1.0 is this window's maximum, not the system's. */
        private const val DAY_BRIGHTNESS = 1f

        /**
         * How long the resume countdown runs.
         *
         * Long enough to read the line and reach the glass, short enough that
         * somebody who does want the tool back is not waiting on ceremony. It
         * also covers the Chrome binding, without which the tool would come
         * back as a Custom Tab with a toolbar rather than full screen.
         */
        private const val RESUME_SECONDS = 3

        /**
         * How recently the DME must have answered for the link to count as live.
         *
         * Ds2Link's idle pace is slower than its fast pace, so this has to clear
         * the slow one comfortably or a parked car with the cable in would look
         * idle between polls.
         */
        private const val DS2_LIVE_MS = 60_000L

        /** Where the M key lives, and therefore which socket gets blanked. */
        private const val M_KEY_COL = 4
        private const val M_KEY_ROW = 1
    }
}
