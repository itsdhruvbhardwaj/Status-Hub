package com.dhruv.status.hub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val fileUri: String? = null,
    val fileName: String,
    val mediaType: String,
    val format: String,
    val quality: String = "Unknown",
    val bitrate: Int? = null, // Store selected MP3 bitrate
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val platform: String,
    val status: String, // "QUEUED", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED"
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val errorMessage: String? = null
)
