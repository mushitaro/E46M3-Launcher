package app.tsunagi.e46m3.launcher.launch

/**
 * One way to try to reach a destination.
 *
 * Ordered lists of these are what make the button table survive the central
 * unknown of this project: `com.ts.MainUI` runs at system uid with targetSdk 27,
 * so its filtered activities *should* default to exported — but that cannot be
 * proven from ADB, because the shell holds START_ANY_ACTIVITY and will happily
 * start components a normal app cannot. Only a real startActivity from inside
 * this app settles it, so every target carries fallbacks.
 */
sealed class Step {
    /** Explicit component. The precise destination, and the one that can throw SecurityException. */
    data class Component(val pkg: String, val cls: String) : Step()

    /** Implicit intent — survives a component being renamed or unexported. */
    data class Implicit(val action: String, val data: String? = null) : Step()

    /** Whatever the package itself declares as its launcher entry point. */
    data class PackageMain(val pkg: String) : Step()

    /**
     * A web tool of ours, opened in Chrome rather than the system WebView.
     *
     * The WebView on this unit is Chromium 61 while the Chrome app is 135, which
     * is why a raw WebView is banned project-wide. This step builds a Custom Tab
     * aimed explicitly at Chrome; once `assetlinks.json` is published for the
     * signing certificate the same call site becomes a full-screen Trusted Web
     * Activity, and nothing else in the app has to know the difference.
     */
    data class Web(val url: String) : Step()

    /** Handled inside this app (app list, M menu) — never leaves the process. */
    data object Internal : Step()
}

/**
 * A button and everything it knows about getting somewhere.
 *
 * [steps] is tried in order. This is the only place component names appear;
 * nothing else in the app hard-codes a package or class.
 */
data class LaunchTarget(
    val id: String,
    val labelRes: Int,
    val steps: List<Step>,
) {
    val isInternal: Boolean get() = steps.singleOrNull() == Step.Internal
}
