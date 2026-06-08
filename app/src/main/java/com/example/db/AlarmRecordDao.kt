package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmRecordDao {
    @Query("SELECT * FROM alarm_records WHERE recordDate = :date ORDER BY scheduledTime ASC")
    suspend fun getRecordsByDate(date: String): List<AlarmRecord>

    @Query("SELECT * FROM alarm_records ORDER BY scheduledTime DESC")
    suspend fun getAllRecords(): List<AlarmRecord>

    @Query("SELECT DISTINCT recordDate FROM alarm_records ORDER BY recordDate DESC")
    suspend fun getAllDates(): List<String>

    @Insert
    suspend fun insert(record: AlarmRecord): Long

    @Query("UPDATE alarm_records SET status = :status, dismissTime = :dismissTime WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, dismissTime: Long?)

    @Query("DELETE FROM alarm_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM alarm_records")
    suspend fun deleteAll()

    @Query("DELETE FROM alarm_records WHERE recordDate = :date")
    suspend fun deleteByDate(date: String)

    @Query("SELECT * FROM alarm_records WHERE alarmId = :alarmId AND recordDate = :date LIMIT 1")
    suspend fun getRecord(alarmId: Long, date: String): AlarmRecord?
}
