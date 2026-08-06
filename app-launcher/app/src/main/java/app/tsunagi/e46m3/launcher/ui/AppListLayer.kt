package app.tsunagi.e46m3.launcher.ui

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.tsunagi.e46m3.launcher.R
import app.tsunagi.e46m3.launcher.apps.AppEntry

/**
 * The app list overlay: inflation, binding, and the show/hide fade.
 *
 * Two columns of 68px rows, exactly as the design lays them out. A
 * [GridLayoutManager] fills row-major, which is what CSS `grid-template-columns:
 * 1fr 1fr` does, so the reading order matches without any index arithmetic.
 */
class AppListLayer(
    val root: View,
    private val onPick: (AppEntry) -> Unit,
    private val onClose: () -> Unit,
) {
    private val grid: RecyclerView = root.findViewById(R.id.app_grid)
    private val count: TextView = root.findViewById(R.id.app_count)
    private val adapter = Adapter()

    init {
        grid.layoutManager = GridLayoutManager(root.context, COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.addItemDecoration(ColumnGap(root.resources.getDimensionPixelSize(R.dimen.d_al_col_gap)))
        // Rows are a fixed height and never animate, so the default change
        // animator only costs us a frame of work when the catalogue reloads.
        grid.itemAnimator = null
        root.findViewById<View>(R.id.app_close).setOnClickListener { onClose() }
    }

    fun submit(entries: List<AppEntry>) {
        adapter.entries = entries
        adapter.notifyDataSetChanged()
        count.text = entries.size.toString()
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    /** The design's `animation: m3fade .16s linear both`. */
    fun show() {
        grid.scrollToPosition(0)
        root.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(FADE_MS).setStartDelay(0L)
    }

    fun hide() {
        root.animate().cancel()
        root.alpha = 1f
        root.visibility = View.GONE
    }

    private inner class Adapter : RecyclerView.Adapter<Row>() {
        var entries: List<AppEntry> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(LayoutInflater.from(parent.context)
                .inflate(R.layout.app_list_row, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val entry = entries[position]
            holder.initial.text = entry.initial
            holder.name.text = entry.label
            holder.itemView.setOnClickListener { onPick(entry) }
        }
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view) {
        val initial: TextView = view.findViewById(R.id.app_initial)
        val name: TextView = view.findViewById(R.id.app_name)
    }

    /**
     * `column-gap: 34px`. Split across the facing edges so both columns end up
     * the same width — the alternative, putting the whole gap on one side,
     * silently makes one column 34px narrower and truncates its names first.
     */
    private class ColumnGap(gap: Int) : RecyclerView.ItemDecoration() {
        private val half = gap / 2

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val index = parent.getChildAdapterPosition(view)
            if (index < 0) return
            if (index % COLUMNS == 0) outRect.right = half else outRect.left = half
        }
    }

    companion object {
        private const val COLUMNS = 2
        private const val FADE_MS = 160L
    }
}
