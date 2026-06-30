package com.example.gallery.app.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.gallery.app.data.db.entities.MediaItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaScanner"
private const val MIN_FILE_SIZE_BYTES = 15_360L    // 15 KB - filter sub-15KB system assets
private const val MIN_DIMENSION_PX = 10            // 10px - much more lenient

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
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        // Remove dimensions from selection to be more compatible with different devices/versions
        val selection = "${MediaStore.Images.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(MIN_FILE_SIZE_BYTES.toString())

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val results = mutableListOf<MediaItemEntity>()
        Log.d(TAG, "Starting media scan... collection: $collection")

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                Log.d(TAG, "Cursor count: ${cursor.count}")
                val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthCol    = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id       = cursor.getLong(idCol)
                val uri      = Uri.withAppendedPath(collection, id.toString()).toString()
                val name     = cursor.getString(nameCol) ?: "unknown"
                val date     = cursor.getLong(dateCol) * 1000L // to millis
                val size     = cursor.getLong(sizeCol)
                val mime     = cursor.getString(mimeCol) ?: "image/jpeg"
                val width    = cursor.getInt(widthCol)
                val height   = cursor.getInt(heightCol)

                results.add(
                    MediaItemEntity(
                        uri        = uri,
                        filePath   = uri,
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

        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed", e)
        }

        Log.d(TAG, "Scan complete. Found ${results.size} valid images.")
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
