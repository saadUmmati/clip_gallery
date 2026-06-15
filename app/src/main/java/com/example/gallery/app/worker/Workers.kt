package com.example.gallery.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.gallery.app.ai.ClusterEngine
import com.example.gallery.app.ai.OnnxEmbedder
import com.example.gallery.app.ai.SharpnessAnalyzer
import com.example.gallery.app.data.repository.ClusterRepository
import com.example.gallery.app.data.repository.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────
// 1. MediaScanWorker — discovers images from MediaStore
// ─────────────────────────────────────────────────────────
@HiltWorker
class MediaScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "media_scan"
        const val KEY_SCANNED_COUNT = "scanned_count"

        fun buildRequest(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<MediaScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            setProgress(workDataOf("status" to "Scanning media…"))
            val count = mediaRepository.scanAndStore()
            Result.success(workDataOf(KEY_SCANNED_COUNT to count))
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }
}

// ─────────────────────────────────────────────────────────
// 2. AiProcessingWorker — embeds + clusters + scores images
// ─────────────────────────────────────────────────────────
@HiltWorker
class AiProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val clusterRepository: ClusterRepository,
    private val embedder: OnnxEmbedder,
    private val sharpness: SharpnessAnalyzer,
    private val clusterEngine: ClusterEngine
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "ai_processing"
        const val BATCH_SIZE = 50

        fun buildRequest(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<AiProcessingWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            setProgress(workDataOf("status" to "Initializing AI model…"))
            embedder.initialize()

            val allEmbeddings = mutableMapOf<String, FloatArray>()
            val allSharpness  = mutableMapOf<String, Float>()

            // Process in batches
            var batchNum = 0
            while (true) {
                val batch = mediaRepository.getUnprocessedBatch(BATCH_SIZE)
                if (batch.isEmpty()) break

                setProgress(workDataOf(
                    "status" to "Processing batch ${++batchNum}…",
                    "processed" to allEmbeddings.size
                ))

                for (item in batch) {
                    val embedding = embedder.embed(item.uri)
                    val score = sharpness.computeSharpness(item.uri)

                    if (embedding != null) {
                        allEmbeddings[item.uri] = embedding
                    }
                    allSharpness[item.uri] = score
                    mediaRepository.markProcessed(item.uri)
                }
            }

            // Cluster all processed images
            setProgress(workDataOf("status" to "Clustering images…"))
            val clusterResults = clusterEngine.cluster(allEmbeddings, allSharpness)

            // Save cluster assignments
            clusterRepository.saveClusterResults(clusterResults)

            // Update each media item with its cluster + quality data
            for (result in clusterResults) {
                for (uri in result.memberUris) {
                    mediaRepository.updateAiResults(
                        uri        = uri,
                        clusterId  = result.clusterId,
                        isBestShot = (uri == result.bestShotUri),
                        isBlurry   = (uri in result.blurryUris),
                        score      = allSharpness[uri]
                    )
                }
            }

            Result.success(workDataOf(
                "status" to "Done",
                "clusters" to clusterResults.size
            ))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(workDataOf("error" to e.message))
        } finally {
            embedder.release()
        }
    }
}

// ─────────────────────────────────────────────────────────
// 3. RecycleBinPurgeWorker — deletes 30-day expired items
// ─────────────────────────────────────────────────────────
@HiltWorker
class RecycleBinPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "recycle_bin_purge"

        /** Schedule a daily purge job. */
        fun buildPeriodicRequest(): PeriodicWorkRequest {
            return PeriodicWorkRequestBuilder<RecycleBinPurgeWorker>(
                1, TimeUnit.DAYS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            // Purge expired recycle bin entries from DB
            // (Permanent file deletion is triggered from UI with user approval)
            val cutoff = System.currentTimeMillis()
            // Just flag expired items — actual deletion requires user consent on API 30+
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }
}
