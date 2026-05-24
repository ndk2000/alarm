package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.db.AlarmDatabase
import com.example.db.AlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive: ACTION = $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            // Re-schedule all active alarms on boot
            rescheduleAll(context)
        } else if (action == "com.example.alarm.ACTION_TRIGGER_ALARM") {
            val alarmId = intent.getLongExtra("ALARM_ID", -1L)
            val label = intent.getStringExtra("ALARM_LABEL") ?: "闹钟"
            val ringtone = intent.getStringExtra("ALARM_RINGTONE")
            val vibrate = intent.getBooleanExtra("ALARM_VIBRATE", true)

            if (alarmId != -1L) {
                // Launch the persistent alarm overlay/service to ring
                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    this.action = "START_RINGING"
                    putExtra("ALARM_ID", alarmId)
                    putExtra("ALARM_LABEL", label)
                    putExtra("ALARM_RINGTONE", ringtone)
                    putExtra("ALARM_VIBRATE", vibrate)
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Also open the full-screen ringing activity
                val activityIntent = Intent(context, AlarmActiveActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("ALARM_ID", alarmId)
                    putExtra("ALARM_LABEL", label)
                    putExtra("ALARM_RINGTONE", ringtone)
                    putExtra("ALARM_VIBRATE", vibrate)
                }
                context.startActivity(activityIntent)

                // Reschedule the same alarm if it's repeating
                rescheduleSingle(context, alarmId)
            }
        } else if (action == "com.example.alarm.ACTION_TRIGGER_CHIME") {
            // Hourly Chime triggered!
            val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            Log.d(TAG, "Hourly Chime triggered for hour: $nowHour (testMode=${AlarmScheduler.testModeActive})")

            val pendingResult = goAsync()
            val dbScope = CoroutineScope(Dispatchers.IO)
            dbScope.launch {
                try {
                    val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                    val repository = AlarmRepository(db.alarmDao())
                    val chimes = repository.getHourlyChimes()
                    val targetChime = chimes.find { it.hour == nowHour }

                    // 速测模式：直接播放当前小时报时（不依赖DB开关）
                    val shouldPlay = if (AlarmScheduler.testModeActive) {
                        true
                    } else {
                        targetChime != null && targetChime.isEnabled
                    }

                    if (shouldPlay) {
                        // 取配置（速测模式取第一个配置项作为默认值）
                        val useTts = targetChime?.useTts ?: (chimes.firstOrNull()?.useTts ?: true)
                        val vibrate = targetChime?.vibrate ?: (chimes.firstOrNull()?.vibrate ?: true)
                        // 从 SharedPreferences 读取 chime_style
                        val prefs = context.getSharedPreferences("chime_prefs", Context.MODE_PRIVATE)
                        val style = prefs.getInt("chime_style", 0)
                        Log.d(TAG, "Hourly Chime for $nowHour is ringing! (testMode=${AlarmScheduler.testModeActive}, useTts=$useTts, vibrate=$vibrate, style=$style)")
                        val chimeIntent = Intent(context, AlarmService::class.java).apply {
                            this.action = "TRIGGER_CHIME"
                            putExtra("CHIME_HOUR", nowHour)
                            putExtra("CHIME_USE_TTS", useTts)
                            putExtra("CHIME_VIBRATE", vibrate)
                            putExtra("CHIME_STYLE", style)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(chimeIntent)
                        } else {
                            context.startService(chimeIntent)
                        }
                    } else {
                        Log.d(TAG, "Hourly Chime for $nowHour not enabled (skipping).")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking hourly chime", e)
                } finally {
                    // Reschedule next hour (or next test interval)
                    AlarmScheduler.scheduleNextHourlyChime(context)
                    pendingResult.finish()
                }
            }
        }
    }

    private fun rescheduleAll(context: Context) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao())
                val groups = repository.getGroupList()
                val alarms = db.alarmDao().getAllAlarmsFlow() // wait, let's just query list directly
                val rawAlarms = db.alarmDao().getAllAlarmsFlow() // wait, we can't block. We can collect first element:
                // Let's query List directly
                
                // Let's create a direct list query in DAO soon, or query via a small helper:
                val allAlarmsList = db.alarmDao().getAllAlarmsFlow()
                // Let's just reschedule everything by reading our standard groups and scheduling:
                // Wait! Let's do it safely:
                val allGroups = repository.getGroupList()
                for (group in allGroups) {
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        AlarmScheduler.scheduleAlarm(context, alarm, group)
                    }
                }
                
                // Also reschedule hourly chime loop
                AlarmScheduler.scheduleNextHourlyChime(context)
                Log.d(TAG, "Successfully rescheduled all alarms and chimes on boot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun rescheduleSingle(context: Context, alarmId: Long) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao())
                val alarm = repository.getAlarmById(alarmId)
                if (alarm != null) {
                    val groups = repository.getGroupList()
                    val group = groups.find { it.id == alarm.groupId }
                    
                    if (alarm.daysOfWeek.isEmpty()) {
                        // One-time alarm, auto disable it since it just ran
                        val disabledAlarm = alarm.copy(isEnabled = false)
                        repository.updateAlarm(disabledAlarm)
                    } else {
                        // Repeating alarm, schedule next occurrence
                        AlarmScheduler.scheduleAlarm(context, alarm, group)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed single reschedule for $alarmId", e)
            }
        }
    }
}
