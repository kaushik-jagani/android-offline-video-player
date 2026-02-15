package com.example.videoplayer

import android.app.Application
import com.example.videoplayer.data.database.VideoDatabase

/**
 * Application class – initialises singletons eagerly so they are
 * ready before any Activity starts.
 */
class VideoPlayerApplication : Application() {

    /** Database singleton — initialized eagerly on app start. */
    lateinit var database: VideoDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        // Pre-warm the database singleton
        database = VideoDatabase.getInstance(this)
    }
}
