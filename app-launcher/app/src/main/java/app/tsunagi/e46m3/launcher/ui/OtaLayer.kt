package app.tsunagi.e46m3.launcher.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R
import app.tsunagi.e46m3.launcher.ota.Block
import app.tsunagi.e46m3.launcher.ota.BlockReason
import app.tsunagi.e46m3.launcher.ota.OtaPackage
import app.tsunagi.e46m3.launcher.ota.OtaSnapshot
import app.tsunagi.e46m3.launcher.ota.OtaStatus
import app.tsunagi.e46m3.launcher.ota.WaitReason
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The update pane: binding and the show/hide fade.
 *
 * Shaped after [AppListLayer] — the same 160 ms `m3fade`, the same "hide the
 * console rather than paint a second carbon field", the same close control.
 *
 * ## The two rules this layer exists to keep
 *
 * **Nothing fabricated.** The elapsed reading is `elapsedRealtime`-derived, so
 * it resets at key-off; rather than show a cross-boot number that looks like a
 * recent check, it says NOT CHECKED THIS BOOT. There is no placeholder version,
 * and the progress bar only ever shows bytes that are genuinely on disk.
 *
 * **No control that cannot act.** Buttons are removed, not greyed — matching
 * the console itself, which blanks a socket rather than showing a dead key
 * (design.xml, on the removed DEAD face). When an action is unavailable, the
 * reason takes its place in the detail line.
 */
internal class OtaLayer(
    val root: View,
    private val onCheck: () -> Unit,
    private val onDownload: () -> Unit,
    private val onInstall: () -> Unit,
    private val onRollback: () -> Unit,
    private val onRemove: () -> Unit,
    private val onGrantInstallPermission: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val headline: TextView = root.findViewById(R.id.ota_headline)
    private val detail: TextView = root.findViewById(R.id.ota_detail)
    private val notes: TextView = root.findViewById(R.id.ota_notes)
    private val footer: TextView = root.findViewById(R.id.ota_footer)
    private val check: TextView = root.findViewById(R.id.ota_check)
    private val rollback: TextView = root.findViewById(R.id.ota_rollback)
    private val action: TextView = root.findViewById(R.id.ota_action)
    private val progress: View = root.findViewById(R.id.ota_progress)
    private val progressFill: View = root.findViewById(R.id.ota_progress_fill)

    init {
        root.findViewById<View>(R.id.ota_close).setOnClickListener { onClose() }
        check.setOnClickListener { onCheck() }
        rollback.setOnClickListener { onRollback() }
        // Hidden on purpose, in the same spirit as the launch-matrix dump on the
        // display window: removing the home screen is not something to be one
        // mis-tap away from, and nobody needs to find it by accident.
        footer.setOnLongClickListener { onRemove(); true }
        // Scaled from the left edge rather than re-measured: a progress update
        // becomes a RenderNode property, with no layout pass behind it.
        progressFill.pivotX = 0f
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun show(snapshot: OtaSnapshot) {
        bind(snapshot)
        root.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0L)
    }

    fun hide() {
        root.animate().cancel()
        root.alpha = 1f
        root.visibility = View.GONE
    }

    fun bind(snapshot: OtaSnapshot) {
        val res = root.resources

        headline.setTextColor(ContextCompat.getColor(root.context, R.color.d_al_title))
        notes.visibility = View.GONE
        progress.visibility = View.GONE
        action.visibility = View.GONE

        when (val status = snapshot.status) {
            is OtaStatus.Idle -> {
                headline.text = res.getString(R.string.ota_headline_idle)
                detail.text = res.getString(R.string.ota_detail_idle)
            }

            is OtaStatus.Checking -> {
                headline.text = res.getString(R.string.ota_headline_checking)
                detail.text = res.getString(R.string.ota_detail_checking)
            }

            is OtaStatus.UpToDate -> {
                headline.text = res.getString(R.string.ota_headline_up_to_date)
                detail.text = res.getString(R.string.ota_detail_up_to_date)
            }

            is OtaStatus.Available -> {
                accentHeadline(snapshot, status.entry)
                // Metered is the only way to reach Available with a link up: an
                // unmetered check starts the download itself. Say which it is.
                detail.text = if (snapshot.metered) {
                    res.getString(R.string.ota_detail_metered)
                } else {
                    entryLine(status.entry)
                }
                action.text = res.getString(R.string.ota_action_download)
                action.setOnClickListener { onDownload() }
                action.visibility = View.VISIBLE
                bindNotes(status.entry)
            }

            is OtaStatus.Downloading -> {
                accentColour()
                headline.text = res.getString(R.string.ota_headline_downloading)
                detail.text = res.getString(
                    R.string.ota_detail_downloading,
                    formatSize(status.soFar),
                    formatSize(status.total),
                )
                progress.visibility = View.VISIBLE
                progressFill.scaleX =
                    if (status.total > 0) (status.soFar.toDouble() / status.total).toFloat()
                    else 0f
            }

            is OtaStatus.Ready -> {
                accentHeadline(snapshot, status.entry)
                detail.text = entryLine(status.entry, verified = true)
                showAction(R.string.ota_action_install, onInstall)
                bindNotes(status.entry)
            }

            is OtaStatus.Installing -> {
                accentColour()
                headline.text = res.getString(R.string.ota_headline_installing)
                detail.text = res.getString(R.string.ota_detail_installing)
                // No button, and none is wanted: the system dialog owns the
                // screen now, and after it the process is replaced.
            }

            is OtaStatus.Blocked -> {
                headline.text = res.getString(R.string.ota_headline_blocked)
                detail.text = res.getString(blockMessage(status.reason))
                when (status.reason) {
                    // The app-op is the one refusal the owner cannot clear from
                    // here, so the button goes to the screen that can.
                    is BlockReason.Appop ->
                        showAction(R.string.ota_action_settings, onGrantInstallPermission)
                    // The others usually clear in seconds — unplug the cable,
                    // close the M console. INSTALL stays, and pressing it
                    // re-evaluates rather than repeating the refusal.
                    is BlockReason.Console -> showAction(R.string.ota_action_install, onInstall)
                    // Nothing to press. A version that failed twice is not
                    // offered a third time, and a button here would be a
                    // control that must not act.
                    is BlockReason.Poisoned -> Unit
                }
            }

            is OtaStatus.Waiting -> {
                headline.text = res.getString(
                    when (status.reason) {
                        WaitReason.CLOCK -> R.string.ota_headline_clock
                        WaitReason.OFFLINE -> R.string.ota_headline_offline
                        WaitReason.UNVALIDATED -> R.string.ota_headline_unvalidated
                    }
                )
                detail.text = res.getString(
                    when (status.reason) {
                        WaitReason.CLOCK -> R.string.ota_detail_clock
                        WaitReason.OFFLINE -> R.string.ota_detail_offline
                        WaitReason.UNVALIDATED -> R.string.ota_detail_unvalidated
                    }
                )
            }

            is OtaStatus.RollingBack -> {
                accentColour()
                headline.text = res.getString(R.string.ota_headline_rolling_back)
                detail.text = res.getString(R.string.ota_detail_rolling_back)
            }

            is OtaStatus.Failed -> {
                headline.text = res.getString(R.string.ota_headline_failed)
                detail.text = res.getString(R.string.ota_detail_failed, status.reason)
            }
        }

        // Removed rather than disabled, like every other control here.
        val busy = when (snapshot.status) {
            is OtaStatus.Checking, is OtaStatus.Downloading,
            is OtaStatus.Installing, is OtaStatus.RollingBack -> true
            else -> false
        }
        check.visibility = if (busy) View.GONE else View.VISIBLE

        val previous = snapshot.rollbackVersionCode
        if (previous != null && !busy) {
            rollback.text = res.getString(R.string.ota_action_rollback, previous)
            rollback.visibility = View.VISIBLE
        } else {
            rollback.visibility = View.GONE
        }

        footer.text = res.getString(
            R.string.ota_footer,
            snapshot.installedVersionName,
            snapshot.gitSha,
            snapshot.sinceLastCheckMs
                ?.let { res.getString(R.string.ota_checked_ago, formatAgo(it)) }
                ?: res.getString(R.string.ota_checked_never),
        )
    }

    /** An update in play is the one thing on this console worth the single accent. */
    private fun accentColour() {
        headline.setTextColor(ContextCompat.getColor(root.context, R.color.d_lit))
    }

    private fun accentHeadline(snapshot: OtaSnapshot, entry: OtaPackage) {
        accentColour()
        headline.text = root.resources.getString(
            R.string.ota_headline_available,
            snapshot.installedVersionName,
            entry.versionName,
        )
    }

    private fun entryLine(entry: OtaPackage, verified: Boolean = false): String =
        root.resources.getString(
            if (verified) R.string.ota_detail_ready else R.string.ota_detail_available,
            formatSize(entry.size),
            abbreviateSigner(entry.signerNormalised),
        )

    private fun showAction(labelRes: Int, onTap: () -> Unit) {
        action.text = root.resources.getString(labelRes)
        action.setOnClickListener { onTap() }
        action.visibility = View.VISIBLE
    }

    /**
     * One message per refusal.
     *
     * A refusal nobody can diagnose is a bug report, and this one would be filed
     * from a driver's seat. Each string names the thing that would have been
     * interrupted, so the owner knows what to change.
     */
    private fun blockMessage(reason: BlockReason): Int = when (reason) {
        is BlockReason.Appop -> R.string.ota_block_appop
        is BlockReason.Poisoned -> R.string.ota_block_poisoned
        is BlockReason.Console -> when (reason.block) {
            Block.NOT_RESUMED -> R.string.ota_block_not_resumed
            Block.M_CONSOLE -> R.string.ota_block_m_console
            Block.RESUME_ARMED -> R.string.ota_block_resume_armed
            Block.APP_LIST -> R.string.ota_block_app_list
            Block.CABLE -> R.string.ota_block_cable
            Block.DS2_LIVE -> R.string.ota_block_ds2_live
            Block.ENGINE_RUNNING -> R.string.ota_block_engine_running
        }
    }

    private fun bindNotes(entry: OtaPackage) {
        if (entry.releaseNotes.isEmpty()) return
        notes.text = entry.releaseNotes.joinToString("\n") { "・$it" }
        notes.visibility = View.VISIBLE
    }

    companion object {
        private const val FADE_MS = 160L

        /**
         * Download size, one decimal place, MB above a megabyte.
         *
         * Binary MB, because that is what every other number the owner will
         * compare this against uses — `adb`, the file manager, the storage
         * screen. A "3.1 MB" here and a "2.97 MB" there is the kind of small
         * disagreement that costs somebody ten minutes.
         */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        /** `8E52…B580` — enough to compare against apksigner by eye, short enough to fit. */
        fun abbreviateSigner(signer: String): String =
            if (signer.length <= 12) signer
            else signer.take(4) + "…" + signer.takeLast(4)

        /**
         * Coarse on purpose. Nobody acts differently on 4 minutes versus 4
         * minutes 20, and a seconds-resolution value implies a precision the
         * throttle does not have.
         */
        fun formatAgo(millis: Long): String {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            return when {
                hours >= 1 -> "${hours}H"
                minutes >= 1 -> "${minutes}M"
                else -> "<1M"
            }
        }
    }
}
