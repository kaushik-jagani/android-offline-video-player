package com.example.videoplayer.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility for runtime permission checks required to scan videos.
 */
object PermissionHelper {

    /**
     * Returns the permission string needed for reading videos on
     * the current API level.
     */
    fun videoPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /**
     * Check whether the app already has the video-read permission.
     */
    fun hasVideoPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            videoPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }
}
