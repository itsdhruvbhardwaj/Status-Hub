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

/**
 * Robust YouTube Extractor that uses NewPipeExtractor library.
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

                        // Add all headers from NewPipeExtractor
                        headers.forEach { (key, values) ->
                            values.forEach { value ->
                                builder.addHeader(key, value)
                            }
                        }

                        // Ensure a Browser-like User-Agent if not provided
                        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
            
            // Normalize URL for shorts
            val normalizedUrl = if (url.contains("/shorts/")) {
                url.replace("/shorts/", "/watch?v=")
            } else url

            val streamInfo = StreamInfo.getInfo(service, normalizedUrl)
            
            // Prefer muxed streams (itag 22 = 720p, 18 = 360p)
            val videoStream = streamInfo.videoStreams.find { it.itag == 22 }
                ?: streamInfo.videoStreams.find { it.itag == 18 }
                ?: streamInfo.videoStreams.maxByOrNull { 
                    it.resolution ?: ""
                }

            val audioStream = streamInfo.audioStreams.maxByOrNull { it.bitrate }

            if (videoStream != null) {
                val formatSuffix = videoStream.format?.suffix ?: "mp4"
                val streamName = streamInfo.name ?: "YouTube_Video"
                
                return@withContext NetworkDownloadUtils.MediaInfo(
                    url = videoStream.url ?: "",
                    fileName = "${streamName.replace(Regex("[^a-zA-Z0-9]"), "_")}.$formatSuffix",
                    contentType = if (formatSuffix == "webm") "video/webm" else "video/mp4",
                    contentLength = -1L,
                    extension = formatSuffix,
                    mediaType = "video",
                    platform = "YouTube",
                    thumbnailUrl = if (streamInfo.thumbnails.isNotEmpty()) streamInfo.thumbnails[0].url else null,
                    audioUrl = audioStream?.url ?: videoStream.url
                )
            } else {
                Log.e(tag, "No video stream found for $url")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Extraction failed for $url: ${e.message}", e)
            null
        }
    }
}
