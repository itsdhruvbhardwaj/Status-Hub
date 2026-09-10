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
import com.dhruv.status.hub.data.DownloadRecord
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility for file operations in Status Hub.
 * Enhanced with robust remuxing to ensure Gallery playability for fragmented MP4s (Facebook, etc.).
 */
object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Checks if the given Uri points to a valid video using MediaMetadataRetriever.
     */
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

    /**
     * Verifies that the file is a valid media container.
     * Rejects HTML or obviously corrupted files.
     */
    fun verifyMediaIntegrity(file: File, mediaType: String): Boolean {
        if (!file.exists() || file.length() < 1024) return false

        // Fast Header Check: Rejects HTML/JSON/Error pages masquerading as media
        val buffer = ByteArray(1024)
        try {
            file.inputStream().use { it.read(buffer) }
            val prefix = String(buffer, Charsets.US_ASCII).lowercase()
            if (prefix.contains("<!doctype") || prefix.contains("<html") || prefix.contains("{\"")) {
                Log.e(TAG, "Rejecting file ${file.name}: detected HTML/JSON content instead of media")
                return false
            }
        } catch (e: Exception) { return false }

        return true
    }

    /**
     * Saves downloaded temporary media into MediaStore.
     * Standardizes MP4 containers via remuxing to fix social media playback issues.
     */
    fun saveTempFileToMediaStore(
        context: Context,
        tempFile: File,
        fileName: String,
        mediaType: String,
        needsRemux: Boolean = false
    ): Uri? {
        if (!verifyMediaIntegrity(tempFile, mediaType)) {
            Log.e(TAG, "Integrity check failed for $fileName")
            return null
        }

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

            // Standardization: Fix fragmented MP4s (fMP4) by remuxing them.
            // This ensures playability in all Gallery apps.
            val processedFile = if (needsRemux && !isAudio && (extension == "mp4" || extension == "m4v")) {
                val muxTemp = File(context.cacheDir, "remux_v_${System.currentTimeMillis()}_$fileName")
                if (remuxVideoStandard(tempFile, muxTemp)) muxTemp else null
            } else if (needsRemux && isAudio && hasVideoTrack(tempFile)) {
                val muxTemp = File(context.cacheDir, "remux_a_${System.currentTimeMillis()}_$fileName")
                if (remuxAudioOnly(tempFile, muxTemp)) muxTemp else null
            } else null

            val sourceToCopy = processedFile ?: tempFile

            resolver.openOutputStream(uri)?.use { output ->
                sourceToCopy.inputStream().use { input -> input.copyTo(output) }
            }

            processedFile?.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore save failed for $fileName", e)
            null
        }
    }

    private fun hasVideoTrack(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (e: Exception) { false } finally { try { retriever.release() } catch (_: Exception) {} }
    }

    /**
     * Standardizes a video file by remuxing it into a regular MP4 container.
     * Fixes moov atom issues and fragmentation which cause playback failure.
     */
    private fun remuxVideoStandard(sourceFile: File, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val newIndex = muxer.addTrack(format)
                    trackMap[i] = newIndex
                    extractor.selectTrack(i)
                    if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                }
            }

            if (trackMap.isEmpty()) return false

            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val lastTimes = mutableMapOf<Int, Long>()
            var sampleCount = 0

            while (true) {
                val index = extractor.sampleTrackIndex
                if (index < 0) break

                val targetIndex = trackMap[index]
                if (targetIndex == null) {
                    extractor.advance()
                    continue
                }

                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                // Ensure strictly increasing timestamps per track as required by MediaMuxer
                var pts = extractor.sampleTime
                val lastPts = lastTimes[targetIndex] ?: -1L
                if (pts <= lastPts) pts = lastPts + 1000
                lastTimes[targetIndex] = pts

                info.presentationTimeUs = pts
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                muxer.writeSampleData(targetIndex, buffer, info)
                sampleCount++
                extractor.advance()
            }
            return sampleCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Standard remux failed for ${sourceFile.name}: ${e.message}")
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun remuxAudioOnly(sourceFile: File, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    extractor.selectTrack(i)
                    muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer.addTrack(format)
                    muxer.start()
                    break
                }
            }
            if (audioIndex == -1 || muxer == null) return false
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastTime = -1L
            while (true) {
                if (extractor.sampleTrackIndex != audioIndex) {
                    if (extractor.sampleTrackIndex < 0) break
                    extractor.advance(); continue
                }
                info.offset = 0; info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                var time = extractor.sampleTime
                if (time <= lastTime) time = lastTime + 1000
                lastTime = time
                info.presentationTimeUs = time
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(0, buffer, info)
                extractor.advance()
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    fun muxVideoAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val vExtractor = MediaExtractor()
        val aExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vExtractor.setDataSource(videoFile.absolutePath)
            aExtractor.setDataSource(audioFile.absolutePath)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var vIndex = -1
            for (i in 0 until vExtractor.trackCount) {
                val format = vExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vIndex = muxer.addTrack(format); vExtractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                    break
                }
            }
            var aIndex = -1
            for (i in 0 until aExtractor.trackCount) {
                val format = aExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    aIndex = muxer.addTrack(format); aExtractor.selectTrack(i); break
                }
            }
            if (vIndex == -1 || aIndex == -1) return false
            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastVTime = -1L
            while (true) {
                info.offset = 0; info.size = vExtractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                var time = vExtractor.sampleTime
                if (time <= lastVTime) time = lastVTime + 1000
                lastVTime = time
                info.presentationTimeUs = time
                info.flags = if ((vExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(vIndex, buffer, info); vExtractor.advance()
            }
            var lastATime = -1L
            while (true) {
                info.offset = 0; info.size = aExtractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                var time = aExtractor.sampleTime
                if (time <= lastATime) time = lastATime + 1000
                lastATime = time
                info.presentationTimeUs = time
                info.flags = if ((aExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(aIndex, buffer, info); aExtractor.advance()
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { vExtractor.release() } catch (_: Exception) {}
            try { aExtractor.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    fun downloadMedia(context: Context, uri: Uri, isAutoSave: Boolean = false) {
        val resolver = context.contentResolver
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val originalName = docFile?.name ?: "Status_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: "video/mp4"
        val extension = if (mimeType.startsWith("video")) "mp4" else "jpg"
        val fileName = if (originalName.contains(".")) originalName else "$originalName.$extension"
        try {
            if (isAutoSave && isFileAlreadyAutoSaved(context, fileName)) return
            val inputStream = resolver.openInputStream(uri) ?: return
            val relativePath = when {
                mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + File.separator + "StatusHub"
                mimeType.startsWith("audio") -> Environment.DIRECTORY_MUSIC + File.separator + "StatusHub"
                else -> Environment.DIRECTORY_PICTURES + File.separator + "StatusHub"
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val collection = when {
                mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val dest = resolver.insert(collection, contentValues)
            if (dest != null) {
                resolver.openOutputStream(dest)?.use { out -> inputStream.use { it.copyTo(out) } }
                if (!isAutoSave) Toast.makeText(context, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                else markFileAsAutoSaved(context, fileName)
            }
        } catch (e: Exception) { Log.e(TAG, "Legacy download failed", e) }
    }

    private fun isFileAlreadyAutoSaved(context: Context, fileName: String): Boolean =
        context.getSharedPreferences("auto_save_prefs", Context.MODE_PRIVATE).getBoolean(fileName, false)

    private fun markFileAsAutoSaved(context: Context, fileName: String) =
        context.getSharedPreferences("auto_save_prefs", Context.MODE_PRIVATE).edit().putBoolean(fileName, true).apply()

    fun getDownloadedMedia(context: Context): List<Uri> {
        val mediaList = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else "${MediaStore.MediaColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("%StatusHub%")
        fun query(collection: Uri) {
            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) mediaList.add(Uri.withAppendedPath(collection, cursor.getLong(idCol).toString()))
                }
            } catch (e: Exception) { Log.e(TAG, "Query failed for $collection", e) }
        }
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) query(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        return mediaList
    }

    fun getDownloadedMediaRecords(context: Context): List<DownloadRecord> {
        val records = mutableListOf<DownloadRecord>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else "${MediaStore.MediaColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("%StatusHub%")
        fun query(collection: Uri) {
            try {
                context.contentResolver.query(collection, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: "Media"
                        val size = cursor.getLong(sizeCol); val date = cursor.getLong(dateCol) * 1000
                        val mime = cursor.getString(mimeCol) ?: ""
                        val uri = Uri.withAppendedPath(collection, cursor.getLong(idCol).toString())
                        records.add(DownloadRecord(sourceUrl = "Recovered", fileUri = uri.toString(), fileName = name, mediaType = if (mime.startsWith("video")) "video" else if (mime.startsWith("audio")) "audio" else "image", format = name.substringAfterLast(".", ""), totalBytes = size, downloadedBytes = size, timestamp = date, platform = "Internal", status = "COMPLETED"))
                    }
                }
            } catch (e: Exception) {}
        }
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        return records
    }

    /**
     * Formats file size in bytes to human readable format (B, KB, MB, GB).
     */
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    /**
     * Formats ETA in seconds to human readable format (H:MM:SS or M:SS).
     */
    fun formatEta(seconds: Long): String {
        return if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        } else {
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}