package com.example.videoplayer.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.videoplayer.data.database.VideoDao
import com.example.videoplayer.data.database.VideoDatabase
import com.example.videoplayer.data.model.VideoFolder
import com.example.videoplayer.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for video data.
 * Handles MediaStore scanning and exposes Room-backed reactive streams.
 */
class VideoRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dao: VideoDao = VideoDatabase.getInstance(appContext).videoDao()

    companion object {
        private const val TAG = "VideoRepository"
    }

    // ── Reactive Streams ─────────────────────────────────────────────────

    /** Observe the list of folders that contain videos. */
    fun observeFolders(): Flow<List<VideoFolder>> =
        dao.getFolders().map { list ->
            list.map { info ->
                VideoFolder(
                    name = info.folderName,
                    path = info.folderPath,
                    videoCount = info.videoCount,
                    firstVideoPath = info.firstVideoPath
                )
            }
        }

    /** Observe all videos within a specific folder. */
    fun observeVideosByFolder(folderPath: String): Flow<List<VideoItem>> =
        dao.getVideosByFolder(folderPath)

    /** Observe every video on device. */
    fun observeAllVideos(): Flow<List<VideoItem>> =
        dao.getAllVideos()

    // ── Single-item lookups ──────────────────────────────────────────────

    /** Fetch a single video by ID (suspend, non-reactive). */
    suspend fun getVideoById(videoId: Long): VideoItem? =
        dao.getVideoById(videoId)

    // ── Playback state ───────────────────────────────────────────────────

    /** Save the last playback position so the user can resume later. */
    suspend fun savePlaybackPosition(videoId: Long, positionMs: Long) {
        dao.updateLastPosition(videoId, positionMs)
    }

    // ── MediaStore Scanner ───────────────────────────────────────────────

    /**
     * Scans the device via [MediaStore] for all video files and caches
     * them into Room. Call this from a coroutine (IO dispatcher used
     * internally). Returns true on success, false on failure.
     */
    suspend fun scanDeviceVideos(): Boolean = withContext(Dispatchers.IO) {
        try {
            val videos = queryMediaStore()
            // Preserve existing saved positions before replacing
            val existingPositions = dao.getAllVideoPositions()
            val positionMap = existingPositions.associate { it.id to it.lastPosition }
            // Apply saved positions to freshly scanned items
            val merged = videos.map { v ->
                val saved = positionMap[v.id]
                if (saved != null && saved > 0L) v.copy(lastPosition = saved) else v
            }
            dao.deleteAll()
            dao.insertAll(merged)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan device videos", e)
            false
        }
    }

    /**
     * Queries [MediaStore.Video] for every video file on the device.
     */
    private fun queryMediaStore(): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        appContext.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown"
                val data = cursor.getString(dataColumn) ?: continue
                var duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val dateAdded = cursor.getLong(dateColumn)

                // Fallback: if MediaStore returns 0 duration, use MediaMetadataRetriever
                if (duration <= 0L) {
                    duration = retrieveDurationFallback(data)
                }

                // Derive folder info from the absolute path
                val parentFile = File(data).parentFile
                val folderName = parentFile?.name ?: "Internal Storage"
                val folderPath = parentFile?.absolutePath ?: "/"

                videos.add(
                    VideoItem(
                        id = id,
                        title = name,
                        path = data,
                        duration = duration,
                        size = size,
                        folderName = folderName,
                        folderPath = folderPath,
                        dateAdded = dateAdded
                    )
                )
            }
        }

        return videos
    }

    /**
     * Uses [MediaMetadataRetriever] to extract duration for videos where
     * MediaStore returned 0. This handles formats that MediaStore can't parse.
     */
    private fun retrieveDurationFallback(path: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "MediaMetadataRetriever failed for: $path", e)
            0L
        }
    }
}
