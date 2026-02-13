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
     * Look up the saved playback position for a video and return it via callback.
     */
    fun getLastPosition(videoId: Long, callback: (Long) -> Unit) {
        viewModelScope.launch {
            val video = repository.getVideoById(videoId)
            callback(video?.lastPosition ?: 0L)
        }
    }
}
