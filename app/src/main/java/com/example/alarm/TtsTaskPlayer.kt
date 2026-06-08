package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.*

/**
 * 打卡任务 TTS 试听播放器
 * 第一次播放指定文本时通过 TTS 合成为 .wav 缓存文件，
 * 之后直接播放缓存，减少重复合成开销。
 *
 * 缓存位置: {filesDir}/tts_task_cache/task_{safe_text}.wav
 * 文件名直接使用文字内容（经非法字符清理），同一文本始终映射到同一文件。
 */
object TtsTaskPlayer : TextToSpeech.OnInitListener {
    private const val TAG = "TtsTaskPlayer"
    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var isReady = false

    /** 待播放队列：合成完成后再播放 */
    private val pendingQueue = mutableListOf<Pair<String, ((String) -> Unit)?>>()

    // ─── 公开方法 ───

    /** 获取缓存目录 */
    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "tts_task_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 根据文本获取缓存文件（不论是否存在） */
    fun cacheFile(ctx: Context, text: String): File {
        // 清理文件名中的非法字符，取前 50 字符避免路径过长
        val safeName = text
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(50)
        return File(cacheDir(ctx), "task_${safeName}.wav")
    }

    /** 如果缓存已存在，返回文件路径；否则返回 null */
    fun getCachedPath(context: Context, text: String): String? {
        val f = cacheFile(context.applicationContext, text)
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * 播放试听（仅播放，不回调）
     * 第一次合成需等待 TTS 引擎就绪 + 合成完成
     */
    fun play(context: Context, text: String) {
        play(context, text, null)
    }

    /**
     * 播放试听，并在缓存文件就绪时回调文件路径
     * @param onFileReady 文件可用时回调（主线程），参数为绝对路径
     */
    fun play(context: Context, text: String, onFileReady: ((String) -> Unit)?) {
        if (text.isBlank()) return
        val ctx = context.applicationContext

        val cached = cacheFile(ctx, text)
        if (cached.exists()) {
            Log.d(TAG, "播放缓存: ${cached.absolutePath}")
            playFile(ctx, cached)
            onFileReady?.invoke(cached.absolutePath)
            return
        }

        // 需要合成
        ensure(ctx)
        if (isReady) {
            doSynthesize(ctx, text, onFileReady)
        } else {
            pendingQueue.add(text to onFileReady)
        }
    }

    /** 删除指定文本的缓存文件（改名后清理旧文件用） */
    fun deleteCache(context: Context, text: String) {
        val f = cacheFile(context.applicationContext, text)
        if (f.exists()) {
            f.delete()
            Log.d(TAG, "已删除旧缓存: ${f.absolutePath}")
        }
    }

    /**
     * 同步生成 TTS 语音文件，等待合成完成再返回路径
     * 最多等待 timeoutMs 毫秒，超时返回 null
     */
    fun generateSync(context: Context, text: String, timeoutMs: Long = 5000): String? {
        if (text.isBlank()) return null
        val ctx = context.applicationContext
        val cached = cacheFile(ctx, text)
        if (cached.exists() && cached.length() > 0) {
            return cached.absolutePath
        }

        ensure(ctx)
        if (!isReady) {
            Log.w(TAG, "TTS 未就绪，无法同步合成")
            return null
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        val utteranceId = "sync_${cached.nameWithoutExtension}_${System.currentTimeMillis()}"
        val capturedId = utteranceId

        // 注册临时监听，等待合成完成
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == capturedId) {
                    latch.countDown()
                }
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == capturedId) {
                    latch.countDown()
                }
            }
        })

        val result = tts?.synthesizeToFile(text, null, cached, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "synthesizeToFile 失败: result=$result")
            return null
        }

        // 等待合成完成，最多 timeoutMs
        val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (completed && cached.exists() && cached.length() > 0) {
            Log.d(TAG, "同步合成完成: ${cached.absolutePath}")
            return cached.absolutePath
        } else {
            Log.w(TAG, "同步合成超时或失败: $text")
            return null
        }
    }

    /** 释放 TTS 资源 */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // ─── 内部实现 ───

    private fun ensure(ctx: Context) {
        if (tts == null) {
            appContext = ctx.applicationContext
            isReady = false
            tts = TextToSpeech(ctx, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val client = tts ?: return
            client.setLanguage(Locale.CHINA)
            client.setPitch(1.0f)
            client.setSpeechRate(1.0f)

            // 注册合成完成监听
            client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    // 由 doSynthesize 中的回调处理
                }
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "合成失败: utteranceId=$utteranceId")
                }
            })

            isReady = true

            val ctx = appContext ?: return
            val batch = pendingQueue.toList()
            pendingQueue.clear()
            for ((text, callback) in batch) {
                doSynthesize(ctx, text, callback)
            }
        } else {
            Log.e(TAG, "TTS 初始化失败: status=$status")
        }
    }

    private fun doSynthesize(ctx: Context, text: String, onFileReady: ((String) -> Unit)?) {
        val target = cacheFile(ctx, text)
        if (target.exists()) {
            playFile(ctx, target)
            onFileReady?.invoke(target.absolutePath)
            return
        }

        Log.d(TAG, "合成语音: \"$text\" → ${target.absolutePath}")

        val utteranceId = "task_${target.nameWithoutExtension}_${System.currentTimeMillis()}"
        val result = tts?.synthesizeToFile(text, null, target, utteranceId)
        if (result == TextToSpeech.SUCCESS) {
            // 轮询等待合成完成（最多 5 秒）
            pollForFile(target, ctx, text, onFileReady, System.currentTimeMillis())
        } else {
            Log.w(TAG, "synthesizeToFile 未成功 (result=$result)，改用实时 speak")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            onFileReady?.let { cb ->
                // 实时合成没有文件可回调，只能放弃
            }
        }
    }

    private fun pollForFile(
        target: File, ctx: Context, text: String,
        onFileReady: ((String) -> Unit)?, startMs: Long
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (target.exists() && target.length() > 0) {
                Log.d(TAG, "合成完成: ${target.absolutePath}")
                playFile(ctx, target)
                onFileReady?.invoke(target.absolutePath)
            } else if (System.currentTimeMillis() - startMs > 5_000) {
                Log.w(TAG, "合成超时(5s)，实时 speak 兜底")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                pollForFile(target, ctx, text, onFileReady, startMs)
            }
        }, 500)
    }

    private fun playFile(ctx: Context, file: File) {
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener { release() }
                setOnErrorListener { _, _, _ -> release(); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放失败: ${file.absolutePath}", e)
        }
    }
}
