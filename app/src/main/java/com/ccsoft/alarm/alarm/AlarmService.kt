package com.ccsoft.alarm.alarm

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
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ccsoft.alarm.MainActivity
import com.ccsoft.alarm.db.AlarmDatabase
import com.ccsoft.alarm.db.AlarmRecord
import com.ccsoft.alarm.db.AlarmRepository
import com.ccsoft.alarm.service.ServiceStatusMonitor
import com.ccsoft.alarm.service.TimerService
import com.ccsoft.alarm.util.PreferencesManager
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
    private val monitorHandler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null
    private var isRinging = false
    private var currentRingingAlarmId = -1L
    private var flashState = false 
    
    private var lastWarningState = false
    private var lastFlashState = false

    // 倒计时功能
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
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
        
        // 关键修复：从统一设置中获取选定的 TTS 引擎
        val prefs = PreferencesManager(this)
        val enginePkg = prefs.getTtsEngine().let { if (it.isBlank()) null else it }
        
        if (enginePkg == "本地推荐tts") {
            Log.d(TAG, "使用本地推荐tts, 跳过原生 TextToSpeech 初始化")
            isTtsInitialized = true
        } else {
            Log.d(TAG, "创建 AlarmService TTS, 引擎: ${enginePkg ?: "系统默认"}")
            tts = TextToSpeech(this, this, enginePkg)
        }
        
        // 发送闹钟服务启动广播
        sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_ALARM, true)
        Log.i(TAG, "已发送闹钟服务启动状态广播")
        
        // 同步 TtsTaskPlayer 的引擎设置，确保整点报时使用正确的引擎
        TtsTaskPlayer.setEngine(enginePkg ?: "")

        // 恢复计时器状态
        val timerEnd = prefs.getTimerEndMillis()
        if (timerEnd > System.currentTimeMillis()) {
            val remain = ((timerEnd - System.currentTimeMillis()) / 1000).toInt()
            Log.i(TAG, "恢复计时器状态: remain=$remain seconds")
            startCountdownForeground(remain)
        }

        // 启动后自动开启最近闹钟监控
        startAutoAlarmMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand Action: $action")

        when (action) {
            "START_RINGING" -> {
                isRinging = true
                val alarmId = intent.getLongExtra("ALARM_ID", -1L)
                currentRingingAlarmId = alarmId
                
                // 通知状态栏时钟：正在响铃
                sendBroadcast(Intent("com.ccsoft.alarm.ALARM_STATE_CHANGED").apply {
                    putExtra("IS_RINGING", true)
                    putExtra("ALARM_ID", alarmId)
                })

                val label = intent.getStringExtra("ALARM_LABEL") ?: "闹钟"
                val ringtone = intent.getStringExtra("ALARM_RINGTONE")
                val vibrate = intent.getBooleanExtra("ALARM_VIBRATE", true)
                val ringtoneDurationSecs = intent.getIntExtra("ALARM_DURATION_SECS", 0)
                startRingingForeground(alarmId, label, ringtone, vibrate, ringtoneDurationSecs)
            }
            "STOP_RINGING" -> {
                isRinging = false
                currentRingingAlarmId = -1
                
                // 通知状态栏时钟：停止响铃
                sendBroadcast(Intent("com.ccsoft.alarm.ALARM_STATE_CHANGED").apply {
                    putExtra("IS_RINGING", false)
                })

                val alarmId = intent.getLongExtra("ALARM_ID", -1L)
                stopRinging(alarmId)
            }
            "TRIGGER_CHIME" -> {
                val hour = intent.getIntExtra("CHIME_HOUR", Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                val useTts = intent.getBooleanExtra("CHIME_USE_TTS", true)
                val vibrate = intent.getBooleanExtra("CHIME_VIBRATE", true)
                val chimeStyle = intent.getIntExtra("CHIME_STYLE", 0)
                val customText = intent.getStringExtra("CHIME_TEXT")
                triggerHourlyChime(hour, useTts, vibrate, chimeStyle, customText)
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
            ServiceStatusMonitor.ACTION_SERVICE_STATUS_CHANGED -> {
                // 收到状态变更广播（由其他服务发出），转发给自己监控
                // 实际不需要处理，保留接口兼容性
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
        isRinging = true
        currentRingingAlarmId = alarmId
        // ★ 第 0 步：唤醒屏幕（非阻塞 <1ms），与响铃并行执行
        wakeUpScreen()

        // ★ 第 1 步：立即响铃，一秒都不耽误
        val isTtsMode = ringtonePath == TTS_RINGTONE_MARKER
        if (!isTtsMode) {
            playAlarmSound(ringtonePath) // 立即播放铃声（已设置 isLooping=true，会循环）
            // 非 TTS 模式：延迟 1.5 秒后用 TTS 说出闹钟标签
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                speak(label)
            }, 1500L)
        } else {
            // TTS 模式：循环朗读标签，直到用户关闭
            speakLooping(label)
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
            fullscreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        isRinging = false
        currentRingingAlarmId = -1
        
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
            val resetIntent = Intent("com.ccsoft.alarm.TIMER_DISMISSED")
            sendBroadcast(resetIntent)
        }

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()

        // 取消 TTS 循环朗读
        ttsLoopHandler?.removeCallbacksAndMessages(null)
        ttsLoopHandler = null

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

    private fun triggerHourlyChime(
        hour: Int, 
        useTts: Boolean, 
        vibrate: Boolean, 
        chimeStyle: Int = 0,
        customText: String? = null
    ) {
        val text = customText ?: ChimeAudioPreloader.hourText(hour)
        Log.d(TAG, "triggerHourlyChime text: $text, chimeStyle: $chimeStyle")
        
        // 发送报时服务启动广播
        sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, true)
        Log.d(TAG, "已发送报时服务启动状态广播")

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
                // ★ 关键修复：先设置用户选择的 TTS 引擎，避免整点报时使用系统默认引擎
                val prefs = PreferencesManager(this)
                val enginePkg = prefs.getTtsEngine().let { if (it.isBlank()) null else it }
                if (enginePkg != null) {
                    TtsTaskPlayer.setEngine(enginePkg)
                }
                // 统一使用 TtsTaskPlayer.play，它内部处理了：文件名区分语音、存在即播放、不存在则生成后再播放
                TtsTaskPlayer.play(this, text)
            } else {
                playSingleBeep()
            }
        }

        // 播报指令发出后，由于 TTS 是异步的，我们不能立即释放锁，上面的 acquire(60s) 会在超时后自动释放
    }

    private fun playSingleBeep() {
        try {
            val mPlayer = MediaPlayer()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mPlayer.setDataSource(this, uri)
            mPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mPlayer.prepare()
            mPlayer.start()
            mPlayer.setOnCompletionListener {
                it.release()
                // 发送报时服务停止广播
                sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
                if (!isWifiServerRunning) {
                    stopSelf()
                } else {
                    updateWifiServerForegroundNotification()
                }
            }
        } catch (e: Exception) {
            // 发送报时服务停止广播
            sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
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
            // 发送报时服务停止广播
            sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
            if (!isWifiServerRunning) {
                stopSelf()
            } else {
                updateWifiServerForegroundNotification()
            }
        }, estimatedMs)
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val prefs = PreferencesManager(this)
        val enginePkg = prefs.getTtsEngine().let { if (it.isBlank()) null else it }
        
        // 核心修复：如果是本地推荐tts (Edge)，必须调用 TtsTaskPlayer，因为原生 tts 实例在此模式下为 null
        if (enginePkg == "本地推荐tts") {
            Log.d(TAG, "speak: 使用 TtsTaskPlayer 播报 -> $text")
            com.ccsoft.alarm.alarm.TtsTaskPlayer.play(this, text, onComplete = {
                // 发送报时服务停止广播
                sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
                onComplete?.invoke()
            })
            return
        }

        if (tts == null || !isTtsInitialized) {
            Log.w(TAG, "speak: TTS 尚未就绪，加入待播放队列")
            pendingSpeech = text
            // 发送报时服务停止广播
            sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
            onComplete?.invoke()
            return
        }
        
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        Log.d(TAG, "speak: 使用原生引擎播报 -> $text")
        // 原生引擎：设置 UtteranceProgressListener 来感知播放完成
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                // 发送报时服务停止广播
                sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
                onComplete?.invoke()
            }
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {
                // 发送报时服务停止广播
                sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_CHIME, false)
                onComplete?.invoke()
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "AlarmServiceTTS")
    }

    /** TTS 模式循环朗读：等上一次播报真正完成后，等 1 秒再播下一次，绝不重叠 */
    private var ttsLoopHandler: android.os.Handler? = null
    private var ttsLoopText: String = ""
    private fun speakLooping(text: String) {
        ttsLoopText = text
        ttsLoopHandler = android.os.Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isRinging) return
                speak(text) {
                    // ★ 关键修复：等上一次 speak 真正播完后，再等 1 秒调度下一次
                    if (!isRinging) return@speak
                    ttsLoopHandler?.postDelayed(this, 1000L)
                }
            }
        }
        ttsLoopHandler?.post(runnable)
    }

    /** 清除旧的预合成报时缓存文件，使下次报时走实时 TTS 以反映新选的语音 */
    private fun clearChimeCache() {
        ChimeAudioPreloader.resetCacheFlag(this)
        Log.d(TAG, "已重置报时语音标记")
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
            
            // 关键修复：从统一设置中获取语速、音调、语音包
            val prefs = PreferencesManager(this)
            val pitch = prefs.getTtsPitch()
            val rate = prefs.getTtsRate()
            val savedVoice = prefs.getTtsVoice()

            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            Log.d(TAG, "onInit: pitch=$pitch, rate=$rate, voice=$savedVoice")

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

        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        
        val endTime = System.currentTimeMillis() + totalSeconds * 1000L
        
        countdownRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val remainingMillis = endTime - now
                val remainingSeconds = (remainingMillis + 500) / 1000 // 四舍五入到最近的秒
                
                if (remainingSeconds <= 0) {
                    updateTimerNotification(0)
                    countdownRunnable = null
                    // 倒计时结束：启动 TimerService 播放声音
                    val prefs = PreferencesManager(this@AlarmService)
                    val soundType = prefs.getTimerFinishSoundType()
                    val customPath = prefs.getTimerFinishCustomPath()
                    val ttsText = prefs.getTimerFinishTtsText()
                    
                    val serviceIntent = Intent(this@AlarmService, TimerService::class.java).apply {
                        action = TimerService.ACTION_TIMER_FINISH
                        putExtra(TimerService.EXTRA_SOUND_TYPE, soundType)
                        putExtra(TimerService.EXTRA_CUSTOM_PATH, customPath)
                        putExtra(TimerService.EXTRA_TTS_TEXT, ttsText)
                    }
                    startService(serviceIntent)
                    
                    // 同时通知 UI（如果 App 还在运行）
                    sendBroadcast(Intent("com.ccsoft.alarm.TIMER_FINISHED").apply {
                        putExtra("REMAINING_SECONDS", 0)
                    })
                    return
                }

                updateTimerNotification(remainingSeconds.toInt())
                // 发送广播通知 UI 进度变化
                sendBroadcast(Intent("com.ccsoft.alarm.TIMER_PROGRESS_CHANGED").apply {
                    putExtra("REMAINING_SECONDS", remainingSeconds.toInt())
                })
                
                // 核心对齐：计算距离下一整秒的延迟
                val delayMs = 1000L - (now % 1000)
                countdownHandler.postDelayed(this, delayMs)
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
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

        monitorRunnable?.let { monitorHandler.removeCallbacks(it) }
        monitorRunnable = object : Runnable {
            override fun run() {
                flashState = !flashState
                val now = System.currentTimeMillis()
                
                if (nextAlarmTimeMillis != Long.MAX_VALUE) {
                    // 修正：增加 500ms 偏移以进行四舍五入，防止毫秒级截断导致看起来慢一秒
                    val remainingSecs = (nextAlarmTimeMillis - now + 500) / 1000
                    
                    val prefs = PreferencesManager(this@AlarmService)
                    val warningThreshold = prefs.getCountdownWarningSeconds()
                    val isWarning = remainingSecs in 1..warningThreshold.toLong()

                    updateMonitorNotification(remainingSecs, isWarning = isWarning)
                    
                    // 核心修复：还原每秒强制刷新最近闹钟插件，以配合 TextView 倒计时滚动
                    com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(this@AlarmService)

                    // 优化：仅在预警状态（需要闪烁）或状态改变时才更新插件
                    if (isWarning || isWarning != lastWarningState) {
                        com.ccsoft.alarm.widget.TimerWidgetProvider.updateWidgetStyle(this@AlarmService, isWarning, flashState)
                    }
                    lastWarningState = isWarning
                    lastFlashState = flashState

                    val intent = Intent("com.ccsoft.alarm.UPDATE_FLOATING_STYLE").apply {
                        putExtra("IS_WARNING", isWarning)
                        putExtra("FLASH_ON", flashState)
                    }
                    sendBroadcast(intent)
                } else {
                    updateMonitorNotification(-1)
                    if (lastWarningState) {
                        com.ccsoft.alarm.widget.TimerWidgetProvider.updateWidgetStyle(this@AlarmService, false, false)
                        lastWarningState = false
                    }
                }
                
                // 核心对齐：计算距离下一整秒的延迟
                val delayMs = 1000L - (now % 1000)
                monitorHandler.postDelayed(this, delayMs)
            }
        }
        monitorHandler.post(monitorRunnable!!)
    }

    private fun updateMonitorNotification(seconds: Long, customMsg: String? = null, isWarning: Boolean = false) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTimeStr = timeFormat.format(Date())
        
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

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 如果正在响铃，点击通知去关闭页面
            if (isRinging) {
                setClass(this@AlarmService, AlarmActiveActivity::class.java)
                putExtra("ALARM_ID", currentRingingAlarmId)
            } else {
                putExtra("TARGET_TAB", 1) // 默认去倒计时 Tab
            }
        }
        
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 100, notificationIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("[$currentTimeStr] 闹钟守护中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (isWarning && flashState) {
                    // 预警时闪烁红色（通过设置彩色通知或简单提示）
                    setColor(0xFFFF0000.toInt())
                }
            }
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
        monitorRunnable?.let { monitorHandler.removeCallbacks(it) }
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        
        // 发送闹钟服务停止广播
        sendServiceStatusBroadcast(ServiceStatusMonitor.SERVICE_ALARM, false)
        Log.i(TAG, "已发送闹钟服务停止状态广播")
        
        super.onDestroy()
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
}
