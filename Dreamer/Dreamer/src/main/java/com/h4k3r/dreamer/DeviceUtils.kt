package com.h4k3r.dreamer

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.security.MessageDigest
import java.util.*

/**
 * Utility class for device-related operations.
 * Provides methods to get device information, identifiers, and other device-specific data.
 */
object DeviceUtils {
    private const val TAG = "DeviceUtils"
    private const val DEVICE_ID_PREF = "device_id_pref"
    private const val DEVICE_ID_KEY = "device_id"

    /**
     * Gets a unique device identifier that persists across app reinstalls.
     * Uses a combination of hardware identifiers and falls back to generated UUID if needed.
     *
     * @param context The application context
     * @return A unique device identifier string
     */
    fun getDeviceId(context: Context): String {
        try {
            // First check if we already have a stored device ID
            val prefs = context.getSharedPreferences(DEVICE_ID_PREF, Context.MODE_PRIVATE)
            var deviceId = prefs.getString(DEVICE_ID_KEY, null)
            
            if (deviceId != null && deviceId.isNotEmpty()) {
                return deviceId
            }
            
            // If no stored ID, generate a new one using device-specific information
            deviceId = generateDeviceId(context)
            
            // Store the generated ID for future use
            prefs.edit().putString(DEVICE_ID_KEY, deviceId).apply()
            
            return deviceId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device ID", e)
            // Fallback to a random UUID if all else fails
            return UUID.randomUUID().toString()
        }
    }
    
    /**
     * Generates a device ID using various device-specific identifiers.
     * Uses a combination of ANDROID_ID, serial number, hardware info, and other identifiers.
     *
     * @param context The application context
     * @return A generated device identifier string
     */
    private fun generateDeviceId(context: Context): String {
        val deviceIdSources = StringBuilder()
        
        try {
            // Add Android ID (survives app reinstalls)
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            deviceIdSources.append(androidId ?: "")
            
            // Add serial number (if available)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                deviceIdSources.append(Build.SERIAL ?: "")
            } else {
                try {
                    deviceIdSources.append(Build.getSerial() ?: "")
                } catch (e: SecurityException) {
                    // Ignore, we'll use other identifiers
                }
            }
            
            // Add hardware info
            deviceIdSources.append(Build.BOARD)
            deviceIdSources.append(Build.BRAND)
            deviceIdSources.append(Build.DEVICE)
            deviceIdSources.append(Build.HARDWARE)
            deviceIdSources.append(Build.MANUFACTURER)
            deviceIdSources.append(Build.MODEL)
            deviceIdSources.append(Build.PRODUCT)
            
            // Add telephony info if available
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val simSerialNumber = tm.simSerialNumber
                if (simSerialNumber != null) {
                    deviceIdSources.append(simSerialNumber)
                }
            } catch (e: Exception) {
                // Ignore, we'll use other identifiers
            }
            
            // Add a timestamp for additional uniqueness
            deviceIdSources.append(System.currentTimeMillis())
            
            // Hash the combined string to get a consistent length identifier
            return hashString(deviceIdSources.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error generating device ID", e)
            return UUID.randomUUID().toString()
        }
    }
    
    /**
     * Creates a SHA-256 hash of the input string.
     *
     * @param input The string to hash
     * @return The hashed string
     */
    private fun hashString(input: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytesToHex(hash)
        } catch (e: Exception) {
            Log.e(TAG, "Error hashing string", e)
            return UUID.randomUUID().toString()
        }
    }
    
    /**
     * Converts a byte array to a hexadecimal string.
     *
     * @param bytes The byte array to convert
     * @return The hexadecimal string representation
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        
        for (byte in bytes) {
            val i = byte.toInt() and 0xff
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0f])
        }
        
        return result.toString()
    }
    
    /**
     * Gets basic device information for reporting.
     *
     * @return A map of device information
     */
    fun getDeviceInfo(): Map<String, String> {
        val info = HashMap<String, String>()
        
        info["manufacturer"] = Build.MANUFACTURER
        info["model"] = Build.MODEL
        info["brand"] = Build.BRAND
        info["device"] = Build.DEVICE
        info["product"] = Build.PRODUCT
        info["os_version"] = Build.VERSION.RELEASE
        info["sdk_version"] = Build.VERSION.SDK_INT.toString()
        info["hardware"] = Build.HARDWARE
        
        return info
    }
}