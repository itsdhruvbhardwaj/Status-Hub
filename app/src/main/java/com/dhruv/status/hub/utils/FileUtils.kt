package com.dhruv.status.hub.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * Utility for file operations in Status Hub.
 * Optimized for accurate MediaStore routing and format detection.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    /**
     * Detects actual file extension from magic bytes (file signature).
     * Used to prevent incorrect .mp3 naming of non-MP3 files.
     */
    fun detectExtensionFromMagicBytes(file: File): String? {
        val buffer = ByteArray(12)
        try {
            file.inputStream().use { it.read(buffer) }
        } catch (e: Exception) {
            return null
        }
        
        val hex = buffer.joinToString("") { "%02X".format(it) }
        
        return when {
            // MP3: ID3 tag or Sync frames
            hex.startsWith("494433") -> "mp3"
            hex.startsWith("FFFB") || hex.startsWith("FFF3") || hex.startsWith("FFF2") -> "mp3"
            
            // MP4 / M4A: ftyp atom usually at offset 4
            hex.length >= 24 && hex.substring(8, 16) == "66747970" -> {
                // Common brands: M4A , mp42, isom, dash
                val brand = hex.substring(16, 24)
                if (brand == "4D344120") "m4a" else "mp4"
            }
            
            // WebM / Matroska: EBML header
            hex.startsWith("1A45DFA3") -> "webm"
            
            // OGG
            hex.startsWith("4F676753") -> "ogg"
            
            // WAV
            hex.length >= 32 && hex.startsWith("52494646") && hex.substring(16, 24).startsWith("57415645") -> "wav"
            
            // Images
            hex.startsWith("89504E47") -> "png"
            hex.startsWith("FFD8FF") -> "jpg"
            hex.startsWith("47494638") -> "gif"
            
            else -> null
        }
    }

    /**
     * Saves a temporary file from cache to the public MediaStore.
     * Ensures files are routed to the correct system folders and have accurate MIME types.
     */
    fun saveTempFileToMediaStore(
        context: Context,
        tempFile: File,
        fileName: String,
        mediaType: String
    ): Uri? {
        val resolver = context.contentResolver
        
        // 1. Validate extension against actual content
        val detectedExt = detectExtensionFromMagicBytes(tempFile)
        val currentExt = fileName.substringAfterLast(".", "").lowercase()
        
        var finalFileName = fileName
        if (detectedExt != null && detectedExt != currentExt) {
            // If the user requested MP3 but the file is something else, use the correct extension
            if (currentExt == "mp3") {
                val base = fileName.substringBeforeLast(".")
                finalFileName = "$base.$detectedExt"
                Log.d(TAG, "Correcting extension from $currentExt to $detectedExt based on magic bytes")
            }
        }

        val lowerFileName = finalFileName.lowercase()
        
        // 2. Determine actual system media category
        // IMPORTANT: WebM and MP4 are technically video containers. 
        // Android requires them in the Video collection to avoid "Unsupported MIME type" errors.
        val isVideoContainer = lowerFileName.endsWith(".mp4") || lowerFileName.endsWith(".webm")

        val actualCategory = when {
            isVideoContainer -> "video" // Force containers to Video collection for system compatibility
            lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".m4a") || 
            lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg") ||
            mediaType.lowercase() == "audio" -> "audio"
            else -> mediaType.lowercase()
        }

        // 3. Map to specific MIME type
        val mimeType = when {
            lowerFileName.endsWith(".mp3") -> "audio/mpeg"
            lowerFileName.endsWith(".m4a") -> "audio/mp4"
            lowerFileName.endsWith(".wav") -> "audio/x-wav"
            lowerFileName.endsWith(".ogg") -> "audio/ogg"
            lowerFileName.endsWith(".mp4") -> "video/mp4"
            lowerFileName.endsWith(".webm") -> "video/webm" // Always use video/webm to avoid rejection
            lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") -> "image/jpeg"
            lowerFileName.endsWith(".png") -> "image/png"
            else -> if (actualCategory == "audio") "audio/mpeg" else "video/mp4"
        }

        val relativePath = when (actualCategory) {
            "video" -> Environment.DIRECTORY_MOVIES + File.separator + "StatusHub"
            "audio" -> Environment.DIRECTORY_MUSIC + File.separator + "StatusHub"
            else -> Environment.DIRECTORY_PICTURES + File.separator + "StatusHub"
        }

        val collection = when (actualCategory) {
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, contentValues)
        } catch (e: Exception) {
            Log.e(TAG, "Initial insertion failed: ${e.message}")
            // Fallback: If Audio collection rejected it, try Video collection as it's more permissive for containers
            if (collection == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) {
                try {
                    val fallbackValues = ContentValues(contentValues).apply {
                        if (isVideoContainer) {
                            put(MediaStore.MediaColumns.MIME_TYPE, if (lowerFileName.endsWith(".webm")) "video/webm" else "video/mp4")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "StatusHub")
                        }
                    }
                    uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fallbackValues)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback insertion also failed")
                }
            }
        }

        if (uri == null) return null

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updatedValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, updatedValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e(TAG, "Failed to save media content", e)
            return null
        }
    }

    /**
     * Legacy support for downloading from URI (Status Saver part).
     */
    fun downloadMedia(context: Context, uri: Uri, isAutoSave: Boolean = false) {
        val contentResolver = context.contentResolver
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val originalName = docFile?.name ?: "Status_${System.currentTimeMillis()}"
        val mimeType = contentResolver.getType(uri) ?: if (uri.toString().contains(".mp4")) "video/mp4" else "image/jpeg"
        val extension = if (mimeType.startsWith("video")) "mp4" else "jpg"
        val fileName = if (originalName.contains(".")) originalName else "$originalName.$extension"

        try {
            if (isAutoSave && isFileAlreadyAutoSaved(context, fileName)) return

            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                if (!isAutoSave) Toast.makeText(context, "Failed to open status", Toast.LENGTH_SHORT).show()
                return
            }

            val relativePath = if (mimeType.startsWith("video")) {
                Environment.DIRECTORY_MOVIES + File.separator + "StatusHub" + File.separator + "Videos"
            } else {
                Environment.DIRECTORY_PICTURES + File.separator + "StatusHub" + File.separator + "Images"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
            }

            val collection = if (mimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val destinationUri = contentResolver.insert(collection, contentValues)

            if (destinationUri != null) {
                contentResolver.openOutputStream(destinationUri).use { outputStream ->
                    if (outputStream != null) {
                        inputStream.copyTo(outputStream)
                        if (!isAutoSave) Toast.makeText(context, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                        if (isAutoSave) markFileAsAutoSaved(context, fileName)
                    }
                }
            }
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
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
        val imageCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val audioCollection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%StatusHub%")

        fun queryCollection(collection: Uri) {
            context.contentResolver.query(collection, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    mediaList.add(Uri.withAppendedPath(collection, id.toString()))
                }
            }
        }
        queryCollection(imageCollection)
        queryCollection(videoCollection)
        queryCollection(audioCollection)
        return mediaList
    }
}
