package com.example.videoplayer.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.videoplayer.data.model.VideoItem

/**
 * Room database holding cached video metadata and playback state.
 */
@Database(
    entities = [VideoItem::class],
    version = 1,
    exportSchema = false
)
abstract class VideoDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao

    companion object {
        private const val DB_NAME = "offline_video_player.db"

        @Volatile
        private var INSTANCE: VideoDatabase? = null

        /** Thread-safe singleton accessor. */
        fun getInstance(context: Context): VideoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VideoDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
