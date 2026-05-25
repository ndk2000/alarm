package com.example.alarm

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.*

/**
 * 报时语音预热器
 * 预先将 0-23 点的 TTS 语音录制为本地文件，实现“内置化”效果
 */
object ChimeAudioPreloader : TextToSpeech.OnInitListener {
    private const val TAG = "ChimePreloader"
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

    /** 开始检查并生成缺失的语音 */
    fun ensure(context: Context) {
        this.context = context.applicationContext
        val missingHours = (0..23).filter { !file(context, it).exists() }
        if (missingHours.isNotEmpty()) {
            Log.d(TAG, "发现 ${missingHours.size} 段报时语音缺失，准备合成...")
            tts = TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsClient = tts ?: return
            ttsClient.setLanguage(Locale.CHINA)
            ttsClient.setPitch(1.0f)
            ttsClient.setSpeechRate(1.0f)

            val ctx = context ?: return
            for (h in 0..23) {
                val target = file(ctx, h)
                if (!target.exists()) {
                    val text = "现在是北京时间 ${h} 点整"
                    // 使用 synthesizeToFile 将 TTS 输出直接录制到文件
                    ttsClient.synthesizeToFile(text, null, target, "chime_$h")
                    Log.d(TAG, "正在合成第 $h 点语音...")
                }
            }
            // 合成任务是异步的，tts 实例保持开启直到应用关闭或任务队列完成
        }
    }
}
