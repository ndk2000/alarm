package com.ccsoft.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 接收守护服务重启广播，用于 App 被划掉后自动重启守护服务。
 * 这是一个独立的 BroadcastReceiver，不依赖守护服务的进程。
 */
class AlarmGuardRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmGuardRestart"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "========== 收到守护服务重启广播，立即启动 AlarmGuardService ==========")
        try {
            AlarmGuardService.start(context)
            Log.w(TAG, "✓ 已成功启动 AlarmGuardService")
        } catch (e: Exception) {
            Log.e(TAG, "✗ 启动 AlarmGuardService 失败: ${e.message}", e)
        }
    }
}
