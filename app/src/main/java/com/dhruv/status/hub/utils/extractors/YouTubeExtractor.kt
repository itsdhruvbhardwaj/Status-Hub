package com.dhruv.status.hub.utils.extractors

import android.util.Log
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit

/**
 * YouTube extractor using NewPipeExtractor.
 *
 * Extracts:
 * - Progressive video streams containing video + audio
 * - Audio streams
 * - DASH video-only streams (720p and higher)
 *
 * DASH video streams are paired with the best available M4A audio stream.
 */
class YouTubeExtractor(
    private val okHttpClient: OkHttpClient
) : MediaExtractor {

    private val tag = "YouTubeExtractor"

    private val extractorClient =
        okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    companion object {

        @Volatile
        private var isInitialized = false
    }


    init {
        initializeNewPipe()
    }


    private fun initializeNewPipe() {

        if (isInitialized) return

        synchronized(
            YouTubeExtractor::class.java
        ) {

            if (isInitialized) return

            try {

                val downloader =
                    object : Downloader() {

                        override fun execute(
                            request: org.schabi.newpipe.extractor.downloader.Request
                        ): Response {

                            val method =
                                request.httpMethod()

                            val url =
                                request.url()

                            val headers =
                                request.headers()

                            val data =
                                request.dataToSend()

                            val mediaType =
                                headers["Content-Type"]
                                    ?.firstOrNull()
                                    ?.toMediaTypeOrNull()

                            val requestBody =
                                when {

                                    data != null ->
                                        data.toRequestBody(
                                            mediaType
                                        )

                                    method == "POST" ||
                                            method == "PUT" ->
                                        "".toByteArray()
                                            .toRequestBody(
                                                mediaType
                                            )

                                    else ->
                                        null
                                }

                            val builder =
                                Request.Builder()
                                    .url(url)
                                    .method(
                                        method,
                                        requestBody
                                    )

                            headers.forEach { (key, values) ->

                                values.forEach { value ->

                                    builder.addHeader(
                                        key,
                                        value
                                    )
                                }
                            }

                            if (
                                headers.none {
                                    it.key.equals(
                                        "User-Agent",
                                        ignoreCase = true
                                    )
                                }
                            ) {

                                builder.header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                                )
                            }

                            extractorClient
                                .newCall(
                                    builder.build()
                                )
                                .execute()
                                .use { okResponse ->

                                    val bodyString =
                                        okResponse.body
                                            ?.string()

                                    return Response(
                                        okResponse.code,
                                        okResponse.message,
                                        okResponse.headers.toMultimap(),
                                        bodyString,
                                        okResponse.request.url.toString()
                                    )
                                }
                        }
                    }

                NewPipe.init(
                    downloader,
                    Localization.DEFAULT
                )

                isInitialized = true

                Log.d(
                    tag,
                    "NewPipeExtractor initialized successfully"
                )

            } catch (e: Exception) {

                Log.e(
                    tag,
                    "Failed to initialize NewPipeExtractor",
                    e
                )
            }
        }
    }


    override fun canHandle(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains("youtube.com") ||
                lower.contains("youtu.be")
    }


    override suspend fun extract(
        url: String
    ): NetworkDownloadUtils.MediaInfo? =
        withContext(Dispatchers.IO) {

            if (!isInitialized) {
                initializeNewPipe()
            }

            try {

                Log.d(
                    tag,
                    "Starting extraction for: $url"
                )


                // ---------------------------------------------------------
                // Normalize URL
                // ---------------------------------------------------------

                val normalizedUrl =
                    if (url.contains("/shorts/")) {

                        url.replace(
                            "/shorts/",
                            "/watch?v="
                        )

                    } else {

                        url
                    }


                val streamInfo =
                    StreamInfo.getInfo(
                        ServiceList.YouTube,
                        normalizedUrl
                    )


                val sanitizedName =
                    (
                            streamInfo.name
                                ?: "YouTube_Video"
                            )
                        .replace(
                            Regex("[^a-zA-Z0-9]"),
                            "_"
                        )


                val formats =
                    mutableListOf<
                            NetworkDownloadUtils.MediaFormat
                            >()


                // =========================================================
                // 1. PROGRESSIVE VIDEO
                // =========================================================

                streamInfo.videoStreams
                    ?.filter {
                        !it.url.isNullOrBlank() &&
                                !it.isVideoOnly
                    }
                    ?.sortedByDescending { video ->

                        getHeight(
                            video.resolution
                        )
                    }
                    ?.distinctBy {

                        (
                                it.resolution ?: ""
                                ) +
                                (
                                        it.format?.suffix
                                            ?: ""
                                        )
                    }
                    ?.forEach { video ->

                        val extension =
                            video.format
                                ?.suffix
                                ?.lowercase()
                                ?: "mp4"

                        formats.add(
                            NetworkDownloadUtils.MediaFormat(

                                id =
                                    "v_${video.itag}_$extension",

                                url =
                                    video.url ?: "",

                                quality =
                                    video.resolution
                                        ?: "Unknown",

                                extension =
                                    extension,

                                format =
                                    video.format?.name
                                        ?: extension.uppercase(),

                                size =
                                    -1L,

                                isAudio =
                                    false,

                                hasVideo =
                                    true,

                                hasAudio =
                                    true,

                                note =
                                    "Direct"
                            )
                        )
                    }


                // =========================================================
                // 2. DASH VIDEO + AUDIO
                // =========================================================

                Log.d(
                    tag,
                    "--- YouTube DASH Detection Logs ---"
                )


                val allVideoCandidates =
                    mutableListOf<VideoStream>()


                streamInfo.videoStreams
                    ?.let {
                        allVideoCandidates.addAll(it)
                    }


                try {

                    val videoOnly =
                        streamInfo.videoOnlyStreams

                    if (
                        videoOnly != null
                    ) {

                        allVideoCandidates.addAll(
                            videoOnly
                        )

                        Log.d(
                            tag,
                            "Found ${videoOnly.size} video-only DASH streams"
                        )
                    }

                } catch (e: Exception) {

                    Log.d(
                        tag,
                        "videoOnlyStreams not available: ${e.message}"
                    )
                }


                // ---------------------------------------------------------
                // Log all video candidates
                // ---------------------------------------------------------

                allVideoCandidates.forEach { video ->

                    val height =
                        getHeight(
                            video.resolution
                        )

                    val extension =
                        video.format
                            ?.suffix
                            ?.lowercase()
                            ?: "unknown"

                    Log.d(
                        tag,
                        "Video Candidate: itag=${video.itag}, res=${video.resolution}, height=$height, videoOnly=${video.isVideoOnly}, ext=$extension, hasUrl=${!video.url.isNullOrBlank()}"
                    )
                }


                // ---------------------------------------------------------
                // Log audio candidates
                // ---------------------------------------------------------

                streamInfo.audioStreams
                    ?.forEach { audio ->

                        val extension =
                            audio.format
                                ?.suffix
                                ?.lowercase()
                                ?: "unknown"

                        Log.d(
                            tag,
                            "Audio Candidate: itag=${audio.itag}, bitrate=${audio.bitrate}, ext=$extension, hasUrl=${!audio.url.isNullOrBlank()}"
                        )
                    }


                // ---------------------------------------------------------
                // Find best M4A/MP4 audio
                // ---------------------------------------------------------

                val bestAudio =
                    streamInfo.audioStreams
                        ?.filter {

                            !it.url.isNullOrBlank() &&
                                    (
                                            it.format
                                                ?.suffix
                                                ?.lowercase() == "m4a" ||

                                                    it.format
                                                        ?.suffix
                                                        ?.lowercase() == "mp4"
                                            )
                        }
                        ?.maxByOrNull {
                            it.bitrate
                        }


                if (
                    bestAudio != null
                ) {

                    // -----------------------------------------------------
                    // Find ALL MP4 DASH video-only streams >= 720p
                    // -----------------------------------------------------

                    val dashVideos =
                        allVideoCandidates
                            .filter { video ->

                                val height =
                                    getHeight(
                                        video.resolution
                                    )

                                val extension =
                                    video.format
                                        ?.suffix
                                        ?.lowercase()
                                        ?: ""

                                video.isVideoOnly &&
                                        height >= 720 &&
                                        extension == "mp4" &&
                                        !video.url.isNullOrBlank()
                            }
                            .distinctBy {
                                getHeight(
                                    it.resolution
                                )
                            }
                            .sortedBy {
                                getHeight(
                                    it.resolution
                                )
                            }


                    Log.d(
                        tag,
                        "Found ${dashVideos.size} usable DASH MP4 video streams >=720p"
                    )


                    // -----------------------------------------------------
                    // Add every available DASH resolution
                    // -----------------------------------------------------

                    dashVideos.forEach { video ->

                        val height =
                            getHeight(
                                video.resolution
                            )

                        val quality =
                            "${height}p (HD)"


                        formats.add(
                            NetworkDownloadUtils.MediaFormat(

                                id =
                                    "dash_${video.itag}_${height}p",

                                url =
                                    video.url ?: "",

                                quality =
                                    quality,

                                extension =
                                    "mp4",

                                format =
                                    "MP4",

                                size =
                                    -1L,

                                isAudio =
                                    false,

                                hasVideo =
                                    true,

                                hasAudio =
                                    true,

                                note =
                                    "HD",

                                dashAudioUrl =
                                    bestAudio.url
                            )
                        )


                        Log.d(
                            tag,
                            "SUCCESS: Added DASH $quality using itag ${video.itag}"
                        )
                    }

                } else {

                    Log.d(
                        tag,
                        "No suitable M4A/MP4 audio found for DASH"
                    )
                }


                Log.d(
                    tag,
                    "--- End DASH Detection Logs ---"
                )


                // =========================================================
                // 3. AUDIO STREAMS
                // =========================================================

                streamInfo.audioStreams
                    ?.filter {
                        !it.url.isNullOrBlank()
                    }
                    ?.sortedByDescending {
                        it.bitrate
                    }
                    ?.distinctBy {
                        "${it.format?.suffix}_${it.bitrate}"
                    }
                    ?.take(3)
                    ?.forEach { audio ->

                        val extension =
                            audio.format
                                ?.suffix
                                ?.lowercase()
                                ?: "m4a"

                        val bitrateKbps =
                            if (
                                audio.bitrate > 0
                            ) {

                                audio.bitrate / 1000

                            } else {

                                128
                            }


                        formats.add(
                            NetworkDownloadUtils.MediaFormat(

                                id =
                                    "a_${audio.itag}_${audio.bitrate}_$extension",

                                url =
                                    audio.url ?: "",

                                quality =
                                    "$bitrateKbps kbps",

                                extension =
                                    extension,

                                format =
                                    if (
                                        extension == "m4a"
                                    ) {
                                        "M4A"
                                    } else {
                                        extension.uppercase()
                                    },

                                size =
                                    -1L,

                                isAudio =
                                    true,

                                hasVideo =
                                    false,

                                hasAudio =
                                    true,

                                note =
                                    "Audio"
                            )
                        )
                    }


                // =========================================================
                // 4. VALIDATE
                // =========================================================

                if (
                    formats.isEmpty()
                ) {

                    Log.e(
                        tag,
                        "No downloadable formats found"
                    )

                    return@withContext null
                }


                // =========================================================
                // 5. DEFAULT FORMAT
                // =========================================================

                val defaultFormat =
                    formats.find {
                        !it.isAudio &&
                                it.quality.contains(
                                    "360p"
                                )
                    }
                        ?: formats.firstOrNull {
                            !it.isAudio &&
                                    it.hasVideo
                        }
                        ?: formats.firstOrNull()


                if (
                    defaultFormat == null
                ) {

                    return@withContext null
                }


                val extension =
                    defaultFormat.extension
                        .lowercase()

                val isAudio =
                    defaultFormat.isAudio


                // =========================================================
                // 6. MEDIA INFO
                // =========================================================

                NetworkDownloadUtils.MediaInfo(

                    url =
                        defaultFormat.url,

                    fileName =
                        "$sanitizedName.$extension",

                    contentType =
                        if (isAudio) {
                            "audio/$extension"
                        } else {
                            "video/$extension"
                        },

                    contentLength =
                        -1L,

                    extension =
                        extension,

                    mediaType =
                        if (isAudio) {
                            "audio"
                        } else {
                            "video"
                        },

                    platform =
                        "YouTube",

                    thumbnailUrl =
                        streamInfo.thumbnails
                            .firstOrNull()
                            ?.url,

                    audioUrl =
                        streamInfo.audioStreams
                            ?.maxByOrNull {
                                it.bitrate
                            }
                            ?.url,

                    formats =
                        formats
                )


            } catch (e: Exception) {

                Log.e(
                    tag,
                    "Extraction failed for $url",
                    e
                )

                null
            }
        }


    /**
     * Converts NewPipe resolution strings such as:
     *
     * 1920x1080 -> 1080
     * 1280x720  -> 720
     * 720p      -> 720
     */
    private fun getHeight(
        resolution: String?
    ): Int {

        if (
            resolution.isNullOrBlank()
        ) {
            return 0
        }

        return try {

            if (
                resolution.contains("x")
            ) {

                resolution
                    .substringAfter("x")
                    .takeWhile {
                        it.isDigit()
                    }
                    .toIntOrNull()
                    ?: 0

            } else {

                resolution
                    .takeWhile {
                        it.isDigit()
                    }
                    .toIntOrNull()
                    ?: 0
            }

        } catch (
            _: Exception
        ) {

            0
        }
    }
}