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
) : CoroutineWorker(applicationContext, params) {

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

        return try {
            try {
                embedder.initialize()
            } catch (e: IllegalStateException) {
                return Result.failure(workDataOf("error" to e.message))
            }

            val selectedUris = inputData.getStringArray(KEY_URIS)
            val isSelective = selectedUris != null && selectedUris.isNotEmpty()

            val processedEmbeddings = mutableMapOf<String, FloatArray>()
            val processedSharpness  = mutableMapOf<String, Float>()

            if (isSelective) {
                setForeground(buildForegroundInfo("Processing ${selectedUris!!.size} selected images…"))
                setProgress(workDataOf(
                    "status" to "Processing ${selectedUris.size} selected images…",
                    "processed" to 0,
                    "total" to selectedUris.size
                ))

                var processedCount = 0
                for (uri in selectedUris) {
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
                    if (processedCount % 10 == 0 || processedCount == selectedUris.size) {
                        val status = "Processing $processedCount/${selectedUris.size}…"
                        setForeground(buildForegroundInfo(status))
                        setProgress(workDataOf("status" to status, "processed" to processedCount, "total" to selectedUris.size))
                    }
                }
            } else {
                var batchNum = 0
                while (true) {
                    if (isStopped) {
                        return Result.success(workDataOf("status" to "Cancelled", "processed" to processedEmbeddings.size))
                    }

                    val batch = mediaRepository.getUnprocessedBatch(BATCH_SIZE)
                    if (batch.isEmpty()) break

                    batchNum++
                    val status = "Processing batch $batchNum…"
                    setForeground(buildForegroundInfo(status))
                    setProgress(workDataOf("status" to status, "processed" to processedEmbeddings.size))

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

            // ── Step 2: Cluster PER FOLDER ──
            setForeground(buildForegroundInfo("Clustering images by folder…"))
            setProgress(workDataOf("status" to "Clustering images by folder…"))

            // Build COMPLETE folder map for ALL images with embeddings (new + existing)
            // This is critical — we need folder info for every embedding, not just newly processed ones
            val allEmbeddings = mutableMapOf<String, FloatArray>()
            val allSharpness  = mutableMapOf<String, Float>()

            if (isSelective) {
                // Load ALL existing embeddings + folder info from DB
                val existingEmbeddings = mediaRepository.getAllProcessedEmbeddings()
                allEmbeddings.putAll(existingEmbeddings)
                allEmbeddings.putAll(processedEmbeddings)

                // Load ALL existing sharpness from DB (we don't have it, use 0 as placeholder)
                for (uri in existingEmbeddings.keys) {
                    if (uri !in processedSharpness) {
                        allSharpness[uri] = 0f
                    }
                }
                allSharpness.putAll(processedSharpness)
            } else {
                allEmbeddings.putAll(processedEmbeddings)
                allSharpness.putAll(processedSharpness)
            }

            // Load folder info for EVERY embedding from DB (single query)
            val uriToFolders = mediaRepository.getFoldersForUris(allEmbeddings.keys.toList())
            Log.d(TAG, "Loaded folder info for ${uriToFolders.size}/${allEmbeddings.size} URIs")

            // Group embeddings by folder
            val folderEmbeddings = mutableMapOf<String, MutableMap<String, FloatArray>>()
            val folderSharpness  = mutableMapOf<String, MutableMap<String, Float>>()

            for ((uri, embedding) in allEmbeddings) {
                val folder = uriToFolders[uri] ?: "Unknown"
                folderEmbeddings.getOrPut(folder) { mutableMapOf() }[uri] = embedding
                val score = allSharpness[uri]
                if (score != null) {
                    folderSharpness.getOrPut(folder) { mutableMapOf() }[uri] = score
                }
            }

            Log.d(TAG, "Folders with embeddings: ${folderEmbeddings.keys}")

            // Cluster within each folder
            val allClusterResults = mutableListOf<ClusterEngine.ClusterResult>()
            var globalClusterOffset = 0

            for ((folder, embeddings) in folderEmbeddings) {
                val folderSharp = folderSharpness[folder] ?: emptyMap()
                val folderResults = clusterEngine.cluster(embeddings, folderSharp)

                val offsetResults = folderResults.map { result ->
                    result.copy(clusterId = result.clusterId + globalClusterOffset, folder = folder)
                }
                allClusterResults.addAll(offsetResults)
                globalClusterOffset += folderResults.size

                Log.d(TAG, "Folder '$folder': ${embeddings.size} images → ${folderResults.size} clusters")
            }

            setForeground(buildForegroundInfo("Saving results…"))
            setProgress(workDataOf("status" to "Saving ${allClusterResults.size} albums…"))

            Log.i(TAG, "Saving ${allClusterResults.size} clusters")
            clusterRepository.saveClusterResults(allClusterResults)

            for (result in allClusterResults) {
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

            val processedCount = if (isSelective) selectedUris!!.size else processedEmbeddings.size
            val folderCount = folderEmbeddings.size
            Log.i(TAG, "Done: $processedCount images across $folderCount folders → ${allClusterResults.size} clusters")

            Result.success(workDataOf(
                "status" to "Done",
                "clusters" to allClusterResults.size,
                "processed" to processedCount,
                "folders" to folderCount
            ))
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed", e)
            Result.failure(workDataOf("error" to e.message))
        }
    }

    private fun buildForegroundInfo(status: String): ForegroundInfo {
        return ForegroundInfo(NOTIFICATION_ID, buildNotification(status))
    }
}
