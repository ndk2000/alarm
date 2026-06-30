package com.ccsoft.alarm.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.net.InetAddress
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ccsoft.alarm.alarm.AlarmScheduler
import com.ccsoft.alarm.alarm.AlarmService
import com.ccsoft.alarm.cloud.CloudConfigKeys
import com.ccsoft.alarm.db.*
import com.ccsoft.alarm.util.PreferencesManager
import com.ccsoft.alarm.util.StatusBarState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class AlarmViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val db = AlarmDatabase.getDatabase(application, viewModelScope)
    private val repository = AlarmRepository(db.alarmDao(), db.checkinDao())
    private val checkInDao = db.checkinDao()
    private val cloudShareDao = db.cloudShareDao()
    var cloudService: com.ccsoft.alarm.cloud.CloudService = com.ccsoft.alarm.cloud.getService(application)
    private val prefs = PreferencesManager(application)
    
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val foundDevicesMap = mutableMapOf<String, String>()
    
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var recordingFile: File? = null

    // ──────────────── 所有的状态变量 (必须与 MainAppContent 中的引用完全一致) ────────────────
    val groups = repository.allGroups.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val alarms = repository.allAlarms.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chimes = repository.allHourlyChimes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val checkInGroups = checkInDao.getAllGroupsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cloudShareRecords = cloudShareDao.getAllRecordsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isWifiServerOn = MutableStateFlow(false)
    val syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncTargetIp = MutableStateFlow("")
    val isRecording = MutableStateFlow(false)
    val recordingDuration = MutableStateFlow(0)
    val appTheme = MutableStateFlow(0)
    val appLanguage = MutableStateFlow("zh")
    val duplicateOffsetHours = MutableStateFlow(0)
    val duplicateOffsetMinutes = MutableStateFlow(10)
    val customRecordingPath = MutableStateFlow("")
    val dbDirectoryPath = MutableStateFlow("")
    
    val timerRemainingSeconds = MutableStateFlow(0)
    val isTimerRunning = MutableStateFlow(false)
    val isTimerRinging = MutableStateFlow(false)
    val timerHours = MutableStateFlow(0)
    val timerMinutes = MutableStateFlow(0)
    val timerSeconds = MutableStateFlow(0)
    
    val debugLogs = MutableStateFlow<List<String>>(emptyList())
    val availableTtsEngines = MutableStateFlow<List<TextToSpeech.EngineInfo>>(emptyList())
    val availableVoices = MutableStateFlow<List<Voice>>(emptyList())
    val selectedTtsEngine = MutableStateFlow("")
    val selectedTtsVoiceName = MutableStateFlow("")
    val ttsFormat = MutableStateFlow("wav") // 默认 wav
    val customRingtones = MutableStateFlow<List<String>>(emptyList())
    val systemRingtones = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val localRecordings = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val discoveredDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val autoUpdateEnabled = MutableStateFlow(true)
    val hourlyChimeMasterEnabled = MutableStateFlow(true)
    
    data class UpdateInfo(val tagName: String, val downloadUrl: String, val body: String)
    val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val downloadProgress = MutableStateFlow(-1f)

    // 预警配置 (必须是显式的 Flow)
    val countdownWarningSeconds = MutableStateFlow(120)
    val countdownWarningSoundType = MutableStateFlow("tick_tock")
    val countdownWarningCustomPath = MutableStateFlow("")
    val countdownWarningTtsText = MutableStateFlow("")
    val timerFinishSoundType = MutableStateFlow("tick_tock")
    val timerFinishCustomPath = MutableStateFlow("")
    val timerFinishTtsText = MutableStateFlow("")

    val isFloatingTimerEnabled = MutableStateFlow(false)
    private val _prefsStatusBarEnabled = prefs.isStatusBarClockEnabled()
    val isStatusBarClockEnabled = MutableStateFlow(_prefsStatusBarEnabled)
    val isTopBarClockEnabled = StatusBarState.topBarClockEnabled
    
    val statusBarX = MutableStateFlow(180)
    val statusBarY = MutableStateFlow(0)
    val floatingX = MutableStateFlow(100)
    val floatingY = MutableStateFlow(100)
    val dpadTarget = MutableStateFlow(0)

    val sbTextColor = StatusBarState.sbTextColor
    val sbBgColor = StatusBarState.sbBgColor
    val floatTextColor = StatusBarState.floatTextColor
    val floatBgColor = StatusBarState.floatBgColor
    val widgetTextColor = StatusBarState.widgetTextColor
    val widgetBgColor = StatusBarState.widgetBgColor
    val naWidgetTimeColor = StatusBarState.naWidgetTimeColor
    val naWidgetCountdownColor = StatusBarState.naWidgetCountdownColor
    val naWidgetLabelColor = StatusBarState.naWidgetLabelColor
    val topBarClockColor = StatusBarState.topBarClockColor
    val topBarClockBgColor = StatusBarState.topBarClockBgColor

    val sbFontSize = StatusBarState.sbFontSize
    val floatFontSize = StatusBarState.floatFontSize
    val naWidgetTimeSize = StatusBarState.naWidgetTimeSize
    val naWidgetCountdownSize = StatusBarState.naWidgetCountdownSize
    val naWidgetLabelSize = StatusBarState.naWidgetLabelSize

    val cloudShareLoading = MutableStateFlow(false)
    val cloudShareCode = MutableStateFlow<String?>(null)
    val cloudImportResult = MutableStateFlow<String?>(null)
    val currentUser = com.ccsoft.alarm.cloud.SupabaseManager.currentUser

    val checkInTasksMap = MutableStateFlow<Map<Long, List<CheckInTaskEntity>>>(emptyMap())

    data class PermissionInfo(
        val id: String,
        val title: String,
        val desc: String,
        val isGranted: Boolean,
        val action: () -> Unit
    )
    val permissionList = MutableStateFlow<List<PermissionInfo>>(emptyList())

    init {
        // 恢复坐标与字号（使用 PreferencesManager）
        statusBarX.value = prefs.getStatusBarX()
        statusBarY.value = prefs.getStatusBarY()
        StatusBarState.updatePosition(statusBarX.value, statusBarY.value)
        
        // 恢复颜色设置
        StatusBarState.updateColors(
            sbText = prefs.getStatusBarTextColor(),
            sbBg = prefs.getStatusBarBgColor(),
            floatText = prefs.getFloatTextColor(),
            floatBg = prefs.getFloatBgColor(),
            widgetText = prefs.getWidgetTextColor(),
            widgetBg = prefs.getWidgetBgColor(),
            topBarClock = prefs.getTopBarClockColor(),
            topBarBg = prefs.getTopBarClockBgColor(),
            naTime = prefs.getNextAlarmWidgetTimeColor(),
            naCountdown = prefs.getNextAlarmWidgetCountdownColor(),
            naLabel = prefs.getNextAlarmWidgetLabelColor()
        )
        StatusBarState.setTopBarClockEnabled(prefs.isTopBarClockEnabled())

        // 恢复悬浮窗开关与调节目标
        isFloatingTimerEnabled.value = prefs.isFloatingTimerEnabled()
        isStatusBarClockEnabled.value = prefs.isStatusBarClockEnabled()
        dpadTarget.value = prefs.getDpadTarget()

        StatusBarState.updateFontSize(
            sb = prefs.getStatusBarFontSize(),
            float = prefs.getFloatFontSize(),
            naTime = prefs.getNextAlarmWidgetTimeSize(),
            naCountdown = prefs.getNextAlarmWidgetCountdownSize(),
            naLabel = prefs.getNextAlarmWidgetLabelSize()
        )

        // 恢复计时器状态
        val timerEnd = prefs.getTimerEndMillis()
        if (timerEnd > System.currentTimeMillis()) {
            val remain = ((timerEnd - System.currentTimeMillis()) / 1000).toInt()
            timerRemainingSeconds.value = remain
            isTimerRunning.value = true
        }

        // 注册计时器广播接收器
        val timerReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.ccsoft.alarm.TIMER_PROGRESS_CHANGED" -> {
                        timerRemainingSeconds.value = intent.getIntExtra("REMAINING_SECONDS", 0)
                        isTimerRunning.value = true
                    }
                    "com.ccsoft.alarm.TIMER_FINISHED" -> {
                        // 计时结束，设置响铃状态
                        isTimerRinging.value = true
                        isTimerRunning.value = false
                        timerRemainingSeconds.value = 0
                    }
                    "com.ccsoft.alarm.TIMER_DISMISSED" -> {
                        isTimerRinging.value = false
                        isTimerRunning.value = false
                        timerRemainingSeconds.value = 0
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.ccsoft.alarm.TIMER_PROGRESS_CHANGED")
            addAction("com.ccsoft.alarm.TIMER_FINISHED")
            addAction("com.ccsoft.alarm.TIMER_DISMISSED")
        }
        ContextCompat.registerReceiver(application, timerReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // 恢复其它全局设置（使用 PreferencesManager）
        appTheme.value = prefs.getTheme()
        appLanguage.value = prefs.getLanguage()
        duplicateOffsetHours.value = prefs.getDuplicateOffsetHours()
        duplicateOffsetMinutes.value = prefs.getDuplicateOffsetMinutes()
        autoUpdateEnabled.value = prefs.isAutoUpdateEnabled()
        hourlyChimeMasterEnabled.value = prefs.isHourlyChimeMasterEnabled()
        selectedTtsEngine.value = prefs.getTtsEngine()
        selectedTtsVoiceName.value = prefs.getTtsVoice()
        ttsFormat.value = prefs.getTtsFormat()
        
        customRecordingPath.value = prefs.getRecordingPath()
        dbDirectoryPath.value = prefs.getDatabaseDirPath()
        
        val ttsPitch = prefs.getTtsPitch()
        val ttsRate = prefs.getTtsRate()

        // 恢复预警配置
        countdownWarningSeconds.value = prefs.getCountdownWarningSeconds()
        countdownWarningSoundType.value = prefs.getCountdownWarningSoundType()
        countdownWarningCustomPath.value = prefs.getCountdownWarningCustomPath()
        countdownWarningTtsText.value = prefs.getCountdownWarningTtsText()
        timerFinishSoundType.value = prefs.getTimerFinishSoundType()
        timerFinishCustomPath.value = prefs.getTimerFinishCustomPath()
        timerFinishTtsText.value = prefs.getTimerFinishTtsText()

        // 同步 TTS 参数到 TtsTaskPlayer
        com.ccsoft.alarm.alarm.TtsTaskPlayer.engineName = selectedTtsEngine.value
        com.ccsoft.alarm.alarm.TtsTaskPlayer.voiceName = selectedTtsVoiceName.value
        com.ccsoft.alarm.alarm.TtsTaskPlayer.outputFormat = ttsFormat.value
        com.ccsoft.alarm.alarm.TtsTaskPlayer.pitch = ttsPitch
        com.ccsoft.alarm.alarm.TtsTaskPlayer.speechRate = ttsRate
        
        scanTtsEngines()
        if (selectedTtsEngine.value.isNotEmpty()) {
            scanTtsVoices(selectedTtsEngine.value)
        }
        
        viewModelScope.launch {
            checkInDao.getAllGroupsFlow().collect { groups ->
                coroutineScope {
                    val tasks = groups.map { group ->
                        async { group.id to checkInDao.getTasksByGroup(group.id) }
                    }.awaitAll().toMap()
                    checkInTasksMap.value = tasks
                }
            }
        }
        viewModelScope.launch {
            while(true) {
                updatePermissionList()
                delay(2000)
            }
        }
    }

    private fun updatePermissionList() {
        val context = getApplication<Application>()
        val list = mutableListOf<PermissionInfo>()

        // 1. 通知权限
        val isNotifyGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        list.add(PermissionInfo(
            "notify", "通知权限", "确保闹钟响铃时能弹出界面和显示状态栏通知",
            isNotifyGranted
        ) {
            val intent = Intent().apply {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        })

        // 2. 精准闹钟 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val isExactGranted = am.canScheduleExactAlarms()
            list.add(PermissionInfo(
                "exact", "精准闹钟", "允许 App 在预定时间秒级触发，不被系统延迟",
                isExactGranted
            ) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            })
        }

        // 3. 电池优化白名单
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        list.add(PermissionInfo(
            "battery", "电池优化白名单", "防止系统在后台杀死 App，确保闹钟服务常驻",
            isBatteryIgnored
        ) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        })

        // 4. 所有文件访问 (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isAllFilesGranted = android.os.Environment.isExternalStorageManager()
            list.add(PermissionInfo(
                "storage", "所有文件访问", "将数据库存储在公共目录，卸载 App 后数据不丢失",
                isAllFilesGranted
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            })
        }

        // 5. 悬浮窗权限
        val isOverlayGranted = Settings.canDrawOverlays(context)
        list.add(PermissionInfo(
            "overlay", "显示在其他应用上", "用于状态栏秒表、倒计时悬浮窗及全屏响铃界面",
            isOverlayGranted
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        })

        permissionList.value = list
    }

    // ──────────────── 所有的接口方法 ────────────────
    
    /** 在 viewModelScope 中执行数据库操作，完成后自动通知守护服务刷新 */
    private fun withGuardRefresh(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            notifyGuardToRefresh()
        }
    }

    /** 同上，并在通知守护之前额外调用 refreshBackgroundMonitor */
    private fun withGuardRefreshAndMonitor(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refreshBackgroundMonitor()
            notifyGuardToRefresh()
        }
    }

    private fun notifyGuardToRefresh() {
        val context = getApplication<Application>()
        val intent = Intent(context, com.ccsoft.alarm.alarm.AlarmGuardService::class.java).apply {
            action = "REFRESH_AND_REPORT"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        // 同步更新所有桌面插件
        com.ccsoft.alarm.widget.TimerWidgetProvider.updateWidgetStyle(context, false, false)
        com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(context)
    }

    fun setTheme(t: Int) { 
        appTheme.value = t
        prefs.setTheme(t)
    }
    fun setLanguage(l: String) { 
        appLanguage.value = l
        prefs.setLanguage(l)
    }
    fun setDuplicateOffsetHours(h: Int) { 
        duplicateOffsetHours.value = h 
        prefs.setDuplicateOffsetHours(h)
    }
    fun setDuplicateOffsetMinutes(m: Int) { 
        duplicateOffsetMinutes.value = m 
        prefs.setDuplicateOffsetMinutes(m)
    }
    fun setFloatingTimerEnabled(e: Boolean) { 
        isFloatingTimerEnabled.value = e 
        prefs.setFloatingTimerEnabled(e)
    }
    fun setStatusBarClockEnabled(e: Boolean) { 
        isStatusBarClockEnabled.value = e
        prefs.setStatusBarClockEnabled(e)
    }
    fun setTopBarClockEnabled(e: Boolean) {
        StatusBarState.setTopBarClockEnabled(e)
        prefs.setTopBarClockEnabled(e)
    }
    fun setDpadTarget(t: Int) { 
        dpadTarget.value = t 
        prefs.setDpadTarget(t)
    }
    
    fun adjustPos(dx: Int, dy: Int) {
        if (dpadTarget.value == 0) {
            statusBarX.value += dx; statusBarY.value += dy
            StatusBarState.updatePosition(statusBarX.value, statusBarY.value)
            prefs.setStatusBarX(statusBarX.value)
            prefs.setStatusBarY(statusBarY.value)
        } else {
            floatingX.value += dx; floatingY.value += dy
            StatusBarState.updateFloatingPosition(floatingX.value, floatingY.value)
        }
    }

    fun setColors(type: String, text: Int, bg: Int) {
        Log.i("ColorDebug", "ViewModel setColors: type=$type, text=${Integer.toHexString(text)}, bg=${Integer.toHexString(bg)}")
        when(type) {
            "sb" -> {
                StatusBarState.updateColors(sbText = text, sbBg = bg)
                prefs.setStatusBarTextColor(text)
                prefs.setStatusBarBgColor(bg)
            }
            "float" -> {
                StatusBarState.updateColors(floatText = text, floatBg = bg)
                prefs.setFloatTextColor(text)
                prefs.setFloatBgColor(bg)
            }
            "widget" -> {
                Log.d("ColorDebug", "Updating general widget colors: text=$text, bg=$bg")
                StatusBarState.updateColors(widgetText = text, widgetBg = bg)
                prefs.setWidgetTextColor(text)
                prefs.setWidgetBgColor(bg)
                com.ccsoft.alarm.widget.TimerWidgetProvider.updateWidgetStyle(getApplication(), false, false)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "na" -> {
                Log.d("ColorDebug", "Updating NA widget background only: bg=$bg")
                StatusBarState.updateColors(widgetBg = bg)
                prefs.setWidgetBgColor(bg)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "topbar" -> {
                StatusBarState.updateColors(topBarClock = text, topBarBg = bg)
                prefs.setTopBarClockColor(text)
                prefs.setTopBarClockBgColor(bg)
            }
            "na_time" -> {
                StatusBarState.updateColors(naTime = text, widgetBg = bg)
                prefs.setNextAlarmWidgetTimeColor(text)
                prefs.setWidgetBgColor(bg)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "na_countdown" -> {
                StatusBarState.updateColors(naCountdown = text, widgetBg = bg)
                prefs.setNextAlarmWidgetCountdownColor(text)
                prefs.setWidgetBgColor(bg)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "na_label" -> {
                StatusBarState.updateColors(naLabel = text, widgetBg = bg)
                prefs.setNextAlarmWidgetLabelColor(text)
                prefs.setWidgetBgColor(bg)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
        }
    }

    fun setFontSize(type: String, size: Float) {
        when(type) {
            "sb" -> {
                StatusBarState.updateFontSize(sb = size)
                prefs.setStatusBarFontSize(size)
            }
            "float" -> {
                StatusBarState.updateFontSize(float = size)
                prefs.setFloatFontSize(size)
            }
            "na_time" -> {
                StatusBarState.updateFontSize(naTime = size)
                prefs.setNextAlarmWidgetTimeSize(size)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "na_countdown" -> {
                StatusBarState.updateFontSize(naCountdown = size)
                prefs.setNextAlarmWidgetCountdownSize(size)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
            "na_label" -> {
                StatusBarState.updateFontSize(naLabel = size)
                prefs.setNextAlarmWidgetLabelSize(size)
                com.ccsoft.alarm.widget.NextAlarmWidgetProvider.updateAllWidgets(getApplication())
            }
        }
    }

    fun toggleGroup(g: AlarmGroup, e: Boolean) {
        withGuardRefresh {
            val updatedGroup = g.copy(isEnabled = e)
            db.alarmDao().updateGroup(updatedGroup)
            // 立即更新该组下所有闹钟的调度状态
            val alarms = db.alarmDao().getAlarmsByGroup(g.id)
            alarms.forEach { AlarmScheduler.scheduleAlarm(getApplication(), it, updatedGroup) }
        }
    }

    fun toggleAlarm(a: Alarm, e: Boolean) {
        withGuardRefresh {
            val updatedAlarm = a.copy(isEnabled = e)
            db.alarmDao().updateAlarm(updatedAlarm)
            // 立即更新该闹钟的调度状态
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == a.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), updatedAlarm, group)
        }
    }

    fun deleteGroup(g: AlarmGroup) {
        withGuardRefresh {
            // 先取消该组下所有闹钟
            val alarms = db.alarmDao().getAlarmsByGroup(g.id)
            alarms.forEach { AlarmScheduler.cancelAlarm(getApplication(), it.id) }
            db.alarmDao().deleteGroup(g)
        }
    }

    fun updateGroup(g: AlarmGroup) {
        withGuardRefresh {
            db.alarmDao().updateGroup(g)
        }
    }

    fun deleteAlarm(a: Alarm) {
        withGuardRefresh {
            AlarmScheduler.cancelAlarm(getApplication(), a.id)
            db.alarmDao().deleteAlarm(a)
        }
    }

    fun insertAlarm(a: Alarm) {
        viewModelScope.launch {
            val id = db.alarmDao().insertAlarm(a)
            val inserted = a.copy(id = id)
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == a.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), inserted, group)
        }
    }

    fun insertGroup(g: AlarmGroup) { viewModelScope.launch { db.alarmDao().insertGroup(g) } }

    fun updateAlarm(a: Alarm) {
        withGuardRefresh {
            db.alarmDao().updateAlarm(a)
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == a.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), a, group)
        }
    }

    fun moveAlarmToGroup(a: Alarm, gId: Long) {
        withGuardRefresh {
            val updated = a.copy(groupId = gId)
            db.alarmDao().updateAlarm(updated)
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == gId }
            AlarmScheduler.scheduleAlarm(getApplication(), updated, group)
        }
    }
    fun toggleHourlyChime(c: HourlyChime, e: Boolean) { viewModelScope.launch { db.alarmDao().updateHourlyChime(c.copy(isEnabled = e)) } }

    fun addGroup(name: String) { 
        withGuardRefresh { 
            db.alarmDao().insertGroup(AlarmGroup(0, name, true))
        } 
    }
    fun addAlarm(gId: Long, h: Int, m: Int, dy: String, l: String, r: String?, v: Boolean, s: Int) {
        withGuardRefresh {
            val newAlarm = Alarm(0, gId, h, m, dy, true, l, r, v, s)
            val id = db.alarmDao().insertAlarm(newAlarm)
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == gId }
            AlarmScheduler.scheduleAlarm(getApplication(), newAlarm.copy(id = id), group)
        }
    }

    fun duplicateAlarm(a: Alarm) {
        withGuardRefresh {
            val copy = a.copy(id = 0, label = "${a.label} (副本)")
            val id = db.alarmDao().insertAlarm(copy)
            val groups = db.alarmDao().getAllGroups()
            val group = groups.find { it.id == a.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), copy.copy(id = id), group)
        }
    }

    fun addCheckInGroup(n: String, t: List<com.ccsoft.alarm.ui.dialogs.CheckInTaskInput>) {
        withGuardRefresh {
            val groupId = checkInDao.insertGroup(CheckInGroupEntity(name = n))
            val tasks = t.mapIndexed { index, input ->
                CheckInTaskEntity(
                    groupId = groupId,
                    name = input.name,
                    hour = input.hour.toIntOrNull() ?: 8,
                    minute = input.minute.toIntOrNull() ?: 0,
                    orderIndex = index,
                    ringtonePath = input.ringtonePath,
                    useTts = input.useTts
                )
            }
            checkInDao.insertTasks(tasks)
        }
    }

    fun addCheckInTasks(id: Long, t: List<CheckInTaskEntity>) {
        viewModelScope.launch {
            val currentTasks = checkInDao.getTasksByGroup(id)
            val startIndex = currentTasks.size
            val newTasks = t.mapIndexed { index, task ->
                task.copy(id = 0, groupId = id, orderIndex = startIndex + index)
            }
            checkInDao.insertTasks(newTasks)
        }
    }

    fun updateCheckInGroup(g: CheckInGroupEntity, t: List<com.ccsoft.alarm.ui.dialogs.CheckInTaskInput>) {
        viewModelScope.launch {
            checkInDao.updateGroup(g)
            checkInDao.deleteTasksByGroup(g.id)
            val tasks = t.mapIndexed { index, input ->
                CheckInTaskEntity(
                    groupId = g.id,
                    name = input.name,
                    hour = input.hour.toIntOrNull() ?: 8,
                    minute = input.minute.toIntOrNull() ?: 0,
                    orderIndex = index,
                    ringtonePath = input.ringtonePath,
                    useTts = input.useTts
                )
            }
            checkInDao.insertTasks(tasks)
            if (g.isEnabled && g.boundAlarmGroupId != -1L) {
                toggleCheckInGroup(g, true, true)
            }
            notifyGuardToRefresh()
        }
    }

    fun deleteCheckInGroup(g: CheckInGroupEntity) {
        withGuardRefresh {
            if (g.boundAlarmGroupId != -1L) {
                db.alarmDao().deleteGroupById(g.boundAlarmGroupId)
            }
            checkInDao.deleteGroup(g)
        }
    }

    fun toggleCheckInGroup(g: CheckInGroupEntity, e: Boolean, r: Boolean) {
        viewModelScope.launch {
            if (e) {
                var targetGroupId = g.boundAlarmGroupId
                if (r && targetGroupId != -1L) {
                    db.alarmDao().deleteGroupById(targetGroupId)
                    targetGroupId = -1L
                }

                if (targetGroupId == -1L) {
                    targetGroupId = db.alarmDao().insertGroup(AlarmGroup(name = g.name, isEnabled = true))
                } else {
                    db.alarmDao().updateGroup(AlarmGroup(id = targetGroupId, name = g.name, isEnabled = true))
                    db.alarmDao().deleteAlarmsByGroupId(targetGroupId)
                }

                val tasks = checkInDao.getTasksByGroup(g.id)
                val alarms = tasks.map { task ->
                    Alarm(
                        groupId = targetGroupId,
                        hour = task.hour,
                        minute = task.minute,
                        label = task.name,
                        ringtonePath = task.ringtonePath ?: g.ringtonePath,
                        isEnabled = true
                    )
                }
                alarms.forEach { db.alarmDao().insertAlarm(it) }
                checkInDao.updateGroup(g.copy(isEnabled = true, boundAlarmGroupId = targetGroupId))
            } else {
                if (g.boundAlarmGroupId != -1L) {
                    db.alarmDao().deleteGroupById(g.boundAlarmGroupId)
                }
                checkInDao.updateGroup(g.copy(isEnabled = false, boundAlarmGroupId = -1L))
            }
            refreshBackgroundMonitor()
        }
    }

    fun duplicateCheckInGroup(g: CheckInGroupEntity) {
        withGuardRefresh {
            val newGroupId = checkInDao.insertGroup(g.copy(id = 0, name = "${g.name} (副本)", isEnabled = false, boundAlarmGroupId = -1))
            val tasks = checkInDao.getTasksByGroup(g.id)
            checkInDao.insertTasks(tasks.map { it.copy(id = 0, groupId = newGroupId) })
        }
    }

    fun setCountdownWarningSeconds(s: Int) { 
        countdownWarningSeconds.value = s 
        prefs.setCountdownWarningSeconds(s)
    }
    fun setCountdownWarningSoundType(t: String) { 
        countdownWarningSoundType.value = t 
        prefs.setCountdownWarningSoundType(t)
    }
    fun setCountdownWarningCustomPath(p: String) { 
        countdownWarningCustomPath.value = p 
        prefs.setCountdownWarningCustomPath(p)
    }
    fun setCountdownWarningTtsText(t: String) { 
        countdownWarningTtsText.value = t 
        prefs.setCountdownWarningTtsText(t)
    }
    fun setTimerFinishSoundType(t: String) { 
        timerFinishSoundType.value = t 
        prefs.setTimerFinishSoundType(t)
    }
    fun setTimerFinishCustomPath(p: String) { 
        timerFinishCustomPath.value = p 
        prefs.setTimerFinishCustomPath(p)
    }
    fun setTimerFinishTtsText(t: String) { 
        timerFinishTtsText.value = t 
        prefs.setTimerFinishTtsText(t)
    }

    fun setTimerHours(h: Int) { timerHours.value = h }
    fun setTimerMinutes(m: Int) { timerMinutes.value = m }
    fun setTimerSeconds(s: Int) { timerSeconds.value = s }
    fun startTimer(d: Int) {
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "START_COUNTDOWN"
            putExtra("COUNTDOWN_TOTAL_SECONDS", d)
        }
        // 持久化结束时间
        val end = System.currentTimeMillis() + d * 1000L
        prefs.setTimerEndMillis(end)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
        isTimerRunning.value = true
        timerRemainingSeconds.value = d
    }

    fun stopTimer() {
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "STOP_COUNTDOWN"
        }
        prefs.removeTimerEndMillis()
            
        getApplication<Application>().startService(intent)
        isTimerRunning.value = false
        timerRemainingSeconds.value = 0
    }

    fun dismissTimerRinging() {
        // 停止 TimerService
        val timerIntent = Intent(getApplication(), com.ccsoft.alarm.service.TimerService::class.java)
        getApplication<Application>().stopService(timerIntent)
        
        // 同时停止 AlarmService 的响铃（如果有）
        val alarmIntent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "STOP_RINGING"
            putExtra("ALARM_ID", -1L)
        }
        prefs.removeTimerEndMillis()

        getApplication<Application>().startService(alarmIntent)
        isTimerRinging.value = false
    }

    fun toggleWifiSync(c: Context, e: Boolean) {
        isWifiServerOn.value = e
        val intent = Intent(c, AlarmService::class.java).apply {
            action = if (e) "START_WIFI_SERVER" else "STOP_WIFI_SERVER"
        }
        c.startService(intent)
    }

    fun syncFromRemote(c: Context, m: com.ccsoft.alarm.alarm.WifiSyncClient.ImportMode, gn: Set<String>? = null) {
        Log.i("SyncDebug", "syncFromRemote: mode=$m, targetIp=${syncTargetIp.value}, selectedGroupsCount=${gn?.size ?: 0}")
        viewModelScope.launch {
            syncStatus.value = SyncStatus.Connecting
            try {
                val client = com.ccsoft.alarm.alarm.WifiSyncClient(c)
                Log.d("SyncDebug", "Calling client.syncFromRemote...")
                val result = client.syncFromRemote(syncTargetIp.value, 8080, m, gn)
                Log.i("SyncDebug", "Sync result received: $result")
                syncStatus.value = when (result) {
                    is com.ccsoft.alarm.alarm.WifiSyncClient.SyncResult.Success -> {
                        Log.i("SyncDebug", "Sync Success! Reloading local UI lists.")
                        SyncStatus.Success(result.message)
                    }
                    is com.ccsoft.alarm.alarm.WifiSyncClient.SyncResult.Error -> {
                        Log.e("SyncDebug", "Sync Error message: ${result.message}")
                        SyncStatus.Error(result.message)
                    }
                }
                if (result is com.ccsoft.alarm.alarm.WifiSyncClient.SyncResult.Success) {
                    loadCustomRingtones()
                    loadLocalRecordings()
                    refreshBackgroundMonitor()
                }
            } catch (e: Exception) {
                Log.e("SyncDebug", "Sync outer exception", e)
                syncStatus.value = SyncStatus.Error("同步异常: ${e.message}")
            }
        }
    }

    fun clearSyncStatus() { syncStatus.value = SyncStatus.Idle }

    fun startDiscovery() {
        Log.i("SyncDebug", "startDiscovery called")
        val nsdManager = getApplication<Application>().getSystemService(Context.NSD_SERVICE) as NsdManager
        stopDiscovery()
        foundDevicesMap.clear()
        discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("SyncDebug", "NSD Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("SyncDebug", "NSD Service found: ${service.serviceName}, type: ${service.serviceType}")
                // 检查服务类型，注意有些系统会自动加点，做包含判断更稳
                if (service.serviceType.contains("_groupalarm")) {
                    Log.d("SyncDebug", "Matching service found, resolving...")
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("SyncDebug", "NSD Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val ip = serviceInfo.host.hostAddress ?: ""
                            val rawName = serviceInfo.serviceName
                            val name = if (rawName.contains("_")) rawName.substringBefore("_") else rawName
                            Log.i("SyncDebug", "NSD Service Resolved: Name=$name, IP=$ip")
                            
                            if (ip.isNotEmpty()) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    // 以 IP 为键防止同名设备覆盖，但在转为列表时确保顺序为 (Name, IP) 对应 UI 的解构
                                    foundDevicesMap[ip] = name
                                    discoveredDevices.value = foundDevicesMap.map { it.value to it.key }
                                    Log.d("SyncDebug", "Updated discoveredDevices list: ${discoveredDevices.value}")
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d("SyncDebug", "NSD Service lost: ${service.serviceName}")
                // 可选：从列表中移除
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("SyncDebug", "NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("SyncDebug", "NSD Start discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("SyncDebug", "NSD Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices("_groupalarm._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("SyncDebug", "Error calling discoverServices", e)
        }
    }

    fun stopDiscovery() {
        val nsdManager = getApplication<Application>().getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e("NSD", "Stop discovery failed", e)
            }
        }
        discoveryListener = null
    }
    fun loadSystemRingtones() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = android.media.RingtoneManager(getApplication())
            manager.setType(android.media.RingtoneManager.TYPE_ALARM)
            val cursor = manager.cursor
            val list = mutableListOf<Pair<String, String>>()
            while (cursor != null && cursor.moveToNext()) {
                val title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX)
                val uri = manager.getRingtoneUri(cursor.position).toString()
                list.add(title to uri)
            }
            systemRingtones.value = list
        }
    }

    fun loadCustomRingtones() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(getApplication<Application>().filesDir, "custom_ringtones")
            if (!dir.exists()) dir.mkdirs()
            val list = dir.listFiles()?.filter { it.extension in listOf("mp3", "wav") }
                ?.map { it.name } ?: emptyList()
            customRingtones.value = list
        }
    }

    fun loadLocalRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = prefs.getRecordingPath()
            val dir = if (path.isNotEmpty()) File(path) else File(getApplication<Application>().filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()
            val list = dir.listFiles()?.filter { it.extension in listOf("mp3", "wav", "m4a") }
                ?.map { it.name to it.absolutePath } ?: emptyList()
            localRecordings.value = list
        }
    }
    fun refreshBackgroundMonitor() {
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "REFRESH_MONITOR"
        }
        getApplication<Application>().startService(intent)
    }
    fun startRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(getApplication<Application>().filesDir, "recordings")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "recording_${System.currentTimeMillis()}.m4a")
                recordingFile = file
                
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(getApplication())
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }.apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                isRecording.value = true
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Start recording failed", e)
            }
        }
    }

    fun stopRecording(n: String): String? {
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording.value = false
            
            val finalFile = File(recordingFile?.parentFile, "$n.m4a")
            recordingFile?.renameTo(finalFile)
            loadLocalRecordings()
            finalFile.absolutePath
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Stop recording failed", e)
            null
        }
    }

    fun cancelRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording.value = false
            recordingFile?.delete()
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Cancel recording failed", e)
        }
    }
    fun deleteRingtone(p: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(p)
            if (file.exists()) {
                file.delete()
                loadCustomRingtones()
            }
        }
    }
    fun checkGroupNameExists(name: String, isAlarm: Boolean): Boolean {
        return runBlocking {
            if (isAlarm) {
                db.alarmDao().getAllGroups().any { it.name == name }
            } else {
                checkInDao.getAllGroups().any { it.name == name }
            }
        }
    }
    fun clearAllLocalData() {
        withGuardRefreshAndMonitor {
            withContext(Dispatchers.IO) {
                // 取消所有闹钟调度
                val allAlarms = db.alarmDao().getAllAlarms()
                for (a in allAlarms) {
                    AlarmScheduler.cancelAlarm(getApplication(), a.id)
                }
                AlarmScheduler.cancelChimeAlarm(getApplication())

                // 清空数据库
                val groups = db.alarmDao().getAllGroups()
                for (g in groups) db.alarmDao().deleteGroup(g)
                
                val cGroups = db.checkinDao().getAllGroups()
                for (cg in cGroups) db.checkinDao().deleteGroup(cg)
                
                val records = db.cloudShareDao().getAllRecords()
                for (r in records) db.cloudShareDao().deleteRecord(r)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
    fun setSyncTargetIp(ip: String) { syncTargetIp.value = ip }
    fun checkForUpdates() {}
    fun performUpdate() {}
    fun setAutoUpdateEnabled(e: Boolean) { 
        autoUpdateEnabled.value = e 
        prefs.setAutoUpdateEnabled(e)
    }
    fun setHourlyChimeMasterEnabled(e: Boolean) {
        hourlyChimeMasterEnabled.value = e
        prefs.setHourlyChimeMasterEnabled(e)
    }
    
    // 服务状态监控
    val isServiceStatusMonitorEnabled = MutableStateFlow(prefs.isServiceStatusMonitorEnabled())
    fun setServiceStatusMonitorEnabled(enabled: Boolean) {
        isServiceStatusMonitorEnabled.value = enabled
        prefs.setServiceStatusMonitorEnabled(enabled)
    }
    
    fun testTts(h: Int, customText: String? = null) {
        val intent = Intent(getApplication(), com.ccsoft.alarm.alarm.AlarmService::class.java).apply {
            action = "TRIGGER_CHIME"
            putExtra("CHIME_HOUR", h)
            putExtra("CHIME_USE_TTS", true)
            putExtra("CHIME_VIBRATE", false)
            putExtra("CHIME_STYLE", 0) // TTS 模式
            if (customText != null) putExtra("CHIME_TEXT", customText)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }
    fun setTtsPitch(p: Float) {
        prefs.setTtsPitch(p)
        com.ccsoft.alarm.alarm.TtsTaskPlayer.pitch = p
    }
    fun setTtsRate(r: Float) {
        prefs.setTtsRate(r)
        com.ccsoft.alarm.alarm.TtsTaskPlayer.speechRate = r
    }
    fun scanTtsEngines() {
        val tts = TextToSpeech(getApplication(), null)
        val engines = tts.engines.toMutableList()
        
        // 手动添加本地推荐tts 虚拟引擎
        try {
            val edgeEngine = TextToSpeech.EngineInfo()
            edgeEngine.name = "本地推荐tts"
            edgeEngine.label = "本地推荐tts"
            engines.add(edgeEngine)
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to add 本地推荐tts engine info", e)
        }

        availableTtsEngines.value = engines
        tts.shutdown()
    }

    fun scanTtsVoices(engine: String) {
        if (engine == "本地推荐tts") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                availableVoices.value = listOf(
                    Voice("zh-CN-XiaoxiaoNeural", Locale.CHINA, 400, 200, true, emptySet()),
                    Voice("zh-CN-YunyangNeural", Locale.CHINA, 400, 200, true, emptySet()),
                    Voice("zh-CN-YunxiNeural", Locale.CHINA, 400, 200, true, emptySet()),
                    Voice("zh-CN-YunjianNeural", Locale.CHINA, 400, 200, true, emptySet()),
                    Voice("zh-CN-XiaoyiNeural", Locale.CHINA, 400, 200, true, emptySet())
                )
            } else {
                availableVoices.value = emptyList()
            }
            return
        }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(getApplication(), { status ->
            if (status == TextToSpeech.SUCCESS) {
                availableVoices.value = tts?.voices?.toList() ?: emptyList()
                tts?.shutdown()
            }
        }, engine)
    }
    fun setTtsEngine(e: String) { 
        selectedTtsEngine.value = e 
        prefs.setTtsEngine(e)
        com.ccsoft.alarm.alarm.TtsTaskPlayer.engineName = e
        scanTtsVoices(e)

        // 引擎切换，必须强制重启 AlarmService 才能应用新引擎实例
        getApplication<Application>().stopService(Intent(getApplication(), com.ccsoft.alarm.alarm.AlarmService::class.java))
    }
    fun setTtsVoice(v: String) { 
        selectedTtsVoiceName.value = v 
        prefs.setTtsVoice(v)
        com.ccsoft.alarm.alarm.TtsTaskPlayer.voiceName = v
        
        // 动态更新正在运行的 AlarmService 中的 TTS 语音
        val intent = Intent(getApplication(), com.ccsoft.alarm.alarm.AlarmService::class.java).apply {
            action = "UPDATE_TTS_VOICE"
            putExtra("TTS_VOICE_NAME", v)
        }
        getApplication<Application>().startService(intent)
    }
    fun setTtsFormat(f: String) {
        ttsFormat.value = f
        prefs.setTtsFormat(f)
        com.ccsoft.alarm.alarm.TtsTaskPlayer.outputFormat = f
        // 格式变了，报时缓存也得重刷
        com.ccsoft.alarm.alarm.ChimeAudioPreloader.rebuildCache(getApplication())
    }
    fun importCheckInGroupConfig(c: Context, u: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                c.contentResolver.openInputStream(u)?.use { isStream ->
                    importSingleCheckInGroup(isStream) { success ->
                        if (success) {
                            loadCustomRingtones()
                            loadLocalRecordings()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Failed to import from URI", e)
            }
        }
    }

    fun exportCheckInGroupToZip(c: Context, g: CheckInGroupEntity, f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tasks = checkInDao.getTasksByGroup(g.id)
                java.io.FileOutputStream(f).use { fos ->
                    exportSingleCheckInGroup(g, tasks, fos)
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Failed to export to File", e)
            }
        }
    }

    fun importLocalAudio(c: Context, u: Uri, n: String): String? {
        return try {
            val dir = File(getApplication<Application>().filesDir, "custom_ringtones")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, n)
            c.contentResolver.openInputStream(u)?.use { isStream ->
                destFile.outputStream().use { os -> isStream.copyTo(os) }
            }
            loadCustomRingtones()
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to import local audio", e)
            null
        }
    }
    fun exportConfig(os: java.io.OutputStream) {
        runBlocking(Dispatchers.IO) {
            try {
                val groups = db.alarmDao().getAllGroups()
                val alarms = db.alarmDao().getAllAlarms()
                val chimes = db.alarmDao().getAllHourlyChimes()
                val checkinGroups = db.checkinDao().getAllGroups()

                val root = JSONObject()
                val groupsArr = JSONArray()
                groups.forEach { g -> groupsArr.put(JSONObject().apply { put("id", g.id); put("name", g.name); put("isEnabled", g.isEnabled) }) }
                root.put("groups", groupsArr)

                val alarmsArr = JSONArray()
                alarms.forEach { a ->
                    alarmsArr.put(JSONObject().apply {
                        put("id", a.id); put("groupId", a.groupId); put("hour", a.hour); put("minute", a.minute)
                        put("daysOfWeek", a.daysOfWeek); put("isEnabled", a.isEnabled); put("label", a.label)
                        put("ringtonePath", a.ringtonePath); put("vibrate", a.vibrate)
                    })
                }
                root.put("alarms", alarmsArr)

                val chimesArr = JSONArray()
                chimes.forEach { c ->
                    chimesArr.put(JSONObject().apply { put("hour", c.hour); put("isEnabled", c.isEnabled); put("useTts", c.useTts); put("vibrate", c.vibrate) })
                }
                root.put("chimes", chimesArr)

                val checkinGroupsArr = JSONArray()
                checkinGroups.forEach { g ->
                    checkinGroupsArr.put(JSONObject().apply {
                        put("id", g.id); put("name", g.name); put("isEnabled", g.isEnabled)
                        put("ringtonePath", g.ringtonePath ?: JSONObject.NULL); put("boundAlarmGroupId", g.boundAlarmGroupId)
                        put("createdAt", g.createdAt)
                    })
                }
                root.put("checkinGroups", checkinGroupsArr)

                val checkinTasksObj = JSONObject()
                checkinGroups.forEach { g ->
                    val tasks = checkInDao.getTasksByGroup(g.id)
                    val tasksArr = JSONArray()
                    tasks.forEach { t ->
                        tasksArr.put(JSONObject().apply {
                            put("id", t.id); put("name", t.name); put("hour", t.hour); put("minute", t.minute)
                            put("orderIndex", t.orderIndex); put("ringtonePath", t.ringtonePath ?: JSONObject.NULL); put("useTts", t.useTts)
                        })
                    }
                    checkinTasksObj.put(g.id.toString(), tasksArr)
                }
                root.put("checkinTasks", checkinTasksObj)

                java.util.zip.ZipOutputStream(os).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry("config.json"))
                    zos.write(root.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    val addedFiles = mutableSetOf<String>()
                    val allRingtones = alarms.map { it.ringtonePath } + checkinGroups.map { it.ringtonePath }
                    allRingtones.filterNotNull().forEach { path ->
                        val file = File(path)
                        if (file.exists() && file.isFile && addedFiles.add(file.canonicalPath)) {
                            zos.putNextEntry(java.util.zip.ZipEntry("ringtones/${file.name}"))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Export failed", e)
            }
        }
    }

    fun importConfig(isS: java.io.InputStream): Boolean {
        return runBlocking(Dispatchers.IO) {
            try {
                val tempZip = File(getApplication<Application>().cacheDir, "restore.zip")
                tempZip.outputStream().use { isS.copyTo(it) }

                val ringtonesDir = File(getApplication<Application>().filesDir, "custom_ringtones")
                if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

                var configJson: String? = null
                java.util.zip.ZipInputStream(tempZip.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "config.json") {
                            configJson = zis.bufferedReader().readText()
                        } else if (entry.name.startsWith("ringtones/")) {
                            val name = entry.name.substringAfter("ringtones/")
                            File(ringtonesDir, name).outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                configJson?.let { jsonStr ->
                    val root = JSONObject(jsonStr)
                    db.alarmDao().getAllGroups().forEach { db.alarmDao().deleteGroup(it) }
                    val idMap = mutableMapOf<Long, Long>()
                    val groupsArr = root.getJSONArray("groups")
                    for (i in 0 until groupsArr.length()) {
                        val g = groupsArr.getJSONObject(i)
                        val newId = db.alarmDao().insertGroup(AlarmGroup(name = g.getString("name"), isEnabled = g.getBoolean("isEnabled")))
                        idMap[g.getLong("id")] = newId
                    }
                    val alarmsArr = root.getJSONArray("alarms")
                    for (i in 0 until alarmsArr.length()) {
                        val a = alarmsArr.getJSONObject(i)
                        val newGroupId = idMap[a.getLong("groupId")] ?: continue
                        var rPath = if (a.isNull("ringtonePath")) null else a.getString("ringtonePath")
                        if (rPath != null && (rPath.contains("/custom_ringtones/") || rPath.contains("/0/"))) {
                            rPath = File(ringtonesDir, rPath.substringAfterLast("/")).absolutePath
                        }
                        db.alarmDao().insertAlarm(Alarm(
                            groupId = newGroupId, hour = a.getInt("hour"), minute = a.getInt("minute"),
                            daysOfWeek = a.getString("daysOfWeek"), isEnabled = a.getBoolean("isEnabled"),
                            label = a.getString("label"), ringtonePath = rPath, vibrate = a.getBoolean("vibrate")
                        ))
                    }
                    val chimesArr = root.getJSONArray("chimes")
                    for (i in 0 until chimesArr.length()) {
                        val c = chimesArr.getJSONObject(i)
                        db.alarmDao().updateHourlyChime(HourlyChime(hour = c.getInt("hour"), isEnabled = c.getBoolean("isEnabled"), useTts = c.getBoolean("useTts"), vibrate = c.getBoolean("vibrate")))
                    }

                    val checkinGroupIdMap = mutableMapOf<Long, Long>()
                    if (root.has("checkinGroups")) {
                        val cGroupsArr = root.getJSONArray("checkinGroups")
                        db.checkinDao().getAllGroups().forEach { db.checkinDao().deleteGroup(it) }
                        for (i in 0 until cGroupsArr.length()) {
                            val g = cGroupsArr.getJSONObject(i)
                            var rPath = if (g.isNull("ringtonePath")) null else g.getString("ringtonePath")
                            if (rPath != null && (rPath.contains("/custom_ringtones/") || rPath.contains("/0/"))) {
                                rPath = File(ringtonesDir, rPath.substringAfterLast("/")).absolutePath
                            }
                            val newId = db.checkinDao().insertGroup(CheckInGroupEntity(
                                name = g.getString("name"), isEnabled = g.getBoolean("isEnabled"),
                                ringtonePath = rPath, boundAlarmGroupId = idMap[g.getLong("boundAlarmGroupId")] ?: -1L,
                                createdAt = if (g.has("createdAt")) g.getLong("createdAt") else System.currentTimeMillis()
                            ))
                            checkinGroupIdMap[g.getLong("id")] = newId
                        }
                    }
                    if (root.has("checkinTasks")) {
                        val cTasksObj = root.getJSONObject("checkinTasks")
                        for (oldIdStr in cTasksObj.keys()) {
                            val newGroupId = checkinGroupIdMap[oldIdStr.toLong()] ?: continue
                            val tasksArr = cTasksObj.getJSONArray(oldIdStr)
                            for (j in 0 until tasksArr.length()) {
                                val t = tasksArr.getJSONObject(j)
                                var rPath = if (t.isNull("ringtonePath")) null else t.getString("ringtonePath")
                                if (rPath != null && (rPath.contains("/custom_ringtones/") || rPath.contains("/0/"))) {
                                    rPath = File(ringtonesDir, rPath.substringAfterLast("/")).absolutePath
                                }
                                db.checkinDao().insertTask(CheckInTaskEntity(
                                    groupId = newGroupId, name = t.getString("name"),
                                    hour = if (t.has("hour")) t.getInt("hour") else 8,
                                    minute = if (t.has("minute")) t.getInt("minute") else 0,
                                    orderIndex = if (t.has("orderIndex")) t.getInt("orderIndex") else 0,
                                    ringtonePath = rPath, useTts = if (t.has("useTts")) t.getBoolean("useTts") else false
                                ))
                            }
                        }
                    }
                    refreshBackgroundMonitor()
                    notifyGuardToRefresh()
                    true
                } ?: false
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Restore failed", e)
                false
            }
        }
    }
    fun importSingleCheckInGroup(isS: java.io.InputStream, cb: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(getApplication<Application>().cacheDir, "import_temp.zip")
                tempFile.outputStream().use { isS.copyTo(it) }
                
                val ringtonesDir = File(getApplication<Application>().filesDir, "custom_ringtones")
                if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

                var configJson: String? = null
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "config.json") {
                            configJson = zis.bufferedReader().readText()
                        } else if (entry.name.startsWith("ringtones/")) {
                            val name = entry.name.substringAfter("ringtones/")
                            File(ringtonesDir, name).outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                configJson?.let {
                    val root = JSONObject(it)
                    val gObj = root.getJSONObject("group")
                    val tasksArr = root.getJSONArray("tasks")
                    
                    val gId = checkInDao.insertGroup(CheckInGroupEntity(
                        name = gObj.getString("name"),
                        ringtonePath = if (gObj.isNull("ringtonePath")) null else gObj.getString("ringtonePath")
                    ))
                    
                    for (i in 0 until tasksArr.length()) {
                        val t = tasksArr.getJSONObject(i)
                        checkInDao.insertTask(CheckInTaskEntity(
                            groupId = gId,
                            name = t.getString("name"),
                            hour = t.getInt("hour"),
                            minute = t.getInt("minute"),
                            orderIndex = t.getInt("orderIndex"),
                            ringtonePath = if (t.isNull("ringtonePath")) null else t.getString("ringtonePath"),
                            useTts = t.getBoolean("useTts")
                        ))
                    }
                    withContext(Dispatchers.Main) { cb(true) }
                } ?: withContext(Dispatchers.Main) { cb(false) }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Import failed", e)
                withContext(Dispatchers.Main) { cb(false) }
            }
        }
    }

    fun exportSingleCheckInGroup(g: CheckInGroupEntity, t: List<CheckInTaskEntity>, os: java.io.OutputStream) {
        val root = JSONObject()
        root.put("type", "single_checkin_group")
        root.put("group", JSONObject().apply {
            put("name", g.name)
            put("ringtonePath", g.ringtonePath)
        })
        val tasksArr = JSONArray()
        t.forEach { task ->
            tasksArr.put(JSONObject().apply {
                put("name", task.name)
                put("hour", task.hour)
                put("minute", task.minute)
                put("orderIndex", task.orderIndex)
                put("ringtonePath", task.ringtonePath)
                put("useTts", task.useTts)
            })
        }
        root.put("tasks", tasksArr)

        java.util.zip.ZipOutputStream(os).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("config.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            
            val added = mutableSetOf<String>()
            (listOf(g.ringtonePath) + t.map { it.ringtonePath }).filterNotNull().forEach { path ->
                val file = File(path)
                if (file.exists() && file.isFile && added.add(file.name)) {
                    zos.putNextEntry(java.util.zip.ZipEntry("ringtones/${file.name}"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
    fun updateChimeDetails(t: Boolean, v: Boolean) {
        viewModelScope.launch {
            val currentChimes = db.alarmDao().getAllHourlyChimes()
            val updated = currentChimes.map { it.copy(useTts = t, vibrate = v) }
            db.alarmDao().insertHourlyChimes(updated)
        }
    }

    suspend fun getLastShareRecordForGroup(id: Long, t: String): CloudShareRecord? = cloudShareDao.getLatestRecord(id, t)
    fun deleteCloudShareRecordWithCloud(r: CloudShareRecord, s: com.ccsoft.alarm.cloud.CloudService) {
        viewModelScope.launch {
            try {
                if (s.deleteConfig(r.shareCode)) {
                    db.cloudShareDao().deleteRecord(r)
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Failed to delete cloud record", e)
            }
        }
    }
    fun shareAlarmGroupToCloud(g: AlarmGroup) {
        viewModelScope.launch {
            cloudShareLoading.value = true
            try {
                val alarms = db.alarmDao().getAlarmsByGroup(g.id)
                val alarmsArr = JSONArray()
                alarms.forEach { a ->
                    alarmsArr.put(JSONObject().apply {
                        put("hour", a.hour)
                        put("minute", a.minute)
                        put("label", a.label)
                        put("daysOfWeek", a.daysOfWeek)
                    })
                }
                val json = cloudService.buildAlarmConfigJson(g.name, alarmsArr)
                val code = cloudService.uploadConfig(json)
                if (code != null) {
                    cloudShareCode.value = code
                    db.cloudShareDao().insertRecord(CloudShareRecord(
                        shareCode = code, groupName = g.name, itemCount = alarms.size,
                        groupType = "alarm", sourceGroupId = g.id
                    ))
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Cloud share failed", e)
            } finally {
                cloudShareLoading.value = false
            }
        }
    }

    fun shareCheckInGroupToCloud(g: CheckInGroupEntity, t: List<CheckInTaskEntity>) {
        viewModelScope.launch {
            cloudShareLoading.value = true
            try {
                val tasksArr = JSONArray()
                t.forEach { task ->
                    tasksArr.put(JSONObject().apply {
                        put("name", task.name)
                        put("hour", task.hour)
                        put("minute", task.minute)
                        put("orderIndex", task.orderIndex)
                    })
                }
                val json = cloudService.buildCheckInConfigJson(g.name, tasksArr)
                val code = cloudService.uploadConfig(json)
                if (code != null) {
                    cloudShareCode.value = code
                    db.cloudShareDao().insertRecord(CloudShareRecord(
                        shareCode = code, groupName = g.name, itemCount = t.size,
                        groupType = "checkin", sourceGroupId = g.id
                    ))
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Cloud share failed", e)
            } finally {
                cloudShareLoading.value = false
            }
        }
    }

    suspend fun importAlarmGroupFromCloud(c: String): Boolean {
        return withContext(Dispatchers.IO) {
            cloudShareLoading.value = true
            try {
                val jsonString = cloudService.downloadConfig(c) ?: return@withContext false
                val root = JSONObject(jsonString)
                if (root.getString("type") != "alarm_group") return@withContext false
                
                val name = root.getString("groupName")
                val alarmsArr = root.getJSONArray("alarms")
                
                val gId = db.alarmDao().insertGroup(AlarmGroup(name = name, isEnabled = true))
                for (i in 0 until alarmsArr.length()) {
                    val a = alarmsArr.getJSONObject(i)
                    db.alarmDao().insertAlarm(Alarm(
                        groupId = gId, hour = a.getInt("hour"), minute = a.getInt("minute"),
                        label = a.getString("label"), daysOfWeek = a.getString("daysOfWeek"),
                        isEnabled = true
                    ))
                }
                cloudImportResult.value = "已成功从云端导入闹钟组: $name"
                refreshBackgroundMonitor()
                notifyGuardToRefresh()
                true
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Cloud import failed", e)
                false
            } finally {
                cloudShareLoading.value = false
            }
        }
    }

    suspend fun importCheckInGroupFromCloud(c: String): Boolean {
        return withContext(Dispatchers.IO) {
            cloudShareLoading.value = true
            try {
                val jsonString = cloudService.downloadConfig(c) ?: return@withContext false
                val parsed = cloudService.parseCheckInConfig(jsonString) ?: return@withContext false
                val name = parsed.first
                val tasksArr = parsed.second
                
                val gId = checkInDao.insertGroup(CheckInGroupEntity(name = name, isEnabled = false))
                for (i in 0 until tasksArr.length()) {
                    val t = tasksArr.getJSONObject(i)
                    checkInDao.insertTask(CheckInTaskEntity(
                        groupId = gId, name = t.getString("name"),
                        hour = t.getInt("hour"), minute = t.getInt("minute"),
                        orderIndex = t.getInt("orderIndex")
                    ))
                }
                cloudImportResult.value = "已成功从云端导入打卡组: $name"
                notifyGuardToRefresh()
                true
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Cloud import failed", e)
                false
            } finally {
                cloudShareLoading.value = false
            }
        }
    }
    fun clearCloudShareCode() { cloudShareCode.value = null }
    fun clearCloudImportResult() { cloudImportResult.value = null }
    fun setCloudService(s: String) {
        val prefs = getApplication<Application>().getSharedPreferences(CloudConfigKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CloudConfigKeys.KEY_SERVICE, s.lowercase()).apply()
        cloudService = com.ccsoft.alarm.cloud.getService(getApplication())
    }
    fun setSupabaseCredentials(u: String, k: String) {
        val prefs = getApplication<Application>().getSharedPreferences(CloudConfigKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CloudConfigKeys.PREF_SUPABASE_URL, u).putString(CloudConfigKeys.PREF_SUPABASE_ANON_KEY, k).apply()
        cloudService = com.ccsoft.alarm.cloud.getService(getApplication())
    }
    fun setFirebaseCredentials(p: String, a: String) {
        val prefs = getApplication<Application>().getSharedPreferences(CloudConfigKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(CloudConfigKeys.PREF_FIREBASE_PROJECT_ID, p).putString(CloudConfigKeys.PREF_FIREBASE_API_KEY, a).apply()
        cloudService = com.ccsoft.alarm.cloud.getService(getApplication())
    }

    suspend fun supabaseSignIn(u: String, p: String): Result<Boolean> = com.ccsoft.alarm.cloud.SupabaseManager.signIn(u, p)
    suspend fun supabaseSignUp(u: String, p: String): Result<Boolean> = com.ccsoft.alarm.cloud.SupabaseManager.signUp(u, p)
    fun supabaseSignOut() { viewModelScope.launch { com.ccsoft.alarm.cloud.SupabaseManager.signOut() } }

    fun setCustomRecordingPath(p: String) { 
        customRecordingPath.value = p 
        prefs.setRecordingPath(p)
        // 同步通知相关模块
        com.ccsoft.alarm.alarm.TtsTaskPlayer.shutdown() // 重建以使新路径生效
        android.widget.Toast.makeText(getApplication(), "录音路径已保存：$p", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun setDatabaseDirectoryPath(p: String) { 
        dbDirectoryPath.value = p 
        prefs.setDatabaseDirPath(p)
        android.widget.Toast.makeText(getApplication(), "数据库目录已保存，请手动重启 App 以生效", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onInit(status: Int) {}

    data class WarningSoundConfig(val countdownWarningSeconds: Int, val countdownWarningSoundType: String, val countdownWarningCustomPath: String, val countdownWarningTtsText: String, val timerFinishSoundType: String, val timerFinishCustomPath: String, val timerFinishTtsText: String)

    sealed class SyncStatus {
        object Idle : SyncStatus()
        object Connecting : SyncStatus()
        data class Success(val message: String) : SyncStatus()
        data class Error(val message: String) : SyncStatus()
    }
}
