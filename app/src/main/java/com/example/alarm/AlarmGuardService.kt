package com.example.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AlarmDatabase
import com.example.db.AlarmRepository
import com.example.db.CheckInDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 闹钟守护前台服务
 *
 * 常驻后台，周期性验证所有启用闹钟的调度状态。
 * 防止厂商省电策略拦截 AlarmManager 导致闹钟不响。
 *
 * 核心策略：
 * 1. 持续运行的前台服务（START_STICKY + 低优先级静默通知）
 * 2. 每 30 秒重新调度所有启用闹钟（setAlarmClock 幂等，重复调用无副作用）
 * 3. 确保整点报时链条不被切断
 * 4. 持有唤醒锁，防止 CPU 休眠导致 Handler 被延迟
 */
class AlarmGuardService : Service() {

    companion object {
        private const val TAG = "AlarmGuard"
        private const val CHANNEL_ID = "alarm_guard_channel"
        private const val NOTIFICATION_ID = 2
        private const val CHECK_INTERVAL_MS = 30_000L // 30 秒检查一次

        fun start(context: Context) {
            val intent = Intent(context, AlarmGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "发出启动守护服务请求")
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmGuardService::class.java))
            Log.d(TAG, "发出停止守护服务请求")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    private val guardRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                verifyAndReschedule()
            } catch (e: Exception) {
                Log.e(TAG, "守护检查异常", e)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    // ── 滴答声管理：每秒检测最近闹钟，<2 分钟自动播放 ──
    private val tickTockRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                checkAndPlayTickTock()
            } catch (e: Exception) {
                Log.e(TAG, "滴答声检查异常", e)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "守护服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "守护服务 onStartCommand, flags=$flags, startId=$startId")

        // 处理停止守护动作
        if (intent?.action == "STOP_GUARD") {
            Log.d(TAG, "用户手动停止守护服务")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (!isRunning) {
            isRunning = true
            handler.post(guardRunnable)
            handler.post(tickTockRunnable)
            Log.d(TAG, "守护循环已启动，间隔 ${CHECK_INTERVAL_MS}ms，滴答声每秒检测")
        }

        return START_STICKY // 被杀死后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(guardRunnable)
        handler.removeCallbacks(tickTockRunnable)
        ChimeGenerator.stopTickTock()
        releaseWakeLock()
        Log.d(TAG, "守护服务已销毁")
        super.onDestroy()
    }

    // ── 核心：验证并重新调度所有闹钟 ──

    private fun verifyAndReschedule() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AlarmDatabase.getDatabase(this@AlarmGuardService, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())

                val groups = repository.getGroupList()

                var rescheduledCount = 0

                for (group in groups) {
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        if (alarm.isEnabled && group.isEnabled) {
                            AlarmScheduler.scheduleAlarm(this@AlarmGuardService, alarm, group)
                            rescheduledCount++
                        }
                    }
                }

                // 确保整点报时链条不断
                AlarmScheduler.scheduleNextHourlyChime(this@AlarmGuardService)

                if (rescheduledCount > 0) {
                    Log.d(TAG, "守护检查完成，已确认 $rescheduledCount 个闹钟调度")
                }
            } catch (e: Exception) {
                Log.e(TAG, "验证重调度失败", e)
            }
        }
    }

    // ── 滴答声：检测最近闹钟是否在 2 分钟内 ──
    private fun checkAndPlayTickTock() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AlarmDatabase.getDatabase(this@AlarmGuardService, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                val groups = repository.getGroupList()
                val enabledGroupIds = groups.filter { it.isEnabled }.map { it.id }.toSet()

                val now = System.currentTimeMillis()
                var nearestSec = Long.MAX_VALUE

                for (group in groups) {
                    if (!group.isEnabled) continue
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        if (!alarm.isEnabled) continue
                        val next = AlarmScheduler.calculateNextAlarmTime(alarm)
                        val sec = (next - now) / 1000
                        if (sec > 0 && sec < nearestSec) {
                            nearestSec = sec
                        }
                    }
                }

                // 计算今天的近最近闹钟
                val calNow = Calendar.getInstance()
                val todayWeekDay = when (calNow.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
                    Calendar.SUNDAY -> 7; else -> 7
                }

                for (group in groups) {
                    if (!group.isEnabled) continue
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        if (!alarm.isEnabled) continue
                        val days = alarm.daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
                        val isToday = days.isEmpty() || todayWeekDay in days
                        if (!isToday) continue

                        val todayTarget = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, alarm.hour)
                            set(Calendar.MINUTE, alarm.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val sec = (todayTarget.timeInMillis - now) / 1000
                        if (sec in 0 until nearestSec) {
                            nearestSec = sec
                        }
                    }
                }

                if (nearestSec in 0..120) {
                    ChimeGenerator.playTickTockContinuous()
                } else {
                    ChimeGenerator.stopTickTock()
                }
            } catch (e: Exception) {
                Log.e(TAG, "滴答声检测失败", e)
            }
        }
    }

    // ── 通知 ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "闹钟守护",
                NotificationManager.IMPORTANCE_LOW // 无声音、无弹窗
            ).apply {
                description = "确保闹钟准时响起的后台守护服务"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        // 停止守护按钮
        val stopIntent = Intent(this, AlarmGuardService::class.java).apply {
            action = "STOP_GUARD"
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("闹钟守护中")
            .setContentText("正在确保闹钟准时响起")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止守护", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ── 唤醒锁 ──

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AlarmGuard:GuardWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 最长持有 10 分钟，之后自动释放防止耗电
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }
}
