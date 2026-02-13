package com.example.videoplayer.data.model

/**
 * Lightweight wrapper for playback-speed options shown in the player UI.
 */
data class PlaybackSpeed(
    val label: String,
    val value: Float
) {
    companion object {
        /** Predefined speed options available in the player. */
        val OPTIONS = listOf(
            PlaybackSpeed("0.25x", 0.25f),
            PlaybackSpeed("0.5x", 0.5f),
            PlaybackSpeed("0.75x", 0.75f),
            PlaybackSpeed("1x (Normal)", 1.0f),
            PlaybackSpeed("1.25x", 1.25f),
            PlaybackSpeed("1.5x", 1.5f),
            PlaybackSpeed("1.75x", 1.75f),
            PlaybackSpeed("2x", 2.0f)
        )
    }
}
