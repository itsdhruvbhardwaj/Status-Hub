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
import java.util.concurrent.atomic.AtomicLong

/**
 * Central manager for all downloads in Status Hub.
 *
 * Progressive downloads (360p/audio) keep the original working flow.
 *
 * YouTube DASH downloads:
 * - Downloads video using parallel HTTP Range requests.
 * - Uses 4 parallel chunks.
 * - Supports cancellation safely.
 * - Downloads audio after video completes.
 * - Uses the existing muxing implementation.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"

    private const val MAX_CONCURRENT = 3

    // Number of simultaneous HTTP connections for DASH video.
    private const val DASH_CHUNK_COUNT = 4

    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeJobs =
        ConcurrentHashMap<Long, Job>()

    private val _downloadSpeeds =
        MutableStateFlow<Map<Long, Long>>(emptyMap())

    val downloadSpeeds =
        _downloadSpeeds.asStateFlow()

    private val client =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()


    fun init(context: Context) {

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            val stuckDownloads =
                db.downloadDao()
                    .getDownloadsByStatus("DOWNLOADING")

            stuckDownloads.forEach { record ->

                db.downloadDao().updateRecord(
                    record.copy(
                        status = "QUEUED"
                    )
                )
            }

            processQueue(context)
        }
    }


    fun enqueue(
        context: Context,
        record: DownloadRecord
    ) {

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            db.downloadDao()
                .insertRecord(record)

            startService(context)

            processQueue(context)
        }
    }


    fun resume(
        context: Context,
        id: Long
    ) {

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@launch

            db.downloadDao().updateRecord(
                record.copy(
                    status = "QUEUED",
                    errorMessage = null
                )
            )

            startService(context)

            processQueue(context)
        }
    }


    fun pause(
        context: Context,
        id: Long
    ) {

        activeJobs[id]?.cancel()

        activeJobs.remove(id)

        updateSpeed(
            id,
            0L
        )

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@launch

            db.downloadDao().updateRecord(
                record.copy(
                    status = "PAUSED"
                )
            )

            processQueue(context)
        }
    }


    fun cancel(
        context: Context,
        id: Long
    ) {

        activeJobs[id]?.cancel()

        activeJobs.remove(id)

        updateSpeed(
            id,
            0L
        )

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@launch

            val tempFile =
                File(
                    context.cacheDir,
                    "temp_${record.id}_${record.fileName}"
                )

            if (tempFile.exists()) {
                tempFile.delete()
            }

            File(
                context.cacheDir,
                "dash_v_${record.id}_${record.fileName}"
            ).delete()

            File(
                context.cacheDir,
                "dash_a_${record.id}_${record.fileName}"
            ).delete()

            db.downloadDao()
                .deleteRecord(record)

            processQueue(context)
        }
    }


    private fun updateSpeed(
        id: Long,
        speed: Long
    ) {

        val current =
            _downloadSpeeds.value.toMutableMap()

        if (speed == 0L) {
            current.remove(id)
        } else {
            current[id] = speed
        }

        _downloadSpeeds.value = current
    }


    private fun startService(
        context: Context
    ) {

        try {

            val intent =
                Intent(
                    context,
                    DownloadService::class.java
                )

            if (
                android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.O
            ) {

                context.startForegroundService(intent)

            } else {

                context.startService(intent)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start service",
                e
            )
        }
    }


    private fun processQueue(
        context: Context
    ) {

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(context)

            val downloadingCount =
                db.downloadDao()
                    .getCountByStatus("DOWNLOADING")

            if (
                downloadingCount <
                MAX_CONCURRENT
            ) {

                val needed =
                    MAX_CONCURRENT -
                            downloadingCount

                val nextInQueue =
                    db.downloadDao()
                        .getNextQueued(needed)

                nextInQueue.forEach { record ->

                    startDownload(
                        context,
                        record
                    )
                }
            }
        }
    }


    private fun startDownload(
        context: Context,
        record: DownloadRecord
    ) {

        if (
            activeJobs.containsKey(record.id)
        ) {
            return
        }

        val job =
            scope.launch {

                val db =
                    DownloadDatabase
                        .getDatabase(context)

                db.downloadDao().updateRecord(
                    record.copy(
                        status = "DOWNLOADING",
                        errorMessage = null
                    )
                )

                try {

                    if (
                        record.dashAudioUrl != null
                    ) {

                        performDashDownload(
                            context,
                            record.id
                        )

                    } else {

                        performDownload(
                            context,
                            record.id
                        )
                    }

                } catch (
                    e: CancellationException
                ) {

                    Log.d(
                        TAG,
                        "Download ${record.id} paused/cancelled"
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Download ${record.id} failed",
                        e
                    )

                    val errorMsg =
                        when (e) {

                            is SocketTimeoutException ->
                                "Request timed out. Please try again."

                            is IOException ->
                                "Network error: ${e.message}"

                            else ->
                                e.message
                                    ?: "Download failed"
                        }

                    db.downloadDao().updateRecord(
                        record.copy(
                            status = "FAILED",
                            errorMessage = errorMsg
                        )
                    )

                } finally {

                    activeJobs.remove(
                        record.id
                    )

                    updateSpeed(
                        record.id,
                        0L
                    )

                    processQueue(context)
                }
            }

        activeJobs[record.id] = job
    }


    /**
     * Original progressive download logic.
     * Kept intact for 360p and normal audio downloads.
     */
    private suspend fun performDownload(
        context: Context,
        id: Long
    ): Unit = withContext(Dispatchers.IO) {

        var retryCount = 0
        var completed = false

        while (
            !completed &&
            retryCount < 3
        ) {

            val db =
                DownloadDatabase
                    .getDatabase(context)

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@withContext

            val downloadUrl =
                record.downloadUrl
                    ?: throw Exception(
                        "Missing download URL"
                    )

            val tempFile =
                File(
                    context.cacheDir,
                    "temp_${record.id}_${record.fileName}"
                )

            val existingBytes =
                if (tempFile.exists()) {
                    tempFile.length()
                } else {
                    0L
                }

            val requestBuilder =
                Request.Builder()
                    .url(downloadUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                    .header(
                        "Accept-Encoding",
                        "identity"
                    )

            if (
                existingBytes > 0 ||
                record.mediaType == "audio"
            ) {

                requestBuilder.header(
                    "Range",
                    "bytes=$existingBytes-"
                )
            }

            val request =
                requestBuilder.build()

            try {

                client.newCall(request)
                    .execute()
                    .use { response ->

                        if (
                            !response.isSuccessful &&
                            response.code != 206
                        ) {

                            if (
                                response.code == 416
                            ) {

                                tempFile.delete()

                                retryCount++

                                return@use
                            }

                            throw Exception(
                                "Server error HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body
                                ?: throw Exception(
                                    "Empty response body"
                                )

                        val totalBytes =
                            if (
                                response.code == 206
                            ) {

                                val rangeHeader =
                                    response.header(
                                        "Content-Range"
                                    )

                                rangeHeader
                                    ?.substringAfterLast("/")
                                    ?.toLongOrNull()
                                    ?: (
                                            existingBytes +
                                                    body.contentLength()
                                            )

                            } else {

                                body.contentLength()
                            }

                        db.downloadDao()
                            .updateRecord(
                                record.copy(
                                    totalBytes =
                                        totalBytes,
                                    downloadedBytes =
                                        if (
                                            response.code == 206
                                        ) {
                                            existingBytes
                                        } else {
                                            0L
                                        }
                                )
                            )

                        downloadToFile(
                            id = id,
                            response = response,
                            file = tempFile,
                            startOffset =
                                if (
                                    response.code == 206
                                ) {
                                    existingBytes
                                } else {
                                    0L
                                },
                            totalBytes = totalBytes,
                            context = context
                        )

                        val finalUri =
                            FileUtils
                                .saveTempFileToMediaStore(
                                    context,
                                    tempFile,
                                    record.fileName,
                                    record.mediaType
                                )

                        if (
                            finalUri != null
                        ) {

                            db.downloadDao()
                                .updateRecord(
                                    record.copy(
                                        status = "COMPLETED",
                                        totalBytes =
                                            totalBytes,
                                        downloadedBytes =
                                            totalBytes,
                                        fileUri =
                                            finalUri.toString(),
                                        errorMessage = null,
                                        timestamp =
                                            System.currentTimeMillis()
                                    )
                                )

                            tempFile.delete()

                            completed = true

                            Log.d(
                                TAG,
                                "Download SUCCESS for ID=$id"
                            )

                        } else {

                            throw Exception(
                                "Failed to save media to system gallery."
                            )
                        }
                    }

            } catch (e: Exception) {

                if (
                    e is CancellationException
                ) {
                    throw e
                }

                retryCount++

                if (
                    retryCount < 3 &&
                    (
                            e is SocketTimeoutException ||
                                    e is IOException
                            )
                ) {

                    delay(
                        2000L * retryCount
                    )

                } else {

                    throw e
                }
            }
        }
    }


    /**
     * DASH download:
     *
     * 1. Download video using parallel Range requests.
     * 2. Download audio.
     * 3. Mux video + audio.
     */
    private suspend fun performDashDownload(
        context: Context,
        id: Long
    ): Unit = withContext(Dispatchers.IO) {

        Log.d(
            TAG,
            "[TRACE] DASH Download Start for ID=$id"
        )

        val db =
            DownloadDatabase
                .getDatabase(context)

        val record =
            db.downloadDao()
                .getRecordById(id)
                ?: return@withContext

        val videoUrl =
            record.downloadUrl
                ?: throw Exception(
                    "Missing video URL"
                )

        val audioUrl =
            record.dashAudioUrl
                ?: throw Exception(
                    "Missing audio URL"
                )

        val videoTemp =
            File(
                context.cacheDir,
                "dash_v_${record.id}_${record.fileName}"
            )

        val audioTemp =
            File(
                context.cacheDir,
                "dash_a_${record.id}_${record.fileName}"
            )

        val finalTemp =
            File(
                context.cacheDir,
                "temp_${record.id}_${record.fileName}"
            )


        // ---------------------------------------------------------
        // 1. VIDEO
        // ---------------------------------------------------------

        Log.d(
            TAG,
            "[TRACE] DASH parallel video download start for ID=$id"
        )

        downloadDashVideoParallel(
            id = id,
            url = videoUrl,
            file = videoTemp,
            context = context
        )

        Log.d(
            TAG,
            "[TRACE] DASH video download complete for ID=$id. Final temp size: ${videoTemp.length()} bytes"
        )


        // ---------------------------------------------------------
        // 2. AUDIO
        // ---------------------------------------------------------

        Log.d(
            TAG,
            "[TRACE] DASH audio download start for ID=$id"
        )

        downloadUrlToFile(
            id = id,
            url = audioUrl,
            file = audioTemp,
            context = context,
            note = "Downloading Audio..."
        )

        Log.d(
            TAG,
            "[TRACE] DASH audio download complete for ID=$id. Final temp size: ${audioTemp.length()} bytes"
        )


        // ---------------------------------------------------------
        // 3. MUX
        // ---------------------------------------------------------

        Log.d(
            TAG,
            "[TRACE] muxVideoAudio start for ID=$id"
        )

        val muxSuccess =
            FileUtils.muxVideoAudio(
                videoTemp,
                audioTemp,
                finalTemp
            )

        Log.d(
            TAG,
            "[TRACE] muxVideoAudio complete for ID=$id. success=$muxSuccess"
        )


        if (muxSuccess) {

            Log.d(
                TAG,
                "[TRACE] final file save start for ID=$id"
            )

            val finalUri =
                FileUtils.saveTempFileToMediaStore(
                    context,
                    finalTemp,
                    record.fileName,
                    record.mediaType
                )

            Log.d(
                TAG,
                "[TRACE] final file save complete for ID=$id. uri=$finalUri"
            )

            if (
                finalUri != null
            ) {

                val finalSize =
                    finalTemp.length()

                db.downloadDao().updateRecord(
                    record.copy(
                        status = "COMPLETED",
                        totalBytes = finalSize,
                        downloadedBytes = finalSize,
                        fileUri = finalUri.toString(),
                        errorMessage = null,
                        timestamp =
                            System.currentTimeMillis()
                    )
                )

                videoTemp.delete()
                audioTemp.delete()
                finalTemp.delete()

                Log.d(
                    TAG,
                    "[TRACE] database COMPLETED update SUCCESS for ID=$id"
                )

                Log.d(
                    TAG,
                    "DASH Download SUCCESS for ID=$id"
                )

            } else {

                throw Exception(
                    "Failed to save muxed media."
                )
            }

        } else {

            throw Exception(
                "Failed to mux DASH video and audio."
            )
        }
    }


    /**
     * Downloads DASH video using 4 parallel HTTP Range requests.
     */
    private suspend fun downloadDashVideoParallel(
        id: Long,
        url: String,
        file: File,
        context: Context
    ) = coroutineScope {

        val db =
            DownloadDatabase
                .getDatabase(context)

        Log.d(
            TAG,
            "[TRACE] DASH probing video size for ID=$id"
        )

        val totalBytes =
            getDashContentLength(
                id,
                url
            )

        if (
            totalBytes <= 0L
        ) {

            throw Exception(
                "Unable to determine DASH video size"
            )
        }

        Log.d(
            TAG,
            "[TRACE] DASH video total size for ID=$id: $totalBytes bytes"
        )

        val record =
            db.downloadDao()
                .getRecordById(id)
                ?: return@coroutineScope

        db.downloadDao().updateRecord(
            record.copy(
                totalBytes = totalBytes,
                downloadedBytes = 0L
            )
        )


        // Always start with a correctly sized file.
        if (
            !file.exists() ||
            file.length() != totalBytes
        ) {

            RandomAccessFile(
                file,
                "rw"
            ).use { raf ->

                raf.setLength(
                    totalBytes
                )
            }
        }


        val totalDownloaded =
            AtomicLong(0L)

        val speedWindowStart =
            AtomicLong(
                System.currentTimeMillis()
            )

        val speedWindowBytes =
            AtomicLong(0L)


        val chunkSize =
            (
                    totalBytes +
                            DASH_CHUNK_COUNT -
                            1
                    ) /
                    DASH_CHUNK_COUNT


        Log.d(
            TAG,
            "[TRACE] DASH chunk size for ID=$id: $chunkSize bytes"
        )


        val jobs =
            (0 until DASH_CHUNK_COUNT)
                .map { index ->

                    val start =
                        index * chunkSize

                    val end =
                        minOf(
                            totalBytes - 1,
                            start +
                                    chunkSize -
                                    1
                        )

                    if (
                        start > end
                    ) {
                        null
                    } else {

                        async(Dispatchers.IO) {

                            downloadDashRange(
                                id = id,
                                url = url,
                                file = file,
                                start = start,
                                end = end,
                                context = context,
                                totalBytes = totalBytes,
                                totalDownloaded =
                                    totalDownloaded,
                                speedWindowStart =
                                    speedWindowStart,
                                speedWindowBytes =
                                    speedWindowBytes
                            )
                        }
                    }
                }
                .filterNotNull()


        try {

            jobs.awaitAll()

        } catch (
            e: CancellationException
        ) {

            jobs.forEach {
                it.cancel()
            }

            throw e
        }


        if (
            file.length() != totalBytes
        ) {

            throw IOException(
                "DASH video size mismatch. Expected $totalBytes bytes, got ${file.length()} bytes."
            )
        }


        db.downloadDao()
            .updateProgress(
                id,
                totalBytes
            )

        updateSpeed(
            id,
            0L
        )

        Log.d(
            TAG,
            "[TRACE] DASH parallel video download finished for ID=$id: $totalBytes bytes"
        )
    }


    /**
     * Gets the total DASH video size using a one-byte Range request.
     */
    private fun getDashContentLength(
        id: Long,
        url: String
    ): Long {

        val request =
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                .header(
                    "Accept-Encoding",
                    "identity"
                )
                .header(
                    "Range",
                    "bytes=0-0"
                )
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                Log.d(
                    TAG,
                    "[TRACE] DASH size probe response for ID=$id: code=${response.code}"
                )

                if (
                    response.code != 206 &&
                    !response.isSuccessful
                ) {

                    throw IOException(
                        "DASH size probe failed: HTTP ${response.code}"
                    )
                }

                val contentRange =
                    response.header(
                        "Content-Range"
                    )

                Log.d(
                    TAG,
                    "[TRACE] DASH Content-Range for ID=$id: $contentRange"
                )

                if (
                    contentRange != null
                ) {

                    val total =
                        contentRange
                            .substringAfterLast("/")
                            .toLongOrNull()

                    if (
                        total != null &&
                        total > 0L
                    ) {
                        return total
                    }
                }

                val length =
                    response.body
                        ?.contentLength()
                        ?: -1L

                if (
                    length > 1L
                ) {
                    return length
                }

                throw IOException(
                    "DASH server did not provide content length"
                )
            }
    }


    /**
     * Downloads one DASH byte range into its correct position.
     */
    private suspend fun downloadDashRange(
        id: Long,
        url: String,
        file: File,
        start: Long,
        end: Long,
        context: Context,
        totalBytes: Long,
        totalDownloaded: AtomicLong,
        speedWindowStart: AtomicLong,
        speedWindowBytes: AtomicLong
    ) {

        var retryCount = 0

        while (
            retryCount < 3
        ) {

            currentCoroutineContext()
                .ensureActive()

            val request =
                Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                    .header(
                        "Accept-Encoding",
                        "identity"
                    )
                    .header(
                        "Range",
                        "bytes=$start-$end"
                    )
                    .build()

            try {

                client.newCall(request)
                    .execute()
                    .use { response ->

                        Log.d(
                            TAG,
                            "[TRACE] DASH Range $start-$end response for ID=$id: HTTP ${response.code}"
                        )

                        if (
                            response.code != 206
                        ) {

                            throw IOException(
                                "DASH Range request failed: HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body
                                ?: throw IOException(
                                    "Empty DASH range response"
                                )

                        val input =
                            body.byteStream()

                        RandomAccessFile(
                            file,
                            "rw"
                        ).use { raf ->

                            raf.seek(start)

                            val buffer =
                                ByteArray(
                                    64 * 1024
                                )

                            var position =
                                start

                            while (
                                position <= end
                            ) {

                                currentCoroutineContext()
                                    .ensureActive()

                                val remaining =
                                    end -
                                            position +
                                            1

                                val maxRead =
                                    minOf(
                                        buffer.size
                                            .toLong(),
                                        remaining
                                    ).toInt()

                                val bytesRead =
                                    input.read(
                                        buffer,
                                        0,
                                        maxRead
                                    )

                                if (
                                    bytesRead == -1
                                ) {
                                    break
                                }

                                if (
                                    bytesRead == 0
                                ) {

                                    yield()

                                    continue
                                }

                                raf.write(
                                    buffer,
                                    0,
                                    bytesRead
                                )

                                position +=
                                    bytesRead

                                totalDownloaded
                                    .addAndGet(
                                        bytesRead.toLong()
                                    )

                                speedWindowBytes
                                    .addAndGet(
                                        bytesRead.toLong()
                                    )

                                val now =
                                    System.currentTimeMillis()

                                val windowStart =
                                    speedWindowStart.get()

                                if (
                                    now -
                                    windowStart >=
                                    1000
                                ) {

                                    val elapsed =
                                        now -
                                                windowStart

                                    val bytes =
                                        speedWindowBytes
                                            .getAndSet(
                                                0L
                                            )

                                    if (
                                        speedWindowStart
                                            .compareAndSet(
                                                windowStart,
                                                now
                                            )
                                    ) {

                                        val speed =
                                            if (
                                                elapsed > 0
                                            ) {

                                                bytes *
                                                        1000L /
                                                        elapsed

                                            } else {
                                                0L
                                            }

                                        updateSpeed(
                                            id,
                                            speed
                                        )

                                        val current =
                                            totalDownloaded
                                                .get()

                                        val progressDb =
                                            DownloadDatabase
                                                .getDatabase(
                                                    context
                                                )

                                        progressDb
                                            .downloadDao()
                                            .updateProgress(
                                                id,
                                                minOf(
                                                    current,
                                                    totalBytes
                                                )
                                            )

                                        Log.d(
                                            TAG,
                                            "[TRACE] DASH parallel progress ID=$id: $current / $totalBytes bytes (${speed / 1024} KB/s)"
                                        )
                                    }
                                }
                            }

                            val expectedBytes =
                                end -
                                        start +
                                        1

                            val downloadedBytes =
                                position -
                                        start

                            if (
                                downloadedBytes !=
                                expectedBytes
                            ) {

                                throw IOException(
                                    "Incomplete DASH range. Expected $expectedBytes bytes, got $downloadedBytes bytes."
                                )
                            }
                        }
                    }

                return

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                retryCount++

                Log.e(
                    TAG,
                    "[TRACE] DASH Range $start-$end failed for ID=$id. Retry $retryCount",
                    e
                )

                if (
                    retryCount >= 3
                ) {

                    throw e
                }

                delay(
                    1000L *
                            retryCount
                )
            }
        }
    }


    /**
     * DASH audio download.
     *
     * Uses normal sequential download with Range resume.
     */
    private suspend fun downloadUrlToFile(
        id: Long,
        url: String,
        file: File,
        context: Context,
        note: String
    ) {

        Log.d(
            TAG,
            "[TRACE] Requesting URL for ID=$id: $url"
        )

        val existingBytes =
            if (file.exists()) {
                file.length()
            } else {
                0L
            }

        val requestBuilder =
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                .header(
                    "Accept-Encoding",
                    "identity"
                )

        if (
            existingBytes > 0L
        ) {

            requestBuilder.header(
                "Range",
                "bytes=$existingBytes-"
            )
        }

        val request =
            requestBuilder.build()

        try {

            client.newCall(request)
                .execute()
                .use { response ->

                    Log.d(
                        TAG,
                        "[TRACE] HTTP Response for ID=$id: code=${response.code}, message=${response.message}"
                    )

                    if (
                        !response.isSuccessful &&
                        response.code != 206
                    ) {

                        throw Exception(
                            "Server error HTTP ${response.code}"
                        )
                    }

                    val body =
                        response.body
                            ?: throw Exception(
                                "Empty body"
                            )

                    val total =
                        if (
                            response.code == 206
                        ) {

                            response.header(
                                "Content-Range"
                            )
                                ?.substringAfterLast("/")
                                ?.toLongOrNull()
                                ?: (
                                        existingBytes +
                                                body.contentLength()
                                        )

                        } else {

                            body.contentLength()
                        }

                    Log.d(
                        TAG,
                        "[TRACE] Content-Length for ID=$id: $total"
                    )

                    downloadToFile(
                        id = id,
                        response = response,
                        file = file,
                        startOffset =
                            if (
                                response.code == 206
                            ) {
                                existingBytes
                            } else {
                                0L
                            },
                        totalBytes = total,
                        context = context
                    )
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "[TRACE] Exception during downloadUrlToFile for ID=$id",
                e
            )

            throw e
        }
    }


    private suspend fun downloadToFile(
        id: Long,
        response: okhttp3.Response,
        file: File,
        startOffset: Long,
        totalBytes: Long,
        context: Context
    ) {

        val db =
            DownloadDatabase
                .getDatabase(context)

        val body =
            response.body
                ?: return

        val randomAccessFile =
            RandomAccessFile(
                file,
                "rw"
            )

        val fileName =
            file.name

        try {

            randomAccessFile.seek(
                startOffset
            )

            val inputStream =
                body.byteStream()

            val buffer =
                ByteArray(65536)

            var bytesRead: Int

            var lastUpdate =
                System.currentTimeMillis()

            var downloadedInInterval =
                0L

            var currentDownloaded =
                startOffset

            Log.d(
                TAG,
                "[TRACE] Starting stream read to $fileName for ID=$id"
            )

            while (
                inputStream.read(buffer)
                    .also { bytesRead = it } != -1
            ) {

                currentCoroutineContext()
                    .ensureActive()

                randomAccessFile.write(
                    buffer,
                    0,
                    bytesRead
                )

                currentDownloaded +=
                    bytesRead

                downloadedInInterval +=
                    bytesRead

                val now =
                    System.currentTimeMillis()

                if (
                    now -
                    lastUpdate >=
                    1000
                ) {

                    val speed =
                        (
                                downloadedInInterval *
                                        1000
                                ) /
                                (
                                        now -
                                                lastUpdate
                                        )

                    updateSpeed(
                        id,
                        speed
                    )

                    db.downloadDao()
                        .updateProgress(
                            id,
                            currentDownloaded
                        )

                    Log.d(
                        TAG,
                        "[TRACE] DASH download progress for $fileName (ID=$id): $currentDownloaded / $totalBytes bytes (${speed / 1024} KB/s)"
                    )

                    lastUpdate =
                        now

                    downloadedInInterval =
                        0L
                }
            }

            db.downloadDao()
                .updateProgress(
                    id,
                    currentDownloaded
                )

            Log.d(
                TAG,
                "[TRACE] Finished stream read to $fileName for ID=$id. Total read: $currentDownloaded bytes"
            )

        } finally {

            randomAccessFile.close()
        }
    }
}