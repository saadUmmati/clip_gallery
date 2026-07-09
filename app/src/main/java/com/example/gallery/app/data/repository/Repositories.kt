package com.example.gallery.app.data.repository


import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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

    suspend fun getPreviewUrisForFolder(folder: String, limit: Int = 4): List<String> =
        mediaItemDao.getPreviewUrisForFolder(folder, limit)

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

    fun getMediaByUrisPaged(uris: List<String>): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = true,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.getMediaByUrisPaging(uris) }
    ).flow

    fun getMediaByUrisAndFolderPaged(uris: List<String>, folder: String): Flow<PagingData<MediaItemEntity>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 30,
            enablePlaceholders = true,
            initialLoadSize = 120
        ),
        pagingSourceFactory = { mediaItemDao.getMediaByUrisAndFolderPaging(uris, folder) }
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

    suspend fun getUnprocessedCount() =
        mediaItemDao.getUnprocessedCount()

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
    private val clusterDao: ClusterDao,
    private val mediaItemDao: MediaItemDao
) {
    fun getAllClusters(): Flow<List<ClusterEntity>> = clusterDao.getAllClusters()

    suspend fun refreshClusterCounts() { clusterDao.recalculateAllCounts() }

    fun getClusterCount() = clusterDao.getClusterCount()

    suspend fun getById(id: Int) = clusterDao.getById(id)

    suspend fun getClusterPreviewUris(clusterId: Int, limit: Int = 4): List<String> =
        clusterDao.getClusterPreviewUris(clusterId, limit)

    suspend fun renameCluster(clusterId: Int, newLabel: String) {
        val cluster = clusterDao.getById(clusterId) ?: return
        clusterDao.update(cluster.copy(label = newLabel))
    }

    suspend fun removeFromCluster(uris: List<String>, clusterId: Int) {
        if (uris.isEmpty()) return
        mediaItemDao.removeFromCluster(uris)
        val cluster = clusterDao.getById(clusterId) ?: return
        val newCount = maxOf(0, cluster.memberCount - uris.size)
        if (newCount <= 0) clusterDao.deleteById(clusterId)
        else clusterDao.updateCounts(clusterId, newCount, cluster.blurryCount)
    }

    suspend fun removeEntireCluster(clusterId: Int) {
        mediaItemDao.removeAllFromCluster(clusterId)
        clusterDao.deleteById(clusterId)
    }

    suspend fun cleanupOrphanedClusters() {
        clusterDao.deleteOrphanedClusters()
    }

    /**
     * FIX: saveClusterResults now ALWAYS computes and stores the centroid.
     * Previously centroidEmbedding was always null here, which broke
     * incremental clustering — matchToExistingClusters got zero centroids
     * and treated every image as unmatched on every subsequent run.
     */
    suspend fun saveClusterResults(
        results: List<ClusterEngine.ClusterResult>,
        embeddings: Map<String, FloatArray> = emptyMap(),
        clusterEngine: ClusterEngine? = null
    ) {
        val maxId = clusterDao.getMaxClusterId() ?: 0
        var nextId = maxId + 1

        for (result in results) {
            if (result.memberUris.isEmpty()) continue

            // Compute centroid from member embeddings if available
            val centroidBytes = if (clusterEngine != null && embeddings.isNotEmpty()) {
                val memberEmbeddings = result.memberUris.mapNotNull { embeddings[it] }
                if (memberEmbeddings.isNotEmpty()) {
                    clusterEngine.bytesFromFloatArray(
                        clusterEngine.computeCentroid(memberEmbeddings)
                    )
                } else null
            } else null

            val entity = ClusterEntity(
                id = nextId,
                label = "Cluster $nextId",
                bestShotUri = result.bestShotUri,
                memberCount = result.memberUris.size,
                blurryCount = result.blurryUris.size,
                centroidEmbedding = centroidBytes   // ← FIXED: no longer always null
            )
            clusterDao.insert(entity)
            nextId++
        }
    }

    /**
     * Incremental clustering: match new images to existing clusters by centroid similarity.
     * Unmatched images → grouped into new clusters via ClusterEngine.
     * Existing clusters are never deleted.
     */
    suspend fun saveIncrementalResults(
        newEmbeddings: Map<String, FloatArray>,
        sharpness: Map<String, Float>,
        clusterEngine: ClusterEngine
    ) {
        if (newEmbeddings.isEmpty()) return

        val existingClusters = clusterDao.getAllClustersList()

        // FIX: Log how many clusters have valid centroids for debugging
        val clustersWithCentroids = existingClusters.filter { it.centroidEmbedding != null }
        Log.d("ClusterRepository",
            "Existing clusters: ${existingClusters.size}, " +
                    "with centroids: ${clustersWithCentroids.size}, " +
                    "new images to process: ${newEmbeddings.size}")

        val centroids = clustersWithCentroids
            .map { it.id to clusterEngine.floatArrayFromBytes(it.centroidEmbedding!!) }

        val matchResult = if (centroids.isEmpty()) {
            // No existing centroids — all images are unmatched, build fresh clusters
            Log.d("ClusterRepository", "No existing centroids — building fresh clusters")
            ClusterEngine.MatchResult(
                assigned = emptyMap(),
                unmatched = newEmbeddings.toMutableMap()
            )
        } else {
            clusterEngine.matchToExistingClusters(newEmbeddings, centroids)
        }

        Log.d("ClusterRepository",
            "Matched: ${matchResult.assigned.values.sumOf { it.size }}, " +
                    "Unmatched: ${matchResult.unmatched.size}")

        // ── Step 1: Assign matched images to existing clusters ──
        for ((clusterId, uris) in matchResult.assigned) {
            val cluster = clusterDao.getById(clusterId) ?: continue

            for (uri in uris) {
                val score = sharpness[uri]
                val isBlurry = score != null && score < ClusterEngine.BLUR_THRESHOLD
                mediaItemDao.updateAiResults(uri, clusterId, false, isBlurry, score)
            }

            // Update centroid as running average including new members
            val allMemberEmbeddings = mutableListOf<FloatArray>()
            val existingCentroid = cluster.centroidEmbedding
                ?.let { clusterEngine.floatArrayFromBytes(it) }
            if (existingCentroid != null) allMemberEmbeddings.add(existingCentroid)
            uris.mapNotNull { newEmbeddings[it] }.forEach { allMemberEmbeddings.add(it) }

            val newCentroid = clusterEngine.computeCentroid(allMemberEmbeddings)
            val newBlurryCount = cluster.blurryCount + uris.count { uri ->
                val s = sharpness[uri]; s != null && s < ClusterEngine.BLUR_THRESHOLD
            }

            clusterDao.update(cluster.copy(
                memberCount = cluster.memberCount + uris.size,
                blurryCount = newBlurryCount,
                centroidEmbedding = clusterEngine.bytesFromFloatArray(newCentroid)
            ))
        }

        // ── Step 2: Cluster unmatched images into new groups ──
        if (matchResult.unmatched.isNotEmpty()) {
            Log.d("ClusterRepository",
                "Clustering ${matchResult.unmatched.size} unmatched images...")

            val unmatchedSharpness = matchResult.unmatched.keys
                .associateWith { uri -> sharpness[uri] ?: 0f }

            val newClusterResults = clusterEngine.cluster(
                matchResult.unmatched,
                unmatchedSharpness
            )

            val maxId = clusterDao.getMaxClusterId() ?: 0
            var nextId = maxId + 1

            for (result in newClusterResults) {
                if (result.memberUris.isEmpty()) continue

                // Always compute and store centroid for new clusters
                val memberEmbeddings = result.memberUris.mapNotNull { newEmbeddings[it] }
                val centroid = if (memberEmbeddings.isNotEmpty())
                    clusterEngine.computeCentroid(memberEmbeddings) else null

                val entity = ClusterEntity(
                    id = nextId,
                    label = "Cluster $nextId",
                    bestShotUri = result.bestShotUri,
                    memberCount = result.memberUris.size,
                    blurryCount = result.blurryUris.size,
                    centroidEmbedding = centroid
                        ?.let { clusterEngine.bytesFromFloatArray(it) }  // ← FIXED
                )
                clusterDao.insert(entity)

                for (uri in result.memberUris) {
                    val score = sharpness[uri]
                    mediaItemDao.updateAiResults(
                        uri        = uri,
                        clusterId  = nextId,
                        isBestShot = uri == result.bestShotUri,
                        isBlurry   = uri in result.blurryUris,
                        score      = score
                    )
                }
                nextId++
            }
        }
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
                        Log.e("DeletionRepository", "Failed to delete $uri", e)
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

    suspend fun requestTrash(
        uris: List<String>,
        launcher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        if (uris.isEmpty()) return
        val contentUris = uris.map { Uri.parse(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createTrashRequest(
                context.contentResolver,
                contentUris,
                true
            )
            launcher?.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        }
    }

    suspend fun requestUntrash(
        uris: List<String>,
        launcher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        if (uris.isEmpty()) return
        val contentUris = uris.map { Uri.parse(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createTrashRequest(
                context.contentResolver,
                contentUris,
                false
            )
            launcher?.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
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
