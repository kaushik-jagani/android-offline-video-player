package com.example.videoplayer.utils

import android.provider.Settings
import android.view.Window

/**
 * Helpers for gesture-based brightness control in the player.
 */
object BrightnessHelper {

    /**
     * Get current screen brightness (0f–1f).
     */
    fun getScreenBrightness(window: Window): Float {
        val lp = window.attributes
        return if (lp.screenBrightness < 0) {
            // System default – read actual system brightness (0-255 → 0f-1f)
            try {
                val sysBrightness = Settings.System.getInt(
                    window.context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
                sysBrightness / 255f
            } catch (_: Exception) {
                0.5f
            }
        } else {
            lp.screenBrightness
        }
    }

    /**
     * Set screen brightness for the player window (0f–1f).
     */
    fun setScreenBrightness(window: Window, brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness.coerceIn(0.01f, 1f)
        window.attributes = lp
    }
}
