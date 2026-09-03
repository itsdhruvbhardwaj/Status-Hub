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
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * YouTube extractor using NewPipeExtractor.
 * Restored to original working flow: Native stream extraction without transcoding.
 */
class YouTubeExtractor(
    private val okHttpClient: OkHttpClient
) : MediaExtractor {

    private val tag = "YouTubeExtractor"

    companion object {
        @Volatile
        private var isInitialized = false
    }

    init {
        initializeNewPipe()
    }

    private fun initializeNewPipe() {
        if (isInitialized) return

        synchronized(YouTubeExtractor::class.java) {
            if (isInitialized) return

            try {
                val downloader = object : Downloader() {

                    override fun execute(
                        request: org.schabi.newpipe.extractor.downloader.Request
                    ): Response {

                        val method = request.httpMethod()
                        val url = request.url()
                        val headers = request.headers()
                        val data = request.dataToSend()

                        val mediaType =
                            headers["Content-Type"]
                                ?.firstOrNull()
                                ?.toMediaTypeOrNull()

                        val requestBody = when {
                            data != null ->
                                data.toRequestBody(mediaType)

                            method == "POST" || method == "PUT" ->
                                "".toByteArray().toRequestBody(mediaType)

                            else ->
                                null
                        }

                        val builder = Request.Builder()
                            .url(url)
                            .method(method, requestBody)

                        headers.forEach { (key, values) ->
                            values.forEach { value ->
                                builder.addHeader(key, value)
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
                                "Mozilla/5.0 (Linux; Android 10; K) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/131.0.0.0 Mobile Safari/537.36"
                            )
                        }

                        okHttpClient
                            .newCall(builder.build())
                            .execute()
                            .use { okResponse ->

                                val bodyString =
                                    okResponse.body?.string()

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

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()

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

                val streamName =
                    streamInfo.name
                        ?: "YouTube_Video"

                val sanitizedName =
                    streamName.replace(
                        Regex("[^a-zA-Z0-9]"),
                        "_"
                    )

                val formats =
                    mutableListOf<NetworkDownloadUtils.MediaFormat>()

                // ---------------------------------------------------------
                // VIDEO STREAMS
                // ---------------------------------------------------------

                streamInfo.videoStreams
                    .filter {
                        it.itag == 18 || it.itag == 22
                    }
                    .take(3)
                    .forEach { video ->

                        val extension =
                            video.format?.suffix
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
                                        ?: "MP4",

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

                // ---------------------------------------------------------
                // AUDIO STREAMS
                // ---------------------------------------------------------

                streamInfo.audioStreams
                    .filter {
                        !it.url.isNullOrBlank()
                    }
                    .sortedWith(
                        compareByDescending<AudioStream> {
                            it.format?.suffix
                                ?.equals(
                                    "m4a",
                                    ignoreCase = true
                                )
                                ?: false
                        }.thenByDescending {
                            it.bitrate
                        }
                    )
                    .distinctBy {
                        "${it.format?.suffix}_${it.bitrate}"
                    }
                    .take(3)
                    .forEach { audio ->

                        val extension =
                            audio.format?.suffix
                                ?.lowercase()
                                ?: "webm"

                        val bitrateKbps =
                            if (audio.bitrate > 0) {
                                audio.bitrate / 1000
                            } else {
                                128
                            }

                        val isM4a =
                            extension == "m4a"

                        formats.add(
                            NetworkDownloadUtils.MediaFormat(

                                id =
                                    "a_${audio.itag}_" +
                                            "${audio.bitrate}_" +
                                            extension,

                                url =
                                    audio.url ?: "",

                                quality =
                                    "$bitrateKbps kbps",

                                extension =
                                    extension,

                                format =
                                    if (isM4a) {
                                        "M4A"
                                    } else {
                                        "WEBM"
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
                                    if (isM4a) {
                                        "MPEG-4 Audio"
                                    } else {
                                        "Opus Audio"
                                    }
                            )
                        )
                    }

                if (formats.isEmpty()) {

                    Log.e(
                        tag,
                        "No downloadable formats found"
                    )

                    return@withContext null
                }

                // ---------------------------------------------------------
                // DEFAULT FORMAT
                // ---------------------------------------------------------

                val bestFormat =
                    formats.firstOrNull {
                        !it.isAudio && it.hasVideo
                    } ?: formats.firstOrNull()

                if (bestFormat == null) {
                    return@withContext null
                }

                val extension =
                    bestFormat.extension.lowercase()

                val isAudio =
                    bestFormat.isAudio

                val contentType =
                    when {

                        isAudio &&
                                extension == "webm" ->
                            "audio/webm"

                        isAudio &&
                                extension == "m4a" ->
                            "audio/mp4"

                        !isAudio &&
                                extension == "webm" ->
                            "video/webm"

                        !isAudio &&
                                extension == "mp4" ->
                            "video/mp4"

                        isAudio ->
                            "audio/*"

                        else ->
                            "video/*"
                    }

                val mediaType =
                    if (isAudio) {
                        "audio"
                    } else {
                        "video"
                    }

                NetworkDownloadUtils.MediaInfo(

                    url =
                        bestFormat.url,

                    fileName =
                        "$sanitizedName.$extension",

                    contentType =
                        contentType,

                    contentLength =
                        -1L,

                    extension =
                        extension,

                    mediaType =
                        mediaType,

                    platform =
                        "YouTube",

                    thumbnailUrl =
                        streamInfo.thumbnails
                            .firstOrNull()
                            ?.url,

                    audioUrl =
                        streamInfo.audioStreams
                            .maxByOrNull {
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
}
