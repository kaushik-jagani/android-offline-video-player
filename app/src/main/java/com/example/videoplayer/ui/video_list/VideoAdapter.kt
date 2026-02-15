package com.example.videoplayer.ui.video_list

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.videoplayer.R
import com.example.videoplayer.data.model.VideoItem
import com.example.videoplayer.databinding.ItemVideoBinding
import com.example.videoplayer.utils.FileSizeFormatter
import com.example.videoplayer.utils.TimeFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date

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

    override fun onViewRecycled(holder: VideoViewHolder) {
        super.onViewRecycled(holder)
        val iv: android.widget.ImageView = holder.itemView.findViewById(R.id.ivThumbnail) ?: return
        Glide.with(iv).clear(iv)
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

            binding.btnMore.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor)
                popup.menu.add(0, 1, 0, anchor.context.getString(R.string.properties))
                popup.setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) {
                        showVideoPropertiesDialog(anchor.context, video)
                        true
                    } else {
                        false
                    }
                }
                popup.show()
            }
        }
    }

    private fun showVideoPropertiesDialog(context: Context, video: VideoItem) {
        val padding = context.resources.getDimensionPixelSize(R.dimen.grid_2)
        val rowSpacing = context.resources.getDimensionPixelSize(R.dimen.grid_1)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun addRow(label: String, value: String) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = rowSpacing }
            }

            val labelView = TextView(context).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f)
            }
            val valueView = TextView(context).apply {
                text = value
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f)
            }

            row.addView(labelView)
            row.addView(valueView)
            container.addView(row)
        }

        val uri = runCatching { Uri.parse(video.path) }.getOrNull()
        val isContentUri = uri?.scheme == "content"

        val (exists, extension) = if (!isContentUri) {
            val file = File(video.path)
            val ex = file.exists()
            val ext = file.extension.takeIf { it.isNotBlank() } ?: "-"
            ex to ext
        } else {
            val ext = video.title.substringAfterLast('.', "-").takeIf { it.isNotBlank() } ?: "-"
            true to ext
        }

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val dateAddedText = runCatching { dateFormat.format(Date(video.dateAdded * 1000L)) }.getOrNull() ?: "-"
        val lastModifiedText = if (!isContentUri) {
            val file = File(video.path)
            runCatching {
                if (exists) dateFormat.format(Date(file.lastModified())) else "-"
            }.getOrNull() ?: "-"
        } else {
            "-"
        }

        addRow(context.getString(R.string.prop_title), video.title.ifBlank { "-" })
        addRow(context.getString(R.string.prop_path), video.path.ifBlank { "-" })
        addRow(context.getString(R.string.prop_size), "${FileSizeFormatter.format(video.size)} (${video.size} B)")
        addRow(context.getString(R.string.prop_duration), "${TimeFormatter.format(video.duration)} (${video.duration} ms)")
        addRow(context.getString(R.string.prop_folder), video.folderName.ifBlank { "-" })
        addRow(context.getString(R.string.prop_folder_path), video.folderPath.ifBlank { "-" })
        addRow(context.getString(R.string.prop_date_added), dateAddedText)
        addRow(context.getString(R.string.prop_last_modified), lastModifiedText)
        addRow(context.getString(R.string.prop_extension), extension)
        addRow(context.getString(R.string.prop_exists), if (exists) context.getString(R.string.yes) else context.getString(R.string.no))
        addRow(context.getString(R.string.prop_media_id), video.id.toString())
        addRow(context.getString(R.string.prop_last_position), TimeFormatter.format(video.lastPosition))

        val scrollView = ScrollView(context).apply {
            addView(container)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.properties)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
