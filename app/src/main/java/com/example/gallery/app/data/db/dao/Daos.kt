package com.example.gallery.app.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.db.entities.RecycleBinEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Query("SELECT * FROM media_items WHERE isInRecycleBin = 0 ORDER BY dateAdded DESC")
    fun getAllMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE clusterId = :clusterId AND isInRecycleBin = 0")
    fun getMediaByCluster(clusterId: Int): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isBlurry = 1 AND isInRecycleBin = 0")
    fun getBlurryImages(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE isInRecycleBin = 1 ORDER BY recycleBinDate DESC")
    fun getRecycleBinItems(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_items WHERE isInRecycleBin = 0")
    fun getTotalCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE isBlurry = 1 AND isInRecycleBin = 0")
    suspend fun getBlurryCount(): Int

    @Query("SELECT SUM(sizeBytes) FROM media_items WHERE isInRecycleBin = 0")
    fun getTotalSize(): LiveData<Long>

    @Query("SELECT SUM(sizeBytes) FROM media_items WHERE (isBlurry = 1 OR (clusterId IS NOT NULL AND isBestShot = 0)) AND isInRecycleBin = 0")
    suspend fun getReclaimableSize(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(item: MediaItemEntity): Long

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("UPDATE media_items SET clusterId = :clusterId, isBestShot = :isBestShot, isBlurry = :isBlurry, sharpnessScore = :score WHERE uri = :uri")
    suspend fun updateAiResults(uri: String, clusterId: Int?, isBestShot: Boolean, isBlurry: Boolean, score: Float?)

    @Query("UPDATE media_items SET embeddingProcessed = 1 WHERE uri = :uri")
    suspend fun markProcessed(uri: String)

    @Query("UPDATE media_items SET isInRecycleBin = 1, recycleBinDate = :timestamp WHERE uri IN (:uris)")
    suspend fun moveToRecycleBin(uris: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE media_items SET isInRecycleBin = 0, recycleBinDate = NULL WHERE uri IN (:uris)")
    suspend fun restoreFromRecycleBin(uris: List<String>)

    @Query("DELETE FROM media_items WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM media_items WHERE isInRecycleBin = 1 AND recycleBinDate < :cutoff")
    suspend fun purgeExpiredRecycleBin(cutoff: Long)

    @Query("SELECT * FROM media_items WHERE embeddingProcessed = 0 AND isInRecycleBin = 0 LIMIT :batchSize")
    suspend fun getUnprocessedBatch(batchSize: Int = 50): List<MediaItemEntity>
}

@Dao
interface ClusterDao {

    @Query("SELECT * FROM clusters ORDER BY memberCount DESC")
    fun getAllClusters(): Flow<List<ClusterEntity>>

    @Query("SELECT * FROM clusters WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ClusterEntity?

    @Query("SELECT COUNT(*) FROM clusters")
    fun getClusterCount(): LiveData<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clusters: List<ClusterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cluster: ClusterEntity): Long

    @Update
    suspend fun update(cluster: ClusterEntity)

    @Query("DELETE FROM clusters")
    suspend fun clearAll()

    @Query("UPDATE clusters SET memberCount = :count, blurryCount = :blurryCount WHERE id = :id")
    suspend fun updateCounts(id: Int, count: Int, blurryCount: Int)
}

@Dao
interface RecycleBinDao {

    @Query("SELECT * FROM recycle_bin ORDER BY movedAt DESC")
    fun getAll(): Flow<List<RecycleBinEntity>>

    @Query("SELECT * FROM recycle_bin WHERE purgeAt < :now")
    suspend fun getExpired(now: Long = System.currentTimeMillis()): List<RecycleBinEntity>

    @Query("SELECT COUNT(*) FROM recycle_bin")
    fun getCount(): LiveData<Int>

    @Query("SELECT SUM(m.sizeBytes) FROM recycle_bin r JOIN media_items m ON r.uri = m.uri")
    fun getTotalSize(): LiveData<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecycleBinEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecycleBinEntity)

    @Delete
    suspend fun delete(item: RecycleBinEntity)

    @Query("DELETE FROM recycle_bin WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM recycle_bin WHERE purgeAt < :now")
    suspend fun purgeExpired(now: Long = System.currentTimeMillis())
}
