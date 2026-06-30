package com.example.gallery.app.util

import org.junit.Assert.*
import org.junit.Test

class UtilsTest {

    @Test
    fun `formatFileSize handles bytes`() {
        assertEquals("500 B", formatFileSize(500))
    }

    @Test
    fun `formatFileSize handles kilobytes`() {
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun `formatFileSize handles megabytes`() {
        assertEquals("2.3 MB", formatFileSize(2411725))
    }

    @Test
    fun `formatFileSize handles gigabytes`() {
        assertEquals("1.2 GB", formatFileSize(1288490188))
    }

    @Test
    fun `formatFileSize handles zero`() {
        assertEquals("0 B", formatFileSize(0))
    }

    @Test
    fun `formatRelativeDate handles recent timestamps`() {
        val now = System.currentTimeMillis()
        assertEquals("Just now", formatRelativeDate(now - 30000)) // 30 seconds ago
        assertEquals("5 min ago", formatRelativeDate(now - 300000)) // 5 minutes ago
        assertEquals("2 hr ago", formatRelativeDate(now - 7200000)) // 2 hours ago
        assertEquals("3 days ago", formatRelativeDate(now - 259200000)) // 3 days ago
    }

    @Test
    fun `formatRelativeDate handles old timestamps`() {
        // 60 days ago should show formatted date
        val old = System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000)
        val result = formatRelativeDate(old)
        // Should contain a date pattern like "MMM d, yyyy"
        assertTrue(result.matches(Regex("\\w{3} \\d{1,2}, \\d{4}")))
    }
}
