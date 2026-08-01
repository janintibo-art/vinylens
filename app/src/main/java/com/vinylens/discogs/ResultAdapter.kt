package com.vinylens.discogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ResultAdapter(
    private val onOpen: (Release) -> Unit,
    private val onAdd: (Release) -> Unit,
    private val isOwned: (Int) -> Boolean
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private val items = ArrayList<Release>()

    fun submit(list: List<Release>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun refreshItem(releaseId: Int) {
        val idx = items.indexOfFirst { it.id == releaseId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val matchBadge: TextView = view.findViewById(R.id.matchBadge)
        val textZone: View = view.findViewById(R.id.textZone)
        val btnAdd: ImageButton = view.findViewById(R.id.btnAdd)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.matchBadge.visibility = if (item.exactMatch) View.VISIBLE else View.GONE

        if (item.thumb.isNotBlank()) {
            holder.cover.load(item.thumb) {
                crossfade(true)
                placeholder(R.drawable.placeholder_cover)
                error(R.drawable.placeholder_cover)
            }
        } else {
            holder.cover.setImageResource(R.drawable.placeholder_cover)
        }

        val owned = isOwned(item.id)
        holder.btnAdd.setImageResource(
            if (owned) R.drawable.ic_in_collection else R.drawable.ic_add_collection
        )
        holder.btnAdd.setBackgroundResource(
            if (owned) R.drawable.bg_circle_owned else R.drawable.bg_circle_gold
        )
        holder.btnAdd.contentDescription = holder.itemView.context.getString(
            if (owned) R.string.in_collection else R.string.add_to_collection
        )

        holder.textZone.setOnClickListener { onOpen(item) }
        holder.cover.setOnClickListener { onOpen(item) }
        holder.btnAdd.setOnClickListener { onAdd(item) }
    }

    override fun getItemCount(): Int = items.size
}
