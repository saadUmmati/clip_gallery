package com.example.gallery.app.data.repository


import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.example.gallery.app.R
import com.example.gallery.app.ai.ClusterEngine
import com.example.gallery.app.data.db.dao.ClusterDao
import com.example.gallery.app.data.db.dao.MediaItemDao
import com.example.gallery.app.data.db.dao.RecycleBinDao
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.FolderInfo
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.db.entities.RecycleBinEntity
import com.example.gallery.app.data.db.entities.UriFolder
import com.example.gallery.app.util.MediaScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val scanner: MediaScanner
) {
    fun getAllMedia(): Flow<List<MediaItemEntity>> = mediaItemDao.getAllMedia()

    /**
     * Returns a paging flow for the main gallery grid.
     * Room automatically invalidates the PagingSource on database writes.
     */
    fun getAllMediaPaged(): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = true,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.getAllMediaPaging() }
    ).flow

    /**
     * Returns a paging flow for search results.
     */
    fun searchMediaPaged(query: String): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = false,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.searchMediaPaging(query) }
    ).flow

    fun getBlurryImages(): Flow<List<MediaItemEntity>> = mediaItemDao.getBlurryImages()

    fun getMediaByCluster(clusterId: Int): Flow<List<MediaItemEntity>> =
        mediaItemDao.getMediaByCluster(clusterId)

    fun getRecycleBinItems(): Flow<List<MediaItemEntity>> = mediaItemDao.getRecycleBinItems()

    fun getAllVaultItems(): Flow<List<MediaItemEntity>> = mediaItemDao.getAllVaultItems()

    // Folder/Album methods
    fun getAllFolders(): Flow<List<FolderInfo>> = mediaItemDao.getAllFolders()

    fun getMediaByFolder(folder: String): Flow<List<MediaItemEntity>> =
        mediaItemDao.getMediaByFolder(folder)

    fun getMediaByFolderPaged(folder: String): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = true,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.getMediaByFolderPaging(folder) }
    ).flow

    fun searchMediaByFolderPaged(query: String, folder: String): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = false,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.searchMediaByFolderPaging(query, folder) }
    ).flow

    fun getFolderCount(folder: String) = mediaItemDao.getFolderCount(folder)

    /**
     * Returns embeddings grouped by folder for per-folder clustering.
     */
    suspend fun getEmbeddingsByFolder(): Map<String, Map<String, FloatArray>> {
        val folders = mediaItemDao.getFoldersWithEmbeddings()
        val result = mutableMapOf<String, Map<String, FloatArray>>()
        for (folder in folders) {
            val pairs = mediaItemDao.getEmbeddingsByFolder(folder)
            result[folder] = pairs.mapNotNull { pair ->
                val bytes = pair.embedding ?: return@mapNotNull null
                val floats = java.nio.ByteBuffer.wrap(bytes).apply {
                    order(java.nio.ByteOrder.LITTLE_ENDIAN)
                }.let { buf -> FloatArray(bytes.size / 4) { buf.getFloat(it) } }
                pair.uri to floats
            }.toMap()
        }
        return result
    }

    suspend fun getByUri(uri: String): MediaItemEntity? = mediaItemDao.getByUri(uri)

    /**
     * Batch-load folder info for a list of URIs. Returns map of uri → folder.
     */
    suspend fun getFoldersForUris(uris: List<String>): Map<String, String> {
        if (uris.isEmpty()) return emptyMap()
        val results = mediaItemDao.getFoldersForUris(uris)
        return results.associate { it.uri to it.folder }
    }

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

    suspend fun storeEmbedding(uri: String, embedding: FloatArray?) {
        val bytes = embedding?.let { arr ->
            java.nio.ByteBuffer.allocate(arr.size * 4).apply {
                order(java.nio.ByteOrder.LITTLE_ENDIAN)
                arr.forEach { putFloat(it) }
            }.array()
        }
        mediaItemDao.storeEmbedding(uri, bytes)
    }

    suspend fun getEmbedding(uri: String): FloatArray? {
        val bytes = mediaItemDao.getEmbedding(uri) ?: return null
        return java.nio.ByteBuffer.wrap(bytes).apply {
            order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }.let { buf ->
            FloatArray(bytes.size / 4) { buf.getFloat(it) }
        }
    }

    suspend fun purgeExpiredRecycleBin(cutoff: Long) = mediaItemDao.purgeExpiredRecycleBin(cutoff)

    /**
     * Returns all processed embeddings as (uri, embedding) pairs for semantic search.
     */
    suspend fun getAllProcessedEmbeddings(): Map<String, FloatArray> {
        val pairs = mediaItemDao.getAllProcessedEmbeddings()
        return pairs.mapNotNull { pair ->
            val bytes = pair.embedding ?: return@mapNotNull null
            val floats = java.nio.ByteBuffer.wrap(bytes).apply {
                order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }.let { buf -> FloatArray(bytes.size / 4) { buf.getFloat(it) } }
            pair.uri to floats
        }.toMap()
    }

    /**
     * Returns all items with embeddings for timeline construction.
     */
    suspend fun getAllWithEmbeddingsForTimeline(): List<Triple<String, Long, FloatArray?>> {
        val items = mediaItemDao.getAllWithEmbeddingsForTimeline()
        return items.map { item ->
            val embedding = item.embedding?.let { bytes ->
                java.nio.ByteBuffer.wrap(bytes).apply {
                    order(java.nio.ByteOrder.LITTLE_ENDIAN)
                }.let { buf -> FloatArray(bytes.size / 4) { buf.getFloat(it) } }
            }
            Triple(item.uri, item.dateAdded, embedding)
        }
    }
}

@Singleton
class ClusterRepository @Inject constructor(
    private val clusterDao: ClusterDao
) {
    fun getAllClusters(): Flow<List<ClusterEntity>> = clusterDao.getAllClusters()

    fun getClusterCount() = clusterDao.getClusterCount()

    suspend fun getById(id: Int) = clusterDao.getById(id)

    suspend fun getClusterPreviewUris(clusterId: Int, limit: Int = 4): List<String> =
        clusterDao.getClusterPreviewUris(clusterId, limit)

    suspend fun saveClusterResults(results: List<ClusterEngine.ClusterResult>) {
        // Group by folder to generate folder-based labels
        val folderCounts = mutableMapOf<String, Int>()

        val entities = results.map { r ->
            val folder = r.folder
            val count = folderCounts.getOrPut(folder) { 0 } + 1
            folderCounts[folder] = count

            val label = if (results.count { it.folder == folder } == 1) {
                // Single cluster in this folder — just use folder name
                folder
            } else {
                // Multiple clusters in same folder — add number
                "$folder ($count)"
            }

            ClusterEntity(
                id           = r.clusterId,
                label        = label,
                bestShotUri  = r.bestShotUri,
                memberCount  = r.memberUris.size,
                blurryCount  = r.blurryUris.size
            )
        }
        clusterDao.replaceAll(entities)
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
     * On Android 11+ uses MediaStore.createTrashRequest (sends to system trash/bin).
     * On Android 10 uses MediaStore.createDeleteRequest (requires user approval).
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
            // API 30+ (Android 11+): use createDeleteRequest — user approves file deletion
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                contentUris
            )
            launcher?.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29 (Android 10): createTrashRequest not available, use createDeleteRequest
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                contentUris
            )
            launcher?.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else {
            // API < 29: delete directly via contentResolver (blocking I/O)
            withContext(Dispatchers.IO) {
                contentUris.forEach { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            mediaItemDao.deleteByUris(uris)
            recycleBinDao.deleteByUris(uris)
        }
    }

    /**
     * Purges items that have been in recycle bin for 30+ days.
     * Called by RecycleBinPurgeWorker.
     * DB records are cleaned up in confirmDeletion() after user confirms file deletion.
     */
    suspend fun purgeExpiredItems(
        launcher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        val expired = recycleBinDao.getExpired()
        if (expired.isNotEmpty()) {
            requestPermanentDeletion(expired.map { it.uri }, launcher)
        }
    }

    /**
     * After Scoped Storage delete is confirmed by user, clean up DB entries.
     */
    suspend fun confirmDeletion(uris: List<String>) {
        mediaItemDao.deleteByUris(uris)
        recycleBinDao.deleteByUris(uris)
    }
}
