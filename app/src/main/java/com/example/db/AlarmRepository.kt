package com.example.db

import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val alarmDao: AlarmDao) {

    val allGroups: Flow<List<AlarmGroup>> = alarmDao.getAllGroupsFlow()
    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarmsFlow()
    val allHourlyChimes: Flow<List<HourlyChime>> = alarmDao.getAllHourlyChimesFlow()

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
}
