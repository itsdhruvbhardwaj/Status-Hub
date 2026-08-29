package com.dhruv.status.hub.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility for downloading media links and saving them to the appropriate
 * MediaStore collections.
 */
object NetworkDownloadUtils {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Engine that coordinates platform extractors
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
        val mediaType: String, // "video", "audio", "image"
        val platform: String = "direct",
        val thumbnailUrl: String? = null,
        val audioUrl: String? = null
    )

    /**
     * Analyzes a URL using the DownloadEngine to find the actual media source.
     */
    suspend fun analyzeUrl(url: String, onStateChange: (DownloadState) -> Unit) {
        onStateChange(DownloadState.Validating)

        // Basic URL validation
        val validatedUrl = try {
            val parsed = URL(url)
            if (parsed.protocol != "http" && parsed.protocol != "https") {
                onStateChange(DownloadState.Error("Only HTTP and HTTPS links are supported."))
                return
            }
            url
        } catch (e: Exception) {
            onStateChange(DownloadState.Error("Invalid URL format."))
            return
        }

        onStateChange(DownloadState.Analyzing)

        try {
            val mediaInfo = engine.extractMedia(validatedUrl)
            if (mediaInfo != null) {
                onStateChange(DownloadState.Analyzed(mediaInfo))
            } else {
                onStateChange(DownloadState.Error("Could not extract media. Ensure the link is a public post and try again."))
            }
        } catch (e: Exception) {
            onStateChange(DownloadState.Error(e.localizedMessage ?: "An error occurred during analysis."))
        }
    }

    /**
     * Downloads media from analyzed info.
     */
    suspend fun downloadMedia(
        context: Context,
        info: MediaInfo,
        forceDownload: Boolean = false,
        isAudioOnly: Boolean = false,
        onStateChange: (DownloadState) -> Unit
    ) {
        try {
            // Handle Audio Extraction if requested from a Video source
            if (isAudioOnly && info.mediaType == "video" && info.audioUrl == null) {
                downloadAndExtractAudio(context, info, forceDownload, onStateChange)
                return
            }

            val downloadUrl = if (isAudioOnly && info.audioUrl != null) info.audioUrl else info.url
            val mimeType = if (isAudioOnly) "audio/mpeg" else info.contentType
            val extension = if (isAudioOnly) "mp3" else info.extension
            val fileName = if (isAudioOnly) {
                val base = info.fileName.substringBeforeLast(".")
                "$base.mp3"
            } else info.fileName

            // Duplicate check
            if (!forceDownload) {
                val existingUri = getExistingFileUri(context, fileName, mimeType)
                if (existingUri != null) {
                    onStateChange(DownloadState.Duplicate(info, existingUri))
                    return
                }
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onStateChange(DownloadState.Error("Server returned error: ${response.code}"))
                    return
                }

                val body = response.body ?: throw Exception("Empty response from server")
                val inputStream = body.byteStream()
                
                saveToMediaStore(context, inputStream, fileName, mimeType, info.contentLength, onStateChange)
            }
        } catch (e: Exception) {
            onStateChange(DownloadState.Error(e.localizedMessage ?: "An error occurred during download."))
        }
    }

    private suspend fun downloadAndExtractAudio(
        context: Context,
        info: MediaInfo,
        forceDownload: Boolean,
        onStateChange: (DownloadState) -> Unit
    ) {
        val baseName = info.fileName.substringBeforeLast(".")
        val audioFileName = "$baseName.m4a"
        
        // Duplicate check
        if (!forceDownload) {
            val existingUri = getExistingFileUri(context, audioFileName, "audio/mp4")
            if (existingUri != null) {
                onStateChange(DownloadState.Duplicate(info, existingUri))
                return
            }
        }

        val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
        try {
            onStateChange(DownloadState.Downloading(0f, 0, info.contentLength))
            
            val request = Request.Builder()
                .url(info.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onStateChange(DownloadState.Error("Failed to fetch video for extraction: ${response.code}"))
                    return
                }
                
                val body = response.body ?: throw Exception("Empty response")
                val totalBytes = response.body?.contentLength() ?: info.contentLength
                
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else -1f
                            onStateChange(DownloadState.Downloading(progress, downloaded, totalBytes))
                        }
                    }
                }
            }

            // Extract audio from temp video file
            onStateChange(DownloadState.Downloading(-1f, 0, -1)) // Indeterminate for processing
            val resultUri = AudioExtractor.extractAudio(context, Uri.fromFile(tempFile), audioFileName)
            
            if (resultUri != null) {
                onStateChange(DownloadState.Success(resultUri.toString()))
            } else {
                onStateChange(DownloadState.Error("Audio extraction failed. This video might not have a supported audio track."))
            }

        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun getExistingFileUri(context: Context, fileName: String, mimeType: String): Uri? {
        val collection = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        
        return context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                Uri.withAppendedPath(collection, id.toString())
            } else null
        }
    }

    private fun getRelativePath(mimeType: String): String {
        return when {
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES + File.separator + "StatusHub"
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC + File.separator + "StatusHub"
            else -> Environment.DIRECTORY_PICTURES + File.separator + "StatusHub"
        }
    }

    private fun saveToMediaStore(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        onStateChange: (DownloadState) -> Unit
    ) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, getRelativePath(mimeType))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val uri = resolver.insert(collection, contentValues) ?: throw Exception("Failed to access storage")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    
                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else -1f
                    onStateChange(DownloadState.Downloading(progress, downloadedBytes, totalBytes))
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            
            onStateChange(DownloadState.Success(uri.toString()))
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            inputStream.close()
        }
    }
}
