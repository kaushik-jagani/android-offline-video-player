package com.example.videoplayer.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videoplayer.R
import com.example.videoplayer.data.model.VideoItem
import com.example.videoplayer.databinding.ItemHomeHistoryBinding

class HomeHistoryHeaderAdapter(
    private val onHistoryClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<HomeHistoryHeaderAdapter.HeaderVH>() {

    private val items: MutableList<VideoItem> = mutableListOf()

    fun submit(items: List<VideoItem>) {
        this.items.clear()
        this.items.addAll(items.take(5))
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderVH {
        val binding = ItemHomeHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HeaderVH(binding)
    }

    override fun onBindViewHolder(holder: HeaderVH, position: Int) {
        holder.bind(items)
    }

    inner class HeaderVH(
        private val binding: ItemHomeHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val historyAdapter = HistoryVideoAdapter(onHistoryClick)

        init {
            binding.tvHistoryTitle.text = binding.root.context.getString(R.string.local_history)
            binding.rvHistory.apply {
                layoutManager = LinearLayoutManager(binding.root.context, RecyclerView.HORIZONTAL, false)
                adapter = historyAdapter
                setHasFixedSize(true)
            }
        }

        fun bind(list: List<VideoItem>) {
            historyAdapter.submitList(list)

            // Adjust item width so the first screen shows 2-3 items on phones and ~5 on tablets.
            binding.rvHistory.post {
                val ctx = binding.root.context
                val sw = ctx.resources.configuration.smallestScreenWidthDp
                val visibleCount = when {
                    sw >= 600 -> 5
                    sw >= 360 -> 3
                    else -> 2
                }

                val available = binding.rvHistory.width - binding.rvHistory.paddingStart - binding.rvHistory.paddingEnd
                if (available > 0) {
                    val w = (available / visibleCount).coerceAtLeast(
                        ctx.resources.getDimensionPixelSize(R.dimen.history_item_min_width)
                    )
                    historyAdapter.setItemWidthPx(w)
                }
            }
        }
    }
}
