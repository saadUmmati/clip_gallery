package com.example.gallery.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.gallery.app.data.repository.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
            Log.d("MediaScanWorker", "Scan completed: $count images found")
            Result.success(workDataOf(KEY_SCANNED_COUNT to count))
        } catch (e: Exception) {
            Log.e("MediaScanWorker", "Scan failed: ${e.message}", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown scan error")))
        }
    }
}
