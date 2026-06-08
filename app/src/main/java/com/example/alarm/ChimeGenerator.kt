package com.example.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 程序化钟声合成引擎
 *
 * 用加法合成（fundamental + harmonics + 指数衰减包络）模拟悦耳的钟声/八音盒音色。
 * 不需要任何外部音频文件，纯代码生成。
 */
object ChimeGenerator {

    private const val TAG = "ChimeGenerator"
    private const val SAMPLE_RATE = 44100
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

    // 音名 → 频率映射（十二平均律, A4 = 440Hz）
    private val NOTES = mapOf(
        "C3" to 130.81, "D3" to 146.83, "E3" to 164.81, "F3" to 174.61, "G3" to 196.00,
        "A3" to 220.00, "B3" to 246.94,
        "C4" to 261.63, "D4" to 293.66, "E4" to 329.63, "F4" to 349.23, "G4" to 392.00,
        "A4" to 440.00, "B4" to 493.88,
        "C5" to 523.25, "D5" to 587.33, "E5" to 659.25, "F5" to 698.46, "G5" to 783.99,
        "A5" to 880.00, "B5" to 987.77,
        "C6" to 1046.50, "D6" to 1174.66, "E6" to 1318.51
    )

    /**
     * 播放指定模式的钟声（后台线程）
     * @param pattern 0=旋律钟声, 1=西敏寺钟声, 2=清亮上行, 3=梦幻叮咚
     */
    fun playChimePattern(pattern: Int = 0) {
        Thread {
            try {
                val sequence = when (pattern) {
                    1 -> westminsterChime()
                    2 -> brightAscending()
                    3 -> dreamyDingDong()
                    else -> melodicChime()
                }
                playSequence(sequence)
            } catch (e: Exception) {
                Log.e(TAG, "播放钟声失败", e)
            }
        }.apply { isDaemon = true }.start()
    }

    // ── 核心合成 ──────────────────────────────────────────────────

    private data class Note(
        val name: String,              // 音名 e.g. "C5"
        val durationSec: Float = 1.2f, // 持续秒数
        val decayRate: Float = 2.2f,   // 衰减速率（越大衰减越快）
        val volume: Float = 0.7f,      // 音量 0~1
        val delayMs: Int = 0           // 延迟启动（毫秒）
    )

    /** 生成单个钟声音符 */
    private fun generateTone(freq: Double, durSec: Float, decay: Float, vol: Float): FloatArray {
        val nSamples = (SAMPLE_RATE * durSec).toInt().coerceAtLeast(1)
        val buf = FloatArray(nSamples)

        // 谐波结构： (倍频, 振幅, 高频额外衰减系数)
        val harmonics = arrayOf(
            1.0  to 1.00,
            2.0  to 0.48,
            3.0  to 0.32,
            4.0  to 0.22,
            5.0  to 0.14,
            6.0  to 0.10,
            7.0  to 0.07,
            8.0  to 0.05,
            10.0 to 0.03,
            12.0 to 0.02
        )

        for (i in 0 until nSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            // 主包络：指数衰减
            val envelope = exp(-decay.toDouble() * t)

            var sample = 0.0
            for ((h, amp) in harmonics) {
                val hFreq = freq * h
                // 轻微非谐性（模拟真实钟声，越高频偏移越大）
                val detune = 1.0 + 0.0025 * (h - 1.0)
                // 高频额外快速衰减
                val extraDecay = exp(-0.4 * (h - 1.0) * t)
                val hAmp = amp * extraDecay
                sample += sin(2.0 * PI * hFreq * detune * t) * hAmp
            }

            buf[i] = (sample * envelope * vol.toDouble()).toFloat().coerceIn(-1f, 1f)
        }
        return buf
    }

    /** 将多个音符混合成一条音频 */
    private fun mixNotes(notes: List<Note>): FloatArray {
        // 计算总时长
        val totalMs = notes.maxOf { it.delayMs + (it.durationSec * 1000).toInt() }
        val totalSamples = (SAMPLE_RATE * totalMs / 1000f).toInt() + SAMPLE_RATE
        val mix = FloatArray(totalSamples)

        for (note in notes) {
            val freq = NOTES[note.name] ?: continue
            val samples = generateTone(freq, note.durationSec, note.decayRate, note.volume)
            val offset = (SAMPLE_RATE * note.delayMs / 1000f).toInt()

            for (i in samples.indices) {
                val pos = offset + i
                if (pos < mix.size) {
                    val sum = mix[pos] + samples[i]
                    // 软限幅防爆
                    mix[pos] = when {
                        sum > 1f -> 1f - (sum - 1f) * 0.3f
                        sum < -1f -> -1f - (sum + 1f) * 0.3f
                        else -> sum
                    }
                }
            }
        }
        return mix
    }

    /** 通过 AudioTrack 播放合成音频 */
    private fun playSequence(notes: List<Note>) {
        val audio = mixNotes(notes)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(audio.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
            track.play()

            // 等待播放完成自动停止
            val durationMs = (audio.size.toLong() * 1000) / SAMPLE_RATE
            Thread.sleep(durationMs + 200)
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    // ── 钟声旋律模式 ───────────────────────────────────────────────

    /** 模式 0 — 旋律钟声（C大调琶音上行，温暖明亮） */
    private fun melodicChime(): List<Note> = listOf(
        Note("C5",  durationSec = 1.0f, decayRate = 2.0f, volume = 0.65f, delayMs = 0),
        Note("E5",  durationSec = 0.9f, decayRate = 2.2f, volume = 0.55f, delayMs = 350),
        Note("G5",  durationSec = 0.8f, decayRate = 2.5f, volume = 0.50f, delayMs = 650),
        Note("C6",  durationSec = 2.2f, decayRate = 1.5f, volume = 0.45f, delayMs = 1000)
    )

    /** 模式 1 — 西敏寺钟声风格 */
    private fun westminsterChime(): List<Note> = listOf(
        Note("G5",  durationSec = 0.7f, decayRate = 2.8f, volume = 0.55f, delayMs = 0),
        Note("E5",  durationSec = 0.7f, decayRate = 2.8f, volume = 0.55f, delayMs = 250),
        Note("C5",  durationSec = 0.7f, decayRate = 2.8f, volume = 0.55f, delayMs = 500),
        Note("D5",  durationSec = 0.9f, decayRate = 2.2f, volume = 0.60f, delayMs = 750),
        Note("G4",  durationSec = 2.0f, decayRate = 1.8f, volume = 0.45f, delayMs = 1100)
    )

    /** 模式 2 — 清亮上行（八音盒风格） */
    private fun brightAscending(): List<Note> = listOf(
        Note("C4",  durationSec = 0.6f, decayRate = 3.0f, volume = 0.45f, delayMs = 0),
        Note("E4",  durationSec = 0.6f, decayRate = 3.0f, volume = 0.45f, delayMs = 200),
        Note("G4",  durationSec = 0.6f, decayRate = 3.0f, volume = 0.45f, delayMs = 400),
        Note("C5",  durationSec = 0.6f, decayRate = 3.0f, volume = 0.45f, delayMs = 600),
        Note("E5",  durationSec = 0.8f, decayRate = 2.5f, volume = 0.50f, delayMs = 800),
        Note("G5",  durationSec = 0.8f, decayRate = 2.5f, volume = 0.50f, delayMs = 1050),
        Note("C6",  durationSec = 0.8f, decayRate = 2.5f, volume = 0.50f, delayMs = 1300),
        Note("E6",  durationSec = 1.5f, decayRate = 1.8f, volume = 0.40f, delayMs = 1550)
    )

    /** 模式 3 — 梦幻叮咚（大三和弦轻响） */
    private fun dreamyDingDong(): List<Note> = listOf(
        Note("A4",  durationSec = 0.4f, decayRate = 4.0f, volume = 0.50f, delayMs = 0),
        Note("C5",  durationSec = 0.6f, decayRate = 3.5f, volume = 0.45f, delayMs = 150),
        Note("E5",  durationSec = 0.6f, decayRate = 3.5f, volume = 0.45f, delayMs = 300),
        Note("A5",  durationSec = 0.8f, decayRate = 2.8f, volume = 0.40f, delayMs = 450),
        Note("E5",  durationSec = 0.6f, decayRate = 3.5f, volume = 0.35f, delayMs = 700),
        Note("C5",  durationSec = 0.6f, decayRate = 3.5f, volume = 0.30f, delayMs = 900),
        Note("A4",  durationSec = 1.2f, decayRate = 2.0f, volume = 0.30f, delayMs = 1100)
    )

    // ── 老式闹钟滴答声 ─────────────────────────────────────────────

    private const val TICK_SAMPLE_RATE = 44100
    private const val TICK_DURATION_MS = 80 // 每个滴答声 80ms
    private var tickTockThread: Thread? = null
    private val tickTockLock = Any()

    /** 生成一个单独的"滴"或"答"声音（机械式短促点击） */
    private fun generateTick(isTick: Boolean): FloatArray {
        val nSamples = (TICK_SAMPLE_RATE * TICK_DURATION_MS / 1000).coerceAtLeast(1)
        val buf = FloatArray(nSamples)
        // 主频：滴 1500Hz, 答 1200Hz（模拟机械齿轮声）
        val freq = if (isTick) 1500.0 else 1200.0
        for (i in 0 until nSamples) {
            val t = i.toDouble() / TICK_SAMPLE_RATE
            // 极速衰减包络
            val envelope = exp(-35.0 * t)
            // 基波 + 少量泛音（模拟金属撞击）
            val sample = sin(2.0 * PI * freq * t) * 0.6 +
                         sin(2.0 * PI * freq * 2.7 * t) * 0.2 +
                         sin(2.0 * PI * freq * 5.3 * t) * 0.1
            buf[i] = (sample * envelope * 0.7).toFloat().coerceIn(-1f, 1f)
        }
        return buf
    }

    /**
     * 持续播放老式机械闹钟滴答声（后台线程循环）
     * 每 1 秒播放一次（滴-答交替，模拟真实机械钟）
     */
    fun playTickTockContinuous() {
        synchronized(tickTockLock) {
            // 如果已有线程在播放，不再重复启动
            if (tickTockThread?.isAlive == true) return
            tickTockThread = Thread {
                try {
                    val tickAudio = generateTick(true)
                    val tockAudio = generateTick(false)
                    var isTickNow = true
                    while (!Thread.currentThread().isInterrupted) {
                        val audio = if (isTickNow) tickAudio else tockAudio
                        isTickNow = !isTickNow
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                    .setSampleRate(TICK_SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(audio.size * 4)
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build()
                        try {
                            track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
                            track.play()
                            // 大约 920ms 静默间隔，凑够 1 秒
                            Thread.sleep(920L)
                        } finally {
                            try { track.stop() } catch (_: Exception) {}
                            try { track.release() } catch (_: Exception) {}
                        }
                    }
                } catch (_: InterruptedException) {
                    // 正常停止
                } catch (e: Exception) {
                    Log.e(TAG, "滴答声播放异常", e)
                }
            }.apply { isDaemon = true }
            tickTockThread!!.start()
        }
    }

    /** 停止老式闹钟滴答声 */
    fun stopTickTock() {
        synchronized(tickTockLock) {
            tickTockThread?.interrupt()
            tickTockThread = null
        }
    }
}
