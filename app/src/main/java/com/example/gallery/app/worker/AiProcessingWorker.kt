package com.example.gallery.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.gallery.app.R
import com.example.gallery.app.ai.ClusterEngine
import com.example.gallery.app.ai.OnnxEmbedder
import com.example.gallery.app.ai.SharpnessAnalyzer
import com.example.gallery.app.data.repository.ClusterRepository
import com.example.gallery.app.data.repository.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
        private const val TAG = "AiProcessingWorker"
        const val WORK_NAME = "ai_processing"
        const val BATCH_SIZE = 50
        const val KEY_URIS = "uris"
        const val CHANNEL_ID = "ai_processing_channel"
        const val NOTIFICATION_ID = 1001

        fun buildRequest(uris: List<String>): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<AiProcessingWorker>()
                .setInputData(workDataOf(KEY_URIS to uris.toTypedArray()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }

        fun buildRequestAll(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<AiProcessingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
    }

    private fun buildNotification(status: String): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle("ClipGallery AI")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI Processing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Processing images with AI"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo("Preparing AI processing…")
    }

    override suspend fun doWork(): Result {
        setForeground(buildForegroundInfo("Initializing AI model…"))

        var onnxAvailable = true

        return try {
            try {
                embedder.initialize()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "ONNX model unavailable, falling back to folder grouping: ${e.message}")
                onnxAvailable = false
            }

            val selectedUris = inputData.getStringArray(KEY_URIS)
            val uris = selectedUris?.takeIf { it.isNotEmpty() }
            val isSelective = uris != null

            val processedEmbeddings = mutableMapOf<String, FloatArray>()
            val processedSharpness  = mutableMapOf<String, Float>()

            if (onnxAvailable) {
                if (isSelective) {
                    setForeground(buildForegroundInfo("Processing ${uris!!.size} selected images…"))
                    setProgress(workDataOf(
                        "status" to "Processing ${uris.size} selected images…",
                        "processed" to 0,
                        "total" to uris.size
                    ))

                    var processedCount = 0
                    for (uri in uris) {
                        if (isStopped) {
                            return Result.success(workDataOf("status" to "Cancelled", "processed" to processedCount))
                        }

                        try {
                            val embedding = embedder.embed(uri)
                            val score = sharpness.computeSharpness(uri)

                            if (embedding != null) {
                                processedEmbeddings[uri] = embedding
                                mediaRepository.storeEmbedding(uri, embedding)
                                mediaRepository.markProcessed(uri)
                            }
                            processedSharpness[uri] = score
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to embed $uri: ${e.message}")
                        }

                        processedCount++
                        if (processedCount % 10 == 0 || processedCount == uris.size) {
                            val status = "Processing $processedCount/${uris.size}…"
                            setProgress(workDataOf("status" to status, "processed" to processedCount, "total" to uris.size))
                        }
                    }
                } else {
                    val totalCount = mediaRepository.getUnprocessedCount()
                    var batchNum = 0
                    setProgress(workDataOf("status" to "Starting…", "processed" to 0, "total" to totalCount))
                    while (true) {
                        if (isStopped) {
                            return Result.success(workDataOf("status" to "Cancelled", "processed" to processedEmbeddings.size))
                        }

                        val batch = mediaRepository.getUnprocessedBatch(BATCH_SIZE)
                        if (batch.isEmpty()) break

                        batchNum++
                        val status = "Processing batch $batchNum…"
                        setForeground(buildForegroundInfo(status))
                        setProgress(workDataOf("status" to status, "processed" to processedEmbeddings.size, "total" to totalCount))

                        for (item in batch) {
                            try {
                                val embedding = embedder.embed(item.uri)
                                val score = sharpness.computeSharpness(item.uri)

                                if (embedding != null) {
                                    processedEmbeddings[item.uri] = embedding
                                    mediaRepository.storeEmbedding(item.uri, embedding)
                                    mediaRepository.markProcessed(item.uri)
                                }
                                processedSharpness[item.uri] = score
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to embed ${item.uri}: ${e.message}")
                            }
                        }
                    }
                }
            }

            // ── Step 2: Incremental clustering ──
            if (onnxAvailable && processedEmbeddings.isNotEmpty()) {
                setForeground(buildForegroundInfo("Matching images to existing clusters…"))
                setProgress(workDataOf("status" to "Matching images to existing clusters…"))

                clusterRepository.saveIncrementalResults(
                    processedEmbeddings, processedSharpness, clusterEngine
                )

                // Recalculate accurate counts from actual media items
                clusterRepository.refreshClusterCounts()

                // Clean up any empty/orphaned clusters
                clusterRepository.cleanupOrphanedClusters()

                val processedCount = processedEmbeddings.size
                Log.i(TAG, "Done: $processedCount images processed incrementally")

                Result.success(workDataOf(
                    "status" to "Done",
                    "clusters" to 0,
                    "processed" to processedCount,
                    "folders" to 0
                ))
            } else {
                // Fallback path: group selected images by folder (no AI needed)
                val urisToGroup = if (isSelective) uris!!.toList() else emptyList()

                if (urisToGroup.isEmpty()) {
                    return Result.success(workDataOf(
                        "status" to "Done",
                        "clusters" to 0,
                        "processed" to 0,
                        "folders" to 0
                    ))
                }

                setForeground(buildForegroundInfo("Grouping ${urisToGroup.size} images by folder…"))

                val uriFolders = mediaRepository.getFoldersForUris(urisToGroup)
                val folderToUris = mutableMapOf<String, MutableList<String>>()
                for (uri in urisToGroup) {
                    val folder = uriFolders[uri] ?: "Unknown"
                    folderToUris.getOrPut(folder) { mutableListOf() }.add(uri)
                }

                val allClusterResults = mutableListOf<ClusterEngine.ClusterResult>()
                var globalClusterOffset = 0

                for ((folder, folderUris) in folderToUris) {
                    val result = ClusterEngine.ClusterResult(
                        clusterId  = globalClusterOffset,
                        memberUris = folderUris,
                        bestShotUri = folderUris.first(),
                        blurryUris = emptyList(),
                        folder     = folder
                    )
                    allClusterResults.add(result)
                    globalClusterOffset++

                    Log.d(TAG, "Folder '$folder': ${folderUris.size} images → 1 group")
                }

                setForeground(buildForegroundInfo("Saving ${allClusterResults.size} groups…"))
                setProgress(workDataOf("status" to "Saving ${allClusterResults.size} groups…"))

                clusterRepository.saveClusterResults(allClusterResults, processedEmbeddings, clusterEngine)


                // Recalculate accurate counts from actual media items
                clusterRepository.refreshClusterCounts()

                // Clean up any empty/orphaned clusters
                clusterRepository.cleanupOrphanedClusters()

                for (result in allClusterResults) {
                    for (uri in result.memberUris) {
                        mediaRepository.updateAiResults(
                            uri        = uri,
                            clusterId  = result.clusterId,
                            isBestShot = false,
                            isBlurry   = false,
                            score      = null
                        )
                    }
                }

                val folderCount = folderToUris.size
                Log.i(TAG, "Fallback: ${urisToGroup.size} images → ${allClusterResults.size} folder groups")

                Result.success(workDataOf(
                    "status" to "Done",
                    "clusters" to allClusterResults.size,
                    "processed" to urisToGroup.size,
                    "folders" to folderCount
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed", e)
            Result.failure(workDataOf("error" to e.message))
        }
    }

    private fun buildForegroundInfo(status: String): ForegroundInfo {
        return ForegroundInfo(NOTIFICATION_ID, buildNotification(status))
    }
}
