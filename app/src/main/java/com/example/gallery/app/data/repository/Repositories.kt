package com.example.gallery.app.data.repository


import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.example.gallery.app.ai.ClusterEngine
import com.example.gallery.app.data.db.dao.ClusterDao
import com.example.gallery.app.data.db.dao.MediaItemDao
import com.example.gallery.app.data.db.dao.RecycleBinDao
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.db.entities.RecycleBinEntity
import com.example.gallery.app.util.MediaScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val scanner: MediaScanner
) {
    fun getAllMedia(): Flow<List<MediaItemEntity>> = mediaItemDao.getAllMedia()

    fun getBlurryImages(): Flow<List<MediaItemEntity>> = mediaItemDao.getBlurryImages()

    fun getMediaByCluster(clusterId: Int): Flow<List<MediaItemEntity>> =
        mediaItemDao.getMediaByCluster(clusterId)

    fun getRecycleBinItems(): Flow<List<MediaItemEntity>> = mediaItemDao.getRecycleBinItems()

    fun getTotalCount() = mediaItemDao.getTotalCount()

    fun getTotalSize() = mediaItemDao.getTotalSize()

    suspend fun getReclaimableSize() = mediaItemDao.getReclaimableSize()

    suspend fun scanAndStore(): Int {
        val scanned = scanner.scanAllImages()
        mediaItemDao.insertAll(scanned)
        return scanned.size
    }

    suspend fun getUnprocessedBatch(size: Int = 50) =
        mediaItemDao.getUnprocessedBatch(size)

    suspend fun updateAiResults(
        uri: String,
        clusterId: Int?,
        isBestShot: Boolean,
        isBlurry: Boolean,
        score: Float?
    ) = mediaItemDao.updateAiResults(uri, clusterId, isBestShot, isBlurry, score)

    suspend fun markProcessed(uri: String) = mediaItemDao.markProcessed(uri)
}

@Singleton
class ClusterRepository @Inject constructor(
    private val clusterDao: ClusterDao
) {
    fun getAllClusters(): Flow<List<ClusterEntity>> = clusterDao.getAllClusters()

    fun getClusterCount() = clusterDao.getClusterCount()

    suspend fun getById(id: Int) = clusterDao.getById(id)

    suspend fun saveClusterResults(results: List<ClusterEngine.ClusterResult>) {
        clusterDao.clearAll()
        val entities = results.map { r ->
            ClusterEntity(
                id           = r.clusterId,
                label        = "Cluster ${r.clusterId + 1}",
                bestShotUri  = r.bestShotUri,
                memberCount  = r.memberUris.size,
                blurryCount  = r.blurryUris.size
            )
        }
        clusterDao.insertAll(entities)
    }
}

@Singleton
class DeletionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaItemDao: MediaItemDao,
    private val recycleBinDao: RecycleBinDao
) {

    fun getRecycleBinCount() = recycleBinDao.getCount()

    fun getRecycleBinSize() = recycleBinDao.getTotalSize()

    /**
     * Moves selected images to the recycle bin (logical deletion).
     * Actual file deletion happens via requestPermanentDeletion after 30 days.
     */
    suspend fun moveToRecycleBin(items: List<MediaItemEntity>) {
        val now = System.currentTimeMillis()
        mediaItemDao.moveToRecycleBin(items.map { it.uri }, now)
        val binEntries = items.map { item ->
            RecycleBinEntity(
                uri          = item.uri,
                originalPath = item.filePath,
                fileName     = item.fileName,
                movedAt      = now
            )
        }
        recycleBinDao.insertAll(binEntries)
    }

    /**
     * Restores images from recycle bin (undo action).
     */
    suspend fun restoreFromRecycleBin(uris: List<String>) {
        mediaItemDao.restoreFromRecycleBin(uris)
        recycleBinDao.deleteByUris(uris)
    }

    /**
     * Permanently deletes images from device via Scoped Storage.
     * On Android 10+ uses MediaStore.createDeleteRequest (requires user approval).
     * On older Android, performs direct file deletion.
     *
     * @param launcher ActivityResultLauncher registered in the calling Activity
     */
    suspend fun requestPermanentDeletion(
        uris: List<String>,
        launcher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        if (uris.isEmpty()) return
        val contentUris = uris.map { Uri.parse(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                contentUris
            )
            launcher?.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else {
            // API < 30: delete directly via contentResolver
            contentUris.forEach { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mediaItemDao.deleteByUris(uris)
            recycleBinDao.deleteByUris(uris)
        }
    }

    /**
     * Purges items that have been in recycle bin for 30+ days.
     * Called by RecycleBinPurgeWorker.
     */
    suspend fun purgeExpiredItems(
        launcher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        val expired = recycleBinDao.getExpired()
        if (expired.isNotEmpty()) {
            requestPermanentDeletion(expired.map { it.uri }, launcher)
        }
        recycleBinDao.purgeExpired()
        mediaItemDao.purgeExpiredRecycleBin(System.currentTimeMillis())
    }

    /**
     * After Scoped Storage delete is confirmed by user, clean up DB entries.
     */
    suspend fun confirmDeletion(uris: List<String>) {
        mediaItemDao.deleteByUris(uris)
        recycleBinDao.deleteByUris(uris)
    }
}
