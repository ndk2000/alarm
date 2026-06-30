package com.ccsoft.alarm.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ccsoft.alarm.db.AlarmDatabase
import com.ccsoft.alarm.db.AlarmRepository
import com.ccsoft.alarm.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_HEARTBEAT = "com.ccsoft.alarm.alarm.ACTION_HEARTBEAT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive: ACTION = $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // ★ 关键：开机/更新时立即启动守护服务，确保闹钟准时响起
            AlarmGuardService.start(context)
            Log.d(TAG, "BOOT_COMPLETED: 已启动 AlarmGuardService")

            // Re-schedule all active alarms on boot or app update
            rescheduleAll(context)
        } else if (action == "com.ccsoft.alarm.alarm.ACTION_TRIGGER_ALARM") {
            // ★ 关键：每次闹钟触发时，确保守护服务在运行
            Log.w(TAG, "========== 收到闹钟触发广播 ALARM_ID=${intent.getLongExtra("ALARM_ID", -1L)} ==========")
            try {
                AlarmGuardService.start(context)
                Log.d(TAG, "ACTION_TRIGGER_ALARM: 已确保 AlarmGuardService 在运行")
            } catch (e: Exception) {
                Log.e(TAG, "启动守护服务失败: ${e.message}")
            }

            val alarmWakeLock = try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmReceiver:StartRinging").apply {
                    acquire(30_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取响铃启动唤醒锁失败: ${e.message}")
                null
            }

            val alarmId = intent.getLongExtra("ALARM_ID", -1L)
            val label = intent.getStringExtra("ALARM_LABEL") ?: "闹钟"
            val ringtone = intent.getStringExtra("ALARM_RINGTONE")
            val vibrate = intent.getBooleanExtra("ALARM_VIBRATE", true)
            val ringtoneDurationSecs = intent.getIntExtra("ALARM_DURATION_SECS", 0)

            if (alarmId != -1L) {
                // ★ 关键修复：直接启动 Activity，确保关屏时也能及时唤醒屏幕
                // 关屏/Doze 模式下，Service 启动可能延迟，直接启动 Activity 更可靠
                val activityIntent = Intent(context, AlarmActiveActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("ALARM_ID", alarmId)
                    putExtra("ALARM_LABEL", label)
                    putExtra("ALARM_RINGTONE", ringtone)
                    putExtra("ALARM_VIBRATE", vibrate)
                    putExtra("ALARM_DURATION_SECS", ringtoneDurationSecs)
                }
                try {
                    context.startActivity(activityIntent)
                    Log.w(TAG, "✓ 已直接启动 AlarmActiveActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "✗ 直接启动 Activity 失败: ${e.message}", e)
                    // 备用方案：发通知提醒用户
                    showFallbackNotification(context, label)
                }

                // 同时启动 Service 播放铃声（双重保障）
                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    setAction("START_RINGING")
                    putExtra("ALARM_ID", alarmId)
                    putExtra("ALARM_LABEL", label)
                    putExtra("ALARM_RINGTONE", ringtone)
                    putExtra("ALARM_VIBRATE", vibrate)
                    putExtra("ALARM_DURATION_SECS", ringtoneDurationSecs)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // 数据库校验 + 重新调度放到后台执行（不阻塞 Service 启动）
                val goAsync = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                        val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                        val alarm = repository.getAlarmById(alarmId)
                        val groups = repository.getGroupList()
                        val group = groups.find { it.id == alarm?.groupId }

                        // 关键加固：核对星期和开关状态
                        if (alarm != null && alarm.isEnabled && (group == null || group.isEnabled)) {
                            // 检查今天是否是设定的星期几
                            val cal = Calendar.getInstance()
                            val todayMapped = getMappedDayOfWeek(cal.get(Calendar.DAY_OF_WEEK))
                            val days = alarm.daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
                            
                            val isRightDay = days.isEmpty() || todayMapped in days
                            
                            if (!isRightDay) {
                                // 今天不是设定日 → 通知 Service 停止响铃
                                Log.w(TAG, "闹钟 $alarmId 被触发，但今天 (Mapped:$todayMapped) 不是设定的星期 ($days)，通知 Service 停止。")
                                val stopIntent = Intent(context, AlarmService::class.java).apply {
                                    setAction("STOP_RINGING")
                                    putExtra("ALARM_ID", alarmId)
                                }
                                context.startService(stopIntent)
                            } else {
                                Log.i(TAG, "闹钟 $alarmId 准时响铃 (Label: $label)")
                            }

                            // ★ 修复：直接在同一协程中重新调度，不启动新协程
                            // 确保 goAsync.finish() 只在重新调度完成后再调用
                            Log.d(TAG, "开始重新调度闹钟 $alarmId...")
                            rescheduleSingleInline(context, alarm)
                        } else {
                            Log.w(TAG, "闹钟 $alarmId 已在数据库中禁用，通知 Service 停止。")
                            val stopIntent = Intent(context, AlarmService::class.java).apply {
                                setAction("STOP_RINGING")
                                putExtra("ALARM_ID", alarmId)
                            }
                            context.startService(stopIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "核对闹钟状态失败", e)
                    } finally {
                        goAsync.finish()
                    }
                }
            }
        } else if (action == ACTION_HEARTBEAT) {
            // ★ 心跳广播：检查并启动守护服务，确保被杀后能自动恢复
            Log.w(TAG, "========== 收到心跳广播，检查守护服务 ==========")
            try {
                AlarmGuardService.start(context)
                Log.w(TAG, "✓ 心跳：已启动 AlarmGuardService")
            } catch (e: Exception) {
                Log.e(TAG, "✗ 心跳：启动 AlarmGuardService 失败: ${e.message}")
            }
            // ★ 关键：重新设置下一次心跳（确保心跳链不断）
            setupNextHeartbeat(context)
        } else if (action == "com.ccsoft.alarm.alarm.ACTION_TRIGGER_CHIME") {
            // ★ 关键：每次整点报时触发时，确保守护服务在运行
            AlarmGuardService.start(context)
            Log.d(TAG, "ACTION_TRIGGER_CHIME: 已确保 AlarmGuardService 在运行")

            // Hourly Chime triggered!
            val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            Log.d(TAG, "Hourly Chime triggered for hour: $nowHour (testMode=${AlarmScheduler.testModeActive})")

            val pendingResult = goAsync()
            val dbScope = CoroutineScope(Dispatchers.IO)
            dbScope.launch {
                try {
                    // 全局总开关检查
                    val prefs = PreferencesManager(context)
                    if (!prefs.isHourlyChimeMasterEnabled()) {
                        Log.d(TAG, "整点报时全局开关已关闭，跳过。")
                        return@launch
                    }

                    val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                    val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
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
                        // 从 PreferencesManager 读取 chime_style
                        val style = prefs.getChimeStyle()
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

    private fun getMappedDayOfWeek(calendarDay: Int): Int {
        return when (calendarDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 7
        }
    }

    private fun rescheduleAll(context: Context) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                
                // Reschedule everything by reading our standard groups and scheduling:
                val allGroups = repository.getGroupList()
                for (group in allGroups) {
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        AlarmScheduler.scheduleAlarm(context, alarm, group)
                    }
                }
                
                // Also reschedule hourly chime loop
                AlarmScheduler.scheduleNextHourlyChime(context)
                Log.d(TAG, "Successfully rescheduled all alarms and chimes on boot/update.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule on boot/update", e)
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
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
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

    // ★ 新增：在同一协程中直接重新调度，不启动新协程
    private suspend fun rescheduleSingleInline(context: Context, alarm: com.ccsoft.alarm.db.Alarm) {
        withContext(Dispatchers.IO) {
            try {
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                val groups = repository.getGroupList()
                val group = groups.find { it.id == alarm.groupId }

                if (alarm.daysOfWeek.isEmpty()) {
                    // One-time alarm, auto disable it since it just ran
                    val disabledAlarm = alarm.copy(isEnabled = false)
                    repository.updateAlarm(disabledAlarm)
                    Log.d(TAG, "一次性闹钟 ${alarm.id} 已响铃，自动禁用")
                } else {
                    // Repeating alarm, schedule next occurrence
                    val nextTime = AlarmScheduler.calculateNextAlarmTime(alarm)
                    AlarmScheduler.scheduleAlarm(context, alarm, group)
                    Log.d(TAG, "重复闹钟 ${alarm.id} 已重新调度，下次响铃: ${Date(nextTime)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inline re-schedule failed for ${alarm.id}", e)
            }
        }
    }

    /**
     * 备用方案：当 startActivity 失败时，发高优先级通知提醒用户
     */
    private fun showFallbackNotification(context: Context, label: String) {
        try {
            val channelId = "alarm_fallback_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "闹钟触发时的备用提醒"
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("⏰ $label")
                .setContentText("闹钟时间到！请打开 App 关闭闹钟。")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(9999, notification)
            Log.w(TAG, "✓ 已发送备用通知")
        } catch (e: Exception) {
            Log.e(TAG, "发送备用通知失败: ${e.message}")
        }
    }

    /**
     * 设置下一次心跳（确保心跳链不断）
     */
    private fun setupNextHeartbeat(context: Context) {
        try {
            val heartbeatIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 999, heartbeatIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L, // 1 分钟后下次心跳
                pendingIntent
            )
            Log.w(TAG, "✓ 已设置下一次心跳（1 分钟后）")
        } catch (e: Exception) {
            Log.e(TAG, "✗ 设置下一次心跳失败: ${e.message}")
        }
    }
}
