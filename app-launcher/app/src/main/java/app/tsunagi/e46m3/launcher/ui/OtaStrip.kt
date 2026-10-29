package app.tsunagi.e46m3.launcher.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.tsunagi.e46m3.launcher.R
import app.tsunagi.e46m3.launcher.ota.BlockReason
import app.tsunagi.e46m3.launcher.ota.OtaSnapshot
import app.tsunagi.e46m3.launcher.ota.OtaStatus
import app.tsunagi.e46m3.launcher.ota.WaitReason

/**
 * The update line on the home screen, above the display window.
 *
 * ## Why the home screen says this at all
 *
 * Until 2026-10-29 the only sign of an update was "UPDATE 0.3.1" in the LCD's
 * source line, and the version only appeared inside the pane a tap on the
 * display window opened. On the car nobody found either: the owner could not
 * say which version was running, nor whether a check had ever reached the
 * network. So this line always says three things — the version, what the last
 * check found, and how long ago — and the update itself is a button rather
 * than a hint.
 *
 * ## One button, the whole way
 *
 * UPDATE fetches what is not yet on disk and then opens the system's install
 * screen, from a single press (HomeActivity holds that intent across the
 * download). When something stops it, the reason is printed on the line below
 * rather than left in the pane.
 *
 * The same rules as [OtaLayer]: nothing fabricated, and a control that cannot
 * act is removed rather than greyed. Tapping the status text opens the pane,
 * which keeps the details (signer, notes, CHECK, ROLLBACK).
 */
internal class OtaStrip(
    root: View,
    onDetails: () -> Unit,
    private val onUpdate: () -> Unit,
    private val onGrantInstallPermission: () -> Unit,
) {
    private val status: TextView = root.findViewById(R.id.ota_strip_status)
    private val reason: TextView = root.findViewById(R.id.ota_strip_reason)
    private val button: TextView = root.findViewById(R.id.ota_strip_button)

    init {
        status.setOnClickListener { onDetails() }
        reason.setOnClickListener { onDetails() }
    }

    fun bind(snapshot: OtaSnapshot) {
        val res = status.resources
        var why: String? = null
        var action: (() -> Unit)? = null
        var actionLabel: String? = null

        val state = when (val s = snapshot.status) {
            is OtaStatus.Idle -> res.getString(R.string.ota_headline_idle)
            is OtaStatus.Checking -> res.getString(R.string.ota_headline_checking)
            is OtaStatus.UpToDate -> res.getString(R.string.ota_headline_up_to_date)

            is OtaStatus.Available -> {
                action = onUpdate
                actionLabel = res.getString(R.string.ota_strip_update, s.entry.versionName)
                s.failure?.let { why = res.getString(R.string.ota_download_failed, it) }
                res.getString(R.string.ota_strip_available, s.entry.versionName)
            }

            is OtaStatus.Downloading -> res.getString(
                R.string.ota_strip_downloading,
                OtaLayer.formatSize(s.soFar),
                OtaLayer.formatSize(s.total),
            )

            is OtaStatus.Ready -> {
                action = onUpdate
                actionLabel = res.getString(R.string.ota_strip_update, s.entry.versionName)
                res.getString(R.string.ota_strip_ready, s.entry.versionName)
            }

            is OtaStatus.Installing -> res.getString(R.string.ota_headline_installing)
            is OtaStatus.RollingBack -> res.getString(R.string.ota_headline_rolling_back)

            is OtaStatus.Blocked -> {
                why = res.getString(OtaLayer.blockMessage(s.reason))
                when (s.reason) {
                    is BlockReason.Appop -> {
                        action = onGrantInstallPermission
                        actionLabel = res.getString(R.string.ota_action_settings)
                    }
                    // Pressing again re-evaluates: the engine may have stopped.
                    is BlockReason.Console -> {
                        action = onUpdate
                        actionLabel = res.getString(R.string.ota_strip_update, s.entry.versionName)
                    }
                    is BlockReason.Poisoned -> Unit
                }
                res.getString(R.string.ota_headline_blocked)
            }

            is OtaStatus.Waiting -> {
                why = res.getString(
                    when (s.reason) {
                        WaitReason.OFFLINE -> R.string.ota_detail_offline
                        WaitReason.UNVALIDATED -> R.string.ota_detail_unvalidated
                    }
                )
                res.getString(
                    when (s.reason) {
                        WaitReason.OFFLINE -> R.string.ota_headline_offline
                        WaitReason.UNVALIDATED -> R.string.ota_headline_unvalidated
                    }
                )
            }

            is OtaStatus.Failed -> {
                why = res.getString(R.string.ota_strip_failed, s.reason)
                res.getString(R.string.ota_headline_failed)
            }
        }

        // No age before the first check, and none while one runs: "NOT CHECKED ·
        // NOT CHECKED THIS BOOT" says one thing twice.
        val checked = snapshot.sinceLastCheckMs
            ?.let { res.getString(R.string.ota_checked_ago, OtaLayer.formatAgo(it)) }
        status.text = when (snapshot.status) {
            is OtaStatus.Idle, is OtaStatus.Checking -> null
            else -> checked
        }?.let {
            res.getString(R.string.ota_strip_status, snapshot.installedVersionName, state, it)
        } ?: res.getString(R.string.ota_strip_status_short, snapshot.installedVersionName, state)

        // INVISIBLE rather than GONE: the line keeps its place, so a reason
        // arriving or leaving moves nothing else on the console.
        reason.text = why ?: ""
        reason.visibility = if (why == null) View.INVISIBLE else View.VISIBLE

        if (action == null) {
            button.visibility = View.GONE
        } else {
            button.text = actionLabel
            button.setOnClickListener { action() }
            button.setTextColor(ContextCompat.getColor(button.context, R.color.d_lit))
            button.visibility = View.VISIBLE
        }
    }
}
