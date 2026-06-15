package com.example.gallery.app.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.gallery.app.data.db.entities.MediaItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_FILE_SIZE_BYTES = 15_360L   // 15 KB — skips system icons/templates
private const val MIN_DIMENSION_PX = 100           // Skips tiny thumbnails

@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Queries MediaStore for all user images on the device.
     * Handles both legacy READ_EXTERNAL_STORAGE (API ≤ 32)
     * and modern READ_MEDIA_IMAGES (API 33+) storage models.
     */
    suspend fun scanAllImages(): List<MediaItemEntity> {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )

        val selection = buildString {
            append("${MediaStore.Images.Media.SIZE} >= $MIN_FILE_SIZE_BYTES")
            append(" AND ${MediaStore.Images.Media.WIDTH} >= $MIN_DIMENSION_PX")
            append(" AND ${MediaStore.Images.Media.HEIGHT} >= $MIN_DIMENSION_PX")
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val results = mutableListOf<MediaItemEntity>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthCol    = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id       = cursor.getLong(idCol)
                val uri      = Uri.withAppendedPath(collection, id.toString()).toString()
                val name     = cursor.getString(nameCol) ?: "unknown"
                val path     = cursor.getString(pathCol) ?: ""
                val date     = cursor.getLong(dateCol) * 1000L // to millis
                val size     = cursor.getLong(sizeCol)
                val width    = cursor.getInt(widthCol)
                val height   = cursor.getInt(heightCol)
                val mime     = cursor.getString(mimeCol) ?: "image/jpeg"

                // Secondary filter: skip corrupted entries with zero dimensions
                if (width > 0 && height > 0 && size >= MIN_FILE_SIZE_BYTES) {
                    results.add(
                        MediaItemEntity(
                            uri        = uri,
                            filePath   = path,
                            fileName   = name,
                            dateAdded  = date,
                            sizeBytes  = size,
                            width      = width,
                            height     = height,
                            mimeType   = mime
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Returns only newly discovered images (not already in DB).
     * Used for incremental re-scans.
     */
    suspend fun scanNewImages(existingUris: Set<String>): List<MediaItemEntity> {
        return scanAllImages().filter { it.uri !in existingUris }
    }
}
