package com.example.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AlarmDatabase
import com.example.db.AlarmRecord
import com.example.db.AlarmRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

class AlarmService : Service(), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "AlarmService"
        private const val RINGING_CHANNEL_ID = "alarm_ringing_channel"
        private const val SERVICE_CHANNEL_ID = "alarm_service_channel"
        private const val RINGING_NOTIFICATION_ID = 2001
        private const val SERVICE_NOTIFICATION_ID = 2002
        const val TIMER_CHANNEL_ID = "countdown_timer_channel"
        const val TIMER_NOTIFICATION_ID = 2003
        /** 标记使用 TTS 朗读标签代替铃声 */
        const val TTS_RINGTONE_MARKER = "__TTS__"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeech: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiServer: WifiSyncServer? = null
    private var isWifiServerRunning = false

    // NSD 相关
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val SERVICE_TYPE = "_groupalarm._tcp."
    private val SERVICE_NAME = "GroupAlarmSync"

    // 倒计时与监控相关
    private var nextAlarmTimeMillis = 0L
    private var isMonitorRunning = false
    private var monitorTimer: CountDownTimer? = null

    // 倒计时功能
    private var countdownTimer: CountDownTimer? = null
    private var countdownTotalSeconds = 0

    private val serviceBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AlarmService = this@AlarmService
    }

    override fun onBind(intent: Intent?): IBinder {
        return serviceBinder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        tts = TextToSpeech(this, this)
        
        // 启动后自动开启最近闹钟监控
        startAutoAlarmMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand Action: $action")

        when (action) {
            "START_RINGING" -> {
                val alarmId = intent.getLongExtra("ALARM_ID", -1L)
                val label = intent.getStringExtra("ALARM_LABEL") ?: "闹钟"
                val ringtone = intent.getStringExtra("ALARM_RINGTONE")
                val vibrate = intent.getBooleanExtra("ALARM_VIBRATE", true)
                val ringtoneDurationSecs = intent.getIntExtra("ALARM_DURATION_SECS", 0)
                startRingingForeground(alarmId, label, ringtone, vibrate, ringtoneDurationSecs)
            }
            "STOP_RINGING" -> {
                val alarmId = intent.getLongExtra("ALARM_ID", -1L)
                stopRinging(alarmId)
            }
            "TRIGGER_CHIME" -> {
                val hour = intent.getIntExtra("CHIME_HOUR", Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                val useTts = intent.getBooleanExtra("CHIME_USE_TTS", true)
                val vibrate = intent.getBooleanExtra("CHIME_VIBRATE", true)
                val chimeStyle = intent.getIntExtra("CHIME_STYLE", 0)
                triggerHourlyChime(hour, useTts, vibrate, chimeStyle)
            }
            "START_WIFI_SERVER" -> {
                startWifiServer()
            }
            "STOP_WIFI_SERVER" -> {
                stopWifiServer()
            }
            "REFRESH_MONITOR" -> {
                // 收到刷新指令，立即强制重新计算最近闹钟
                forceRefreshMonitor()
            }
            "START_COUNTDOWN" -> {
                val totalSeconds = intent?.getIntExtra("COUNTDOWN_TOTAL_SECONDS", 0) ?: 0
                if (totalSeconds > 0) {
                    startCountdownForeground(totalSeconds)
                }
            }
            "STOP_COUNTDOWN" -> {
                stopCountdown()
            }
            "UPDATE_TTS_VOICE" -> {
                // 用户切换语音后，动态更新 AlarmService 的 TTS 实例
                val voiceName = intent?.getStringExtra("TTS_VOICE_NAME") ?: ""
                if (voiceName.isNotEmpty() && isTtsInitialized) {
                    val matched = tts?.voices?.find { it.name == voiceName }
                    if (matched != null) {
                        tts?.setVoice(matched)
                        Log.d(TAG, "已动态更新 TTS 语音: $voiceName")
                        // 清除旧的预合成缓存，下次报时走实时 TTS 从而使用新语音
                        clearChimeCache()
                    } else {
                        Log.w(TAG, "动态更新语音失败，不可用: $voiceName")
                    }
                }
            }
        }

        // Return START_STICKY to satisfy background persistence
        return START_STICKY
    }

    // Starts foreground notification for active alarm ringing
    private fun startRingingForeground(alarmId: Long, label: String, ringtonePath: String?, vibrate: Boolean, ringtoneDurationSecs: Int = 0) {
        // ★ 第 0 步：唤醒屏幕（非阻塞 <1ms），与响铃并行执行
        wakeUpScreen()

        // ★ 第 1 步：立即响铃，一秒都不耽误
        val isTtsMode = ringtonePath == TTS_RINGTONE_MARKER
        if (!isTtsMode) {
            playAlarmSound(ringtonePath) // 立即播放铃声
            // 非 TTS 模式：延迟 1.5 秒后用 TTS 说出闹钟标签
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                speak(label)
            }, 1500L)
        } else {
            speak(label) // TTS 模式：立即朗读标签
        }

        // 如果设置了响铃时长，则在指定时间后自动关闭
        if (ringtoneDurationSecs > 0) {
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                stopRinging(alarmId)
            }, (ringtoneDurationSecs * 1000L).toLong())
        }

        // ★ 第 2 步：振动（与响铃同时）
        if (vibrate) {
            vibrateDevice()
        }

        // ★ 第 3 步：后台保活 + 通知 + Activity（与响铃并行）
        // 核心加固：申请唤醒锁，防止系统在锁屏下瞬间切断 CPU 导致不响
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:RingingWakeLock")
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 最长持有10分钟，直到手动关闭

        val fullscreenIntent = Intent(this, AlarmActiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_LABEL", label)
        }
        
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullscreenIntent,
            pendingFlags
        )

        // Dismiss action button
        val dismissIntent = Intent(this, AlarmService::class.java).apply {
            action = "STOP_RINGING"
            putExtra("ALARM_ID", alarmId)
        }
        val dismissPendingIntent = PendingIntent.getService(this, 1, dismissIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, RINGING_CHANNEL_ID)
            .setContentTitle("🔔 闹钟响起")
            .setContentText("点击此处关闭 · $label")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(dismissPendingIntent) // 点通知本身也能关
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🔕 关闭闹钟", dismissPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        startForegroundCompat(RINGING_NOTIFICATION_ID, notification)

        // 直接启动关闭界面（前台服务属于前台进程，不受 Android 12+ 后台限制）
        // fullScreenIntent（setFullScreenIntent）在某些手机上不可靠，双重保障
        try {
            startActivity(fullscreenIntent)
        } catch (e: Exception) {
            Log.w(TAG, "直接启动关闭界面失败，fullScreenIntent 兜底: ${e.message}")
        }
    }

    /** 唤醒屏幕 — 在响铃之前调用，确保用户先看到亮屏 */
    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlarmClock:ScreenWakeLock"
            )
            // 持有 3 秒，等 AlarmActiveActivity 的 setTurnScreenOn(true) 接管屏幕
            screenWakeLock.acquire(3000L)
        } catch (e: Exception) {
            Log.w(TAG, "wakeUpScreen 失败: ${e.message}")
        }
    }

    private fun playAlarmSound(ringtonePath: String?) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val mPlayer = MediaPlayer()
            mediaPlayer = mPlayer

            if (!ringtonePath.isNullOrEmpty()) {
                if (ringtonePath.startsWith("content://") || ringtonePath.startsWith("android.resource://")) {
                    mPlayer.setDataSource(this, Uri.parse(ringtonePath))
                } else {
                    val file = File(ringtonePath)
                    if (file.exists()) {
                        mPlayer.setDataSource(file.absolutePath)
                    } else {
                        // Fallback to system default if uploaded custom file missing
                        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        mPlayer.setDataSource(this, alarmUri)
                    }
                }
            } else {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mPlayer.setDataSource(this, alarmUri)
            }

            mPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mPlayer.isLooping = true
            mPlayer.prepare()
            mPlayer.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing alarm sound", e)
        }
    }

    private fun vibrateDevice() {
        val pattern = longArrayOf(0, 1000, 1000) // vibrate 1s, pause 1s
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging(alarmId: Long = -1L) {
        Log.d(TAG, "stopRinging called, alarmId=$alarmId")
        
        // 更新 AlarmRecord 状态为 COMPLETED（只有 PENDING 状态才更新，避免覆写已关闭的）
        if (alarmId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AlarmDatabase.getDatabase(this@AlarmService, CoroutineScope(Dispatchers.IO))
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val record = db.alarmRecordDao().getRecord(alarmId, todayDate)
                    if (record != null && record.status == "PENDING") {
                        db.alarmRecordDao().updateStatus(record.id, "COMPLETED", System.currentTimeMillis())
                        Log.d(TAG, "AlarmRecord $alarmId updated to COMPLETED")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update AlarmRecord status", e)
                }
            }
        } else if (alarmId == -1L) {
            // 计时器关闭 → 通知 UI 重置状态
            val resetIntent = Intent("com.example.TIMER_DISMISSED")
            sendBroadcast(resetIntent)
        }

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        
        // 释放唤醒锁
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Stop foreground state of ringing notification
        stopForeground(true)

        // If WiFi server is still running, restart foreground under service notification
        if (isWifiServerRunning) {
            updateWifiServerForegroundNotification()
        } else {
            stopSelf()
        }
    }

    private fun triggerHourlyChime(hour: Int, useTts: Boolean, vibrate: Boolean, chimeStyle: Int = 0) {
        val text = "现在是北京时间${hour}点整"
        Log.d(TAG, "triggerHourlyChime text: $text, chimeStyle: $chimeStyle")

        // 核心加固：申请 1 分钟的唤醒锁，确保 CPU 在灭屏/应用关闭状态下不休眠，直到报时完成
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:ChimeWakeLock")
        wakeLock.acquire(60 * 1000L)

        // Temporary foreground notification for hourly chimes to obey Android constraints
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("整点报时")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForegroundCompat(SERVICE_NOTIFICATION_ID, notification)

        // Vibrate
        if (vibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500)
            }
        }

        if (chimeStyle >= 1) {
            // 钟声模式
            val bellPattern = (chimeStyle - 1).coerceIn(0, 3)
            playChimeBell(bellPattern)
        } else {
            // TTS 模式
            if (useTts) {
                // 优先播放预合成的“内置”语音文件
                val cachedFile = ChimeAudioPreloader.file(this, hour)
                if (cachedFile.exists()) {
                    Log.d(TAG, "正在播放内置报时语音: ${cachedFile.absolutePath}")
                    playAudioFile(cachedFile.absolutePath)
                } else {
                    // 兜底：如果文件还没生成好，使用实时合成
                    if (isTtsInitialized) {
                        speak(text)
                    } else {
                        // ★ 关键修复：TTS 未就绪时，播放提示音代替完全静音
                        Log.w(TAG, "TTS 未就绪（isTtsInitialized=false），播放提示音代替报时")
                        playSingleBeep()
                    }
                }
            } else {
                playSingleBeep()
            }
        }

        // 播报指令发出后，由于 TTS 是异步的，我们不能立即释放锁，上面的 acquire(60s) 会在超时后自动释放
    }

    private fun playSingleBeep() {
        try {
            val mPlayer = MediaPlayer()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mPlayer.setDataSource(this, uri)
            mPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mPlayer.prepare()
            mPlayer.start()
            mPlayer.setOnCompletionListener {
                it.release()
                if (!isWifiServerRunning) {
                    stopSelf()
                } else {
                    updateWifiServerForegroundNotification()
                }
            }
        } catch (e: Exception) {
            if (!isWifiServerRunning) stopSelf() else updateWifiServerForegroundNotification()
        }
    }

    private fun playChimeBell(chimePattern: Int = 0) {
        ChimeGenerator.playChimePattern(chimePattern)
        // 预计播放时长后自动停止服务
        val estimatedMs = when (chimePattern) {
            1 -> 3500L
            2 -> 3500L
            3 -> 3000L
            else -> 3000L
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isWifiServerRunning) {
                stopSelf()
            } else {
                updateWifiServerForegroundNotification()
            }
        }, estimatedMs)
    }

    /**
     * 播放本地音频文件（如预缓存的 24 段 TTS 报时 WAV）。
     * 播放完毕后自动停止服务。
     */
    private fun playAudioFile(filePath: String, looping: Boolean = false) {
        try {
            val mPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                isLooping = looping
                setOnCompletionListener {
                    Log.d(TAG, "报时音频播放完毕，停止服务")
                    stopRinging()
                }
                prepare()
                start()
            }
            mediaPlayer = mPlayer
        } catch (e: Exception) {
            Log.e(TAG, "播放缓存报时音频失败", e)
            // 回退到实时 TTS
            speak("现在是北京时间 ${Calendar.getInstance().get(Calendar.HOUR_OF_DAY)} 点整")
        }
    }

    private fun speak(text: String) {
        if (!isTtsInitialized) {
            pendingSpeech = text
            return
        }
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ChimeTTS")
    }

    /** 清除旧的预合成报时缓存文件，使下次报时走实时 TTS 以反映新选的语音 */
    private fun clearChimeCache() {
        for (h in 0..23) {
            ChimeAudioPreloader.file(this, h).delete()
        }
        ChimeAudioPreloader.resetCacheFlag(this)
        Log.d(TAG, "已清除旧的报时语音缓存文件")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "China language is not supported or missing data")
                tts?.setLanguage(Locale.CHINESE)
            }
            
            // 关键修复：长驻服务报时也要同步应用标准音频属性
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)

            // 恢复用户选择的 TTS 语音（与 ViewModel 共享 tts_prefs）
            val prefs = getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
            val savedVoice = prefs.getString("tts_voice", "") ?: ""
            if (savedVoice.isNotEmpty()) {
                val matched = tts?.voices?.find { it.name == savedVoice }
                if (matched != null) {
                    tts?.setVoice(matched)
                    Log.d(TAG, "已恢复 TTS 语音: $savedVoice")
                } else {
                    Log.w(TAG, "语音 $savedVoice 在当前引擎中不可用")
                }
            }

            // 引擎准备好了，检查是否有待播放的内容
            isTtsInitialized = true
            pendingSpeech?.let {
                Log.d(TAG, "TTS initialized, playing pending speech: $it")
                speak(it)
                pendingSpeech = null
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed")
        }
    }

    // WiFi Configuration Sync Server Management
    private fun startWifiServer() {
        if (isWifiServerRunning) return
        isWifiServerRunning = true

        val port = 8080
        wifiServer = WifiSyncServer(this, port = port) {
            Log.d(TAG, "Server updated DB properties, trigger reschedule loop.")
        }
        wifiServer?.start()

        registerService(port)
        updateWifiServerForegroundNotification()
    }

    private fun stopWifiServer() {
        if (!isWifiServerRunning) return
        isWifiServerRunning = false
        
        unregisterService()
        wifiServer?.stop()
        wifiServer = null

        stopForeground(true)
        stopSelf()
    }

    private fun registerService(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME + "_" + UUID.randomUUID().toString().substring(0, 4)
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "NSD Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let {
            nsdManager?.unregisterService(it)
        }
        registrationListener = null
        nsdManager = null
    }

    private fun updateWifiServerForegroundNotification() {
        val ip = getLocalIpAddress() ?: "连接WiFi"
        val address = "http://$ip:8080"
        
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("WiFi 同步中心已开启")
            .setContentText("在浏览器输入: $address 管理闹钟与铃声")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForegroundCompat(SERVICE_NOTIFICATION_ID, notification)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmChannel = NotificationChannel(
                RINGING_CHANNEL_ID,
                "闹钟鸣响",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "该通道在闹钟触发时播放铃声"
                enableLights(true)
                enableVibration(true)
            }

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "后台常驻与服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "展示WiFi同步状态及后台守护状态"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(alarmChannel)
            manager.createNotificationChannel(serviceChannel)

            val timerChannel = NotificationChannel(
                TIMER_CHANNEL_ID,
                "倒计时",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示倒计时状态"
            }
            manager.createNotificationChannel(timerChannel)
        }
    }

    // ===== 倒计时功能 =====

    private fun startCountdownForeground(totalSeconds: Int) {
        countdownTotalSeconds = totalSeconds

        val notification = NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setContentTitle("倒计时")
            .setContentText(formatTime(totalSeconds))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForegroundCompat(TIMER_NOTIFICATION_ID, notification)

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = millisUntilFinished / 1000
                updateTimerNotification(remaining.toInt())
            }
            override fun onFinish() {
                updateTimerNotification(0)
                countdownTimer = null
                // 倒计时结束：移除前台通知 → 启动响铃前台
                stopForeground(true)
                startRingingForeground(-1L, "计时结束", null, true)
            }
        }.start()
    }

    private fun stopCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        stopForeground(true)
        stopSelf()
    }

    private fun updateTimerNotification(remainingSeconds: Int) {
        val notification = NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setContentTitle("倒计时")
            .setContentText(formatTime(remainingSeconds))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(TIMER_NOTIFICATION_ID, notification)
    }

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun startAutoAlarmMonitor() {
        if (isMonitorRunning) return
        isMonitorRunning = true
        
        forceRefreshMonitor()

        // 开启前台通知秒级更新计时器
        monitorTimer = object : CountDownTimer(365 * 24 * 3600 * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (nextAlarmTimeMillis != Long.MAX_VALUE) {
                    val remainingSecs = (nextAlarmTimeMillis - System.currentTimeMillis()) / 1000
                    if (remainingSecs > 0) {
                        updateMonitorNotification(remainingSecs)
                    } else {
                        // 时间到了或正在响铃，暂时显示处理中
                        updateMonitorNotification(0, "即将开响...")
                    }
                } else {
                    updateMonitorNotification(-1, "暂未设置闹钟")
                }
            }
            override fun onFinish() {}
        }.start()
    }

    private fun updateMonitorNotification(seconds: Long, customMsg: String? = null) {
        val contentText = if (customMsg != null) {
            customMsg
        } else if (seconds >= 0) {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            "距离下次闹钟：${String.format("%02d:%02d:%02d", h, m, s)}"
        } else {
            "未发现活跃闹钟"
        }

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("打卡闹钟后台守护中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        
        startForegroundCompat(SERVICE_NOTIFICATION_ID, notification)
    }

    private fun forceRefreshMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AlarmDatabase.getDatabase(this@AlarmService, this)
                val alarms = db.alarmDao().getAllAlarms()
                val groups = db.alarmDao().getAllGroups()
                
                var nearestTime = Long.MAX_VALUE

                alarms.forEach { alarm ->
                    val group = groups.find { it.id == alarm.groupId }
                    if (alarm.isEnabled && (group == null || group.isEnabled)) {
                        val nextTime = AlarmScheduler.calculateNextAlarmTime(alarm)
                        if (nextTime < nearestTime) {
                            nearestTime = nextTime
                        }
                    }
                }

                nextAlarmTimeMillis = nearestTime
                Log.d(TAG, "Monitor Refreshed. Next alarm at: ${if(nearestTime == Long.MAX_VALUE) "None" else Date(nearestTime)}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    override fun onDestroy() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
        tts?.stop()
        tts?.shutdown()
        wifiServer?.stop()
        super.onDestroy()
    }
}
