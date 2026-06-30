package com.ccsoft.alarm.ui.screens

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
import com.ccsoft.alarm.R
import com.ccsoft.alarm.cloud.*
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmGroup
import com.ccsoft.alarm.db.HourlyChime
import com.ccsoft.alarm.util.PreferencesManager
import com.ccsoft.alarm.ui.AlarmViewModel
import com.ccsoft.alarm.ui.dialogs.AddAlarmDialog
import com.ccsoft.alarm.ui.dialogs.AppSettingsDialog
import com.ccsoft.alarm.ui.screens.CheckInTab
import com.ccsoft.alarm.db.CheckInGroupEntity
import com.ccsoft.alarm.db.CheckInTaskEntity
import com.ccsoft.alarm.ui.dialogs.RingtoneSelectionDialog
import com.ccsoft.alarm.ui.screens.CountdownTab
import com.ccsoft.alarm.cloud.CloudService
import com.ccsoft.alarm.ui.dialogs.AddCheckInGroupDialog
import com.ccsoft.alarm.ui.dialogs.CheckInTaskInput
import com.ccsoft.alarm.alarm.ChimeGenerator
import android.widget.Toast
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioTrack
import android.media.AudioFormat
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import java.io.File

/**
 * 主界面入口。
 * 修复：移除过长的参数列表，直接在内部观察 ViewModel 状态，解决 Dalvik 寄存器溢出导致的闪退。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onImportCheckInGroup: () -> Unit,
    onShareCheckInGroup: (CheckInGroupEntity) -> Unit
) {
    val context = LocalContext.current
    val viewModel: AlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    
    // 观察数据状态
    val groups by viewModel.groups.collectAsState()
    val alarms by viewModel.alarms.collectAsState()
    val chimes by viewModel.chimes.collectAsState()
    val checkInGroups by viewModel.checkInGroups.collectAsState()
    val checkInTasksMap by viewModel.checkInTasksMap.collectAsState()
    val customRingtones by viewModel.customRingtones.collectAsState()
    val systemRingtones by viewModel.systemRingtones.collectAsState()
    val localRecordings by viewModel.localRecordings.collectAsState()
    val isWifiServerOn by viewModel.isWifiServerOn.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncTargetIp by viewModel.syncTargetIp.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()
    val customRecordingPath by viewModel.customRecordingPath.collectAsState()
    val dbDirectoryPath by viewModel.dbDirectoryPath.collectAsState()
    val timerRemainingSeconds by viewModel.timerRemainingSeconds.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val isTimerRinging by viewModel.isTimerRinging.collectAsState()
    val timerHours by viewModel.timerHours.collectAsState()
    val timerMinutes by viewModel.timerMinutes.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val duplicateOffsetHours by viewModel.duplicateOffsetHours.collectAsState()
    val duplicateOffsetMinutes by viewModel.duplicateOffsetMinutes.collectAsState()
    val availableTtsEngines by viewModel.availableTtsEngines.collectAsState()
    val selectedTtsEngine by viewModel.selectedTtsEngine.collectAsState()
    val availableVoices by viewModel.availableVoices.collectAsState()
    val selectedTtsVoice by viewModel.selectedTtsVoiceName.collectAsState()
    val ttsFormat by viewModel.ttsFormat.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsState()
    val hourlyChimeMasterEnabled by viewModel.hourlyChimeMasterEnabled.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    val isFloatingEnabled by viewModel.isFloatingTimerEnabled.collectAsState()
    val isStatusBarClockEnabled by viewModel.isStatusBarClockEnabled.collectAsState()
    val isTopBarClockEnabled by viewModel.isTopBarClockEnabled.collectAsState()
    val topBarClockColor by viewModel.topBarClockColor.collectAsState()
    val topBarClockBgColor by viewModel.topBarClockBgColor.collectAsState()
    
    val dpadTarget by viewModel.dpadTarget.collectAsState()
    val sbTextColor by viewModel.sbTextColor.collectAsState()
    val sbBgColor by viewModel.sbBgColor.collectAsState()
    val floatTextColor by viewModel.floatTextColor.collectAsState()
    val floatBgColor by viewModel.floatBgColor.collectAsState()
    val widgetTextColor by viewModel.widgetTextColor.collectAsState()
    val widgetBgColor by viewModel.widgetBgColor.collectAsState()
    val naWidgetTimeColor by viewModel.naWidgetTimeColor.collectAsState()
    val naWidgetCountdownColor by viewModel.naWidgetCountdownColor.collectAsState()
    val naWidgetLabelColor by viewModel.naWidgetLabelColor.collectAsState()
    
    val sbFontSize by viewModel.sbFontSize.collectAsState()
    val floatFontSize by viewModel.floatFontSize.collectAsState()
    val naWidgetTimeSize by viewModel.naWidgetTimeSize.collectAsState()
    val naWidgetCountdownSize by viewModel.naWidgetCountdownSize.collectAsState()
    val naWidgetLabelSize by viewModel.naWidgetLabelSize.collectAsState()
    
    val permissionList by viewModel.permissionList.collectAsState()

    val isServiceStatusMonitorEnabled by viewModel.isServiceStatusMonitorEnabled.collectAsState()

    val countdownWarningSeconds by viewModel.countdownWarningSeconds.collectAsState()
    val warningSoundType by viewModel.countdownWarningSoundType.collectAsState()
    val warningCustomPath by viewModel.countdownWarningCustomPath.collectAsState()
    val warningTtsText by viewModel.countdownWarningTtsText.collectAsState()
    val timerSoundType by viewModel.timerFinishSoundType.collectAsState()
    val timerCustomPath by viewModel.timerFinishCustomPath.collectAsState()
    val timerTtsText by viewModel.timerFinishTtsText.collectAsState()

    val warningSoundConfig = remember(
        countdownWarningSeconds, warningSoundType, warningCustomPath, warningTtsText,
        timerSoundType, timerCustomPath, timerTtsText
    ) {
        AlarmViewModel.WarningSoundConfig(
            countdownWarningSeconds = countdownWarningSeconds,
            countdownWarningSoundType = warningSoundType,
            countdownWarningCustomPath = warningCustomPath,
            countdownWarningTtsText = warningTtsText,
            timerFinishSoundType = timerSoundType,
            timerFinishCustomPath = timerCustomPath,
            timerFinishTtsText = timerTtsText
        )
    }

    // 监控悬浮窗状态开关，自动启停服务
    LaunchedEffect(isFloatingEnabled) {
        val intent = Intent(context, com.ccsoft.alarm.service.FloatingTimerService::class.java)
        if (isFloatingEnabled) {
            if (android.provider.Settings.canDrawOverlays(context)) {
                context.startService(intent)
            } else {
                // 没权限，异步复位开关避免重组循环
                launch { viewModel.setFloatingTimerEnabled(false) }
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            }
        } else {
            context.stopService(intent)
        }
    }

    // 监控状态栏固定秒表开关
    LaunchedEffect(isStatusBarClockEnabled) {
        val intent = Intent(context, com.ccsoft.alarm.service.StatusBarClockService::class.java)
        if (isStatusBarClockEnabled) {
            if (android.provider.Settings.canDrawOverlays(context)) {
                context.startService(intent)
            } else {
                // 如果没有权限，重置开关并提示用户
                viewModel.setStatusBarClockEnabled(false)
                Toast.makeText(context, "请先授予悬浮窗权限才能开启状态栏秒表", Toast.LENGTH_LONG).show()
            }
        } else {
            context.stopService(intent)
        }
    }

    // ════════════ 辅助状态 ════════════
    var currentTab by remember { mutableIntStateOf(0) }
    
    // 监听外部跳转 Tab 请求
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    LaunchedEffect(activity?.intent) {
        val target = activity?.intent?.getIntExtra("TARGET_TAB", -1)
        if (target != null && target >= 0) {
            currentTab = target
            // 清除已处理的 Extra，防止旋转或重组时反复跳转
            activity.intent.removeExtra("TARGET_TAB")
        }
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // TTS 进度对话框
    var showTtsProgress by remember { mutableStateOf(false) }
    var ttsProgressText by remember { mutableStateOf("") }
    var ttsProgressCurrent by remember { mutableIntStateOf(0) }
    var ttsProgressTotal by remember { mutableIntStateOf(0) }

    // 报时风格 (0=TTS, 1=铃声) - 使用 PreferencesManager
    val prefsManager = remember { PreferencesManager(context) }
    var chimeStyle by remember { mutableIntStateOf(prefsManager.getChimeStyle()) }
    var ttsPitch by remember { mutableStateOf(1.0f) }
    var ttsRate by remember { mutableStateOf(1.0f) }

    // 顶部栏时间显示
    var wallClockTime by remember { mutableStateOf("") }
    var dateWeekStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val dateFormat = java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.getDefault())
        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        while (true) {
            val nowMillis = System.currentTimeMillis()
            val now = java.util.Date(nowMillis)
            wallClockTime = timeFormat.format(now)
            val cal = java.util.Calendar.getInstance().apply { time = now }
            dateWeekStr = "${dateFormat.format(now)} 星期${weekDays[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]}"
            
            // 核心对齐：计算距离下一整秒还有多少毫秒，精准同步
            val delayMs = 1000L - (nowMillis % 1000)
            delay(delayMs)
        }
    }

    // 当进入 WiFi 同步页时开始自动发现，离开时停止
    LaunchedEffect(currentTab) {
        if (currentTab == 4) {
            viewModel.startDiscovery()
        } else {
            viewModel.stopDiscovery()
        }
    }

    // 对话框控制器
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var showAddAlarmDialog by remember { mutableStateOf(false) }
    var selectedAlarmGroupId by remember { mutableStateOf(-1L) }
    var editingAlarm by remember { mutableStateOf<Alarm?>(null) }
    var showAddCheckInDialog by remember { mutableStateOf(false) }
    var editingCheckInGroup by remember { mutableStateOf<CheckInGroupEntity?>(null) }

    LaunchedEffect(showAddAlarmDialog, editingAlarm) {
        if (showAddAlarmDialog || editingAlarm != null) {
            viewModel.loadLocalRecordings()
        }
    }

    // ═══ 全局预警音检测 ═══
    // 注意：预警音逻辑已迁移到 AlarmGuardService（独立进程），主进程不再负责播放预警音
    // 这样可以确保在划掉最近任务后，预警音还能继续播放
    // 以下代码仅用于顶部栏闪烁效果，不播放声音
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(500L) // 改为 500ms 刷新一次以支持闪烁
        }
    }
    val enabledGroupIds = remember(groups) { groups.filter { it.isEnabled }.map { it.id }.toSet() }
    val enabledAlarms = remember(alarms, tick, enabledGroupIds) {
        alarms.filter { it.isEnabled && it.groupId in enabledGroupIds }
    }
    val nearestSec = remember(enabledAlarms, tick) {
        val now = System.currentTimeMillis()
        enabledAlarms.map { alarm ->
            val nextTimeMillis = com.ccsoft.alarm.alarm.AlarmScheduler.calculateNextAlarmTime(alarm)
            (nextTimeMillis - now) / 1000
        }.filter { it > 0 }.minOrNull() ?: Long.MAX_VALUE
    }
    val warningSec = maxOf(warningSoundConfig.countdownWarningSeconds, 10)
    val isInWarningZone = nearestSec <= warningSec && nearestSec > 0

    Scaffold(
        topBar = {
            val isFlashOn = (tick / 500) % 2 == 0L
            val topBarColor = if (isInWarningZone && isFlashOn) Color.Red else Color.Black
            val topBarBorder = if (isInWarningZone && isFlashOn) Color.White else Color(0xFFFFD700).copy(alpha = 0.5f)

            TopAppBar(
                title = {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = dateWeekStr, fontWeight = FontWeight.Bold, color = if (isInWarningZone && isFlashOn) Color.White else Color(0xFFFF0000), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (isTopBarClockEnabled) {
                            Surface(shape = RoundedCornerShape(6.dp), color = if (isInWarningZone && isFlashOn) topBarColor else Color(topBarClockBgColor), border = BorderStroke(1.dp, topBarBorder)) {
                                Text(text = wallClockTime, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (isInWarningZone && isFlashOn) Color.White else Color(topBarClockColor), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), maxLines = 1)
                            }
                        }
                    }
                },
                actions = {
                    // 全屏显示快捷入口
                    IconButton(onClick = {
                        val intent = Intent(context, com.ccsoft.alarm.ui.screens.FullScreenAlarmActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }) { Icon(Icons.Default.Fullscreen, "全屏显示") }
                    if (currentTab == 0) IconButton(onClick = { showAddGroupDialog = true }) { Icon(Icons.Default.Add, "Add Group") }
                    else if (currentTab == 5) IconButton(onClick = { showAddCheckInDialog = true }) { Icon(Icons.Default.Add, "Add Check-in") }
                    IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                val navItems = listOf(
                    Triple(0, Icons.Default.Alarm, R.string.nav_alarms),
                    Triple(1, Icons.Default.NotificationsActive, R.string.nav_countdown),
                    Triple(2, Icons.Default.NotificationsActive, R.string.nav_chimes),
                    Triple(3, Icons.Default.Timer, R.string.nav_timer),
                    Triple(4, Icons.Default.Wifi, R.string.nav_sync),
                    Triple(5, Icons.Default.CheckCircle, R.string.nav_checkin),
                    Triple(6, Icons.Default.Cloud, R.string.nav_cloud)
                )
                navItems.forEach { (index, icon, labelRes) ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { 
                            currentTab = index 
                            if (index == 4) viewModel.loadCustomRingtones()
                        },
                        icon = { Icon(icon, null) },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Brush.verticalGradient(colors = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))))) {
            when (currentTab) {
                0 -> AlarmsTab(
                    groups = groups, alarms = alarms,
                    onToggleGroup = viewModel::toggleGroup,
                    onToggleAlarm = viewModel::toggleAlarm,
                    onDeleteGroup = viewModel::deleteGroup,
                    onUpdateGroup = viewModel::updateGroup,
                    onDeleteAlarm = viewModel::deleteAlarm,
                    onAddAlarmClick = { selectedAlarmGroupId = it; showAddAlarmDialog = true },
                    onDuplicateAlarm = viewModel::duplicateAlarm,
                    onMoveAlarmToGroup = viewModel::moveAlarmToGroup,
                    onEditAlarm = { editingAlarm = it },
                    onConvertToCheckIn = { group ->
                        val groupAlarms = alarms.filter { it.groupId == group.id }
                        val tasks = groupAlarms.map { CheckInTaskInput(name = it.label.ifBlank { group.name }, hour = it.hour.toString(), minute = it.minute.toString()) }
                        viewModel.addCheckInGroup(group.name, tasks)
                        Toast.makeText(context, "已转为打卡组", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> CountdownTab(alarms = alarms, groups = groups, warningSeconds = warningSec, warningSoundType = warningSoundConfig.countdownWarningSoundType, warningCustomPath = warningSoundConfig.countdownWarningCustomPath, warningTtsText = warningSoundConfig.countdownWarningTtsText)
                2 -> ChimesTab(
                    chimes = chimes, onToggleChime = viewModel::toggleHourlyChime, onUpdateChimeDetails = viewModel::updateChimeDetails,
                    onTestTts = viewModel::testTts, onSetTtsPitch = viewModel::setTtsPitch, onSetTtsRate = viewModel::setTtsRate,
                    onTestCacheFiles = { com.ccsoft.alarm.alarm.ChimeAudioPreloader.rebuildCache(context) },
                    debugLogs = debugLogs, chimeStyle = chimeStyle, onChimeStyleChange = { chimeStyle = it; prefsManager.setChimeStyle(it) },
                    availableTtsEngines = availableTtsEngines, selectedTtsEngine = selectedTtsEngine, onSetTtsEngine = viewModel::setTtsEngine,
                    availableVoices = availableVoices, selectedTtsVoice = selectedTtsVoice, onSetTtsVoice = viewModel::setTtsVoice
                )
                3 -> TimerTab(
                    remainingSeconds = timerRemainingSeconds, isRunning = isTimerRunning, isRinging = isTimerRinging,
                    onStart = viewModel::startTimer, onStop = viewModel::stopTimer, onDismissRinging = viewModel::dismissTimerRinging,
                    hours = timerHours, minutes = timerMinutes, seconds = timerSeconds,
                    onSetHours = viewModel::setTimerHours, onSetMinutes = viewModel::setTimerMinutes, onSetSeconds = viewModel::setTimerSeconds,
                    warningSoundType = warningSoundConfig.timerFinishSoundType, warningCustomPath = warningSoundConfig.timerFinishCustomPath, warningTtsText = warningSoundConfig.timerFinishTtsText
                )
                4 -> WifiSyncTab(
                    isOn = isWifiServerOn, customRingtones = customRingtones, onToggle = { viewModel.toggleWifiSync(context, it) },
                    onExport = onExportConfig, onImport = onImportConfig, onRefreshMonitor = viewModel::refreshBackgroundMonitor,
                    syncStatus = syncStatus, syncTargetIp = syncTargetIp, onSetSyncTargetIp = viewModel::setSyncTargetIp,
                    onSyncFromRemote = { mode -> 
                        Log.i("SyncDebug", "UI: Triggering syncFromRemote mode=$mode")
                        viewModel.syncFromRemote(context, mode) 
                    },
                    onSelectiveSync = { groups -> 
                        Log.i("SyncDebug", "UI: Triggering selective sync count=${groups.size}")
                        viewModel.syncFromRemote(context, com.ccsoft.alarm.alarm.WifiSyncClient.ImportMode.SELECTIVE, groups) 
                    },
                    onClearSyncStatus = viewModel::clearSyncStatus,
                    appTheme = appTheme, appLanguage = appLanguage, onSetTheme = viewModel::setTheme, onSetLanguage = viewModel::setLanguage,
                    discoveredDevices = discoveredDevices, recordingPath = customRecordingPath, onDeleteRingtone = viewModel::deleteRingtone,
                    groups = groups, checkInGroups = checkInGroups, checkInTasksMap = checkInTasksMap, cloudService = viewModel.cloudService,
                    viewModel = viewModel
                )
                5 -> CheckInTab(
                    groups = checkInGroups, tasksMap = checkInTasksMap,
                    onAddGroup = { showAddCheckInDialog = true }, onEditGroup = { editingCheckInGroup = it; showAddCheckInDialog = true },
                    onDeleteGroup = viewModel::deleteCheckInGroup, onToggleGroup = viewModel::toggleCheckInGroup,
                    onDuplicateGroup = viewModel::duplicateCheckInGroup, onShareGroup = { onShareCheckInGroup(it) },
                    onImportGroup = onImportCheckInGroup, onCloudShareGroup = { onShareCheckInGroup(it) },
                    offsetHours = duplicateOffsetHours, offsetMinutes = duplicateOffsetMinutes
                )
                6 -> {
                    val cShareCode by viewModel.cloudShareCode.collectAsState()
                    val cShareLoading by viewModel.cloudShareLoading.collectAsState()
                    val cImportResult by viewModel.cloudImportResult.collectAsState()
                    CloudShareTab(
                        cloudService = viewModel.cloudService, groups = groups, checkInGroups = checkInGroups, checkInTasksMap = checkInTasksMap,
                        cloudShareCode = cShareCode, cloudShareLoading = cShareLoading, cloudImportResult = cImportResult,
                        onShareAlarmGroup = { scope.launch { viewModel.shareAlarmGroupToCloud(it) } },
                        onShareCheckInGroup = { scope.launch { viewModel.shareCheckInGroupToCloud(it, checkInTasksMap[it.id] ?: emptyList()) } },
                        onImportFromCloud = { scope.launch { if (!viewModel.importAlarmGroupFromCloud(it)) viewModel.importCheckInGroupFromCloud(it) } },
                        onSelectService = viewModel::setCloudService,
                        onSetSupabaseCredentials = viewModel::setSupabaseCredentials,
                        onSetFirebaseCredentials = viewModel::setFirebaseCredentials,
                        onClearCloudShareCode = viewModel::clearCloudShareCode, onClearCloudImportResult = viewModel::clearCloudImportResult,
                        onShowSnackbar = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }, onNavigateToGroup = {}
                    )
                }
            }
        }
    }

    // ── 对话框逻辑 ──
    if (showAddGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false },
            modifier = Modifier.fillMaxWidth(0.92f).wrapContentHeight().imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.create_group_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = groupName, onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name_label)) },
                    placeholder = { Text(stringResource(R.string.group_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = { if (groupName.isNotBlank()) { viewModel.addGroup(groupName); showAddGroupDialog = false } }) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = { TextButton(onClick = { showAddGroupDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showAddAlarmDialog && selectedAlarmGroupId != -1L) {
        AddAlarmDialog(
            customRingtones = customRingtones, systemRingtones = systemRingtones, localRecordings = localRecordings,
            customRecordingPath = customRecordingPath, isRecording = isRecording, recordingDuration = recordingDuration,
            onStartRecording = viewModel::startRecording, onStopRecording = viewModel::stopRecording, onCancelRecording = viewModel::cancelRecording,
            onDismiss = { showAddAlarmDialog = false },
            onConfirm = { h, m, d, l, r, v, dur -> viewModel.addAlarm(selectedAlarmGroupId, h, m, d, l, r, v, dur); showAddAlarmDialog = false },
            onImportAudio = { uri, name -> viewModel.importLocalAudio(context, uri, name) }
        )
    }

    if (editingAlarm != null) {
        AddAlarmDialog(
            editingAlarm = editingAlarm, customRingtones = customRingtones, systemRingtones = systemRingtones, localRecordings = localRecordings,
            customRecordingPath = customRecordingPath, isRecording = isRecording, recordingDuration = recordingDuration,
            onStartRecording = viewModel::startRecording, onStopRecording = viewModel::stopRecording, onCancelRecording = viewModel::cancelRecording,
            onDismiss = { editingAlarm = null },
            onConfirm = { h, m, d, l, r, v, dur ->
                viewModel.updateAlarm(editingAlarm!!.copy(hour = h, minute = m, daysOfWeek = d, label = l, ringtonePath = r, vibrate = v, ringtoneDurationSecs = dur))
                editingAlarm = null
            },
            onImportAudio = { uri, name -> viewModel.importLocalAudio(context, uri, name) }
        )
    }

    if (showSettingsDialog) {
        AppSettingsDialog(
            theme = appTheme, lang = appLanguage, offsetHours = duplicateOffsetHours, offsetMinutes = duplicateOffsetMinutes,
            recordingPath = customRecordingPath, dbDirectoryPath = dbDirectoryPath, autoUpdate = autoUpdateEnabled,
            onSetTheme = viewModel::setTheme, onSetLanguage = viewModel::setLanguage,
            onSetOffsetHours = viewModel::setDuplicateOffsetHours, onSetOffsetMinutes = viewModel::setDuplicateOffsetMinutes,
            onSetRecordingPath = viewModel::setCustomRecordingPath, onSetDatabaseDirectoryPath = viewModel::setDatabaseDirectoryPath,
            onSetAutoUpdate = viewModel::setAutoUpdateEnabled, onCheckUpdate = viewModel::checkForUpdates, onDismiss = { showSettingsDialog = false },
            availableTtsEngines = availableTtsEngines, selectedTtsEngine = selectedTtsEngine, onSetTtsEngine = viewModel::setTtsEngine,
            availableVoices = availableVoices, selectedTtsVoice = selectedTtsVoice, onSetTtsVoice = viewModel::setTtsVoice,
            ttsFormat = ttsFormat, onSetTtsFormat = viewModel::setTtsFormat,
            ttsPitch = ttsPitch, ttsRate = ttsRate, onSetTtsPitch = { viewModel.setTtsPitch(it); ttsPitch = it }, onSetTtsRate = { viewModel.setTtsRate(it); ttsRate = it },
            onTestTts = viewModel::testTts, onScanTtsEngines = viewModel::scanTtsEngines, debugLogs = debugLogs,
            onCleanupUnusedCache = {
                showTtsProgress = true; ttsProgressText = "正在扫描缓存..."
                scope.launch(Dispatchers.IO) {
                    val used = checkInTasksMap.values.flatten().filter { it.useTts }.map { it.name }.toSet()
                    val (del, freed) = com.ccsoft.alarm.alarm.TtsTaskPlayer.cleanupUnused(context, used)
                    withContext(Dispatchers.Main) { showTtsProgress = false; Toast.makeText(context, "已清除 $del 个文件", Toast.LENGTH_SHORT).show() }
                }
            },
            onRebuildMissingCache = {
                showTtsProgress = true; ttsProgressText = "正在合成语音..."
                scope.launch(Dispatchers.IO) {
                    val all = checkInTasksMap.values.flatten().filter { it.useTts }.map { it.name }.distinct()
                    val missing = all.filter { com.ccsoft.alarm.alarm.TtsTaskPlayer.getCacheFile(context, it)?.exists() != true }
                    ttsProgressTotal = missing.size
                    missing.forEachIndexed { i, text ->
                        withContext(Dispatchers.Main) { ttsProgressCurrent = i + 1 }
                        com.ccsoft.alarm.alarm.TtsTaskPlayer.generateSync(context, text)
                    }
                    withContext(Dispatchers.Main) { showTtsProgress = false; Toast.makeText(context, "合成完成", Toast.LENGTH_SHORT).show() }
                }
            },
            countdownWarningSeconds = warningSec, currentSoundType = warningSoundConfig.countdownWarningSoundType,
            currentCustomPath = warningSoundConfig.countdownWarningCustomPath, currentTtsText = warningSoundConfig.countdownWarningTtsText,
            onSetCountdownWarningSeconds = viewModel::setCountdownWarningSeconds, onSetCountdownWarningSoundType = viewModel::setCountdownWarningSoundType,
            onSetCustomPath = viewModel::setCountdownWarningCustomPath, onSetTtsText = viewModel::setCountdownWarningTtsText,
            timerFinishSoundType = warningSoundConfig.timerFinishSoundType, timerFinishCustomPath = warningSoundConfig.timerFinishCustomPath, timerFinishTtsText = warningSoundConfig.timerFinishTtsText,
            onSetTimerFinishSoundType = viewModel::setTimerFinishSoundType, onSetTimerFinishCustomPath = viewModel::setTimerFinishCustomPath, onSetTimerFinishTtsText = viewModel::setTimerFinishTtsText,
            onOpenFullScreen = {
                val intent = Intent(context, com.ccsoft.alarm.ui.screens.FullScreenAlarmActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            isFloatingEnabled = isFloatingEnabled, onSetFloatingEnabled = { 
                if (it && !android.provider.Settings.canDrawOverlays(context)) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    viewModel.setFloatingTimerEnabled(it)
                }
            },
            isStatusBarClockEnabled = isStatusBarClockEnabled,
            onSetStatusBarClockEnabled = viewModel::setStatusBarClockEnabled,
            onAdjustStatusBarPos = { dx, dy -> viewModel.adjustPos(dx, dy) },
            dpadTarget = dpadTarget,
            onSetDpadTarget = viewModel::setDpadTarget,
            isTopBarClockEnabled = isTopBarClockEnabled,
            topBarClockColor = topBarClockColor,
            topBarClockBgColor = topBarClockBgColor,
            onSetTopBarClockEnabled = viewModel::setTopBarClockEnabled,
            sbTextColor = sbTextColor,
            sbBgColor = sbBgColor,
            floatTextColor = floatTextColor,
            floatBgColor = floatBgColor,
            widgetTextColor = widgetTextColor,
            widgetBgColor = widgetBgColor,
            naWidgetTimeColor = naWidgetTimeColor,
            naWidgetCountdownColor = naWidgetCountdownColor,
            naWidgetLabelColor = naWidgetLabelColor,
            onSetColors = viewModel::setColors,
            sbFontSize = sbFontSize,
            floatFontSize = floatFontSize,
            naWidgetTimeSize = naWidgetTimeSize,
            naWidgetCountdownSize = naWidgetCountdownSize,
            naWidgetLabelSize = naWidgetLabelSize,
            onSetFontSize = viewModel::setFontSize,
            permissionList = permissionList,
            hourlyChimeMasterEnabled = hourlyChimeMasterEnabled,
            onSetHourlyChimeMasterEnabled = viewModel::setHourlyChimeMasterEnabled,
            isServiceStatusMonitorEnabled = isServiceStatusMonitorEnabled,
            onSetServiceStatusMonitorEnabled = { enabled ->
                viewModel.setServiceStatusMonitorEnabled(enabled)
                if (!enabled) {
                    com.ccsoft.alarm.service.ServiceStatusMonitor.stop(context)
                } else {
                    com.ccsoft.alarm.service.ServiceStatusMonitor.start(context)
                }
            }
        )
    }

    if (showTtsProgress) {
        AlertDialog(onDismissRequest = {}, title = { Text("语音缓存管理") }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (ttsProgressTotal > 0) LinearProgressIndicator(progress = { (ttsProgressCurrent.toFloat() / ttsProgressTotal).coerceIn(0f, 1f) })
                else CircularProgressIndicator()
                Text(ttsProgressText)
            }
        }, confirmButton = {})
    }

    if (showAddCheckInDialog) {
        val group = editingCheckInGroup
        val tasks = if (group != null) checkInTasksMap[group.id] ?: emptyList() else emptyList()
        AddCheckInGroupDialog(
            existingGroup = group, existingTasks = tasks, customRingtones = customRingtones, systemRingtones = systemRingtones,
            localRecordings = localRecordings, customRecordingPath = customRecordingPath, isRecording = isRecording,
            recordingDuration = recordingDuration, onStartRecording = viewModel::startRecording, onStopRecording = viewModel::stopRecording,
            onCancelRecording = viewModel::cancelRecording, onImportAudio = { u, n -> viewModel.importLocalAudio(context, u, n) },
            onDismiss = { showAddCheckInDialog = false; editingCheckInGroup = null },
            offsetHours = duplicateOffsetHours, offsetMinutes = duplicateOffsetMinutes,
            onSetOffsetHours = viewModel::setDuplicateOffsetHours, onSetOffsetMinutes = viewModel::setDuplicateOffsetMinutes,
            onConfirm = { n, t -> if (group != null) viewModel.updateCheckInGroup(group, t) else viewModel.addCheckInGroup(n, t); showAddCheckInDialog = false; editingCheckInGroup = null }
        )
    }

    if (updateInfo != null && downloadProgress == -1f) {
        AlertDialog(onDismissRequest = {}, title = { Text("发现新版本") }, text = { Text(updateInfo!!.body) },
            confirmButton = { Button(onClick = viewModel::performUpdate) { Text("立即更新") } },
            dismissButton = { TextButton(onClick = { /* TODO: viewModel.skipUpdate(updateInfo!!.tagName) */ }) { Text("以后再说") } })
    }

    if (downloadProgress >= 0f) {
        AlertDialog(onDismissRequest = {}, title = { Text("正在下载更新...") }, text = { Column { LinearProgressIndicator(progress = { downloadProgress }) } }, confirmButton = {})
    }
}
