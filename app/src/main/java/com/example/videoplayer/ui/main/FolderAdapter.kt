package com.example.videoplayer.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoplayer.R
import com.example.videoplayer.data.model.VideoFolder
import com.example.videoplayer.databinding.ItemFolderBinding

/**
 * RecyclerView adapter for displaying video folders in a grid.
 */
class FolderAdapter(
    private val onClick: (VideoFolder) -> Unit
) : ListAdapter<VideoFolder, FolderAdapter.FolderViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: FolderViewHolder) {
        super.onViewRecycled(holder)
        val iv: android.widget.ImageView = holder.itemView.findViewById(R.id.ivThumbnail) ?: return
        Glide.with(iv).clear(iv)
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: VideoFolder) {
            binding.tvFolderName.text = folder.name
            binding.tvVideoCount.text = binding.root.context.getString(
                R.string.video_count_format, folder.videoCount
            )

            // Load a thumbnail from the first video in the folder
            Glide.with(binding.ivThumbnail)
                .load(folder.firstVideoPath)
                .placeholder(R.drawable.ic_folder_placeholder)
                .error(R.drawable.ic_folder_placeholder)
                .centerCrop()
                .thumbnail(0.25f)
                .into(binding.ivThumbnail)

            binding.root.setOnClickListener { onClick(folder) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoFolder>() {
            override fun areItemsTheSame(a: VideoFolder, b: VideoFolder) = a.path == b.path
            override fun areContentsTheSame(a: VideoFolder, b: VideoFolder) = a == b
        }
    }
}
