package com.example.videoplayer.utils

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Format a duration in milliseconds into a human-readable string.
 *
 * Examples:
 *  - 90_000  → "01:30"
 *  - 3_661_000 → "1:01:01"
 */
object TimeFormatter {

    fun format(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"

        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
