package com.primusobscurus.dedupcleaner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class GroupAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    var groups: List<DuplicateGroup> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.groupTitle)
        val grid: GridLayout = view.findViewById(R.id.fileGrid)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder =
        GroupViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group, parent, false)
        )

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val ctx = holder.itemView.context
        holder.grid.removeAllViews()
        holder.grid.columnCount = 2 // pairs show left/right, 3+ form a grid

        val sim = group.maxSimilarity
        holder.title.text = if (group.exact)
            ctx.getString(R.string.group_exact, group.items.size)
        else
            ctx.getString(R.string.group_similar, group.items.size, sim)

        for (item in group.items) {
            val cell = LayoutInflater.from(ctx).inflate(R.layout.item_file, holder.grid, false)
            val thumb: ImageView = cell.findViewById(R.id.thumb)
            val name: TextView = cell.findViewById(R.id.fileName)
            val meta: TextView = cell.findViewById(R.id.fileMeta)
            val check: CheckBox = cell.findViewById(R.id.deleteCheck)

            item.thumb?.let { thumb.setImageBitmap(it) }
            name.text = item.name
            meta.text = buildString {
                append(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.date * 1000L)))
                append("  ·  ${formatSize(item.size)}")
                if (item.isVideo) append("  ·  ${formatDuration(item.durationMs)}")
            }
            check.isChecked = item.selectedForDeletion
            check.setOnCheckedChangeListener { _, checked ->
                item.selectedForDeletion = checked
                onSelectionChanged()
            }
            // Tapping the card toggles the selection too
            cell.setOnClickListener { check.isChecked = !check.isChecked }

            holder.grid.addView(cell)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.1f KB".format(bytes / 1024.0)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
