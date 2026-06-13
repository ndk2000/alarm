package com.example.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.db.Alarm
import com.example.db.AlarmGroup
import java.util.*

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    private fun canScheduleExactAlarmsReflective(alarmManager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            val method = AlarmManager::class.java.getDeclaredMethod("canScheduleExactAlarms")
            method.invoke(alarmManager) as? Boolean ?: true
        } catch (e: Exception) {
            true
        } catch (t: Throwable) {
            true
        }
    }

    // Map Calendar.DAY_OF_WEEK (Sun=1, Mon=2...) to User Format (Mon=1, Tue=2, Wed=3, Thu=4, Fri=5, Sat=6, Sun=7)
    private fun getMappedDayOfWeek(calendarDay: Int): Int {
        return when (calendarDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 7 // 确保周日始终映射为 7
        }
    }

    fun calculateNextAlarmTime(alarm: Alarm): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val days = alarm.daysOfWeek.split(",")
            .filter { it.isNotEmpty() }
            .map { it.toInt() }

        if (days.isEmpty()) {
            // One-time alarm
            if (target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        }

        // Repeating alarm - find the next active day
        var minTime = Long.MAX_VALUE
        val todayMapped = getMappedDayOfWeek(now.get(Calendar.DAY_OF_WEEK))

        for (day in days) {
            val testCal = Calendar.getInstance().apply {
                timeInMillis = target.timeInMillis
            }
            
            var daysDifference = day - todayMapped
            if (daysDifference < 0) {
                daysDifference += 7
            } else if (daysDifference == 0) {
                // 如果是今天，检查时间是否已经过了
                if (testCal.before(now)) {
                    daysDifference = 7
                }
            }

            testCal.add(Calendar.DAY_OF_YEAR, daysDifference)
            if (testCal.timeInMillis < minTime) {
                minTime = testCal.timeInMillis
            }
        }
        return minTime
    }

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleAlarm(context: Context, alarm: Alarm, group: AlarmGroup?) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // If the group or the alarm is disabled, cancel it.
        if (group?.isEnabled == false || !alarm.isEnabled) {
            cancelAlarm(context, alarm.id)
            return
        }

        val triggerAtMillis = calculateNextAlarmTime(alarm)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.alarm.ACTION_TRIGGER_ALARM"
            putExtra("ALARM_ID", alarm.id)
            putExtra("ALARM_LABEL", alarm.label)
            putExtra("ALARM_RINGTONE", alarm.ringtonePath)
            putExtra("ALARM_VIBRATE", alarm.vibrate)
            putExtra("ALARM_DURATION_SECS", alarm.ringtoneDurationSecs)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            flags
        )

        // 使用 setAlarmClock 确保准时触发——不依赖 SCHEDULE_EXACT_ALARM 权限，
        // 且在 Doze 模式下也不会被延迟。这是闹钟 App 最可靠的方案。
        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "Scheduled alarm ${alarm.id} via setAlarmClock at ${Date(triggerAtMillis)}")
        } catch (e: Exception) {
            // 极少数情况下 setAlarmClock 也失败时，降级
            Log.e(TAG, "setAlarmClock failed, falling back", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    fun cancelAlarm(context: Context, alarmId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.alarm.ACTION_TRIGGER_ALARM"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            flags
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Canceled alarm $alarmId")
    }

    // Schedule the next active Hourly Chime (e.g. schedules the next upcoming top of hour other than currently active ones or standard flow)
    @SuppressLint("ScheduleExactAlarm")
    fun scheduleNextHourlyChime(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Target is the top of the next hour (or test-mode interval)
        val now = Calendar.getInstance()
        val triggerAtMillis = if (testModeActive) {
            now.timeInMillis + testModeIntervalSecs * 1000L
        } else {
            Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.alarm.ACTION_TRIGGER_CHIME"
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999, // Constant code for hourly chimes
            intent,
            flags
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (canScheduleExactAlarmsReflective(alarmManager)) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled Hourly Chime at ${Date(triggerAtMillis)}")
        } catch (e: SecurityException) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    // --- Test mode helpers ---
    @Volatile
    var testModeActive: Boolean = false
    var testModeIntervalSecs: Long = 5L

    fun isChimeTestModeActive(): Boolean = testModeActive

    fun cancelChimeAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.alarm.ACTION_TRIGGER_CHIME"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            flags
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled chime alarm (code=9999)")
    }
}
