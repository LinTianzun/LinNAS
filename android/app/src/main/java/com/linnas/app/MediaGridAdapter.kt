package com.linnas.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** 媒体库网格卡片。 */
class MediaGridAdapter(
    private val thumbUrlFor: (String) -> String,
    private val onOpen: (Entry) -> Unit,
) : RecyclerView.Adapter<MediaGridAdapter.VH>() {

    private val items = mutableListOf<Entry>()

    fun submit(rows: List<Entry>) {
        items.clear(); items.addAll(rows); notifyDataSetChanged()
    }

    class VH(root: android.view.View) : RecyclerView.ViewHolder(root) {
        val thumb: ImageView = root.findViewById(R.id.mthumb)
        val name: TextView = root.findViewById(R.id.mname)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.name
        val vp = e.vpath ?: return
        h.thumb.load(thumbUrlFor(vp)) { crossfade(true) }
        h.itemView.setOnClickListener { onOpen(e) }
    }
}
