package com.example.profilebrowser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProfileAdapter(
    private val onOpen: (UserProfile) -> Unit,
    private val onMenu: (UserProfile, View) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    private var items: List<UserProfile> = emptyList()

    fun submit(list: List<UserProfile>) {
        items = list
        notifyDataSetChanged()
    }

    /** Called every second to refresh only the countdown texts. */
    fun tick() {
        if (items.any { it.countdownEnd != null }) {
            notifyItemRangeChanged(0, items.size, TICK)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val countdown: TextView = view.findViewById(R.id.countdown)
        val menu: ImageButton = view.findViewById(R.id.menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.countdown.showCountdown(p.countdownEnd)
        holder.itemView.setOnClickListener { onOpen(p) }
        holder.menu.setOnClickListener { onMenu(p, holder.menu) }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            holder.countdown.showCountdown(items[position].countdownEnd)
        }
    }

    companion object {
        private const val TICK = "tick"
    }
}
