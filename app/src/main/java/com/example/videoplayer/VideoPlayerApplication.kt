package com.example.videoplayer

import android.app.Application
import com.example.videoplayer.data.database.VideoDatabase

/**
 * Application class – initialises singletons eagerly so they are
 * ready before any Activity starts.
 */
class VideoPlayerApplication : Application() {

    /** Eager database initialisation on app start. */
    val database: VideoDatabase by lazy {
        VideoDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Pre-warm the database singleton
        database
    }
}
