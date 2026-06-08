package com.example.alarm

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.tts.MicrosoftTtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

/**
 * 统一报时语音预热器
 *
 * 策略：
 * 1. 优先使用微软在线 TTS 合成 24 段报时语音（晓晓女声，音质统一好听）
 * 2. 如果微软 TTS 不可用（无网络），退回到系统 TTS
 * 3. 结果文件全部缓存到本地，下次启动直接播放
 */
object ChimeAudioPreloader : TextToSpeech.OnInitListener {
    private const val TAG = "ChimePreloader"
    private const val PREFS_NAME = "chime_prefs"
    private const val KEY_CACHE_READY = "chime_cache_ready"

    // 微软 TTS 默认凭据（免费版每月 50 万字符）
    // 用户可在设置页面替换为自己的 API Key
    private const val MS_SUBSCRIPTION_KEY = "YOUR_AZURE_SUBSCRIPTION_KEY"
    private const val MS_REGION = "eastasia"
    private const val MS_VOICE_NAME = "zh-CN-XiaoxiaoNeural"

    private var tts: TextToSpeech? = null
    private var context: Context? = null

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, "chime_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取特定小时的语音文件（优先 MP3，其次 WAV） */
    fun file(context: Context, hour: Int): File {
        val mp3 = File(getDir(context), "hour_$hour.mp3")
        if (mp3.exists()) return mp3
        val wav = File(getDir(context), "hour_$hour.wav")
        if (wav.exists()) return wav
        return mp3 // 默认返回 MP3（用于新建）
    }

    /** 获取特定小时的 WAV 文件（系统 TTS 用） */
    private fun fileWav(context: Context, hour: Int): File {
        return File(getDir(context), "hour_$hour.wav")
    }

    /**
     * 检查并生成缺失的语音
     */
    fun ensure(context: Context) {
        this.context = context.applicationContext
        val ctx = context.applicationContext

        // 每次启动都检查文件是否真的存在
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CACHE_READY, false).apply()

        // 检查是否全部文件已存在（MP3 或 WAV 都算）
        val missingHours = (0..23).filter { !file(ctx, it).exists() }
        if (missingHours.isEmpty()) {
            prefs.edit().putBoolean(KEY_CACHE_READY, true).apply()
            Log.d(TAG, "24 段报时语音已全部存在")
            return
        }

        // 先尝试微软 TTS（在线合成，晓晓女声）
        tryMicrosoftSynthesis(ctx, missingHours) { success ->
            if (success) {
                Log.d(TAG, "微软 TTS 合成完成")
                prefs.edit().putBoolean(KEY_CACHE_READY, true).apply()
            } else {
                // 微软失败，回退到系统 TTS
                Log.w(TAG, "微软 TTS 不可用，回退到系统 TTS")
                tts = TextToSpeech(ctx, this)
            }
        }
    }

    /** 使用微软 TTS 批量合成缺失的报时语音 */
    private fun tryMicrosoftSynthesis(
        context: Context,
        missingHours: List<Int>,
        onResult: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = MicrosoftTtsClient()
                val tokenResult = client.getAccessToken(MS_SUBSCRIPTION_KEY, MS_REGION)
                if (tokenResult.isFailure) {
                    Log.e(TAG, "微软 TTS token 获取失败: ${tokenResult.exceptionOrNull()?.message}")
                    onResult(false)
                    return@launch
                }
                val token = tokenResult.getOrNull() ?: run { onResult(false); return@launch }

                var allSuccess = true
                for (h in missingHours) {
                    val text = "现在是北京时间 ${h} 点整"
                    val result = client.synthesize(text, token, MS_REGION, MS_VOICE_NAME)
                    if (result.isSuccess) {
                        val audioData = result.getOrNull() ?: continue
                        val targetFile = file(context, h)  // hour_h.mp3
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            targetFile.writeBytes(audioData)
                        }
                        Log.d(TAG, "微软 TTS 合成第 $h 点完成 (${audioData.size}B)")
                    } else {
                        Log.e(TAG, "微软 TTS 合成第 $h 点失败: ${result.exceptionOrNull()?.message}")
                        allSuccess = false
                    }
                }

                // 如果全部成功，删除旧的 WAV 文件
                if (allSuccess) {
                    for (h in 0..23) {
                        val oldWav = File(getDir(context), "hour_$h.wav")
                        if (oldWav.exists()) oldWav.delete()
                    }
                }
                onResult(allSuccess)
            } catch (e: Exception) {
                Log.e(TAG, "微软 TTS 合成异常", e)
                onResult(false)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsClient = tts ?: return
            ttsClient.setLanguage(Locale.CHINA)
            ttsClient.setPitch(1.0f)
            ttsClient.setSpeechRate(1.0f)

            val ctx = context ?: return
            val prefs = ctx.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
            val savedVoice = prefs.getString("tts_voice", "") ?: ""
            if (savedVoice.isNotEmpty()) {
                val matched = ttsClient.voices?.find { it.name == savedVoice }
                if (matched != null) ttsClient.setVoice(matched)
            }

            var anySynthesized = false
            for (h in 0..23) {
                val target = file(ctx, h)
                if (!target.exists()) {
                    val text = "现在是北京时间 ${h} 点整"
                    val wavFile = File(getDir(ctx), "hour_$h.wav")
                    ttsClient.synthesizeToFile(text, null, wavFile, "chime_$h")
                    Log.d(TAG, "系统 TTS 合成第 $h 点...")
                    anySynthesized = true
                }
            }

            if (!anySynthesized) {
                prefs.edit().putBoolean(KEY_CACHE_READY, true).apply()
                Log.d(TAG, "24 段报时语音均已就绪")
            }
        } else {
            Log.e(TAG, "TTS 初始化失败: status=$status")
            // 不标记为就绪，下次启动重新尝试
        }
    }

    fun resetCacheFlag(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CACHE_READY, false).apply()
        Log.d(TAG, "缓存标记已重置")
    }
}
