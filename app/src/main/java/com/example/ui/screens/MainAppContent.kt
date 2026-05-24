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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.db.Alarm
import com.example.db.AlarmGroup
import com.example.db.HourlyChime
import com.example.ui.AlarmViewModel
import com.example.ui.dialogs.AddAlarmDialog
import com.example.ui.dialogs.AppSettingsDialog

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
    onDeleteAlarm: (Alarm) -> Unit,
    onDuplicateAlarm: (Alarm) -> Unit,
    onMoveAlarmToGroup: (Alarm, Long) -> Unit,
    onToggleChime: (HourlyChime, Boolean) -> Unit,
    onUpdateChimeDetails: (Boolean, Boolean) -> Unit,
    onToggleWifiSync: (Boolean) -> Unit,
    onLoadCustomRingtones: () -> Unit,
    onLoadLocalRecordings: () -> Unit,
    onAddGroup: (String) -> Unit,
    onAddAlarm: (groupId: Long, hour: Int, minute: Int, days: String, label: String, ringtone: String?, vibrate: Boolean) -> Unit,
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
    timerRemainingSeconds: Int,
    isTimerRunning: Boolean,
    timerHours: Int,
    timerMinutes: Int,
    timerSeconds: Int,
    onSetTimerHours: (Int) -> Unit,
    onSetTimerMinutes: (Int) -> Unit,
    onSetTimerSeconds: (Int) -> Unit,
    onStartTimer: (Int) -> Unit,
    onStopTimer: () -> Unit,
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
    discoveredDevices: List<Pair<String, String>> = emptyList(),
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    autoUpdateEnabled: Boolean = true,
    onSetAutoUpdateEnabled: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    updateInfo: AlarmViewModel.UpdateInfo? = null,
    onPerformUpdate: () -> Unit = {},
    downloadProgress: Float = -1f,
    onDeleteRingtone: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // 当前选中的 tab，0=Alarms, 1=Chimes, 2=WiFi Sync
    var currentTab by remember { mutableIntStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // 当进入 WiFi 同步页时开始自动发现，离开时停止
    LaunchedEffect(currentTab) {
        if (currentTab == 3) {
            onStartDiscovery()
        } else {
            onStopDiscovery()
        }
    }

    // 报时风格状态 (0 = TTS语音, 1 = 悦耳钟声)
    val chimePrefs = context.getSharedPreferences("chime_prefs", 0)
    var chimeStyle by remember { mutableIntStateOf(chimePrefs.getInt("chime_style", 0)) }

    // 实时秒表时间显示逻辑
    var wallClockTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        while (true) {
            wallClockTime = timeFormat.format(java.util.Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    // Dialog state controllers
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showAddAlarmDialog by remember { mutableStateOf(false) }
    var selectedAlarmGroupId by remember { mutableStateOf(-1L) }
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }

    // 当用户计划添加或编辑闹钟时，自动触发文件流重载扫描，高频同步手机录音
    LaunchedEffect(showAddAlarmDialog, editingAlarm) {
        if (showAddAlarmDialog || editingAlarm != null) {
            onLoadLocalRecordings()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        // 增强：高对比度数字表盘时间
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = wallClockTime,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF00), // 经典荧光绿，极其显眼
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (currentTab == 0) {
                        IconButton(onClick = { showAddGroupDialog = true }) {
                            Icon(Icons.Default.GroupAdd, contentDescription = "Add Group")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = "Hourly Chimes") },
                    label = { Text(stringResource(R.string.nav_chimes)) }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Timer, contentDescription = "Timer") },
                    label = { Text(stringResource(R.string.nav_timer)) }
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { 
                        currentTab = 3 
                        onLoadCustomRingtones()
                    },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = "WiFi Sync") },
                    label = { Text(stringResource(R.string.nav_sync)) }
                )
                NavigationBarItem(
                    selected = currentTab == 4,
                    onClick = { currentTab = 4 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "About") },
                    label = { Text(stringResource(R.string.nav_about)) }
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
                    onDeleteAlarm = onDeleteAlarm,
                    onAddAlarmClick = { groupId ->
                        selectedAlarmGroupId = groupId
                        showAddAlarmDialog = true
                    },
                    onDuplicateAlarm = onDuplicateAlarm,
                    onMoveAlarmToGroup = onMoveAlarmToGroup,
                    onEditAlarm = { alarm ->
                        editingAlarm = alarm
                    }
                )
                1 -> ChimesTab(
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
                2 -> TimerTab(
                    remainingSeconds = timerRemainingSeconds,
                    isRunning = isTimerRunning,
                    onStart = onStartTimer,
                    onStop = onStopTimer,
                    hours = timerHours,
                    minutes = timerMinutes,
                    seconds = timerSeconds,
                    onSetHours = onSetTimerHours,
                    onSetMinutes = onSetTimerMinutes,
                    onSetSeconds = onSetTimerSeconds
                )
                3 -> WifiSyncTab(
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
                    onClearSyncStatus = onClearSyncStatus,
                    appTheme = appTheme,
                    appLanguage = appLanguage,
                    onSetTheme = onSetTheme,
                    onSetLanguage = onSetLanguage,
                    discoveredDevices = discoveredDevices,
                    recordingPath = customRecordingPath,
                    onDeleteRingtone = onDeleteRingtone
                )
                4 -> AboutTab()
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
                .wrapContentHeight(),
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
            onConfirm = { hour, minute, days, label, ringtone, vibrate ->
                onAddAlarm(selectedAlarmGroupId, hour, minute, days, label, ringtone, vibrate)
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
            onConfirm = { hour, minute, days, label, ringtone, vibrate ->
                val updated = editingAlarm!!.copy(
                    hour = hour,
                    minute = minute,
                    daysOfWeek = days,
                    label = label,
                    ringtonePath = ringtone,
                    vibrate = vibrate
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
            autoUpdate = autoUpdateEnabled,
            onSetTheme = onSetTheme,
            onSetLanguage = onSetLanguage,
            onSetOffsetHours = onSetDuplicateOffsetHours,
            onSetOffsetMinutes = onSetDuplicateOffsetMinutes,
            onSetRecordingPath = onSetCustomRecordingPath,
            onSetAutoUpdate = onSetAutoUpdateEnabled,
            onCheckUpdate = onCheckUpdate,
            onDismiss = { showSettingsDialog = false }
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
