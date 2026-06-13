package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.alarm.ChimeGenerator
import com.example.ui.components.WheelDialPicker
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.os.PowerManager
import android.util.Log

@Composable
fun TimerTab(
    remainingSeconds: Int,
    isRunning: Boolean,
    isRinging: Boolean,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismissRinging: () -> Unit,
    hours: Int,
    minutes: Int,
    seconds: Int,
    onSetHours: (Int) -> Unit,
    onSetMinutes: (Int) -> Unit,
    onSetSeconds: (Int) -> Unit,
    // 预警音设置（与 CountdownTab 共用）
    warningSoundType: String = "tick_tock",
    warningCustomPath: String = "",
    warningTtsText: String = ""
) {
    val context = LocalContext.current

    // 计时结束 → 播放预警音
    LaunchedEffect(isRinging) {
        if (isRinging) {
            playTimerFinishSound(context, warningSoundType, warningCustomPath, warningTtsText)
        }
    }
    // 组件卸载时停止
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isRinging) {
            // ── 计时结束，响铃中 ──
            Text(
                stringResource(R.string.timer_done),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "00:00:00",
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onDismissRinging,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.timer_dismiss), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else if (!isRunning) {
            Text(stringResource(R.string.set_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            // 三列拨盘选择器：时 / 分 / 秒
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.hours_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = hours,
                        range = 0..23,
                        onValueChange = onSetHours
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.minutes_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = minutes,
                        range = 0..59,
                        onValueChange = onSetMinutes
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.seconds_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = seconds,
                        range = 0..59,
                        onValueChange = onSetSeconds
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 显示合计时间预览
            val totalSecs = hours * 3600 + minutes * 60 + seconds
            Text(
                text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onStart(totalSecs) },
                enabled = totalSecs > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.start_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            val h = remainingSeconds / 3600
            val m = (remainingSeconds % 3600) / 60
            val s = remainingSeconds % 60

            Text(
                text = String.format("%02d:%02d:%02d", h, m, s),
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.stop_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ═══ 计时器响铃辅助函数 ═══

private fun playTimerFinishSound(context: Context, type: String, customPath: String, ttsText: String) {
    when (type) {
        "tick_tock" -> {
            Thread {
                try {
                    val tickOnce = com.example.alarm.ChimeGenerator.generateTickOnce(true)
                    val tockOnce = com.example.alarm.ChimeGenerator.generateTickOnce(false)
                    var isTick = true
                    for (i in 1..6) { // 大约响 6 秒
                        val data = if (isTick) tickOnce else tockOnce
                        isTick = !isTick
                        val track = android.media.AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            .setAudioFormat(
                                android.media.AudioFormat.Builder()
                                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                                    .setSampleRate(44100)
                                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(data.size * 4)
                            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                            .build()
                        try {
                            track.write(data, 0, data.size, android.media.AudioTrack.WRITE_BLOCKING)
                            track.play()
                            Thread.sleep(1000L)
                        } finally {
                            try { track.stop() } catch(_: Exception) {}
                            try { track.release() } catch(_: Exception) {}
                        }
                    }
                } catch(_: Exception) {}
            }.apply { isDaemon = true }.start()
        }
        "chime_0", "chime_1", "chime_2", "chime_3" -> {
            val pattern = type.last().digitToInt()
            ChimeGenerator.playChimePattern(pattern)
        }
        "custom" -> {
            if (customPath.isNotBlank()) {
                try {
                    val player = MediaPlayer().apply {
                        setDataSource(customPath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    Log.e("TimerTab", "播放自定义录音失败", e)
                }
            }
        }
        "tts" -> {
            val text = ttsText.ifBlank { "计时结束" }
            var ttsRef: TextToSpeech? = null
            val tts = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsRef?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "timer_finish")
                }
            })
            ttsRef = tts
        }
    }
}
