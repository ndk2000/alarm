package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Intent
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// import kotlinx.coroutines.flow.drop
import androidx.lifecycle.lifecycleScope
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import com.example.alarm.AlarmScheduler
import com.example.db.Alarm
import com.example.db.AlarmGroup
import com.example.db.HourlyChime
import com.example.db.CheckInGroupEntity
import com.example.ui.AlarmViewModel
import com.example.ui.theme.Theme
import com.example.ui.components.AlarmItem
import com.example.ui.components.RingtoneListItem
import com.example.ui.components.WheelDialPicker
import com.example.ui.screens.MainAppShell
import com.example.ui.screens.AboutTab
import com.example.ui.screens.TimerTab
import com.example.ui.screens.AlarmsTab
import com.example.ui.screens.ChimesTab
import com.example.ui.screens.WifiSyncTab
import com.example.ui.screens.MainAppContent
import com.example.ui.dialogs.AddAlarmDialog
import com.example.ui.dialogs.AppSettingsDialog
import com.example.ui.dialogs.RingtoneSelectionDialog
import com.example.ui.dialogs.AudioRecordDialog
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 后台预生成 24 段报时语音（仅首次需要，之后即开即播）
        // ★ 放到 IO 协程中，避免阻塞主线程（首次合成慢，后续直接跳过）
        lifecycleScope.launch(Dispatchers.IO) {
            com.example.alarm.ChimeAudioPreloader.ensure(this@MainActivity)
        }

        setContent {
            val viewModel: AlarmViewModel = viewModel()
            val appTheme by viewModel.appTheme.collectAsState()

            Theme(appTheme = appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppShell(viewModel)
                }
            }
        }
    }
}

private val ShareDebugTag = "ShareDebug"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShellContent(viewModel: AlarmViewModel) {
    val context = LocalContext.current
    // 从 View 树获取 Activity（比 LocalContext.current 更可靠）
    val activity = LocalView.current.context as? ComponentActivity

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(context, context.getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    // MANAGE_EXTERNAL_STORAGE（Android 11+）需要用 Settings intent，不能用普通权限请求
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 从设置页返回后检查授权状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(context, context.getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "需要「所有文件访问权限」才能录音到公共目录", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
        // 请求 MANAGE_EXTERNAL_STORAGE（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                manageStorageLauncher.launch(intent)
            }
        }
    }

    val groups by viewModel.groups.collectAsState()
    val alarms by viewModel.alarms.collectAsState()
    val chimes by viewModel.chimes.collectAsState()
    val customRingtones by viewModel.customRingtones.collectAsState()
    val systemRingtones by viewModel.systemRingtones.collectAsState()
    val localRecordings by viewModel.localRecordings.collectAsState()
    val isWifiServerOn by viewModel.isWifiServerOn.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncTargetIp by viewModel.syncTargetIp.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val duplicateOffset by viewModel.duplicateOffset.collectAsState()
    val customRecordingPath by viewModel.customRecordingPath.collectAsState()
    val timerRemainingSeconds by viewModel.timerRemainingSeconds.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val isTimerRinging by viewModel.isTimerRinging.collectAsState()
    val timerHours by viewModel.timerHours.collectAsState()
    val timerMinutes by viewModel.timerMinutes.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()

    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        val success = viewModel.exportConfig(os)
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, context.getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { isStream ->
                        val success = viewModel.importConfig(isStream)
                        if (success) {
                            viewModel.loadCustomRingtones()
                            viewModel.loadLocalRecordings()
                            Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, context.getString(R.string.import_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importCheckInGroupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { isStream ->
                        viewModel.importSingleCheckInGroup(isStream) { success ->
                            if (success) {
                                viewModel.loadCustomRingtones()
                                viewModel.loadLocalRecordings()
                                Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, context.getString(R.string.import_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onShareCheckInGroup: (CheckInGroupEntity) -> Unit = { group ->
        scope.launch {
            try {
                Log.d(ShareDebugTag, "=== 开始导出分享流程 ===")

                // ─── 从 View 树获取的 Activity 引用 ───
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    Log.w(ShareDebugTag, "Activity 不可用, activity=${activity}, 跳过分享")
                    return@launch
                }
                Log.d(ShareDebugTag, "group: id=${group.id}, name=${group.name}, isEnabled=${group.isEnabled}")

                val safeName = group.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val tasks = viewModel.checkInTasksMap.value[group.id] ?: emptyList()
                Log.d(ShareDebugTag, "safeName: $safeName, tasks count: ${tasks.size}")

                // ─── 统一写入缓存目录 + FileProvider ───
                val tempFile = File(context.cacheDir, "checkin_${safeName}_${System.currentTimeMillis()}.zip")
                Log.d(ShareDebugTag, "tempFile: ${tempFile.absolutePath}")

                val exportSuccess = java.io.FileOutputStream(tempFile).use { fos ->
                    viewModel.exportSingleCheckInGroup(group, tasks, fos)
                }
                if (!exportSuccess) {
                    Log.e(ShareDebugTag, "exportSingleCheckInGroup 返回 false")
                    Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val fileSize = tempFile.length()
                Log.d(ShareDebugTag, "导出成功, 文件大小: $fileSize bytes")

                if (!tempFile.exists()) {
                    Log.e(ShareDebugTag, "文件不存在: ${tempFile.absolutePath}")
                    Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val shareUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )
                Log.d(ShareDebugTag, "shareUri: $shareUri")

                Log.d(ShareDebugTag, "API level: ${Build.VERSION.SDK_INT}, package: ${context.packageName}")

                // ─── 查询可接收应用（多种 MIME 类型，合并去重） ───
                val mimeTypes = listOf("application/zip", "application/x-zip-compressed",
                    "application/octet-stream")
                val pm = context.packageManager
                val allResults = linkedSetOf<ResolveInfo>()
                for (mime in mimeTypes) {
                    val qi = Intent(Intent.ACTION_SEND).apply { type = mime }
                    try {
                        allResults.addAll(pm.queryIntentActivities(qi, 0))
                    } catch (_: Exception) { }
                }
                // 按包名去重，保留第一个出现的
                val seenPkgs = mutableSetOf<String>()
                val deduped = allResults.filter { ri ->
                    ri.activityInfo.packageName.let { pkg -> if (pkg !in seenPkgs) { seenPkgs.add(pkg); true } else false }
                }.sortedBy { it.loadLabel(pm).toString() }

                // ─── 常用分享白名单 ───
                // 只显示这些包名的应用，列表可自行增删
                val whitelist = setOf(
                    "com.tencent.mm",                // 微信
                    "com.tencent.mobileqq",           // QQ
                    "com.ss.android.ugc.aweme",      // 抖音
                    "com.kuaishou.nebula",            // 快手
                    "com.eg.android.AlipayGphone",    // 支付宝
                    "com.taobao.taobao",              // 淘宝
                    "com.ss.android.article.news",    // 今日头条
                    "com.ss.android.article.lite",     // 今日头条极速版
                    "com.microsoft.emmx",             // Edge
                    "com.android.chrome",             // Chrome
                    "com.quark.browser",              // 夸克
                    "org.localsend.localsend_app",    // LocalSend
                    "com.chinamobile.mcloud",         // 中国移动云盘
                    "com.android.email",              // 电子邮件
                    "com.android.mms",                 // 短信
                    "com.xiaomi.smarthome",           // 米家
                )
                val filteredApps = deduped.filter { ri ->
                    ri.activityInfo.packageName in whitelist
                }.ifEmpty { deduped }  // 如果白名单全都没装，回退到全部

                Log.d(ShareDebugTag, "白名单应用数量: ${filteredApps.size}")
                deduped.forEach { ri ->
                    Log.d(ShareDebugTag, "  应用: ${ri.loadLabel(pm)} / ${ri.activityInfo.packageName}")
                }

                if (filteredApps.isEmpty()) {
                    Log.w(ShareDebugTag, "没有应用可接收分享")
                    Toast.makeText(context, "未找到可接收的应用", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // ─── 弹出自定义应用选择列表 ───
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.w(ShareDebugTag, "Activity 已不可用, 改用系统 Chooser")
                    // 直接用带 EXTRA_STREAM 的 intent 走系统分享
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "分享打卡配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@launch
                }

                val items = filteredApps.map { ri ->
                    Triple(
                        ri.loadLabel(pm).toString(),
                        ri.activityInfo.packageName,
                        ri.activityInfo.name   // 精确的 Activity 类名
                    )
                }
                val appNames = items.map { it.first }.toTypedArray()
                val appIcons = filteredApps.map { ri -> ri.loadIcon(pm) }.toTypedArray()

                android.app.AlertDialog.Builder(activity)
                    .setTitle("分享到")
                    .setAdapter(
                        object : android.widget.ArrayAdapter<String>(activity, android.R.layout.select_dialog_item, appNames) {
                            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                                val view = super.getView(position, convertView, parent)
                                val icon = appIcons[position]
                                if (view is android.widget.TextView) {
                                    val resizedIcon = icon.constantState?.newDrawable()?.mutate() ?: icon
                                    resizedIcon.setBounds(0, 0, 48, 48)
                                    view.setCompoundDrawablesRelative(resizedIcon, null, null, null)
                                    view.compoundDrawablePadding = 24
                                }
                                return view
                            }
                        }
                    ) { dialog: android.content.DialogInterface, which: Int ->
                        val (_, pkg, cls) = items[which]
                        Log.d(ShareDebugTag, "用户选择: $pkg / $cls")
                        dialog.dismiss()
                        activity.window?.decorView?.post {
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    component = android.content.ComponentName(pkg, cls)
                                }
                                activity.startActivity(shareIntent)
                                Log.d(ShareDebugTag, "启动分享到 $pkg / $cls")
                            } catch (e: Exception) {
                                Log.e(ShareDebugTag, "启动分享异常: ${e.message}")
                                // fallback: 用 setPackage 重试
                                try {
                                    val fallback = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        setPackage(pkg)
                                    }
                                    activity.startActivity(fallback)
                                } catch (e2: Exception) {
                                    Toast.makeText(activity, "无法分享到该应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setOnCancelListener { Log.d(ShareDebugTag, "用户取消分享") }
                    .show()

                Log.d(ShareDebugTag, "自定义分享弹窗已显示")
            } catch (e: Exception) {
                Log.e(ShareDebugTag, "分享流程异常: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                Toast.makeText(context, context.getString(R.string.export_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    MainAppContent(
        groups = groups,
        alarms = alarms,
        chimes = chimes,
        customRingtones = customRingtones,
        systemRingtones = systemRingtones,
        localRecordings = localRecordings,
        isWifiServerOn = isWifiServerOn,
        isRecording = isRecording,
        recordingDuration = recordingDuration,
        onToggleGroup = { group, enabled -> viewModel.toggleGroup(group, enabled) },
        onToggleAlarm = { alarm, enabled -> viewModel.toggleAlarm(alarm, enabled) },
        onDeleteGroup = { viewModel.deleteGroup(it) },
        onUpdateGroup = { viewModel.updateGroup(it) },
        onDeleteAlarm = { viewModel.deleteAlarm(it) },
        onDuplicateAlarm = { viewModel.duplicateAlarm(it) },
        onMoveAlarmToGroup = { alarm, targetGroupId -> viewModel.moveAlarmToGroup(alarm, targetGroupId) },
        onToggleChime = { chime, enabled -> viewModel.toggleHourlyChime(chime, enabled) },
        onUpdateChimeDetails = { useTts, vibrate -> viewModel.updateChimeDetails(useTts, vibrate) },
        onToggleWifiSync = { enabled -> viewModel.toggleWifiSync(context, enabled) },
        onLoadCustomRingtones = { viewModel.loadCustomRingtones() },
        onLoadLocalRecordings = { viewModel.loadLocalRecordings() },
        onAddGroup = { viewModel.addGroup(it) },
        onAddAlarm = { groupId, hour, minute, days, label, ringtone, vibrate ->
            viewModel.addAlarm(groupId, hour, minute, days, label, ringtone, vibrate)
        },
        onUpdateAlarm = { viewModel.updateAlarm(it) },
        onImportAudio = { uri, name -> viewModel.importLocalAudio(context, uri, name) },
        onExportConfig = { exportLauncher.launch("alarm_backup_${System.currentTimeMillis()}.zip") },
        onImportConfig = { importLauncher.launch(arrayOf("application/octet-stream", "application/octet-stream")) },
        onTestTts = { viewModel.testTts(it) },
        onSetTtsPitch = { viewModel.setTtsPitch(it) },
        onSetTtsRate = { viewModel.setTtsRate(it) },
        onRefreshMonitor = { viewModel.refreshBackgroundMonitor() },
        syncStatus = syncStatus,
        syncTargetIp = syncTargetIp,
        onSetSyncTargetIp = { viewModel.setSyncTargetIp(it) },
        onSyncFromRemote = { mode -> viewModel.syncFromRemote(context, mode) },
        onClearSyncStatus = { viewModel.clearSyncStatus() },
        appTheme = appTheme,
        appLanguage = appLanguage,
        duplicateOffsetHours = viewModel.duplicateOffsetHours.collectAsState().value,
        duplicateOffsetMinutes = viewModel.duplicateOffsetMinutes.collectAsState().value,
        onSetTheme = { viewModel.setTheme(it) },
        onSetLanguage = { viewModel.setLanguage(it) },
        onSetDuplicateOffsetHours = { viewModel.setDuplicateOffsetHours(it) },
        onSetDuplicateOffsetMinutes = { viewModel.setDuplicateOffsetMinutes(it) },
        customRecordingPath = customRecordingPath,
        onSetCustomRecordingPath = { viewModel.setCustomRecordingPath(it) },
        timerRemainingSeconds = timerRemainingSeconds,
        isTimerRunning = isTimerRunning,
        isTimerRinging = isTimerRinging,
        timerHours = timerHours,
        timerMinutes = timerMinutes,
        timerSeconds = timerSeconds,
        onSetTimerHours = { viewModel.setTimerHours(it) },
        onSetTimerMinutes = { viewModel.setTimerMinutes(it) },
        onSetTimerSeconds = { viewModel.setTimerSeconds(it) },
        onStartTimer = { viewModel.startTimer(it) },
        onStopTimer = { viewModel.stopTimer() },
        onDismissTimerRinging = { viewModel.dismissTimerRinging() },
        debugLogs = debugLogs,
        onStartRecording = { viewModel.startRecording() },
        onStopRecording = { viewModel.stopRecording(it) },
        onCancelRecording = { viewModel.cancelRecording() },
        availableTtsEngines = viewModel.availableTtsEngines.collectAsState().value,
        selectedTtsEngine = viewModel.selectedTtsEngine.collectAsState().value,
        onSetTtsEngine = { viewModel.setTtsEngine(it) },
        availableVoices = viewModel.availableVoices.collectAsState().value,
        selectedTtsVoice = viewModel.selectedTtsVoiceName.collectAsState().value,
        onSetTtsVoice = { viewModel.setTtsVoice(it) },
        onScanTtsEngines = { viewModel.scanTtsEngines() },
        discoveredDevices = viewModel.discoveredDevices.collectAsState().value,
        onStartDiscovery = { viewModel.startDiscovery() },
        onStopDiscovery = { viewModel.stopDiscovery() },
        autoUpdateEnabled = viewModel.autoUpdateEnabled.collectAsState().value,
        onSetAutoUpdateEnabled = { viewModel.setAutoUpdateEnabled(it) },
        onCheckUpdate = { viewModel.checkForUpdates() },
        updateInfo = viewModel.updateInfo.collectAsState().value,
        onPerformUpdate = { viewModel.performUpdate() },
        downloadProgress = viewModel.downloadProgress.collectAsState().value,
        onDeleteRingtone = { viewModel.deleteRingtone(it) },
        // 打卡相关
        checkInGroups = viewModel.checkInGroups.collectAsState().value,
        checkInTasksMap = viewModel.checkInTasksMap.collectAsState().value,
        onAddCheckInGroup = { name, tasks ->
            viewModel.addCheckInGroup(name, tasks)
        },
        onDeleteCheckInGroup = { viewModel.deleteCheckInGroup(it) },
        onUpdateCheckInGroup = { group, tasks ->
            viewModel.updateCheckInGroup(group, tasks)
        },
        onToggleCheckInGroup = { group, enabled, replaceExisting ->
            viewModel.toggleCheckInGroup(group, enabled, replaceExisting)
        },
        onDuplicateCheckInGroup = { viewModel.duplicateCheckInGroup(it) },
        onShareCheckInGroup = onShareCheckInGroup,
        onImportCheckInGroup = { importCheckInGroupLauncher.launch(arrayOf("application/octet-stream", "application/octet-stream")) }
    )
}











// Utility IP fetcher on local Network
fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val ip = address.hostAddress
                    if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                        return ip
                    }
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return null
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF121318)
@Composable
fun MainAppPreview() {
    Theme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainAppContent(
                groups = listOf(
                    AlarmGroup(1, "工作日", true),
                    AlarmGroup(2, "休息日", true)
                ),
                alarms = listOf(
                    Alarm(1, 1, 7, 30, "1,2,3,4,5", true, "早起打卡", null, true),
                    Alarm(2, 2, 10, 0, "6,7", true, "周末懒觉", null, true)
                ),
                chimes = List(24) { i -> HourlyChime(i, i == 12, true, true) },
                customRingtones = listOf("晨间鸟鸣.mp3", "活力电音.wav"),
                systemRingtones = listOf("默认" to "content://ringtone/1", "欢快" to "content://ringtone/2"),
                localRecordings = listOf("会议录音.mp3" to "/path/to/rec1"),
                isWifiServerOn = false,
                isRecording = false,
                recordingDuration = 0,
                onToggleGroup = { _, _ -> },
                onToggleAlarm = { _, _ -> },
                onDeleteGroup = {},
                onUpdateGroup = {},
                onDeleteAlarm = {},
                onDuplicateAlarm = {},
                onMoveAlarmToGroup = { _, _ -> },
                onToggleChime = { _, _ -> },
                onUpdateChimeDetails = { _, _ -> },
                onToggleWifiSync = {},
                onLoadCustomRingtones = {},
                onLoadLocalRecordings = {},
                onAddGroup = {},
                onAddAlarm = { _, _, _, _, _, _, _ -> },
                onUpdateAlarm = {},
                onImportAudio = { _, _ -> null },
                onExportConfig = {},
                onImportConfig = {},
                onTestTts = {},
                onSetTtsPitch = {},
                onSetTtsRate = {},
                onRefreshMonitor = {},
                syncStatus = com.example.ui.AlarmViewModel.SyncStatus.Idle,
                syncTargetIp = "",
                onSetSyncTargetIp = {},
                onSyncFromRemote = { _ -> },
                onClearSyncStatus = {},
                appTheme = 0,
                appLanguage = "zh",
                duplicateOffsetHours = 0,
                duplicateOffsetMinutes = 10,
                onSetTheme = {},
                onSetLanguage = {},
                onSetDuplicateOffsetHours = {},
                onSetDuplicateOffsetMinutes = {},
                timerRemainingSeconds = 0,
                isTimerRunning = false,
                isTimerRinging = false,
                timerHours = 0,
                timerMinutes = 0,
                timerSeconds = 0,
                onSetTimerHours = {},
                onSetTimerMinutes = {},
                onSetTimerSeconds = {},
                onStartTimer = {},
                onStopTimer = {},
                onDismissTimerRinging = {},
                debugLogs = emptyList(),
                onStartRecording = {},
                onStopRecording = { _ -> null },
                onCancelRecording = {},
                onScanTtsEngines = {},
                discoveredDevices = emptyList(),
                customRecordingPath = android.os.Environment.getExternalStorageDirectory().absolutePath + "/0",
                onSetCustomRecordingPath = {},
                autoUpdateEnabled = true,
                onSetAutoUpdateEnabled = {},
                updateInfo = null,
                onPerformUpdate = {},
                downloadProgress = -1f,
            )
        }
    }
}
