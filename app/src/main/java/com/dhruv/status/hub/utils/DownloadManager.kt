package com.dhruv.status.hub.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.dhruv.status.hub.data.DownloadDatabase
import com.dhruv.status.hub.data.DownloadRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
 *
 * Facebook downloads now use the direct signed CDN URL returned by
 * FastSaverClient. No backend-specific code is required here.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"

    private const val MAX_CONCURRENT = 3
    private const val VIDEO_CHUNKS = 4

    private const val RETRIES_BEFORE_WAIT = 3
    private const val INITIAL_RETRY_DELAY_MS = 2_000L
    private const val MAX_RETRY_DELAY_MS = 30_000L

    private const val BUFFER_SIZE = 131_072

    private val scope =
        CoroutineScope(
            Dispatchers.IO + SupervisorJob()
        )

    private val activeJobs =
        ConcurrentHashMap<Long, Job>()

    private val runningIds =
        ConcurrentHashMap.newKeySet<Long>()

    private val isInitialized =
        AtomicBoolean(false)

    private val _downloadSpeeds =
        MutableStateFlow<Map<Long, Long>>(emptyMap())

    val downloadSpeeds =
        _downloadSpeeds.asStateFlow()

    private val client =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private var networkCallback:
            ConnectivityManager.NetworkCallback? = null

    /**
     * Initializes the download manager.
     *
     * Downloads that were interrupted while the process was killed are
     * returned to QUEUED so they can resume from their existing temp files.
     */
    fun init(context: Context) {
        if (!isInitialized.compareAndSet(false, true)) return

        val appContext = context.applicationContext

        registerNetworkCallback(appContext)

        scope.launch {
            val db = DownloadDatabase.getDatabase(appContext)

            db.downloadDao()
                .getDownloadsByStatus("DOWNLOADING")
                .forEach { record ->
                    db.downloadDao().updateRecord(
                        record.copy(
                            status = "QUEUED",
                            errorMessage = null
                        )
                    )
                }

            db.downloadDao()
                .getDownloadsByStatus("PROCESSING")
                .forEach { record ->
                    db.downloadDao().updateRecord(
                        record.copy(
                            status = "QUEUED",
                            errorMessage = null
                        )
                    )
                }

            processQueue(appContext)
        }
    }

    /**
     * Watches for a validated internet connection.
     */
    private fun registerNetworkCallback(context: Context) {

        val cm =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        try {

            networkCallback =
                object : ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(network: Network) {
                        processQueue(context)
                    }
                }

            val request =
                NetworkRequest.Builder()
                    .addCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    .build()

            cm.registerNetworkCallback(
                request,
                networkCallback!!
            )

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to register network callback",
                e
            )
        }
    }

    /**
     * Adds a new download to the queue.
     */
    fun enqueue(
        context: Context,
        record: DownloadRecord
    ) {

        val appContext =
            context.applicationContext

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(
                    appContext
                )

            db.downloadDao()
                .insertRecord(record)

            startService(appContext)

            processQueue(appContext)
        }
    }

    /**
     * Resumes a paused/failed download.
     *
     * Existing temporary data is deliberately preserved.
     */
    fun resume(
        context: Context,
        id: Long
    ) {

        val appContext =
            context.applicationContext

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(
                    appContext
                )

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

            startService(appContext)

            processQueue(appContext)
        }
    }

    /**
     * Pauses a download.
     *
     * Temporary files are preserved so resume can continue.
     */
    fun pause(
        context: Context,
        id: Long
    ) {

        activeJobs[id]?.cancel()
        activeJobs.remove(id)

        updateSpeed(id, 0L)

        val appContext =
            context.applicationContext

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(
                    appContext
                )

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@launch

            db.downloadDao().updateRecord(
                record.copy(
                    status = "PAUSED"
                )
            )

            processQueue(appContext)
        }
    }

    /**
     * Cancels a download and removes its temporary files.
     */
    fun cancel(
        context: Context,
        id: Long
    ) {

        activeJobs[id]?.cancel()
        activeJobs.remove(id)

        updateSpeed(id, 0L)

        val appContext =
            context.applicationContext

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(
                    appContext
                )

            val record =
                db.downloadDao()
                    .getRecordById(id)
                    ?: return@launch

            deleteTemporaryFiles(
                appContext,
                record
            )

            db.downloadDao()
                .deleteRecord(record)

            processQueue(appContext)
        }
    }

    /**
     * Deletes temporary files associated with a download.
     */
    private fun deleteTemporaryFiles(
        context: Context,
        record: DownloadRecord
    ) {

        File(
            context.cacheDir,
            "temp_${record.id}_${record.fileName}"
        ).delete()

        File(
            context.cacheDir,
            "dash_v_${record.id}_${record.fileName}"
        ).delete()

        File(
            context.cacheDir,
            "dash_a_${record.id}_${record.fileName}"
        ).delete()

        for (i in 0 until VIDEO_CHUNKS) {

            File(
                context.cacheDir,
                "dash_v_${record.id}_chunk_$i"
            ).delete()
        }
    }

    private fun updateSpeed(
        id: Long,
        speed: Long
    ) {

        val current =
            _downloadSpeeds.value.toMutableMap()

        if (speed <= 0L) {
            current.remove(id)
        } else {
            current[id] = speed
        }

        _downloadSpeeds.value = current
    }

    /**
     * Starts the foreground download service.
     */
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
                "Unable to start DownloadService",
                e
            )
        }
    }

    /**
     * Starts queued downloads while respecting the maximum concurrency.
     */
    private fun processQueue(
        context: Context
    ) {

        scope.launch {

            val db =
                DownloadDatabase.getDatabase(
                    context
                )

            val active =
                db.downloadDao()
                    .getCountByStatus("DOWNLOADING") +
                        db.downloadDao()
                            .getCountByStatus("PROCESSING")

            val availableSlots =
                MAX_CONCURRENT - active

            if (availableSlots <= 0) return@launch

            db.downloadDao()
                .getNextQueued(availableSlots)
                .forEach { record ->

                    startDownload(
                        context,
                        record
                    )
                }
        }
    }

    /**
     * Checks whether Android currently has a validated internet connection.
     */
    private fun hasValidatedInternet(
        context: Context
    ): Boolean {

        return try {

            val cm =
                context.getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            val network =
                cm.activeNetwork
                    ?: return false

            val capabilities =
                cm.getNetworkCapabilities(network)
                    ?: return false

            capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) &&
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )

        } catch (e: Exception) {

            false
        }
    }

    /**
     * Waits silently until a validated internet connection exists.
     */
    private suspend fun waitForInternet(
        context: Context
    ) {

        while (
            currentCoroutineContext().isActive
        ) {

            if (
                hasValidatedInternet(context)
            ) {
                return
            }

            delay(2_000L)
        }

        currentCoroutineContext()
            .ensureActive()
    }

    /**
     * Network failures that can safely be retried.
     */
    private fun isRetryableNetworkError(
        e: Exception
    ): Boolean {

        return e is SocketTimeoutException ||
                e is IOException
    }

    /**
     * HTTP statuses that should be retried.
     */
    private fun isRetryableHttpCode(
        code: Int
    ): Boolean {

        return code == 408 ||
                code == 429 ||
                code in 500..599
    }

    /**
     * Applies appropriate browser-style headers for CDN requests.
     */
    private fun applyPlatformHeaders(
        builder: Request.Builder,
        platform: String
    ) {

        val lower =
            platform.lowercase()

        val browserUA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/121.0.0.0 Safari/537.36"

        when {

            lower.contains("facebook") -> {

                builder.header(
                    "User-Agent",
                    browserUA
                )

                builder.header(
                    "Referer",
                    "https://www.facebook.com/"
                )
            }

            lower.contains("instagram") -> {

                builder.header(
                    "User-Agent",
                    browserUA
                )

                builder.header(
                    "Referer",
                    "https://www.instagram.com/"
                )
            }

            else -> {

                builder.header(
                    "User-Agent",
                    browserUA
                )
            }
        }
    }

    /**
     * Starts one download.
     *
     * Retry handling is intentionally invisible to the UI.
     */
    private fun startDownload(
        context: Context,
        record: DownloadRecord
    ) {

        val id = record.id

        if (!runningIds.add(id)) {
            return
        }

        val job =
            scope.launch {

                val db =
                    DownloadDatabase.getDatabase(
                        context
                    )

                try {

                    val initialRecord =
                        db.downloadDao()
                            .getRecordById(id)
                            ?: return@launch

                    db.downloadDao().updateRecord(
                        initialRecord.copy(
                            status = "DOWNLOADING",
                            errorMessage = null
                        )
                    )

                    val sharedProgress =
                        AtomicLong(
                            initialRecord.downloadedBytes
                        )

                    val sharedSpeed =
                        AtomicLong(0L)

                    /*
                     * Progress monitor.
                     *
                     * Facebook now behaves exactly like every other
                     * direct download because FastSaver gives us the
                     * actual CDN URL.
                     */
                    val monitorJob =
                        launch {

                            var lastTime =
                                System.currentTimeMillis()

                            while (isActive) {

                                delay(1_000L)

                                val now =
                                    System.currentTimeMillis()

                                val bytes =
                                    sharedSpeed
                                        .getAndSet(0L)

                                val elapsed =
                                    now - lastTime

                                if (elapsed > 0L) {

                                    updateSpeed(
                                        id,
                                        bytes *
                                                1000L /
                                                elapsed
                                    )
                                }

                                lastTime = now

                                val currentRecord =
                                    db.downloadDao()
                                        .getRecordById(id)

                                if (
                                    currentRecord != null &&
                                    currentRecord.status ==
                                    "DOWNLOADING"
                                ) {

                                    val total =
                                        if (
                                            currentRecord.totalBytes > 0L
                                        ) {
                                            currentRecord.totalBytes
                                        } else {
                                            Long.MAX_VALUE
                                        }

                                    db.downloadDao()
                                        .updateProgress(
                                            id,
                                            minOf(
                                                sharedProgress.get(),
                                                total
                                            )
                                        )
                                }
                            }
                        }

                    var completed = false

                    var lastError:
                            Exception? = null

                    var retryAttempt = 0

                    var retryDelay =
                        INITIAL_RETRY_DELAY_MS

                    while (
                        isActive &&
                        !completed
                    ) {

                        val freshRecord =
                            db.downloadDao()
                                .getRecordById(id)
                                ?: break

                        if (
                            freshRecord.status ==
                            "PAUSED" ||
                            freshRecord.status ==
                            "CANCELLED"
                        ) {
                            break
                        }

                        try {

                            /*
                             * If the network disappears, this waits here.
                             * No retry status is written to Room.
                             */
                            waitForInternet(context)

                            /*
                             * Recalculate progress from the actual
                             * temporary files before every retry.
                             */
                            refreshProgressFromDisk(
                                context,
                                freshRecord,
                                sharedProgress
                            )

                            if (
                                freshRecord.dashAudioUrl != null
                            ) {

                                performDashDownload(
                                    context,
                                    id,
                                    sharedProgress,
                                    sharedSpeed
                                )

                            } else {

                                performSingleDownload(
                                    context,
                                    id,
                                    sharedProgress,
                                    sharedSpeed
                                )
                            }

                            completed = true

                            /*
                             * Successful attempt resets retry state.
                             */
                            retryAttempt = 0
                            retryDelay =
                                INITIAL_RETRY_DELAY_MS

                        } catch (
                            e: CancellationException
                        ) {

                            throw e

                        } catch (
                            e: HttpDownloadException
                        ) {

                            lastError = e

                            if (
                                !isRetryableHttpCode(
                                    e.code
                                )
                            ) {
                                break
                            }

                            retryAttempt++

                            delay(retryDelay)

                            retryDelay =
                                minOf(
                                    retryDelay * 2L,
                                    MAX_RETRY_DELAY_MS
                                )

                            if (
                                retryAttempt >=
                                RETRIES_BEFORE_WAIT
                            ) {

                                retryAttempt = 0

                                retryDelay =
                                    INITIAL_RETRY_DELAY_MS

                                waitForInternet(
                                    context
                                )
                            }

                        } catch (
                            e: Exception
                        ) {

                            lastError = e

                            if (
                                !isRetryableNetworkError(
                                    e
                                )
                            ) {
                                break
                            }

                            retryAttempt++

                            if (
                                retryAttempt <=
                                RETRIES_BEFORE_WAIT
                            ) {

                                delay(retryDelay)

                                retryDelay =
                                    minOf(
                                        retryDelay * 2L,
                                        MAX_RETRY_DELAY_MS
                                    )

                            } else {

                                retryAttempt = 0

                                retryDelay =
                                    INITIAL_RETRY_DELAY_MS

                                waitForInternet(
                                    context
                                )
                            }
                        }
                    }

                    if (
                        !completed &&
                        isActive
                    ) {

                        val finalRecord =
                            db.downloadDao()
                                .getRecordById(id)

                        if (
                            finalRecord != null &&
                            finalRecord.status !=
                            "PAUSED" &&
                            finalRecord.status !=
                            "CANCELLED"
                        ) {

                            val message =
                                when (lastError) {

                                    is HttpDownloadException ->
                                        "Server error ${lastError.code}."

                                    is SocketTimeoutException ->
                                        "Connection timed out."

                                    is IOException ->
                                        "Network error. Check connection."

                                    else ->
                                        lastError?.message
                                            ?: "Download failed."
                                }

                            db.downloadDao()
                                .updateRecord(
                                    finalRecord.copy(
                                        status = "FAILED",
                                        errorMessage = message
                                    )
                                )
                        }
                    }

                    monitorJob.cancel()

                } finally {

                    activeJobs.remove(id)

                    runningIds.remove(id)

                    updateSpeed(
                        id,
                        0L
                    )

                    processQueue(context)
                }
            }

        activeJobs[id] = job
    }

    /**
     * Restores progress from temporary files.
     */
    private fun refreshProgressFromDisk(
        context: Context,
        record: DownloadRecord,
        progress: AtomicLong
    ) {

        if (
            record.dashAudioUrl != null
        ) {

            val audioFile =
                File(
                    context.cacheDir,
                    "dash_a_${record.id}_${record.fileName}"
                )

            val videoFinal =
                File(
                    context.cacheDir,
                    "dash_v_${record.id}_${record.fileName}"
                )

            var done = 0L

            if (
                videoFinal.exists()
            ) {

                done += videoFinal.length()

            } else {

                for (
                i in 0 until VIDEO_CHUNKS
                ) {

                    val chunk =
                        File(
                            context.cacheDir,
                            "dash_v_${record.id}_chunk_$i"
                        )

                    if (chunk.exists()) {
                        done += chunk.length()
                    }
                }
            }

            if (audioFile.exists()) {
                done += audioFile.length()
            }

            progress.set(done)

        } else {

            /*
             * For Facebook FastSaver downloads, downloadUrl is already
             * the signed CDN URL. Therefore the normal temporary MP4
             * is the source of truth for progress.
             */
            val tempFile =
                File(
                    context.cacheDir,
                    "temp_${record.id}_${record.fileName}"
                )

            progress.set(
                if (tempFile.exists()) {
                    tempFile.length()
                } else {
                    0L
                }
            )
        }
    }

    /**
     * Downloads a normal single-file media URL.
     *
     * This includes Facebook URLs resolved by FastSaverClient.
     */
    private suspend fun performSingleDownload(
        context: Context,
        id: Long,
        progress: AtomicLong,
        speed: AtomicLong
    ) = withContext(Dispatchers.IO) {

        val db =
            DownloadDatabase.getDatabase(
                context
            )

        var record =
            db.downloadDao()
                .getRecordById(id)
                ?: return@withContext

        val url =
            record.downloadUrl
                ?: throw IOException(
                    "URL missing"
                )

        val tempFile =
            File(
                context.cacheDir,
                "temp_${record.id}_${record.fileName}"
            )

        /*
         * FastSaver returns a signed CDN URL.
         *
         * The signed URL is downloaded directly and is therefore
         * handled by the same resumable downloader as other media.
         */
        val size =
            getStreamSize(
                url,
                record.platform
            )

        if (size <= 0L) {
            throw IOException(
                "Cannot determine file size"
            )
        }

        /*
         * Never start from zero when a valid partial file exists.
         */
        val currentOffset =
            if (tempFile.exists()) {

                tempFile.length()
                    .coerceAtMost(size)

            } else {
                0L
            }

        progress.set(currentOffset)

        record =
            db.downloadDao()
                .getRecordById(id)
                ?: record

        record =
            record.copy(
                totalBytes = size,
                downloadedBytes = currentOffset
            )

        db.downloadDao()
            .updateRecord(record)

        /*
         * Resume the exact missing range.
         */
        if (
            currentOffset < size
        ) {

            downloadStreamSegment(
                url = url,
                file = tempFile,
                offset = currentOffset,
                endInclusive = size - 1L,
                progress = progress,
                speed = speed,
                chunkStart = 0L,
                platform = record.platform
            )
        }

        /*
         * Do not save an incomplete or corrupt file.
         */
        if (
            !tempFile.exists() ||
            tempFile.length() != size
        ) {

            throw IOException(
                "Downloaded file is incomplete."
            )
        }

        /*
         * Save the finished file into MediaStore.
         */
        val finalUri =
            FileUtils.saveTempFileToMediaStore(
                context,
                tempFile,
                record.fileName,
                record.mediaType
            )

        if (finalUri != null) {

            val freshRecord =
                db.downloadDao()
                    .getRecordById(id)
                    ?: record

            db.downloadDao()
                .updateRecord(
                    freshRecord.copy(
                        status = "COMPLETED",
                        totalBytes = size,
                        downloadedBytes = size,
                        fileUri = finalUri.toString(),
                        timestamp =
                            System.currentTimeMillis(),
                        errorMessage = null
                    )
                )

            /*
             * Only remove the temporary file after the
             * MediaStore operation and database update succeeded.
             */
            tempFile.delete()

        } else {

            throw IOException(
                "Failed to save media."
            )
        }
    }

    /**
     * Downloads DASH video + audio streams concurrently.
     *
     * This is retained for YouTube/other extractors that provide
     * separate video and audio URLs.
     */
    private suspend fun performDashDownload(
        context: Context,
        id: Long,
        progress: AtomicLong,
        speed: AtomicLong
    ) = withContext(Dispatchers.IO) {

        val db =
            DownloadDatabase.getDatabase(
                context
            )

        var record =
            db.downloadDao()
                .getRecordById(id)
                ?: return@withContext

        val videoUrl =
            record.downloadUrl
                ?: throw IOException(
                    "Video URL missing"
                )

        val audioUrl =
            record.dashAudioUrl
                ?: throw IOException(
                    "Audio URL missing"
                )

        val videoSize =
            getStreamSize(
                videoUrl,
                record.platform
            )

        val audioSize =
            getStreamSize(
                audioUrl,
                record.platform
            )

        if (
            videoSize <= 0L ||
            audioSize <= 0L
        ) {

            throw IOException(
                "Could not retrieve DASH stream info."
            )
        }

        val totalCombined =
            videoSize + audioSize

        val audioFile =
            File(
                context.cacheDir,
                "dash_a_${id}_${record.fileName}"
            )

        val videoFinalFile =
            File(
                context.cacheDir,
                "dash_v_${id}_${record.fileName}"
            )

        val tempFinal =
            File(
                context.cacheDir,
                "temp_${id}_${record.fileName}"
            )

        /*
         * Split the video into four independent byte ranges.
         */
        val chunkSize =
            (
                    videoSize +
                            VIDEO_CHUNKS -
                            1L
                    ) / VIDEO_CHUNKS

        val chunkFiles =
            (0 until VIDEO_CHUNKS)
                .map { i ->

                    File(
                        context.cacheDir,
                        "dash_v_${id}_chunk_$i"
                    )
                }

        val videoAlreadyConcatenated =
            videoFinalFile.exists() &&
                    videoFinalFile.length() ==
                    videoSize

        var initialDone =
            if (
                videoAlreadyConcatenated
            ) {

                videoSize

            } else {

                chunkFiles
                    .mapIndexed { index, file ->

                        if (file.exists()) {

                            val start =
                                index * chunkSize

                            val end =
                                minOf(
                                    videoSize - 1L,
                                    start +
                                            chunkSize -
                                            1L
                                )

                            file.length()
                                .coerceAtMost(
                                    end -
                                            start +
                                            1L
                                )

                        } else {
                            0L
                        }
                    }
                    .sum()
            }

        if (audioFile.exists()) {

            initialDone +=
                audioFile.length()
                    .coerceAtMost(
                        audioSize
                    )
        }

        progress.set(initialDone)

        record =
            db.downloadDao()
                .getRecordById(id)
                ?: record

        db.downloadDao()
            .updateRecord(
                record.copy(
                    totalBytes =
                        totalCombined,
                    downloadedBytes =
                        initialDone
                )
            )

        /*
         * Download video chunks and audio concurrently.
         */
        coroutineScope {

            if (!videoAlreadyConcatenated) {

                chunkFiles
                    .forEachIndexed { index, file ->

                        val start =
                            index * chunkSize

                        val end =
                            minOf(
                                videoSize - 1L,
                                start +
                                        chunkSize -
                                        1L
                            )

                        if (start <= end) {

                            launch {

                                val expected =
                                    end -
                                            start +
                                            1L

                                val existing =
                                    file.length()
                                        .coerceAtMost(
                                            expected
                                        )

                                if (
                                    existing <
                                    expected
                                ) {

                                    downloadStreamSegment(
                                        videoUrl,
                                        file,
                                        existing,
                                        end,
                                        progress,
                                        speed,
                                        start,
                                        record.platform
                                    )
                                }
                            }
                        }
                    }
            }

            launch {

                val existing =
                    audioFile.length()
                        .coerceAtMost(
                            audioSize
                        )

                if (
                    existing <
                    audioSize
                ) {

                    downloadStreamSegment(
                        audioUrl,
                        audioFile,
                        existing,
                        audioSize - 1L,
                        progress,
                        speed,
                        0L,
                        record.platform
                    )
                }
            }
        }

        /*
         * Verify every video chunk.
         */
        if (!videoAlreadyConcatenated) {

            chunkFiles
                .forEachIndexed { index, file ->

                    val start =
                        index * chunkSize

                    val end =
                        minOf(
                            videoSize - 1L,
                            start +
                                    chunkSize -
                                    1L
                        )

                    if (
                        !file.exists() ||
                        file.length() !=
                        (
                                end -
                                        start +
                                        1L
                                )
                    ) {

                        throw IOException(
                            "Video chunk $index incomplete."
                        )
                    }
                }

            /*
             * Concatenate video chunks in order.
             */
            videoFinalFile
                .outputStream()
                .use { out ->

                    chunkFiles.forEach { chunk ->

                        chunk.inputStream()
                            .use {
                                it.copyTo(out)
                            }

                        chunk.delete()
                    }
                }
        }

        /*
         * Verify audio.
         */
        if (
            !audioFile.exists() ||
            audioFile.length() !=
            audioSize
        ) {

            throw IOException(
                "Audio stream incomplete."
            )
        }

        /*
         * Mark processing state while muxing.
         */
        val processingRecord =
            db.downloadDao()
                .getRecordById(id)
                ?: record

        db.downloadDao()
            .updateRecord(
                processingRecord.copy(
                    status = "PROCESSING",
                    downloadedBytes =
                        totalCombined
                )
            )

        /*
         * Merge video and audio.
         */
        if (
            FileUtils.muxVideoAudio(
                videoFinalFile,
                audioFile,
                tempFinal
            )
        ) {

            val uri =
                FileUtils.saveTempFileToMediaStore(
                    context,
                    tempFinal,
                    record.fileName,
                    record.mediaType
                )

            if (uri != null) {

                val freshRecord =
                    db.downloadDao()
                        .getRecordById(id)
                        ?: record

                db.downloadDao()
                    .updateRecord(
                        freshRecord.copy(
                            status = "COMPLETED",
                            totalBytes =
                                totalCombined,
                            downloadedBytes =
                                totalCombined,
                            fileUri =
                                uri.toString(),
                            timestamp =
                                System.currentTimeMillis(),
                            errorMessage = null
                        )
                    )

                videoFinalFile.delete()
                audioFile.delete()
                tempFinal.delete()

            } else {

                throw IOException(
                    "Final save failed."
                )
            }

        } else {

            throw IOException(
                "Merge failed."
            )
        }
    }

    /**
     * Downloads one exact byte range.
     *
     * This function deliberately rejects a 200 response when a
     * non-zero/resume Range request was made. This prevents a CDN
     * from returning the complete file and corrupting the temp file.
     */
    private suspend fun downloadStreamSegment(
        url: String,
        file: File,
        offset: Long,
        endInclusive: Long,
        progress: AtomicLong,
        speed: AtomicLong,
        chunkStart: Long,
        platform: String
    ) {

        if (offset < 0L) {
            throw IOException(
                "Invalid offset."
            )
        }

        val absStart =
            chunkStart + offset

        if (
            absStart > endInclusive
        ) {
            return
        }

        val expected =
            endInclusive -
                    absStart +
                    1L

        val builder =
            Request.Builder()
                .url(url)
                .header(
                    "Range",
                    "bytes=$absStart-$endInclusive"
                )

        applyPlatformHeaders(
            builder,
            platform
        )

        client.newCall(
            builder.build()
        ).execute().use { response ->

            val isPartial =
                response.code == 206

            /*
             * A non-zero resume/chunk request MUST be 206.
             *
             * Accepting 200 here can cause the complete file to be
             * written at an offset, producing a corrupt media file.
             */
            if (
                offset > 0L ||
                absStart > 0L
            ) {

                if (!isPartial) {

                    throw HttpDownloadException(
                        response.code,
                        "Range request was not honored " +
                                "(HTTP ${response.code})."
                    )
                }

            } else if (
                response.code !in 200..299
            ) {

                throw HttpDownloadException(
                    response.code,
                    "HTTP ${response.code}"
                )
            }

            val body =
                response.body
                    ?: throw IOException(
                        "Empty body."
                    )

            if (isPartial) {

                val range =
                    validateContentRange(
                        response = response,
                        expectedStart = absStart,
                        expectedEnd =
                            absStart +
                                    expected -
                                    1L
                    )

                if (
                    range.total > 0L &&
                    range.total <
                    range.end + 1L
                ) {

                    throw IOException(
                        "Invalid total size in Content-Range."
                    )
                }

                val bodyLen =
                    body.contentLength()

                if (
                    bodyLen >= 0L &&
                    bodyLen != expected
                ) {

                    throw IOException(
                        "Range length mismatch. " +
                                "Expected $expected, " +
                                "received $bodyLen."
                    )
                }

            } else {

                val contentType =
                    response
                        .header("Content-Type")
                        ?.lowercase()
                        .orEmpty()

                if (
                    isClearlyNonMediaContentType(
                        contentType
                    )
                ) {

                    throw IOException(
                        "Server returned non-media content " +
                                "($contentType)."
                    )
                }

                val bodyLen =
                    body.contentLength()

                if (
                    bodyLen >= 0L &&
                    bodyLen != expected
                ) {

                    throw IOException(
                        "Full response length mismatch. " +
                                "Expected $expected, " +
                                "received $bodyLen."
                    )
                }
            }

            /*
             * RandomAccessFile allows exact resume positions.
             */
            RandomAccessFile(
                file,
                "rw"
            ).use { raf ->

                if (offset == 0L) {
                    raf.setLength(0L)
                }

                raf.seek(offset)

                body.byteStream()
                    .use { source ->

                        val buffer =
                            ByteArray(
                                BUFFER_SIZE
                            )

                        var written =
                            0L

                        while (true) {

                            currentCoroutineContext()
                                .ensureActive()

                            val read =
                                source.read(buffer)

                            if (read == -1) {
                                break
                            }

                            if (read == 0) {
                                continue
                            }

                            val remaining =
                                expected -
                                        written

                            if (
                                remaining <= 0L
                            ) {

                                throw IOException(
                                    "Server returned more " +
                                            "data than requested."
                                )
                            }

                            val toWrite =
                                minOf(
                                    read.toLong(),
                                    remaining
                                ).toInt()

                            raf.write(
                                buffer,
                                0,
                                toWrite
                            )

                            written +=
                                toWrite.toLong()

                            progress.addAndGet(
                                toWrite.toLong()
                            )

                            speed.addAndGet(
                                toWrite.toLong()
                            )

                            if (
                                toWrite < read
                            ) {

                                throw IOException(
                                    "Server returned more " +
                                            "data than requested."
                                )
                            }
                        }

                        if (
                            written != expected
                        ) {

                            throw IOException(
                                "Incomplete response. " +
                                        "Expected $expected bytes, " +
                                        "received $written bytes."
                            )
                        }
                    }
            }
        }
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long
    )

    /**
     * Strictly validates Content-Range.
     */
    private fun validateContentRange(
        response: Response,
        expectedStart: Long,
        expectedEnd: Long
    ): ContentRange {

        val header =
            response.header("Content-Range")
                ?: throw IOException(
                    "No Content-Range."
                )

        val match =
            Regex(
                """bytes\s+(\d+)-(\d+)/(\d+|\*)""",
                RegexOption.IGNORE_CASE
            ).find(header)
                ?: throw IOException(
                    "Invalid Content-Range."
                )

        val start =
            match.groupValues[1]
                .toLongOrNull()
                ?: throw IOException(
                    "Invalid Content-Range start."
                )

        val end =
            match.groupValues[2]
                .toLongOrNull()
                ?: throw IOException(
                    "Invalid Content-Range end."
                )

        val total =
            if (
                match.groupValues[3] == "*"
            ) {

                -1L

            } else {

                match.groupValues[3]
                    .toLongOrNull()
                    ?: throw IOException(
                        "Invalid Content-Range total."
                    )
            }

        if (
            start != expectedStart
        ) {

            throw IOException(
                "Range start mismatch. " +
                        "Expected $expectedStart, " +
                        "received $start."
            )
        }

        if (
            end != expectedEnd
        ) {

            throw IOException(
                "Range end mismatch. " +
                        "Expected $expectedEnd, " +
                        "received $end."
            )
        }

        if (end < start) {

            throw IOException(
                "Invalid Content-Range interval."
            )
        }

        if (
            total == 0L ||
            (
                    total > 0L &&
                            end >= total
                    )
        ) {

            throw IOException(
                "Invalid Content-Range total size."
            )
        }

        return ContentRange(
            start,
            end,
            total
        )
    }

    /**
     * Detects obvious HTML/JSON/XML responses that are not media.
     */
    private fun isClearlyNonMediaContentType(
        contentType: String
    ): Boolean {

        if (contentType.isBlank()) {
            return false
        }

        return contentType.startsWith("text/") ||
                contentType.contains("json") ||
                contentType.contains("html") ||
                contentType.contains("xml")
    }

    /**
     * Determines the remote file size.
     *
     * Range -> HEAD -> GET fallback.
     */
    private suspend fun getStreamSize(
        url: String,
        platform: String
    ): Long = withContext(Dispatchers.IO) {

        /*
         * First try a one-byte Range request.
         */
        val rangeBuilder =
            Request.Builder()
                .url(url)
                .header(
                    "Range",
                    "bytes=0-0"
                )

        applyPlatformHeaders(
            rangeBuilder,
            platform
        )

        try {

            client.newCall(
                rangeBuilder.build()
            ).execute().use { response ->

                if (
                    response.code == 206
                ) {

                    val header =
                        response.header(
                            "Content-Range"
                        )

                    val match =
                        header?.let {

                            Regex(
                                """bytes\s+(\d+)-(\d+)/(\d+|\*)""",
                                RegexOption.IGNORE_CASE
                            ).find(it)
                        }

                    val total =
                        match
                            ?.groupValues
                            ?.get(3)
                            ?.takeUnless {
                                it == "*"
                            }
                            ?.toLongOrNull()

                    if (
                        total != null &&
                        total > 0L
                    ) {

                        return@withContext total
                    }
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Range request failed for size detection",
                e
            )
        }

        /*
         * Fallback to HEAD.
         */
        val headBuilder =
            Request.Builder()
                .url(url)
                .head()

        applyPlatformHeaders(
            headBuilder,
            platform
        )

        try {

            client.newCall(
                headBuilder.build()
            ).execute().use { response ->

                if (
                    response.isSuccessful
                ) {

                    val len =
                        response
                            .header(
                                "Content-Length"
                            )
                            ?.toLongOrNull()

                    if (
                        len != null &&
                        len > 0L
                    ) {

                        return@withContext len
                    }
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "HEAD request failed for size detection",
                e
            )
        }

        /*
         * Last resort: normal GET.
         *
         * The response is immediately closed without reading the
         * body because this is only being used to obtain Content-Length.
         */
        val getBuilder =
            Request.Builder()
                .url(url)

        applyPlatformHeaders(
            getBuilder,
            platform
        )

        try {

            client.newCall(
                getBuilder.build()
            ).execute().use { response ->

                if (
                    response.isSuccessful
                ) {

                    val contentType =
                        response
                            .header("Content-Type")
                            ?.lowercase()
                            .orEmpty()

                    if (
                        !isClearlyNonMediaContentType(
                            contentType
                        )
                    ) {

                        val len =
                            response.body
                                ?.contentLength()
                                ?: -1L

                        if (
                            len > 0L
                        ) {

                            return@withContext len
                        }
                    }
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "GET request failed for size detection",
                e
            )
        }

        throw IOException(
            "Cannot determine size."
        )
    }

    /**
     * Represents an HTTP error where retry policy depends on the status code.
     */
    private class HttpDownloadException(
        val code: Int,
        message: String
    ) : IOException(message)
}