package com.linnas.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * v1.8.0 媒体库「文件夹模式」适配器:像文件页一样按目录浏览。
 * - 目录行:文件夹图标 + 名称 + 数量,点击进入该目录;
 * - 文件行:缩略图 + 名称,点击打开媒体。
 */
class MediaFolderAdapter(
    private val thumbUrlFor: (String) -> String,
    private val onOpenFolder: (Entry) -> Unit,
    private val onOpenFile: (Entry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item {
        data class Header(val title: String) : Item()
        data class Dir(val entry: Entry, val count: Int) : Item()
        data class Media(val entry: Entry) : Item()
    }

    private val items = mutableListOf<Item>()

    /** 提交当前目录内容:目录在前、文件在后。 */
    fun submit(entries: List<Entry>) {
        items.clear()
        val dirs = entries.filter { it.isDir }
        val files = entries.filter { !it.isDir }
        if (dirs.isNotEmpty()) {
            items.add(Item.Header("文件夹(${dirs.size})"))
            dirs.forEach { items.add(Item.Dir(it, 0)) }
        }
        if (files.isNotEmpty()) {
            items.add(Item.Header("媒体(${files.size})"))
            files.forEach { items.add(Item.Media(it)) }
        }
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is Item.Header

    class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.mgroup)
    }

    class DirVH(root: View) : RecyclerView.ViewHolder(root) {
        val icon: ImageView = root.findViewById(R.id.mfolderIcon)
        val name: TextView = root.findViewById(R.id.mfolderName)
        val meta: TextView = root.findViewById(R.id.mfolderMeta)
    }

    class MediaVH(root: View) : RecyclerView.ViewHolder(root) {
        val thumb: ImageView = root.findViewById(R.id.mthumb)
        val name: TextView = root.findViewById(R.id.mname)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> 0
        is Item.Dir -> 1
        is Item.Media -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            0 -> HeaderVH(
                LayoutInflater.from(parent.context).inflate(R.layout.item_media_header, parent, false)
            )
            1 -> DirVH(
                LayoutInflater.from(parent.context).inflate(R.layout.item_media_folder, parent, false)
            )
            else -> MediaVH(
                LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            )
        }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is Item.Header -> (holder as HeaderVH).title.text = it.title
            is Item.Dir -> {
                val h = holder as DirVH
                val dir = it.entry
                h.name.text = dir.name
                h.meta.text = "文件夹"
                h.itemView.setOnClickListener { onOpenFolder(dir) }
            }
            is Item.Media -> {
                val h = holder as MediaVH
                val e = it.entry
                h.name.text = e.name
                val vp = e.vpath ?: return
                h.thumb.load(thumbUrlFor(vp)) { crossfade(true) }
                h.itemView.setOnClickListener { onOpenFile(e) }
            }
        }
    }
}
