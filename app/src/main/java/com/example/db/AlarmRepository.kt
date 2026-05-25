package com.example.db

import kotlinx.coroutines.flow.Flow
import com.example.db.CheckInDao
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity

class AlarmRepository(
    private val alarmDao: AlarmDao,
    private val checkinDao: CheckInDao
) {

    val allGroups: Flow<List<AlarmGroup>> = alarmDao.getAllGroupsFlow()
    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarmsFlow()
    val allHourlyChimes: Flow<List<HourlyChime>> = alarmDao.getAllHourlyChimesFlow()
    val allCheckInGroups: Flow<List<CheckInGroupEntity>> = checkinDao.getAllGroupsFlow()

    suspend fun getGroupList(): List<AlarmGroup> {
        return alarmDao.getAllGroups()
    }

    suspend fun getAlarmsByGroup(groupId: Long): List<Alarm> {
        return alarmDao.getAlarmsByGroup(groupId)
    }

    fun getAlarmsByGroupFlow(groupId: Long): Flow<List<Alarm>> {
        return alarmDao.getAlarmsByGroupFlow(groupId)
    }

    suspend fun insertGroup(group: AlarmGroup): Long {
        return alarmDao.insertGroup(group)
    }

    suspend fun updateGroup(group: AlarmGroup) {
        alarmDao.updateGroup(group)
    }

    suspend fun deleteGroup(group: AlarmGroup) {
        alarmDao.deleteGroup(group)
    }

    suspend fun insertAlarm(alarm: Alarm): Long {
        return alarmDao.insertAlarm(alarm)
    }

    suspend fun getAlarmById(id: Long): Alarm? {
        return alarmDao.getAlarmById(id)
    }

    suspend fun updateAlarm(alarm: Alarm) {
        alarmDao.updateAlarm(alarm)
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        alarmDao.deleteAlarm(alarm)
    }

    suspend fun updateHourlyChime(chime: HourlyChime) {
        alarmDao.updateHourlyChime(chime)
    }

    suspend fun getHourlyChimes(): List<HourlyChime> {
        return alarmDao.getAllHourlyChimes()
    }

    fun getCheckInTasksByGroupFlow(groupId: Long): Flow<List<CheckInTaskEntity>> {
        return checkinDao.getTasksByGroupFlow(groupId)
    }

    suspend fun insertCheckInGroup(group: CheckInGroupEntity): Long {
        return checkinDao.insertGroup(group)
    }

    suspend fun updateCheckInGroup(group: CheckInGroupEntity) {
        checkinDao.updateGroup(group)
    }

    suspend fun deleteCheckInGroup(group: CheckInGroupEntity) {
        checkinDao.deleteGroup(group)
    }

    suspend fun insertCheckInTask(task: CheckInTaskEntity): Long {
        return checkinDao.insertTask(task)
    }

    suspend fun insertCheckInTasks(tasks: List<CheckInTaskEntity>) {
        checkinDao.insertTasks(tasks)
    }

    suspend fun deleteCheckInTasksByGroup(groupId: Long) {
        checkinDao.deleteTasksByGroup(groupId)
    }

    suspend fun getCheckInTasksByGroup(groupId: Long): List<CheckInTaskEntity> {
        return checkinDao.getTasksByGroup(groupId)
    }
}
