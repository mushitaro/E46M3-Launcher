package app.tsunagi.e46m3.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.Collator
import java.util.Locale

/** One launchable app, reduced to exactly what the design's list shows. */
data class AppEntry(
    val label: String,
    val component: ComponentName,
    /** The badge glyph: the first character of the label. */
    val initial: String,
)

/**
 * Everything on this unit that declares a launcher entry point.
 *
 * The design ships a hand-written table of 32 apps as a mock-up. This enumerates
 * the device instead, which is the requirement — *every* app reachable — and
 * means a sideloaded APK appears without a rebuild.
 *
 * ## Why there are no icons
 *
 * The design shows an initial in a rounded badge rather than the app's icon, and
 * that is followed here rather than "improved" on. It happens to also be the
 * only cheap option: `loadIcon()` opens each target APK's resources and builds a
 * Drawable, which on 2GB of RAM with a 192MB heap limit forces the whole
 * lazy-load / LruCache / generation-counter apparatus the plan sketched. Sixty
 * badges cost sixty short strings.
 *
 * ## Why the sort uses a Collator
 *
 * Half these labels are Japanese. `String.compareTo` sorts by UTF-16 code unit,
 * which puts every kana after every Latin letter and orders kanji by codepoint —
 * i.e. arbitrarily. A JAPAN Collator gives the order a Japanese reader expects.
 */
object AppCatalog {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager

        val standard = query(pm, Intent.CATEGORY_LAUNCHER)

        // This unit's vendor apps declare their entry point under a private
        // category instead of LAUNCHER, so three of them — the boot-logo tool,
        // CarPlay and the CAN updater — are invisible to the standard query and
        // would be unreachable from a list that only asked for LAUNCHER.
        //
        // Only packages with NO standard entry are taken from it. MainUI alone
        // declares 35 MYLAUNCHER activities (its radio, media, Bluetooth and
        // settings screens); folding those in would bury the app list under one
        // app's internal pages, and they are screens, not apps. One entry per
        // otherwise-unreachable package is what "every app is reachable" needs.
        val known = standard.mapTo(HashSet()) { it.activityInfo?.packageName }
        val vendorOnly = query(pm, CATEGORY_MYLAUNCHER)
            .filter { it.activityInfo?.packageName !in known }
            .distinctBy { it.activityInfo?.packageName }

        val self = context.packageName
        val resolved = standard + vendorOnly
        val entries = ArrayList<AppEntry>(resolved.size)
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            // A launcher listing itself is a loop with a nicer name.
            if (activity.packageName == self) continue

            val label = runCatching { info.loadLabel(pm).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: activity.packageName

            entries += AppEntry(
                label = label,
                component = ComponentName(activity.packageName, activity.name),
                initial = initialOf(label),
            )
        }

        val collator = Collator.getInstance(Locale.JAPAN)
        entries.sortWith { a, b -> collator.compare(a.label, b.label) }
        return entries
    }

    private fun query(pm: android.content.pm.PackageManager, category: String) = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
    } catch (e: Exception) {
        Log.w(TAG, "could not enumerate $category activities", e)
        emptyList()
    }

    /**
     * The first character, uppercased.
     *
     * Uppercasing is a no-op for kana and kanji, so one rule covers both scripts
     * — "Chrome" gives C, "設定" gives 設, exactly as the design's hand-written
     * initials do.
     */
    private fun initialOf(label: String): String {
        val c = label.firstOrNull { !it.isWhitespace() } ?: return "?"
        return c.uppercaseChar().toString()
    }

    /** An explicit component start, so the entry launches what the list showed. */
    fun intentFor(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private const val TAG = "AppCatalog"

    /** This vendor's private stand-in for CATEGORY_LAUNCHER. Verified on the unit. */
    private const val CATEGORY_MYLAUNCHER = "android.intent.category.MYLAUNCHER"
}
