package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudShareDao {

    @Query("SELECT * FROM cloud_share_records ORDER BY shareTime DESC")
    fun getAllRecordsFlow(): Flow<List<CloudShareRecord>>

    @Query("SELECT * FROM cloud_share_records WHERE sourceGroupId = :groupId AND groupType = :groupType ORDER BY shareTime DESC LIMIT 1")
    suspend fun getLatestRecord(groupId: Long, groupType: String): CloudShareRecord?

    @Insert
    suspend fun insertRecord(record: CloudShareRecord): Long

    @Delete
    suspend fun deleteRecord(record: CloudShareRecord)

    @Query("DELETE FROM cloud_share_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)
}
