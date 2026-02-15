package com.example.videoplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerManager(
    context: Context,
    private val videoLayout: VLCVideoLayout,
    private val onBufferingChanged: (Boolean) -> Unit = {},
    private val onPlayingChanged: (Boolean) -> Unit = {},
    private val onLengthChanged: (Long) -> Unit = {},
    private val onEnded: () -> Unit = {}
) {

    companion object {
        private const val TAG = "VlcPlayerManager"
    }

    private val appContext = context.applicationContext
    private val libVlc: LibVLC = LibVLC(appContext, arrayListOf(
        "--no-drop-late-frames",
        "--no-skip-frames"
    ))

    val player: MediaPlayer = MediaPlayer(libVlc)

    private var pendingInitialSeekMs: Long = -1L
    private var hasAppliedInitialSeek: Boolean = false

    var loopingEnabled: Boolean = false
    private var isMuted: Boolean = false
    private var lastVolume: Int = 100

    val isPlaying: Boolean
        get() = player.isPlaying

    val duration: Long
        get() = player.length

    val currentPosition: Long
        get() = player.time

    fun attach() {
        player.attachViews(videoLayout, null, false, false)
    }

    fun detach() {
        runCatching { player.detachViews() }
    }

    fun prepare(pathOrUri: String, startPositionMs: Long = 0L) {
        val uri = Uri.parse(pathOrUri)
        val media = if (uri.scheme.isNullOrEmpty()) {
            // Treat as file path
            Media(libVlc, Uri.fromFile(java.io.File(pathOrUri)))
        } else {
            Media(libVlc, uri)
        }

        // Keep it simple: let VLC decide demux/codec.
        media.setHWDecoderEnabled(true, false)

        pendingInitialSeekMs = startPositionMs
        hasAppliedInitialSeek = false

        fun tryApplyInitialSeek(trigger: String) {
            val target = pendingInitialSeekMs
            if (hasAppliedInitialSeek || target <= 0L) return
            // Apply only after VLC is actually playing/initialized.
            // Some files will ignore player.time if set too early and start from 0.
            if (player.isPlaying) {
                hasAppliedInitialSeek = true
                Log.d(TAG, "applyInitialSeek: trigger=$trigger targetMs=$target")
                player.time = target
            }
        }

        player.setEventListener(MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    // event.buffering is 0..100
                    onBufferingChanged(event.buffering < 99.5f)
                }
                MediaPlayer.Event.Playing -> {
                    onBufferingChanged(false)
                    onPlayingChanged(true)
                    tryApplyInitialSeek("Playing")
                }
                MediaPlayer.Event.Paused,
                MediaPlayer.Event.Stopped -> {
                    onBufferingChanged(false)
                    onPlayingChanged(false)
                }
                MediaPlayer.Event.LengthChanged -> {
                    // Duration can arrive late; report it so UI can enable seekbar.
                    onLengthChanged(player.length)
                    tryApplyInitialSeek("LengthChanged")
                }
                MediaPlayer.Event.EndReached -> {
                    if (loopingEnabled) {
                        player.time = 0L
                        player.play()
                    } else {
                        onEnded()
                    }
                }
            }
        })

        player.media = media
        media.release()

        attach()
        Log.d(TAG, "prepare: startPositionMs=$startPositionMs source=$pathOrUri")
        player.play()
        // Initial seek is applied once VLC reports it's playing.
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        player.time = positionMs
    }

    fun setSpeed(speed: Float) {
        runCatching { player.setRate(speed) }
    }

    fun toggleMute(): Boolean {
        if (!isMuted) {
            lastVolume = player.volume
            player.volume = 0
            isMuted = true
        } else {
            player.volume = lastVolume.coerceAtLeast(1)
            isMuted = false
        }
        return isMuted
    }

    fun release() {
        detach()
        runCatching { player.stop() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
    }
}
