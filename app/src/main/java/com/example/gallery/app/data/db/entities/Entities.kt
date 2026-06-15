package com.example.gallery.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single image discovered on the device.
 * Stores MediaStore URI, file metadata, AI embedding, and quality score.
 */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey
    val uri: String,                    // content:// URI from MediaStore
    val filePath: String,               // Absolute file path
    val fileName: String,
    val dateAdded: Long,                // epoch millis
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,

    // AI-generated fields (null until processed)
    val clusterId: Int? = null,
    val sharpnessScore: Float? = null,  // Laplacian variance score
    val isBestShot: Boolean = false,    // Best image in its cluster
    val isBlurry: Boolean = false,      // Below sharpness threshold
    val embeddingProcessed: Boolean = false,

    // Recycle Bin fields
    val isInRecycleBin: Boolean = false,
    val recycleBinDate: Long? = null    // When moved to bin; purge after 30 days
)

/**
 * Represents a semantic cluster of visually similar images.
 */
@Entity(tableName = "clusters")
data class ClusterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val label: String,                  // Human-readable cluster concept
    val bestShotUri: String?,           // URI of highest sharpness image
    val memberCount: Int,
    val blurryCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tracks deleted items for undo snackbar + 30-day recycle bin purge.
 */
@Entity(tableName = "recycle_bin")
data class RecycleBinEntity(
    @PrimaryKey
    val uri: String,
    val originalPath: String,
    val fileName: String,
    val movedAt: Long = System.currentTimeMillis(),
    val purgeAt: Long = System.currentTimeMillis() + RECYCLE_BIN_TTL_MS
) {
    companion object {
        const val RECYCLE_BIN_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
