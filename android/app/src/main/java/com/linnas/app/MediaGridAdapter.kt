package com.linnas.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * 媒体库网格卡片。
 *
 * v1.8.0 起支持「按文件夹分组」:分组标题占满一行(spanSize=2),其下为该文件夹的媒体卡片。
 * - [submit] 平铺列表; - [submitGrouped] 带分组标题。
 */
class MediaGridAdapter(
    private val thumbUrlFor: (String) -> String,
    private val onOpen: (Entry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item {
        data class Header(val title: String) : Item()
        data class Media(val entry: Entry) : Item()
    }

    private val items = mutableListOf<Item>()

    fun submit(rows: List<Entry>) {
        items.clear(); items.addAll(rows.map { Item.Media(it) }); notifyDataSetChanged()
    }

    /** groups: (文件夹标题, 该文件夹下的媒体列表)。 */
    fun submitGrouped(groups: List<Pair<String, List<Entry>>>) {
        items.clear()
        groups.forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            items.add(Item.Header(title))
            list.forEach { items.add(Item.Media(it)) }
        }
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is Item.Header

    class HeaderVH(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.mgroup)
    }

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val thumb: ImageView = root.findViewById(R.id.mthumb)
        val name: TextView = root.findViewById(R.id.mname)
    }

    override fun getItemViewType(position: Int): Int = if (isHeader(position)) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 0) {
            HeaderVH(LayoutInflater.from(parent.context).inflate(R.layout.item_media_header, parent, false))
        } else {
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))
        }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is Item.Header -> (holder as HeaderVH).title.text = it.title
            is Item.Media -> {
                val h = holder as VH
                val e = it.entry
                h.name.text = e.name
                val vp = e.vpath ?: return
                h.thumb.load(thumbUrlFor(vp)) { crossfade(true) }
                h.itemView.setOnClickListener { onOpen(e) }
            }
        }
    }
}
