package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.util.BatteryOptimizationGuide
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import android.media.MediaRecorder
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private data class SettingsTab(
    val label: String,
    val labelRes: Int? = null
)

private val tabs = listOf(
    SettingsTab("通用"),
    SettingsTab("偏移"),
    SettingsTab("预警音"),
    SettingsTab("语音"),
    SettingsTab("电池"),
    SettingsTab("关于", R.string.about_section),
    SettingsTab("权限")
)

@Composable
fun AppSettingsDialog(
    theme: Int,
    lang: String,
    offsetHours: Int,
    offsetMinutes: Int,
    recordingPath: String,
    dbDirectoryPath: String,
    autoUpdate: Boolean,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetOffsetHours: (Int) -> Unit,
    onSetOffsetMinutes: (Int) -> Unit,
    onSetRecordingPath: (String) -> Unit,
    onSetDatabaseDirectoryPath: (String) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit = {},
    onDismiss: () -> Unit,
    // 语音合成(TTS)参数
    availableTtsEngines: List<EngineInfo> = emptyList(),
    selectedTtsEngine: String = "",
    onSetTtsEngine: (String) -> Unit = {},
    availableVoices: List<Voice> = emptyList(),
    selectedTtsVoice: String = "",
    onSetTtsVoice: (String) -> Unit = {},
    ttsPitch: Float = 1.0f,
    ttsRate: Float = 1.0f,
    onSetTtsPitch: (Float) -> Unit = {},
    onSetTtsRate: (Float) -> Unit = {},
    onTestTts: (Int) -> Unit = {},
    onScanTtsEngines: () -> Unit = {},
    debugLogs: List<String> = emptyList(),
    onCleanupUnusedCache: () -> Unit = {},
    onRebuildMissingCache: () -> Unit = {},
    // 倒计时预警设置
    countdownWarningSeconds: Int = 120,
    currentSoundType: String = "tick_tock",
    currentCustomPath: String = "",
    currentTtsText: String = "",
    onSetCountdownWarningSeconds: (Int) -> Unit = {},
    onSetCountdownWarningSoundType: (String) -> Unit = {},
    onSetCustomPath: (String) -> Unit = {},
    onSetTtsText: (String) -> Unit = {},
    // 计时器响铃设置（独立选择，与倒计时同一张卡片）
    timerFinishSoundType: String = "tick_tock",
    timerFinishCustomPath: String = "",
    timerFinishTtsText: String = "",
    onSetTimerFinishSoundType: (String) -> Unit = {},
    onSetTimerFinishCustomPath: (String) -> Unit = {},
    onSetTimerFinishTtsText: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var tempPath by remember { mutableStateOf(recordingPath) }
    var tempDbPath by remember { mutableStateOf(dbDirectoryPath) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // ════ 顶部标题栏 + Tabs ════
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        // 标题 + 关闭按钮
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "设置",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) {
                                Text("完成", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            }
                        }
                        // Tab 行（可横向滚动，适配窄屏）
                        ScrollableTabRow(
                            selectedTabIndex = selectedTab,
                            edgePadding = 8.dp,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            divider = {}
                        ) {
                            tabs.forEachIndexed { i, tab ->
                                Tab(
                                    selected = selectedTab == i,
                                    onClick = { selectedTab = i },
                                    text = {
                                        Text(tab.label, fontSize = 12.sp, fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                // ════ 内容区（占满剩余空间 + 可滚动）════
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    when (selectedTab) {
                            0 -> GeneralSettingsContent(
                                lang = lang,
                                theme = theme,
                                autoUpdate = autoUpdate,
                                tempPath = tempPath,
                                tempDbPath = tempDbPath,
                                onSetLanguage = onSetLanguage,
                                onSetTheme = onSetTheme,
                                onSetAutoUpdate = onSetAutoUpdate,
                                onCheckUpdate = onCheckUpdate,
                                onTempPathChange = { tempPath = it },
                                onSavePath = { onSetRecordingPath(tempPath) },
                                onTempDbPathChange = { tempDbPath = it },
                                onSaveDbPath = { onSetDatabaseDirectoryPath(tempDbPath) }
                            )
                            1 -> OffsetContent(
                                offsetHours = offsetHours,
                                offsetMinutes = offsetMinutes,
                                onSetOffsetHours = onSetOffsetHours,
                                onSetOffsetMinutes = onSetOffsetMinutes
                            )
                            2 -> WarningSoundContent(
                                countdownWarningSeconds = countdownWarningSeconds,
                                currentSoundType = currentSoundType,
                                currentCustomPath = currentCustomPath,
                                currentTtsText = currentTtsText,
                                onSetCountdownWarningSeconds = onSetCountdownWarningSeconds,
                                onSetCountdownWarningSoundType = onSetCountdownWarningSoundType,
                                onSetCustomPath = onSetCustomPath,
                                onSetTtsText = onSetTtsText,
                                timerFinishSoundType = timerFinishSoundType,
                                timerFinishCustomPath = timerFinishCustomPath,
                                timerFinishTtsText = timerFinishTtsText,
                                onSetTimerFinishSoundType = onSetTimerFinishSoundType,
                                onSetTimerFinishCustomPath = onSetTimerFinishCustomPath,
                                onSetTimerFinishTtsText = onSetTimerFinishTtsText
                            )
                            3 -> TtsSettingsContent(
                                availableTtsEngines = availableTtsEngines,
                                selectedTtsEngine = selectedTtsEngine,
                                onSetTtsEngine = onSetTtsEngine,
                                availableVoices = availableVoices,
                                selectedTtsVoice = selectedTtsVoice,
                                onSetTtsVoice = onSetTtsVoice,
                                ttsPitch = ttsPitch,
                                ttsRate = ttsRate,
                                onSetTtsPitch = onSetTtsPitch,
                                onSetTtsRate = onSetTtsRate,
                                onTestTts = onTestTts,
                                onScanTtsEngines = onScanTtsEngines,
                                debugLogs = debugLogs,
                                onCleanupUnusedCache = onCleanupUnusedCache,
                                onRebuildMissingCache = onRebuildMissingCache
                            )
                            4 -> BatteryContent()
                            5 -> AboutContent()
                            6 -> PermissionContent(
                                context = context,
                                onRequestPermissions = { /* handled inside */ }
                            )
                        }
                    }
                }
            }
        }
    }

    // ════ 通用设置（语言 + 主题 + 更新 + 录音）════
@Composable
private fun GeneralSettingsContent(
    lang: String,
    theme: Int,
    autoUpdate: Boolean,
    tempPath: String,
    tempDbPath: String,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (Int) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onTempPathChange: (String) -> Unit,
    onSavePath: () -> Unit,
    onTempDbPathChange: (String) -> Unit,
    onSaveDbPath: () -> Unit,
    // 倒计时预警
    countdownWarningSeconds: Int = 120,
    currentSoundType: String = "tick_tock",
    currentCustomPath: String = "",
    currentTtsText: String = "",
    onSetCountdownWarningSeconds: (Int) -> Unit = {},
    onSetCountdownWarningSoundType: (String) -> Unit = {},
    onSetCustomPath: (String) -> Unit = {},
    onSetTtsText: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 语言与主题 ──
        SettingsSectionCard("🌐 语言与主题") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.language), fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onSetLanguage("zh") }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(stringResource(R.string.chinese), fontSize = 12.sp, color = if(lang=="zh") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onSetLanguage("en") }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("English", fontSize = 12.sp, color = if(lang=="en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.themes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val themeNames = listOf(stringResource(R.string.theme_night), stringResource(R.string.theme_forest), stringResource(R.string.theme_sunset))
                val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFF2E7D4F), Color(0xFFCC5422))
                themeNames.forEachIndexed { i, name ->
                    val bgColor = if (theme == i) colors[i] else colors[i].copy(alpha = 0.25f)
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp)
                            .clip(RoundedCornerShape(8.dp)).background(bgColor)
                            .then(if (theme == i) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier.border(1.dp, colors[i].copy(alpha = 0.4f), RoundedCornerShape(8.dp)))
                            .clickable { onSetTheme(i) },
                        contentAlignment = Alignment.Center
                    ) { Text(name, fontSize = 11.sp, color = if (theme == i) Color.White else colors[i], fontWeight = if(theme == i) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }

        // ── 更新设置 ──
        SettingsSectionCard("📦 更新") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("自动检测更新", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = autoUpdate, onCheckedChange = onSetAutoUpdate)
            }
            Button(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Text("手动检查更新", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }

        // ── 数据库设置 ──
        SettingsSectionCard("🗄 数据库目录") {
            Text("公共目录路径（仅目录，不含文件名）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tempDbPath, onValueChange = onTempDbPathChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.primary)
            )
            Text(text = "例如 /storage/emulated/0/DroidCloudAlarm，需授予“所有文件访问权限”。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = onSaveDbPath, modifier = Modifier.align(Alignment.End)) { Text("保存目录", fontSize = 12.sp) }
        }

        // ── 录音设置 ──
        SettingsSectionCard("🎤 录音设置") {
            Text("录音存放路径", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tempPath, onValueChange = onTempPathChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.primary)
            )
            TextButton(onClick = onSavePath, modifier = Modifier.align(Alignment.End)) { Text("保存", fontSize = 12.sp) }
        }
    }
}

// ════ 警告音设置 ════
@Composable
private fun WarningSoundContent(
    countdownWarningSeconds: Int = 120,
    currentSoundType: String = "tick_tock",
    currentCustomPath: String = "",
    currentTtsText: String = "",
    onSetCountdownWarningSeconds: (Int) -> Unit = {},
    onSetCountdownWarningSoundType: (String) -> Unit = {},
    onSetCustomPath: (String) -> Unit = {},
    onSetTtsText: (String) -> Unit = {},
    // 计时器响铃设置（同一张卡片，独立选择）
    timerFinishSoundType: String = "tick_tock",
    timerFinishCustomPath: String = "",
    timerFinishTtsText: String = "",
    onSetTimerFinishSoundType: (String) -> Unit = {},
    onSetTimerFinishCustomPath: (String) -> Unit = {},
    onSetTimerFinishTtsText: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionCard("🔊 预警音设置") {
            val previewCtx = LocalContext.current
            val previewScope = rememberCoroutineScope()

            // ── 作用目标选择 ──
            var target by remember { mutableStateOf("countdown") } // "countdown" | "timer"
            Text("配置目标", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = target == "countdown",
                    onClick = { target = "countdown" },
                    label = { Text("⏰ 倒计时预警", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = target == "timer",
                    onClick = { target = "timer" },
                    label = { Text("⏱ 计时器响铃", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))

            // 根据目标选择绑定的值和回调
            val currentSoundType = if (target == "countdown") currentSoundType else timerFinishSoundType
            val currentCustomPath = if (target == "countdown") currentCustomPath else timerFinishCustomPath
            val currentTtsText = if (target == "countdown") currentTtsText else timerFinishTtsText
            val onSetSoundType: (String) -> Unit = if (target == "countdown") onSetCountdownWarningSoundType else onSetTimerFinishSoundType
            val onSetCustomPath: (String) -> Unit = if (target == "countdown") onSetCustomPath else onSetTimerFinishCustomPath
            val onSetTtsText: (String) -> Unit = if (target == "countdown") onSetTtsText else onSetTimerFinishTtsText

            // 倒计时预警专属：时长选择
            if (target == "countdown") {
                Text("提前预警时间", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                val durationOpts = listOf(30 to "30秒", 60 to "1分钟", 120 to "2分钟", 180 to "3分钟", 300 to "5分钟", 600 to "10分钟")
                durationOpts.chunked(3).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        chunk.forEach { (sec, label) ->
                            FilterChip(
                                selected = countdownWarningSeconds == sec,
                                onClick = { onSetCountdownWarningSeconds(sec) },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── 音色选择（共用，读写根据 target 切换） ──
            Text("音色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            var previewingSound by remember { mutableStateOf<String?>(null) }
            val previewPlayer = remember { mutableStateOf<MediaPlayer?>(null) }

            fun previewSound(type: String) {
                // 停止之前的预览
                previewPlayer.value?.stop()
                previewPlayer.value?.release()
                previewPlayer.value = null
                previewingSound = type
                when (type) {
                    "tick_tock" -> {
                        // 本地 AudioTrack 预览，不依赖 ChimeGenerator 的全局线程
                        previewScope.launch {
                            try {
                                val tickData = com.example.alarm.ChimeGenerator.generateTickOnce(true)
                                val tockData = com.example.alarm.ChimeGenerator.generateTickOnce(false)
                                var isTick = true
                                repeat(3) { // 播放 3 声
                                    val data = if (isTick) tickData else tockData
                                    isTick = !isTick
                                    val track = android.media.AudioTrack.Builder()
                                        .setAudioAttributes(
                                            android.media.AudioAttributes.Builder()
                                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                                        kotlinx.coroutines.delay(1000L)
                                    } finally {
                                        try { track.stop() } catch(_: Exception) {}
                                        try { track.release() } catch(_: Exception) {}
                                    }
                                }
                            } catch(_: Exception) {}
                            previewingSound = null
                        }
                    }
                    "chime_0", "chime_1", "chime_2", "chime_3" -> {
                        val pattern = type.last().digitToInt()
                        com.example.alarm.ChimeGenerator.playChimePattern(pattern)
                        previewScope.launch {
                            kotlinx.coroutines.delay(4000)
                            previewingSound = null
                        }
                    }
                    "custom" -> {
                        if (currentCustomPath.isNotBlank()) {
                            try {
                                val player = MediaPlayer()
                                previewPlayer.value = player
                                val path = currentCustomPath
                                if (path.startsWith("content://") || path.startsWith("android.resource://")) {
                                    player.setDataSource(previewCtx, android.net.Uri.parse(path))
                                } else {
                                    player.setDataSource(path)
                                }
                                player.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                                )
                                player.setOnCompletionListener { previewingSound = null }
                                player.prepare()
                                player.start()
                            } catch (e: Exception) {
                                previewingSound = null
                                android.widget.Toast.makeText(previewCtx, "无法播放该文件", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            android.widget.Toast.makeText(previewCtx, "请先选择录音文件", android.widget.Toast.LENGTH_SHORT).show()
                            previewingSound = null
                        }
                    }
                }
            }

            val soundOptions = listOf(
                "tick_tock" to "滴答声",
                "chime_0" to "旋律钟声",
                "chime_1" to "西敏寺钟声",
                "chime_2" to "清亮上行",
                "chime_3" to "梦幻叮咚",
                "custom" to "自定义录音",
                "tts" to "TTS 朗读文字"
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 每行最多 3 个
                val chunks = soundOptions.chunked(3)
                for (chunk in chunks) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        chunk.forEach { (type, label) ->
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                FilterChip(
                                    selected = currentSoundType == type,
                                    onClick = { onSetSoundType(type) },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (type != "tts") {
                                    IconButton(
                                        onClick = { previewSound(type) },
                                        modifier = Modifier.size(28.dp),
                                        enabled = previewingSound != type
                                    ) {
                                        Icon(
                                            if (previewingSound == type) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "试听",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        // 补空位
                        repeat(3 - chunk.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // 自定义录音 — 真正的录音/选择文件
            if (currentSoundType == "custom") {
                Spacer(Modifier.height(8.dp))
                Text("自定义录音文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))

                // 录音状态（必须在 launcher 之前定义）
                var showRecording by remember { mutableStateOf(false) }
                var isRec by remember { mutableStateOf(false) }
                var recDuration by remember { mutableIntStateOf(0) }
                var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
                // 跟踪当前录音文件路径
                var currentRecFilePath by remember { mutableStateOf<String?>(null) }

                // 文件选择器
                val audioPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        val fileName = "warning_${System.currentTimeMillis()}.mp3"
                        try {
                            val savedPath = File(previewCtx.filesDir, "ringtones").also { it.mkdirs() }
                            val destFile = File(savedPath, fileName)
                            previewCtx.contentResolver.openInputStream(uri)?.use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            onSetCustomPath(destFile.absolutePath)
                            Toast.makeText(previewCtx, "已选择: $fileName", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(previewCtx, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 录音权限请求
                val recordPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) showRecording = true
                    else Toast.makeText(previewCtx, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("选择文件", fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            val perm = Manifest.permission.RECORD_AUDIO
                            val hasPerm = ContextCompat.checkSelfPermission(previewCtx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                showRecording = true
                            } else {
                                recordPermLauncher.launch(perm)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E1717))
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("录制新录音", fontSize = 11.sp, color = Color.White)
                    }
                }

                // 录音对话框
                if (showRecording) {
                    AlertDialog(
                        onDismissRequest = {
                            showRecording = false
                            isRec = false
                            recDuration = 0
                            try { mediaRecorder?.stop(); mediaRecorder?.release() } catch(_: Exception) {}
                            mediaRecorder = null
                        },
                        title = { Text("录制新录音", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (isRec) "录音中..." else "准备录音",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "${recDuration / 60}:${String.format("%02d", recDuration % 60)}",
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isRec) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        confirmButton = {
                            if (!isRec) {
                                Button(
                                    onClick = {
                                        try {
                                            val dir = File(previewCtx.filesDir, "ringtones").also { it.mkdirs() }
                                            val file = File(dir, "warning_rec_${System.currentTimeMillis()}.m4a")
                                            currentRecFilePath = file.absolutePath
                                            val recorder = MediaRecorder().apply {
                                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                setAudioSamplingRate(44100)
                                                setAudioChannels(1)
                                                setAudioEncodingBitRate(96000)
                                                setOutputFile(file.absolutePath)
                                                prepare()
                                                start()
                                            }
                                            mediaRecorder = recorder
                                            isRec = true
                                            // 计时
                                            previewScope.launch {
                                                while (isRec) {
                                                    kotlinx.coroutines.delay(1000)
                                                    recDuration++
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(previewCtx, "录音启动失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) { Text("开始录音", fontWeight = FontWeight.Bold) }
                            } else {
                                Button(
                                    onClick = {
                                        isRec = false
                                        try {
                                            mediaRecorder?.stop()
                                            mediaRecorder?.release()
                                            mediaRecorder = null
                                            val savedPath = currentRecFilePath
                                            if (savedPath != null && File(savedPath).exists()) {
                                                onSetCustomPath(savedPath)
                                                Toast.makeText(previewCtx, "已保存录音", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(previewCtx, "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                        showRecording = false
                                        recDuration = 0
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                                ) { Text("停止并保存", color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showRecording = false
                                isRec = false
                                recDuration = 0
                                try { mediaRecorder?.stop(); mediaRecorder?.release() } catch(_: Exception) {}
                                mediaRecorder = null
                            }) { Text("取消") }
                        }
                    )
                }
                if (currentCustomPath.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                currentCustomPath.substringAfterLast('/'),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // 播放试听按钮
                            var isPlayingCustom by remember { mutableStateOf(false) }
                            var customPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                            IconButton(
                                onClick = {
                                    if (isPlayingCustom) {
                                        customPlayer?.stop()
                                        customPlayer?.release()
                                        customPlayer = null
                                        isPlayingCustom = false
                                    } else {
                                        try {
                                            val player = MediaPlayer()
                                            customPlayer = player
                                            player.setDataSource(currentCustomPath)
                                            player.setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                    .build()
                                            )
                                            player.setOnCompletionListener {
                                                isPlayingCustom = false
                                                customPlayer?.release()
                                                customPlayer = null
                                            }
                                            player.prepare()
                                            player.start()
                                            isPlayingCustom = true
                                        } catch (e: Exception) {
                                            Toast.makeText(previewCtx, "无法播放", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (isPlayingCustom) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingCustom) "停止" else "播放",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = {
                                customPlayer?.stop()
                                customPlayer?.release()
                                customPlayer = null
                                isPlayingCustom = false
                                onSetCustomPath("")
                                currentRecFilePath = null
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            // TTS 文字 + 试听按钮
            if (currentSoundType == "tts") {
                Spacer(Modifier.height(8.dp))
                Text("朗读文字", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                var ttsText by remember(currentTtsText) { mutableStateOf(currentTtsText) }
                OutlinedTextField(
                    value = ttsText,
                    onValueChange = {
                        ttsText = it
                        onSetTtsText(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    placeholder = { Text("例如：注意！闹钟即将响起", fontSize = 11.sp) }
                )
                Spacer(Modifier.height(6.dp))
                // TTS 试听
                var isTtsTesting by remember { mutableStateOf(false) }
                val ttsForPreview = remember { mutableStateOf<TextToSpeech?>(null) }
                Button(
                    onClick = {
                        val text = ttsText.ifBlank { "注意！闹钟即将响起" }
                        isTtsTesting = true
                        if (ttsForPreview.value == null) {
                            ttsForPreview.value = TextToSpeech(previewCtx) {
                                if (it == TextToSpeech.SUCCESS) {
                                    ttsForPreview.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
                                }
                                previewScope.launch {
                                    kotlinx.coroutines.delay(3000)
                                    isTtsTesting = false
                                }
                            }
                        } else {
                            ttsForPreview.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
                            previewScope.launch {
                                kotlinx.coroutines.delay(3000)
                                isTtsTesting = false
                            }
                        }
                    },
                    enabled = !isTtsTesting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(
                        if (isTtsTesting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isTtsTesting) "播放中..." else "▶ 试听", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(Modifier.height(8.dp))
        Text("⏱ 计时器响铃", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text("音色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val sOpts2 = listOf(
            "tick_tock" to "滴答声", "chime_0" to "旋律钟声", "chime_1" to "西敏寺钟声",
            "chime_2" to "清亮上行", "chime_3" to "梦幻叮咚", "custom" to "自定义录音",
            "tts" to "TTS 朗读文字"
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sOpts2.chunked(3).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    chunk.forEach { (type, label) ->
                        FilterChip(
                            selected = timerFinishSoundType == type,
                            onClick = { onSetTimerFinishSoundType(type) },
                            label = { Text(label, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(3 - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
        if (timerFinishSoundType == "custom") {
            Spacer(Modifier.height(8.dp))
            Text("录音文件路径", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = timerFinishCustomPath,
                onValueChange = onSetTimerFinishCustomPath,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        if (timerFinishSoundType == "tts") {
            Spacer(Modifier.height(8.dp))
            Text("朗读文字", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = timerFinishTtsText,
                onValueChange = onSetTimerFinishTtsText,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = false,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                placeholder = { Text("例如：时间到！", fontSize = 11.sp) }
            )
        }
    }
}

// ════ 复制偏移 ════

// ════ 复制偏移 ════
@Composable
private fun OffsetContent(
    offsetHours: Int,
    offsetMinutes: Int,
    onSetOffsetHours: (Int) -> Unit,
    onSetOffsetMinutes: (Int) -> Unit
) {
    SettingsSectionCard("⏱ 复制偏移") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("当前偏移", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+$offsetHours:$offsetMinutes", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("时", fontSize = 12.sp, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = offsetHours.toFloat(), onValueChange = { onSetOffsetHours(it.toInt()) }, valueRange = 0f..24f, modifier = Modifier.weight(1f))
            Text("${offsetHours}h", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分", fontSize = 12.sp, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = offsetMinutes.toFloat(), onValueChange = { onSetOffsetMinutes(it.toInt()) }, valueRange = 0f..59f, modifier = Modifier.weight(1f))
            Text("${offsetMinutes}m", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
    }
}

    // ════ 电池优化 ════
@Composable
private fun BatteryContent() {
    val bCtx = LocalContext.current
    val batteryDisabled = remember { BatteryOptimizationGuide.isBatteryOptimizationDisabled(bCtx) }
    SettingsSectionCard(stringResource(R.string.alarm_reliability)) {
        Text(stringResource(R.string.battery_opt_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { BatteryOptimizationGuide.openBatteryOptimizationSettings(bCtx) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (batteryDisabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary,
                contentColor = if (batteryDisabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
            ), enabled = !batteryDisabled
        ) { Text(if (batteryDisabled) stringResource(R.string.battery_opt_already_disabled) else stringResource(R.string.disable_battery_opt), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.disable_battery_opt_tip), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { BatteryOptimizationGuide.openAutostartSettings(bCtx) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        ) { Text(stringResource(R.string.autostart_title), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.autostart_tip), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        var showManufacturerGuide by remember { mutableStateOf(false) }
        TextButton(onClick = { showManufacturerGuide = !showManufacturerGuide }) {
            Text(stringResource(R.string.manufacturer_guide_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (showManufacturerGuide) {
            val tip = remember { BatteryOptimizationGuide.getManufacturerTip() }
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(tip, fontSize = 10.sp, modifier = Modifier.padding(12.dp), lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ════ 关于 ════
@Composable
private fun AboutContent() {
    SettingsSectionCard(stringResource(R.string.about_section)) {
        Text("${stringResource(R.string.version_format, BuildConfig.VERSION_NAME)} (${stringResource(R.string.build_date_format, BuildConfig.BUILD_DATE)})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        var showChangelog by remember { mutableStateOf(false) }
        TextButton(onClick = { showChangelog = !showChangelog }) {
            Text(if (showChangelog) stringResource(R.string.hide_changelog) else stringResource(R.string.view_changelog), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (showChangelog) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(stringResource(R.string.changelog_content), fontSize = 10.sp, modifier = Modifier.padding(12.dp), lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("这是一个多功能闹钟应用，支持分组管理、WiFi同步、录音记录等功能", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("开发者: AtomGit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("版本 ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

// ════ 权限管理 ════
@Composable
private fun PermissionContent(
    context: android.content.Context,
    onRequestPermissions: () -> Unit
) {
    SettingsSectionCard("🔐 权限管理") {
        Log.d("AppSettings", "SDK_INT=${Build.VERSION.SDK_INT}, R=${Build.VERSION_CODES.R}")
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                Toast.makeText(context, "基本权限已获取", Toast.LENGTH_SHORT).show()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            } else Toast.makeText(context, "权限被拒绝: ${permissions.filter { !it.value }.keys.joinToString(", ")}", Toast.LENGTH_LONG).show()
        }
        Button(onClick = {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { list.add(Manifest.permission.POST_NOTIFICATIONS); list.add(Manifest.permission.READ_MEDIA_AUDIO) }
            else { list.add(Manifest.permission.READ_EXTERNAL_STORAGE); list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            launcher.launch(list.toTypedArray())
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("请求录音/通知权限", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            Log.d("AppSettings", "请求文件权限: SDK_INT=${Build.VERSION.SDK_INT}, R=${Build.VERSION_CODES.R}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val isManager = Environment.isExternalStorageManager(); Log.d("AppSettings", "isExternalStorageManager=$isManager")
                if (!isManager) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                else Toast.makeText(context, "文件访问权限已获取", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Android 10 不需要此权限，请使用上方按钮获取", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        ) { Text("请求文件访问权限", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

// ════ 语音合成(TTS)设置 ════
@Composable
private fun TtsSettingsContent(
    availableTtsEngines: List<EngineInfo>,
    selectedTtsEngine: String,
    onSetTtsEngine: (String) -> Unit,
    availableVoices: List<Voice>,
    selectedTtsVoice: String,
    onSetTtsVoice: (String) -> Unit,
    ttsPitch: Float,
    ttsRate: Float,
    onSetTtsPitch: (Float) -> Unit,
    onSetTtsRate: (Float) -> Unit,
    onTestTts: (Int) -> Unit,
    onScanTtsEngines: () -> Unit,
    debugLogs: List<String>,
    onCleanupUnusedCache: () -> Unit,
    onRebuildMissingCache: () -> Unit
) {
    val ctx = LocalContext.current
    var localPitch by remember(ttsPitch) { mutableStateOf(ttsPitch) }
    var localRate by remember(ttsRate) { mutableStateOf(ttsRate) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

    // ── TTS 引擎选择 ──
    SettingsSectionCard("TTS 引擎") {
        var engineExpanded by remember { mutableStateOf(false) }
        val currentEngineLabel = availableTtsEngines.find { it.name == selectedTtsEngine }?.label
            ?: if (selectedTtsEngine.isNotEmpty()) selectedTtsEngine else "系统默认"
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("引擎", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(48.dp))
            Box(modifier = Modifier.weight(1f).clickable { engineExpanded = true }) {
                Text(currentEngineLabel.toString(), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    DropdownMenuItem(text = { Text("系统默认") }, onClick = { onSetTtsEngine(""); engineExpanded = false })
                    availableTtsEngines.forEach { e ->
                        DropdownMenuItem(text = { Text("${e.label}（${e.name}）") }, onClick = { onSetTtsEngine(e.name); engineExpanded = false })
                    }
                }
            }
            IconButton(onClick = onScanTtsEngines, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp).clickable { engineExpanded = true })
        }
    }

    // ── 语音选择 ──
    SettingsSectionCard("语音") {
        var voiceExpanded by remember { mutableStateOf(false) }
        val currentVoiceName = availableVoices.find { it.name == selectedTtsVoice }?.let {
            "${it.name}（${it.locale}）"
        } ?: if (selectedTtsVoice.isNotEmpty()) selectedTtsVoice else "默认"
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("语音", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(48.dp))
            Box(modifier = Modifier.weight(1f).clickable { voiceExpanded = true }) {
                Text(currentVoiceName, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    DropdownMenuItem(text = { Text("默认") }, onClick = { onSetTtsVoice(""); voiceExpanded = false })
                    availableVoices.forEach { v ->
                        val label = "${v.name}（${v.locale}）"
                        DropdownMenuItem(text = { Text(label) }, onClick = { onSetTtsVoice(v.name); voiceExpanded = false })
                    }
                }
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp).clickable { voiceExpanded = true })
        }
    }

    // ── 语速与音调 ──
    SettingsSectionCard("语速与音调") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("语速", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(40.dp))
            Slider(value = localRate, onValueChange = { localRate = it; onSetTtsRate(it) }, valueRange = 0.1f..3.0f,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f))
            Text("${String.format("%.1f", localRate)}x", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("音调", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(40.dp))
            Slider(value = localPitch, onValueChange = { localPitch = it; onSetTtsPitch(it) }, valueRange = 0.1f..3.0f,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f))
            Text("${String.format("%.1f", localPitch)}x", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
        }
    }

    // ── 语音测试 ──
    SettingsSectionCard("语音测试") {
        Button(onClick = {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            onTestTts(hour)
            Toast.makeText(ctx, "正在测试：这是语音合成测试效果", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("测试：这是语音合成测试效果", fontSize = 15.sp)
        }
    }

    // ── 缓存管理 ──
    SettingsSectionCard("🗑 语音缓存管理") {
        Text("TTS 语音缓存文件存放在: ${ctx.filesDir}/tts_task_cache", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        // 清除未使用
        Button(
            onClick = onCleanupUnusedCache,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("清除未使用的缓存", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("删除所有未被打卡任务和闹钟引用的缓存文件。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(12.dp))

        // 重建缺失
        Button(
            onClick = onRebuildMissingCache,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("重建缺失的缓存", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("遍历所有打卡任务，补生成尚未缓存的语音文件，已有文件不受影响。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (debugLogs.isNotEmpty()) {
        SettingsSectionCard("调试日志") {
            debugLogs.takeLast(20).forEach { log ->
                Text(log, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
}

// ════ 辅助：卡片容器 ════
@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}
