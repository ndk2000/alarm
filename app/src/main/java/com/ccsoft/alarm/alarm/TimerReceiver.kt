package com.ccsoft.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ccsoft.alarm.service.TimerService

/**
 * 计时器结束广播接收器
 * 在计时结束时启动 TimerService 播放声音
 */
class TimerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimerReceiver"
        const val ACTION_TIMER_FINISH = "com.ccsoft.alarm.TIMER_FINISH"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_TIMER_FINISH) {
            Log.i(TAG, "收到计时结束广播，启动 TimerService")
            val serviceIntent = Intent(context, TimerService::class.java).apply {
                action = TimerService.ACTION_TIMER_FINISH
                putExtra(TimerService.EXTRA_SOUND_TYPE, intent.getStringExtra(TimerService.EXTRA_SOUND_TYPE) ?: "tick_tock")
                putExtra(TimerService.EXTRA_CUSTOM_PATH, intent.getStringExtra(TimerService.EXTRA_CUSTOM_PATH) ?: "")
                putExtra(TimerService.EXTRA_TTS_TEXT, intent.getStringExtra(TimerService.EXTRA_TTS_TEXT) ?: "")
            }
            context?.startService(serviceIntent)
        }
    }
}
