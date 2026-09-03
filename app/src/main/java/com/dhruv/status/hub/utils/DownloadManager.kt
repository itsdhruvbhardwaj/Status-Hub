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
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central manager for all downloads in Status Hub.
 * Restored to original working flow: Direct download and MediaStore saving.
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val MAX_CONCURRENT = 3

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    
    private val _downloadSpeeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val downloadSpeeds = _downloadSpeeds.asStateFlow()

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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
            db.downloadDao().updateRecord(record.copy(status = "DOWNLOADING", errorMessage = null))
            
            try {
                performDownload(context, record.id)
            } catch (e: CancellationException) {
                Log.d(TAG, "Download ${record.id} paused/cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Download ${record.id} failed", e)
                val errorMsg = when (e) {
                    is SocketTimeoutException -> "Request timed out. Please try again."
                    is IOException -> "Network error: ${e.message}"
                    else -> e.message ?: "Download failed"
                }
                db.downloadDao().updateRecord(record.copy(status = "FAILED", errorMessage = errorMsg))
            } finally {
                activeJobs.remove(record.id)
                updateSpeed(record.id, 0L)
                processQueue(context)
            }
        }
        activeJobs[record.id] = job
    }

    private suspend fun performDownload(context: Context, id: Long): Unit = withContext(Dispatchers.IO) {
        var retryCount = 0
        var completed = false
        
        while (!completed && retryCount < 3) {
            val db = DownloadDatabase.getDatabase(context)
            val record = db.downloadDao().getRecordById(id) ?: return@withContext
            val downloadUrl = record.downloadUrl ?: throw Exception("Missing download URL")
            
            val tempFile = File(context.cacheDir, "temp_${record.id}_${record.fileName}")
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Accept-Encoding", "identity")

            if (existingBytes > 0 || record.mediaType == "audio") {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val request = requestBuilder.build()
            
            Log.d(TAG, "--- DOWNLOAD REQUEST START ---")
            Log.d(TAG, "ID: $id, Existing Bytes: $existingBytes")
            Log.d(TAG, "URL: ${request.url}")
            request.headers.forEach { (name, value) ->
                Log.d(TAG, "Req Header: $name: $value")
            }
            
            try {
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Response Code: ${response.code}")
                    Log.d(TAG, "Protocol: ${response.protocol}")
                    response.headers.forEach { (name, value) ->
                        Log.d(TAG, "Res Header: $name: $value")
                    }
                    Log.d(TAG, "Body Content-Length: ${response.body?.contentLength()}")
                    Log.d(TAG, "--- DOWNLOAD REQUEST END ---")

                    if (!response.isSuccessful && response.code != 206) {
                        if (response.code == 416) {
                            tempFile.delete()
                            retryCount++
                            return@use
                        }
                        throw Exception("Server error HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val totalBytes = if (response.code == 206) {
                        val rangeHeader = response.header("Content-Range")
                        rangeHeader?.substringAfterLast("/")?.toLongOrNull() ?: (existingBytes + body.contentLength())
                    } else {
                        body.contentLength()
                    }
                    
                    db.downloadDao().updateRecord(record.copy(totalBytes = totalBytes, downloadedBytes = if (response.code == 206) existingBytes else 0L))

                    val randomAccessFile = RandomAccessFile(tempFile, "rw")
                    try {
                        if (response.code == 206) {
                            randomAccessFile.seek(existingBytes)
                        } else {
                            randomAccessFile.setLength(0)
                            randomAccessFile.seek(0)
                        }

                        val inputStream = body.byteStream()
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var lastUpdate = System.currentTimeMillis()
                        var downloadedInInterval = 0L
                        var currentDownloaded = if (response.code == 206) existingBytes else 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            yield()
                            randomAccessFile.write(buffer, 0, bytesRead)
                            currentDownloaded += bytesRead
                            downloadedInInterval += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 1000) {
                                updateSpeed(id, (downloadedInInterval * 1000) / (now - lastUpdate))
                                db.downloadDao().updateProgress(id, currentDownloaded)
                                lastUpdate = now
                                downloadedInInterval = 0
                            }
                        }
                    } finally {
                        randomAccessFile.close()
                    }

                    // Save the native file directly
                    val finalUri = FileUtils.saveTempFileToMediaStore(context, tempFile, record.fileName, record.mediaType)
                    
                    if (finalUri != null) {
                        db.downloadDao().updateRecord(record.copy(
                            status = "COMPLETED",
                            totalBytes = totalBytes,
                            downloadedBytes = totalBytes,
                            fileUri = finalUri.toString(),
                            errorMessage = null,
                            timestamp = System.currentTimeMillis()
                        ))
                        tempFile.delete()
                        completed = true
                        Log.d(TAG, "Download SUCCESS for ID=$id")
                    } else {
                        throw Exception("Failed to save media to system gallery.")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                retryCount++
                if (retryCount < 3 && (e is SocketTimeoutException || e is IOException)) {
                    delay(2000L * retryCount)
                } else {
                    throw e
                }
            }
        }
    }
}
