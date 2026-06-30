package com.example.gallery.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.example.gallery.app.worker.RecycleBinPurgeWorker

/**
 * Handles system boot events.
 * Reschedules periodic WorkManager jobs that were cleared on reboot.
 * Includes a fix for the common "HiAiBroadcastReceiver" crash on Huawei devices
 * by ensuring setResult is only called for ordered broadcasts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device boot completed — rescheduling workers")

            // Reschedule periodic recycle bin purge worker (cleared on reboot)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                RecycleBinPurgeWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                RecycleBinPurgeWorker.buildPeriodicRequest()
            )

            // Fix: Check isOrderedBroadcast before calling setResult
            // to avoid RuntimeException on certain devices (e.g. Huawei)
            if (isOrderedBroadcast) {
                resultCode = android.app.Activity.RESULT_OK
            }
        }
    }
}
