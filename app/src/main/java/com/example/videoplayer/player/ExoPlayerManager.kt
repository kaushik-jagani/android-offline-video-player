package com.example.videoplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
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
    // ── Audio Track Selection ────────────────────────────────────────

    /** Lightweight info about an audio track. */
    data class AudioTrackInfo(
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val language: String?,
        val isSelected: Boolean
    )

    /**
     * Returns all available audio tracks with their selection state.
     * Thread-safe: only accesses player on the calling (main) thread.
     */
    fun getAudioTracks(): List<AudioTrackInfo> {
        val tracks = mutableListOf<AudioTrackInfo>()
        val currentTracks = player.currentTracks
        for (group in currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language
                val label = buildString {
                    format.label?.let { append(it) }
                    if (lang != null) {
                        if (isNotEmpty()) append(" - ")
                        append(java.util.Locale(lang).displayLanguage)
                    }
                    if (isEmpty()) append("Track ${tracks.size + 1}")
                    // Append channel info
                    if (format.channelCount > 0) {
                        append(" (${format.channelCount}ch)")
                    }
                }
                tracks.add(
                    AudioTrackInfo(
                        groupIndex = currentTracks.groups.indexOf(group),
                        trackIndex = i,
                        label = label,
                        language = lang,
                        isSelected = group.isTrackSelected(i)
                    )
                )
            }
        }
        return tracks
    }

    /**
     * Select a specific audio track by group and track index.
     * Pass groupIndex = -1 to disable all audio tracks.
     */
    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        if (groupIndex < 0) {
            // Disable audio
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            return
        }
        val groups = player.currentTracks.groups
        val targetGroup = groups.getOrNull(groupIndex) ?: return
        if (targetGroup.type != C.TRACK_TYPE_AUDIO) return

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(
                TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(trackIndex))
            )
            .build()
    }

    /** Whether audio track type is currently disabled. */
    val isAudioDisabled: Boolean
        get() = player.trackSelectionParameters
            .disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)

    /** Re-enable audio (undo disable). */
    fun enableAudio() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
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
