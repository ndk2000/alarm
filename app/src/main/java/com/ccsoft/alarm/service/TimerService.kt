package com.ccsoft.alarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ccsoft.alarm.R
import com.ccsoft.alarm.alarm.ChimeGenerator
import com.ccsoft.alarm.alarm.TtsTaskPlayer
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.ACTION_SERVICE_STATUS_CHANGED
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.EXTRA_SERVICE_NAME
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.EXTRA_SERVICE_STATUS
import com.ccsoft.alarm.service.ServiceStatusMonitor.Companion.SERVICE_TIMER
import com.ccsoft.alarm.util.PreferencesManager


class TimerService : Service() {

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_TIMER_FINISH = "com.ccsoft.alarm.TIMER_FINISH"
        const val EXTRA_SOUND_TYPE = "sound_type"
        const val EXTRA_CUSTOM_PATH = "custom_path"
        const val EXTRA_TTS_TEXT = "tts_text"
    }

    private var isTtsLoopRunning = false
    private var ttsLoopHandler: android.os.Handler? = null
    private var ttsLoopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 停止 TTS 循环
        isTtsLoopRunning = false
        ttsLoopHandler?.removeCallbacks(ttsLoopRunnable!!)
        TtsTaskPlayer.shutdown()
        
        // 发送计时服务停止广播
        sendServiceStatusBroadcast(SERVICE_TIMER, false)
        Log.i(TAG, "已发送计时服务停止状态广播")
    }
    
    /**
     * 发送服务状态广播
     */
    private fun sendServiceStatusBroadcast(serviceName: String, isRunning: Boolean) {
        val intent = Intent(ACTION_SERVICE_STATUS_CHANGED).apply {
            putExtra(EXTRA_SERVICE_NAME, serviceName)
            putExtra(EXTRA_SERVICE_STATUS, isRunning)
        }
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, soundType=${intent?.getStringExtra(EXTRA_SOUND_TYPE)}")
        if (intent?.action == ACTION_TIMER_FINISH) {
            val soundType = intent.getStringExtra(EXTRA_SOUND_TYPE) ?: "tick_tock"
            val customPath = intent.getStringExtra(EXTRA_CUSTOM_PATH) ?: ""
            val ttsText = intent.getStringExtra(EXTRA_TTS_TEXT) ?: ""
            Log.i(TAG, "计时结束，开始播放: type=$soundType, ttsText=$ttsText")

            // 启动前台通知（Android O+ 要求前台服务必须显示通知）
            startForegroundNotification()
            
            // 发送计时服务启动广播
            sendServiceStatusBroadcast(SERVICE_TIMER, true)
            
            playFinishSound(soundType, customPath, ttsText)

            // 启动关闭界面 Activity（锁屏/熄屏也能显示）
            val activityIntent = Intent(this, com.ccsoft.alarm.TimerDoneActivity::class.java)
            activityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            try {
                startActivity(activityIntent)
                Log.i(TAG, "已启动计时关闭界面")
            } catch (e: Exception) {
                Log.e(TAG, "启动计时关闭界面失败: ${e.message}")
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "timer_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "计时器服务",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "计时器结束后的播放服务"
            }
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("计时器")
            .setContentText("计时结束，正在播放...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    @Suppress("DEPRECATION")
    private fun playFinishSound(type: String, customPath: String, ttsText: String) {
        // 唤醒屏幕
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "TimerService:Finish"
            )
            wl.acquire(3000L)
        } catch (e: Exception) {
            Log.e(TAG, "唤醒屏幕失败: ${e.message}")
        }

        when (type) {
            "tick_tock" -> {
                Thread {
                    try {
                        val tickOnce = ChimeGenerator.generateTickOnce(true)
                        val tockOnce = ChimeGenerator.generateTickOnce(false)
                        var isTick = true
                        for (i in 1..6) { // 大约响 6 秒
                            val data = if (isTick) tickOnce else tockOnce
                            isTick = !isTick
                            val track = android.media.AudioTrack.Builder()
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
                                .setAudioFormat(
                                    android.media.AudioFormat.Builder()
                                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                                        .setSampleRate(44100)
                                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                        .build()
                                )
                                .setBufferSizeInBytes(data.size * 4)
                                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                                .build()
                            try {
                                track.write(data, 0, data.size, android.media.AudioTrack.WRITE_BLOCKING)
                                track.play()
                                Thread.sleep(1000L)
                            } finally {
                                try { track.stop() } catch (_: Exception) {}
                                try { track.release() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
            }
            "chime_0", "chime_1", "chime_2", "chime_3" -> {
                val pattern = type.last().digitToInt()
                ChimeGenerator.playChimePattern(pattern)
            }
            "custom" -> {
                if (customPath.isNotBlank()) {
                    try {
                        val player = MediaPlayer().apply {
                            setDataSource(customPath)
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            isLooping = true
                            prepare()
                            start()
                        }
                        // 6 秒后自动停止
                        Thread {
                            Thread.sleep(6000L)
                            try { player.stop() } catch (_: Exception) {}
                            try { player.release() } catch (_: Exception) {}
                        }.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "播放自定义录音失败", e)
                    }
                }
            }
            "tts" -> {
                // 使用 TtsTaskPlayer，确保使用用户选择的引擎
                val text = ttsText.ifBlank { "计时结束" }
                val prefs = PreferencesManager(this)
                val enginePkg = prefs.getTtsEngine().let { if (it.isBlank()) null else it }
                if (enginePkg != null) {
                    TtsTaskPlayer.setEngine(enginePkg)
                }
                // 先确保 TTS 引擎已初始化
                TtsTaskPlayer.ensure(this)
                
                // 循环播放 TTS，直到用户关闭
                val ttsLoopHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val ttsLoopRunnable = object : Runnable {
                    override fun run() {
                        if (!isTtsLoopRunning) return
                        TtsTaskPlayer.play(this@TimerService, text, onComplete = {
                            if (!isTtsLoopRunning) return@play
                            // 播放完成后等待 1 秒再播放下一次
                            ttsLoopHandler.postDelayed(this, 1000L)
                        })
                    }
                }
                isTtsLoopRunning = true
                ttsLoopHandler.post(ttsLoopRunnable)
                
                // 保存引用，方便 onDestroy 时停止
                this.ttsLoopHandler = ttsLoopHandler
                this.ttsLoopRunnable = ttsLoopRunnable
                
                return // 不立即 stopSelf，等用户关闭
            }
        }

        // 非 TTS 模式，6 秒后自动停止服务
        Thread {
            Thread.sleep(6000L)
            stopSelf()
        }.start()
    }
}
