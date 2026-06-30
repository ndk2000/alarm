package com.ccsoft.alarm.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInDao {

    // ─── 打卡组 ───

    @Query("SELECT * FROM check_in_groups ORDER BY createdAt DESC")
    fun getAllGroupsFlow(): Flow<List<CheckInGroupEntity>>

    @Query("SELECT * FROM check_in_groups ORDER BY createdAt DESC")
    suspend fun getAllGroups(): List<CheckInGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: CheckInGroupEntity): Long

    @Update
    suspend fun updateGroup(group: CheckInGroupEntity)

    @Delete
    suspend fun deleteGroup(group: CheckInGroupEntity)

    @Query("SELECT * FROM check_in_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): CheckInGroupEntity?

    // ─── 打卡事项 ───

    @Query("SELECT * FROM check_in_tasks WHERE groupId = :groupId ORDER BY orderIndex ASC")
    fun getTasksByGroupFlow(groupId: Long): Flow<List<CheckInTaskEntity>>

    @Query("SELECT * FROM check_in_tasks WHERE groupId = :groupId ORDER BY orderIndex ASC")
    suspend fun getTasksByGroup(groupId: Long): List<CheckInTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: CheckInTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<CheckInTaskEntity>)

    @Update
    suspend fun updateTask(task: CheckInTaskEntity)

    @Delete
    suspend fun deleteTask(task: CheckInTaskEntity)

    @Query("DELETE FROM check_in_tasks WHERE groupId = :groupId")
    suspend fun deleteTasksByGroup(groupId: Long)

    @Query("SELECT * FROM check_in_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): CheckInTaskEntity?
}
