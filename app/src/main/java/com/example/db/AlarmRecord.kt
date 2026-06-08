package com.example.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alarm_records",
    indices = [Index(value = ["recordDate"], name = "index_alarm_records_recordDate")]
)
data class AlarmRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val label: String,
    val scheduledTime: Long,       // 计划响铃时间 (epoch ms)
    val recordDate: String,        // 日期 "2026-05-29"
    val dismissTime: Long? = null, // 手动关闭时间 (epoch ms)
    val status: String = "PENDING" // PENDING, COMPLETED, FAILED
)
