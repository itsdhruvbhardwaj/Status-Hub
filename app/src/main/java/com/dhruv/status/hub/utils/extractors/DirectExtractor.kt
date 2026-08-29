package com.dhruv.status.hub.utils.extractors

import android.webkit.MimeTypeMap
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class DirectExtractor(private val client: OkHttpClient) : MediaExtractor {
    
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override fun canHandle(url: String): Boolean {
        // Direct extractor is the fallback, so we'll check it last or if it's clearly a direct link
        // For simplicity in the engine, we might treat it as a generic handler.
        return true 
    }

    override suspend fun extract(url: String): NetworkDownloadUtils.MediaInfo? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val contentType = response.header("Content-Type") ?: ""
            if (!isSupportedMedia(contentType)) return null

            val contentLength = response.body?.contentLength() ?: -1L
            val extension = getExtensionFromMimeType(contentType) ?: getExtensionFromUrl(url) ?: "bin"
            val fileName = generateSafeFileName(response, url, extension)
            val mediaType = when {
                contentType.startsWith("video/") -> "video"
                contentType.startsWith("audio/") -> "audio"
                else -> "image"
            }

            return NetworkDownloadUtils.MediaInfo(url, fileName, contentType, contentLength, extension, mediaType, "direct")
        }
    }

    private fun isSupportedMedia(mimeType: String): Boolean {
        val type = mimeType.lowercase()
        return type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/")
    }

    private fun getExtensionFromMimeType(mimeType: String): String? {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }

    private fun getExtensionFromUrl(url: String): String? {
        return MimeTypeMap.getFileExtensionFromUrl(url)
    }

    private fun generateSafeFileName(response: Response, url: String, extension: String): String {
        var name: String? = null
        val disposition = response.header("Content-Disposition")
        if (disposition != null && disposition.contains("filename=")) {
            name = disposition.substringAfter("filename=")
                .removeSurrounding("\"")
                .substringBefore(";")
                .trim()
        }

        if (name.isNullOrBlank()) {
            try {
                val path = URL(url).path
                name = path.substringAfterLast('/').substringBeforeLast('.')
            } catch (e: Exception) {}
        }

        val sanitized = name?.replace(Regex("[^a-zA-Z0-9._-]"), "_")?.take(50)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return if (!sanitized.isNullOrBlank()) {
            "${sanitized}_$timeStamp.$extension"
        } else {
            "StatusHub_$timeStamp.$extension"
        }
    }
}
