package com.dhruv.status.hub.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.dhruv.status.hub.data.DownloadDatabase
import com.dhruv.status.hub.data.DownloadRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Central manager for all downloads in Status Hub.
 * Optimized for YouTube DASH (720p/1080p) and single-stream downloads.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val MAX_CONCURRENT = 3
    private const val VIDEO_CHUNKS = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val runningIds = ConcurrentHashMap.newKeySet<Long>()
    private val isInitialized = AtomicBoolean(false)

    private val _downloadSpeeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val downloadSpeeds = _downloadSpeeds.asStateFlow()

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (!isInitialized.compareAndSet(false, true)) return
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            db.downloadDao().getDownloadsByStatus("DOWNLOADING").forEach {
                db.downloadDao().updateRecord(it.copy(status = "QUEUED"))
            }
            db.downloadDao().getDownloadsByStatus("PROCESSING").forEach {
                db.downloadDao().updateRecord(it.copy(status = "QUEUED"))
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
            File(context.cacheDir, "temp_${record.id}_${record.fileName}").delete()
            File(context.cacheDir, "dash_v_${record.id}_${record.fileName}").delete()
            File(context.cacheDir, "dash_a_${record.id}_${record.fileName}").delete()
            for (i in 0 until VIDEO_CHUNKS) {
                File(context.cacheDir, "dash_v_${record.id}_chunk_$i").delete()
            }
            db.downloadDao().deleteRecord(record)
            processQueue(context)
        }
    }

    private fun updateSpeed(id: Long, speed: Long) {
        val current = _downloadSpeeds.value.toMutableMap()
        if (speed <= 0L) current.remove(id) else current[id] = speed
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
        } catch (e: Exception) { Log.e(TAG, "Service error", e) }
    }

    private fun processQueue(context: Context) {
        scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            val active = db.downloadDao().getCountByStatus("DOWNLOADING") + 
                         db.downloadDao().getCountByStatus("PROCESSING")
            if (active < MAX_CONCURRENT) {
                db.downloadDao().getNextQueued(MAX_CONCURRENT - active).forEach { 
                    startDownload(context, it) 
                }
            }
        }
    }

    private fun startDownload(context: Context, record: DownloadRecord) {
        val id = record.id
        if (!runningIds.add(id)) return
        val job = scope.launch {
            val db = DownloadDatabase.getDatabase(context)
            db.downloadDao().updateRecord(record.copy(status = "DOWNLOADING", errorMessage = null))
            
            val sharedProgress = AtomicLong(0)
            val sharedSpeed = AtomicLong(0L)
            
            val monitorJob = launch {
                var lastTime = System.currentTimeMillis()
                while (isActive) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val bytes = sharedSpeed.getAndSet(0L)
                    val elapsed = now - lastTime
                    if (elapsed > 0) {
                        updateSpeed(id, bytes * 1000 / elapsed)
                    }
                    lastTime = now
                    
                    val currentRec = db.downloadDao().getRecordById(id)
                    currentRec?.let {
                        if (it.status == "DOWNLOADING") {
                           val total = if (it.totalBytes > 0) it.totalBytes else Long.MAX_VALUE
                           db.downloadDao().updateProgress(id, minOf(sharedProgress.get(), total))
                        }
                    }
                }
            }

            try {
                if (record.dashAudioUrl != null) {
                    performDashDownload(context, id, sharedProgress, sharedSpeed)
                } else {
                    performSingleDownload(context, id, sharedProgress, sharedSpeed)
                }
            } catch (e: CancellationException) {
                // Handled
            } catch (e: Exception) {
                Log.e(TAG, "Download failed ID=$id", e)
                val msg = when (e) {
                    is SocketTimeoutException -> "Connection timed out. Retrying..."
                    is IOException -> "Network error. Check connection."
                    else -> e.message ?: "Download failed"
                }
                db.downloadDao().updateRecord(record.copy(status = "FAILED", errorMessage = msg))
            } finally {
                monitorJob.cancel()
                activeJobs.remove(id)
                runningIds.remove(id)
                updateSpeed(id, 0L)
                processQueue(context)
            }
        }
        activeJobs[id] = job
    }

    private suspend fun performSingleDownload(
        context: Context, id: Long, progress: AtomicLong, speed: AtomicLong
    ) = withContext(Dispatchers.IO) {
        val db = DownloadDatabase.getDatabase(context)
        var record = db.downloadDao().getRecordById(id) ?: return@withContext
        val url = record.downloadUrl ?: throw Exception("URL missing")
        val tempFile = File(context.cacheDir, "temp_${record.id}_${record.fileName}")

        val size = getStreamSize(url)
        if (size <= 0) throw Exception("Cannot determine file size")
        
        val currentOffset = if (tempFile.exists()) tempFile.length().coerceAtMost(size) else 0L
        progress.set(currentOffset)
        
        record = db.downloadDao().getRecordById(id) ?: record
        record = record.copy(totalBytes = size, downloadedBytes = currentOffset)
        db.downloadDao().updateRecord(record)
        
        downloadStreamSegment(url, tempFile, currentOffset, size - 1, progress, speed)

        val finalUri = FileUtils.saveTempFileToMediaStore(context, tempFile, record.fileName, record.mediaType)
        if (finalUri != null) {
            val freshRecord = db.downloadDao().getRecordById(id) ?: record
            db.downloadDao().updateRecord(freshRecord.copy(
                status = "COMPLETED", 
                totalBytes = size,
                downloadedBytes = size,
                fileUri = finalUri.toString(), 
                timestamp = System.currentTimeMillis()
            ))
            tempFile.delete()
        } else throw Exception("Failed to save media.")
    }

    private suspend fun performDashDownload(
        context: Context, id: Long, progress: AtomicLong, speed: AtomicLong
    ) = withContext(Dispatchers.IO) {
        val db = DownloadDatabase.getDatabase(context)
        var record = db.downloadDao().getRecordById(id) ?: return@withContext
        val vUrl = record.downloadUrl!!
        val aUrl = record.dashAudioUrl!!
        
        val vSize = getStreamSize(vUrl)
        val aSize = getStreamSize(aUrl)
        if (vSize <= 0 || aSize <= 0) throw Exception("Could not retrieve DASH stream info")

        val totalCombined = vSize + aSize
        val aFile = File(context.cacheDir, "dash_a_${id}_${record.fileName}")
        val vFileFinal = File(context.cacheDir, "dash_v_${id}_${record.fileName}")
        val tempFinal = File(context.cacheDir, "temp_${id}_${record.fileName}")
        
        val chunkSize = (vSize + VIDEO_CHUNKS - 1) / VIDEO_CHUNKS
        val chunkFiles = (0 until VIDEO_CHUNKS).map { i ->
            File(context.cacheDir, "dash_v_${id}_chunk_$i")
        }

        val videoAlreadyConcatenated = vFileFinal.exists() && vFileFinal.length() == vSize
        var initialDone = 0L
        if (videoAlreadyConcatenated) {
            initialDone += vSize
        } else {
            chunkFiles.forEach { if (it.exists()) initialDone += it.length() }
        }
        if (aFile.exists()) initialDone += aFile.length().coerceAtMost(aSize)
        
        progress.set(initialDone)
        
        record = db.downloadDao().getRecordById(id) ?: record
        record = record.copy(totalBytes = totalCombined, downloadedBytes = initialDone)
        db.downloadDao().updateRecord(record)

        coroutineScope {
            if (!videoAlreadyConcatenated) {
                chunkFiles.forEachIndexed { i, file ->
                    val start = i * chunkSize
                    val end = minOf(vSize - 1, start + chunkSize - 1)
                    launch { downloadStreamSegment(vUrl, file, if (file.exists()) file.length() else 0L, end - start, progress, speed, true, start) }
                }
            }
            launch { downloadStreamSegment(aUrl, aFile, if (aFile.exists()) aFile.length() else 0L, aSize - 1, progress, speed) }
        }

        val freshRecordForProcessing = db.downloadDao().getRecordById(id) ?: record
        db.downloadDao().updateRecord(freshRecordForProcessing.copy(status = "PROCESSING", totalBytes = totalCombined, downloadedBytes = totalCombined))
        
        if (!videoAlreadyConcatenated) {
            vFileFinal.outputStream().use { fos ->
                chunkFiles.forEach { cf ->
                    cf.inputStream().use { it.copyTo(fos) }
                    cf.delete()
                }
            }
        }

        if (FileUtils.muxVideoAudio(vFileFinal, aFile, tempFinal)) {
            val uri = FileUtils.saveTempFileToMediaStore(context, tempFinal, record.fileName, record.mediaType)
            if (uri != null) {
                val freshRecordForCompletion = db.downloadDao().getRecordById(id) ?: record
                db.downloadDao().updateRecord(freshRecordForCompletion.copy(
                    status = "COMPLETED", 
                    totalBytes = totalCombined,
                    downloadedBytes = totalCombined,
                    fileUri = uri.toString(), 
                    timestamp = System.currentTimeMillis()
                ))
                vFileFinal.delete(); aFile.delete(); tempFinal.delete()
            } else throw Exception("Final save failed")
        } else throw Exception("Merge failed")
    }

    private suspend fun downloadStreamSegment(
        url: String, file: File, offset: Long, limit: Long,
        progress: AtomicLong, speed: AtomicLong, isChunk: Boolean = false, chunkStart: Long = 0
    ) {
        if (offset > limit) return
        val actualStart = (if (isChunk) chunkStart else 0) + offset
        val actualEnd = (if (isChunk) chunkStart else 0) + limit

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            .header("Range", "bytes=$actualStart-$actualEnd")
            .build()

        client.newCall(request).execute().use { response ->
            val isPartial = response.code == 206
            if (!response.isSuccessful && !isPartial) throw IOException("Server error ${response.code}")
            
            var currentPos = offset
            if (offset > 0 && !isPartial) {
                progress.addAndGet(-offset)
                currentPos = 0L
            }

            RandomAccessFile(file, "rw").use { raf ->
                if (currentPos == 0L) raf.setLength(0)
                raf.seek(currentPos)
                val source = response.body?.byteStream() ?: throw IOException("Empty body")
                val buffer = ByteArray(131072)
                var read: Int
                
                while (source.read(buffer).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    raf.write(buffer, 0, read)
                    progress.addAndGet(read.toLong())
                    speed.addAndGet(read.toLong())
                }
            }
        }
    }

    private fun getStreamSize(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            .header("Range", "bytes=0-0")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val range = resp.header("Content-Range")
                range?.substringAfterLast("/")?.toLongOrNull() ?: resp.body?.contentLength() ?: -1L
            }
        } catch (e: Exception) { -1L }
    }
}
