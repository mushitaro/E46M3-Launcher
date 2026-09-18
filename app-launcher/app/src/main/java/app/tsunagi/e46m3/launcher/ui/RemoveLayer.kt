package app.tsunagi.e46m3.launcher.ui

import android.view.View
import android.widget.TextView
import app.tsunagi.e46m3.launcher.R

/**
 * The four-step procedure for handing HOME back and removing this launcher.
 *
 * Purely presentational: it renders a step index and a message, and reports
 * taps. Every decision — whether a step may be taken, whether the platform
 * actually handed HOME over — belongs to
 * [app.tsunagi.e46m3.launcher.ota.Relinquish], because those are facts about
 * the device rather than about the screen.
 *
 * ## The steps never move
 *
 * All four are drawn from the first frame, each carrying its own mark. A
 * procedure that reveals the next step only after the previous one committed is
 * how somebody ends up half way through this one, in a garage, with no laptop.
 */
internal class RemoveLayer(
    val root: View,
    private val onAdvance: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val steps: TextView = root.findViewById(R.id.remove_steps)
    private val action: TextView = root.findViewById(R.id.remove_action)
    private val recovery: TextView = root.findViewById(R.id.remove_recovery)

    init {
        root.findViewById<View>(R.id.remove_close).setOnClickListener { onClose() }
        action.setOnClickListener { onAdvance() }
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun show(state: State, recoveryCommands: List<String>) {
        recovery.text = recoveryCommands.joinToString("\n")
        bind(state)
        root.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0L)
    }

    fun hide() {
        root.animate().cancel()
        root.alpha = 1f
        root.visibility = View.GONE
    }

    fun bind(state: State) {
        val res = root.resources
        val labels = listOf(
            R.string.remove_step_clear,
            R.string.remove_step_pick,
            R.string.remove_step_verify,
            R.string.remove_step_uninstall,
        )

        steps.text = labels.indices.joinToString("\n") { i ->
            val mark = when {
                state.failedAt == i -> MARK_FAILED
                i < state.done -> MARK_DONE
                i == state.done -> MARK_NEXT
                else -> MARK_TODO
            }
            "$mark ${i + 1}  ${res.getString(labels[i])}"
        } + state.message?.let { "\n\n$it" }.orEmpty()

        action.text = res.getString(
            when {
                state.failedAt != null -> R.string.remove_action_retry
                state.done >= labels.size -> R.string.remove_action_done
                else -> labels.indices.map {
                    when (it) {
                        0 -> R.string.remove_action_clear
                        1 -> R.string.remove_action_pick
                        2 -> R.string.remove_action_verify
                        else -> R.string.remove_action_uninstall
                    }
                }[state.done]
            }
        )
        // The last step has no follow-up: after the system's uninstall dialog
        // there is either no app or no change, and either way this screen has
        // nothing left to offer.
        action.visibility = if (state.done >= labels.size) View.GONE else View.VISIBLE
    }

    /**
     * @param done how many steps have completed.
     * @param failedAt the step that refused, if any. A failure is never fatal:
     *        every step up to the last is reversible with the command shown at
     *        the bottom of the pane.
     */
    data class State(val done: Int, val failedAt: Int? = null, val message: String? = null)

    private companion object {
        const val FADE_MS = 160L

        // Latin-1 and ASCII only: this block is monospace, and the device's
        // monospace face is not guaranteed to carry box-drawing or dingbats.
        const val MARK_DONE = "[x]"
        const val MARK_NEXT = "[>]"
        const val MARK_TODO = "[ ]"
        const val MARK_FAILED = "[!]"
    }
}
