package com.example.videoplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File

/**
 * Encapsulates [ExoPlayer] setup, lifecycle, and convenience helpers.
 *
 * Create one instance per player screen and call [release] when done.
 */
@OptIn(UnstableApi::class)
class ExoPlayerManager(context: Context) {

    /** Underlying Media3 ExoPlayer instance. */
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    // ── Playback Controls ────────────────────────────────────────────────

    /**
     * Load a video file from its absolute [path] and optionally seek to
     * [startPositionMs] (for resume).
     */
    fun prepare(path: String, startPositionMs: Long = 0L) {
        val uri = if (path.startsWith("/") || path.startsWith("file")) {
            Uri.fromFile(File(path))
        } else {
            Uri.parse(path)
        }
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = true
    }

    /**
     * Load a playlist of video paths starting at [startIndex].
     */
    fun preparePlaylist(
        paths: List<String>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L
    ) {
        val items = paths.map { path ->
            val uri = if (path.startsWith("/") || path.startsWith("file")) {
                Uri.fromFile(File(path))
            } else {
                Uri.parse(path)
            }
            MediaItem.fromUri(uri)
        }
        player.setMediaItems(items, startIndex, startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    /** Seek forward by [ms] milliseconds. */
    fun seekForward(ms: Long = 10_000L) {
        val dur = player.duration
        if (dur == C.TIME_UNSET) return
        player.seekTo((player.currentPosition + ms).coerceAtMost(dur))
    }

    /** Seek backward by [ms] milliseconds. */
    fun seekBackward(ms: Long = 10_000L) {
        player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
    }

    /** Absolute seek. */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    /** Use keyframe-only seeking (fast, for scrub gestures). */
    fun setFastSeekMode(fast: Boolean) {
        player.setSeekParameters(
            if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT
        )
    }

    // ── Speed ────────────────────────────────────────────────────────────

    /** Change playback speed (e.g., 0.5f, 1.0f, 2.0f). */
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    /** Current playback speed. */
    val currentSpeed: Float
        get() = player.playbackParameters.speed

    // ── Loop ─────────────────────────────────────────────────────────────

    /** Toggle single-video loop on/off. */
    fun toggleLoop(): Boolean {
        val looping = player.repeatMode != Player.REPEAT_MODE_ONE
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        return looping
    }

    /** Whether loop is currently on. */
    val isLooping: Boolean
        get() = player.repeatMode == Player.REPEAT_MODE_ONE

    // ── Volume / Mute ────────────────────────────────────────────────────

    /** Toggle mute. Returns true if now muted. */
    fun toggleMute(): Boolean {
        val muting = player.volume > 0f
        player.volume = if (muting) 0f else 1f
        return muting
    }

    val isMuted: Boolean
        get() = player.volume == 0f

    // ── Subtitle ─────────────────────────────────────────────────────────

    /**
     * Add an external SRT subtitle track from [uri] path.
     */
    fun setSubtitle(uri: String, mimeType: String = "application/x-subrip") {
        val currentItem = player.currentMediaItem ?: return
        val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
            .setMimeType(mimeType)
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()

        val updated = currentItem.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()

        val position = player.currentPosition
        player.setMediaItem(updated)
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = true
    }

    // ── State Helpers ────────────────────────────────────────────────────

    /** Current playback position in milliseconds. */
    val currentPosition: Long
        get() = player.currentPosition

    /**
     * Total duration of the current media in milliseconds.
     * Returns 0 if duration is unknown.
     */
    val duration: Long
        get() {
            val d = player.duration
            return if (d == C.TIME_UNSET) 0L else d
        }

    /** Whether the player is currently playing. */
    val isPlaying: Boolean
        get() = player.isPlaying

    // ── Listener ─────────────────────────────────────────────────────────

    fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Release the player. Always call this in onDestroy / onStop. */
    fun release() {
        player.release()
    }
}
