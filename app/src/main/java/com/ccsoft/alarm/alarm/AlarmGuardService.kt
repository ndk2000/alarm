package com.ccsoft.alarm.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import android.media.AudioTrack
import android.media.AudioFormat
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.ccsoft.alarm.MainActivity
import com.ccsoft.alarm.db.AlarmDatabase
import com.ccsoft.alarm.db.AlarmRepository
import com.ccsoft.alarm.service.ServiceStatusMonitor
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.SERVICE_GUARD
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.SERVICE_WARNING
import com.ccsoft.alarm.util.PreferencesManager
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 闹钟守护服务 (前台服务)
 */
class AlarmGuardService : Service() {

    private val TAG = "AlarmGuard"
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 预警音相关
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasInWarning = false
    private var warningRepeatJob: Job? = null
    private var lastWarningCheckTime = 0L

    companion object {
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
            Log.d("AlarmGuard", "发出启动守护服务请求")
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmGuardService::class.java))
            Log.d("AlarmGuard", "发出停止守护服务请求")
        }
    }

    private val guardRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                verifyAndReschedule()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        acquireWakeLock()
        handler.post(guardRunnable)
        // ★ 设置心跳：每 1 分钟通过 AlarmManager 发送心跳广播，确保被杀后能自动恢复
        setupHeartbeat()
        // ★ 启动预警音检查协程（每 1 秒检查一次）
        startWarningCheck()
        Log.w(TAG, "========== 守护服务已创建，心跳已设置 ==========")
        
        // 发送守护服务启动广播
        sendServiceStatusBroadcast(SERVICE_GUARD, true)
        Log.i(TAG, "已发送守护服务启动状态广播")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand Action: $action")

        if (action == "REFRESH_AND_REPORT") {
            Log.i(TAG, "收到立即刷新请求，开始同步状态并生成报告...")
            verifyAndReschedule()
        }

        if (action == "STOP_GUARD") {
            Log.d(TAG, "用户手动停止守护服务")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 保留接口，暂不处理请求状态报告

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 当用户划掉 App 时，系统会调用此方法。
     * 必须立即重启守护服务，否则闹钟调度会丢失。
     * 用 AlarmManager 发送一个广播，在广播里启动服务（最可靠的方式）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "========== onTaskRemoved: 用户划掉了 App，安排重启守护服务 ==========")
        try {
            // 用 AlarmManager 延迟 2 秒发送广播，确保当前进程完全死亡后再启动新进程
            val restartIntent = Intent(applicationContext, AlarmGuardRestartReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, 0, restartIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2000L,
                pendingIntent
            )
            Log.w(TAG, "onTaskRemoved: ✓ 已安排 2 秒后重启守护服务")
        } catch (e: Exception) {
            Log.e(TAG, "onTaskRemoved: ✗ 安排重启失败: ${e.message}")
        }
    }

    // ── 核心：验证并重新调度所有闹钟 ──

    private var isPerformingRebuild = false

    private fun verifyAndReschedule() {
        if (isPerformingRebuild) {
            Log.d(TAG, "上一次自检任务尚未完成，跳过本次周期性检查")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                isPerformingRebuild = true
                val db = AlarmDatabase.getDatabase(this@AlarmGuardService, CoroutineScope(Dispatchers.IO))
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())

                val groups = repository.getGroupList()
                Log.i(TAG, "守护自检开始，数据库中共找到 ${groups.size} 个闹钟组")

                var scheduledCount = 0
                var canceledCount = 0
                val stats = StringBuilder()

                // 第一阶段：立即执行闹钟调度（最高优先级，保证必响）
                // 先让 AlarmManager 跑起来，不被后面的耗时任务（如 TTS 重建）阻塞
                for (group in groups) {
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    for (alarm in groupAlarms) {
                        val isEffectiveEnabled = alarm.isEnabled && group.isEnabled
                        val nextTime = AlarmScheduler.calculateNextAlarmTime(alarm)
                        val diffMillis = nextTime - System.currentTimeMillis()
                        
                        // 除非已经响铃或过点，否则强制同步状态给系统
                        if (diffMillis > 0) {
                            AlarmScheduler.scheduleAlarm(this@AlarmGuardService, alarm, group)
                            if (isEffectiveEnabled) {
                                scheduledCount++
                                Log.d(TAG, "  守护调度: 闹钟 ${alarm.id} (${alarm.label}) 下次响铃: ${Date(nextTime)} (${(diffMillis / 1000)}秒后)")
                            } else {
                                canceledCount++
                                Log.d(TAG, "  守护取消: 闹钟 ${alarm.id} (${alarm.label}) 已禁用")
                            }
                        } else if (diffMillis <= 0 && isEffectiveEnabled) {
                            // 已经过了响铃时间但还没触发，立即触发一次
                            Log.w(TAG, "  守护警告: 闹钟 ${alarm.id} (${alarm.label}) 的响铃时间 ${Date(nextTime)} 已过，但还未触发！立即重新调度...")
                            AlarmScheduler.scheduleAlarm(this@AlarmGuardService, alarm, group)
                            scheduledCount++
                        }
                    }
                }
                
                // 第二阶段：后台扫描并重建缺失的语音文件
                for (group in groups) {
                    val groupAlarms = repository.getAlarmsByGroup(group.id)
                    val groupEnabled = group.isEnabled
                    val activeAlarms = groupAlarms.filter { it.isEnabled && groupEnabled }
                    
                    if (activeAlarms.isNotEmpty()) {
                        stats.append("\n   - 组 [${group.name}] (${activeAlarms.size} 个开启):")
                        for (a in activeAlarms) {
                            var currentPath = a.ringtonePath
                            var statusLabel = ""
                            
                            // 发现缺失直接重建
                            if (currentPath == "__TTS__" || (currentPath != null && currentPath.contains("task_"))) {
                                val cacheFile = TtsTaskPlayer.getCacheFile(this@AlarmGuardService, a.label)
                                if (cacheFile == null || !cacheFile.exists() || cacheFile.length() < 1024) {
                                    Log.w(TAG, "语音缺失: [${a.label}] -> 开始重建...")
                                    // 延长超时，防止大批量排队失败
                                    val newPath = TtsTaskPlayer.generateSync(this@AlarmGuardService, a.label, timeoutMs = 60000L)
                                    if (newPath != null) statusLabel = "[现场重建成功]" else statusLabel = "[重建失败/超时]"
                                }
                                
                                if (currentPath != "__TTS__") {
                                    db.alarmDao().updateAlarm(a.copy(ringtonePath = "__TTS__"))
                                    currentPath = "__TTS__"
                                }
                            }
                            
                            val ringtoneDisplay = when {
                                currentPath == "__TTS__" -> {
                                    val f = TtsTaskPlayer.getCacheFile(this@AlarmGuardService, a.label)
                                    val size = if (f?.exists() == true) "${f.length() / 1024}KB" else "异常"
                                    val p = f?.absolutePath ?: "未知"
                                    "🔊 TTS ($size) $statusLabel | 路径: $p"
                                }
                                currentPath == null -> "🔔 系统默认"
                                else -> {
                                    val f = java.io.File(currentPath)
                                    if (f.exists()) "🎵 ${currentPath.substringAfterLast("/")} (${f.length() / 1024}KB) | 路径: $currentPath"
                                    else "文件丢失 | 路径: $currentPath"
                                }
                            }
                            stats.append("\n     * [${a.label}] ${String.format(Locale.getDefault(), "%02d:%02d", a.hour, a.minute)} (${a.getActiveDaysDesc()}) -> $ringtoneDisplay")
                        }
                    }
                }
                
                AlarmScheduler.scheduleNextHourlyChime(this@AlarmGuardService)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(this@AlarmGuardService)

                if (scheduledCount > 0 || canceledCount > 0) {
                    Log.i(TAG, "守护自检完成: $scheduledCount 个活跃, $canceledCount 个已取消。明细 $stats")
                } else {
                    Log.i(TAG, "守护自检完成: 无活跃闹钟需要调度")
                }
            } catch (e: Exception) {
                Log.e(TAG, "自检过程异常", e)
            } finally {
                isPerformingRebuild = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "闹钟守护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "确保闹钟准时响起的后台守护服务"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val stopIntent = Intent(this, AlarmGuardService::class.java).apply { action = "STOP_GUARD" }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏰ 闹钟守护服务运行中")
            .setContentText("确保所有闹钟按时响起 · 点击查看")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭守护", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmGuard:GuardWakeLock")
        }
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /**
     * 启动预警音检查协程（每 1 秒检查一次）
     */
    private fun startWarningCheck() {
        serviceScope.launch {
            while (isRunning) {
                checkAndPlayWarning()
                delay(1000L) // 每 1 秒检查一次
            }
        }
        Log.i(TAG, "✓ 预警音检查协程已启动")
    }
    
    /**
     * 检查是否需要播放预警音
     */
    private suspend fun checkAndPlayWarning() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = PreferencesManager(this@AlarmGuardService)
                val warningSeconds = maxOf(prefs.getCountdownWarningSeconds(), 10)
                val warningSoundType = prefs.getCountdownWarningSoundType()
                val warningCustomPath = prefs.getCountdownWarningCustomPath()
                val warningTtsText = prefs.getCountdownWarningTtsText()
                
                // 获取数据库实例
                val db = AlarmDatabase.getDatabase(this@AlarmGuardService, serviceScope)
                val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                
                // 获取所有闹钟和分组
                val groups = repository.getGroupList()
                val enabledGroupIds = groups.filter { it.isEnabled }.map { it.id }.toSet()
                
                // 计算最近的闹钟距离现在的秒数
                val now = System.currentTimeMillis()
                val nearestSec = groups.flatMap { group ->
                    repository.getAlarmsByGroup(group.id)
                }.filter { it.isEnabled && it.groupId in enabledGroupIds }
                    .map { alarm ->
                        val nextTimeMillis = AlarmScheduler.calculateNextAlarmTime(alarm)
                        (nextTimeMillis - now) / 1000
                    }
                    .filter { it > 0 }
                    .minOrNull() ?: Long.MAX_VALUE
                
                val isInWarningZone = nearestSec <= warningSeconds && nearestSec > 0
                
                // 切换到主线程更新状态
                withContext(Dispatchers.Main) {
                    if (isInWarningZone) {
                        if (!wasInWarning) {
                            // 进入预警区域，启动预警音播放
                            startWarningSound(nearestSec)
                            wasInWarning = true
                        } else {
                            // 已经在预警区域内，更新最近的秒数
                            updateWarningNearestSec(nearestSec)
                        }
                    } else {
                        if (wasInWarning) {
                            // 离开预警区域，停止预警音
                            stopWarningSound()
                            wasInWarning = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查预警音状态失败: ${e.message}")
            }
        }
    }
    
    /**
     * 启动预警音播放
     */
    @Synchronized
    private fun startWarningSound(nearestSec: Long) {
        if (warningRepeatJob != null) return
        
        // 发送预警服务启动广播
        sendServiceStatusBroadcast(SERVICE_WARNING, true)
        Log.i(TAG, "已发送预警服务启动状态广播")
        
        warningRepeatJob = serviceScope.launch {
            var currentNearestSec = nearestSec
            
            while (isActive) {
                try {
                    // 唤醒屏幕
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                        PowerManager.ON_AFTER_RELEASE, 
                        "AlarmGuard:Warning"
                    )
                    wl.acquire(3000L)
                } catch (_: Exception) {}
                
                // ★ 每次播放前都重新读取设置（防止设置已更改）
                val prefs = PreferencesManager(this@AlarmGuardService)
                val warningSeconds = prefs.getCountdownWarningSeconds()
                val soundType = prefs.getCountdownWarningSoundType()
                val customPath = prefs.getCountdownWarningCustomPath()
                val ttsText = prefs.getCountdownWarningTtsText()
                
                // ★ 设置 TTS 引擎（使用用户设置的引擎）
                val selectedEngine = prefs.getTtsEngine()
                if (selectedEngine.isNotBlank()) {
                    TtsTaskPlayer.setEngine(selectedEngine)
                }
                
                val dynamicMinute = (currentNearestSec + 59) / 60
                Log.i("WarningLoop", ">>> [守护服务播报] 实时秒数: $currentNearestSec, 播报分钟: $dynamicMinute, 音色: $soundType, TTS文字: $ttsText")
                
                if (currentNearestSec > (warningSeconds + 5) || currentNearestSec <= 0) {
                    break
                }
                
                // 1. 播放自定义部分 (滴答/钟声/录音/TTS)
                val latch = CountDownLatch(1)
                try {
                    when (soundType) {
                        "tick_tock" -> {
                            ChimeGenerator.playChimePattern(99)
                            delay(2000L)
                            latch.countDown()
                        }
                        "chime_0", "chime_1", "chime_2", "chime_3" -> {
                            val p = soundType.last().digitToInt()
                            ChimeGenerator.playChimePattern(p)
                            delay(4000L)
                            latch.countDown()
                        }
                        "custom" -> {
                            if (customPath.isNotBlank()) {
                                TtsTaskPlayer.playFile(this@AlarmGuardService, customPath) { latch.countDown() }
                            } else {
                                latch.countDown()
                            }
                        }
                        "tts" -> {
                            val t = ttsText.ifBlank { "注意，闹钟即将响起" }
                            TtsTaskPlayer.play(this@AlarmGuardService, t, onComplete = { latch.countDown() })
                        }
                        else -> latch.countDown()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "播放预警音片段失败: ${e.message}")
                    latch.countDown()
                }
                
                withContext(Dispatchers.IO) { latch.await(15, TimeUnit.SECONDS) }
                
                // 2. 播报剩余分钟 (仅 1-10 分钟)
                if (dynamicMinute in 1..10) {
                    val minuteText = ChimeAudioPreloader.minuteText(dynamicMinute.toInt())
                    Log.d("WarningLoop", "守护服务正在播报时间: $minuteText")
                    val mLatch = CountDownLatch(1)
                    try {
                        TtsTaskPlayer.play(this@AlarmGuardService, minuteText) { mLatch.countDown() }
                    } catch (e: Exception) {
                        Log.e(TAG, "播报剩余分钟失败: ${e.message}")
                        mLatch.countDown()
                    }
                    withContext(Dispatchers.IO) { mLatch.await(8, TimeUnit.SECONDS) }
                }
                
                // 更新最近的秒数（独立 try-catch，防止数据库异常导致协程退出）
                try {
                    val newNearestSec = withContext(Dispatchers.IO) {
                        val db = AlarmDatabase.getDatabase(this@AlarmGuardService, serviceScope)
                        val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
                        val groups = repository.getGroupList()
                        val enabledGroupIds = groups.filter { it.isEnabled }.map { it.id }.toSet()
                        val now = System.currentTimeMillis()
                        groups.flatMap { group ->
                            repository.getAlarmsByGroup(group.id)
                        }.filter { it.isEnabled && it.groupId in enabledGroupIds }
                            .map { alarm ->
                                val nextTimeMillis = AlarmScheduler.calculateNextAlarmTime(alarm)
                                (nextTimeMillis - now) / 1000
                            }
                            .filter { it > 0 }
                            .minOrNull() ?: Long.MAX_VALUE
                    }
                    currentNearestSec = newNearestSec
                } catch (e: Exception) {
                    Log.e(TAG, "更新最近闹钟秒数失败（继续使用当前值）: ${e.message}")
                    // 数据库查询失败时保留 currentNearestSec 原值，循环继续
                    // 若 currentNearestSec 已 <= 0，下次循环会触发 break
                }
                
                delay(1000L) // 间隔 1 秒立即进入下一次播报
            }
        }
    }
    
    /**
     * 更新预警音的最近秒数
     */
    @Synchronized
    private fun updateWarningNearestSec(nearestSec: Long) {
        // 这个方法可以用来更新预警音播放循环中的 currentNearestSec
        // 目前预警音播放循环内部会自己计算，所以这里不需要做太多事情
    }
    
    /**
     * 停止预警音播放
     */
    @Synchronized
    private fun stopWarningSound() {
        ChimeGenerator.stopTickTock()
        warningRepeatJob?.cancel()
        warningRepeatJob = null
        Log.i(TAG, "✓ 预警音已停止")
        
        // 发送预警服务停止广播
        sendServiceStatusBroadcast(SERVICE_WARNING, false)
    }
    
    /**
     * 发送服务状态广播
     */
    private fun sendServiceStatusBroadcast(serviceName: String, isRunning: Boolean) {
        val intent = Intent(ServiceStatusMonitor.ACTION_SERVICE_STATUS_CHANGED).apply {
            putExtra(ServiceStatusMonitor.EXTRA_SERVICE_NAME, serviceName)
            putExtra(ServiceStatusMonitor.EXTRA_SERVICE_STATUS, isRunning)
        }
        sendBroadcast(intent)
    }

    /**
     * 设置心跳：每 1 分钟通过 AlarmManager 发送心跳广播
     * 确保守护服务被杀后最多 1 分钟就能自动恢复
     */
    private fun setupHeartbeat() {
        val heartbeatIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 999, heartbeatIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 每 1 分钟发送一次心跳（OPPO 系统杀进程后最多 1 分钟恢复）
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            pendingIntent
        )
        Log.w(TAG, "✓ 心跳已设置：每 1 分钟检查一次守护服务")
    }

    override fun onDestroy() {
        Log.w(TAG, "========== 守护服务被销毁，安排重启 ==========")
        isRunning = false
        handler.removeCallbacks(guardRunnable)
        releaseWakeLock()
        
        // ★ 停止预警音播放
        stopWarningSound()
        serviceScope.cancel()
        
        // 发送守护服务停止广播
        sendServiceStatusBroadcast(SERVICE_GUARD, false)
        Log.i(TAG, "已发送守护服务停止状态广播")
        
        // 立即安排重启（通过 AlarmManager 发送广播）
        try {
            val restartIntent = Intent(applicationContext, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, 999, restartIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 2000L,
                pendingIntent
            )
            Log.w(TAG, "✓ 已安排 2 秒后重启守护服务")
        } catch (e: Exception) {
            Log.e(TAG, "✗ 安排重启失败: ${e.message}")
        }
        super.onDestroy()
    }
}
