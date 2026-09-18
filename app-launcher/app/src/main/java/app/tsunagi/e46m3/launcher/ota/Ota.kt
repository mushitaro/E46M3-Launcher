package app.tsunagi.e46m3.launcher.ota

import android.content.Context
import java.io.File

/**
 * Shared constants and the on-disk layout for the OTA subsystem.
 *
 * ## What this subsystem is for
 *
 * Until now the only way to update this launcher was `tools/deploy.sh`: a
 * person at a PC, on the same subnet as the car, with the ignition on. This
 * lets the car fetch its own updates instead.
 *
 * ## The two constraints that shape everything here
 *
 * **The clock is unreliable.** `device-extract/bugreport.zip` is named
 * `bugreport-FF-5000-O11019-2006-12-18-…` and `sdcard/TsCrash/` holds
 * `crash-2006-02-03-…`. With the RTC reading 2006, an HTTPS handshake fails
 * with `CertificateNotYetValidException` before a single byte of payload
 * arrives. So integrity here does NOT rest on TLS: the manifest carries our own
 * RSA-2048/SHA-256 signature, verified against a key compiled into the APK, and
 * every APK is checked by content hash and by signing certificate. TLS is
 * transport hygiene. A wrong clock can cost availability; it cannot cost
 * integrity.
 *
 * **The app being updated is the home screen.** Losing HOME strands the unit on
 * `com.android.settings/.FallbackHome`, and recovery then needs the car powered
 * and on the right WiFi (docs/04 §12). Every decision below prefers "do
 * nothing, say why" over "try something clever".
 */
internal object Ota {

    const val TAG = "Ota"

    /**
     * Where staged downloads live.
     *
     * `filesDir`, not the cache directory: the platform may delete cache at any
     * time, and a half-deleted APK that still has its `.part` bookkeeping would
     * make a resume append to nothing. /data had ~20 GB free at survey time, so
     * the few MB this holds are not a concern.
     */
    fun dir(context: Context): File = File(context.filesDir, "ota").apply { mkdirs() }

    /** Where the previous APK is kept, so a bad build has somewhere to fall back to. */
    fun prevDir(context: Context): File = File(dir(context), "prev").apply { mkdirs() }

    fun apkFile(context: Context, packageName: String, versionCode: Int): File =
        File(dir(context), "$packageName-$versionCode.apk")

    fun partFile(context: Context, packageName: String, versionCode: Int): File =
        File(dir(context), "$packageName-$versionCode.apk.part")

    /**
     * The detached signature sits next to the manifest under the same name.
     *
     * Derived rather than configured: two independently settable URLs is two
     * chances to point them at different releases, and the failure would look
     * like a corrupt signature rather than a misconfiguration.
     */
    fun signatureUrl(manifestUrl: String): String = "$manifestUrl.sig"

    /** The only manifest schema this build understands. */
    const val SCHEMA = 1

    /** The only package kind this build acts on. Anything else is ignored, not an error. */
    const val KIND_APK = "apk"

    // ── Network ─────────────────────────────────────────────────────────────
    // Short, because the check runs on the same single thread that loads the
    // app catalogue. The worst this can delay a catalogue refresh is connect +
    // read, and it must stay under the time somebody would spend on the console
    // before pressing something.
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 8_000

    /** Redirect hops followed by hand. GitHub's asset URL takes two. */
    const val MAX_REDIRECTS = 5

    /**
     * A ceiling on the manifest, so a wrong URL that happens to serve something
     * enormous cannot be read into a 192 MB heap before it is rejected.
     * The real manifest is ~600 bytes.
     */
    const val MANIFEST_MAX_BYTES = 256 * 1024

    // ── Scheduling ──────────────────────────────────────────────────────────

    /**
     * How long after the first frame the check is allowed to start.
     *
     * The cold-start budget is 800 ms and this screen cold-starts on every boot,
     * every KILL_APPS and every low-memory kill. Six seconds is comfortably past
     * the catalogue load and the wallpaper write, both of which share this
     * thread.
     */
    const val CHECK_DELAY_MS = 6_000L

    /**
     * Minimum spacing between checks, measured on [android.os.SystemClock.elapsedRealtime].
     *
     * NOT on the wall clock. A wall-clock throttle would be governed by the one
     * value this whole subsystem is built not to trust: an RTC reading 2037
     * would suppress checks for years, and one reading 2006 would re-check on
     * every process start. elapsedRealtime resets at every key-off, and this
     * unit cold-boots on every key cycle, so in practice this means "once per
     * boot" — which is the intended behaviour, reached by a route that cannot
     * be broken by a bad clock.
     */
    const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
}
