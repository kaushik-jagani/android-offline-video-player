package com.example.videoplayer.ui.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.videoplayer.R
import com.example.videoplayer.data.model.PlaybackSpeed
import com.example.videoplayer.databinding.ActivityPlayerBinding
import com.example.videoplayer.player.ExoPlayerManager
import com.example.videoplayer.utils.Constants
import com.example.videoplayer.utils.TimeFormatter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Modern fullscreen video player with:
 * - Previous / Next navigation, gesture controls
 * - Toolbar: A-B repeat, Equalizer, Speed, Screenshot, Rotation
 * - Expanded panel: Night mode, Loop, Mute, Sleep timer
 * - Bottom: Lock, Aspect ratio toggle, Fit/Fill toggle
 * - Remaining time display, HW/SW decoder label
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var playerManager: ExoPlayerManager
    private lateinit var gestureHandler: PlayerGestureHandler

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
            if (::playerManager.isInitialized && playerManager.isPlaying) {
                updateProgress()
                checkAbRepeat()
                handler.postDelayed(this, 500L)
            }
        }
    }

    // ═══════════════════════════ Lifecycle ════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractIntentExtras()
        if (videoPath.isEmpty()) { finish(); return }

        hideSystemUi()
        setupPlayer()
        setupControls()
        setupToolbar()
        setupGestures()
    }

    override fun onPause() {
        super.onPause()
        if (::playerManager.isInitialized) {
            playerManager.pause()
            saveCurrentPosition()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sleepTimer?.cancel()
        if (::playerManager.isInitialized) {
            playerManager.release()
        }
        super.onDestroy()
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

        binding.tvTitle.text = videoTitle

        playerManager.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                if (isPlaying) {
                    handler.post(updateProgressRunnable)
                    scheduleHideControls()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        binding.seekBar.max = playerManager.duration.toInt()
                        binding.progressBar.visibility = View.GONE
                        if (!isUserDraggingSeekbar && !isSeeking) updateProgress()
                    }
                    Player.STATE_BUFFERING -> {
                        // Don't show spinner during seek/drag — frame will appear shortly
                        if (!isSeeking && !isUserDraggingSeekbar) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Video fully watched – reset position to 0 so next open starts fresh
                        if (videoId != -1L) viewModel.savePosition(videoId, 0L)
                    }
                    else -> { /* idle */ }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                autoRotateForVideo(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@PlayerActivity,
                    "Cannot play this video: ${error.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        playerManager.prepare(videoPath, startPosition)
        updatePrevNextButtons()
    }

    // ═══════════════════════ Center Controls ══════════════════════════════

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener { playerManager.togglePlayPause() }

        binding.btnPrevious.setOnClickListener { playPreviousVideo() }
        binding.btnNext.setOnClickListener { playNextVideo() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvPosition.text = TimeFormatter.format(progress.toLong())
                    updateRemainingTime(progress.toLong())
                    showSeekbarPreview(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserDraggingSeekbar = true
                // Pause during seekbar drag so only the static frame is shown
                wasPlayingBeforeSeek = playerManager.isPlaying
                if (wasPlayingBeforeSeek) playerManager.pause()
                // Use keyframe-only seeking for instant frame display
                playerManager.setFastSeekMode(true)
                // Hide spinner during seekbar drag
                binding.progressBar.visibility = View.GONE
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Restore precise seeking before final seek
                playerManager.setFastSeekMode(false)
                sb?.let {
                    val seekPosition = it.progress.toLong()
                    playerManager.seekTo(seekPosition)
                }
                // Resume playback if it was playing before the drag
                if (wasPlayingBeforeSeek) playerManager.play()
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

        // Screenshot
        binding.btnScreenshot.setOnClickListener { takeScreenshot() }

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

        // HW/SW toggle label
        binding.btnHwToggle.setOnClickListener {
            val current = binding.btnHwToggle.text.toString()
            if (current == getString(R.string.hw_label)) {
                binding.btnHwToggle.text = getString(R.string.sw_label)
            } else {
                binding.btnHwToggle.text = getString(R.string.hw_label)
            }
        }
    }

    // ═══════════════════════ Gestures ═════════════════════════════════════

    private fun setupGestures() {
        gestureHandler = PlayerGestureHandler(
            context = this,
            window = window,
            view = binding.gestureOverlay,
            onToggleControls = { if (!isScreenLocked) toggleControls() },
            onDoubleTap = { if (!isScreenLocked) playerManager.togglePlayPause() },
            onBrightnessChanged = { brightness ->
                if (!isScreenLocked) showBrightnessIndicator(brightness)
            },
            onVolumeChanged = { volume, maxVol ->
                if (!isScreenLocked) showVolumeIndicator(volume, maxVol)
            },
            onSeekPreview = { deltaMs ->
                if (!isScreenLocked) showSeekPreview(deltaMs)
            },
            onSeekCommit = { deltaMs ->
                if (!isScreenLocked) commitSeek(deltaMs)
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
        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = playerManager.currentPosition
            // Pause during seek so only the static frame is shown (no playback)
            wasPlayingBeforeSeek = playerManager.isPlaying
            if (wasPlayingBeforeSeek) playerManager.pause()
            // Use keyframe-only seeking for instant frame display
            playerManager.setFastSeekMode(true)
            // Hide spinner during seek gesture
            binding.progressBar.visibility = View.GONE
        }
        val dur = playerManager.duration
        val targetPos = (seekStartPosition + deltaMs).coerceIn(0L, dur)

        binding.tvInfo.visibility = View.GONE
        binding.seekPreview.visibility = View.VISIBLE
        binding.tvSeekTarget.text = TimeFormatter.format(targetPos)

        val sign = if (deltaMs >= 0) "+" else "-"
        binding.tvSeekDelta.text = "$sign${TimeFormatter.format(abs(deltaMs))}"

        // Seek ExoPlayer so PlayerView renders the frame at this position (paused)
        playerManager.seekTo(targetPos)

        // Update seekbar & time labels
        binding.seekBar.progress = targetPos.toInt()
        binding.tvPosition.text = TimeFormatter.format(targetPos)
        updateRemainingTime(targetPos)
    }

    /** Show seek preview for seekbar drag (absolute position, no delta). */
    private fun showSeekbarPreview(positionMs: Long) {
        val dur = playerManager.duration
        val targetPos = positionMs.coerceIn(0L, dur)

        binding.seekPreview.visibility = View.VISIBLE
        binding.tvSeekTarget.text = TimeFormatter.format(targetPos)
        binding.tvSeekDelta.visibility = View.GONE

        // Seek ExoPlayer in real-time so PlayerView shows the actual frame
        playerManager.seekTo(targetPos)
    }

    private fun commitSeek(deltaMs: Long) {
        // Restore precise seeking before resuming playback
        playerManager.setFastSeekMode(false)
        // Player is already at the correct position from real-time seeking
        // Resume playback if it was playing before the seek gesture
        if (wasPlayingBeforeSeek) playerManager.play()
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
        videoPath = playlistPaths[index]
        videoTitle = playlistTitles[index]
        videoId = if (index < playlistIds.size) playlistIds[index] else -1L

        binding.tvTitle.text = videoTitle
        clearAbRepeat()
        updatePrevNextButtons()

        // Resume from saved position (0 if fully watched or never opened)
        if (videoId != -1L) {
            viewModel.getLastPosition(videoId) { savedPos ->
                runOnUiThread { playerManager.prepare(videoPath, savedPos) }
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
        val currentIdx = speedValues.indexOfFirst {
            abs(it - playerManager.currentSpeed) < 0.01f
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
                playerManager.setSpeed(speedValues[idx])
                // Update toolbar button label
                val shortLabel = if (speedValues[idx] == 1.0f) "1X"
                    else "${speedValues[idx]}x".removeSuffix(".0x")
                        .uppercase().replace("X", "X")
                binding.btnSpeed.text = if (speedValues[idx] == 1.0f) "1X"
                    else "${speedValues[idx]}X"
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // ═══════════════════════ Feature: A-B Repeat ═════════════════════════

    private fun handleAbRepeat() {
        when {
            abPointA < 0 -> {
                abPointA = playerManager.currentPosition
                binding.btnAbRepeat.alpha = 0.6f
                Toast.makeText(this, R.string.ab_point_a_set, Toast.LENGTH_SHORT).show()
            }
            abPointB < 0 -> {
                abPointB = playerManager.currentPosition
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
            if (playerManager.currentPosition >= abPointB) {
                playerManager.seekTo(abPointA)
            }
        }
    }

    // ═══════════════════════ Feature: Screenshot ═════════════════════════

    private fun takeScreenshot() {
        try {
            val textureView = binding.playerView.videoSurfaceView
            if (textureView is android.view.TextureView) {
                val bitmap = textureView.bitmap
                if (bitmap != null) {
                    saveBitmapToGallery(bitmap)
                    Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
                    return
                }
            }
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "VideoPlayer"
        )
        dir.mkdirs()
        val file = File(dir, "screenshot_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
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
        // Apply warm orange overlay to reduce eye strain at night
        val nightOverlay = binding.root.findViewWithTag<View>("nightOverlay")
        if (isNightMode) {
            if (nightOverlay == null) {
                val overlay = View(this).apply {
                    tag = "nightOverlay"
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.parseColor("#44FF8F00"))
                    isClickable = false
                    isFocusable = false
                    elevation = 100f
                }
                binding.root.addView(overlay, 1) // Behind controls, above player
            }
            Toast.makeText(this, R.string.night_mode_on, Toast.LENGTH_SHORT).show()
        } else {
            nightOverlay?.let { binding.root.removeView(it) }
            Toast.makeText(this, R.string.night_mode_off, Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════ Feature: Loop ════════════════════════════════

    private fun toggleLoop() {
        val nowLooping = playerManager.toggleLoop()
        binding.btnLoop.alpha = if (nowLooping) 1f else 0.5f
        Toast.makeText(
            this,
            if (nowLooping) R.string.loop_on else R.string.loop_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ═══════════════════════ Feature: Mute ════════════════════════════════

    private fun toggleMute() {
        val nowMuted = playerManager.toggleMute()
        isMuted = nowMuted
        binding.btnMute.setImageResource(
            if (nowMuted) R.drawable.ic_mute else R.drawable.ic_volume_up
        )
    }

    // ═══════════════════════ Feature: Sleep Timer ════════════════════════

    private fun showSleepTimerPicker() {
        val dialog = BottomSheetDialog(this, R.style.Theme_OfflineVideoPlayer_BottomSheet)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sleep_timer, null)

        val options = mapOf(
            R.id.optOff to 0,
            R.id.opt5 to 5,
            R.id.opt10 to 10,
            R.id.opt15 to 15,
            R.id.opt30 to 30,
            R.id.opt60 to 60
        )

        val clickListener = View.OnClickListener { v ->
            val minutes = options[v.id] ?: return@OnClickListener
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

        options.keys.forEach { id ->
            view.findViewById<TextView>(id).setOnClickListener(clickListener)
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
        val pos = playerManager.currentPosition
        val dur = playerManager.duration
        binding.seekBar.progress = pos.toInt()
        binding.tvPosition.text = TimeFormatter.format(pos)
        updateRemainingTime(pos)
    }

    private fun updateRemainingTime(currentPos: Long) {
        val dur = playerManager.duration
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
        if (videoId != -1L && ::playerManager.isInitialized) {
            viewModel.savePosition(videoId, playerManager.currentPosition)
        }
    }
}
