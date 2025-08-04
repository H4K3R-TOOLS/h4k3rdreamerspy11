package com.h4k3r.dreamer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.util.Log

class OnePixelActivity : Activity() {
    companion object {
        private const val TAG = "OnePixelActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Do not run OnePixelActivity on Android 12+
            finish()
            return
        }
        Log.d(TAG, "OnePixelActivity created")

        // Create a 1x1 pixel view
        val layout = LinearLayout(this)
        layout.layoutParams = LinearLayout.LayoutParams(1, 1)
        layout.setBackgroundColor(0x00000000) // Transparent
        setContentView(layout)

        // Configure window to be minimal and transparent
        window.apply {
            setGravity(Gravity.START or Gravity.TOP)
            setLayout(1, 1)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Keep screen on
        addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OnePixelActivity destroyed")
        
        // Try to restart services when this activity is destroyed
        try {
            val intent = Intent(this, PermissionMonitorService::class.java)
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart services", e)
        }
    }

    private fun addFlags(flags: Int) {
        window.addFlags(flags)
    }
} 