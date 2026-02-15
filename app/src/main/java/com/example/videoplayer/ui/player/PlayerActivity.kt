package com.example.videoplayer.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.videoplayer.R
import com.example.videoplayer.databinding.ActivityPlayerBinding
import com.example.videoplayer.player.ExoPlayerManager
import com.example.videoplayer.player.VlcPlayerManager
import com.example.videoplayer.utils.Constants
import com.example.videoplayer.utils.TimeFormatter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope

/**
 * Modern fullscreen video player with:
 * - Previous / Next navigation, gesture controls
 * - Toolbar: A-B repeat, Equalizer, Speed, Screenshot, Rotation
 * - Expanded panel: Night mode, Loop, Mute, Sleep timer
 * - Bottom: Lock, Aspect ratio toggle, Fit/Fill toggle
 * - Remaining time display
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var playerManager: ExoPlayerManager
    private var vlcManager: VlcPlayerManager? = null
    private lateinit var gestureHandler: PlayerGestureHandler

    private val requestWriteStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                takeScreenshot()
            } else {
                Toast.makeText(this, R.string.screenshot_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private var isUsingVlcFallback = false
    private var hasSwitchedToVlcFallback = false

    // ── Playlist & current video ─────────────────────────────────────────
    private var playlistPaths: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var playlistIds: LongArray = longArrayOf()
    private var currentIndex: Int = 0

    private var videoId: Long = -1L
    private var videoPath: String = ""
    private var videoTitle: String = ""
    private var startPosition: Long = 0L

    // ── UI state ─────────────────────────────────────────────────────────
    private var isControlsVisible = true
    private var isScreenLocked = false

    private var isNightMode = false
    private var isMuted = false

    // ── A-B Repeat state ─────────────────────────────────────────────────
    private var abPointA: Long = -1L
    private var abPointB: Long = -1L

    // ── Resize modes cycle: Fit → Stretch → Crop → 100% ───────────────────
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    )
    private var currentResizeIndex = 0

    // ── Sleep timer ──────────────────────────────────────────────────────
    private var sleepTimer: CountDownTimer? = null

    // ── Seek preview state ───────────────────────────────────────────────
    private var isSeeking = false
    private var seekStartPosition = 0L
    private var wasPlayingBeforeSeek = false
    private var isUserDraggingSeekbar = false

    // ── VLC scrubbing throttling (only in VLC mode) ─────────────────────
    private var lastVlcScrubSeekUptimeMs: Long = 0L
    private var lastVlcScrubTargetMs: Long = -1L

    // ── Seek-failure protection ──────────────────────────────────────────
    /** Timestamp of the last committed seek (seekbar release or gesture commit). */
    private var lastCommitSeekTimestamp = 0L
    /** Target position of the last committed seek. */
    private var lastCommitSeekTargetMs = -1L
    /** Number of seek retries attempted for the current commit target. */
    private var seekRetryCount = 0
    /** Whether we've retried playback using MediaParser extractor (API 30+). */
    private var hasTriedMediaParserFallback = false
    private var hasTriedDisableCueSeekFallback = false

    // ── Loading overlay state ────────────────────────────────────────────
    private var isPlayerReady = false

    // ── Indicator track height (130dp → px) for brightness/volume bars ──
    private val indicatorTrackHeightPx: Int by lazy {
        (130 * resources.displayMetrics.density + 0.5f).toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val hideIndicatorRunnable = Runnable {
        binding.brightnessIndicator.visibility = View.GONE
        binding.volumeIndicator.visibility = View.GONE
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            // Always keep progress ticking while the activity is alive.
            // If we stop the runnable on a temporary pause/buffer, VLC doesn't
            // always restart it, which can make the UI appear stuck at 0.
            if (isFinishing || isDestroyed) return
            updateProgress()
            checkAbRepeat()
            handler.postDelayed(this, 500L)
        }
    }

    private fun startProgressUpdates() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    private fun syncPlayPauseIcon(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun setKeepScreenOnEnabled(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun vlcScrubSeekTo(targetMs: Long) {
        if (!isUsingVlcFallback) return
        val mgr = vlcManager ?: return

        val now = SystemClock.uptimeMillis()
        // Throttle to keep UI smooth (avoid spamming time seeks).
        if (now - lastVlcScrubSeekUptimeMs < 120L) return

        // Skip tiny updates to reduce churn.
        if (lastVlcScrubTargetMs >= 0L && abs(targetMs - lastVlcScrubTargetMs) < 250L) return

        lastVlcScrubSeekUptimeMs = now
        lastVlcScrubTargetMs = targetMs
        mgr.seekTo(targetMs)
    }

    private fun isPlayingCompat(): Boolean {
        return if (isUsingVlcFallback) {
            vlcManager?.isPlaying == true
        } else {
            ::playerManager.isInitialized && playerManager.isPlaying
        }
    }

    private fun currentPositionCompat(): Long {
        return if (isUsingVlcFallback) {
            vlcManager?.currentPosition ?: 0L
        } else {
            if (::playerManager.isInitialized) playerManager.currentPosition else 0L
        }
    }

    private fun durationCompat(): Long {
        return if (isUsingVlcFallback) {
            vlcManager?.duration ?: C.TIME_UNSET
        } else {
            if (::playerManager.isInitialized) playerManager.duration else C.TIME_UNSET
        }
    }

    private fun playCompat() {
        if (isUsingVlcFallback) vlcManager?.play() else playerManager.play()
    }

    private fun pauseCompat() {
        if (isUsingVlcFallback) vlcManager?.pause() else playerManager.pause()
    }

    private fun togglePlayPauseCompat() {
        if (isUsingVlcFallback) {
            val willBePlaying = !(vlcManager?.isPlaying ?: false)
            vlcManager?.togglePlayPause()
            syncPlayPauseIcon(willBePlaying)
        } else {
            playerManager.togglePlayPause()
        }
    }

    private fun seekToCompat(positionMs: Long) {
        if (isUsingVlcFallback) vlcManager?.seekTo(positionMs) else playerManager.seekTo(positionMs)
    }

    private fun seekToCommitCompat(positionMs: Long) {
        if (isUsingVlcFallback) {
            vlcManager?.seekTo(positionMs)
        } else {
            playerManager.seekToCommit(positionMs)
        }
    }

    private fun switchToVlcFallback(startPositionMs: Long) {
        if (hasSwitchedToVlcFallback) return
        hasSwitchedToVlcFallback = true
        isUsingVlcFallback = true

        // Stop Exo playback but keep instance alive (other UI still references it).
        runCatching { playerManager.pause() }
        runCatching { playerManager.player.playWhenReady = false }

        binding.playerView.player = null
        binding.playerView.visibility = View.GONE
        binding.vlcVideoLayout.visibility = View.VISIBLE

        vlcManager?.release()
        vlcManager = VlcPlayerManager(
            context = this,
            videoLayout = binding.vlcVideoLayout,
            onBufferingChanged = { buffering ->
                if (!isSeeking && !isUserDraggingSeekbar) {
                    binding.progressBar.visibility = if (buffering) View.VISIBLE else View.GONE
                }
            },
            onPlayingChanged = { playing ->
                handler.post {
                    syncPlayPauseIcon(playing)
                    setKeepScreenOnEnabled(playing)
                    if (playing && !isPlayerReady) onFirstFrameRendered()
                    if (playing) startProgressUpdates()
                }
            },
            onLengthChanged = { lengthMs ->
                handler.post {
                    if (lengthMs > 0) {
                        binding.seekBar.max = lengthMs.toInt()
                        binding.seekBar.isEnabled = true
                    }
                }
            },
            onEnded = {
                if (videoId != -1L) viewModel.savePosition(videoId, 0L)
            }
        )
        Log.w(TAG, "Switching to LibVLC fallback; startPositionMs=$startPositionMs")
        vlcManager?.prepare(videoPath, startPositionMs)
        startProgressUpdates()
    }

    // ═══════════════════════════ Lifecycle ════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractIntentExtras()
        if (videoPath.isEmpty()) { finish(); return }

        // Restore saved instance state (e.g. after process death)
        savedInstanceState?.let { state ->
            currentIndex = state.getInt("currentIndex", currentIndex)
            isNightMode = state.getBoolean("isNightMode", false)
            isMuted = state.getBoolean("isMuted", false)
            currentResizeIndex = state.getInt("currentResizeIndex", 0)
            abPointA = state.getLong("abPointA", -1L)
            abPointB = state.getLong("abPointB", -1L)
            startPosition = state.getLong("playbackPosition", startPosition)
            // Re-derive current video info from restored index
            if (currentIndex in playlistPaths.indices) {
                videoPath = playlistPaths[currentIndex]
                videoTitle = playlistTitles[currentIndex]
                videoId = if (currentIndex < playlistIds.size) playlistIds[currentIndex] else -1L
            }
        }

        hideSystemUi()
        showLoadingOverlay()

        // ── Critical path: start player preparation ASAP ──
        setupPlayer()

        // ── Deferred: UI setup runs on next frame so it doesn't block decoding ──
        handler.post {
            setupControls()
            setupToolbar()
            setupGestures()
            // Restore visual state from savedInstanceState
            if (isNightMode) binding.nightOverlay.visibility = View.VISIBLE
            if (isMuted) binding.btnMute.setImageResource(R.drawable.ic_mute)
            if (currentResizeIndex > 0) {
                binding.playerView.resizeMode = resizeModes[currentResizeIndex]
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::playerManager.isInitialized) pauseCompat()
        setKeepScreenOnEnabled(false)
        saveCurrentPosition()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)  // Covers loadingTimeoutRunnable + all others
        sleepTimer?.cancel()
        if (::playerManager.isInitialized) {
            playerManager.release()
        }
        vlcManager?.release()
        setKeepScreenOnEnabled(false)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("currentIndex", currentIndex)
        outState.putBoolean("isNightMode", isNightMode)
        outState.putBoolean("isMuted", isMuted)
        outState.putInt("currentResizeIndex", currentResizeIndex)
        outState.putLong("abPointA", abPointA)
        outState.putLong("abPointB", abPointB)
        outState.putLong("playbackPosition", currentPositionCompat())
    }

    // ═══════════════════════════ Init ═════════════════════════════════════

    private fun extractIntentExtras() {
        videoId = intent.getLongExtra(Constants.EXTRA_VIDEO_ID, -1L)
        videoPath = intent.getStringExtra(Constants.EXTRA_VIDEO_PATH) ?: ""
        videoTitle = intent.getStringExtra(Constants.EXTRA_VIDEO_TITLE) ?: ""
        startPosition = intent.getLongExtra(Constants.EXTRA_START_POSITION, 0L)

        playlistPaths = intent.getStringArrayListExtra(Constants.EXTRA_PLAYLIST_PATHS) ?: listOf(videoPath)
        playlistTitles = intent.getStringArrayListExtra(Constants.EXTRA_PLAYLIST_TITLES) ?: listOf(videoTitle)
        playlistIds = intent.getLongArrayExtra(Constants.EXTRA_PLAYLIST_IDS) ?: longArrayOf(videoId)
        currentIndex = intent.getIntExtra(Constants.EXTRA_PLAYLIST_INDEX, 0)
    }

    private fun setupPlayer() {
        playerManager = ExoPlayerManager(this)
        binding.playerView.player = playerManager.player
        binding.playerView.useController = false
        binding.vlcVideoLayout.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE

        startProgressUpdates()

        binding.tvTitle.text = videoTitle

        playerManager.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlayPauseIcon(isPlaying)
                setKeepScreenOnEnabled(isPlaying)
                if (isPlaying) {
                    startProgressUpdates()
                    if (isPlayerReady) scheduleHideControls()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "onPlaybackStateChanged: state=$state pos=${playerManager.currentPosition} dur=${playerManager.duration}")
                when (state) {
                    Player.STATE_READY -> {
                        val dur = playerManager.duration
                        val curPos = playerManager.currentPosition
                        Log.d(TAG, "STATE_READY: duration=$dur currentPos=$curPos seekBar.max=${binding.seekBar.max}")
                        if (dur > 0) {
                            binding.seekBar.max = dur.toInt()
                            binding.seekBar.isEnabled = true
                        }

                        // ── Deferred seek after prepare (resume) ──────────────
                        val pendingSeek = playerManager.pendingSeekAfterPrepareMs
                        if (pendingSeek != C.TIME_UNSET && pendingSeek > 0L) {
                            Log.d(TAG, "STATE_READY: executing deferred seek to $pendingSeek")
                            playerManager.clearPendingSeekAfterPrepare()
                            playerManager.seekToCommit(pendingSeek)
                            lastCommitSeekTargetMs = pendingSeek
                            lastCommitSeekTimestamp = SystemClock.uptimeMillis()
                            seekRetryCount = 0
                            return  // wait for next STATE_READY after seek
                        }

                        // ── Seek failure detection ────────────────────────────
                        val commitTarget = playerManager.lastCommittedSeekTargetMs
                        if (commitTarget != C.TIME_UNSET && commitTarget > 3000L && curPos < 1000L) {
                            seekRetryCount++
                            Log.w(TAG, "STATE_READY: seek missed target=$commitTarget actual=$curPos retry=$seekRetryCount")
                            if (seekRetryCount <= 2) {
                                // Retry the seek — extractor may have built a better SeekMap now
                                playerManager.player.seekTo(commitTarget)
                                return  // wait for next STATE_READY
                            }

                            // Persistent seek-to-0: switch to LibVLC fallback (robust MKV seeking).
                            seekRetryCount = 0
                            playerManager.clearCommittedSeekTarget()
                            switchToVlcFallback(commitTarget)
                            return

                            // Still failing: stop trying to seek repeatedly.
                            playerManager.clearCommittedSeekTarget()
                            seekRetryCount = 0
                            if (isPlayerReady) binding.progressBar.visibility = View.GONE
                            return
                        }
                        // Seek landed correctly — clear tracking
                        if (commitTarget != C.TIME_UNSET) {
                            playerManager.clearCommittedSeekTarget()
                            seekRetryCount = 0
                        }

                        // Hide buffering spinner only after first frame has been shown
                        if (isPlayerReady) {
                            binding.progressBar.visibility = View.GONE
                        }
                        if (!isUserDraggingSeekbar && !isSeeking) updateProgress()
                    }
                    Player.STATE_BUFFERING -> {
                        // Show mid-playback spinner ONLY if player is already past first frame
                        if (isPlayerReady && !isSeeking && !isUserDraggingSeekbar) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (videoId != -1L) viewModel.savePosition(videoId, 0L)
                    }
                    else -> { /* idle */ }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                autoRotateForVideo(videoSize.width, videoSize.height)
            }

            override fun onTimelineChanged(
                timeline: androidx.media3.common.Timeline,
                reason: Int
            ) {
                val dur = playerManager.duration
                if (dur > 0 && binding.seekBar.max != dur.toInt()) {
                    binding.seekBar.max = dur.toInt()
                    binding.seekBar.isEnabled = true
                    if (!isUserDraggingSeekbar && !isSeeking) updateProgress()
                }
            }

            override fun onRenderedFirstFrame() {
                // First actual video frame is visible — dismiss loading overlay
                onFirstFrameRendered()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // On error, dismiss loading so user sees the error message
                dismissLoadingOverlay()
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@PlayerActivity,
                    "Cannot play this video: ${error.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // Always query Room for the freshest saved position (intent extra can be stale)
        if (videoId != -1L) {
            lifecycleScope.launch {
                val freshPos = viewModel.getLastPosition(videoId)
                // Use Room position if it's > 0, otherwise fall back to intent's startPosition
                val resumePos = if (freshPos > 0L) freshPos else startPosition
                Log.d(TAG, "setupPlayer: videoId=$videoId freshPos=$freshPos startPosition=$startPosition resumePos=$resumePos path=$videoPath")
                playerManager.prepare(videoPath, resumePos)
            }
        } else {
            Log.d(TAG, "setupPlayer: no videoId, startPosition=$startPosition path=$videoPath")
            playerManager.prepare(videoPath, startPosition)
        }
        updatePrevNextButtons()
    }

    // ═══════════════════════ Center Controls ══════════════════════════════

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { togglePlayPauseCompat() }

        binding.btnPrevious.setOnClickListener { playPreviousVideo() }
        binding.btnNext.setOnClickListener { playNextVideo() }

        // Disable seekbar until duration is known (prevents seeks with wrong max)
        binding.seekBar.isEnabled = false

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvPosition.text = TimeFormatter.format(progress.toLong())
                    updateRemainingTime(progress.toLong())
                    showSeekbarPreview(progress.toLong())

                    // In VLC mode, do real scrubbing seeks so the displayed frame
                    // updates while dragging (MX Player-like behavior).
                    if (isUsingVlcFallback && isUserDraggingSeekbar) {
                        vlcScrubSeekTo(progress.toLong())
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                val dur = durationCompat()
                // Re-sync seekBar.max with actual duration and fix thumb position
                if (dur > 0 && sb != null && sb.max != dur.toInt()) {
                    val oldMax = sb.max
                    val oldProgress = sb.progress
                    sb.max = dur.toInt()
                    // Remap thumb position proportionally to the corrected max
                    if (oldMax > 0) {
                        sb.progress = (oldProgress.toLong() * dur / oldMax).toInt()
                    }
                }
                isUserDraggingSeekbar = true
                wasPlayingBeforeSeek = isPlayingCompat()
                if (wasPlayingBeforeSeek) pauseCompat()
                // Note: no setFastSeekMode — CLOSEST_SYNC is disabled
                binding.progressBar.visibility = View.GONE
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let {
                    val dur = durationCompat()
                    val seekPosition = it.progress.toLong().coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                    Log.d(TAG, "onStopTrackingTouch: seekTo=$seekPosition dur=$dur sbMax=${it.max} sbProgress=${it.progress} currentPos=${currentPositionCompat()}")

                    // Record guard state so updateProgress won't reset seekbar to 0
                    lastCommitSeekTargetMs = seekPosition
                    lastCommitSeekTimestamp = SystemClock.uptimeMillis()
                    seekRetryCount = 0

                    seekToCommitCompat(seekPosition)
                }
                if (wasPlayingBeforeSeek) playCompat()
                isUserDraggingSeekbar = false
                hideSeekPreview()
                scheduleHideControls()
            }
        })

        binding.btnBack.setOnClickListener {
            saveCurrentPosition()
            finish()
        }

        // Lock (bottom bar)
        binding.btnLock.setOnClickListener { toggleScreenLock() }
        binding.btnLockStandalone.setOnClickListener { toggleScreenLock() }

        // Aspect ratio cycle (Fit → Fill → Zoom)
        binding.btnAspectRatio.setOnClickListener { cycleResizeMode() }

        // Fit/Fill screen toggle
        // (removed – btnAspectRatio now cycles through all modes)
    }

    // ═══════════════════════ Top Toolbar ══════════════════════════════════

    private fun setupToolbar() {
        // Speed picker
        binding.btnSpeed.setOnClickListener { showSpeedPicker() }

        // A-B Repeat
        binding.btnAbRepeat.setOnClickListener { handleAbRepeat() }

        // Equalizer placeholder
        binding.btnEqualizer.setOnClickListener {
            Toast.makeText(this, R.string.equalizer, Toast.LENGTH_SHORT).show()
        }

        // Audio Track
        binding.btnAudioTrack.setOnClickListener { showAudioTrackPicker() }

        // Screenshot
        binding.btnScreenshot.setOnClickListener { takeScreenshotWithPermission() }

        // Screen Rotation
        binding.btnRotation.setOnClickListener { cycleOrientation() }

        // Night Mode
        binding.btnNightMode.setOnClickListener { toggleNightMode() }

        // Loop
        binding.btnLoop.setOnClickListener { toggleLoop() }

        // Mute
        binding.btnMute.setOnClickListener { toggleMute() }

        // Sleep Timer
        binding.btnSleepTimer.setOnClickListener { showSleepTimerPicker() }
    }

    // ═══════════════════════ Gestures ═════════════════════════════════════

    private fun setupGestures() {
        gestureHandler = PlayerGestureHandler(
            context = this,
            window = window,
            view = binding.gestureOverlay,
            onToggleControls = { if (!isScreenLocked && isPlayerReady) toggleControls() },
            onDoubleTap = { if (!isScreenLocked && isPlayerReady) togglePlayPauseCompat() },
            onBrightnessChanged = { brightness ->
                if (!isScreenLocked && isPlayerReady) showBrightnessIndicator(brightness)
            },
            onVolumeChanged = { volume, maxVol ->
                if (!isScreenLocked && isPlayerReady) showVolumeIndicator(volume, maxVol)
            },
            onSeekPreview = { deltaMs ->
                if (!isScreenLocked && isPlayerReady) showSeekPreview(deltaMs)
            },
            onSeekCommit = { deltaMs ->
                if (!isScreenLocked && isPlayerReady) commitSeek(deltaMs)
            },
            onGestureEnd = {
                scheduleHideIndicators()
            }
        )
        gestureHandler.attach()
    }

    // ═══════════════════════ Brightness / Volume Indicators ═══════════════

    private fun showBrightnessIndicator(brightness: Float) {
        handler.removeCallbacks(hideIndicatorRunnable)
        val percent = (brightness * 100).toInt()
        binding.brightnessIndicator.visibility = View.VISIBLE
        binding.tvBrightnessPercent.text = "$percent%"

        val params = binding.brightnessProgressFill.layoutParams
        params.height = (indicatorTrackHeightPx * brightness).toInt()
        binding.brightnessProgressFill.layoutParams = params
    }

    private fun showVolumeIndicator(volume: Int, maxVolume: Int) {
        handler.removeCallbacks(hideIndicatorRunnable)
        val percent = if (maxVolume > 0) (volume * 100 / maxVolume) else 0
        binding.volumeIndicator.visibility = View.VISIBLE
        binding.tvVolumePercent.text = "$percent%"

        val fraction = if (maxVolume > 0) volume.toFloat() / maxVolume else 0f
        val params = binding.volumeProgressFill.layoutParams
        params.height = (indicatorTrackHeightPx * fraction).toInt()
        binding.volumeProgressFill.layoutParams = params
    }

    private fun scheduleHideIndicators() {
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 800L)
    }

    // ═══════════════════════ Seek Preview (real-time scrubbing) ════════════

    private fun showSeekPreview(deltaMs: Long) {
        val dur = durationCompat()

        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = currentPositionCompat()
            Log.d(TAG, "showSeekPreview START: seekStartPosition=$seekStartPosition dur=$dur")
            wasPlayingBeforeSeek = isPlayingCompat()
            if (wasPlayingBeforeSeek) pauseCompat()
            // Note: no setFastSeekMode — CLOSEST_SYNC is disabled to avoid
            // HEVC MKV files snapping every seek to keyframe 0.
            binding.progressBar.visibility = View.GONE
        }

        // Clamp to duration if known, otherwise just keep >= 0
        val targetPos = if (dur > 0L) {
            (seekStartPosition + deltaMs).coerceIn(0L, dur)
        } else {
            (seekStartPosition + deltaMs).coerceAtLeast(0L)
        }

        binding.tvInfo.visibility = View.GONE
        binding.seekPreview.visibility = View.VISIBLE
        binding.tvSeekTarget.text = TimeFormatter.format(targetPos)

        val sign = if (deltaMs >= 0) "+" else "-"
        binding.tvSeekDelta.text = "$sign${TimeFormatter.format(abs(deltaMs))}"

        // ── UI-only during preview — do NOT seek the player on every gesture
        // event. Rapid seekTo calls confuse ExoPlayer's MKV extractor on some
        // files, causing the position to snap to 0.  The actual seek happens
        // in commitSeek() when the gesture ends.
        //
        // In VLC fallback mode, we can safely scrub by seeking in real-time so
        // the user sees the correct frame at the target position.
        if (isUsingVlcFallback) {
            vlcScrubSeekTo(targetPos)
        }
        if (dur > 0L) {
            binding.seekBar.progress = targetPos.toInt()
        }
        binding.tvPosition.text = TimeFormatter.format(targetPos)
        updateRemainingTime(targetPos)
    }

    /** Show seek preview for seekbar drag (absolute position, no delta). */
    private fun showSeekbarPreview(positionMs: Long) {
        val dur = durationCompat()
        if (dur <= 0L) return  // Can't seek without known duration
        val targetPos = positionMs.coerceIn(0L, dur)

        binding.seekPreview.visibility = View.VISIBLE
        binding.tvSeekTarget.text = TimeFormatter.format(targetPos)
        binding.tvSeekDelta.visibility = View.GONE

        // UI-only during drag — actual seek on release (onStopTrackingTouch).
        // Avoids rapid seekTo calls that break MKV/HEVC seeking.
        if (isUsingVlcFallback && isUserDraggingSeekbar) {
            vlcScrubSeekTo(targetPos)
        }
    }

    private fun commitSeek(deltaMs: Long) {
        val dur = durationCompat()
        val targetPos = if (dur > 0L) {
            (seekStartPosition + deltaMs).coerceIn(0L, dur)
        } else {
            (seekStartPosition + deltaMs).coerceAtLeast(0L)
        }
        Log.d(TAG, "commitSeek: deltaMs=$deltaMs seekStartPos=$seekStartPosition targetPos=$targetPos dur=$dur")

        // Record guard state so updateProgress won't reset seekbar to 0
        lastCommitSeekTargetMs = targetPos
        lastCommitSeekTimestamp = SystemClock.uptimeMillis()
        seekRetryCount = 0

        seekToCommitCompat(targetPos)
        if (wasPlayingBeforeSeek) playCompat()
        hideSeekPreview()
    }

    private fun hideSeekPreview() {
        isSeeking = false
        seekStartPosition = 0L
        wasPlayingBeforeSeek = false
        binding.tvSeekDelta.visibility = View.VISIBLE
        binding.seekPreview.visibility = View.GONE
    }

    // ═══════════════════════ Feature: Next / Previous ═════════════════════

    private fun playNextVideo() {
        if (currentIndex < playlistPaths.size - 1) {
            saveCurrentPosition()
            currentIndex++
            loadVideoAtIndex(currentIndex)
        } else {
            Toast.makeText(this, R.string.no_next_video, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPreviousVideo() {
        if (currentIndex > 0) {
            saveCurrentPosition()
            currentIndex--
            loadVideoAtIndex(currentIndex)
        } else {
            Toast.makeText(this, R.string.no_previous_video, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadVideoAtIndex(index: Int) {
        if (index !in playlistPaths.indices) return  // bounds check
        videoPath = playlistPaths[index]
        videoTitle = playlistTitles[index]
        videoId = if (index < playlistIds.size) playlistIds[index] else -1L

        binding.tvTitle.text = videoTitle
        clearAbRepeat()
        updatePrevNextButtons()

        // Show loading overlay for the new video
        showLoadingOverlay()

        // Disable seekbar until new video's duration is known
        binding.seekBar.isEnabled = false
        binding.seekBar.progress = 0
        binding.tvPosition.text = TimeFormatter.format(0L)
        binding.tvRemaining.text = ""

        // Resume from saved position (0 if fully watched or never opened)
        if (videoId != -1L) {
            lifecycleScope.launch {
                val savedPos = viewModel.getLastPosition(videoId)
                playerManager.prepare(videoPath, savedPos)
            }
        } else {
            playerManager.prepare(videoPath, 0L)
        }
    }

    private fun updatePrevNextButtons() {
        binding.btnPrevious.alpha = if (currentIndex > 0) 1f else 0.4f
        binding.btnNext.alpha = if (currentIndex < playlistPaths.size - 1) 1f else 0.4f
    }

    // ═══════════════════════ Feature: Speed Bottom Sheet ════════════════

    private val speedValues = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")

    private fun showSpeedPicker() {
        val currentSpeed = if (isUsingVlcFallback) 1.0f else playerManager.currentSpeed
        val currentIdx = speedValues.indexOfFirst {
            abs(it - currentSpeed) < 0.01f
        }.coerceAtLeast(3) // default 1.0x

        val dialog = BottomSheetDialog(this, R.style.Theme_OfflineVideoPlayer_BottomSheet)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_speed, null)

        val tvSpeed = view.findViewById<TextView>(R.id.tvCurrentSpeed)
        val slider = view.findViewById<Slider>(R.id.speedSlider)

        tvSpeed.text = speedLabels[currentIdx]
        slider.value = currentIdx.toFloat()

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val idx = value.toInt().coerceIn(0, speedValues.lastIndex)
                tvSpeed.text = speedLabels[idx]
                if (isUsingVlcFallback) {
                    vlcManager?.setSpeed(speedValues[idx])
                } else {
                    playerManager.setSpeed(speedValues[idx])
                }
                binding.btnSpeed.text = if (speedValues[idx] == 1.0f) "1X"
                    else "${speedValues[idx]}X"
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // ═══════════════════════ Feature: Audio Track ═════════════════════

    /** Audio sync offset in milliseconds (applied to ExoPlayer). */
    private var audioSyncOffsetMs = 0L

    /** Stereo mode index: 0 = Auto, 1 = Mono, 2 = Stereo. */
    private var stereoModeIndex = 0
    private val stereoLabels by lazy {
        arrayOf(
            getString(R.string.stereo_auto),
            getString(R.string.stereo_mono),
            getString(R.string.stereo_stereo)
        )
    }

    private fun showAudioTrackPicker() {
        val tracks = playerManager.getAudioTracks()
        val isDisabled = playerManager.isAudioDisabled

        val dialog = BottomSheetDialog(this, R.style.Theme_OfflineVideoPlayer_BottomSheet)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_audio_track, null)

        val radioGroup = view.findViewById<RadioGroup>(R.id.rgAudioTracks)
        val cbSwDecoder = view.findViewById<CheckBox>(R.id.cbSwAudioDecoder)
        val tvStereo = view.findViewById<TextView>(R.id.tvStereoMode)
        val btnSyncMinus = view.findViewById<View>(R.id.btnSyncMinus)
        val btnSyncPlus = view.findViewById<View>(R.id.btnSyncPlus)
        val tvSyncValue = view.findViewById<TextView>(R.id.tvSyncValue)
        val cbAvSync = view.findViewById<CheckBox>(R.id.cbAvSync)
        val rowStereo = view.findViewById<View>(R.id.rowStereoMode)

        // ── Row click handlers ──────────────────────────────────────
        val rowSwDecoder = view.findViewById<View>(R.id.rowSwDecoder)
        rowSwDecoder.setOnClickListener { cbSwDecoder.toggle() }

        val rowOpen = view.findViewById<View>(R.id.rowOpen)
        rowOpen.setOnClickListener {
            // Could open an audio file picker in the future
            Toast.makeText(this, "Open audio file", Toast.LENGTH_SHORT).show()
        }

        val rowAvSync = view.findViewById<View>(R.id.rowAvSync)
        rowAvSync.setOnClickListener { cbAvSync.toggle() }

        // ── Populate audio tracks ──────────────────────────────────
        val dp12 = (12 * resources.displayMetrics.density).toInt()
        val dp14 = (14 * resources.displayMetrics.density).toInt()

        if (tracks.isEmpty()) {
            val emptyBtn = RadioButton(this).apply {
                text = getString(R.string.no_audio_tracks)
                setTextColor(resources.getColor(R.color.colorOnSurfaceVariant, theme))
                setButtonDrawable(R.drawable.custom_radio_button)
                isEnabled = false
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt()
                ).apply { marginStart = dp12; marginEnd = dp12 }
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp14, 0, 0, 0)
            }
            radioGroup.addView(emptyBtn)
        } else {
            // "Disable" option
            val disableBtn = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.disable_audio)
                setTextColor(resources.getColor(R.color.colorOnSurface, theme))
                setButtonDrawable(R.drawable.custom_radio_button)
                textSize = 15f
                isChecked = isDisabled
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt()
                ).apply { marginStart = dp12; marginEnd = dp12 }
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    val attrs = intArrayOf(it)
                    val ta = context.obtainStyledAttributes(attrs)
                    val bg = ta.getResourceId(0, 0)
                    ta.recycle()
                    bg
                })
                setPadding(dp14, 0, 0, 0)
            }
            radioGroup.addView(disableBtn)

            // Each audio track
            tracks.forEachIndexed { idx, track ->
                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = track.label
                    setTextColor(resources.getColor(R.color.colorOnSurface, theme))
                    setButtonDrawable(R.drawable.custom_radio_button)
                    textSize = 15f
                    isChecked = track.isSelected && !isDisabled
                    tag = idx  // store track index for lookup
                    layoutParams = RadioGroup.LayoutParams(
                        RadioGroup.LayoutParams.MATCH_PARENT, (48 * resources.displayMetrics.density).toInt()
                    ).apply { marginStart = dp12; marginEnd = dp12 }
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val attrs = intArrayOf(it)
                        val ta = context.obtainStyledAttributes(attrs)
                        val bg = ta.getResourceId(0, 0)
                        ta.recycle()
                        bg
                    })
                    setPadding(dp14, 0, 0, 0)
                }
                radioGroup.addView(rb)
            }

            radioGroup.setOnCheckedChangeListener { group, checkedId ->
                val checkedView = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
                if (checkedView === disableBtn) {
                    playerManager.selectAudioTrack(-1, -1)
                } else {
                    val trackIdx = checkedView.tag as? Int ?: return@setOnCheckedChangeListener
                    val t = tracks.getOrNull(trackIdx) ?: return@setOnCheckedChangeListener
                    playerManager.selectAudioTrack(t.groupIndex, t.trackIndex)
                }
            }
        }

        // ── Stereo mode ────────────────────────────────────────────
        tvStereo.text = stereoLabels[stereoModeIndex]
        rowStereo.setOnClickListener {
            stereoModeIndex = (stereoModeIndex + 1) % stereoLabels.size
            tvStereo.text = stereoLabels[stereoModeIndex]
            // Stereo mode switching is informational in this implementation;
            // ExoPlayer handles channel output automatically for most devices.
        }

        // ── Synchronization ────────────────────────────────────────
        tvSyncValue.text = formatSyncOffset(audioSyncOffsetMs)

        btnSyncMinus.setOnClickListener {
            audioSyncOffsetMs -= 50
            tvSyncValue.text = formatSyncOffset(audioSyncOffsetMs)
            applyAudioSyncOffset()
        }
        btnSyncPlus.setOnClickListener {
            audioSyncOffsetMs += 50
            tvSyncValue.text = formatSyncOffset(audioSyncOffsetMs)
            applyAudioSyncOffset()
        }

        // ── AV Sync checkbox ──────────────────────────────────────
        cbAvSync.isChecked = audioSyncOffsetMs == 0L
        cbAvSync.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                audioSyncOffsetMs = 0L
                tvSyncValue.text = formatSyncOffset(0L)
                applyAudioSyncOffset()
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    /** Format sync offset like "0.00s", "+0.15s", "-0.10s". */
    private fun formatSyncOffset(ms: Long): String {
        val seconds = ms / 1000.0
        val prefix = if (ms > 0) "+" else ""
        return "${prefix}%.2fs".format(seconds)
    }

    /** Apply audio sync offset to ExoPlayer renderer. */
    private fun applyAudioSyncOffset() {
        // ExoPlayer doesn't have a direct audio delay API in Media3 stable.
        // We implement it by adjusting the player's audio session timing.
        // For now, the offset is tracked and displayed; full renderer-level
        // offset requires a custom RenderersFactory which is an advanced feature.
    }

    // ═══════════════════════ Feature: A-B Repeat ═════════════════════════

    private fun handleAbRepeat() {
        when {
            abPointA < 0 -> {
                abPointA = currentPositionCompat()
                binding.btnAbRepeat.alpha = 0.6f
                Toast.makeText(this, R.string.ab_point_a_set, Toast.LENGTH_SHORT).show()
            }
            abPointB < 0 -> {
                abPointB = currentPositionCompat()
                binding.btnAbRepeat.alpha = 1f
                Toast.makeText(this, R.string.ab_point_b_set, Toast.LENGTH_SHORT).show()
            }
            else -> {
                clearAbRepeat()
                Toast.makeText(this, R.string.ab_repeat_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearAbRepeat() {
        abPointA = -1L
        abPointB = -1L
        binding.btnAbRepeat.alpha = 1f
    }

    private fun checkAbRepeat() {
        if (abPointA >= 0 && abPointB > abPointA) {
            if (currentPositionCompat() >= abPointB) {
                seekToCompat(abPointA)
            }
        }
    }

    // ═══════════════════════ Feature: Screenshot ═════════════════════════

    private fun takeScreenshotWithPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.screenshot_permission_required, Toast.LENGTH_SHORT).show()
                requestWriteStoragePermission.launch(permission)
                return
            }
        }
        takeScreenshot()
    }

    private fun takeScreenshot() {
        try {
            val textureView = binding.playerView.videoSurfaceView
            if (textureView is android.view.TextureView) {
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    saveBitmapToGalleryAsync(bitmap)
                    return
                }
            }
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Save bitmap to gallery on a background thread.
     * Uses MediaStore API on API 29+ and file-based approach on API 24-28.
     * Bitmap is recycled after saving.
     */
    private fun saveBitmapToGalleryAsync(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "screenshot_${System.currentTimeMillis()}.jpg"
                val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: Use MediaStore (no WRITE_EXTERNAL_STORAGE needed)
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VideoPlayer")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, contentValues, null, null)
                        true
                    } else false
                } else {
                    // API 24-28: Use file-based approach with WRITE_EXTERNAL_STORAGE
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "VideoPlayer"
                    )
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    MediaScannerConnection.scanFile(this@PlayerActivity, arrayOf(file.absolutePath), null, null)
                    true
                }
                withContext(Dispatchers.Main) {
                    if (saved) {
                        Toast.makeText(this@PlayerActivity, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@PlayerActivity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    // ═══════════════════════ Feature: Screen Rotation ════════════════════

    private fun cycleOrientation() {
        requestedOrientation = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // ═══════════════════════ Feature: Night Mode ═════════════════════════

    private fun toggleNightMode() {
        isNightMode = !isNightMode
        binding.nightOverlay.visibility = if (isNightMode) View.VISIBLE else View.GONE
        Toast.makeText(
            this,
            if (isNightMode) R.string.night_mode_on else R.string.night_mode_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ═══════════════════════ Feature: Loop ════════════════════════════════

    private fun toggleLoop() {
        val nowLooping = if (isUsingVlcFallback) {
            val next = !(vlcManager?.loopingEnabled ?: false)
            vlcManager?.loopingEnabled = next
            next
        } else {
            playerManager.toggleLoop()
        }
        binding.btnLoop.alpha = if (nowLooping) 1f else 0.5f
        Toast.makeText(
            this,
            if (nowLooping) R.string.loop_on else R.string.loop_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ═══════════════════════ Feature: Mute ════════════════════════════════

    private fun toggleMute() {
        val nowMuted = if (isUsingVlcFallback) {
            vlcManager?.toggleMute() ?: false
        } else {
            playerManager.toggleMute()
        }
        isMuted = nowMuted
        binding.btnMute.setImageResource(
            if (nowMuted) R.drawable.ic_mute else R.drawable.ic_volume_up
        )
    }

    // ═══════════════════════ Feature: Sleep Timer ════════════════════════

    private fun showSleepTimerPicker() {
        val dialog = BottomSheetDialog(this, R.style.Theme_OfflineVideoPlayer_BottomSheet)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sleep_timer, null)

        val rgSleep = view.findViewById<RadioGroup>(R.id.rgSleepTimer)

        val options = mapOf(
            R.id.optOff to 0,
            R.id.opt5 to 5,
            R.id.opt10 to 10,
            R.id.opt15 to 15,
            R.id.opt30 to 30,
            R.id.opt60 to 60
        )

        rgSleep.setOnCheckedChangeListener { _, checkedId ->
            val minutes = options[checkedId] ?: return@setOnCheckedChangeListener
            sleepTimer?.cancel()
            sleepTimer = null
            if (minutes > 0) {
                startSleepTimer(minutes)
                Toast.makeText(
                    this,
                    getString(R.string.sleep_timer_set, minutes),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, R.string.sleep_timer_off, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimer = object : CountDownTimer(minutes * 60_000L, 60_000L) {
            override fun onTick(millisUntilFinished: Long) { /* nothing */ }
            override fun onFinish() {
                if (::playerManager.isInitialized) {
                    playerManager.pause()
                    saveCurrentPosition()
                }
            }
        }.start()
    }

    // ═══════════════════════ Feature: Resize / Aspect Ratio ══════════════

    private fun cycleResizeMode() {
        currentResizeIndex = (currentResizeIndex + 1) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[currentResizeIndex]

        val (label, icon) = when (resizeModes[currentResizeIndex]) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> getString(R.string.resize_fit) to R.drawable.ic_aspect_fit
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> getString(R.string.resize_stretch) to R.drawable.ic_aspect_stretch
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> getString(R.string.resize_crop) to R.drawable.ic_aspect_crop
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> getString(R.string.resize_100) to R.drawable.ic_aspect_100
            else -> "Fit to Screen" to R.drawable.ic_aspect_fit
        }
        binding.btnAspectRatio.setImageResource(icon)
        binding.tvInfo.text = label
        showInfoBriefly()
    }

    // ═══════════════════════ Screen Lock ══════════════════════════════════

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        if (isScreenLocked) {
            isControlsVisible = false
            binding.controlsOverlay.visibility = View.GONE
            binding.btnLockStandalone.visibility = View.VISIBLE
            binding.btnLockStandalone.setImageResource(R.drawable.ic_lock)
        } else {
            binding.btnLockStandalone.visibility = View.GONE
            showControls()
        }
        binding.btnLock.setImageResource(
            if (isScreenLocked) R.drawable.ic_lock else R.drawable.ic_unlock
        )
    }

    // ═══════════════════════ Loading Overlay ════════════════════════════

    /** Safety timeout — dismiss overlay even if first frame is slow (max 5s). */
    private val loadingTimeoutRunnable = Runnable {
        if (!isPlayerReady) onFirstFrameRendered()
    }

    /**
     * Show the loading overlay — covers the entire screen with a spinner
     * and the video title. Called on initial open and when switching videos.
     */
    private fun showLoadingOverlay() {
        isPlayerReady = false
        binding.loadingOverlay.alpha = 1f
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingTitle.text = videoTitle
        // Hide controls while loading — they'll be shown after first frame
        binding.controlsOverlay.visibility = View.GONE
        isControlsVisible = false
        handler.removeCallbacks(hideControlsRunnable)
        // Hide buffering spinner — loading overlay already shows a spinner
        binding.progressBar.visibility = View.GONE
        // Safety timeout: never let the user wait more than 5 seconds
        handler.removeCallbacks(loadingTimeoutRunnable)
        handler.postDelayed(loadingTimeoutRunnable, 5_000)
    }

    /**
     * Dismiss the loading overlay with a smooth fade-out transition (300ms).
     */
    private fun dismissLoadingOverlay() {
        if (binding.loadingOverlay.visibility != View.VISIBLE) return
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.loadingOverlay.visibility = View.GONE
                }
            })
            .start()
    }

    /**
     * Called from onRenderedFirstFrame — the actual moment a decoded video
     * frame appears on the surface. This is the optimal time to:
     * 1. Dismiss the loading overlay (user sees video instantly)
     * 2. Show controls briefly so the user knows they exist
     * 3. Enable gesture interaction
     */
    private fun onFirstFrameRendered() {
        if (isPlayerReady) return  // Already handled (e.g. re-seek triggers another render)
        isPlayerReady = true
        handler.removeCallbacks(loadingTimeoutRunnable)
        dismissLoadingOverlay()
        binding.progressBar.visibility = View.GONE
        // Show controls briefly so user sees the UI, then auto-hide
        showControls()
    }

    // ═══════════════════════ Controls Visibility ══════════════════════════

    private fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        isControlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        if (isUserDraggingSeekbar || isSeeking) return  // Don't hide while user is seeking
        isControlsVisible = false
        binding.controlsOverlay.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, Constants.CONTROLS_TIMEOUT_MS)
    }

    // ═══════════════════════ Progress ═════════════════════════════════════

    private fun updateProgress() {
        if (isSeeking || isUserDraggingSeekbar) return  // Don't overwrite seek preview or user drag
        val pos = currentPositionCompat()
        val dur = durationCompat()

        // ── Seek-commit guard ─────────────────────────────────────────────
        // After a committed seek, if the player reports position near 0 while
        // our target was far away, suppress the UI update for up to 3 seconds
        // so the seekbar doesn't jump back to the start.
        if (lastCommitSeekTargetMs > 3000L &&
            SystemClock.uptimeMillis() - lastCommitSeekTimestamp < 3000L &&
            pos < 1000L
        ) {
            return  // skip — don't let seekbar snap to 0
        }
        // Clear the guard after 3 seconds
        if (lastCommitSeekTimestamp > 0L &&
            SystemClock.uptimeMillis() - lastCommitSeekTimestamp >= 3000L
        ) {
            lastCommitSeekTargetMs = -1L
            lastCommitSeekTimestamp = 0L
        }

        // Keep seekBar.max in sync with duration (may arrive late for some formats)
        if (dur > 0 && binding.seekBar.max != dur.toInt()) {
            binding.seekBar.max = dur.toInt()
        }
        if (dur > 0) {
            binding.seekBar.progress = pos.coerceIn(0L, dur).toInt()
        }
        binding.tvPosition.text = TimeFormatter.format(pos)
        updateRemainingTime(pos)
    }

    private fun updateRemainingTime(currentPos: Long) {
        val dur = durationCompat()
        if (dur > 0) {
            val remaining = dur - currentPos
            binding.tvRemaining.text = "-${TimeFormatter.format(remaining)}"
        } else {
            binding.tvRemaining.text = ""
        }
    }

    // ═══════════════════════ Info ═════════════════════════════════════════

    private fun showInfoBriefly() {
        binding.tvInfo.visibility = View.VISIBLE
        handler.postDelayed({ binding.tvInfo.visibility = View.GONE }, 1_000L)
    }

    // ═══════════════════════ Auto-rotate ══════════════════════════════════

    private fun autoRotateForVideo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        requestedOrientation = if (videoHeight > videoWidth) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // ═══════════════════════ System UI ════════════════════════════════════

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ═══════════════════════ Save position ════════════════════════════════

    private fun saveCurrentPosition() {
        if (videoId != -1L) {
            viewModel.savePosition(videoId, currentPositionCompat())
        }
    }
}
