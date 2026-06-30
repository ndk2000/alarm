package com.ccsoft.alarm.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 云端分享记录 — 每次上传成功后保存，方便后续直接复用分享码而不需重新上传
 */
@Entity(tableName = "cloud_share_records")
data class CloudShareRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 云端返回的分享码 */
    val shareCode: String,
    /** 被分享的组名 */
    val groupName: String,
    /** 组内闹钟/打卡事项数量 */
    val itemCount: Int,
    /** 分享时间戳 */
    val shareTime: Long = System.currentTimeMillis(),
    /** 组类型: "alarm" 或 "checkin" */
    val groupType: String,
    /** 原始组 ID，用于点击记录跳转 */
    val sourceGroupId: Long
)
