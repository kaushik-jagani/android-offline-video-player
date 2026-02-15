package com.example.videoplayer.ui.video_list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videoplayer.data.model.VideoItem
import com.example.videoplayer.data.repository.VideoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the video-list screen that shows videos inside a folder.
 */
class VideoListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _isEmpty = MutableStateFlow(false)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    /** Active collection job — cancelled and replaced on each [loadVideos] call. */
    private var collectJob: Job? = null

    /**
     * Start observing videos for the given [folderPath].
     * Safe to call multiple times — previous collection is cancelled before starting new one.
     */
    fun loadVideos(folderPath: String) {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            repository.observeVideosByFolder(folderPath).collectLatest { list ->
                _videos.value = list
                _isEmpty.value = list.isEmpty()
            }
        }
    }
}
