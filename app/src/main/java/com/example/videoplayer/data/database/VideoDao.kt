package com.example.videoplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.videoplayer.data.model.VideoItem
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the [VideoItem] table.
 */
@Dao
interface VideoDao {

    // ── Queries ──────────────────────────────────────────────────────────

    /** Observe all videos ordered by title. */
    @Query("SELECT * FROM videos ORDER BY title ASC")
    fun getAllVideos(): Flow<List<VideoItem>>

    /** Observe videos that belong to a specific folder. */
    @Query("SELECT * FROM videos WHERE folderPath = :folderPath ORDER BY title ASC")
    fun getVideosByFolder(folderPath: String): Flow<List<VideoItem>>

    /** Get a single video by its MediaStore ID. */
    @Query("SELECT * FROM videos WHERE id = :videoId LIMIT 1")
    suspend fun getVideoById(videoId: Long): VideoItem?

    /** Get distinct folder paths with a count, used to build folder list. */
    @Query(
        """
        SELECT folderPath, folderName, COUNT(*) AS videoCount, MIN(path) AS firstVideoPath
        FROM videos
        GROUP BY folderPath
        ORDER BY folderName ASC
        """
    )
    fun getFolders(): Flow<List<FolderInfo>>

    // ── Inserts / Updates ────────────────────────────────────────────────

    /** Bulk-insert or replace scanned videos. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoItem>)

    /** Update last playback position for resume functionality. */
    @Query("UPDATE videos SET lastPosition = :position WHERE id = :videoId")
    suspend fun updateLastPosition(videoId: Long, position: Long)

    /** Get all video IDs and their saved positions (used to preserve positions across rescan). */
    @Query("SELECT id, lastPosition FROM videos")
    suspend fun getAllVideoPositions(): List<VideoPosition>

    // ── Deletes ──────────────────────────────────────────────────────────

    /** Remove all videos (used before a full re-scan). */
    @Query("DELETE FROM videos")
    suspend fun deleteAll()
}

/**
 * Lightweight projection returned by [VideoDao.getFolders].
 */
data class FolderInfo(
    val folderPath: String,
    val folderName: String,
    val videoCount: Int,
    val firstVideoPath: String
)

/**
 * Lightweight projection for preserving resume positions across rescans.
 */
data class VideoPosition(
    val id: Long,
    val lastPosition: Long
)
