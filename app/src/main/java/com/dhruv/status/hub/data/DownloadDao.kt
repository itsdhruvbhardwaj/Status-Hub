package com.dhruv.status.hub.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<DownloadRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: DownloadRecord): Long

    @Update
    suspend fun updateRecord(record: DownloadRecord)

    @Delete
    suspend fun deleteRecord(record: DownloadRecord)

    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM download_records WHERE sourceUrl = :url LIMIT 1")
    suspend fun getRecordByUrl(url: String): DownloadRecord?
}
