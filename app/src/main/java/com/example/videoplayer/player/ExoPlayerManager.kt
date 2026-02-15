package com.example.videoplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaParserExtractorAdapter
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File

/**
 * Encapsulates [ExoPlayer] setup, lifecycle, and convenience helpers.
 *
 * Create one instance per player screen and call [release] when done.
 */
@OptIn(UnstableApi::class)
class ExoPlayerManager(context: Context) {

    private val appContext = context.applicationContext

    /** Underlying Media3 ExoPlayer instance — configured for fast startup. */
    private val dataSourceFactory = DefaultDataSource.Factory(appContext)
    private val extractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs */         2_000,   // start with tiny buffer for faster first-frame
                    /* maxBufferMs */         50_000,  // buffer up to 50s once playing
                    /* bufferForPlaybackMs */ 500,     // start playback after just 500ms buffered
                    /* bufferForPlaybackAfterRebufferMs */ 1_500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()
        .apply {
            setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        }

    // ── Seek state tracking ─────────────────────────────────────────────
    /**
     * Pending initial seek: stored when preparing a video that should resume.
     * We prepare from 0 (so the extractor builds a full SeekMap), then seek
     * to this position on the first STATE_READY.
     */
    var pendingSeekAfterPrepareMs: Long = C.TIME_UNSET
        private set

    /**
     * Last seek target for commit — used to detect if a seek resolved
     * to the wrong position (e.g. MKV files with broken cue indices).
     */
    var lastCommittedSeekTargetMs: Long = C.TIME_UNSET
        private set

    /** Clear the pending-seek-after-prepare flag. */
    fun clearPendingSeekAfterPrepare() {
        pendingSeekAfterPrepareMs = C.TIME_UNSET
    }

    /** Clear the committed seek target (e.g. after validation). */
    fun clearCommittedSeekTarget() {
        lastCommittedSeekTargetMs = C.TIME_UNSET
    }

    // ── Playback Controls ────────────────────────────────────────────────

    /**
     * Load a video file from its absolute [path] and optionally start at
     * [startPositionMs] (for resume).
     *
     * Strategy: prepare the media from position 0 first so ExoPlayer's
     * MatroskaExtractor can build a complete SeekMap. Then, on the first
     * STATE_READY, seek to [startPositionMs]. This fixes MKV files where
     * the seek index (cues) isn't available until the extractor has parsed
     * the container header fully.
     */
    fun prepare(
        path: String,
        startPositionMs: Long = 0L,
        useMediaParserForMkv: Boolean = false,
        disableCueSeekingForMkv: Boolean = false
    ) {
        val uri = when {
            path.startsWith("content://") || path.startsWith("file://") || path.startsWith("http://") || path.startsWith("https://") -> {
                Uri.parse(path)
            }
            path.startsWith("/") -> Uri.fromFile(File(path))
            else -> Uri.parse(path)
        }

        val lower = path.lowercase()
        val uriLastSegmentLower = (uri.lastPathSegment ?: "").lowercase()

        val isMkv =
            lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                uriLastSegmentLower.endsWith(".mkv") || uriLastSegmentLower.endsWith(".webm")

        val resolverMime = if (uri.scheme == "content") {
            runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        } else {
            null
        }
        val mimeType = when {
            resolverMime?.contains("matroska", ignoreCase = true) == true -> MimeTypes.VIDEO_MATROSKA
            resolverMime?.contains("webm", ignoreCase = true) == true -> MimeTypes.VIDEO_WEBM
            isMkv -> MimeTypes.VIDEO_MATROSKA
            else -> null
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()
        Log.d(
            "ExoPlayerManager",
            "prepare: startPositionMs=$startPositionMs useMediaParserForMkv=$useMediaParserForMkv path=$path"
        )

        // Use platform MediaParser extractor for MKV/WebM only when explicitly requested.
        // MediaParser has known limitations with some Matroska files (e.g., multi-segment).
        val shouldUseMediaParser = useMediaParserForMkv && isMkv && Build.VERSION.SDK_INT >= 30

        val shouldDisableCueSeeking = disableCueSeekingForMkv && isMkv

        Log.d(
            "ExoPlayerManager",
            "prepare: uri=$uri scheme=${uri.scheme} isMkv=$isMkv resolverMime=$resolverMime mimeType=$mimeType disableCueSeekingForMkv=$disableCueSeekingForMkv"
        )

        if (shouldDisableCueSeeking) {
            // Matroska cue/index seeking can be broken for some files (e.g. multi-segment MKV).
            // Disabling cue-based seeking forces fallback to constant-bitrate seeking where possible.
            val mkvFallbackExtractors = DefaultExtractorsFactory()
                .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

            val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory, mkvFallbackExtractors)
            val mediaSource = progressiveFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, /* resetPosition= */ true)
        } else if (shouldUseMediaParser) {
            // Use platform MediaParser-based extractor for progressive media.
            // This can seek better on some Matroska files where cue/index parsing is unreliable.
            val mediaParserFactory = MediaParserExtractorAdapter.Factory().apply {
                setConstantBitrateSeekingEnabled(true)
            }
            val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory, mediaParserFactory)
            val mediaSource = progressiveFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, /* resetPosition= */ true)
        } else {
            // Default path uses ExoPlayer's media source factory (with our tuned extractors).
            player.setMediaItem(mediaItem, /* startPositionMs= */ 0L)
        }

        if (startPositionMs > 0L) {
            // Store the resume position — we'll seek after STATE_READY
            pendingSeekAfterPrepareMs = startPositionMs
        } else {
            pendingSeekAfterPrepareMs = C.TIME_UNSET
        }
        player.prepare()
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
            val uri = when {
                path.startsWith("content://") || path.startsWith("file://") || path.startsWith("http://") || path.startsWith("https://") -> {
                    Uri.parse(path)
                }
                path.startsWith("/") -> Uri.fromFile(File(path))
                else -> Uri.parse(path)
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
        Log.d("ExoPlayerManager", "seekTo: positionMs=$positionMs currentPos=${player.currentPosition} dur=${duration} state=${player.playbackState}")
        player.seekTo(positionMs)
    }

    /**
     * Commit-level seek — records the target so we can detect if it fails
     * (e.g. MKV files resolving every seek to position 0).
     */
    fun seekToCommit(positionMs: Long) {
        lastCommittedSeekTargetMs = positionMs
        Log.d("ExoPlayerManager", "seekToCommit: target=$positionMs currentPos=${player.currentPosition}")
        player.seekTo(positionMs)
    }

    /**
     * Fast seek mode is intentionally disabled — CLOSEST_SYNC causes
     * HEVC MKV files with sparse keyframes to snap every seek to position 0.
     * Using DEFAULT for all seeks is slower but correct.
     */
    fun setFastSeekMode(fast: Boolean) {
        // Intentionally always use DEFAULT — CLOSEST_SYNC breaks some containers
        player.setSeekParameters(SeekParameters.DEFAULT)
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
     * Uses setMediaItem with seekTo to minimize interruption.
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
        val wasPlaying = player.playWhenReady
        player.setMediaItem(updated, position)
        player.prepare()
        player.playWhenReady = wasPlaying
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
