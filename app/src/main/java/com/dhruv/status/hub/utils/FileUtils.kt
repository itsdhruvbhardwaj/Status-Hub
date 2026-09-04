package com.dhruv.status.hub.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Utility for file operations in Status Hub.
 * Restored to original behavior: Direct MediaStore saving based on container.
 * Added: MediaStore remuxing for Instagram audio extraction.
 */
object FileUtils {

    private const val TAG = "FileUtils"

    fun isActuallyVideo(context: Context, uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            hasVideo == "yes"
        } catch (e: Exception) {
            val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""
            mimeType.startsWith("video/")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun isActuallyVideoFile(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            hasVideo == "yes"
        } catch (e: Exception) {
            false
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Saves downloaded temporary media into MediaStore.
     * If the target is audio but the source is a video (Instagram Reel),
     * it remuxes the audio track into a standalone M4A file.
     */
    fun saveTempFileToMediaStore(
        context: Context,
        tempFile: File,
        fileName: String,
        mediaType: String
    ): Uri? {
        val resolver = context.contentResolver
        val extension = fileName.substringAfterLast(".", "").lowercase()
        val isAudio = mediaType.lowercase() == "audio" || extension == "m4a" || (extension == "webm" && mediaType == "audio")

        val mimeType = when (extension) {
            "mp4" -> if (isAudio) "audio/mp4" else "video/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> if (isAudio) "audio/webm" else "video/webm"
            "mp3" -> "audio/mpeg"
            else -> if (isAudio) "audio/*" else "video/*"
        }

        val relativePath = if (isAudio) {
            Environment.DIRECTORY_MUSIC + File.separator + "StatusHub"
        } else {
            Environment.DIRECTORY_MOVIES + File.separator + "StatusHub"
        }

        val collection = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(collection, contentValues) ?: return null
            
            // For Instagram Audio, we remux the video's audio track.
            // Muxing directly to FileDescriptor via MediaMuxer requires API 26+.
            // To support API 24, we mux to a temporary file first.
            if (isAudio && isActuallyVideoFile(tempFile)) {
                val muxTempFile = File(context.cacheDir, "mux_${System.currentTimeMillis()}_$fileName")
                val success = remuxAudioOnly(tempFile, muxTempFile)
                
                if (success) {
                    resolver.openOutputStream(uri)?.use { output ->
                        muxTempFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    muxTempFile.delete()
                } else {
                    muxTempFile.delete()
                    resolver.delete(uri, null, null)
                    return null
                }
            } else {
                // Direct copy for standard media
                resolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            null
        }
    }

    /**
     * Extracts the audio track from a source MP4 and muxes it into a destination M4A.
     * No re-encoding occurs; AAC samples are copied directly.
     */
    private fun remuxAudioOnly(sourceFile: File, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    extractor.selectTrack(i)
                    
                    muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer.addTrack(format)
                    muxer.start()
                    break
                }
            }

            if (audioTrackIndex == -1 || muxer == null) {
                Log.e(TAG, "No audio track found in source file")
                return false
            }

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                
                bufferInfo.presentationTimeUs = extractor.sampleTime
                
                // Map MediaExtractor flags to MediaCodec flags to fix lint warnings
                var sampleFlags = 0
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = sampleFlags
                
                muxer.writeSampleData(0, buffer, bufferInfo)
                extractor.advance()
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Legacy status-saver support.
     * Restored from Play Store version.
     */
    fun downloadMedia(
        context: Context,
        uri: Uri,
        isAutoSave: Boolean = false
    ) {
        val contentResolver = context.contentResolver
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val originalName = docFile?.name ?: "Status_${System.currentTimeMillis()}"
        val mimeType = contentResolver.getType(uri) ?: when {
            uri.toString().contains(".mp4", true) -> "video/mp4"
            uri.toString().contains(".webm", true) -> "video/webm"
            else -> "image/jpeg"
        }

        val extension = when {
            mimeType.startsWith("video") -> if (mimeType.contains("webm")) "webm" else "mp4"
            mimeType.startsWith("audio") -> when {
                mimeType.contains("webm") -> "webm"
                mimeType.contains("mp4") -> "m4a"
                mimeType.contains("mpeg") -> "mp3"
                mimeType.contains("ogg") -> "ogg"
                else -> "audio"
            }
            else -> "jpg"
        }

        val fileName = if (originalName.contains(".")) originalName else "$originalName.$extension"

        try {
            if (isAutoSave && isFileAlreadyAutoSaved(context, fileName)) return

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                if (!isAutoSave) Toast.makeText(context, "Failed to open status", Toast.LENGTH_SHORT).show()
                return
            }

            val relativePath = when {
                mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + File.separator + "StatusHub" + File.separator + "Videos"
                mimeType.startsWith("audio") -> Environment.DIRECTORY_MUSIC + File.separator + "StatusHub" + File.separator + "Audio"
                else -> Environment.DIRECTORY_PICTURES + File.separator + "StatusHub" + File.separator + "Images"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
            }

            val collection = when {
                mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val destinationUri = contentResolver.insert(collection, contentValues)
            if (destinationUri != null) {
                contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    inputStream.use { it.copyTo(outputStream) }
                    if (!isAutoSave) {
                        Toast.makeText(context, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        markFileAsAutoSaved(context, fileName)
                    }
                }
            } else {
                inputStream.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy media download failed", e)
        }
    }

    private fun isFileAlreadyAutoSaved(context: Context, fileName: String): Boolean {
        val prefs = context.getSharedPreferences("auto_save_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(fileName, false)
    }

    private fun markFileAsAutoSaved(context: Context, fileName: String) {
        val prefs = context.getSharedPreferences("auto_save_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(fileName, true).apply()
    }

    fun getDownloadedMedia(context: Context): List<Uri> {
        val mediaList = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%StatusHub%")

        fun query(collection: Uri) {
            context.contentResolver.query(collection, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    mediaList.add(Uri.withAppendedPath(collection, cursor.getLong(idCol).toString()))
                }
            }
        }

        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        return mediaList
    }
}
