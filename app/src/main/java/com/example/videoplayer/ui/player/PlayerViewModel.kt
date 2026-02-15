package com.example.videoplayer.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videoplayer.data.repository.VideoRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for the player screen — persists playback position to Room.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    /**
     * Persist the current playback position so the user can resume later.
     */
    fun savePosition(videoId: Long, positionMs: Long) {
        viewModelScope.launch {
            repository.savePlaybackPosition(videoId, positionMs)
        }
    }

    /**
     * Look up the saved playback position for a video.
     * Returns the saved position or 0L if not found.
     */
    suspend fun getLastPosition(videoId: Long): Long {
        val video = repository.getVideoById(videoId)
        return video?.lastPosition ?: 0L
    }
}
