package com.example.gallery.app.util

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
fun formatRelativeDate(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L            -> "Just now"
        diff < 3_600_000L         -> "${diff / 60_000} min ago"
        diff < 86_400_000L        -> "${diff / 3_600_000} hr ago"
        diff < 2_592_000_000L     -> "${diff / 86_400_000} days ago"
        else                      -> {
            val date = java.util.Date(ms)
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(date)
        }
    }
}
