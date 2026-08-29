package com.dhruv.status.hub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a record in the download history.
 */
@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val fileUri: String,
    val fileName: String,
    val mediaType: String, // "video", "audio", "image"
    val format: String,    // e.g., "mp4", "mp3"
    val fileSize: Long,
    val timestamp: Long,   // Download time in milliseconds
    val platform: String,  // "direct", "instagram", "facebook", "youtube"
    val status: String     // "success", "failed", "pending"
)
