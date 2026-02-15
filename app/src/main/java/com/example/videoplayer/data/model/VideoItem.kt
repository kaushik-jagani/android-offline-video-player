package com.example.videoplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single video file discovered on device storage.
 *
 * @property id         MediaStore content ID (unique per device).
 * @property title      Display name of the video file.
 * @property path       Absolute file path on disk.
 * @property duration   Duration in milliseconds.
 * @property size       File size in bytes.
 * @property folderName Parent folder display name.
 * @property folderPath Absolute path of the parent folder.
 * @property dateAdded  Epoch seconds when the file was added.
 * @property lastPosition Last playback position in ms (for resume).
 * @property lastPlayedAt Epoch millis of the last playback update.
 */
@Entity(tableName = "videos")
data class VideoItem(
    @PrimaryKey
    val id: Long,
    val title: String,
    val path: String,
    val duration: Long,
    val size: Long,
    val folderName: String,
    val folderPath: String,
    val dateAdded: Long,
    val lastPosition: Long = 0L,
    val lastPlayedAt: Long = 0L
)
