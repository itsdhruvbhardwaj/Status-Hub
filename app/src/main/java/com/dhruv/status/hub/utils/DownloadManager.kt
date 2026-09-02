package com.dhruv.status.hub.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.dhruv.status.hub.data.DownloadDatabase
import com.dhruv.status.hub.data.DownloadRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central manager for all downloads in Status Hub.
 * Handles queuing, concurrency (max 3), persistence, and range-based resume.
 * Updated to fix initial download speed and improve speed calculation with rolling average.
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val MAX_CONCURRENT = 3

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    
    // Observable map for real-time speed updates
    private val _downloadSpeeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val downloadSpeeds = _downloadSpeeds.asStateFlow()

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Recovery logic: Reset stuck "DOWNLOADING" records to "QUEUED" on startup.
     */
    fun init(context: Context) {
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val stuckDownloads = db.downloadDao().getDownloadsByStatus("DOWNLOADING")
            stuckDownloads.forEach { record ->
                db.downloadDao().updateRecord(record.copy(status = "QUEUED"))
            }
            processQueue(context)
        }
    }

    fun enqueue(context: Context, record: DownloadRecord) {
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            db.downloadDao().insertRecord(record)
            startService(context)
            processQueue(context)
        }
    }

    fun resume(context: Context, id: Long) {
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val record = db.downloadDao().getRecordById(id) ?: return@launch
            db.downloadDao().updateRecord(record.copy(status = "QUEUED", errorMessage = null))
            startService(context)
            processQueue(context)
        }
    }

    fun pause(context: Context, id: Long) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateSpeed(id, 0L)
        
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val record = db.downloadDao().getRecordById(id) ?: return@launch
            db.downloadDao().updateRecord(record.copy(status = "PAUSED"))
            processQueue(context)
        }
    }

    fun cancel(context: Context, id: Long) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateSpeed(id, 0L)
        
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val record = db.downloadDao().getRecordById(id) ?: return@launch
            
            val tempFile = File(context.cacheDir, "temp_${record.id}_${record.fileName}")
            if (tempFile.exists()) tempFile.delete()
            
            db.downloadDao().deleteRecord(record)
            processQueue(context)
        }
    }

    private fun updateSpeed(id: Long, speed: Long) {
        val current = _downloadSpeeds.value.toMutableMap()
        if (speed == 0L) current.remove(id) else current[id] = speed
        _downloadSpeeds.value = current
    }

    private fun startService(context: Context) {
        try {
            val intent = Intent(context, DownloadService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }

    private fun processQueue(context: Context) {
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val downloadingCount = db.downloadDao().getCountByStatus("DOWNLOADING")
            
            if (downloadingCount < MAX_CONCURRENT) {
                val needed = MAX_CONCURRENT - downloadingCount
                val nextInQueue = db.downloadDao().getNextQueued(needed)
                nextInQueue.forEach { record ->
                    startDownload(context, record)
                }
            }
        }
    }

    private fun startDownload(context: Context, record: DownloadRecord) {
        if (activeJobs.containsKey(record.id)) return

        val job = scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            db.downloadDao().updateRecord(record.copy(status = "DOWNLOADING"))
            
            try {
                performDownload(context, record.id)
            } catch (_: CancellationException) {
                // Handled
            } catch (e: Exception) {
                Log.e(TAG, "Download ${record.id} failed", e)
                db.downloadDao().updateRecord(record.copy(status = "FAILED", errorMessage = e.message))
            } finally {
                activeJobs.remove(record.id)
                updateSpeed(record.id, 0L)
                processQueue(context)
            }
        }
        activeJobs[record.id] = job
    }

    private suspend fun performDownload(context: Context, id: Long): Unit {
        var shouldRetry = true
        while (shouldRetry) {
            shouldRetry = false
            withContext(Dispatchers.IO) {
                val db = DownloadDatabase.getDatabase(context)
                val record = db.downloadDao().getRecordById(id) ?: return@withContext
                val downloadUrl = record.downloadUrl ?: throw Exception("Missing download URL")
                
                val tempFile = File(context.cacheDir, "temp_${record.id}_${record.fileName}")
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                // DEBUG LOGGING: START/RESUME
                Log.d(TAG, "DOWNLOAD_EVENT: ${if (existingBytes == 0L) "START" else "RESUME"} ID=$id")
                Log.d(TAG, "Download URL: $downloadUrl")
                Log.d(TAG, "Existing Bytes: $existingBytes")

                val requestBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    // Requirement: Use Range header for ALL requests to ensure consistent high speed
                    .header("Range", "bytes=$existingBytes-")
                    .header("Accept-Encoding", "identity") // Prevent gzip which messes with Content-Length and speed calc

                val request = requestBuilder.build()
                Log.d(TAG, "Request Headers: ${request.headers}")

                client.newCall(request).execute().use { response ->
                    // DEBUG LOGGING: RESPONSE
                    Log.d(TAG, "HTTP Status: ${response.code}")
                    Log.d(TAG, "Response Headers: ${response.headers}")

                    if (!response.isSuccessful && response.code != 206) {
                        Log.e(TAG, "Download failed with code ${response.code}")
                        if (response.code == 416) {
                            Log.w(TAG, "Requested range not satisfiable (416). Deleting temp file and retrying.")
                            tempFile.delete()
                            shouldRetry = true
                            return@use
                        }
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val totalBytes = if (response.code == 206) {
                        val rangeHeader = response.header("Content-Range")
                        Log.d(TAG, "Content-Range: $rangeHeader")
                        rangeHeader?.substringAfterLast("/")?.toLongOrNull() ?: (existingBytes + body.contentLength())
                    } else {
                        body.contentLength()
                    }
                    Log.d(TAG, "Content-Length: ${body.contentLength()}, Total Bytes: $totalBytes")

                    db.downloadDao().updateRecord(record.copy(totalBytes = totalBytes, downloadedBytes = existingBytes))

                    val inputStream = body.byteStream()
                    val randomAccessFile = RandomAccessFile(tempFile, "rw")
                    randomAccessFile.seek(existingBytes)

                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    val startTime = System.currentTimeMillis()
                    var lastUpdate = startTime
                    var downloadedSinceLastUpdate = 0L
                    var currentDownloaded = existingBytes
                    
                    // Rolling average (5 seconds)
                    val speedWindow = LongArray(5)
                    var windowIndex = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        yield()
                        randomAccessFile.write(buffer, 0, bytesRead)
                        currentDownloaded += bytesRead
                        downloadedSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 1000) {
                            val timeDiff = now - lastUpdate
                            val currentSpeed = (downloadedSinceLastUpdate * 1000) / timeDiff // bytes/sec
                            
                            // Update rolling average
                            speedWindow[windowIndex] = currentSpeed
                            windowIndex = (windowIndex + 1) % speedWindow.size
                            val avgSpeed = speedWindow.filter { it > 0 }.average().toLong()
                            
                            val totalTimeElapsed = now - startTime
                            val totalDownloadedInThisSession = currentDownloaded - existingBytes
                            val sessionAvgSpeed = if (totalTimeElapsed > 0) (totalDownloadedInThisSession * 1000) / totalTimeElapsed else 0L

                            // Log metrics as requested
                            Log.d(TAG, "Progress ID=$id: ${currentDownloaded}/${totalBytes} bytes | " +
                                  "Current: ${formatSpeed(currentSpeed)} | Rolling Avg: ${formatSpeed(avgSpeed)} | " +
                                  "Session Avg: ${formatSpeed(sessionAvgSpeed)}")

                            updateSpeed(id, avgSpeed)
                            db.downloadDao().updateProgress(id, currentDownloaded)
                            
                            lastUpdate = now
                            downloadedSinceLastUpdate = 0
                        }
                    }
                    randomAccessFile.close()

                    val finalUri = FileUtils.saveTempFileToMediaStore(context, tempFile, record.fileName, record.mediaType)
                    if (finalUri != null) {
                        Log.d(TAG, "Download completed successfully: $id")
                        db.downloadDao().updateRecord(record.copy(
                            status = "COMPLETED",
                            downloadedBytes = totalBytes,
                            fileUri = finalUri.toString(),
                            timestamp = System.currentTimeMillis()
                        ))
                        tempFile.delete()
                    } else {
                        throw Exception("Failed to save to gallery")
                    }
                }
            }
        }
    }
    
    private fun formatSpeed(bytesPerSecond: Long): String {
        return if (bytesPerSecond < 1024 * 1024) {
            "${bytesPerSecond / 1024} KB/s"
        } else {
            String.format("%.2f MB/s", bytesPerSecond.toDouble() / (1024 * 1024))
        }
    }
}
