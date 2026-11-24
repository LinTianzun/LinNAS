package com.linnas.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** 文件页列表的一行:存储库 或 文件/目录。 */
sealed class ListRow {
    data class Lib(val lib: Library) : ListRow()
    data class FileRow(val e: Entry) : ListRow()
}

class FileListAdapter(
    private val thumbUrlFor: (String) -> String,
    private val onOpen: (ListRow) -> Unit,
    private val onMore: (ListRow, View) -> Unit,
    private val resumeLabelFor: (String) -> String? = { null },
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private val items = mutableListOf<ListRow>()
    private var basePath: String = ""

    fun submit(rows: List<ListRow>, basePath: String = "") {
        items.clear()
        items.addAll(rows)
        this.basePath = basePath
        notifyDataSetChanged()
    }

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val icon: ImageView = root.findViewById(R.id.ficon)
        val thumb: ImageView = root.findViewById(R.id.fthumb)
        val name: TextView = root.findViewById(R.id.fname)
        val meta: TextView = root.findViewById(R.id.fmeta)
        val more: ImageButton = root.findViewById(R.id.fmore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = items[position]
        val ctx = h.itemView.context
        h.thumb.visibility = View.GONE
        h.icon.visibility = View.VISIBLE
        h.icon.imageTintList = ThemeColors.csl(ctx, R.attr.lPrimary)

        when (row) {
            is ListRow.Lib -> {
                h.icon.setImageResource(R.drawable.ic_cloud)
                h.name.text = row.lib.name
                h.meta.text = row.lib.path + if (row.lib.readOnly) "  ·  只读" else ""
                h.more.visibility = View.GONE
            }
            is ListRow.FileRow -> {
                val e = row.e
                val ext = e.name.substringAfterLast('.', "").lowercase()
                h.more.visibility = View.VISIBLE
                h.name.text = e.name
                if (e.isDir) {
                    h.icon.setImageResource(R.drawable.ic_folder)
                    h.meta.text = "文件夹"
                } else {
                    val (iconRes, tint) = when (ext) {
                        in VIDEO -> R.drawable.ic_video to R.attr.lPrimary
                        in IMAGE -> R.drawable.ic_image to R.attr.lPrimary
                        in AUDIO -> R.drawable.ic_music to R.attr.lPrimary
                        else -> R.drawable.ic_file to R.attr.lTextDim
                    }
                    h.icon.setImageResource(iconRes)
                    h.icon.imageTintList = ThemeColors.csl(ctx, tint)
                    val resume = resumeLabelFor(e.vpath ?: "")
                    h.meta.text = if (resume != null) resume
                    else fmtSize(e.size) + if (e.modTime > 0) "  ·  " + fmtDate(e.modTime) else ""

                    val fullVpath = e.vpath ?: if (basePath.isNotEmpty()) "$basePath/${e.name}" else ""
                    if ((ext in VIDEO || ext in IMAGE) && fullVpath.isNotEmpty()) {
                        h.icon.visibility = View.GONE
                        h.thumb.visibility = View.VISIBLE
                        h.thumb.load(thumbUrlFor(fullVpath)) { crossfade(true) }
                    }
                }
            }
        }
        h.itemView.setOnClickListener { onOpen(row) }
        h.more.setOnClickListener { onMore(row, it) }
    }

    companion object {
        val VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "ts", "m2ts", "rmvb", "flv", "wmv", "mpg", "mpeg", "vob", "ogv", "asf")
        val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
        val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma")

        fun fmtSize(n: Long): String {
            if (n <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = n.toDouble(); var i = 0
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            return if (i == 0) "$n B" else String.format("%.1f %s", v, units[i])
        }

        fun fmtDate(ts: Long): String {
            val now = System.currentTimeMillis() / 1000
            val d = java.util.Date(ts * 1000)
            val fmt = if (now - ts < 7 * 86400) "MM-dd HH:mm" else "yyyy-MM-dd"
            return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(d)
        }
    }
}
