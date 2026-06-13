package com.example.ui.screens

import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.example.R
import com.example.cloud.*
import com.example.db.Alarm
import com.example.db.AlarmGroup
import com.example.db.HourlyChime
import com.example.ui.AlarmViewModel
import com.example.ui.dialogs.AddAlarmDialog
import com.example.ui.dialogs.AppSettingsDialog
import com.example.ui.screens.CheckInTab
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity
import com.example.ui.dialogs.RingtoneSelectionDialog
import com.example.ui.screens.CheckInTab
import com.example.ui.screens.CountdownTab
import com.example.cloud.CloudService
import com.example.ui.dialogs.AddCheckInGroupDialog
import com.example.ui.dialogs.CheckInTaskInput
import com.example.alarm.ChimeGenerator
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioTrack
import android.media.AudioFormat
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.Context
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    groups: List<AlarmGroup>,
    alarms: List<Alarm>,
    chimes: List<HourlyChime>,
    customRingtones: List<String>,
    systemRingtones: List<Pair<String, String>>,
    localRecordings: List<Pair<String, String>>,
    isWifiServerOn: Boolean,
    isRecording: Boolean,
    recordingDuration: Int,
    onToggleGroup: (AlarmGroup, Boolean) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDeleteGroup: (AlarmGroup) -> Unit,
    onUpdateGroup: (AlarmGroup) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onDuplicateAlarm: (Alarm) -> Unit,
    onMoveAlarmToGroup: (Alarm, Long) -> Unit,
    onToggleChime: (HourlyChime, Boolean) -> Unit,
    onUpdateChimeDetails: (Boolean, Boolean) -> Unit,
    onToggleWifiSync: (Boolean) -> Unit,
    onLoadCustomRingtones: () -> Unit,
    onLoadLocalRecordings: () -> Unit,
    onAddGroup: (String) -> Unit,
    onAddAlarm: (groupId: Long, hour: Int, minute: Int, days: String, label: String, ringtone: String?, vibrate: Boolean, ringtoneDurationSecs: Int) -> Unit,
    onUpdateAlarm: (Alarm) -> Unit,
    onImportAudio: (android.net.Uri, String) -> String?,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onTestTts: (Int) -> Unit,
    onSetTtsPitch: (Float) -> Unit,
    onSetTtsRate: (Float) -> Unit,
    onRefreshMonitor: () -> Unit,
    syncStatus: AlarmViewModel.SyncStatus,
    syncTargetIp: String,
    onSetSyncTargetIp: (String) -> Unit,
    onSyncFromRemote: (com.example.alarm.WifiSyncClient.ImportMode) -> Unit,
    onSelectiveSync: (Set<String>) -> Unit = {},
    onClearSyncStatus: () -> Unit,
    appTheme: Int,
    appLanguage: String,
    duplicateOffsetHours: Int,
    duplicateOffsetMinutes: Int,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetDuplicateOffsetHours: (Int) -> Unit,
    onSetDuplicateOffsetMinutes: (Int) -> Unit,
    customRecordingPath: String,
    onSetCustomRecordingPath: (String) -> Unit,
    dbDirectoryPath: String,
    onSetDatabaseDirectoryPath: (String) -> Unit,
    timerRemainingSeconds: Int,
    isTimerRunning: Boolean,
    isTimerRinging: Boolean,
    timerHours: Int,
    timerMinutes: Int,
    timerSeconds: Int,
    onSetTimerHours: (Int) -> Unit,
    onSetTimerMinutes: (Int) -> Unit,
    onSetTimerSeconds: (Int) -> Unit,
    onStartTimer: (Int) -> Unit,
    onStopTimer: () -> Unit,
    onDismissTimerRinging: () -> Unit,
    debugLogs: List<String>,
    onStartRecording: () -> Unit,
    onStopRecording: (String) -> String?,
    onCancelRecording: () -> Unit,
    availableTtsEngines: List<EngineInfo> = emptyList(),
    selectedTtsEngine: String = "",
    onSetTtsEngine: (String) -> Unit = {},
    availableVoices: List<Voice> = emptyList(),
    selectedTtsVoice: String = "",
    onSetTtsVoice: (String) -> Unit = {},
    onScanTtsEngines: () -> Unit = {},
    discoveredDevices: List<Pair<String, String>> = emptyList(),
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    autoUpdateEnabled: Boolean = true,
    onSetAutoUpdateEnabled: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    updateInfo: AlarmViewModel.UpdateInfo? = null,
    onPerformUpdate: () -> Unit = {},
    downloadProgress: Float = -1f,
    onDeleteRingtone: (String) -> Unit = {},
    // 打卡相关参数
    checkInGroups: List<CheckInGroupEntity> = emptyList(),
    checkInTasksMap: Map<Long, List<CheckInTaskEntity>> = emptyMap(),
    onAddCheckInGroup: (String, List<CheckInTaskInput>) -> Unit = { _, _ -> },
    onDeleteCheckInGroup: (CheckInGroupEntity) -> Unit = {},
    onUpdateCheckInGroup: (CheckInGroupEntity, List<CheckInTaskInput>) -> Unit = { _, _ -> },
    onToggleCheckInGroup: (CheckInGroupEntity, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onDuplicateCheckInGroup: (CheckInGroupEntity) -> Unit = {},
    onShareCheckInGroup: (CheckInGroupEntity) -> Unit = {},
    onImportCheckInGroup: () -> Unit = {},
    onConvertToCheckIn: (AlarmGroup) -> Unit = {},
    // 预警音配置聚合体
    warningSoundConfig: com.example.ui.AlarmViewModel.WarningSoundConfig = com.example.ui.AlarmViewModel.WarningSoundConfig(),
    onSetCountdownWarningSeconds: (Int) -> Unit = {},
    onSetCountdownWarningSoundType: (String) -> Unit = {},
    onSetCountdownWarningCustomPath: (String) -> Unit = {},
    onSetCountdownWarningTtsText: (String) -> Unit = {},
    onSetTimerFinishSoundType: (String) -> Unit = {},
    onSetTimerFinishCustomPath: (String) -> Unit = {},
    onSetTimerFinishTtsText: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // 当前选中的 tab，0=Alarms, 1=Chimes, 2=WiFi Sync
    var currentTab by remember { mutableIntStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // TTS 缓存管理进度
    var showTtsProgress by remember { mutableStateOf(false) }
    var ttsProgressText by remember { mutableStateOf("") }
    var ttsProgressCurrent by remember { mutableIntStateOf(0) }
    var ttsProgressTotal by remember { mutableIntStateOf(0) }

    // 当进入 WiFi 同步页时开始自动发现，离开时停止
    LaunchedEffect(currentTab) {
        if (currentTab == 4) {
            onStartDiscovery()
        } else {
            onStopDiscovery()
        }
    }

    // 报时风格状态 (0 = TTS语音, 1 = 悦耳钟声)
    val chimePrefs = context.getSharedPreferences("chime_prefs", 0)
    var chimeStyle by remember { mutableIntStateOf(chimePrefs.getInt("chime_style", 0)) }
    var ttsPitch by remember { mutableStateOf(1.0f) }
    var ttsRate by remember { mutableStateOf(1.0f) }

    // 实时秒表时间显示逻辑
    var wallClockTime by remember { mutableStateOf("") }
    var dateWeekStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val dateFormat = java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.getDefault())
        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        while (true) {
            val now = java.util.Date()
            wallClockTime = timeFormat.format(now)
            val cal = java.util.Calendar.getInstance().apply { time = now }
            dateWeekStr = "${dateFormat.format(now)} 星期${weekDays[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]}"
            kotlinx.coroutines.delay(1000)
        }
    }

    // Dialog state controllers
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showAddAlarmDialog by remember { mutableStateOf(false) }
    var selectedAlarmGroupId by remember { mutableStateOf(-1L) }
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }
    // 打卡对话框状态
    var showAddCheckInDialog by remember { mutableStateOf(false) }
    var editingCheckInGroup by remember { mutableStateOf<CheckInGroupEntity?>(null) }

    // 当用户计划添加或编辑闹钟时，自动触发文件流重载扫描，高频同步手机录音
    LaunchedEffect(showAddAlarmDialog, editingAlarm) {
        if (showAddAlarmDialog || editingAlarm != null) {
            onLoadLocalRecordings()
        }
    }

    // ═══ 全局预警音 ═══
    // 每秒检测最近闹钟，进入预警区时播放选中的音色（不限 Tab）
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val enabledGroupIds = remember(groups) { groups.filter { it.isEnabled }.map { it.id }.toSet() }
    val enabledAlarms = remember(alarms, tick, enabledGroupIds) {
        alarms.filter { it.isEnabled && it.groupId in enabledGroupIds }
    }
    val todayDate = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()) }
    val nearestSec = remember(enabledAlarms, tick) {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val todayWeekDay = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2; java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4; java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
            java.util.Calendar.SUNDAY -> 7; else -> 7
        }
        enabledAlarms.mapNotNull { alarm ->
            val days = alarm.daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            if (days.isNotEmpty() && todayWeekDay !in days) return@mapNotNull null
            val targetCal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                set(java.util.Calendar.MINUTE, alarm.minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            (targetCal.timeInMillis - now) / 1000
        }.filter { it > 0 }.minOrNull() ?: Long.MAX_VALUE
    }
    val warningSec = maxOf(warningSoundConfig.countdownWarningSeconds, 10)
    val isInWarningZone = nearestSec <= warningSec && nearestSec > 0
    var wasInWarning by remember { mutableStateOf(false) }
    var warningActiveTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var warningActiveTtsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var warningTickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var warningTickTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var warningRepeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun stopGlobalWarning() {
        ChimeGenerator.stopTickTock()
        try { warningTickTrack?.pause() } catch(_: Exception) {}
        try { warningTickTrack?.stop() } catch(_: Exception) {}
        try { warningTickTrack?.release() } catch(_: Exception) {}
        warningTickTrack = null
        warningTickJob?.cancel()
        warningTickJob = null
        warningRepeatJob?.cancel()
        warningRepeatJob = null
        try { warningActiveTts?.stop() } catch(_: Exception) {}
        try { warningActiveTts?.shutdown() } catch(_: Exception) {}
        warningActiveTts = null
        warningActiveTtsJob?.cancel()
        warningActiveTtsJob = null
    }

    LaunchedEffect(isInWarningZone, warningSoundConfig.countdownWarningSoundType, warningSoundConfig.countdownWarningCustomPath, warningSoundConfig.countdownWarningTtsText) {
        if (isInWarningZone) {
            stopGlobalWarning()
            // 唤醒屏幕
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "MainApp:Warning")
                wl.acquire(3000L)
            } catch(_: Exception) {}
            when (warningSoundConfig.countdownWarningSoundType) {
                "tick_tock" -> {
                    warningTickJob = scope.launch {
                        val tickData = ChimeGenerator.generateTickOnce(true)
                        val tockData = ChimeGenerator.generateTickOnce(false)
                        var isTick = true
                        while (true) {
                            kotlinx.coroutines.yield()
                            val data = if (isTick) tickData else tockData; isTick = !isTick
                            val track = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setBufferSizeInBytes(data.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
                            try {
                                track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                                warningTickTrack = track; track.play(); delay(1000L)
                            } finally {
                                if (warningTickTrack == track) warningTickTrack = null
                                try { track.pause() } catch(_: Exception) {}
                                try { track.stop() } catch(_: Exception) {}
                                try { track.release() } catch(_: Exception) {}
                            }
                        }
                    }
                }
                "chime_0", "chime_1", "chime_2", "chime_3" -> {
                    val pattern = warningSoundConfig.countdownWarningSoundType.last().digitToInt()
                    // 先播一次，等当前播完再等 3 秒再播下一次，避免重叠
                    warningRepeatJob = scope.launch {
                        while (true) {
                            // playChimePattern 内部会等播完才返回
                            ChimeGenerator.playChimePattern(pattern)
                            // 等前一次播完再隔 3 秒
                            delay(3000L)
                        }
                    }
                }
                "custom" -> {
                    if (warningSoundConfig.countdownWarningCustomPath.isNotBlank()) {
                        try {
                            val player = MediaPlayer().apply {
                                setDataSource(warningSoundConfig.countdownWarningCustomPath)
                                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                isLooping = true; prepare(); start()
                            }
                        } catch (e: Exception) { Log.e("MainApp", "自定义预警音播放失败", e) }
                    }
                }
                "tts" -> {
                    val text = warningSoundConfig.countdownWarningTtsText.ifBlank { "注意，闹钟即将响起" }
                    var ttsRef: TextToSpeech? = null
                    val tts = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            ttsRef?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warning")
                        }
                    })
                    ttsRef = tts
                    warningActiveTts = tts
                    warningActiveTtsJob = scope.launch {
                        while (true) {
                            delay(3000L)
                            // 检查是否还在朗读，等它读完
                            if (warningActiveTts?.isSpeaking == true) {
                                delay(1000L)
                            }
                            warningActiveTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "warning_rep")
                        }
                    }
                }
            }
        } else if (wasInWarning) {
            stopGlobalWarning()
        }
        wasInWarning = isInWarningZone
    }
    // 组件卸载时停止
    DisposableEffect(Unit) { onDispose { stopGlobalWarning() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateWeekStr,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF5350),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 黑底黄字数字时钟
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black,
                            border = BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = wallClockTime,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD700),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    if (currentTab == 0) {
                        IconButton(onClick = { showAddGroupDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Group")
                        }
                    } else if (currentTab == 5) {
                        IconButton(onClick = { showAddCheckInDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Check-in")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = "Alarms") },
                    label = { Text(stringResource(R.string.nav_alarms)) }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = "Countdown") },
                    label = { Text(stringResource(R.string.nav_countdown)) }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = "Hourly Chimes") },
                    label = { Text(stringResource(R.string.nav_chimes)) }
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.Timer, contentDescription = "Timer") },
                    label = { Text(stringResource(R.string.nav_timer)) }
                )
                NavigationBarItem(
                    selected = currentTab == 4,
                    onClick = { 
                        currentTab = 4 
                        onLoadCustomRingtones()
                    },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = "WiFi Sync") },
                    label = { Text(stringResource(R.string.nav_sync)) }
                )
                NavigationBarItem(
                    selected = currentTab == 5,
                    onClick = { currentTab = 5 },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Check-in") },
                    label = { Text(stringResource(R.string.nav_checkin)) }
                )
                NavigationBarItem(
                    selected = currentTab == 6,
                    onClick = { currentTab = 6 },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud") },
                    label = { Text(stringResource(R.string.nav_cloud)) }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                )
        ) {
            when (currentTab) {
                0 -> AlarmsTab(
                    groups = groups,
                    alarms = alarms,
                    onToggleGroup = onToggleGroup,
                    onToggleAlarm = onToggleAlarm,
                    onDeleteGroup = onDeleteGroup,
                    onUpdateGroup = onUpdateGroup,
                    onDeleteAlarm = onDeleteAlarm,
                    onAddAlarmClick = { groupId ->
                        selectedAlarmGroupId = groupId
                        showAddAlarmDialog = true
                    },
                    onDuplicateAlarm = onDuplicateAlarm,
                    onMoveAlarmToGroup = onMoveAlarmToGroup,
                    onEditAlarm = { alarm ->
                        editingAlarm = alarm
                    },
                    onConvertToCheckIn = { group ->
                        onConvertToCheckIn(group)
                        android.widget.Toast.makeText(context, "已转为打卡组", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> CountdownTab(
                    alarms = alarms,
                    groups = groups,
                    warningSeconds = warningSoundConfig.countdownWarningSeconds,
                    warningSoundType = warningSoundConfig.countdownWarningSoundType,
                    warningCustomPath = warningSoundConfig.countdownWarningCustomPath,
                    warningTtsText = warningSoundConfig.countdownWarningTtsText
                )
                2 -> ChimesTab(
                        chimes = chimes,
                        onToggleChime = onToggleChime,
                        onUpdateChimeDetails = onUpdateChimeDetails,
                        onTestTts = onTestTts,
                        onSetTtsPitch = onSetTtsPitch,
                        onSetTtsRate = onSetTtsRate,
                        debugLogs = debugLogs,
                        chimeStyle = chimeStyle,
                        onChimeStyleChange = { newStyle ->
                            chimeStyle = newStyle
                            chimePrefs.edit().putInt("chime_style", newStyle).apply()
                        },
                        availableTtsEngines = availableTtsEngines,
                        selectedTtsEngine = selectedTtsEngine,
                        onSetTtsEngine = onSetTtsEngine,
                        availableVoices = availableVoices,
                        selectedTtsVoice = selectedTtsVoice,
                        onSetTtsVoice = onSetTtsVoice
                    )
                3 -> TimerTab(
                    remainingSeconds = timerRemainingSeconds,
                    isRunning = isTimerRunning,
                    isRinging = isTimerRinging,
                    onStart = onStartTimer,
                    onStop = onStopTimer,
                    onDismissRinging = onDismissTimerRinging,
                    hours = timerHours,
                    minutes = timerMinutes,
                    seconds = timerSeconds,
                    onSetHours = onSetTimerHours,
                    onSetMinutes = onSetTimerMinutes,
                    onSetSeconds = onSetTimerSeconds,
                    warningSoundType = warningSoundConfig.timerFinishSoundType,
                    warningCustomPath = warningSoundConfig.timerFinishCustomPath,
                    warningTtsText = warningSoundConfig.timerFinishTtsText
                )
                4 -> WifiSyncTab(
                    isOn = isWifiServerOn,
                    customRingtones = customRingtones,
                    onToggle = onToggleWifiSync,
                    onExport = onExportConfig,
                    onImport = onImportConfig,
                    onRefreshMonitor = onRefreshMonitor,
                    syncStatus = syncStatus,
                    syncTargetIp = syncTargetIp,
                    onSetSyncTargetIp = onSetSyncTargetIp,
                    onSyncFromRemote = onSyncFromRemote,
                    onSelectiveSync = onSelectiveSync,
                    onClearSyncStatus = onClearSyncStatus,
                    appTheme = appTheme,
                    appLanguage = appLanguage,
                    onSetTheme = onSetTheme,
                    onSetLanguage = onSetLanguage,
                    discoveredDevices = discoveredDevices,
                    recordingPath = customRecordingPath,
                    onDeleteRingtone = onDeleteRingtone,
                    groups = groups,
                    checkInGroups = checkInGroups,
                    checkInTasksMap = checkInTasksMap,
                    cloudService = getService(LocalContext.current)
                )
                5 -> CheckInTab(
                    groups = checkInGroups,
                    tasksMap = checkInTasksMap,
                    onAddGroup = { showAddCheckInDialog = true },
                    onEditGroup = { group ->
                        editingCheckInGroup = group
                        showAddCheckInDialog = true
                    },
                    onDeleteGroup = onDeleteCheckInGroup,
                    onToggleGroup = onToggleCheckInGroup,
                    onDuplicateGroup = onDuplicateCheckInGroup,
                    onShareGroup = onShareCheckInGroup,
                    onImportGroup = onImportCheckInGroup,
                    onCloudShareGroup = onShareCheckInGroup,
                    offsetHours = duplicateOffsetHours,
                    offsetMinutes = duplicateOffsetMinutes
                )
                6 -> {
                    val ctx = LocalContext.current
                    val cloudVm: AlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val cShareCode by cloudVm.cloudShareCode.collectAsState()
                    val cShareLoading by cloudVm.cloudShareLoading.collectAsState()
                    val cImportResult by cloudVm.cloudImportResult.collectAsState()

                    CloudShareTab(
                        cloudService = getService(ctx),
                        groups = groups,
                        checkInGroups = checkInGroups,
                        checkInTasksMap = checkInTasksMap,
                        cloudShareCode = cShareCode,
                        cloudShareLoading = cShareLoading,
                        cloudImportResult = cImportResult,
                        onShareAlarmGroup = { group ->
                            scope.launch { cloudVm.shareAlarmGroupToCloud(group) }
                        },
                        onShareCheckInGroup = { group ->
                            scope.launch { cloudVm.shareCheckInGroupToCloud(group, checkInTasksMap[group.id] ?: emptyList()) }
                        },
                        onImportFromCloud = { code ->
                            scope.launch {
                                // 根据分享码尝试导入闹钟组，失败则尝试打卡组
                                if (!cloudVm.importAlarmGroupFromCloud(code)) {
                                    cloudVm.importCheckInGroupFromCloud(code)
                                }
                            }
                        },
                        onSelectService = { ctx.selectService(it) },
                        onSetSupabaseCredentials = { url, key -> ctx.setSupabaseCredentials(url, key) },
                        onSetFirebaseCredentials = { projectId, apiKey -> ctx.setFirebaseCredentials(projectId, apiKey) },
                        onClearCloudShareCode = { cloudVm.clearCloudShareCode() },
                        onClearCloudImportResult = { cloudVm.clearCloudImportResult() },
                        onShowSnackbar = { msg -> /* snackbar not wired, add later */ },
                        onNavigateToGroup = {}
                    )
                }
            }
        }
    }

    // 新建分组对话框的弹窗展示，优化对话框宽度避免边缘过大留空
    if (showAddGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false },
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.create_group_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name_label), color = Color(0xFF8E9099)) },
                    placeholder = { Text(stringResource(R.string.group_name_placeholder), color = Color(0xFF6B6E76)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            onAddGroup(groupName)
                            showAddGroupDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D3269),
                        contentColor = Color(0xFFADC6FF)
                    )
                ) {
                    Text(stringResource(R.string.create), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddGroupDialog = false }) {
                    Text(stringResource(R.string.cancel), color = Color(0xFF8E9099), fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Modal Add Alarm Dialog — 用于给指定的分组新建闹钟
    if (showAddAlarmDialog && selectedAlarmGroupId != -1L) {
        AddAlarmDialog(
            customRingtones = customRingtones,
            systemRingtones = systemRingtones,
            localRecordings = localRecordings,
            customRecordingPath = customRecordingPath,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onDismiss = { showAddAlarmDialog = false },
            onConfirm = { hour, minute, days, label, ringtone, vibrate, durationSecs ->
                onAddAlarm(selectedAlarmGroupId, hour, minute, days, label, ringtone, vibrate, durationSecs)
                showAddAlarmDialog = false
            },
            onImportAudio = onImportAudio
        )
    }

    // Modal Edit Alarm Dialog — 用于修改已有闹钟的属性与保存设置
    if (editingAlarm != null) {
        AddAlarmDialog(
            editingAlarm = editingAlarm,
            customRingtones = customRingtones,
            systemRingtones = systemRingtones,
            localRecordings = localRecordings,
            customRecordingPath = customRecordingPath,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onDismiss = { editingAlarm = null },
            onConfirm = { hour, minute, days, label, ringtone, vibrate, durationSecs ->
                val updated = editingAlarm!!.copy(
                    hour = hour,
                    minute = minute,
                    daysOfWeek = days,
                    label = label,
                    ringtonePath = ringtone,
                    vibrate = vibrate,
                    ringtoneDurationSecs = durationSecs
                )
                onUpdateAlarm(updated)
                editingAlarm = null
            },
            onImportAudio = onImportAudio
        )
    }

    if (showSettingsDialog) {
        AppSettingsDialog(
            theme = appTheme,
            lang = appLanguage,
            offsetHours = duplicateOffsetHours,
            offsetMinutes = duplicateOffsetMinutes,
            recordingPath = customRecordingPath,
            dbDirectoryPath = dbDirectoryPath,
            autoUpdate = autoUpdateEnabled,
            onSetTheme = onSetTheme,
            onSetLanguage = onSetLanguage,
            onSetOffsetHours = onSetDuplicateOffsetHours,
            onSetOffsetMinutes = onSetDuplicateOffsetMinutes,
            onSetRecordingPath = onSetCustomRecordingPath,
            onSetDatabaseDirectoryPath = onSetDatabaseDirectoryPath,
            onSetAutoUpdate = onSetAutoUpdateEnabled,
            onCheckUpdate = onCheckUpdate,
            onDismiss = { showSettingsDialog = false },
            availableTtsEngines = availableTtsEngines,
            selectedTtsEngine = selectedTtsEngine,
            onSetTtsEngine = onSetTtsEngine,
            availableVoices = availableVoices,
            selectedTtsVoice = selectedTtsVoice,
            onSetTtsVoice = onSetTtsVoice,
            ttsPitch = ttsPitch,
            ttsRate = ttsRate,
            onSetTtsPitch = { onSetTtsPitch(it); ttsPitch = it },
            onSetTtsRate = { onSetTtsRate(it); ttsRate = it },
            onTestTts = onTestTts,
            onScanTtsEngines = onScanTtsEngines,
            debugLogs = debugLogs,
            onCleanupUnusedCache = {
                ttsProgressText = "正在扫描缓存文件..."
                ttsProgressCurrent = 0
                ttsProgressTotal = 100
                showTtsProgress = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val usedTexts = checkInTasksMap.values.flatten()
                        .filter { it.useTts }
                        .map { it.name }
                        .toSet()
                    val (deleted, freedBytes) = com.example.alarm.TtsTaskPlayer.cleanupUnused(context, usedTexts)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showTtsProgress = false
                        if (deleted > 0) {
                            val mb = freedBytes / (1024 * 1024)
                            val kb = (freedBytes % (1024 * 1024)) / 1024
                            android.widget.Toast.makeText(context,
                                "已清除 $deleted 个文件（${mb}MB ${kb}KB）",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            android.widget.Toast.makeText(context,
                                "没有需要清除的缓存",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onRebuildMissingCache = {
                ttsProgressText = "正在统计需要生成的语音..."
                ttsProgressCurrent = 0
                showTtsProgress = true
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val allTexts = checkInTasksMap.values.flatten()
                        .filter { it.useTts }
                        .map { it.name }
                        .distinct()
                    val toGenerate = allTexts.filter { text ->
                        // 只生成缺失的
                        val f = com.example.alarm.TtsTaskPlayer.cacheFile(context, text)
                        !f.exists() || f.length() == 0L
                    }
                    ttsProgressTotal = toGenerate.size
                    var count = 0
                    for (text in toGenerate) {
                        val idx = count
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            ttsProgressText = "语音合成中"
                            ttsProgressCurrent = idx + 1
                        }
                        com.example.alarm.TtsTaskPlayer.generateSync(context, text)
                        count++
                    }
                    // 统计总容量
                    val dir = com.example.alarm.TtsTaskPlayer.getCacheDir(context)
                    var totalBytes = 0L
                    dir.listFiles()?.forEach { if (it.isFile) totalBytes += it.length() }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        showTtsProgress = false
                        val mb = totalBytes / (1024 * 1024)
                        val kb = (totalBytes % (1024 * 1024)) / 1024
                        android.widget.Toast.makeText(context,
                            "已生成 $count 个文件，缓存总容量 ${mb}MB ${kb}KB（${allTexts.size} 个文本）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            // 预警音配置
            countdownWarningSeconds = warningSoundConfig.countdownWarningSeconds,
            currentSoundType = warningSoundConfig.countdownWarningSoundType,
            currentCustomPath = warningSoundConfig.countdownWarningCustomPath,
            currentTtsText = warningSoundConfig.countdownWarningTtsText,
            onSetCountdownWarningSeconds = onSetCountdownWarningSeconds,
            onSetCountdownWarningSoundType = onSetCountdownWarningSoundType,
            onSetCustomPath = onSetCountdownWarningCustomPath,
            onSetTtsText = onSetCountdownWarningTtsText,
            timerFinishSoundType = warningSoundConfig.timerFinishSoundType,
            timerFinishCustomPath = warningSoundConfig.timerFinishCustomPath,
            timerFinishTtsText = warningSoundConfig.timerFinishTtsText,
            onSetTimerFinishSoundType = onSetTimerFinishSoundType,
            onSetTimerFinishCustomPath = onSetTimerFinishCustomPath,
            onSetTimerFinishTtsText = onSetTimerFinishTtsText
        )
    }

    // ── TTS 缓存管理进度对话框 ──
    if (showTtsProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("语音缓存管理", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (ttsProgressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (ttsProgressCurrent.toFloat() / ttsProgressTotal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(ttsProgressText,
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("$ttsProgressCurrent / $ttsProgressTotal",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(ttsProgressText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // ── 打卡对话框 ──
    if (showAddCheckInDialog) {
        val editingGroup = editingCheckInGroup
        val editingTasks = if (editingGroup != null) checkInTasksMap[editingGroup.id] ?: emptyList() else emptyList()
        AddCheckInGroupDialog(
            existingGroup = editingGroup,
            existingTasks = editingTasks,
            customRingtones = customRingtones,
            systemRingtones = systemRingtones,
            localRecordings = localRecordings,
            customRecordingPath = customRecordingPath,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onImportAudio = onImportAudio,
            onDismiss = {
                showAddCheckInDialog = false
                editingCheckInGroup = null
            },
            offsetHours = duplicateOffsetHours,
            offsetMinutes = duplicateOffsetMinutes,
            onSetOffsetHours = onSetDuplicateOffsetHours,
            onSetOffsetMinutes = onSetDuplicateOffsetMinutes,
            onConfirm = { name, tasks ->
                if (editingGroup != null) {
                    onUpdateCheckInGroup(editingGroup, tasks)
                } else {
                    onAddCheckInGroup(name, tasks)
                }
                showAddCheckInDialog = false
                editingCheckInGroup = null
            }
        )
    }

    // 发现新版本对话框
    if (updateInfo != null && downloadProgress == -1f) {
        AlertDialog(
            onDismissRequest = { /* 静默 */ },
            title = { Text("发现新版本 ${updateInfo.tagName}") },
            text = { Text(updateInfo.body) },
            confirmButton = {
                Button(onClick = onPerformUpdate) {
                    Text("立即自动更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { /* 同上 */ }) {
                    Text("以后再说")
                }
            }
        )
    }

    // 下载进度显示
    if (downloadProgress >= 0f) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在自动更新...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                    Text("${(downloadProgress * 100).toInt()}%")
                }
            },
            confirmButton = {}
        )
    }
}
