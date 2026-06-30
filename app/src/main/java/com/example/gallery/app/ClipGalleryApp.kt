package com.example.gallery.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.example.gallery.app.worker.AiProcessingWorker
import com.example.gallery.app.worker.RecycleBinPurgeWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ClipGalleryApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() {
            Log.d("ClipGalleryApp", "Providing WorkManager configuration via property. workerFactory initialized: ${::workerFactory.isInitialized}")
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecycleBinPurgeWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RecycleBinPurgeWorker.buildPeriodicRequest()
        )

        preWarmOnnxModel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val aiChannel = NotificationChannel(
                AiProcessingWorker.CHANNEL_ID,
                "AI Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Processing images with AI"
            }
            manager.createNotificationChannel(aiChannel)
        }
    }

    /**
     * Pre-initialize the ONNX model on a background thread during app cold start.
     * This avoids the 1-3s latency when the user first triggers AI processing.
     * The model stays in memory (singleton) — subsequent worker runs are instant.
     */
    private fun preWarmOnnxModel() {
        Thread {
            try {
                val startTime = System.currentTimeMillis()
                // Inject via Hilt is not available on background thread,
                // so we access the singleton directly through the component.
                val component = dagger.hilt.android.EntryPointAccessors
                    .fromApplication(applicationContext, OnnxEntryPoint::class.java)
                val embedder = component.onnxEmbedder()
                embedder.initialize()
                val elapsed = System.currentTimeMillis() - startTime
                Log.i("ClipGalleryApp", "ONNX model pre-warmed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.w("ClipGalleryApp", "ONNX pre-warm failed (non-fatal): ${e.message}")
            }
        }.start()
    }
}
