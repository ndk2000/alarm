package com.example.alarm

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 预先生成 24 小时报时语音 WAV 文件，报时时直接播放本地文件，无需实时 TTS。
 * 文件位置：{context.cacheDir}/chime_audio/chime_{hour}.wav
 *
 * 在 MainActivity 初始化时调用 ensure() 即可。
 */
object ChimeAudioPreloader {

    private const val TAG = "ChimeAudioPreloader"
    private const val DIR = "chime_audio"

    private var tts: TextToSpeech? = null

    /** 小时 → 中文报时文本，带现在时间前缀更自然 */
    private fun timeText(hour: Int): String {
        return when (hour) {
            0 -> "现在时间，零点整"
            1 -> "现在时间，一点整"
            2 -> "现在时间，两点整"
            3 -> "现在时间，三点整"
            4 -> "现在时间，四点整"
            5 -> "现在时间，五点整"
            6 -> "现在时间，六点整"
            7 -> "现在时间，七点整"
            8 -> "现在时间，八点整"
            9 -> "现在时间，九点整"
            10 -> "现在时间，十点整"
            11 -> "现在时间，十一点整"
            12 -> "现在时间，十二点整"
            13 -> "现在时间，下午一点整"
            14 -> "现在时间，下午两点整"
            15 -> "现在时间，下午三点整"
            16 -> "现在时间，下午四点整"
            17 -> "现在时间，下午五点整"
            18 -> "现在时间，下午六点整"
            19 -> "现在时间，下午七点整"
            20 -> "现在时间，下午八点整"
            21 -> "现在时间，下午九点整"
            22 -> "现在时间，下午十点整"
            23 -> "现在时间，下午十一点整"
            else -> "现在时间，${hour}点整"
        }
    }

    /** 返回某小时的缓存音频文件 */
    fun file(context: Context, hour: Int): File {
        val dir = File(context.cacheDir, DIR)
        return File(dir, "chime_$hour.wav")
    }

    /** 24 个文件是否全部存在 */
    fun isComplete(context: Context): Boolean {
        for (h in 0..23) {
            if (!file(context, h).exists()) return false
        }
        return true
    }

    /**
     * 确保 24 段音频已被缓存。
     * 如果齐全立即回调；否则初始化 TTS 逐一合成。
     * @param callback 全部就绪后回调（可能同步也可能异步）
     */
    fun ensure(context: Context, callback: Runnable? = null) {
        if (isComplete(context)) {
            Log.d(TAG, "24 段报时音频已缓存，无需重新生成")
            callback?.run()
            return
        }

        tts = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失败，使用实时 TTS 回退")
                callback?.run()
                return@OnInitListener
            }

            val lang = tts?.setLanguage(Locale.CHINESE)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS 不支持中文，使用实时 TTS 回退")
                callback?.run()
                return@OnInitListener
            }

            // 尝试选一个好听的中文语音
            try {
                val voices = tts?.voices ?: emptyList()
                val preferred = voices.firstOrNull { v ->
                    v.locale.language == "zh" && (
                            v.name.contains("high-quality") ||
                            v.name.contains("standard") ||
                            !v.name.contains("network")  // 优先离线语音
                            )
                }
                if (preferred != null) {
                    tts?.voice = preferred
                    Log.d(TAG, "选中语音：${preferred.name}")
                }
            } catch (_: Exception) {}

            tts?.setSpeechRate(0.82f)  // 稍慢，清晰端庄
            tts?.setPitch(1.0f)       // 自然音高

            generateAll(context, callback)
        })
    }

    private fun generateAll(context: Context, callback: Runnable?) {
        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        // 清理旧文件
        dir.listFiles()?.filter { it.name.endsWith(".wav") }?.forEach { it.delete() }

        val remaining = (0..23).toMutableList()
        var localTts: TextToSpeech? = null

        // 初始化本地 TTS 实例，不与类级共享以避免竞态
        localTts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS 初始化失败：$status，跳过预生成")
                callback?.run()
                return@TextToSpeech
            }
            localTts?.language = Locale.CHINA
            localTts?.setSpeechRate(0.82f)
            localTts?.setPitch(1.0f)

            // 递归 lambda：逐段合成，用 UtteranceProgressListener 链式驱动
            lateinit var synthesizeNext: () -> Unit
            synthesizeNext = {
                if (remaining.isEmpty()) {
                    localTts?.shutdown()
                    localTts = null
                    Log.d(TAG, "🎉 24 段报时音频全部生成完毕")
                    if (isComplete(context)) {
                        Log.d(TAG, "✅ 缓存完整性验证通过")
                    } else {
                        Log.w(TAG, "⚠️ 部分文件缺失，将使用实时 TTS 回退")
                    }
                    callback?.run()
                } else {
                    val hour = remaining.removeAt(0)
                    val text = timeText(hour)
                    val outFile = file(context, hour)
                    Log.d(TAG, "生成第 ${hour} 小时：$text")
                    val extras = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, hour.toString())
                    }
                    val result = localTts?.synthesizeToFile(text, extras, outFile, hour.toString())
                    if (result != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "synthesizeToFile 返回 $result，跳过 $hour")
                        synthesizeNext()
                    }
                }
            }

            localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onStop(utteranceId: String?, interrupted: Boolean) {}
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "合成失败：$utteranceId，跳过")
                    synthesizeNext()
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        Log.d(TAG, "✅ 第 ${utteranceId} 小时报时合成完成")
                    }
                    synthesizeNext()
                }
            })

            synthesizeNext()
        }
    }

    /** 释放 TTS 资源 */
    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
