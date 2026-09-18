package app.tsunagi.e46m3.launcher.launch

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.trusted.TrustedWebActivityDisplayMode
import androidx.browser.trusted.TrustedWebActivityIntentBuilder

/** What actually happened, so callers never have to guess. */
sealed class LaunchOutcome {
    data class Started(val step: Step) : LaunchOutcome()
    data object Internal : LaunchOutcome()
    data object Failed : LaunchOutcome()
}

/**
 * Starts a [LaunchTarget], degrading through its fallbacks, and never throwing
 * into the caller.
 *
 * The central design constraint: **`resolveActivity` returning non-null does not
 * prove we are allowed to start something.** Resolution ignores `exported` and
 * permission checks. So two mechanisms are kept, and they answer different
 * questions:
 *
 *  - [resolves] → "is it installed?"           (pre-flight dimming)
 *  - [launch]'s catch blocks → "are we allowed?" (runtime truth)
 *
 * Every outcome is recorded, so after a few days of real use the accumulated
 * matrix is the empirical answer to the exported question that ADB cannot give
 * (the shell holds START_ANY_ACTIVITY and will start things we cannot).
 */
class TargetLauncher(
    private val context: Context,
    private val onNotice: (String) -> Unit,
) {
    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intentFor(step: Step): Intent? {
        val intent = when (step) {
            is Step.Component ->
                Intent(Intent.ACTION_MAIN).setClassName(step.pkg, step.cls)

            is Step.Implicit ->
                Intent(step.action).apply { step.data?.let { data = Uri.parse(it) } }

            is Step.PackageMain ->
                pm.getLaunchIntentForPackage(step.pkg)

            is Step.Web -> webIntent(step.url)

            Step.Internal -> null
        } ?: return null

        // What a launcher does: a fresh task, and bring an existing one forward
        // rather than stacking a duplicate.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return intent
    }

    // ── Our own web tools ────────────────────────────────────────────────────

    private var session: CustomTabsSession? = null
    private var bound = false

    private val chromeConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            client.warmup(0)
            session = client.newSession(null)
            // Chrome starts fetching assetlinks.json and the page itself now,
            // while the M console is still animating open.
            for (url in WEB_URLS) session?.mayLaunchUrl(Uri.parse(url), null, null)
            Log.i(TAG, "Chrome session ${if (session != null) "ready" else "refused"}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            session = null
        }
    }

    /**
     * Binds Chrome and pre-warms our web targets. Called when the M console
     * opens, not at startup.
     *
     * Two things need it. **A Trusted Web Activity cannot be built without a
     * session** — the whole difference between a TWA and a Custom Tab is an
     * extra carried on a session-bearing intent — so without this the TUNER key
     * degrades to a toolbar and a bookmark. And Chrome's warm-up costs tens of
     * megabytes on a unit with about 1.1GB free, which is worth spending one
     * press before the key is used and not worth spending on every boot.
     */
    fun warmUp() {
        if (bound || WEB_URLS.isEmpty()) return
        val chrome = chromePackage() ?: return
        bound = runCatching {
            CustomTabsClient.bindCustomTabsService(context, chrome, chromeConnection)
        }.getOrElse {
            Log.w(TAG, "could not bind Chrome", it); false
        }
    }

    /** Lets Chrome go. The session dies with it; the next warmUp makes another. */
    fun release() {
        if (!bound) return
        bound = false
        session = null
        runCatching { context.unbindService(chromeConnection) }
    }

    /**
     * Set for the duration of one [launch] call. A field rather than a
     * parameter because [intentFor] is also the pre-flight resolver, and giving
     * every caller of that an argument it does not care about would spread this
     * one special case across the whole file.
     */
    private var resume = false

    /**
     * One of our web tools, pinned to Chrome.
     *
     * Chrome is named rather than left to the default handler because the
     * default may well be a WebView-backed browser, and the WebView here is
     * Chromium 61. Falling back to whatever is installed would silently hand our
     * own tools a seven-year-old engine — the exact failure this project bans
     * WebView to avoid. If Chrome is absent the step simply does not resolve and
     * the next one is tried.
     *
     * ## Trusted Web Activity, degrading to a Custom Tab
     *
     * With a warm session this is a TWA: no toolbar, no URL bar, the page owns
     * the whole panel, and it is our app on screen rather than a browser showing
     * our site. Without one — [warmUp] not called yet, Chrome refused the
     * binding, the service died — it is the Custom Tab that shipped before, and
     * the key still works.
     *
     * The fallback is not only for a missing session. **Chrome fetches
     * assetlinks.json over the network when the TWA starts**, and if that fetch
     * fails it shows the toolbar by itself. So this can end up looking like a
     * Custom Tab in a garage with no WiFi even when everything is configured
     * correctly, and that is a cosmetic outcome rather than a broken key.
     */
    private fun webIntent(url: String): Intent? {
        val chrome = chromePackage() ?: return null
        val uri = if (resume) {
            Uri.parse(url).buildUpon().appendQueryParameter("resume", "1").build()
        } else {
            Uri.parse(url)
        }
        val colours = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.BLACK)
            .setNavigationBarColor(Color.BLACK)
            .build()

        val intent = session?.let { live ->
            TrustedWebActivityIntentBuilder(uri)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setDefaultColorSchemeParams(colours)
                // Edge to edge, status bar included. Verification alone only
                // removes Chrome's own toolbar; the head unit's status bar sits
                // above that and is the last 48px between our tool and the
                // whole panel. A Custom Tab cannot ask for this — it is one of
                // the things being a Trusted Web Activity actually buys.
                .setDisplayMode(TrustedWebActivityDisplayMode.ImmersiveMode(
                    /* isSticky = */ true,
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                ))
                .build(live)
                .intent
        } ?: CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colours)
            .build()
            .intent
            .apply { data = uri }

        return intent.apply { setPackage(chrome) }
    }

    private fun chromePackage(): String? = CHROME_PACKAGES.firstOrNull { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Installed and resolvable. Says nothing about whether we may start it. */
    fun resolves(step: Step): Boolean {
        val intent = intentFor(step) ?: return false
        return try {
            pm.resolveActivity(intent, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    /** True if any step resolves. Drives pre-flight dimming — position never moves. */
    fun isAvailable(target: LaunchTarget): Boolean =
        target.isInternal || target.steps.any { resolves(it) }

    /**
     * @param resume marks a web target as being reopened after the unit
     *   restarted under it, rather than opened by a press. It reaches the page
     *   as `?resume=1` — see docs/05-tuner-resume-spec.md for what the tool does
     *   with it. Ignored by every other kind of step, which have no such notion.
     */
    fun launch(target: LaunchTarget, resume: Boolean = false): LaunchOutcome {
        if (target.isInternal) return LaunchOutcome.Internal
        this.resume = resume
        // Cleared however this returns. The flag belongs to one call, and
        // [resolves] shares the same intent builder — leaving it set would mean
        // the pre-flight check quietly asking about a URL nobody requested.
        try {
            return attempt(target)
        } finally {
            this.resume = false
        }
    }

    private fun attempt(target: LaunchTarget): LaunchOutcome {

        // Try the previously winning step first; it is almost always right, and
        // it avoids re-paying a SecurityException on every single press.
        val remembered = prefs.getInt(keyWinner(target), -1)
        val order = if (remembered in target.steps.indices) {
            listOf(remembered) + target.steps.indices.filter { it != remembered }
        } else {
            target.steps.indices.toList()
        }

        for (i in order) {
            val step = target.steps[i]
            val intent = intentFor(step)
            if (intent == null) {
                record(target, i, "NO_INTENT")
                continue
            }
            try {
                // ActivityManager truncates the URI in its own START line, so
                // the only way to see whether `?resume=1` actually went out is
                // to say so here. One line per press, and it is the check the
                // resume path is verified with on the car.
                if (step is Step.Web) Log.i(TAG, "opening ${intent.data}")
                context.startActivity(intent)
                prefs.edit().putInt(keyWinner(target), i).apply()
                record(target, i, "OK")
                return LaunchOutcome.Started(step)
            } catch (e: SecurityException) {
                // The interesting one: the component exists but is not exported
                // to us. This is the answer ADB structurally cannot provide.
                record(target, i, "SECURITY")
                Log.w(TAG, "not exported to us: ${describe(step)}", e)
            } catch (e: ActivityNotFoundException) {
                record(target, i, "NOT_FOUND")
            } catch (e: Exception) {
                record(target, i, "ERR_${e.javaClass.simpleName}")
                Log.w(TAG, "launch failed: ${describe(step)}", e)
            }
        }

        // Non-fatal and must stay visible without a Toast: a Toast is a system
        // window we do not control and can land on top of a freshly-launched app.
        onNotice(context.getString(target.labelRes).uppercase() + "  UNAVAILABLE")
        return LaunchOutcome.Failed
    }

    // ── The empirical record ─────────────────────────────────────────────────

    private fun record(target: LaunchTarget, stepIndex: Int, result: String) {
        prefs.edit().putString(keyResult(target, stepIndex), result).apply()
    }

    /** Formats everything observed so far — the raw material for docs/04-target-matrix.md. */
    fun dumpMatrix(): String = buildString {
        appendLine("# Target matrix — observed from inside the app")
        appendLine("# OK / SECURITY (exists, not exported to us) / NOT_FOUND / ERR_* / (untried)")
        appendLine()
        for (target in Targets.ALL) {
            if (target.isInternal) continue
            val winner = prefs.getInt(keyWinner(target), -1)
            appendLine("## ${target.id}")
            target.steps.forEachIndexed { i, step ->
                val result = prefs.getString(keyResult(target, i), null) ?: "(untried)"
                val mark = if (i == winner) " <-- winner" else ""
                appendLine("  [$i] ${describe(step)} => $result$mark")
            }
            appendLine()
        }
    }

    private fun describe(step: Step): String = when (step) {
        is Step.Component -> "component ${step.pkg}/${step.cls}"
        is Step.Implicit -> "implicit ${step.action}${step.data?.let { " $it" } ?: ""}"
        is Step.PackageMain -> "packageMain ${step.pkg}"
        is Step.Web -> "web ${step.url}"
        Step.Internal -> "internal"
    }

    private fun keyWinner(t: LaunchTarget) = "winner.${t.id}"
    private fun keyResult(t: LaunchTarget, i: Int) = "result.${t.id}.$i"

    companion object {
        private const val TAG = "TargetLauncher"
        private const val PREFS = "launch_targets"

        /** Chrome 135 is installed on this unit; the others are ordinary fallbacks. */
        private val CHROME_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
        )

        /** Every web target, read off the one table rather than repeated here. */
        private val WEB_URLS: List<String> =
            Targets.ALL.flatMap { it.steps }.filterIsInstance<Step.Web>().map { it.url }
    }
}
