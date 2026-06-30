package com.ccsoft.alarm.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ccsoft.alarm.MainActivity
import com.ccsoft.alarm.util.PreferencesManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * 服务状态监控前台服务
 * 在状态栏通知中显示各个服务的启用/就绪状态
 *
 * 状态说明：
 * ✅ = 服务已启用/就绪（守护服务正在运行、闹钟有启用调度、报时已启用等）
 * ❌ = 服务未启用/未运行
 */
class ServiceStatusMonitor : Service() {

    companion object {
        private const val TAG = "ServiceStatus"
        private const val CHANNEL_ID = "service_status_channel"
        private const val NOTIFICATION_ID = 3001

        // 广播动作：服务状态变更
        const val ACTION_SERVICE_STATUS_CHANGED = "com.ccsoft.alarm.SERVICE_STATUS_CHANGED"
        const val EXTRA_SERVICE_NAME = "service_name"
        const val EXTRA_SERVICE_STATUS = "service_status" // true=就绪/运行中, false=未运行/已停止
        const val ACTION_STOP_MONITOR = "com.ccsoft.alarm.STOP_SERVICE_MONITOR"

        // 服务名称常量
        const val SERVICE_ALARM = "闹钟"
        const val SERVICE_CHIME = "报时"
        const val SERVICE_TIMER = "计时"
        const val SERVICE_WARNING = "预警"
        const val SERVICE_GUARD = "守护"

        fun start(context: Context) {
            val prefs = PreferencesManager(context)
            if (!prefs.isServiceStatusMonitorEnabled()) {
                Log.d(TAG, "服务状态监控已关闭，不启动")
                return
            }
            val intent = Intent(context, ServiceStatusMonitor::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "启动服务状态监控")
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServiceStatusMonitor::class.java))
            Log.d(TAG, "停止服务状态监控")
        }
    }

    // 服务状态映射：true=✅启用/就绪, false=❌未启用
    private val serviceStatusMap = mutableMapOf(
        SERVICE_ALARM to false,
        SERVICE_CHIME to false,
        SERVICE_TIMER to false,
        SERVICE_WARNING to false,
        SERVICE_GUARD to false
    )

    private var updateReceiver: BroadcastReceiver? = null
    private var stopReceiver: BroadcastReceiver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 定期刷新状态的 Runnable */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            checkServicesEnabled()
            updateNotification()
            handler.postDelayed(this, 30_000L) // 每 30 秒刷新一次
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerStatusReceiver()
        registerStopReceiver()
        // 主动检查各服务的启用/就绪状态
        checkServicesEnabled()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 启动定期刷新
        handler.postDelayed(refreshRunnable, 30_000L)
        Log.i(TAG, "服务状态监控已启动，初始状态: $serviceStatusMap")
    }

    /**
     * 主动检查各服务是否启用/就绪
     * 闹钟：是否有启用的闹钟（调度是否已设置）
     * 报时：整点报时是否启用
     * 计时：TimerService 是否正在运行（计时结束播放中）
     * 预警：倒计时预警是否启用
     * 守护：AlarmGuardService 是否正在运行
     */
    private fun checkServicesEnabled() {
        val prefs = PreferencesManager(this)

        // 守护：检查服务是否正在运行
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == "com.ccsoft.alarm.alarm.AlarmGuardService"
        }
        serviceStatusMap[SERVICE_GUARD] = running

        // 闹钟：检查是否有启用的闹钟
        // 通过 AlarmManager 查询下一个闹钟（Android 5.1+ 支持）
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAlarmTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            alarmManager.nextAlarmClock?.triggerTime ?: 0L
        } else {
            0L
        }
        serviceStatusMap[SERVICE_ALARM] = nextAlarmTime > System.currentTimeMillis()

        // 报时：检查整点报时是否启用
        val chimeEnabled = prefs.isHourlyChimeMasterEnabled()
        serviceStatusMap[SERVICE_CHIME] = chimeEnabled

        // 预警：检查倒计时预警是否启用（阈值 > 0）
        val warningThreshold = prefs.getCountdownWarningSeconds()
        serviceStatusMap[SERVICE_WARNING] = warningThreshold > 0

        // 计时：检查是否有正在运行的计时器（通过 TimerService 是否在运行来判断）
        // TimerService 只有在计时结束播放声音时才运行，不是常驻服务
        // 所以这里检查 AlarmService 中的倒计时是否正在进行
        val timerEnd = prefs.getTimerEndMillis()
        val timerRunning = timerEnd > System.currentTimeMillis()
        serviceStatusMap[SERVICE_TIMER] = timerRunning

        Log.i(TAG, "检查完成: 闹钟=${serviceStatusMap[SERVICE_ALARM]}, " +
            "报时=$chimeEnabled, 计时=$timerRunning, 预警=${warningThreshold > 0}, 守护=$running")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 收到状态更新广播，或外部调用 startService，都重新检查状态
        checkServicesEnabled()
        updateNotification()
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "服务状态监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示各个服务的运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
    
    private fun registerStatusReceiver() {
        updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SERVICE_STATUS_CHANGED) {
                    val serviceName = intent.getStringExtra(EXTRA_SERVICE_NAME)
                    val status = intent.getBooleanExtra(EXTRA_SERVICE_STATUS, false)
                    if (serviceName != null) {
                        serviceStatusMap[serviceName] = status
                        updateNotification()
                        Log.d(TAG, "服务状态更新: $serviceName = $status")
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_SERVICE_STATUS_CHANGED)
        registerReceiver(updateReceiver, filter)
    }
    
    private fun registerStopReceiver() {
        stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STOP_MONITOR) {
                    Log.i(TAG, "收到停止监控广播，关闭服务状态监控")
                    // 关闭开关
                    val prefs = PreferencesManager(this@ServiceStatusMonitor)
                    prefs.setServiceStatusMonitorEnabled(false)
                    // 停止服务
                    stopSelf()
                }
            }
        }
        val filter = IntentFilter(ACTION_STOP_MONITOR)
        registerReceiver(stopReceiver, filter)
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
        
        // 添加关闭按钮
        val stopIntent = Intent(ACTION_STOP_MONITOR)
        val stopPendingIntent = PendingIntent.getBroadcast(this, 1, stopIntent, pendingFlags)
        
        // 构建状态文本
        val statusText = StringBuilder()
        statusText.append("服务状态监控\n")
        
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        statusText.append("更新时间: ${timeFormat.format(Date())}\n")
        statusText.append("\n")
        
        // 逐个添加服务状态
        serviceStatusMap.forEach { (name, isRunning) ->
            val statusIcon = if (isRunning) "✅" else "❌"
            statusText.append("$statusIcon $name\n")
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚙️ 服务状态监控")
            .setContentText("点击查看详细信息")
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText.toString()))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭监控", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        updateReceiver?.let { unregisterReceiver(it) }
        stopReceiver?.let { unregisterReceiver(it) }
        Log.i(TAG, "服务状态监控已停止")
        
        // 如果开关仍然是开启的，自动重启（防止被系统杀死后无法恢复）
        val prefs = PreferencesManager(this)
        if (prefs.isServiceStatusMonitorEnabled()) {
            Log.i(TAG, "开关仍开启，自动重启服务状态监控")
            val restartIntent = Intent(this, ServiceStatusMonitor::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }
}
