package com.ccsoft.alarm.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 打卡任务组 — 一个组包含一系列打卡事项，可一键转为闹钟
 */
@Entity(tableName = "check_in_groups")
data class CheckInGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = false,
    /** 自定义铃声路径（null=系统默认） */
    val ringtonePath: String? = null,
    /** 当启用时创建的 AlarmGroup ID，停用时删除 */
    val boundAlarmGroupId: Long = -1L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 打卡事项 — 组中的一个打卡步骤
 */
@Entity(
    tableName = "check_in_tasks",
    foreignKeys = [
        ForeignKey(
            entity = CheckInGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["groupId"])]
)
data class CheckInTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val hour: Int = 8,      // 提醒小时
    val minute: Int = 0,     // 提醒分钟
    val orderIndex: Int = 0, // 排序
    /** 自定义铃声路径（null=使用组默认铃声，组也为null则系统默认） */
    val ringtonePath: String? = null,
    /** 是否用 TTS 朗读任务文字代替铃声 */
    val useTts: Boolean = false
)
