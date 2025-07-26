package com.h4k3r.dreamer

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters

class PermissionMonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val serviceIntent = Intent(applicationContext, PermissionMonitorService::class.java)
            applicationContext.startService(serviceIntent)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
} 