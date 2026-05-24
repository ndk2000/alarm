package com.example.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alarm_groups")
data class AlarmGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true
)

@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = AlarmGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["groupId"])]
)
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: String = "1,2,3,4,5,6,7", // Comma separated: 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun
    val isEnabled: Boolean = true,
    val label: String = "Alarm",
    val ringtonePath: String? = null, // Path to custom ringtone uploaded via WiFi or system default if null
    val vibrate: Boolean = true
) {
    // Helper to check if a specific day is selected
    fun isDayEnabled(day: Int): Boolean {
        return daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }.contains(day)
    }

    // Human-readable active days
    fun getActiveDaysDesc(): String {
        val days = daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }.sorted()
        if (days.size == 7) return "每天"
        if (days.size == 5 && days.containsAll(listOf(1, 2, 3, 4, 5)) && !days.contains(6) && !days.contains(7)) return "工作日"
        if (days.size == 2 && days.containsAll(listOf(6, 7))) return "周末"
        if (days.isEmpty()) return "仅一次"
        
        val weekNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return days.joinToString(" ") { weekNames[it] }
    }
}

@Entity(tableName = "hourly_chimes")
data class HourlyChime(
    @PrimaryKey val hour: Int, // 0 to 23
    val isEnabled: Boolean = false,
    val useTts: Boolean = true, // Speak "北京时间X点整" using TextToSpeech
    val vibrate: Boolean = true
)
