package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
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
    @Volatile
    private var isReady = false

    /** TTS 初始化同步锁：在后台线程调用 generateSync 时可等待初始化完成 */
    private var initLatch: java.util.concurrent.CountDownLatch? = null

    /** 待播放队列：合成完成后再播放 */
    private val pendingQueue = mutableListOf<Pair<String, ((String) -> Unit)?>>()

    // ─── 全局 TTS 设置 ───
    var pitch: Float = 1.0f
        set(value) { field = value; tts?.setPitch(value) }
    var speechRate: Float = 1.0f
        set(value) { field = value; tts?.setSpeechRate(value) }
    /** 用户选择的 TTS 引擎包名（如 com.google.android.tts），空=系统默认 */
    var engineName: String = ""
        set(value) {
            if (field == value) return
            Log.d(TAG, "设置 engineName: \"$value\" (旧值: \"$field\")")
            field = value
            // 引擎改变必须重建 TTS 实例
            shutdown()
        }
    var voiceName: String = ""
        set(value) {
            Log.d(TAG, "设置 voiceName: \"$value\" (旧值: \"$field\")")
            field = value
            applyVoice()
        }
    private fun applyVoice() {
        if (voiceName.isNotEmpty() && tts != null) {
            val allVoices = tts?.voices ?: emptyList()
            val matched = allVoices.find { it.name == voiceName }
            if (matched != null) {
                tts?.voice = matched
                val engine = tts?.let { t ->
                    try { t::class.java.name } catch (_: Exception) { "unknown" }
                } ?: "null"
                Log.d(TAG, "applyVoice: 引擎=$engine, 语音=\"${matched.name}\" (${matched.locale}), 文件生成将使用此语音")
            } else {
                Log.w(TAG, "applyVoice: 语音 \"$voiceName\" 在可用语音列表中未找到！可用语音共 ${allVoices.size} 个")
                // 列出前几个语音供调试
                allVoices.take(10).forEachIndexed { i, v ->
                    Log.d(TAG, "  可用语音[$i]: name=\"${v.name}\", locale=${v.locale}")
                }
            }
        } else {
            if (voiceName.isEmpty()) Log.d(TAG, "applyVoice: voiceName 为空，使用 TTS 引擎默认语音")
            if (tts == null) Log.d(TAG, "applyVoice: tts 尚未初始化，语音将在 TTS 就绪后应用")
        }
    }

    /**
     * 预初始化 TTS 引擎（尽早调用，避免 generateSync 时等待初始化）
     * 可在 Application.onCreate 或 ViewModel.init 中调用
     */
    fun ensureInitialized(context: Context) {
        ensure(context.applicationContext)
    }

    // ─── 公开方法 ───

    /** 获取缓存目录 */
    fun getCacheDir(context: Context): File = cacheDir(context.applicationContext)

    /**
     * 重建缺失的 TTS 语音缓存（不删除已有文件）
     * 遍历文本列表，仅生成缓存不存在的文件
     * @return 成功生成的文件数
     */
    fun rebuildMissingCache(context: Context, texts: List<String>): Int {
        val ctx = context.applicationContext
        var count = 0
        for (text in texts.distinct()) {
            try {
                val cached = cacheFile(ctx, text)
                if (cached.exists() && cached.length() > 0) continue // 已有缓存，跳过
                val path = generateSync(ctx, text)
                if (path != null) count++
            } catch (_: Exception) { }
        }
        return count
    }

    /**
     * 清除未被任何任务引用的 TTS 缓存文件
     * @param usedTexts 正在被使用的任务文本集合（所有 useTts 任务的 name）
     *        内部自动通过 cacheFile() 转为文件路径匹配，打卡和闹钟同名文本共享同一文件
     * @return Pair(删除的文件数, 释放的字节数)
     */
    fun cleanupUnused(context: Context, usedTexts: Set<String>): Pair<Int, Long> {
        val ctx = context.applicationContext
        val dir = cacheDir(ctx)
        if (!dir.exists()) return Pair(0, 0L)
        // 用文本生成路径，比用 ringtonePath 更可靠（ringtonePath 可能为 null）
        val usedPaths = usedTexts.map { cacheFile(ctx, it).absolutePath }.toSet()
        var deleted = 0
        var freedBytes = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".wav")) {
                if (file.absolutePath !in usedPaths) {
                    freedBytes += file.length()
                    file.delete()
                    deleted++
                    Log.d(TAG, "清除未使用缓存: ${file.name}")
                }
            }
        }
        if (deleted > 0) Log.d(TAG, "共清除 $deleted 个未使用的 TTS 缓存文件，释放 ${freedBytes / 1024}KB")
        return Pair(deleted, freedBytes)
    }

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
            Log.d(TAG, "generateSync: 缓存已存在，直接返回: ${cached.absolutePath}")
            return cached.absolutePath
        }

        ensure(ctx)
        if (!isReady) {
            Log.d(TAG, "generateSync: TTS 未就绪，等待初始化... (线程=${Thread.currentThread().name})")
            // onInit 在主线程回调。如果在主线程阻塞等待 initLatch，onInit 无法执行 → 死锁。
            // 所以只有在非主线程（后台线程）才能安全地 await
            val isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            if (!isMainThread) {
                initLatch?.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (!isReady) {
                Log.w(TAG, "generateSync: TTS 仍然未就绪 (isMainThread=$isMainThread, timeoutMs=$timeoutMs), text=\"$text\"")
                // 把任务加入 pending 队列，等 onInit 完成后自动合成
                pendingQueue.add(text to null)
                return null
            }
        }

        // 打印当前 TTS 语音设置
        val currentVoice = tts?.voice
        Log.d(TAG, "generateSync: 开始合成 text=\"$text\", voiceName=\"$voiceName\", 当前实际语音=${currentVoice?.name ?: "默认"} (${currentVoice?.locale ?: "?"}), 文件=${cached.absolutePath}")

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
            val isMainThread = Looper.myLooper() == Looper.getMainLooper()
            if (isMainThread) {
                doCreateTts(ctx)
            } else {
                // TextToSpeech 构造函数必须在有 Looper 的线程调用
                // 如果在后台线程直接创建，onInit 可能永远不会回调
                val latch = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(Looper.getMainLooper()).post {
                    try {
                        doCreateTts(ctx)
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun doCreateTts(ctx: Context) {
        appContext = ctx.applicationContext
        isReady = false
        initLatch = java.util.concurrent.CountDownLatch(1)
        val enginePkg = if (engineName.isNotEmpty()) engineName else null
        Log.d(TAG, "创建 TTS 实例，引擎: ${enginePkg ?: "系统默认"}")
        tts = TextToSpeech(ctx, this, enginePkg)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val client = tts ?: return

            // 打印 TTS 引擎信息
            val engineInfo = try {
                val enginePkg = client.voice?.name?.let { "engine=$it" } ?: ""
                "TTS引擎: ${client::class.java.name} $enginePkg"
            } catch (_: Exception) { "TTS引擎: 未知" }
            Log.d(TAG, "onInit: $engineInfo")
            Log.d(TAG, "onInit: pitch=$pitch, speechRate=$speechRate, voiceName=\"$voiceName\"")
            val allVoices = client.voices ?: emptyList()
            Log.d(TAG, "onInit: 可用语音共 ${allVoices.size} 个")
            allVoices.take(5).forEachIndexed { i, v ->
                Log.d(TAG, "onInit:   语音[$i]: name=\"${v.name}\", locale=${v.locale}, quality=${v.quality}")
            }

            client.setLanguage(Locale.CHINA)
            client.setPitch(pitch)
            client.setSpeechRate(speechRate)
            applyVoice()

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
            initLatch?.countDown()  // 通知等待初始化的线程

            val ctx = appContext ?: return
            val batch = pendingQueue.toList()
            pendingQueue.clear()
            for ((text, callback) in batch) {
                doSynthesize(ctx, text, callback)
            }
        } else {
            Log.e(TAG, "TTS 初始化失败: status=$status")
            initLatch?.countDown()  // 失败也要释放，避免死等
        }
    }

    private fun doSynthesize(ctx: Context, text: String, onFileReady: ((String) -> Unit)?) {
        val target = cacheFile(ctx, text)
        if (target.exists()) {
            playFile(ctx, target)
            onFileReady?.invoke(target.absolutePath)
            return
        }

        Log.d(TAG, "合成语音: \"$text\" → ${target.absolutePath} (voiceName=\"$voiceName\", 当前语音=${tts?.voice?.name ?: "默认"})")

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
