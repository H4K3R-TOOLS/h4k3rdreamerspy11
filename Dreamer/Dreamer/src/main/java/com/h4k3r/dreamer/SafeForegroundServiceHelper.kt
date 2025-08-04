package com.h4k3r.dreamer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

object SafeForegroundServiceHelper {
    private const val TAG = "SafeForegroundServiceHelper"
    private const val FOREGROUND_START_TIMEOUT = 3000L // 3 seconds

    /**
     * Safely starts a foreground service with proper timeout handling
     */
    suspend fun startForegroundServiceSafely(
        context: Context,
        serviceClass: Class<out Service>,
        waitForStart: Boolean = true
    ): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(context, serviceClass)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service: ${serviceClass.simpleName}")

                    // Start the foreground service
                    context.startForegroundService(intent)

                    if (waitForStart) {
                        // Give the service time to call startForeground()
                        delay(FOREGROUND_START_TIMEOUT)
                    }

                    Log.d(TAG, "Foreground service started successfully: ${serviceClass.simpleName}")
                    true
                } else {
                    // For older Android versions, just start as regular service
                    context.startService(intent)
                    Log.d(TAG, "Regular service started: ${serviceClass.simpleName}")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service ${serviceClass.simpleName}: ${e.message}")

                // Check if it's a foreground service timeout exception
                if (e.message?.contains("ForegroundServiceDidNotStartInTime") == true) {
                    Log.w(TAG, "Foreground service timeout detected for ${serviceClass.simpleName}")
                    return@withContext tryFallbackService(context, serviceClass)
                }

                // For other exceptions, try fallback
                return@withContext tryFallbackService(context, serviceClass)
            }
        }
    }

    /**
     * Attempts to start service as regular service when foreground fails
     */
    private suspend fun tryFallbackService(
        context: Context,
        serviceClass: Class<out Service>
    ): Boolean {
        return try {
            Log.d(TAG, "Attempting fallback regular service start: ${serviceClass.simpleName}")
            val intent = Intent(context, serviceClass)
            context.startService(intent)
            Log.d(TAG, "Fallback service started successfully: ${serviceClass.simpleName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback service start also failed for ${serviceClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Batch start multiple services with staggered timing
     */
    suspend fun startMultipleServicesSafely(
        context: Context,
        services: List<Class<out Service>>,
        staggerDelayMs: Long = 1000L
    ): Map<Class<out Service>, Boolean> {
        val results = mutableMapOf<Class<out Service>, Boolean>()

        services.forEachIndexed { index, serviceClass ->
            try {
                // Stagger service starts to reduce system load
                if (index > 0) {
                    delay(staggerDelayMs)
                }

                val success = startForegroundServiceSafely(context, serviceClass, waitForStart = false)
                results[serviceClass] = success

                Log.d(TAG, "Service ${serviceClass.simpleName}: ${if (success) "SUCCESS" else "FAILED"}")

            } catch (e: Exception) {
                Log.e(TAG, "Exception starting service ${serviceClass.simpleName}: ${e.message}")
                results[serviceClass] = false
            }
        }

        Log.d(TAG, "Batch service start complete: ${results.values.count { it }}/${services.size} successful")
        return results
    }

    /**
     * Creates a safe intent for starting services
     */
    fun createSafeServiceIntent(context: Context, serviceClass: Class<out Service>): Intent {
        return Intent(context, serviceClass).apply {
            // Add any safe flags or extras here
            putExtra("safe_start", true)
            putExtra("start_time", System.currentTimeMillis())
        }
    }

    /**
     * Handles foreground service exceptions gracefully
     */
    fun handleForegroundServiceException(
        exception: Exception,
        serviceClass: Class<out Service>
    ): String {
        return when {
            exception.message?.contains("ForegroundServiceDidNotStartInTime") == true -> {
                "Foreground service timeout for ${serviceClass.simpleName} - service took too long to call startForeground()"
            }
            exception.message?.contains("FOREGROUND_SERVICE_TYPE") == true -> {
                "Missing or invalid foreground service type for ${serviceClass.simpleName}"
            }
            exception.message?.contains("Bad notification") == true -> {
                "Invalid notification for foreground service ${serviceClass.simpleName}"
            }
            else -> {
                "Unknown foreground service error for ${serviceClass.simpleName}: ${exception.message}"
            }
        }
    }
}