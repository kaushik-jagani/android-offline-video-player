package com.example.videoplayer.utils

import java.util.Locale

/**
 * Format a file size in bytes to "KB", "MB", or "GB".
 */
object FileSizeFormatter {

    fun format(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576     -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024         -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
            else                   -> "$bytes B"
        }
    }
}
