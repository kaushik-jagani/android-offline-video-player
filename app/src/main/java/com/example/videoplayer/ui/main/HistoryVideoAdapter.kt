package com.example.videoplayer.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoplayer.R
import com.example.videoplayer.data.model.VideoItem
import com.example.videoplayer.databinding.ItemHistoryVideoBinding

class HistoryVideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, HistoryVideoAdapter.HistoryViewHolder>(DIFF) {

    private var itemWidthPx: Int? = null

    fun setItemWidthPx(widthPx: Int) {
        if (widthPx <= 0) return
        itemWidthPx = widthPx
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemHistoryVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            itemWidthPx?.let { w ->
                val lp = binding.root.layoutParams
                if (lp != null && lp.width != w) {
                    lp.width = w
                    binding.root.layoutParams = lp
                }
            }

            Glide.with(binding.ivThumb)
                .load(video.path)
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop()
                .thumbnail(0.25f)
                .into(binding.ivThumb)

            val dur = video.duration
            val pos = video.lastPosition
            val percent = if (dur > 0L) ((pos.coerceAtLeast(0L) * 100L) / dur).toInt().coerceIn(0, 100) else 0
            binding.progressWatched.progress = percent

            binding.root.setOnClickListener { onClick(video) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem == newItem
        }
    }
}
