package com.example.videoplayer.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videoplayer.data.model.VideoFolder
import com.example.videoplayer.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the main (folder-browsing) screen.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    // ── UI State ─────────────────────────────────────────────────────────

    private val _folders = MutableStateFlow<List<VideoFolder>>(emptyList())
    val folders: StateFlow<List<VideoFolder>> = _folders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isEmpty = MutableStateFlow(false)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    init {
        observeFolders()
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /** Trigger a full MediaStore scan in the background. */
    fun scanVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            _scanError.value = null
            val success = repository.scanDeviceVideos()
            if (!success) {
                _scanError.value = "Failed to scan videos. Please try again."
            }
            _isLoading.value = false
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun observeFolders() {
        viewModelScope.launch {
            repository.observeFolders().collectLatest { list ->
                _folders.value = list
                _isEmpty.value = list.isEmpty()
            }
        }
    }
}
