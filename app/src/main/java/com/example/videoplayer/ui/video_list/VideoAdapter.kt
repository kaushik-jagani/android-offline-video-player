package com.example.videoplayer.ui.video_list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoplayer.R
import com.example.videoplayer.data.model.VideoItem
import com.example.videoplayer.databinding.ItemVideoBinding
import com.example.videoplayer.utils.FileSizeFormatter
import com.example.videoplayer.utils.TimeFormatter

/**
 * RecyclerView adapter for displaying a list of video files.
 */
class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: VideoItem) {
            binding.tvTitle.text = video.title
            binding.tvDuration.text = TimeFormatter.format(video.duration)
            binding.tvSize.text = FileSizeFormatter.format(video.size)

            Glide.with(binding.ivThumbnail)
                .load(video.path)
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .centerCrop()
                .thumbnail(0.25f)
                .into(binding.ivThumbnail)

            binding.root.setOnClickListener { onClick(video) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
