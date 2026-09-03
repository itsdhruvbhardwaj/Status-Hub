package com.dhruv.status.hub.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Utility for downloading media links.
 * Restored to original behavior: Preserves native stream format and container.
 */
object NetworkDownloadUtils {

    private const val TAG = "NetworkDownloadUtils"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val engine = DownloadEngine(client)

    sealed class DownloadState {
        object Idle : DownloadState()
        object Validating : DownloadState()
        object Analyzing : DownloadState()
        data class Analyzed(val info: MediaInfo) : DownloadState()
        data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        data class Success(val filePath: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
        data class Duplicate(val info: MediaInfo, val existingUri: Uri) : DownloadState()
        data class UnsupportedSource(val platform: String) : DownloadState()
    }

    data class MediaInfo(
        val url: String,
        val fileName: String,
        val contentType: String,
        val contentLength: Long,
        val extension: String,
        val mediaType: String,
        val platform: String = "direct",
        val thumbnailUrl: String? = null,
        val audioUrl: String? = null,
        val formats: List<MediaFormat> = emptyList()
    )

    data class MediaFormat(
        val id: String,
        val url: String,
        val quality: String,
        val extension: String,
        val format: String,
        val size: Long = -1L,
        val isAudio: Boolean = false,
        val hasVideo: Boolean = true,
        val hasAudio: Boolean = true,
        val note: String? = null
    )

    suspend fun analyzeUrl(url: String, onStateChange: (DownloadState) -> Unit) {
        onStateChange(DownloadState.Validating)
        try {
            val parsed = URL(url)
            if (parsed.protocol != "http" && parsed.protocol != "https") {
                onStateChange(DownloadState.Error("Only HTTP/HTTPS links supported."))
                return
            }
        } catch (e: Exception) {
            onStateChange(DownloadState.Error("Invalid URL format."))
            return
        }

        onStateChange(DownloadState.Analyzing)
        try {
            val mediaInfo = engine.extractMedia(url)
            if (mediaInfo != null) {
                onStateChange(DownloadState.Analyzed(mediaInfo))
            } else {
                onStateChange(DownloadState.Error("Could not extract media."))
            }
        } catch (e: Exception) {
            onStateChange(DownloadState.Error(e.localizedMessage ?: "Analysis failed."))
        }
    }

    /**
     * Map extension to MediaStore MIME types.
     * WebM is handled correctly for both audio and video.
     */
    fun getMimeType(extension: String, isAudio: Boolean): String {
        return when (extension.lowercase()) {
            "mp4" -> if (isAudio) "audio/mp4" else "video/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> if (isAudio) "audio/webm" else "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> if (isAudio) "audio/*" else "video/*"
        }
    }
}
