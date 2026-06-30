package com.example.gallery.app.util

import android.os.Build
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formats a byte count as a human-readable string (KB, MB, GB).
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }
}

/**
 * Formats a millisecond timestamp as "X days ago" or a short date.
 */
@Suppress("DEPRECATION")
fun formatRelativeDate(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < TimeUnit.MINUTES.toMillis(1)  -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} min ago"
        diff < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diff)} hr ago"
        diff < TimeUnit.DAYS.toMillis(30)    -> "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                Instant.ofEpochMilli(ms)
                    .atZone(ZoneId.systemDefault())
                    .format(formatter)
            } else {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
            }
        }
    }
}
