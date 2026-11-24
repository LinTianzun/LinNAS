package com.linnas.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** 文件页的「网格」视图(卡片式),复用 item_media 布局。 */
class FileGridAdapter(
    private val thumbUrlFor: (String) -> String,
    private val onOpen: (ListRow) -> Unit,
    private val onMore: (ListRow, View) -> Unit,
) : RecyclerView.Adapter<FileGridAdapter.VH>() {

    private val items = mutableListOf<ListRow>()
    private var basePath: String = ""

    fun submit(rows: List<ListRow>, basePath: String = "") {
        items.clear(); items.addAll(rows); this.basePath = basePath
        notifyDataSetChanged()
    }

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val thumb: ImageView = root.findViewById(R.id.mthumb)
        val name: TextView = root.findViewById(R.id.mname)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = items[position]
        val ctx = h.itemView.context
        h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        h.thumb.imageTintList = null
        when (row) {
            is ListRow.Lib -> {
                h.name.text = row.lib.name
                h.thumb.setImageResource(R.drawable.ic_cloud)
                h.thumb.scaleType = ImageView.ScaleType.CENTER
                h.thumb.imageTintList = ThemeColors.csl(ctx, R.attr.lPrimary)
                h.thumb.setBackgroundColor(ThemeColors.color(ctx, R.attr.lSurfaceHigh))
            }
            is ListRow.FileRow -> {
                val e = row.e
                h.name.text = e.name
                val ext = e.name.substringAfterLast('.', "").lowercase()
                val fullVpath = e.vpath ?: if (basePath.isNotEmpty()) "$basePath/${e.name}" else ""
                if (!e.isDir && (ext in FileListAdapter.VIDEO || ext in FileListAdapter.IMAGE) && fullVpath.isNotEmpty()) {
                    h.thumb.load(thumbUrlFor(fullVpath)) { crossfade(true) }
                } else {
                    val (icon, tint) = when {
                        e.isDir -> R.drawable.ic_folder to R.attr.lPrimary
                        ext in FileListAdapter.VIDEO -> R.drawable.ic_video to R.attr.lPrimary
                        ext in FileListAdapter.IMAGE -> R.drawable.ic_image to R.attr.lPrimary
                        ext in FileListAdapter.AUDIO -> R.drawable.ic_music to R.attr.lPrimary
                        else -> R.drawable.ic_file to R.attr.lTextDim
                    }
                    h.thumb.scaleType = ImageView.ScaleType.CENTER
                    h.thumb.setBackgroundColor(ThemeColors.color(ctx, R.attr.lSurfaceHigh))
                    h.thumb.setImageResource(icon)
                    h.thumb.imageTintList = ThemeColors.csl(ctx, tint)
                }
            }
        }
        h.itemView.setOnClickListener { onOpen(row) }
        h.itemView.setOnLongClickListener { onMore(row, it); true }
    }
}
