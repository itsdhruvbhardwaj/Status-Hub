package com.dhruv.status.hub.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

fun downloadMedia(context: Context, uri: Uri, isAutoSave: Boolean = false) {
    val contentResolver = context.contentResolver
    
    // Get original filename to avoid duplicates during auto-save
    val docFile = DocumentFile.fromSingleUri(context, uri)
    val originalName = docFile?.name ?: "Status_${System.currentTimeMillis()}"
    
    val mimeType = contentResolver.getType(uri) ?: if (uri.toString().contains(".mp4")) "video/mp4" else "image/jpeg"
    val extension = if (mimeType.startsWith("video")) "mp4" else "jpg"
    
    // Use original name if available, otherwise fallback
    val fileName = if (originalName.contains(".")) originalName else "$originalName.$extension"

    try {
        // For auto-save, check if already logged
        if (isAutoSave && isFileAlreadyAutoSaved(context, fileName)) {
            return
        }

        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        if (inputStream == null) {
            if (!isAutoSave) Toast.makeText(context, "Failed to open status", Toast.LENGTH_SHORT).show()
            return
        }

        val relativePath = if (mimeType.startsWith("video")) {
            Environment.DIRECTORY_MOVIES + File.separator + "StatusHub"
        } else {
            Environment.DIRECTORY_PICTURES + File.separator + "StatusHub"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
        }

        val collection = if (mimeType.startsWith("video")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        // Check if file already exists in MediaStore to avoid duplicates
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        val cursor = contentResolver.query(collection, projection, selection, selectionArgs, null)
        val alreadyExists = cursor?.use { it.count > 0 } ?: false

        if (alreadyExists) {
            if (isAutoSave) markFileAsAutoSaved(context, fileName)
            inputStream.close()
            return
        }

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

fun getDownloadedMedia(context: Context): List<Uri> {
    val mediaList = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    
    val imageCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    } else {
        "${MediaStore.MediaColumns.DATA} LIKE ?"
    }

    val selectionArgs = arrayOf("%StatusHub%")

    fun queryCollection(collection: Uri) {
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(collection, id.toString())
                mediaList.add(contentUri)
            }
        }
    }

    queryCollection(imageCollection)
    queryCollection(videoCollection)
    
    return mediaList
}
