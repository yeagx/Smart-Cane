package com.example.smart_cane

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smart_cane.databinding.ItemHazardLogBinding

class HazardLogAdapter(
    private val logs: List<HazardLogEntry>
) : RecyclerView.Adapter<HazardLogAdapter.ViewHolder>() {

    data class HazardLogEntry(
        val type: String,
        val time: String,
        val detail: String
    )

    class ViewHolder(val binding: ItemHazardLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHazardLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = logs[position]
        holder.binding.tvLogType.text = entry.type
        holder.binding.tvLogTime.text = entry.time
        holder.binding.tvLogDetail.text = entry.detail
    }

    override fun getItemCount(): Int = logs.size
}
