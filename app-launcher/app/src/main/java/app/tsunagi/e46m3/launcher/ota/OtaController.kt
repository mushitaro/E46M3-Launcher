package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import app.tsunagi.e46m3.launcher.BuildConfig
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Finds an update, fetches it, proves it, and — when the console is in a state
 * where that is safe — applies it.
 *
 * ## The order things happen in, and why it is that order
 *
 * Find, fetch, verify, *then* offer. By the time INSTALL appears, the APK is on
 * disk, its SHA-256 matches a manifest carrying our own signature, and its
 * signing certificate matches the one this very process runs under. The tap is
 * the last step rather than the first because a garage is a bad place to
 * discover that a download does not finish.
 *
 * ## When a download starts by itself
 *
 * On an unmetered link, as soon as a check finds something: the owner asked for
 * "automatic download, one tap to apply", and having the bytes already present
 * and verified is what makes the tap instant rather than a three-minute wait in
 * a garage. On a **metered** link nothing is fetched until somebody presses
 * DOWNLOAD — the car may be tethered to a phone, and three megabytes is not a
 * decision to make on the owner's behalf.
 *
 * ## Threading
 *
 * Everything that touches storage or the network runs on the executors it is
 * handed. Nothing here touches a View; results are posted to [ui].
 *
 * The check shares the "catalogue" thread — it is a few KB with bounded
 * timeouts. The **download does not**: a three-megabyte transfer sitting in
 * front of a package-change-triggered catalogue reload is a visible bug, so it
 * gets its own minimum-priority thread.
 */
internal class OtaController(
    context: Context,
    private val io: Executor,
    private val ui: Handler,
    private val downloadExecutor: Executor,
    private val onChanged: (OtaSnapshot) -> Unit,
) {

    private val app = context.applicationContext

    /** Constructed on first use, which is always off the main thread. */
    private val state by lazy { OtaState(app) }
    private val connectivity by lazy { Connectivity(app) }
    private val store by lazy { OtaStore(app) }

    /**
     * Exposed so the pane can offer the Settings shortcut when the app-op is
     * missing. Nothing else reaches into it.
     */
    val installer = ConfirmInstaller(app)

    @Volatile private var status: OtaStatus = OtaStatus.Idle
    @Volatile private var lastCheckElapsed: Long? = null

    /** True when the link the last check saw charges for bytes. */
    @Volatile private var metered = false

    /**
     * A kept earlier APK and its versionCode, when going back to it is both
     * possible and meaningful.
     *
     * Null unless a copy exists AND the platform will accept a downgrade, so the
     * pane never offers a rollback that could only fail — see [Downgrade].
     */
    @Volatile private var rollback: Pair<java.io.File, Int>? = null

    private var checkInFlight = false
    private var downloadInFlight = false

    fun snapshot(): OtaSnapshot = OtaSnapshot(
        status = status,
        installedVersionName = BuildConfig.VERSION_NAME,
        installedVersionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.GIT_SHA,
        metered = metered,
        sinceLastCheckMs = lastCheckElapsed?.let {
            val now = SystemClock.elapsedRealtime()
            if (now >= it) now - it else null
        },
        rollbackVersionCode = rollback?.second,
    )

    // ── Entry points ────────────────────────────────────────────────────────

    /** From the home screen, after the first frame. Cheap when not due. */
    fun checkIfDue() = check(force = false)

    /** From the pane, when the owner asks. Ignores the throttle. */
    fun checkNow() = check(force = true)

    /**
     * From the pane, when the owner accepts a download on a metered link.
     * A no-op unless there is something to fetch.
     */
    fun downloadNow() {
        val entry = (status as? OtaStatus.Available)?.entry ?: return
        download(entry)
    }

    /**
     * Reinstalls the kept earlier APK.
     *
     * The local half of the rollback story. The other half — publishing the old
     * source as a HIGHER versionCode with `tools/release.sh --bump patch` — needs
     * no code, keeps versionCode monotonic, and travels the ordinary update path;
     * it is the one to reach for when a bad version has already gone out. This is
     * the one to reach for when the car is in front of you.
     */
    fun rollbackNow(console: ConsoleState) {
        val (apk, versionCode) = rollback ?: return

        SelfUpdateGuard.check(console)?.let { block ->
            Log.i(Ota.TAG, "rollback refused: " + block)
            publishBlockedRollback(block)
            return
        }
        if (!installer.isAvailable()) {
            publishBlockedRollback(null)
            return
        }

        submit(downloadExecutor) {
            // Recorded like any other install: if going back also fails to
            // produce a running build, the next launch has to be able to say so.
            state.beginInstall(versionCode)
            publish(OtaStatus.RollingBack(versionCode))
            installer.install(apk, allowDowngrade = true) { event ->
                when (event) {
                    is InstallEvent.Committed, is InstallEvent.AwaitingUser ->
                        publish(OtaStatus.RollingBack(versionCode))
                    is InstallEvent.Succeeded ->
                        publish(OtaStatus.UpToDate(BuildConfig.VERSION_NAME))
                    is InstallEvent.Refused ->
                        publish(OtaStatus.Failed("ROLLBACK_" + event.reason))
                    is InstallEvent.Failed ->
                        publish(OtaStatus.Failed("ROLLBACK_" + event.code))
                }
            }
        }
    }

    private fun publishBlockedRollback(block: Block?) {
        // Reuses the update-blocked state so the pane needs no second vocabulary
        // for the same seven reasons.
        val entry = (status as? OtaStatus.Ready)?.entry
            ?: (status as? OtaStatus.Blocked)?.entry
            ?: return
        publish(
            OtaStatus.Blocked(
                entry,
                if (block != null) BlockReason.Console(block) else BlockReason.Appop,
            )
        )
    }

    /**
     * Applies the staged update, if this is a moment at which that is allowed.
     *
     * @param console what the home screen looks like right now. Supplied by the
     *        caller rather than read here, because every one of those facts
     *        already lives on the home screen, and reading them twice would
     *        create two answers to the same question.
     */
    fun installNow(console: ConsoleState) {
        val ready = status as? OtaStatus.Ready
            ?: (status as? OtaStatus.Blocked)?.let { blocked ->
                // Re-offered after a refusal: the cable may have come out since.
                val apk = Ota.apkFile(app, blocked.entry.packageName, blocked.entry.versionCode)
                if (apk.isFile) OtaStatus.Ready(blocked.entry, apk) else null
            }
            ?: return

        SelfUpdateGuard.check(console)?.let { block ->
            Log.i(Ota.TAG, "install refused: " + block)
            publish(OtaStatus.Blocked(ready.entry, BlockReason.Console(block)))
            return
        }
        if (!installer.isAvailable()) {
            Log.i(Ota.TAG, "install refused: REQUEST_INSTALL_PACKAGES app-op not granted")
            publish(OtaStatus.Blocked(ready.entry, BlockReason.Appop))
            return
        }

        // The OTA thread, not the catalogue one: writing three megabytes into
        // an install session would otherwise sit in front of the app-list
        // refresh that a package change triggers — which is a refresh caused by
        // this very install.
        submit(downloadExecutor) {
            if (state.isPoisoned(ready.entry.versionCode)) {
                publish(
                    OtaStatus.Blocked(ready.entry, BlockReason.Poisoned(ready.entry.versionCode))
                )
                return@submit
            }

            // A copy of what is running now, so there is something to go
            // back to. Before the replace, because afterwards sourceDir points
            // at the new APK — and because after a self-update there is no
            // "afterwards" in this process. A failure here is logged and
            // ignored: losing the rollback copy is smaller than refusing a fix.
            store.keepCurrentAsPrevious(app.packageName, installedVersionCode())

            // Written with commit(), and written BEFORE the session is
            // committed. Replacing our own package is a SIGKILL: after the next
            // line this process may simply stop existing, and this record is the
            // only way the next launch can tell a successful update from a
            // failed one. See OtaState.beginInstall.
            state.beginInstall(ready.entry.versionCode)

            publish(OtaStatus.Installing(ready.entry))
            installer.install(ready.apk) { event -> onInstallEvent(ready.entry, event) }
        }
    }

    /**
     * Reads the record left behind by the last install attempt, once per launch.
     *
     * Consumed before it is acted on — the same discipline as
     * `ResumeAfterRestart` — so a version that crashes on first run cannot be
     * retried for ever.
     */
    fun consumeStartupOutcome(
        onOutcome: (OtaState.InstallOutcome, HomeGuard.Verdict?) -> Unit,
    ) {
        submit(io) {
            val outcome = state.consumeInstallOutcome(BuildConfig.VERSION_CODE)
            if (outcome !is OtaState.InstallOutcome.None) {
                Log.i(Ota.TAG, "previous install attempt: " + outcome)
            }

            // Only after an install attempt, and deliberately not on every
            // launch: this is two binder round trips, and this screen cold
            // starts on every boot with an 800 ms budget. The state it looks
            // for can only be entered by a package replace, so a launch with no
            // replace behind it has nothing to find.
            val home = if (outcome is OtaState.InstallOutcome.None) null else {
                HomeGuard.check(app).also {
                    if (!it.isHome) {
                        Log.e(
                            Ota.TAG,
                            OtaReplacedReceiver.LOST_HOME_TAG + " — recover with: " +
                                HomeGuard.recoveryCommand(app.packageName),
                        )
                    }
                }
            }
            refreshRollback()

            when (outcome) {
                is OtaState.InstallOutcome.Succeeded -> {
                    // The bytes did their job. Keeping them costs /data and
                    // buys nothing.
                    store.prune(keep = null)
                    publish(OtaStatus.UpToDate(BuildConfig.VERSION_NAME))
                }
                is OtaState.InstallOutcome.Failed -> if (outcome.poisoned) store.prune(keep = null)
                is OtaState.InstallOutcome.None -> Unit
            }
            ui.post { onOutcome(outcome, home) }
        }
    }

    private fun onInstallEvent(entry: OtaPackage, event: InstallEvent) {
        when (event) {
            // Both mean "the system has it now". For our own package there is no
            // third event: success is observed on the next launch, because this
            // process will not be here to see it.
            is InstallEvent.Committed, is InstallEvent.AwaitingUser ->
                publish(OtaStatus.Installing(entry))

            is InstallEvent.Succeeded -> publish(OtaStatus.UpToDate(BuildConfig.VERSION_NAME))

            is InstallEvent.Refused -> publish(
                if (event.reason == "APPOP") OtaStatus.Blocked(entry, BlockReason.Appop)
                else OtaStatus.Failed("INSTALL_" + event.reason)
            )

            is InstallEvent.Failed -> publish(OtaStatus.Failed("INSTALL_" + event.code))
        }
    }

    /**
     * The clock just changed, which on this unit usually means NTP corrected an
     * RTC that was reading 2006. If a check was parked for exactly that reason,
     * this is its trigger.
     *
     * Hooked into the `clockReceiver` the home screen already registers, so it
     * costs no new receiver and no polling.
     */
    fun onTimeChanged() {
        val current = status
        if (current is OtaStatus.Waiting && current.reason == WaitReason.CLOCK &&
            OtaClock.isPlausible()
        ) {
            Log.i(Ota.TAG, "clock became plausible — retrying the parked check")
            check(force = true)
        }
    }

    // ── Check ───────────────────────────────────────────────────────────────

    private fun check(force: Boolean) {
        synchronized(this) {
            if (checkInFlight || downloadInFlight) return
            checkInFlight = true
        }
        // Belt as well as braces. HomeActivity takes back its pending check on
        // pause, but an executor can also refuse a task while shutting down, and
        // "the update check could not be scheduled" must never be the reason the
        // home screen disappears.
        if (!submit(io) {
            var found: OtaPackage? = null
            try {
                if (!force && !state.isCheckDue()) return@submit
                state.noteCheckStarted()
                lastCheckElapsed = SystemClock.elapsedRealtime()
                publish(OtaStatus.Checking)
                val result = runCatching { performCheck() }.getOrElse { e ->
                    Log.w(Ota.TAG, "check threw", e)
                    OtaStatus.Failed("ERROR_${e.javaClass.simpleName}")
                }
                found = (result as? OtaStatus.Available)?.entry
                publish(result)
            } finally {
                synchronized(this) { checkInFlight = false }
            }

            // Outside the in-flight window, so the download can claim its own.
            // Unmetered only: see the class docs on why metered waits for a tap.
            if (found != null && !metered) download(found)
        }) {
            synchronized(this) { checkInFlight = false }
        }
    }

    /**
     * Submits [task], and reports whether it was accepted rather than throwing.
     *
     * @return false if the executor refused it — it is shutting down, which
     *         happens exactly once, in onDestroy.
     */
    private fun submit(executor: Executor, task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (e: RejectedExecutionException) {
        Log.i(Ota.TAG, "not scheduled: the OTA executor is shutting down")
        false
    }

    private fun performCheck(): OtaStatus {
        // 1. The clock, before anything opens a socket. A certificate that is
        //    not yet valid fails the handshake before any payload exists, so
        //    there is nothing to be gained by trying — and a lot to be gained
        //    by being able to say which of the two problems this is.
        if (!OtaClock.isPlausible()) {
            Log.i(
                Ota.TAG,
                "clock reads ${System.currentTimeMillis()}, below the floor " +
                    "${OtaClock.floorMillis()} — not connecting",
            )
            return OtaStatus.Waiting(WaitReason.CLOCK)
        }

        // 2. The link.
        when (val net = connectivity.assess(state.unvalidatedStreak)) {
            is Connectivity.State.Offline -> {
                state.unvalidatedStreak = 0
                return OtaStatus.Waiting(WaitReason.OFFLINE)
            }
            is Connectivity.State.Unvalidated -> {
                state.unvalidatedStreak = net.streak
                return OtaStatus.Waiting(WaitReason.UNVALIDATED)
            }
            is Connectivity.State.Usable -> {
                state.unvalidatedStreak = 0
                metered = net.metered
                if (!net.validated) Log.i(Ota.TAG, "proceeding on an unvalidated network")
            }
        }

        // 3. The manifest and its detached signature. Two GETs, both small.
        val manifestUrl = BuildConfig.OTA_MANIFEST_URL
        val manifestBytes = when (val f = OtaHttp.get(manifestUrl, Ota.MANIFEST_MAX_BYTES)) {
            is OtaHttp.Fetch.Ok -> f.bytes
            is OtaHttp.Fetch.Failed -> return f.toStatus()
        }
        val signature = when (
            val f = OtaHttp.get(Ota.signatureUrl(manifestUrl), Ota.MANIFEST_MAX_BYTES)
        ) {
            is OtaHttp.Fetch.Ok -> String(f.bytes, Charsets.US_ASCII)
            is OtaHttp.Fetch.Failed -> return f.toStatus()
        }

        // 4. Verify the bytes, then parse them. Never the other way round.
        val manifest = when (
            val v = ManifestVerifier.verify(app, manifestBytes, signature, state.maxSerialSeen)
        ) {
            is ManifestVerifier.Result.Ok -> v.manifest
            is ManifestVerifier.Result.Rejected -> {
                Log.w(Ota.TAG, "manifest rejected: ${v.why}")
                return OtaStatus.Failed(v.why)
            }
        }
        state.maxSerialSeen = manifest.serial

        // 5. What does it say about us?
        val entry = manifest.apkFor(app.packageName) ?: return upToDate()

        if (!entry.runsOn(Build.VERSION.SDK_INT)) {
            Log.w(
                Ota.TAG,
                "published ${entry.versionName} needs API ${entry.minSdk}..${entry.maxSdk}, " +
                    "this is ${Build.VERSION.SDK_INT}",
            )
            return upToDate()
        }

        // Compared against what is actually installed rather than against
        // BuildConfig. They are the same number in every normal case; when they
        // are not, the package manager is the one telling the truth.
        val installed = installedVersionCode()
        if (entry.versionCode <= installed) return upToDate()

        if (state.isPoisoned(entry.versionCode)) {
            // Two attempts at this versionCode already failed to produce a
            // running build. Offering it a third time would be a loop, and the
            // loop would be on the car's home screen.
            Log.w(Ota.TAG, "poisoned: ${entry.versionName} (${entry.versionCode}) not offered")
            store.prune(keep = null)
            return OtaStatus.Blocked(entry, BlockReason.Poisoned(entry.versionCode))
        }

        Log.i(
            Ota.TAG,
            "update available: ${entry.versionName} (${entry.versionCode}) over $installed, " +
                "serial ${manifest.serial}",
        )
        return OtaStatus.Available(entry)
    }

    private fun upToDate(): OtaStatus {
        // Nothing published for us any more — a withdrawn release, or one we
        // already installed. Whatever was staged is now litter.
        store.prune(keep = null)
        return OtaStatus.UpToDate(BuildConfig.VERSION_NAME)
    }

    // ── Download ────────────────────────────────────────────────────────────

    private fun download(entry: OtaPackage) {
        synchronized(this) {
            if (downloadInFlight) return
            downloadInFlight = true
        }
        if (!submit(downloadExecutor) {
            try {
                publish(OtaStatus.Downloading(entry, 0, entry.size))
                var lastPublished = 0L
                val fetched = store.fetch(entry) { soFar, total ->
                    // Throttled: the pane redraws two TextViews, but posting a
                    // Runnable for every 8 KB of a three-megabyte file is 380
                    // main-thread wake-ups for a progress bar nobody is watching
                    // most of the time.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublished >= PROGRESS_MS || soFar == total) {
                        lastPublished = now
                        publish(OtaStatus.Downloading(entry, soFar, total))
                    }
                }

                val apk = when (fetched) {
                    is OtaStore.Result.Ready -> fetched.apk
                    is OtaStore.Result.Failed -> {
                        publish(
                            if (fetched.clockFault) OtaStatus.Waiting(WaitReason.CLOCK)
                            else OtaStatus.Failed(fetched.reason)
                        )
                        return@submit
                    }
                }

                // The hash proved these are the bytes the manifest describes.
                // This proves the archive is what it claims to be, and — for our
                // own package — that it is signed by the certificate this very
                // process runs under. Both before anything is committed.
                when (val verdict = ApkInspector.inspect(app, apk, entry)) {
                    is ApkInspector.Verdict.Ok -> {
                        Log.i(
                            Ota.TAG,
                            "${apk.name} verified: versionCode=${verdict.versionCode} " +
                                "signer=${verdict.signer}",
                        )
                        publish(OtaStatus.Ready(entry, apk))
                    }
                    is ApkInspector.Verdict.Bad -> {
                        // A file that passed its hash and still is not what it
                        // claims does not get a second chance at this version.
                        Log.e(Ota.TAG, "${apk.name} rejected: ${verdict.reason}")
                        apk.delete()
                        publish(OtaStatus.Failed("APK_${verdict.reason}"))
                    }
                }
            } catch (e: Exception) {
                Log.w(Ota.TAG, "download threw", e)
                publish(OtaStatus.Failed("ERROR_${e.javaClass.simpleName}"))
            } finally {
                synchronized(this) { downloadInFlight = false }
            }
        }) {
            synchronized(this) { downloadInFlight = false }
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")  // PackageInfo.getLongVersionCode is API 28
    private fun installedVersionCode(): Int = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        BuildConfig.VERSION_CODE
    }

    /**
     * Recomputes whether a rollback can be offered. Always on a worker: it
     * stats a directory and probes a hidden API.
     */
    private fun refreshRollback() {
        val kept = store.previous(app.packageName, installedVersionCode())
        rollback = if (kept != null && Downgrade.isSupported()) kept else {
            if (kept != null) {
                Log.i(Ota.TAG, "a previous APK is kept, but this platform refuses downgrades")
            }
            null
        }
    }

    private fun publish(next: OtaStatus) {
        status = next
        ui.post { onChanged(snapshot()) }
    }

    private companion object {
        const val PROGRESS_MS = 200L
    }
}

/**
 * A fetch failure becomes a *waiting* state when it is the clock, and a
 * *failure* otherwise. The distinction is the whole point of [OtaClock]: one of
 * these fixes itself when NTP lands, and the other does not.
 */
private fun OtaHttp.Fetch.Failed.toStatus(): OtaStatus =
    if (clockFault) OtaStatus.Waiting(WaitReason.CLOCK) else OtaStatus.Failed(reason)

/** Why an install was refused. Each one is something the owner can act on. */
internal sealed class BlockReason {
    /** The console is busy with something a restart would interrupt. */
    data class Console(val block: Block) : BlockReason()

    /** REQUEST_INSTALL_PACKAGES is declared but its app-op has never been granted. */
    data object Appop : BlockReason()

    /** This versionCode failed twice. It is not offered again. */
    data class Poisoned(val versionCode: Int) : BlockReason()
}

/** Why the subsystem is holding off. Each has its own message; none is an error. */
internal enum class WaitReason { CLOCK, OFFLINE, UNVALIDATED }

internal sealed class OtaStatus {
    /** Nothing has been attempted yet this boot. */
    data object Idle : OtaStatus()

    data object Checking : OtaStatus()

    data class UpToDate(val versionName: String) : OtaStatus()

    /** Published and newer, but not fetched — a metered link, or a failed fetch. */
    data class Available(val entry: OtaPackage) : OtaStatus()

    data class Downloading(val entry: OtaPackage, val soFar: Long, val total: Long) : OtaStatus()

    /** Downloaded, hash-checked, signature-checked. The only installable state. */
    data class Ready(val entry: OtaPackage, val apk: File) : OtaStatus()

    /**
     * Handed to the system. For our own package this is the last thing anyone
     * here observes — the next event is a new process.
     */
    data class Installing(val entry: OtaPackage) : OtaStatus()

    /** The same, going the other way. */
    data class RollingBack(val toVersionCode: Int) : OtaStatus()

    /**
     * Ready, but not now. Distinct from [Failed] because nothing went wrong and
     * the owner can usually clear it in a second — so INSTALL stays on screen,
     * and pressing it re-evaluates rather than repeating itself.
     */
    data class Blocked(val entry: OtaPackage, val reason: BlockReason) : OtaStatus()

    /** Not an error: a condition expected to clear on its own. */
    data class Waiting(val reason: WaitReason) : OtaStatus()

    /** @param reason a short machine-ish token; the pane maps it to words. */
    data class Failed(val reason: String) : OtaStatus()
}

internal data class OtaSnapshot(
    val status: OtaStatus,
    val installedVersionName: String,
    val installedVersionCode: Int,
    val gitSha: String,
    /** The last observed link charges for bytes, so a download waits for a tap. */
    val metered: Boolean,
    /**
     * Measured on elapsedRealtime, so this is "since boot" and the pane must say
     * so. Null when no check has run in this boot.
     */
    val sinceLastCheckMs: Long?,
    /** Non-null when an earlier APK is kept AND the platform accepts a downgrade. */
    val rollbackVersionCode: Int?,
)
