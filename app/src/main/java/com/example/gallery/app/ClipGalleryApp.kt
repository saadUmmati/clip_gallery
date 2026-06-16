package com.example.gallery.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.example.gallery.app.worker.RecycleBinPurgeWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ClipGalleryApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecycleBinPurgeWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RecycleBinPurgeWorker.buildPeriodicRequest()
        )
        Log.d("ClipGalleryApp", "WorkManager initialized with HiltWorkerFactory: ${workerFactory::class.simpleName}")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
