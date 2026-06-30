package com.example.gallery.app.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.gallery.app.data.db.dao.RecycleBinDao
import com.example.gallery.app.data.db.entities.RecycleBinEntity
import com.example.gallery.app.data.repository.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class RecycleBinPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val recycleBinDao: RecycleBinDao
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "recycle_bin_purge"
        private const val TAG = "RecycleBinPurgeWorker"

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
            val cutoff = System.currentTimeMillis()
            val expired = recycleBinDao.getExpired(cutoff)

            if (expired.isEmpty()) {
                Log.d(TAG, "No expired items to purge")
                return Result.success(workDataOf("purged" to 0))
            }

            Log.d(TAG, "Purging ${expired.size} expired recycle bin items")

            // Delete actual files from storage on API < 30 (direct access)
            // On API 30+, Scoped Storage requires user approval via MediaStore.createDeleteRequest,
            // which can only be launched from UI. DB records are cleaned up here;
            // file deletion happens when user empties bin via UI or system handles it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                withContext(Dispatchers.IO) {
                    expired.forEach { item ->
                        try {
                            val uri = Uri.parse(item.uri)
                            applicationContext.contentResolver.delete(uri, null, null)
                            Log.d(TAG, "Deleted file: ${item.fileName}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete file: ${item.fileName}", e)
                        }
                    }
                }
            }

            // Clean up DB records regardless of API level
            // purgeExpiredRecycleBin uses recycleBinDate (= movedAt), so we must subtract
            // the 30-day TTL to only delete items that have been in the bin long enough
            recycleBinDao.purgeExpired(cutoff)
            mediaRepository.purgeExpiredRecycleBin(cutoff - RecycleBinEntity.RECYCLE_BIN_TTL_MS)

            Log.d(TAG, "Purge complete: ${expired.size} items processed")
            Result.success(workDataOf("purged" to expired.size))
        } catch (e: Exception) {
            Log.e(TAG, "Purge failed", e)
            Result.failure(workDataOf("error" to e.message))
        }
    }
}
