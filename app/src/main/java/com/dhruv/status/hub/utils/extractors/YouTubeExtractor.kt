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
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Robust YouTube Extractor that uses NewPipeExtractor library.
 * Updated to prefer M4A for audio compatibility with Android MediaStore.
 */
class YouTubeExtractor(private val okHttpClient: OkHttpClient) : MediaExtractor {

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
                    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                        val method = request.httpMethod()
                        val url = request.url()
                        val headers = request.headers()
                        val data = request.dataToSend()

                        val mediaType = headers["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
                        val requestBody = when {
                            data != null -> data.toRequestBody(mediaType)
                            method == "POST" || method == "PUT" -> "".toByteArray().toRequestBody(mediaType)
                            else -> null
                        }

                        val builder = Request.Builder()
                            .url(url)
                            .method(method, requestBody)

                        headers.forEach { (key, values) ->
                            values.forEach { value ->
                                builder.addHeader(key, value)
                            }
                        }

                        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                        }

                        val okHttpRequest = builder.build()
                        okHttpClient.newCall(okHttpRequest).execute().use { okResponse ->
                            val bodyString = okResponse.body?.string()
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

                NewPipe.init(downloader, Localization.DEFAULT)
                isInitialized = true
                Log.d(tag, "NewPipeExtractor initialized successfully")
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize NewPipeExtractor: ${e.message}")
            }
        }
    }

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    override suspend fun extract(url: String): NetworkDownloadUtils.MediaInfo? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initializeNewPipe()
        }
        
        try {
            Log.d(tag, "Starting extraction for: $url")
            val service = ServiceList.YouTube
            val normalizedUrl = if (url.contains("/shorts/")) {
                url.replace("/shorts/", "/watch?v=")
            } else url

            val streamInfo = StreamInfo.getInfo(service, normalizedUrl)
            val streamName = streamInfo.name ?: "YouTube_Video"
            val sanitizedName = streamName.replace(Regex("[^a-zA-Z0-9]"), "_")
            
            val formats = mutableListOf<NetworkDownloadUtils.MediaFormat>()

            // 1. Add Video + Audio (Muxed) streams
            streamInfo.videoStreams
                .filter { vs -> vs.itag == 18 || vs.itag == 22 }
                .take(3)
                .forEach { vs ->
                    formats.add(
                        NetworkDownloadUtils.MediaFormat(
                            id = "v_${vs.itag}_${vs.format?.suffix}",
                            url = vs.url ?: "",
                            quality = vs.resolution ?: "Unknown",
                            extension = vs.format?.suffix ?: "mp4",
                            format = vs.format?.name ?: "MP4",
                            size = -1L,
                            isAudio = false,
                            hasVideo = true,
                            hasAudio = true,
                            note = "Direct"
                        )
                    )
                }

            // 2. Add Audio-only streams - Prefer M4A for compatibility
            streamInfo.audioStreams
                .sortedWith(compareByDescending<AudioStream> { it.bitrate }
                    .thenByDescending { it.format?.suffix == "m4a" })
                .distinctBy { it.bitrate / 1000 }
                .take(2) 
                .forEach { as_ ->
                    val bitrateKbps = if (as_.bitrate > 0) as_.bitrate / 1000 else 128
                    val ext = as_.format?.suffix ?: "m4a"
                    
                    formats.add(
                        NetworkDownloadUtils.MediaFormat(
                            id = "a_${as_.itag}_${as_.bitrate}_$ext",
                            url = as_.url ?: "",
                            quality = "${bitrateKbps} kbps",
                            extension = ext,
                            format = if (ext == "m4a") "M4A" else "WEBM",
                            size = -1L,
                            isAudio = true,
                            hasVideo = false,
                            hasAudio = true,
                            note = if (ext == "m4a") "MPEG-4 Audio" else "Opus Audio"
                        )
                    )
                }

            if (formats.isNotEmpty()) {
                val bestFormat = formats.firstOrNull { !it.isAudio } ?: formats.firstOrNull()

                return@withContext NetworkDownloadUtils.MediaInfo(
                    url = bestFormat?.url ?: "",
                    fileName = "$sanitizedName.${bestFormat?.extension ?: "mp4"}",
                    contentType = if (bestFormat?.extension == "webm") "video/webm" else if (bestFormat?.isAudio == true) "audio/mp4" else "video/mp4",
                    contentLength = -1L,
                    extension = bestFormat?.extension ?: "mp4",
                    mediaType = if (bestFormat?.isAudio == true) "audio" else "video",
                    platform = "YouTube",
                    thumbnailUrl = if (streamInfo.thumbnails.isNotEmpty()) streamInfo.thumbnails[0].url else null,
                    audioUrl = streamInfo.audioStreams.maxByOrNull { it.bitrate }?.url,
                    formats = formats
                )
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "Extraction failed for $url: ${e.message}", e)
            null
        }
    }
}
