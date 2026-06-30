package com.ccsoft.alarm.alarm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 统一报时语音预热器
 *
 * 策略：
 * 1. 统一调用 TtsTaskPlayer 合成并缓存报时语音
 * 2. 结果文件优先存放于用户指定的公共目录，实现卸载不丢数据
 * 3. 之后启动直接播放缓存，减少重复合成开销
 */
object ChimeAudioPreloader {
    private const val TAG = "ChimePreloader"

    // ★ 唯一文本定义源：播放和合成都从这里取，不再散落硬编码
    fun hourText(hour: Int): String = "$hour 点整"
    fun minuteText(minute: Int): String = "$minute 分钟"

    /** 检查文件是否有效（存在且大小大于 1KB） */
    private fun isValid(file: File): Boolean {
        return file.exists() && file.length() > 1024
    }

    /** 获取特定小时的语音文件 */
    fun file(context: Context, hour: Int): File {
        return TtsTaskPlayer.getCacheFile(context, hourText(hour))!!
    }

    /** 获取特定分钟的预警语音文件 (1-10) */
    fun minuteFile(context: Context, minute: Int): File {
        return TtsTaskPlayer.getCacheFile(context, minuteText(minute))!!
    }

    /**
     * 检查并生成缺失的语音（包含24小时整点 + 10分钟预警）
     */
    fun ensure(context: Context) {
        val ctx = context.applicationContext
        
        // 检查24小时整点
        val missingHours = (0..23).filter { !isValid(file(ctx, it)) }
        // 检查1-10分钟预警
        val missingMinutes = (1..10).filter { !isValid(minuteFile(ctx, it)) }

        if (missingHours.isEmpty() && missingMinutes.isEmpty()) {
            Log.d(TAG, "所有本地报时及预警语音已就绪")
            return
        }

        Log.i(TAG, "检测到 ${missingHours.size} 个报时语音和 ${missingMinutes.size} 个预警语音需要合成")
        
        Thread {
            // 合成整点
            missingHours.forEach { h ->
                TtsTaskPlayer.generateSync(ctx, hourText(h))
            }
            // 合成预警分钟
            missingMinutes.forEach { m ->
                TtsTaskPlayer.generateSync(ctx, minuteText(m))
            }
            
            Log.i(TAG, "所有语音合成任务尝试完毕")
        }.start()
    }

    /**
     * 强制重新生成所有报时语音（用于用户修改 TTS 设置后的手动测试/重置）
     */
    fun rebuildCache(context: Context) {
        ensure(context)
    }

    /** 兼容旧代码：标记缓存失效并尝试重新生成 */
    fun resetCacheFlag(context: Context) {
        ensure(context)
    }
}
