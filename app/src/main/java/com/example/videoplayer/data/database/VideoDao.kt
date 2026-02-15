package com.example.videoplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** Update last playback position (resume) and record a last-played timestamp. */
    @Query("UPDATE videos SET lastPosition = :position, lastPlayedAt = :playedAt WHERE id = :videoId")
    suspend fun updatePlaybackState(videoId: Long, position: Long, playedAt: Long)

    /** Get all video IDs and their saved playback state (used to preserve state across rescan). */
    @Query("SELECT id, lastPosition, lastPlayedAt FROM videos")
    suspend fun getAllVideoPositions(): List<VideoPlaybackState>

    /** Recently watched videos for the main-screen Local History row. */
    @Query("SELECT * FROM videos WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<VideoItem>>

    // ── Deletes ──────────────────────────────────────────────────────────

    /** Remove all videos (used before a full re-scan). */
    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    /**
     * Atomically replace all videos: delete existing then insert new list.
     * Wrapped in @Transaction so the UI never sees an empty intermediate state.
     */
    @Transaction
    suspend fun replaceAll(videos: List<VideoItem>) {
        deleteAll()
        insertAll(videos)
    }
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

/** Lightweight projection for preserving playback state across rescans. */
data class VideoPlaybackState(
    val id: Long,
    val lastPosition: Long,
    val lastPlayedAt: Long
)
