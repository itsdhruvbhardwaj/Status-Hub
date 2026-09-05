package com.dhruv.status.hub.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_records WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED', 'PROCESSING') ORDER BY timestamp DESC")
    fun getActiveRecords(): Flow<List<DownloadRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: DownloadRecord): Long

    @Update
    suspend fun updateRecord(record: DownloadRecord)

    @Delete
    suspend fun deleteRecord(record: DownloadRecord)

    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM download_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: Long): DownloadRecord?

    @Query("SELECT * FROM download_records WHERE sourceUrl = :url LIMIT 1")
    suspend fun getRecordByUrl(url: String): DownloadRecord?

    @Query("SELECT COUNT(*) FROM download_records WHERE status = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT * FROM download_records WHERE status = :status")
    suspend fun getDownloadsByStatus(status: String): List<DownloadRecord>

    @Query("SELECT * FROM download_records WHERE status = 'QUEUED' ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getNextQueued(limit: Int): List<DownloadRecord>

    @Query("UPDATE download_records SET downloadedBytes = :downloadedBytes WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedBytes: Long)
}
