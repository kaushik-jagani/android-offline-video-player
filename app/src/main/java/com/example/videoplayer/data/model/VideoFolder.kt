package com.example.videoplayer.data.model

/**
 * Represents a folder that contains one or more video files.
 *
 * @property name       Folder display name.
 * @property path       Absolute folder path.
 * @property videoCount Number of videos inside the folder.
 * @property firstVideoPath Path of the first video (used for thumbnail).
 */
data class VideoFolder(
    val name: String,
    val path: String,
    val videoCount: Int,
    val firstVideoPath: String
)
