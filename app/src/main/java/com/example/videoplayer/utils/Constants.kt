package com.example.videoplayer.utils

/**
 * Intent-extra keys and other constants shared across the app.
 */
object Constants {

    // ── Intent Extras ────────────────────────────────────────────────────
    const val EXTRA_FOLDER_PATH = "extra_folder_path"
    const val EXTRA_FOLDER_NAME = "extra_folder_name"
    const val EXTRA_VIDEO_ID = "extra_video_id"
    const val EXTRA_VIDEO_PATH = "extra_video_path"
    const val EXTRA_VIDEO_TITLE = "extra_video_title"
    const val EXTRA_START_POSITION = "extra_start_position"

    // Playlist extras (for next/previous navigation)
    const val EXTRA_PLAYLIST_PATHS = "extra_playlist_paths"
    const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
    const val EXTRA_PLAYLIST_IDS = "extra_playlist_ids"
    const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

    // ── Permission Request ───────────────────────────────────────────────
    const val REQUEST_CODE_PERMISSION = 1001

    // ── Player defaults ──────────────────────────────────────────────────
    const val SEEK_INCREMENT_MS = 10_000L
    const val CONTROLS_TIMEOUT_MS = 4_000L
}
