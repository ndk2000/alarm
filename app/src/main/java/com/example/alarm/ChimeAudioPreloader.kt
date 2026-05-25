package com.example.alarm

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.*

/**
 * 报时语音预热器
 * 预先将 0-23 点的 TTS 语音录制为本地文件，实现"内置化"效果
 *
 * ★ 使用 SharedPreferences 持久化标记，避免每次启动都重复检查/合成
 */
object ChimeAudioPreloader : TextToSpeech.OnInitListener {
    private const val TAG = "ChimePreloader"
    private const val PREFS_NAME = "chime_prefs"
    private const val KEY_CACHE_READY = "chime_cache_ready"
    private var tts: TextToSpeech? = null
    private var context: Context? = null
    private var isInitialized = false

    /** 获取报时文件保存目录 */
    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, "chime_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取特定小时的语音文件 */
    fun file(context: Context, hour: Int): File {
        return File(getDir(context), "hour_$hour.wav")
    }

    /**
     * 开始检查并生成缺失的语音
     * ★ 已被 SharedPreferences 标记过 → 直接跳过，不阻塞主线程
     */
    fun ensure(context: Context) {
        this.context = context.applicationContext
        val ctx = context.applicationContext

        // ★ 关键优化：如果之前已经标记完成，直接跳过所有 I/O
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CACHE_READY, false)) {
            Log.d(TAG, "报时语音缓存已就绪，跳过检查和合成")
            return
        }

        // 检查 24 段文件是否存在
        val missingHours = (0..23).filter { !file(ctx, it).exists() }
        if (missingHours.isNotEmpty()) {
            Log.d(TAG, "发现 ${missingHours.size} 段报时语音缺失，准备合成...")
            tts = TextToSpeech(ctx, this)
        } else {
            // ★ 全部文件已存在，标记完成，下次直接跳过
            prefs.edit().putBoolean(KEY_CACHE_READY, true).apply()
            Log.d(TAG, "24 段报时语音已全部存在，标记缓存就绪")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsClient = tts ?: return
            ttsClient.setLanguage(Locale.CHINA)
            ttsClient.setPitch(1.0f)
            ttsClient.setSpeechRate(1.0f)

            val ctx = context ?: return
            var anySynthesized = false
            for (h in 0..23) {
                val target = file(ctx, h)
                if (!target.exists()) {
                    val text = "现在是北京时间 ${h} 点整"
                    // 使用 synthesizeToFile 将 TTS 输出直接录制到文件
                    ttsClient.synthesizeToFile(text, null, target, "chime_$h")
                    Log.d(TAG, "正在合成第 $h 点语音...")
                    anySynthesized = true
                }
            }

            // ★ 标记已初始化，防止重复进入
            isInitialized = true

            // ★ 如果没有任何需要合成的，直接标记完成
            if (!anySynthesized) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_CACHE_READY, true).apply()
                Log.d(TAG, "无需合成，标记缓存就绪")
            }
            // 合成任务是异步的，tts 实例保持开启直到应用关闭或任务队列完成
        } else {
            Log.e(TAG, "TTS 初始化失败: status=$status")
            // ★ TTS 引擎不可用，标记为已完成（避免每次都重试）
            val ctx = context
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_CACHE_READY, true).apply()
                Log.w(TAG, "TTS 不可用，标记缓存就绪避免重复尝试")
            }
        }
    }

    /** 重置缓存状态（供调试或手动刷新使用） */
    fun resetCacheFlag(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CACHE_READY, false).apply()
        isInitialized = false
        Log.d(TAG, "缓存标记已重置")
    }
}
