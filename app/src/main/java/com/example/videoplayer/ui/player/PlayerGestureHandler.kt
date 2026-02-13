package com.example.videoplayer.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import com.example.videoplayer.utils.BrightnessHelper
import kotlin.math.abs

/**
 * Handles swipe gestures on the player surface:
 * - Left-side vertical swipe  → Brightness (vertical bar indicator)
 * - Right-side vertical swipe → Volume (vertical bar indicator)
 * - Horizontal swipe          → Seek preview (scrub committed on finger-up)
 * - Single tap                → Toggle controls visibility
 * - Double tap                → Play / Pause
 */
class PlayerGestureHandler(
    context: Context,
    private val window: Window,
    private val view: View,
    private val onToggleControls: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onBrightnessChanged: (Float) -> Unit,
    private val onVolumeChanged: (Int, Int) -> Unit,
    private val onSeekPreview: (Long) -> Unit,
    private val onSeekCommit: (Long) -> Unit,
    private val onGestureEnd: () -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // Gesture tracking state
    private var isVerticalGesture = false
    private var isHorizontalGesture = false
    private var accumulatedSeekDelta = 0L
    private var volumeAccumulator = 0f

    private val gestureDetector = GestureDetector(context, GestureListener())

    /** Attach this handler to the player view's touch events. */
    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isHorizontalGesture && accumulatedSeekDelta != 0L) {
                        onSeekCommit(accumulatedSeekDelta)
                    }
                    if (isVerticalGesture || isHorizontalGesture) {
                        onGestureEnd()
                    }
                    resetGestureState()
                }
            }
            true
        }
    }

    private fun resetGestureState() {
        isVerticalGesture = false
        isHorizontalGesture = false
        accumulatedSeekDelta = 0L
        volumeAccumulator = 0f
    }

    // ── Gesture Listener ─────────────────────────────────────────────────

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onToggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 == null) return false

            // Lock gesture direction on first significant movement
            if (!isVerticalGesture && !isHorizontalGesture) {
                if (abs(distanceY) > abs(distanceX) && abs(distanceY) > 2f) {
                    isVerticalGesture = true
                } else if (abs(distanceX) > abs(distanceY) && abs(distanceX) > 2f) {
                    isHorizontalGesture = true
                } else {
                    return false
                }
            }

            if (isVerticalGesture) {
                val isLeftSide = e1.x < view.width / 2

                if (isLeftSide) {
                    // Brightness: direct float mapping (0–1)
                    val current = BrightnessHelper.getScreenBrightness(window)
                    val change = distanceY / view.height * 1.5f
                    val newBrightness = (current + change).coerceIn(0.01f, 1f)
                    BrightnessHelper.setScreenBrightness(window, newBrightness)
                    onBrightnessChanged(newBrightness)
                } else {
                    // Volume: float accumulator for smooth stepping
                    volumeAccumulator += distanceY / view.height * maxVolume * 1.8f
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (abs(volumeAccumulator) >= 1f) {
                        val steps = volumeAccumulator.toInt()
                        val newVolume = (currentVolume + steps).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                        volumeAccumulator -= steps
                        onVolumeChanged(newVolume, maxVolume)
                    } else {
                        // Show current level while accumulating sub-step movement
                        onVolumeChanged(currentVolume, maxVolume)
                    }
                }
            } else if (isHorizontalGesture) {
                // Accumulate seek delta — preview only, no actual seek until finger-up
                accumulatedSeekDelta += (-distanceX * 80).toLong()
                onSeekPreview(accumulatedSeekDelta)
            }

            return true
        }
    }
}
