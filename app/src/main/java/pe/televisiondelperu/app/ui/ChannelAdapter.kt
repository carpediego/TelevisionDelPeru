package pe.televisiondelperu.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import pe.televisiondelperu.app.R
import pe.televisiondelperu.app.catalog.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private val items = mutableListOf<Channel>()
    private var activeId: String? = null

    fun submit(newItems: List<Channel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setActive(id: String?) {
        if (activeId == id) return
        activeId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val active = channel.id == activeId

        holder.name.text = channel.name
        holder.tagline.text = channel.tagline
        holder.tagline.visibility = if (channel.tagline.isBlank()) View.GONE else View.VISIBLE

        holder.monogram.text = channel.initials
        holder.monogram.backgroundTintList =
            android.content.res.ColorStateList.valueOf(parseColor(channel.color))

        holder.itemView.isSelected = active
        holder.state.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        holder.state.contentDescription = if (active) {
            holder.itemView.context.getString(R.string.cd_stop, channel.name)
        } else {
            holder.itemView.context.getString(R.string.cd_watch, channel.name)
        }

        holder.itemView.setOnClickListener { onClick(channel) }
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: IllegalArgumentException) {
        DEFAULT_COLOR
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.txtName)
        val tagline: TextView = view.findViewById(R.id.txtTagline)
        val monogram: TextView = view.findViewById(R.id.txtMonogram)
        val state: ImageView = view.findViewById(R.id.imgState)
    }

    private companion object {
        val DEFAULT_COLOR: Int = Color.parseColor("#C62828")
    }
}
