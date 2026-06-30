package com.ccsoft.alarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ccsoft.alarm.util.PreferencesManager
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

    /** 合成操作的互斥锁：确保同一时间只有一个合成任务在执行，避免引擎负载过高或并发冲突 */
    private val synthesisLock = java.util.concurrent.locks.ReentrantLock()

    /** 同步合成等待队列：utteranceId → CountDownLatch */
    private val syncLatches = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

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

    /** 当前选中的语音名称，用于调试和日志 */
    var voiceName: String = ""
        set(value) {
            field = value
            applyVoice()
        }

    /** 语音引擎包名（内部使用） */
    var enginePackage: String? = null

    /** 输出格式：wav 或 mp3 */
    var outputFormat: String = "wav"

    /** 是否使用 Edge TTS */
    val isEdgeTts: Boolean
        get() = engineName == "本地推荐tts"

    // ─── 初始化 ───

    init {
        Log.d(TAG, "TtsTaskPlayer 初始化")
    }

    /** 设置 TTS 引擎（公开方法，供外部设置用户选择的引擎） */
    fun setEngine(engine: String) {
        engineName = engine
        Log.i(TAG, "setEngine: 设置 TTS 引擎为: $engine")
    }
    
    /** 确保 TTS 实例已创建 (在主线程/后台线程均可调用) */
    fun ensure(ctx: Context) {
        if (isEdgeTts) {
            isReady = true
            return
        }
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

    /** 创建 TTS 实例 */
    private fun doCreateTts(ctx: Context) {
        appContext = ctx.applicationContext
        isReady = false
        initLatch = java.util.concurrent.CountDownLatch(1)
        val enginePkg = if (engineName.isNotEmpty()) engineName else null
        enginePackage = enginePkg
        Log.d(TAG, "创建 TTS 实例，引擎: ${enginePkg ?: "系统默认"}")
        tts = TextToSpeech(ctx, this, enginePkg)
    }

    /** TTS 初始化回调 */
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

            // 统一音频属性：使用闹钟通道
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            client.setAudioAttributes(audioAttributes)

            applyVoice()

            // 注册统一的合成完成监听（不要每次在 generateSync 中覆盖）
            client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id ->
                        syncLatches.remove(id)?.countDown()
                    }
                }
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "合成失败: utteranceId=$utteranceId")
                    utteranceId?.let { id ->
                        syncLatches.remove(id)?.countDown()
                    }
                }
            })

            isReady = true
            initLatch?.countDown()  // 通知等待初始化的线程

            val ctx = appContext ?: return
            val batch = pendingQueue.toList()
            pendingQueue.clear()
            if (batch.isNotEmpty()) {
                Log.d(TAG, "处理待定合成队列，批次大小: ${batch.size}")
                for ((text, callback) in batch) {
                    doSynthesize(ctx, text, callback)
                }
            } else {
                Log.d(TAG, "onInit 完成后无待定任务")
            }
        } else {
            Log.e(TAG, "TTS 初始化失败: status=$status")
            initLatch?.countDown()  // 失败也要释放，避免死等
        }
    }

    /** 应用用户选择的语音 */
    private fun applyVoice() {
        if (voiceName.isNotEmpty() && tts != null) {
            val allVoices = tts?.voices ?: emptyList()
            val matched = allVoices.find { it.name == voiceName }
            if (matched != null) {
                tts?.voice = matched
                Log.d(TAG, "applyVoice: 语音=\"${matched.name}\" (${matched.locale}),引擎=$engineName, 文件生成将使用此语音")
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

    // ─── 语音设置 ───

    /** 重置 TTS 设置为默认值 */
    fun resetSettings() {
        pitch = 1.0f
        speechRate = 1.0f
        voiceName = ""
        engineName = ""
    }

    /** 释放 TTS 资源 */
    fun shutdown() {
        syncLatches.values.forEach { it.countDown() }
        syncLatches.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingQueue.clear()
        Log.d(TAG, "TTS 资源已释放")
    }

    // ─── 合成逻辑 ───

    /** 获取缓存文件路径（预先检查文件有效性） */
    private fun cacheFile(ctx: Context, text: String): File {
        val safeName = text.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace("\\\\", "_").replace("/", "_")
        // 在文件名中加入语音、语速、音调标识，防止切换设置后依然播放旧缓存
        val settingsSuffix = buildString {
            if (voiceName.isNotEmpty()) {
                append("_v${voiceName.replace(Regex("[\\\\/:*?\"<>|]"), "_")}")
            }
            append("_r${(speechRate * 10).toInt()}")
            append("_p${(pitch * 10).toInt()}")
        }
        
        // 核心修复：优先使用用户在设置中指定的目录，实现卸载不丢缓存
        val prefs = PreferencesManager(ctx)
        val customPath = prefs.getRecordingPath()
        
        val baseDir = if (customPath.isNotEmpty()) {
            val dir = File(customPath, "tts_cache")
            if (!dir.exists()) dir.mkdirs()
            if (dir.exists() && dir.canWrite()) dir else File(ctx.filesDir, "tts_task_cache")
        } else {
            File(ctx.filesDir, "tts_task_cache")
        }

        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                Log.w(TAG, "无法创建缓存目录: ${baseDir.absolutePath}")
            }
        }
        // 确定文件后缀：Edge TTS 强制用 mp3，系统 TTS 强制用 wav（系统引擎不支持直接存 mp3）
        val ext = if (isEdgeTts) "mp3" else "wav"
        return File(baseDir, "task_$safeName$settingsSuffix.$ext")
    }

    /**
     * 同步生成 TTS 语音文件，等待合成完成再返回路径
     * 最多等待 timeoutMs 毫秒，超时返回 null
     *
     * @return 如果生成失败返回 null，成功返回文件绝对路径
     */
    fun generateSync(context: Context, text: String, timeoutMs: Long = 15000): String? {
        if (text.isBlank()) {
            Log.w(TAG, "generateSync: 非空文本要求")
            return null
        }
        val ctx = context.applicationContext
        val cached = cacheFile(ctx, text)

        // 清理无效缓存文件
        if (cached.exists() && cached.length() > 0) {
            // 缓存有效，直接返回
            Log.d(TAG, "generateSync: 缓存已存在且有效: ${cached.absolutePath}")
            return cached.absolutePath
        } else if (cached.exists()) {
            // 缓存存在但为空/损坏，删除旧缓存（下次重新生成）
            Log.w(TAG, "generateSync: 缓存存在但无效，删除并重试: ${cached.absolutePath}")
            cached.delete()
        }

        // 确保开发者能在控制台快速查找问题
        Log.d(TAG, "generateSync: 开始生成语音 (text=\"$text\", 文件=${cached.absolutePath})")

        // 使用锁确保串行合成，避免并发导致的超时或引擎崩溃
        val locked = try {
            synthesisLock.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }

        if (!locked) {
            Log.e(TAG, "generateSync: 无法获取合成锁，可能有太多任务在排队 (text=\"$text\")")
            return null
        }

        try {
            // 带重试的生成逻辑
            for (attempt in 1..2) {  // 最多重试 1 次
                if (!generateSyncOnce(ctx, text, cached, timeoutMs)) {
                    if (attempt == 2) {
                        Log.e(TAG, "generateSync: 重试后仍未生成成功 (text=\"$text\")")
                    } else {
                        Log.w(TAG, "generateSync: 合成失败，1 秒后重试 (文本=\"$text\")")
                        Thread.sleep(1000)
                    }
                } else {
                    // 检查生成结果是否有效
                    if (!cached.exists() || cached.length() == 0L) {
                        Log.e(TAG, "generateSync: 合成返回成功但文件无效 (path=${cached.absolutePath}, size=${cached.length()})")
                    } else {
                        Log.d(TAG, "generateSync: 成功生成语音 (path=${cached.absolutePath}, size=${cached.length()} bytes)")
                        return cached.absolutePath
                    }
                }
            }
        } finally {
            synthesisLock.unlock()
        }

        return null
    }

    /** 单次尝试生成 TTS 语音 */
    private fun generateSyncOnce(ctx: Context, text: String, cached: File, timeoutMs: Long): Boolean {
        if (isEdgeTts) {
            return generateEdgeTtsSync(text, cached, timeoutMs)
        }
        ensure(ctx)
        if (!isReady) {
            val isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            Log.d(TAG, "generateSync: TTS 未就绪，等待初始化... (isMainThread=$isMainThread)")
            // 在非主线程等待，避免死锁
            if (!isMainThread) {
                val remainingMs = timeoutMs
                val result = initLatch?.await(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (result == true && !isReady) {
                    Log.w(TAG, "generateSync: TTS 初始化超时 (timeout=$remainingMs ms)")
                    joinPendingQueue(text, null)
                    return false
                }
            }
            if (!isReady) {
                Log.w(TAG, "generateSync: TTS 仍然未就绪 (isMainThread=$isMainThread), 加入待定队列")
                joinPendingQueue(text, null)
                return false
            }
        }

        // 打印当前 TTS 语音设置
        val currentVoice = tts?.voice
        Log.d(TAG, "generateSync: 开始合成 text=\"$text\", voiceName=\"$voiceName\", 实际语音=${currentVoice?.name ?: "默认"} (${currentVoice?.locale ?: "?"})")

        val latch = java.util.concurrent.CountDownLatch(1)
        val utteranceId = "sync_${cached.nameWithoutExtension}_${System.currentTimeMillis()}"
        syncLatches[utteranceId] = latch

        val result = tts?.synthesizeToFile(text, null, cached, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            syncLatches.remove(utteranceId)
            Log.e(TAG, "synthesizeToFile 失败: result=$result, text=\"$text\"")
            return false
        }

        // 等待合成完成
        val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        syncLatches.remove(utteranceId)
        if (completed && cached.exists() && cached.length() > 0) {
            return true
        } else {
            val reason = when {
                !completed -> "超时 ($timeoutMs ms)"
                !cached.exists() -> "文件不存在"
                cached.length() == 0L -> "文件为空"
                else -> "未知错误"
            }
            Log.w(TAG, "generateSync: 合成未完成: $reason (text=\"$text\", file=${cached.absolutePath})")
            return false
        }
    }

    private fun generateEdgeTtsSync(text: String, cached: File, timeoutMs: Long): Boolean {
        // 强制使用 MP3 格式，因为 Edge 接口目前对 PCM/WAV 的支持极不稳定
        val edgeFormat = "audio-24khz-48kbitrate-mono-mp3"
        Log.d(TAG, "generateEdgeTtsSync: 开始 Edge TTS 合成 text=\"$text\", 强制格式=$edgeFormat")
        
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        var totalBytes = 0

        // 确保父目录存在
        val parent = cached.parentFile
        if (parent != null && !parent.exists()) {
            val ok = parent.mkdirs()
            Log.d(TAG, "创建缓存目录: $ok, path=${parent.absolutePath}")
        }
        
        // 如果文件已存在，先删除旧的，防止追加导致文件损坏
        if (cached.exists()) cached.delete()

        val client = EdgeTtsClient.Builder()
            .text(text)
            .voice(if (voiceName.isNotEmpty()) voiceName else "zh-CN-XiaoxiaoNeural")
            .rate(formatEdgeParam(speechRate))
            .pitch(formatEdgeParam(pitch, isPitch = true))
            .format(edgeFormat)
            .listener(object : EdgeTtsClient.EdgeTtsListener {
                override fun onAudioData(data: ByteArray) {
                    try {
                        cached.appendBytes(data)
                        totalBytes += data.size
                    } catch (e: Exception) {
                        errorMsg = "文件写入失败: ${e.message}"
                        Log.e(TAG, "写入字节失败", e)
                    }
                }
                override fun onComplete(s: Boolean, e: String?) {
                    success = s
                    if (e != null) errorMsg = e
                    Log.d(TAG, "Edge TTS 回调 onComplete: success=$s, error=$e, 已写入=$totalBytes 字节")
                    latch.countDown()
                }
            })
            .build()

        client.speak()
        val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!completed) {
            client.cancel()
            Log.e(TAG, "Edge TTS 合成超时 ($timeoutMs ms)")
            return false
        }
        
        if (!success || totalBytes < 1024) {
            Log.e(TAG, "Edge TTS 合成判定失败: $errorMsg (字节数=$totalBytes)")
            if (cached.exists()) cached.delete()
            return false
        }
        
        Log.i(TAG, "Edge TTS 合成成功: ${cached.absolutePath} ($totalBytes 字节)")
        return true
    }

    private fun formatEdgeParam(value: Float, isPitch: Boolean = false): String {
        // Edge TTS 接受 "+0%", "+0Hz" 等格式
        val percent = ((value - 1.0f) * 100).toInt()
        val sign = if (percent >= 0) "+" else ""
        return if (isPitch) "${sign}${percent}Hz" else "${sign}${percent}%"
    }

    /** 将任务加入待定队列（TTS 未就绪时调用） */
    private fun joinPendingQueue(text: String, callback: ((String) -> Unit)?) {
        pendingQueue.add(text to callback)
        if (!isReady) {
            Log.d(TAG, "generateSync: 已加入待定队列 (队列大小=${pendingQueue.size})")
        } else {
            pendingQueue.forEach { (t, cb) ->
                doSynthesize(appContext ?: return, t, cb)
            }
            pendingQueue.clear()
            Log.d(TAG, "generateSync: 待定队列已自动触发")
        }
    }

    private fun doSynthesize(ctx: Context, text: String, onFileReady: ((String) -> Unit)?) {
        val target = cacheFile(ctx, text)
        if (target.exists() && target.length() > 0) {
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
        }, 200)
    }

    // ─── 播放逻辑 ───

    /** 复用的 MediaPlayer，避免每次播放都 new + release 导致卡顿 */
    private var pooledPlayer: MediaPlayer? = null

    /**
     * 播放缓存文件（公开接口）
     * @param ctx 应用上下文
     * @param filePath 文件路径
     */
    fun playFile(ctx: Context, filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            playFile(ctx, file)
        } else {
            Log.e(TAG, "playFile: 文件不存在 - $filePath")
        }
    }

    /**
     * 播放缓存文件（内部函数：检查文件有效性并播放）
     * @param onComplete 播放完成后的回调
     */
    private fun playFile(ctx: Context, file: File, onComplete: (() -> Unit)? = null) {
        Log.i(TAG, "playFile: 开始播放 -> 路径: ${file.absolutePath}, 大小: ${file.length()} bytes")
        // 文件有效性检查
        if (!file.exists()) {
            Log.e(TAG, "playFile: 播放终止，文件不存在 - ${file.absolutePath}")
            onComplete?.invoke()
            return
        }
        if (file.length() == 0L) {
            Log.e(TAG, "playFile: 播放终止，文件为空 - ${file.absolutePath}")
            onComplete?.invoke()
            return
        }

        try {
            // 停止并重置复用池中的旧播放器
            pooledPlayer?.apply {
                try { stop() } catch (_: Exception) {}
                reset()
            }

            val mp = pooledPlayer ?: run {
                val newMp = MediaPlayer()
                newMp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                pooledPlayer = newMp
                newMp
            }

            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { 
                Log.d(TAG, "playFile: MediaPlayer 就绪，开始鸣响")
                mp.start() 
            }
            mp.setOnCompletionListener { 
                Log.d(TAG, "playFile: 播放完毕")
                // 不 release，reset 后复用
                onComplete?.invoke()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "playFile: MediaPlayer 错误 (what=$what, extra=$extra), 文件=${file.absolutePath}")
                try { mp.reset() } catch (_: Exception) {}
                onComplete?.invoke()
                true
            }
            try {
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "playFile: prepareAsync 失败 - ${file.absolutePath}", e)
                onComplete?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFile: 播放初始化失败 - ${file.absolutePath}", e)
            onComplete?.invoke()
        }
    }

    /**
     * 播放缓存文件（公开方法）
     */
    fun playFile(ctx: Context, filePath: String, onComplete: (() -> Unit)? = null) {
        val file = File(filePath)
        playFile(ctx, file, onComplete)
    }

    // ==================== 公开外部接口（供其他模块调用）====================

    /**
     * 获取指定文本对应的缓存文件（公开方法）
     * 供需要直接访问缓存文件路径的模块使用
     */
    fun getCacheFile(context: Context, text: String): File? {
        if (text.isBlank()) return null
        return cacheFile(context.applicationContext, text)
    }

    /**
     * 播放缓存文件（公开方法，支持回调）
     * @param ctx 应用上下文
     * @param text 文本内容（自动生成缓存文件）
     * @param onPlay 完成回调（可选）
     * @param onComplete 播放完成后的回调（可选）
     */
    fun play(context: Context, text: String, onPlay: ((String) -> Unit)? = null, onComplete: (() -> Unit)? = null) {
        Log.i(TAG, "play: 请求朗读文字 -> \"$text\"")
        ensure(context)
        val file = cacheFile(context.applicationContext, text)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "play: 缓存文件缺失，开始合成... (文本=\"$text\")")
            generateSync(context, text)
        }
        if (file.exists() && file.length() > 0) {
            playFile(context, file, onComplete)
            onPlay?.invoke(file.absolutePath)
        } else {
            Log.e(TAG, "play: 朗读失败，无法获取到有效的语音文件 (文本=\"$text\")")
            onComplete?.invoke()
        }
    }

    /**
     * 删除指定文本的缓存文件（公开方法）
     */
    fun deleteCache(context: Context, text: String) {
        if (text.isBlank()) return
        val file = cacheFile(context.applicationContext, text)
        if (file.exists() && file.delete()) {
            Log.d(TAG, "已删除缓存: ${file.absolutePath}")
        }
    }

    /**
     * 获取整个缓存目录（公开方法）
     * 供需要扫描整个缓存目录的模块使用
     */
    fun getCacheDir(context: Context): File {
        return File(context.applicationContext.filesDir, "tts_task_cache")
    }

    /**
     * 清理未使用的缓存（公开方法）
     * @param context 应用上下文
     * @param usedTexts 当前需要的文本集合
     * @return Pair<删除的文件数量, 释放的字节数>
     */
    fun cleanupUnused(context: Context, usedTexts: Set<String>): Pair<Int, Long> {
        val dir = getCacheDir(context)
        var deleted = 0
        var freedBytes = 0L

        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (file.extension == "wav" || file.extension == "mp3")) {
                    val fullName = file.nameWithoutExtension.replace(Regex("^task_"), "")
                    
                    // 检查文件名是否包含当前正在使用的文本（忽略后缀的语音标识）
                    val matches = usedTexts.any { text ->
                        val safeText = text.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace("\\\\", "_").replace("/", "_")
                        fullName == safeText || fullName.startsWith("${safeText}_")
                    }

                    if (!matches && fullName.isNotEmpty()) {
                        val size = file.length()
                        if (file.delete()) {
                            deleted++
                            freedBytes += size
                            Log.d(TAG, "清理未使用缓存: ${file.name} (路径=${file.absolutePath}, 大小=$size bytes)")
                        }
                    }
                }
            }
        }

        return deleted to freedBytes
    }
}