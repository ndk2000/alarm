package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    // Alarm Group Queries
    @Query("SELECT * FROM alarm_groups ORDER BY id ASC")
    fun getAllGroupsFlow(): Flow<List<AlarmGroup>>

    @Query("SELECT * FROM alarm_groups ORDER BY id ASC")
    suspend fun getAllGroups(): List<AlarmGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: AlarmGroup): Long

    @Update
    suspend fun updateGroup(group: AlarmGroup)

    @Delete
    suspend fun deleteGroup(group: AlarmGroup)

    // Alarm Queries
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun getAllAlarmsFlow(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE groupId = :groupId ORDER BY hour ASC, minute ASC")
    fun getAlarmsByGroupFlow(groupId: Long): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE groupId = :groupId ORDER BY hour ASC, minute ASC")
    suspend fun getAlarmsByGroup(groupId: Long): List<Alarm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: Alarm): Long

    @Update
    suspend fun updateAlarm(alarm: Alarm)

    @Delete
    suspend fun deleteAlarm(alarm: Alarm)

    @Query("SELECT * FROM alarms")
    suspend fun getAllAlarms(): List<Alarm>

    // Hourly Chime Queries
    @Query("SELECT * FROM hourly_chimes ORDER BY hour ASC")
    fun getAllHourlyChimesFlow(): Flow<List<HourlyChime>>

    @Query("SELECT * FROM hourly_chimes ORDER BY hour ASC")
    suspend fun getAllHourlyChimes(): List<HourlyChime>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHourlyChimes(chimes: List<HourlyChime>)

    @Update
    suspend fun updateHourlyChime(chime: HourlyChime)
}
