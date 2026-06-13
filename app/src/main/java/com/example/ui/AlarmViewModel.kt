package com.example.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.edit
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.media.RingtoneManager
import android.provider.MediaStore
import android.net.Uri
import android.content.ContentValues
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.AlarmScheduler
import com.example.alarm.AlarmService
import com.example.alarm.ChimeAudioPreloader
import com.example.db.*
import com.example.db.CheckInDao
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity
import com.example.cloud.*
import com.example.ui.dialogs.CheckInTaskInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import com.example.tts.BaiduTtsClient
import java.io.File
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

class AlarmViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val repository: AlarmRepository
    private val checkInDao: CheckInDao
    private val cloudShareDao: CloudShareDao
    private var cloudService: CloudService = getService(application)
    private var tts: TextToSpeech? = null
    private val baiduTtsClient = BaiduTtsClient()
    private var isTtsReady = false
    private var pendingTtsText: String? = null  // 等 TTS 就绪后再播放的文本

    // UI 配置相关 (持久化建议后续加入 SharedPreferences)
    private val _appTheme = MutableStateFlow(0) // 0: 暗夜, 1: 森林, 2: 暖阳
    val appTheme: StateFlow<Int> = _appTheme.asStateFlow()

    private val _appLanguage = MutableStateFlow("zh") // "zh", "en"
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _duplicateOffset = MutableStateFlow(10) // 默认复制后加10分钟（已废弃，保留兼容性）
    val duplicateOffset: StateFlow<Int> = _duplicateOffset.asStateFlow()
    
    private val _duplicateOffsetHours = MutableStateFlow(0) // 默认复制后加0小时
    val duplicateOffsetHours: StateFlow<Int> = _duplicateOffsetHours.asStateFlow()
    
    private val _duplicateOffsetMinutes = MutableStateFlow(10) // 默认复制后加10分钟
    val duplicateOffsetMinutes: StateFlow<Int> = _duplicateOffsetMinutes.asStateFlow()

    // ══════════════════════════════════════════
    // 倒计时预警设置（响铃前 X 秒开始预警提示）
    // ══════════════════════════════════════════
    private val _countdownWarningSeconds = MutableStateFlow(120) // 默认提前 2 分钟
    val countdownWarningSeconds: StateFlow<Int> = _countdownWarningSeconds.asStateFlow()

    // sound type: "tick_tock" | "chime_0" | "chime_1" | "chime_2" | "chime_3" | "custom" | "tts"
    private val _countdownWarningSoundType = MutableStateFlow("tick_tock")
    val countdownWarningSoundType: StateFlow<String> = _countdownWarningSoundType.asStateFlow()

    private val _countdownWarningCustomPath = MutableStateFlow("")
    val countdownWarningCustomPath: StateFlow<String> = _countdownWarningCustomPath.asStateFlow()

    private val _countdownWarningTtsText = MutableStateFlow("")
    val countdownWarningTtsText: StateFlow<String> = _countdownWarningTtsText.asStateFlow()

    // ═══ 计时器响铃设置（独立于倒计时预警，但设置放在同一张卡片） ═══
    private val _timerFinishSoundType = MutableStateFlow("tick_tock")
    val timerFinishSoundType: StateFlow<String> = _timerFinishSoundType.asStateFlow()
    private val _timerFinishCustomPath = MutableStateFlow("")
    val timerFinishCustomPath: StateFlow<String> = _timerFinishCustomPath.asStateFlow()
    private val _timerFinishTtsText = MutableStateFlow("")
    val timerFinishTtsText: StateFlow<String> = _timerFinishTtsText.asStateFlow()

    /** 预警音配置聚合体（减少 MainAppContent 参数数量） */
    data class WarningSoundConfig(
        val countdownWarningSeconds: Int = 120,
        val countdownWarningSoundType: String = "tick_tock",
        val countdownWarningCustomPath: String = "",
        val countdownWarningTtsText: String = "",
        val timerFinishSoundType: String = "tick_tock",
        val timerFinishCustomPath: String = "",
        val timerFinishTtsText: String = ""
    )

    // 录音存放路径配置（默认使用应用专属目录，兼容 Android 11+ 分区存储）
    private val _customRecordingPath = MutableStateFlow("")
    val customRecordingPath: StateFlow<String> = _customRecordingPath.asStateFlow()

    // 数据库目录配置（用户可指定公共目录以实现卸载保留数据）
    private val _dbDirectoryPath = MutableStateFlow("")
    val dbDirectoryPath: StateFlow<String> = _dbDirectoryPath.asStateFlow()

    // 自动更新配置
    private val _autoUpdateEnabled = MutableStateFlow(true)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String,
        val body: String
    )

    fun setAutoUpdateEnabled(enabled: Boolean) {
        _autoUpdateEnabled.value = enabled
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_update", enabled).apply()
        if (enabled) checkForUpdates()
    }

    private val _downloadProgress = MutableStateFlow(-1f) // -1 表示未在下载，0-1 表示进度
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    fun checkForUpdates(isManual: Boolean = false) {
        if (!isManual && !_autoUpdateEnabled.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("开始检查更新...")
                // 请在此处替换为您真实的 GitHub 用户名和仓库名
                val owner = "ndk2000"
                val repo = "alarm"
                val url = java.net.URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                addLog("请求URL: $url")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                addLog("连接超时设置: 10000ms")
                
                addLog("正在连接服务器...")
                if (conn.responseCode == 200) {
                    addLog("服务器响应成功 (200)")
                    val response = conn.inputStream.bufferedReader().readText()
                    addLog("响应数据长度: ${response.length} 字符")
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name")
                    val body = json.getString("body")
                    addLog("解析到版本标签: $tagName")
                    val assets = json.getJSONArray("assets")
                    addLog("找到 ${assets.length()} 个资源文件")
                    var downloadUrl = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.getString("name")
                        addLog("资源 $i: $assetName")
                        if (assetName.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            addLog("找到APK下载链接")
                            break
                        }
                    }

                    val currentVersion = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName
                    addLog("当前应用版本: $currentVersion")
                    
                    if (currentVersion != null && tagName != currentVersion && downloadUrl.isNotEmpty()) {
                        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                        val skipTag = prefs.getString("skip_update_tag", "")
                        if (skipTag != tagName) {
                            _updateInfo.value = UpdateInfo(tagName, downloadUrl, body)
                            addLog("发现新版本: $tagName")
                        } else {
                            addLog("用户已选择忽略版本: $tagName")
                        }
                    } else {
                        addLog("当前已是最新版本 ($currentVersion)")
                    }
                } else {
                    addLog("服务器响应失败: ${conn.responseCode}")
                }
            } catch (e: Exception) {
                addLog("检查更新失败: ${e.message}")
                addLog("错误详情: ${e.stackTraceToString()}")
            }
        }
    }

    fun performUpdate() {
        val info = _updateInfo.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addLog("正在启动自动更新...")
                _downloadProgress.value = 0f
                val url = java.net.URL(info.downloadUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                val length = conn.contentLength
                val apkFile = File(getApplication<Application>().cacheDir, "update.apk")
                
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var total = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            total += read
                            if (length > 0) {
                                _downloadProgress.value = total.toFloat() / length
                            }
                        }
                    }
                }
                _downloadProgress.value = 1f
                addLog("下载完成，触发系统安装...")
                installApk(apkFile)
                _downloadProgress.value = -1f
            } catch (e: Exception) {
                addLog("下载更新失败: ${e.message}")
                _downloadProgress.value = -1f
            }
        }
    }

    fun skipUpdate(tagName: String) {
        _updateInfo.value = null
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("skip_update_tag", tagName).apply()
        addLog("忽略版本: $tagName")
    }

    private fun installApk(file: File) {
        try {
            val context = getApplication<Application>()
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            addLog("调起安装程序失败: ${e.message}")
        }
    }

    fun setCloudService(service: String) {
        getApplication<Application>().selectService(service)
        cloudService = getService(getApplication())
        addLog("切换云端服务为: $service")
    }

    fun setSupabaseCredentials(url: String, anonKey: String) {
        getApplication<Application>().setSupabaseCredentials(url, anonKey)
        if (getApplication<Application>().getSelectedService() == "supabase") {
            cloudService = getService(getApplication())
            addLog("更新 Supabase 凭据并重新加载服务")
        }
    }

    fun setFirebaseCredentials(projectId: String, apiKey: String) {
        getApplication<Application>().setFirebaseCredentials(projectId, apiKey)
        if (getApplication<Application>().getSelectedService() == "firebase") {
            cloudService = getService(getApplication())
            addLog("更新 Firebase 凭据并重新加载服务")
        }
    }

    fun setCustomRecordingPath(path: String) {
        _customRecordingPath.value = path
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("recording_path", path).apply()
        loadCustomRingtones() // 路径改变，刷新列表
    }

    fun setDatabaseDirectoryPath(path: String) {
        _dbDirectoryPath.value = path
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("database_dir_path", path).apply()
        addLog("数据库目录已更新: $path")
    }

    // 计时器 (倒计时闹钟) 相关状态
    private val _timerRemainingSeconds = MutableStateFlow(0)
    val timerRemainingSeconds: StateFlow<Int> = _timerRemainingSeconds.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private val _isTimerRinging = MutableStateFlow(false)
    val isTimerRinging: StateFlow<Boolean> = _isTimerRinging.asStateFlow()

    // 计时器拨盘输入值（跨 Tab 切换保持）
    private val _timerHours = MutableStateFlow(0)
    val timerHours: StateFlow<Int> = _timerHours.asStateFlow()
    private val _timerMinutes = MutableStateFlow(0)
    val timerMinutes: StateFlow<Int> = _timerMinutes.asStateFlow()
    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private var timerJobInstance: Job? = null

    fun setTheme(theme: Int) { 
        _appTheme.value = theme
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("app_theme", theme).apply()

    }
    fun setLanguage(lang: String) { 
        _appLanguage.value = lang
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", lang).apply()

    }
    fun setDuplicateOffset(offset: Int) { 
        _duplicateOffset.value = offset
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("duplicate_offset", offset).apply()

    }
    fun setDuplicateOffsetHours(hours: Int) { 
        _duplicateOffsetHours.value = hours
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("duplicate_offset_hours", hours).apply()

    }
    fun setDuplicateOffsetMinutes(minutes: Int) { 
        _duplicateOffsetMinutes.value = minutes
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("duplicate_offset_minutes", minutes).apply()

    }
    fun setTimerHours(h: Int) {
        _timerHours.value = h.coerceIn(0, 23)
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("timer_hours", _timerHours.value).apply()
    }
    fun setTimerMinutes(m: Int) {
        _timerMinutes.value = m.coerceIn(0, 59)
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("timer_minutes", _timerMinutes.value).apply()
    }
    fun setTimerSeconds(s: Int) {
        _timerSeconds.value = s.coerceIn(0, 59)
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("timer_seconds", _timerSeconds.value).apply()
    }

    // ═══ 倒计时预警设置 ═══
    fun setCountdownWarningSeconds(sec: Int) {
        _countdownWarningSeconds.value = sec.coerceIn(10, 3600)
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putInt("countdown_warning_seconds", _countdownWarningSeconds.value).apply()
    }
    fun setCountdownWarningSoundType(type: String) {
        _countdownWarningSoundType.value = type
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("countdown_warning_sound_type", type).apply()
    }
    fun setCountdownWarningCustomPath(path: String) {
        _countdownWarningCustomPath.value = path
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("countdown_warning_custom_path", path).apply()
    }
    fun setCountdownWarningTtsText(text: String) {
        _countdownWarningTtsText.value = text
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("countdown_warning_tts_text", text).apply()
    }

    // ═══ 计时器响铃设置 ═══
    fun setTimerFinishSoundType(type: String) {
        _timerFinishSoundType.value = type
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("timer_finish_sound_type", type).apply()
    }
    fun setTimerFinishCustomPath(path: String) {
        _timerFinishCustomPath.value = path
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("timer_finish_custom_path", path).apply()
    }
    fun setTimerFinishTtsText(text: String) {
        _timerFinishTtsText.value = text
        getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("timer_finish_tts_text", text).apply()
    }

    // 内部调试日志记录
    private val _debugLogs = MutableStateFlow<List<String>>(listOf("应用启动，调试日志已开启"))
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    // TTS 引擎与语音选择
    private val _availableTtsEngines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val availableTtsEngines: StateFlow<List<EngineInfo>> = _availableTtsEngines.asStateFlow()

    private val _selectedTtsEngine = MutableStateFlow("")
    val selectedTtsEngine: StateFlow<String> = _selectedTtsEngine.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private val _selectedTtsVoiceName = MutableStateFlow("")
    val selectedTtsVoiceName: StateFlow<String> = _selectedTtsVoiceName.asStateFlow()

    // 在线 TTS 配置（百度语音合成）
    private val _useOnlineTts = MutableStateFlow(false)
    val useOnlineTts: StateFlow<Boolean> = _useOnlineTts.asStateFlow()

    private val _baiduApiKey = MutableStateFlow("")
    val baiduApiKey: StateFlow<String> = _baiduApiKey.asStateFlow()

    private val _baiduSecretKey = MutableStateFlow("")
    val baiduSecretKey: StateFlow<String> = _baiduSecretKey.asStateFlow()

    private val _baiduVoice = MutableStateFlow(0)
    val baiduVoice: StateFlow<Int> = _baiduVoice.asStateFlow()

    private val _isPlayingOnlineTts = MutableStateFlow(false)
    val isPlayingOnlineTts: StateFlow<Boolean> = _isPlayingOnlineTts.asStateFlow()

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val fullMsg = "[$timestamp] $message"
        _debugLogs.value = (_debugLogs.value + fullMsg).takeLast(50) // 保留最后50条
        android.util.Log.d("AlarmDebug", fullMsg)
    }

    private val _groups = MutableStateFlow<List<AlarmGroup>>(emptyList())
    val groups: StateFlow<List<AlarmGroup>> = _groups.asStateFlow()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _chimes = MutableStateFlow<List<HourlyChime>>(emptyList())
    val chimes: StateFlow<List<HourlyChime>> = _chimes.asStateFlow()

    private val _checkInGroups = MutableStateFlow<List<CheckInGroupEntity>>(emptyList())
    val checkInGroups: StateFlow<List<CheckInGroupEntity>> = _checkInGroups.asStateFlow()

    private val _checkInTasksMap = MutableStateFlow<Map<Long, List<CheckInTaskEntity>>>(emptyMap())
    val checkInTasksMap: StateFlow<Map<Long, List<CheckInTaskEntity>>> = _checkInTasksMap.asStateFlow()

    private val _isLoadingCheckIn = MutableStateFlow(false)
    val isLoadingCheckIn: StateFlow<Boolean> = _isLoadingCheckIn.asStateFlow()

    private val _customRingtones = MutableStateFlow<List<String>>(emptyList())
    val customRingtones: StateFlow<List<String>> = _customRingtones.asStateFlow()

    private val _systemRingtones = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val systemRingtones: StateFlow<List<Pair<String, String>>> = _systemRingtones.asStateFlow()

    // 存放扫描发现的系统录音机文件列表 (名称, 绝对路径或URI)
    private val _localRecordings = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val localRecordings: StateFlow<List<Pair<String, String>>> = _localRecordings.asStateFlow()

    private val _isWifiServerOn = MutableStateFlow(false)
    val isWifiServerOn: StateFlow<Boolean> = _isWifiServerOn.asStateFlow()

    // ─── 远程同步相关状态 ───
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncTargetIp = MutableStateFlow("")
    val syncTargetIp: StateFlow<String> = _syncTargetIp.asStateFlow()

    // ─── NSD 自动发现相关 ───
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val SERVICE_TYPE = "_groupalarm._tcp."

    private val _discoveredDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val discoveredDevices: StateFlow<List<Pair<String, String>>> = _discoveredDevices.asStateFlow()

    sealed class SyncStatus {
        data object Idle : SyncStatus()
        data object Connecting : SyncStatus()
        data class Success(val message: String) : SyncStatus()
        data class Error(val message: String) : SyncStatus()
    }

    fun setSyncTargetIp(ip: String) {
        _syncTargetIp.value = ip
    }

    fun syncFromRemote(context: Context, importMode: com.example.alarm.WifiSyncClient.ImportMode = com.example.alarm.WifiSyncClient.ImportMode.CLEAR, selectedGroupNames: Set<String>? = null) {
        val ip = _syncTargetIp.value.trim()
        if (ip.isEmpty()) {
            _syncStatus.value = SyncStatus.Error("请输入 IP 地址")
            return
        }
        // 事前检查外部存储权限（Android 11+ 需要 MANAGE_EXTERNAL_STORAGE）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                _syncStatus.value = SyncStatus.Error("缺少「所有文件访问权限」，请在系统设置中开启后重试")
                return
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                _syncStatus.value = SyncStatus.Error("缺少存储写入权限，请在应用权限设置中开启后重试")
                return
            }
        }
        _syncStatus.value = SyncStatus.Connecting
        viewModelScope.launch {
            val client = com.example.alarm.WifiSyncClient(context)
            val result = client.syncFromRemote(ip, importMode = importMode, selectedGroupNames = selectedGroupNames)
            when (result) {
                is com.example.alarm.WifiSyncClient.SyncResult.Success -> {
                    // 刷新数据
                    addLog("远程同步成功: ${result.message}")
                    loadCustomRingtones()
                    refreshBackgroundMonitor()
                    _syncStatus.value = SyncStatus.Success(result.message)
                }
                is com.example.alarm.WifiSyncClient.SyncResult.Error -> {
                    addLog("远程同步失败: ${result.message}")
                    _syncStatus.value = SyncStatus.Error(result.message)
                }
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = SyncStatus.Idle
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        addLog("开始自动搜寻局域网设备...")
        nsdManager = getApplication<Application>().getSystemService(Context.NSD_SERVICE) as NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NSD", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("NSD", "Service discovery success: $service")
                if (service.serviceType == SERVICE_TYPE) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("NSD", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("NSD", "Resolve Succeeded. $serviceInfo")
                            val host = serviceInfo.host.hostAddress ?: ""
                            val name = serviceInfo.serviceName
                            
                            val currentList = _discoveredDevices.value.toMutableList()
                            if (currentList.none { it.second == host }) {
                                currentList.add(Pair(name, host))
                                _discoveredDevices.value = currentList
                                addLog("发现新设备: $name ($host)")
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("NSD", "service lost: $service")
                val currentList = _discoveredDevices.value.toMutableList()
                currentList.removeAll { it.first == service.serviceName }
                _discoveredDevices.value = currentList
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("NSD", "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            nsdManager?.stopServiceDiscovery(it)
        }
        discoveryListener = null
        nsdManager = null
        _discoveredDevices.value = emptyList()
        addLog("停止搜寻设备")
    }

    // 录制相关状态控制
    private var mediaRecorder: MediaRecorder? = null
    private var tempRecordFile: File? = null
    private var timerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0) // 录音时长（单位：秒）
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    init {
        addLog("正在启动 TTS 引擎...")
        
        // 从 SharedPreferences 恢复配置
        val settings = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        // 恢复主题
        val savedTheme = settings.getInt("app_theme", 0)
        _appTheme.value = savedTheme
        
        // 恢复语言
        val savedLanguage = settings.getString("app_language", "zh") ?: "zh"
        _appLanguage.value = savedLanguage
        
        // 恢复复制延后
        val savedOffsetHours = settings.getInt("duplicate_offset_hours", 0)
        _duplicateOffsetHours.value = savedOffsetHours
        val savedOffsetMinutes = settings.getInt("duplicate_offset_minutes", 10)
        _duplicateOffsetMinutes.value = savedOffsetMinutes
        
        // 恢复录音路径（默认使用公共存储根目录下的 0/ 文件夹，卸载保留且排序靠前）
        val savedPath = settings.getString("recording_path", "")
        if (!savedPath.isNullOrEmpty()) {
            _customRecordingPath.value = savedPath
        } else {
            val defaultDir = android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
            _customRecordingPath.value = defaultDir
        }
        val savedDbPath = settings.getString("database_dir_path", "") ?: ""
        _dbDirectoryPath.value = savedDbPath
        // 恢复计时器上次拨盘值
        _timerHours.value = settings.getInt("timer_hours", 0)
        _timerMinutes.value = settings.getInt("timer_minutes", 0)
        _timerSeconds.value = settings.getInt("timer_seconds", 0)

        // 恢复计时器运行状态（关 App 后重新打开时）
        val timerStatePrefs = getApplication<Application>().getSharedPreferences("timer_state", Context.MODE_PRIVATE)
        val timerEndMillis = timerStatePrefs.getLong("timer_end_millis", 0L)
        val now = System.currentTimeMillis()
        if (timerEndMillis > now) {
            // 计时器还在跑，恢复 UI 状态
            val remainingSec = ((timerEndMillis - now) / 1000).toInt()
            _timerRemainingSeconds.value = remainingSec
            _isTimerRunning.value = true
            timerJobInstance?.cancel()
            timerJobInstance = viewModelScope.launch {
                while (_timerRemainingSeconds.value > 0) {
                    delay(1000)
                    _timerRemainingSeconds.value -= 1
                }
                _isTimerRunning.value = false
                _isTimerRinging.value = true
            }
        }

        _autoUpdateEnabled.value = settings.getBoolean("auto_update", false)

        // 恢复倒计时预警设置
        _countdownWarningSeconds.value = settings.getInt("countdown_warning_seconds", 120)
        _countdownWarningSoundType.value = settings.getString("countdown_warning_sound_type", "tick_tock") ?: "tick_tock"
        _countdownWarningCustomPath.value = settings.getString("countdown_warning_custom_path", "") ?: ""
        _countdownWarningTtsText.value = settings.getString("countdown_warning_tts_text", "") ?: ""

        // 恢复计时器响铃设置
        _timerFinishSoundType.value = settings.getString("timer_finish_sound_type", "tick_tock") ?: "tick_tock"
        _timerFinishCustomPath.value = settings.getString("timer_finish_custom_path", "") ?: ""
        _timerFinishTtsText.value = settings.getString("timer_finish_tts_text", "") ?: ""

        if (_autoUpdateEnabled.value) {
            checkForUpdates()
        }

        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        val savedEngine = prefs.getString("tts_engine", "") ?: ""
        val savedVoice = prefs.getString("tts_voice", "") ?: ""
        _selectedTtsEngine.value = savedEngine
        _selectedTtsVoiceName.value = savedVoice
        // 同步引擎和语音到 TtsTaskPlayer（全局 TTS 播放器）
        com.example.alarm.TtsTaskPlayer.engineName = savedEngine
        com.example.alarm.TtsTaskPlayer.voiceName = savedVoice
        addLog("恢复 TTS 引擎: $savedEngine, 语音: $savedVoice")

        // 恢复在线 TTS 配置
        val ttsPrefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        _useOnlineTts.value = ttsPrefs.getBoolean("use_online_tts", false)
        _baiduApiKey.value = ttsPrefs.getString("baidu_api_key", "") ?: ""
        _baiduSecretKey.value = ttsPrefs.getString("baidu_secret_key", "") ?: ""
        _baiduVoice.value = ttsPrefs.getInt("baidu_voice", 0)
        
        // 注意：不在此处扫描引擎！某些设备（如 Realme/Oppo）上，提前创建临时 TTS 实例
        // 会与后续的主 TTS 实例冲突，导致 onInit 回调永远不触发。
        // 引擎扫描已移到 onInit 成功回调中执行。
        
        // 如果保存了引擎包名，则使用该引擎创建 TTS 实例，否则使用默认引擎
        val enginePackage = if (savedEngine.isNotEmpty()) savedEngine else null
        tts = TextToSpeech(application, this, enginePackage)
        val defaultEngine = tts?.defaultEngine ?: "未知"
        addLog("系统默认 TTS 引擎: $defaultEngine")

        // 注册发音进度监听，诊断引擎是否真正发音
        try {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(uttId: String?) {
                    lastSpeakStarted = true
                    lastSpeakTimeMs = System.currentTimeMillis()
                    addLog("✓ TTS 引擎确认开始发音 (utterance: $uttId)")
                }
                override fun onDone(uttId: String?) {
                    addLog("✓ TTS 引擎发音完成 (utterance: $uttId)")
                }
                override fun onError(uttId: String?) {
                    addLog("✗ TTS 引擎发音出错 (utterance: $uttId)")
                }
            })
        } catch (_: Exception) {}

        val db = AlarmDatabase.getDatabase(application, viewModelScope)
        val dao = db.alarmDao()
        repository = AlarmRepository(dao, db.checkinDao())
        checkInDao = db.checkinDao()
        cloudShareDao = db.cloudShareDao()

        viewModelScope.launch {
            repository.allGroups.collect { _groups.value = it }
        }
        viewModelScope.launch {
            checkInDao.getAllGroupsFlow().collect { _checkInGroups.value = it }
        }
        viewModelScope.launch {
            delay(100) // 等待首次分组数据
            loadAllCheckInTasks()
        }
        viewModelScope.launch {
            repository.allAlarms.collect { _alarms.value = it }
        }
        viewModelScope.launch {
            repository.allHourlyChimes.collect { _chimes.value = it }
        }
        viewModelScope.launch {
            cloudShareDao.getAllRecordsFlow().collect { _cloudShareRecords.value = it }
        }
        loadSystemRingtones()
        loadLocalRecordings() // 在初始化时自动触发读取本地录音机保存的列表

        // 启动后台闹钟监控服务，在状态栏显示闹钟倒计时通知
        refreshBackgroundMonitor()

        // 注册计时器关闭广播（AlarmService 停止计时器响铃时重置 UI 状态）
        val timerDismissedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                _isTimerRunning.value = false
                _isTimerRinging.value = false
                _timerRemainingSeconds.value = 0
            }
        }
        getApplication<Application>().registerReceiver(
            timerDismissedReceiver,
            IntentFilter("com.example.TIMER_DISMISSED")
        )
    }

    // 扫描系统录音机路径，列出所有保存的录音文件，包含小米、华为、OPPO、VIVO等主流机型的深度适配
    fun loadLocalRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Pair<String, String>>()
            
            // 递归扫描工具方法：检索深度最大为 2 级，避免耗费过多计算资源或产生堆栈溢出
            fun scanDirectoryRecursive(directory: File, currentDepth: Int, maxDepth: Int) {
                if (currentDepth > maxDepth) return
                try {
                    if (!directory.exists() || !directory.isDirectory) return
                    directory.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            val ext = file.extension.lowercase()
                            // 仅收集录音机、麦克风录音常见的声音扩展格式
                            if (ext in listOf("mp3", "wav", "m4a", "amr", "ogg", "aac", "flac", "3gp")) {
                                val pair = Pair(file.name, file.absolutePath)
                                if (list.none { it.second == file.absolutePath }) {
                                    list.add(pair)
                                }
                            }
                        } else if (file.isDirectory) {
                            scanDirectoryRecursive(file, currentDepth + 1, maxDepth)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            val standardRecordingsDir = if (Build.VERSION.SDK_INT >= 31) {
                android.os.Environment.getExternalStoragePublicDirectory("Recordings")
            } else {
                File(android.os.Environment.getExternalStorageDirectory(), "Recordings")
            }

            // 【第一步】：直接全盘扫描各大厂商（如小米、华为、VIVO、OPPO、魅族等）默认的录音文件夹物理存储路径
            val targetFolders = listOf(
                // 1. Android 标准录音存放目录
                standardRecordingsDir,
                File("/storage/emulated/0/Recordings"),
                
                // 2. 小米与红米系统 (MIUI / HyperOS) 独有核心录音机目录：
                // 包含：普通录音、通话录音 (call_rec)、应用录音 (app_rec) 等
                File("/storage/emulated/0/MIUI/sound_recorder"),
                File("/storage/emulated/0/MIUI/sound_recorder/call_rec"),
                File("/storage/emulated/0/MIUI/sound_recorder/app_rec"),
                File("/storage/emulated/0/Recordings/SoundRecorder"), // MIUI新版本存放位置
                
                // 3. 华为 / 荣耀 (EMUI / MagicOS) 专用录音目录
                File("/storage/emulated/0/Recordings/Recorder"),
                File("/storage/emulated/0/Huawei/Recorder"),
                File("/storage/emulated/0/Sounds"),
                
                // 4. OPPO / 一加 / 真我 (ColorOS) 与 VIVO / iQOO (OriginOS) 常见录音目录
                File("/storage/emulated/0/Recorder"),
                File("/storage/emulated/0/SoundRecorder"),
                File("/storage/emulated/0/sound_recorder"),

                // 5. 新版 MIUI 系统录音机 (Android/data 目录) —— 此路径因 Android 11+ Scoped Storage 限制，
                //    无法通过 File API 直接读取，只能通过下方的 MediaStore 查询（contains com.android.soundrecorder）获取
            )

            // 开始深挖这些文件夹
            for (folder in targetFolders) {
                try {
                    if (folder.exists() && folder.isDirectory) {
                        scanDirectoryRecursive(folder, 1, 2)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 【第二步】：高版本 Android 必须依靠 MediaStore 内容协作者
            // MediaStore 不受 Scoped Storage (分区存储) 物理路径隐藏机制的多重限制，是跨手机机型兼容的终极利器
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATA
                )
                
                // 寻找所有带有“录音”或“MIUI”以及常见录音文件夹标记的音频库记录
                val cursor = contentResolver.query(uri, projection, null, null, null)
                if (cursor != null) {
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: ""
                        val path = cursor.getString(dataColumn) ?: ""
                        if (path.isNotEmpty() && (
                            path.contains("sound_recorder", ignoreCase = true) ||
                            path.contains("com.android.soundrecorder", ignoreCase = true) ||
                            path.contains("MIUI", ignoreCase = true) ||
                            path.contains("Recordings", ignoreCase = true) ||
                            path.contains("SoundRecorder", ignoreCase = true) ||
                            path.contains("call_rec", ignoreCase = true) ||
                            path.contains("app_rec", ignoreCase = true) ||
                            path.contains("Recorder", ignoreCase = true) ||
                            path.contains("Sound_Recorder", ignoreCase = true)
                        )) {
                            val ext = path.substringAfterLast(".", "").lowercase()
                            if (ext in listOf("mp3", "wav", "m4a", "amr", "ogg", "aac", "flac", "3gp")) {
                                if (list.none { it.second == path }) {
                                    list.add(Pair(name, path))
                                }
                            }
                        }
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            // 【第三步】：如果发现目标机型并未存放任何录音，则主动为其自动创建系统常规 /Recordings 文件夹，指引系统的标准存放渠道
            if (list.isEmpty()) {
                try {
                    val rootRecordDir = File("/storage/emulated/0/Recordings")
                    if (!rootRecordDir.exists()) {
                        rootRecordDir.mkdirs()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 按照录音文件名或路径进行美化排序并派发更新
            list.sortBy { it.first.lowercase() }
            _localRecordings.value = list
        }
    }

    fun loadSystemRingtones() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Pair<String, String>>()
            try {
                val ringtoneManager = RingtoneManager(getApplication()).apply {
                    setType(RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                }
                val cursor = ringtoneManager.cursor
                if (cursor != null) {
                    val count = cursor.count
                    for (i in 0 until count) {
                        if (cursor.moveToPosition(i)) {
                            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                            val uri = ringtoneManager.getRingtoneUri(i)?.toString()
                            if (uri != null) {
                                list.add(Pair(title, uri))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            _systemRingtones.value = list
        }
    }

    fun deleteRingtone(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(_customRecordingPath.value)
            val file = File(dir, name)
            if (file.exists() && file.delete()) {
                addLog("已删除铃声: $name")
                loadCustomRingtones()
            }
        }
    }

    fun loadCustomRingtones() {
        viewModelScope.launch(Dispatchers.IO) {
            val ringtonesDir = File(_customRecordingPath.value)
            if (!ringtonesDir.exists()) {
                ringtonesDir.mkdirs()
            }
            val files = ringtonesDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in listOf("mp3", "wav", "m4a", "amr", "ogg", "aac", "flac", "3gp") }
                ?.map { it.name } ?: emptyList()
            _customRingtones.value = files
        }
    }

    // 开始录制新铃声
    fun startRecording() {
        viewModelScope.launch {
            try {
                val ringtonesDir = File(_customRecordingPath.value)
                if (!ringtonesDir.exists()) {
                    ringtonesDir.mkdirs()
                }
                // 使用唯一前缀，随后重命名
                val tempFile = File.createTempFile("temp_rec_", ".m4a", ringtonesDir)
                tempRecordFile = tempFile

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(getApplication())
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(tempFile.absolutePath)
                    prepare()
                    start()
                }
                
                mediaRecorder = recorder
                _isRecording.value = true
                _recordingDuration.value = 0
                
                // 开启录音秒数累加监听器
                timerJob?.cancel()
                timerJob = viewModelScope.launch {
                    while (true) {
                        delay(1000)
                        _recordingDuration.value += 1
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isRecording.value = false
                // 通知用户录音启动失败
                val app = getApplication<Application>()
                val msg = when {
                    e is java.io.IOException && e.message?.contains("Permission") == true ->
                        "无法写入存储目录，请检查「所有文件访问权限」是否已开启"
                    else -> app.getString(com.example.R.string.recording_save_failed) + ": " + e.localizedMessage
                }
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // 停止并保存录制铃声，返回生成的绝对物理路径
    fun stopRecording(fileName: String): String? {
        timerJob?.cancel()
        timerJob = null
        _isRecording.value = false
        
        var resultPath: String? = null
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }

        try {
            val tempFile = tempRecordFile
            if (tempFile != null && tempFile.exists()) {
                val ringtonesDir = File(_customRecordingPath.value)
                // 移除文件名中的非法字符，同时保留中文、英文和数字
                val cleanName = fileName.replace("[^\\u4e00-\\u9fa5a-zA-Z0-9._-]".toRegex(), "_")
                val finalName = if (cleanName.endsWith(".m4a", ignoreCase = true)) cleanName else "$cleanName.m4a"
                val destFile = File(ringtonesDir, finalName)
                
                if (destFile.exists()) {
                    destFile.delete()
                }
                
                if (tempFile.renameTo(destFile)) {
                    resultPath = destFile.absolutePath
                } else {
                    // 应对设备重命名失败容错方案
                    tempFile.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                    resultPath = destFile.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempRecordFile = null
        }
        
        loadCustomRingtones()
        return resultPath
    }

    // 取消并丢弃当前正在录制的声音
    fun cancelRecording() {
        timerJob?.cancel()
        timerJob = null
        _isRecording.value = false
        _recordingDuration.value = 0
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }
        try {
            tempRecordFile?.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempRecordFile = null
        }
    }

    fun toggleGroup(group: AlarmGroup, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedGroup = group.copy(isEnabled = isEnabled)
            repository.updateGroup(updatedGroup)
            
            // Re-schedule all alarms in this group
            val groupAlarms = repository.getAlarmsByGroup(group.id)
            groupAlarms.forEach { alarm ->
                AlarmScheduler.scheduleAlarm(getApplication(), alarm, updatedGroup)
            }
            refreshBackgroundMonitor()
        }

    }

    // Toggle individual alarm
    fun toggleAlarm(alarm: Alarm, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAlarm = alarm.copy(isEnabled = isEnabled)
            repository.updateAlarm(updatedAlarm)

            val parentGroup = _groups.value.find { it.id == alarm.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), updatedAlarm, parentGroup)
            refreshBackgroundMonitor()
        }

    }

    // Toggle hourly chime hour
    fun toggleHourlyChime(chime: HourlyChime, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedChime = chime.copy(isEnabled = isEnabled)
            repository.updateHourlyChime(updatedChime)
            
            // Reschedule hourly alarm check stream
            AlarmScheduler.scheduleNextHourlyChime(getApplication())
        }

    }

    fun updateChimeDetails(useTts: Boolean, vibrate: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedChimes = _chimes.value.map {
                it.copy(useTts = useTts, vibrate = vibrate)
            }
            updatedChimes.forEach {
                repository.updateHourlyChime(it)
            }
        }

    }

    fun updateGroup(group: AlarmGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateGroup(group)
        }
    }

    fun addGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertGroup(AlarmGroup(name = name, isEnabled = true))
        }

    }

    fun clearAllLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 删除所有闹钟组（会同时删除组内闹钟）
                val allGroups = repository.getGroupList()
                for (g in allGroups) {
                    deleteGroup(g)
                }
                // 删除所有打卡组和任务
                val allCheckInGroups = checkInDao.getAllGroups()
                for (g in allCheckInGroups) {
                    val tasks = checkInDao.getTasksByGroup(g.id)
                    for (t in tasks) checkInDao.deleteTask(t)
                    checkInDao.deleteGroup(g)
                }
                // 删除云端分享记录
                val currentRecords = _cloudShareRecords.value
                for (r in currentRecords) {
                    cloudShareDao.deleteRecord(r)
                }
                addLog("clearAllLocalData: done")
            } catch (e: Exception) {
                addLog("clearAllLocalData failed: ${e.message}")
            }
        }
    }

    /** 查询本地数据库是否已有同名群组（用于云端同步去重） */
    suspend fun checkGroupNameExists(groupName: String, isAlarmGroup: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            if (isAlarmGroup) {
                repository.getGroupList().any { it.name == groupName }
            } else {
                checkInDao.getAllGroups().any { it.name == groupName }
            }
        }
    }

    fun deleteGroup(group: AlarmGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            // Cancel and deleted alarms inside first
            val groupAlarms = repository.getAlarmsByGroup(group.id)
            groupAlarms.forEach {
                AlarmScheduler.cancelAlarm(getApplication(), it.id)
            }
            repository.deleteGroup(group)
            refreshBackgroundMonitor()
        }

    }

    fun addAlarm(
        groupId: Long,
        hour: Int,
        minute: Int,
        daysOfWeek: String,
        label: String,
        ringtonePath: String?,
        vibrate: Boolean,
        ringtoneDurationSecs: Int = 0
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newAlarm = Alarm(
                groupId = groupId,
                hour = hour,
                minute = minute,
                daysOfWeek = daysOfWeek,
                isEnabled = true,
                label = label,
                ringtonePath = ringtonePath,
                vibrate = vibrate,
                ringtoneDurationSecs = ringtoneDurationSecs
            )
            val insertId = repository.insertAlarm(newAlarm)
            val finalAlarm = newAlarm.copy(id = insertId)
            val parentGroup = _groups.value.find { it.id == groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), finalAlarm, parentGroup)
            refreshBackgroundMonitor()
        }

    }

    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch(Dispatchers.IO) {
            AlarmScheduler.cancelAlarm(getApplication(), alarm.id)
            repository.deleteAlarm(alarm)
            refreshBackgroundMonitor()
        }
    }

    fun moveAlarmToGroup(alarm: Alarm, targetGroupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedAlarm = alarm.copy(groupId = targetGroupId)
            repository.updateAlarm(updatedAlarm)
            val parentGroup = _groups.value.find { it.id == targetGroupId }
            AlarmScheduler.scheduleAlarm(getApplication(), updatedAlarm, parentGroup)
        }
    }

    fun duplicateAlarm(alarm: Alarm) {
        viewModelScope.launch(Dispatchers.IO) {
            val offsetHours = _duplicateOffsetHours.value
            val offsetMinutes = _duplicateOffsetMinutes.value
            var newTotalMinutes = alarm.hour * 60 + alarm.minute + offsetHours * 60 + offsetMinutes
            
            // 处理跨天逻辑
            if (newTotalMinutes >= 24 * 60) {
                newTotalMinutes %= (24 * 60)
            }
            
            val newHour = newTotalMinutes / 60
            val newMinute = newTotalMinutes % 60

            val copy = alarm.copy(
                id = 0, 
                hour = newHour,
                minute = newMinute,
                label = "${alarm.label} (副本)"
            )
            val insertId = repository.insertAlarm(copy)
            val finalCopy = copy.copy(id = insertId)
            val parentGroup = _groups.value.find { it.id == alarm.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), finalCopy, parentGroup)
            refreshBackgroundMonitor()
            val offsetText = if (offsetHours > 0) "${offsetHours}小时${offsetMinutes}分钟" else "${offsetMinutes}分钟"
            addLog("已复制闹钟并延后 $offsetText")
        }
    }

    fun toggleWifiSync(context: Context, isEnabled: Boolean) {
        _isWifiServerOn.value = isEnabled
        val intent = Intent(context, AlarmService::class.java).apply {
            action = if (isEnabled) "START_WIFI_SERVER" else "STOP_WIFI_SERVER"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun updateAlarm(alarm: Alarm) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAlarm(alarm)
            val parentGroup = _groups.value.find { it.id == alarm.groupId }
            AlarmScheduler.scheduleAlarm(getApplication(), alarm, parentGroup)
            refreshBackgroundMonitor()
        }
    }

    fun importLocalAudio(context: Context, uri: android.net.Uri, originalFileName: String): String? {
        return runBlocking(Dispatchers.IO) {
            try {
                var path = _customRecordingPath.value
                if (path.isBlank()) {
                    path = getApplication<Application>().getExternalFilesDir("ringtones")?.absolutePath
                        ?: context.filesDir.absolutePath + "/ringtones"
                }
                val ringtonesDir = File(path)
                if (!ringtonesDir.exists()) {
                    ringtonesDir.mkdirs()
                }
                // 允许中文字符，仅过滤掉系统非法字符
                val safeName = originalFileName.replace("[^\\u4e00-\\u9fa5a-zA-Z0-9._-]".toRegex(), "_")
                val destFile = File(ringtonesDir, safeName)

                // 使用 openFileDescriptor + FileInputStream，比 openInputStream 更可靠
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    addLog("导入失败：openFileDescriptor 返回 null")
                    return@runBlocking null
                }
                pfd.use { fd ->
                    val bytesCopied = FileInputStream(fd.fileDescriptor).use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            var total = 0L
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                total += bytesRead
                            }
                            total
                        }
                    }
                    if (bytesCopied == 0L) {
                        addLog("导入失败：复制后文件为空（读了 0 字节）")
                        return@runBlocking null
                    }
                    addLog("导入成功: $safeName ($bytesCopied bytes)")
                }
                loadCustomRingtones()
                return@runBlocking destFile.absolutePath
            } catch (e: Exception) {
                addLog("导入失败: ${e.message}")
                e.printStackTrace()
                return@runBlocking null
            }
        }
    }

    suspend fun exportConfig(outputStream: OutputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ZipOutputStream(outputStream).use { zos ->
                    // 1. Export Database to JSON
                    val rootJson = JSONObject()
                    
                    val groupsJson = JSONArray()
                    groups.value.forEach { group ->
                        groupsJson.put(JSONObject().apply {
                            put("id", group.id)
                            put("name", group.name)
                            put("isEnabled", group.isEnabled)
                        })
                    }
                    rootJson.put("groups", groupsJson)

                    val alarmsJson = JSONArray()
                    alarms.value.forEach { alarm ->
                        alarmsJson.put(JSONObject().apply {
                            put("id", alarm.id)
                            put("groupId", alarm.groupId)
                            put("hour", alarm.hour)
                            put("minute", alarm.minute)
                            put("daysOfWeek", alarm.daysOfWeek)
                            put("isEnabled", alarm.isEnabled)
                            put("label", alarm.label)
                            put("ringtonePath", alarm.ringtonePath)
                            put("vibrate", alarm.vibrate)
                        })
                    }
                    rootJson.put("alarms", alarmsJson)

                    val chimesJson = JSONArray()
                    chimes.value.forEach { chime ->
                        chimesJson.put(JSONObject().apply {
                            put("hour", chime.hour)
                            put("isEnabled", chime.isEnabled)
                            put("useTts", chime.useTts)
                            put("vibrate", chime.vibrate)
                        })
                    }
                    rootJson.put("chimes", chimesJson)

                    val db = AlarmDatabase.getDatabase(getApplication(), viewModelScope)

                    // ─── 打卡组与打卡事项 ───
                    val checkInDao = db.checkinDao()
                    val checkInGroups = checkInDao.getAllGroups()
                    val checkInGroupsJson = JSONArray()
                    checkInGroups.forEach { g ->
                        checkInGroupsJson.put(JSONObject().apply {
                            put("id", g.id)
                            put("name", g.name)
                            put("isEnabled", g.isEnabled)
                            put("ringtonePath", g.ringtonePath ?: JSONObject.NULL)
                            put("boundAlarmGroupId", g.boundAlarmGroupId)
                            put("createdAt", g.createdAt)
                            // 导出该组下的所有事项
                            val tasks = checkInDao.getTasksByGroup(g.id)
                            val tasksJson = JSONArray()
                            tasks.forEach { t ->
                                tasksJson.put(JSONObject().apply {
                                    put("name", t.name)
                                    put("hour", t.hour)
                                    put("minute", t.minute)
                                    put("orderIndex", t.orderIndex)
                                    put("ringtonePath", t.ringtonePath ?: JSONObject.NULL)
                                    put("useTts", t.useTts)
                                })
                            }
                            put("tasks", tasksJson)
                        })
                    }
                    rootJson.put("checkInGroups", checkInGroupsJson)

                    // Write JSON entry
                    zos.putNextEntry(ZipEntry("config.json"))
                    zos.write(rootJson.toString().toByteArray())
                    zos.closeEntry()

                    // 2. Export Ringtones — 按每个闹钟引用的文件打包，不管在哪个目录
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    val addedExportFiles = mutableSetOf<String>()
                    alarms.value.forEach { alarm ->
                        val ringtonePath = alarm.ringtonePath
                        if (!ringtonePath.isNullOrEmpty()) {
                            val file = File(ringtonePath)
                            if (file.isFile && file.exists()) {
                                val canonicalKey = file.canonicalPath
                                if (canonicalKey !in addedExportFiles) {
                                    addedExportFiles.add(canonicalKey)
                                    zos.putNextEntry(ZipEntry("ringtones/${file.name}"))
                                    file.inputStream().use { fis -> fis.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                    }
                    // 导出打卡组引用的铃声文件
                    val checkInExportDao = db.checkinDao()
                    checkInExportDao.getAllGroups().forEach { g ->
                        listOfNotNull(g.ringtonePath).forEach { path ->
                            val file = File(path)
                            if (file.isFile && file.exists()) {
                                val canonicalKey = file.canonicalPath
                                if (canonicalKey !in addedExportFiles) {
                                    addedExportFiles.add(canonicalKey)
                                    zos.putNextEntry(ZipEntry("ringtones/${file.name}"))
                                    file.inputStream().use { fis -> fis.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        }
                        checkInExportDao.getTasksByGroup(g.id).forEach { t ->
                            t.ringtonePath?.let { path ->
                                val file = File(path)
                                if (file.isFile && file.exists()) {
                                    val canonicalKey = file.canonicalPath
                                    if (canonicalKey !in addedExportFiles) {
                                        addedExportFiles.add(canonicalKey)
                                        zos.putNextEntry(ZipEntry("ringtones/${file.name}"))
                                        file.inputStream().use { fis -> fis.copyTo(zos) }
                                        zos.closeEntry()
                                    }
                                }
                            }
                        }
                    }
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun exportConfig(outputFile: File) {
        viewModelScope.launch {
            exportConfig(FileOutputStream(outputFile))
        }
    }

    // ─── 单个打卡组导出/分享 ───

    suspend fun exportSingleCheckInGroup(
        group: CheckInGroupEntity,
        tasks: List<CheckInTaskEntity>,
        outputStream: OutputStream
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ZipOutputStream(outputStream).use { zos ->
                    val rootJson = JSONObject()
                    rootJson.put("formatVersion", 1)
                    rootJson.put("exportType", "checkin_group") // 标记为单打卡组导出

                    val checkInGroupsJson = JSONArray()
                    checkInGroupsJson.put(JSONObject().apply {
                        put("name", group.name)
                        put("isEnabled", group.isEnabled)
                        put("ringtonePath", group.ringtonePath ?: JSONObject.NULL)
                        put("createdAt", group.createdAt)
                        val tasksJson = JSONArray()
                        tasks.forEach { t ->
                            tasksJson.put(JSONObject().apply {
                                put("name", t.name)
                                put("hour", t.hour)
                                put("minute", t.minute)
                                put("orderIndex", t.orderIndex)
                                put("ringtonePath", t.ringtonePath ?: JSONObject.NULL)
                                put("useTts", t.useTts)
                            })
                        }
                        put("tasks", tasksJson)
                    })
                    rootJson.put("checkInGroups", checkInGroupsJson)

                    zos.putNextEntry(ZipEntry("config.json"))
                    zos.write(rootJson.toString().toByteArray())
                    zos.closeEntry()

                    // 打包铃声
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    val addedFiles = mutableSetOf<String>()
                    listOfNotNull(group.ringtonePath).forEach { path ->
                        val file = File(path)
                        if (file.isFile && file.exists() && file.canonicalPath !in addedFiles) {
                            addedFiles.add(file.canonicalPath)
                            zos.putNextEntry(ZipEntry("ringtones/${file.name}"))
                            file.inputStream().use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    tasks.forEach { t ->
                        t.ringtonePath?.let { path ->
                            val file = File(path)
                            if (file.isFile && file.exists() && file.canonicalPath !in addedFiles) {
                                addedFiles.add(file.canonicalPath)
                                zos.putNextEntry(ZipEntry("ringtones/${file.name}"))
                                file.inputStream().use { fis -> fis.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun importSingleCheckInGroup(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val path = _customRecordingPath.value
                if (path.isEmpty()) {
                    addLog("导入打卡组失败：存储路径为空")
                    return@withContext false
                }
                val ringtonesDir = File(path)
                if (!ringtonesDir.exists() && !ringtonesDir.mkdirs()) {
                    addLog("导入失败：无法创建目录")
                    return@withContext false
                }

                var configJson: String? = null
                ZipInputStream(inputStream).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "config.json" -> {
                                configJson = String(zis.readBytes(), Charsets.UTF_8)
                            }
                            entry.name.startsWith("ringtones/") -> {
                                val fileName = entry.name.substringAfter("ringtones/")
                                if (fileName.isNotEmpty()) {
                                    File(ringtonesDir, fileName).outputStream().use {
                                        zis.copyTo(it)
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                configJson?.let { jsonStr ->
                    val root = JSONObject(jsonStr)
                    if (!root.has("checkInGroups")) {
                        addLog("导入失败：未找到打卡组数据")
                        return@withContext false
                    }

                    val db = AlarmDatabase.getDatabase(getApplication(), viewModelScope)
                    val checkInGroupsArr = root.getJSONArray("checkInGroups")

                    for (i in 0 until checkInGroupsArr.length()) {
                        val g = checkInGroupsArr.getJSONObject(i)
                        val newId = db.checkinDao().insertGroup(CheckInGroupEntity(
                            name = g.getString("name"),
                            isEnabled = g.getBoolean("isEnabled"),
                            ringtonePath = fixRingtonePath(g, "ringtonePath", ringtonesDir),
                            boundAlarmGroupId = -1L,
                            createdAt = g.optLong("createdAt", System.currentTimeMillis())
                        ))

                        if (g.has("tasks")) {
                            val tasksArr = g.getJSONArray("tasks")
                            val tasksToInsert = mutableListOf<CheckInTaskEntity>()
                            for (j in 0 until tasksArr.length()) {
                                val t = tasksArr.getJSONObject(j)
                                tasksToInsert.add(CheckInTaskEntity(
                                    groupId = newId,
                                    name = t.getString("name"),
                                    hour = t.getInt("hour"),
                                    minute = t.getInt("minute"),
                                    orderIndex = t.getInt("orderIndex"),
                                    ringtonePath = fixRingtonePath(t, "ringtonePath", ringtonesDir),
                                    useTts = t.optBoolean("useTts", false)
                                ))
                            }
                            db.checkinDao().insertTasks(tasksToInsert)
                        }
                    }

                    addLog("打卡组导入成功")
                    loadCustomRingtones()
                    true
                } ?: run {
                    addLog("导入失败：无法解析配置文件")
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addLog("导入打卡组失败: ${e.message}")
                false
            }
        }
    }

    fun importSingleCheckInGroup(inputStream: InputStream, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = importSingleCheckInGroup(inputStream)
            onResult(result)
        }
    }

    suspend fun importConfig(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val path = _customRecordingPath.value
                if (path.isEmpty()) {
                    addLog("导入失败：存储路径为空")
                    return@withContext false
                }
                val ringtonesDir = File(path)
                if (!ringtonesDir.exists()) {
                    if (!ringtonesDir.mkdirs()) {
                        addLog("导入失败：无法创建目录 $path，请检查存储权限")
                        return@withContext false
                    }
                }

                var configJson: String? = null

                ZipInputStream(inputStream).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "config.json" -> {
                                // 核心修复：不要使用 bufferedReader().readText()，因为它会关闭整个 zis 流
                                val bytes = zis.readBytes()
                                configJson = String(bytes, Charsets.UTF_8)
                                addLog("读取到 config.json (${bytes.size} bytes)")
                            }
                            entry.name.startsWith("ringtones/") -> {
                                val fileName = entry.name.substringAfter("ringtones/")
                                if (fileName.isNotEmpty()) {
                                    val destFile = File(ringtonesDir, fileName)
                                    try {
                                        destFile.outputStream().use { fos ->
                                            zis.copyTo(fos)
                                        }
                                        addLog("已解压铃声: $fileName")
                                    } catch (e: Exception) {
                                        addLog("解压铃声失败 $fileName: ${e.message}")
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                configJson?.let { jsonStr ->
                    val root = JSONObject(jsonStr)
                    val db = AlarmDatabase.getDatabase(getApplication(), viewModelScope)

                    // Import Groups and map old IDs to new IDs
                    val idMap = mutableMapOf<Long, Long>()
                    val groupsArr = root.getJSONArray("groups")
                    for (i in 0 until groupsArr.length()) {
                        val g = groupsArr.getJSONObject(i)
                        val oldId = g.getLong("id")
                        val newId = db.alarmDao().insertGroup(AlarmGroup(
                            name = g.getString("name"),
                            isEnabled = g.getBoolean("isEnabled")
                        ))
                        idMap[oldId] = newId
                    }

                    // Import Alarms
                    val alarmsArr = root.getJSONArray("alarms")
                    for (i in 0 until alarmsArr.length()) {
                        val a = alarmsArr.getJSONObject(i)
                        val oldGroupId = a.getLong("groupId")
                        val newGroupId = idMap[oldGroupId] ?: continue
                        
                        // Fix ringtonePath if it was a local app path
                        val ringtonePath = fixRingtonePath(a, "ringtonePath", ringtonesDir)

                        db.alarmDao().insertAlarm(Alarm(
                            groupId = newGroupId,
                            hour = a.getInt("hour"),
                            minute = a.getInt("minute"),
                            daysOfWeek = a.getString("daysOfWeek"),
                            isEnabled = a.getBoolean("isEnabled"),
                            label = a.getString("label"),
                            ringtonePath = ringtonePath,
                            vibrate = a.getBoolean("vibrate")
                        ))
                    }

                    // Import Chimes
                    val chimesArr = root.getJSONArray("chimes")
                    for (i in 0 until chimesArr.length()) {
                        val c = chimesArr.getJSONObject(i)
                        db.alarmDao().updateHourlyChime(HourlyChime(
                            hour = c.getInt("hour"),
                            isEnabled = c.getBoolean("isEnabled"),
                            useTts = c.getBoolean("useTts"),
                            vibrate = c.getBoolean("vibrate")
                        ))
                    }

                    // ─── 导入打卡组与打卡事项 ───
                    if (root.has("checkInGroups")) {
                        // 清空旧的打卡数据
                        val oldCheckInGroups = db.checkinDao().getAllGroups()
                        oldCheckInGroups.forEach { db.checkinDao().deleteGroup(it) }

                        val checkInGroupsArr = root.getJSONArray("checkInGroups")
                        val checkInGroupIdMap = mutableMapOf<Long, Long>() // oldId -> newId
                        for (i in 0 until checkInGroupsArr.length()) {
                            val g = checkInGroupsArr.getJSONObject(i)
                            val oldId = g.getLong("id")

                            val newId = db.checkinDao().insertGroup(CheckInGroupEntity(
                                name = g.getString("name"),
                                isEnabled = g.getBoolean("isEnabled"),
                                ringtonePath = fixRingtonePath(g, "ringtonePath", ringtonesDir),
                                boundAlarmGroupId = -1L, // 导入时不绑定闹钟组
                                createdAt = g.optLong("createdAt", System.currentTimeMillis())
                            ))
                            checkInGroupIdMap[oldId] = newId

                            // 导入该组下的事项
                            if (g.has("tasks")) {
                                val tasksArr = g.getJSONArray("tasks")
                                val tasksToInsert = mutableListOf<CheckInTaskEntity>()
                                for (j in 0 until tasksArr.length()) {
                                    val t = tasksArr.getJSONObject(j)
                                    tasksToInsert.add(CheckInTaskEntity(
                                        groupId = newId,
                                        name = t.getString("name"),
                                        hour = t.getInt("hour"),
                                        minute = t.getInt("minute"),
                                        orderIndex = t.getInt("orderIndex"),
                                        ringtonePath = fixRingtonePath(t, "ringtonePath", ringtonesDir),
                                        useTts = t.optBoolean("useTts", false)
                                    ))
                                }
                                db.checkinDao().insertTasks(tasksToInsert)
                            }
                        }
                    }

                    // Refresh all alarms in AlarmManager
                    val allAlarms = db.alarmDao().getAllAlarms()
                    val allGroups = db.alarmDao().getAllGroups()
                    allAlarms.forEach { alarm ->
                        val group = allGroups.find { it.id == alarm.groupId }
                        AlarmScheduler.scheduleAlarm(getApplication(), alarm, group)
                    }
                    AlarmScheduler.scheduleNextHourlyChime(getApplication())
                }
                
                loadCustomRingtones()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * 导入 JSON 对象中的铃声路径，修正为解压后的本地路径
     */
    private fun fixRingtonePath(obj: org.json.JSONObject, key: String, ringtonesDir: File): String? {
        val raw = if (obj.isNull(key)) null else obj.getString(key)
        if (raw == null || raw == "null") return null
        return if (raw.contains("/files/custom_ringtones/") || raw.contains("/0/")) {
            val fileName = raw.substringAfterLast("/")
            File(ringtonesDir, fileName).absolutePath
        } else {
            raw
        }
    }

    private var ttsPitch = 1.0f
    private var ttsRate = 1.0f
    private var lastSpeakTimeMs = 0L
    private var lastSpeakStarted = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            addLog("TTS 引擎初始化成功")

            // 主 TTS 初始化后重新扫描引擎（tempTts 可能因 null listener 漏掉某些引擎）
            scanTtsEngines()

            // 尝试使用更精确的中国大陆区域设置
            val result = tts?.setLanguage(Locale.CHINA)
            addLog("TTS 语言设置结果: $result")

            // 配置音频属性 — 使用 USAGE_ALARM 确保在 Realme 等设备上走扬声器
            // USAGE_ASSISTANCE_SONIFICATION 在部分系统上可能被路由到听筒/蓝牙
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            
            // 记录当前使用的语音/引擎信息，便于诊断
            addLog("当前语音: ${tts?.voice?.name ?: "未设置"}, 引擎: ${tts?.defaultEngine ?: "未知"}")

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                addLog("警告: 当前引擎缺失中文语音包，尝试从可用语音中查找中文语音...")
                // 从引擎支持的语音列表中寻找中文语音
                val chineseVoice = tts?.voices?.find { voice ->
                    voice.locale.also { loc ->
                        loc.language == "zh" ||
                        loc.language == "cmn" ||
                        loc.displayLanguage?.startsWith("中文") == true ||
                        loc.displayLanguage?.startsWith("Chinese") == true
                    } != null
                }
                if (chineseVoice != null) {
                    val voiceResult = tts?.setVoice(chineseVoice)
                    addLog("自动选用中文语音: ${chineseVoice.name}, 结果: $voiceResult")
                } else {
                    addLog("错误: 当前引擎没有可用中文语音，请前往系统 TTS 设置下载中文语音数据")
                    // 显示 Toast 提醒用户
                    try {
                        android.widget.Toast.makeText(
                            getApplication(),
                            "当前 TTS 引擎没有中文语音，请在系统设置中下载",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                }
            }
            // 初始化成功后，应用一次默认参数
            tts?.setPitch(ttsPitch)
            tts?.setSpeechRate(ttsRate)

            // 扫描当前引擎支持的语音列表
            scanAvailableVoices()

            // 重启后恢复上次选择的语音
            val savedVoice = _selectedTtsVoiceName.value
            if (savedVoice.isNotEmpty()) {
                val matched = _availableVoices.value.find { it.name == savedVoice }
                if (matched != null) {
                    tts?.setVoice(matched)
                    addLog("已恢复语音设置: $savedVoice")
                } else {
                    addLog("语音 $savedVoice 在当前引擎中不可用")
                }
            }
            isTtsReady = true
            addLog("TTS 已就绪，可以播放")

            // 如果有等待播放的测试文本，立即播放
            pendingTtsText?.let { text ->
                addLog("播放排队中的测试语音: $text")
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                tts?.setPitch(ttsPitch)
                tts?.setSpeechRate(ttsRate)
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "pending_test")
                addLog("排队播放结果: $result")
                pendingTtsText = null
            }
        } else {
            addLog("错误: TTS 初始化失败，状态码: $status")
        }
    }

    fun testTts(hour: Int) {
        val text = "现在是北京时间${hour}点整"
        addLog("尝试播放测试语音: $text (音调:$ttsPitch, 语速:$ttsRate)")

        // 如果启用了在线 TTS，使用百度语音合成
        if (_useOnlineTts.value) {
            playOnlineTts(text)
            return
        }
        // 构建带音量的参数包（与 AlarmService 一致），避免 null 导致某些引擎音量异常
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        if (isTtsReady) {
            // 播放前再次强制设置参数，解决部分引擎在长时空闲后重置的问题
            tts?.setPitch(ttsPitch)
            tts?.setSpeechRate(ttsRate)
            // 设置用户选的语音
            applySelectedVoice()
            addLog("当前语音: ${tts?.voice?.name ?: "无"}, 引擎: ${tts?.defaultEngine ?: "未知"}")

            // 重置发音监听标记，用于检测引擎是否真正开始发音
            lastSpeakStarted = false
            lastSpeakTimeMs = System.currentTimeMillis()
            
            val utteranceId = "test_chime_${System.currentTimeMillis()}"
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                addLog("错误: speak 接口调用返回 ERROR")
            } else {
                addLog("播放指令已发送，等待系统发声...")
            }
            
            // 3 秒后检查引擎是否真的开始发音，如果没开始则回退到提示音
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!lastSpeakStarted) {
                    addLog("⚠ TTS 引擎 3 秒内未开始发音（可能是引擎不可用），播放提示音回退")
                    try {
                        val mp = android.media.MediaPlayer()
                        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        mp.setDataSource(getApplication(), uri)
                        mp.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        mp.setOnCompletionListener { it.release() }
                        mp.prepare()
                        mp.start()
                        addLog("✓ 已播放通知提示音作为回退")
                    } catch (e: Exception) {
                        addLog("播放回退提示音失败: ${e.message}")
                    }
                }
            }, 3000)
        } else {
            addLog("TTS 尚未就绪（引擎绑定中），将排队等待播放")
            pendingTtsText = text
            // 启动协程等待 TTS 就绪后自动重试（最多等待 10 秒）
            viewModelScope.launch {
                var waited = 0
                while (!isTtsReady && waited < 100) {  // 100 × 100ms = 10s
                    delay(100)
                    waited++
                }
                if (isTtsReady) {
                    val retryText = pendingTtsText ?: return@launch
                    addLog("TTS 已就绪，播放排队中的测试语音: $retryText")
                    tts?.setPitch(ttsPitch)
                    tts?.setSpeechRate(ttsRate)
                    applySelectedVoice()
                    addLog("排队播放, 当前语音: ${tts?.voice?.name ?: "无"}")
                    val result = tts?.speak(retryText, TextToSpeech.QUEUE_FLUSH, params, "retry_test")
                    addLog("排队播放结果: $result")
                    pendingTtsText = null
                } else {
                    addLog("等待 TTS 就绪超时（10秒），请检查系统 TTS 引擎是否正常")
                }
            }
        }
    }

    /**
     * 测试缓存报时语音：清空 → 重新合成 → MediaPlayer 播放验证
     * 确保不受同步文件干扰，验证 synthesizeToFile 在 Realme 上可用
     */
    fun testChimeCacheFiles() {
        addLog("======= 开始测试缓存报时语音 =======")
        val ctx = getApplication<Application>()
        val workerScope = viewModelScope
        workerScope.launch(Dispatchers.IO) {
            try {
                // 1. 清空缓存标记和所有文件
                ChimeAudioPreloader.resetCacheFlag(ctx)
                addLog("已重置缓存标记")
                for (h in 0..23) {
                    val f = ChimeAudioPreloader.file(ctx, h)
                    if (f.exists()) {
                        f.delete()
                        addLog("已删除: ${f.name}")
                    }
                }
                addLog("24 个缓存文件已全部清空")

                // 2. 重新触发合成（synthesizeToFile）
                addLog("开始通过 synthesizeToFile 重新合成...")
                ChimeAudioPreloader.ensure(ctx)

                // 3. 等待合成完成（轮询最多 15 秒）
                addLog("等待合成完成...")
                var waited = 0
                val maxWait = 150 // 15秒 = 150 × 100ms
                var allExist = false
                while (waited < maxWait) {
                    delay(100)
                    waited++
                    val existing = (0..23).count { h -> ChimeAudioPreloader.file(ctx, h).exists() }
                    if (existing == 24) {
                        allExist = true
                        addLog("24 个文件合成完毕（等待 ${(waited * 100)}ms）")
                        break
                    }
                }

                if (!allExist) {
                    val count = (0..23).count { h -> ChimeAudioPreloader.file(ctx, h).exists() }
                    addLog("⚠ 超时 ${maxWait * 100}ms，仅有 $count/24 个文件就绪，继续播放已有文件")
                } else {
                    // 标记缓存就绪
                    ctx.getSharedPreferences("chime_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("chime_cache_ready", true).apply()
                }

                // 4. 用 MediaPlayer 播放验证（只播当前小时，不挨个读）
                addLog("===== 开始播放验证 =====")
                var playedCount = 0
                var failedCount = 0
                val testHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val hourToTest = if (ChimeAudioPreloader.file(ctx, testHour).exists()) testHour
                                 else (0..23).firstOrNull { ChimeAudioPreloader.file(ctx, it).exists() } ?: 0
                val testFile = ChimeAudioPreloader.file(ctx, hourToTest)
                if (!testFile.exists()) {
                    addLog("跳过播放：无可用缓存文件")
                    failedCount++
                } else {
                    try {
                        addLog("播放 ${hourToTest}:00...")
                        val mp = MediaPlayer().apply {
                            setDataSource(testFile.absolutePath)
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            setOnCompletionListener {
                                it.release()
                                addLog("✓ ${hourToTest}:00 播放完成")
                            }
                            setOnErrorListener { _, what, extra ->
                                addLog("✗ ${hourToTest}:00 播放出错 (what=$what, extra=$extra)")
                                release()
                                true
                            }
                            prepare()
                            start()
                        }
                        playedCount++
                    } catch (e: Exception) {
                        addLog("✗ ${hourToTest}:00 播放失败: ${e.message}")
                        failedCount++
                    }
                }
                addLog("======= 测试完成: 成功 $playedCount 段, 失败 $failedCount 段 =======")
            } catch (e: Exception) {
                addLog("测试缓存报时语音异常: ${e.message}")
            }
        }
    }

    fun setTtsPitch(pitch: Float) {
        ttsPitch = pitch
        val result = tts?.setPitch(pitch)
        com.example.alarm.TtsTaskPlayer.pitch = pitch
        addLog("调节音调为: $pitch, 结果: $result")
    }

    fun setTtsRate(rate: Float) {
        ttsRate = rate
        val result = tts?.setSpeechRate(rate)
        com.example.alarm.TtsTaskPlayer.speechRate = rate
        addLog("调节语速为: $rate, 结果: $result")
    }

    // 扫描系统中所有可用的 TTS 引擎
    fun scanTtsEngines() {
        try {
            val enginesList: MutableList<android.speech.tts.TextToSpeech.EngineInfo>

            // 如果主 TTS 已初始化，优先通过主实例获取引擎列表（避免创建临时实例造成冲突）
            if (tts != null) {
                enginesList = tts!!.engines.toMutableList()
                addLog("通过主 TTS 实例获取引擎列表 (${enginesList.size} 个)")
            } else {
                // 主 TTS 不可用时，通过临时实例获取
                val tempTts = TextToSpeech(getApplication(), null)
                enginesList = tempTts.engines.toMutableList()
                tempTts.shutdown()
                addLog("通过临时 TTS 实例获取引擎列表")
            }

            _availableTtsEngines.value = enginesList
            addLog("扫描到 ${enginesList.size} 个 TTS 引擎")
            enginesList.forEach { engine ->
                addLog("  TTS 引擎: ${engine.label} (${engine.name})")
            }
        } catch (e: Exception) {
            addLog("扫描 TTS 引擎出错: ${e.message}")
        }
    }

    // 扫描当前 TTS 引擎支持的所有语音
    private fun scanAvailableVoices() {
        try {
            val voices = tts?.voices?.toList() ?: emptyList()
            _availableVoices.value = voices
            addLog("扫描到 ${voices.size} 个可用语音")
            // 只记录前5个以免刷屏
            voices.take(5).forEach { voice ->
                addLog("  语音: ${voice.name} (${voice.locale})")
            }
        } catch (e: Exception) {
            addLog("扫描语音资源出错: ${e.message}")
        }
    }

    // 切换 TTS 引擎：保存偏好、关闭旧引擎、用新引擎包名重新创建
    fun setTtsEngine(engineName: String) {
        // 先同步到 TtsTaskPlayer（无论是否 early return）
        com.example.alarm.TtsTaskPlayer.engineName = engineName

        if (engineName == _selectedTtsEngine.value) return
        addLog("切换 TTS 引擎至: $engineName")
        
        // 切换引擎，清除之前排队的测试请求
        pendingTtsText = null
        
        // 保存偏好
        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("tts_engine", engineName).apply()
        _selectedTtsEngine.value = engineName
        _selectedTtsVoiceName.value = "" // 引擎切换后重置语音选择
        
        // 关闭旧 TTS 实例
        isTtsReady = false
        tts?.stop()
        tts?.shutdown()
        
        // 用新引擎重新创建
        val enginePackage = if (engineName.isNotEmpty()) engineName else null
        tts = TextToSpeech(getApplication(), this, enginePackage)
        addLog("已使用新引擎重新创建 TTS 实例")
    }

    // 重新应用用户选择的语音（在 speak 前调用，确保引擎使用的是用户选的那个语音）
    private fun applySelectedVoice() {
        val savedVoice = _selectedTtsVoiceName.value
        if (savedVoice.isNotEmpty()) {
            val matched = _availableVoices.value.find { it.name == savedVoice }
            if (matched != null) {
                tts?.setVoice(matched)
            } else {
                addLog("applySelectedVoice: 未匹配到语音: $savedVoice")
            }
        }
    }

    // 设置 TTS 语音：保存偏好、调用 setVoice
    fun setTtsVoice(voiceName: String) {
        // 先同步到 TtsTaskPlayer（无论是否 early return，都要确保全局生效）
        com.example.alarm.TtsTaskPlayer.voiceName = voiceName

        if (voiceName == _selectedTtsVoiceName.value) return
        addLog("设置 TTS 语音至: $voiceName")
        
        // 保存偏好
        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("tts_voice", voiceName).apply()
        _selectedTtsVoiceName.value = voiceName
        
        if (isTtsReady) {
            val matched = _availableVoices.value.find { it.name == voiceName }
            if (matched != null) {
                val result = tts?.setVoice(matched)
                addLog("设置语音结果: $result")
            } else {
                addLog("未找到匹配的语音: $voiceName")
            }
        } else {
            addLog("TTS 尚未就绪，语音将在下次初始化时应用")
        }

        // 通知后台 AlarmService 同步更新语音
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "UPDATE_TTS_VOICE"
            putExtra("TTS_VOICE_NAME", voiceName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    // 刷新后台闹钟监控服务
    fun refreshBackgroundMonitor() {
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "REFRESH_MONITOR"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    // ────────── 在线 TTS（百度语音合成）相关 ──────────

    fun setUseOnlineTts(enabled: Boolean) {
        _useOnlineTts.value = enabled
        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_online_tts", enabled).apply()
        addLog(if (enabled) "切换到在线 TTS 模式" else "切换到系统 TTS 模式")
    }

    fun setBaiduCredentials(apiKey: String, secretKey: String) {
        _baiduApiKey.value = apiKey
        _baiduSecretKey.value = secretKey
        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("baidu_api_key", apiKey)
            .putString("baidu_secret_key", secretKey)
            .apply()
        addLog("已保存百度 TTS API 凭据")
    }

    fun setBaiduVoice(voice: Int) {
        _baiduVoice.value = voice
        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("baidu_voice", voice).apply()
        val voiceName = baiduTtsClient.getVoiceList().find { it.first == voice }?.second ?: voice.toString()
        addLog("切换百度 TTS 发音人至: $voiceName")
    }

    /**
     * 使用在线 TTS 播放语音（百度语音合成）
     * 在后台 IO 线程获取 token 和合成音频，主线程播放
     */
    private fun playOnlineTts(text: String) {
        val apiKey = _baiduApiKey.value
        val secretKey = _baiduSecretKey.value
        val voice = _baiduVoice.value

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            addLog("错误: 百度 TTS API Key 或 Secret Key 未设置，请在设置中填写")
            return
        }

        _isPlayingOnlineTts.value = true
        addLog("正在请求百度 TTS 合成: $text (发音人: $voice)")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 获取 access token
                val tokenResult = baiduTtsClient.getAccessToken(apiKey, secretKey)
                if (tokenResult.isFailure) {
                    addLog("获取百度 token 失败: ${tokenResult.exceptionOrNull()?.message}")
                    _isPlayingOnlineTts.value = false
                    // 回退到系统 TTS
                    launch(Dispatchers.Main) { fallbackToSystemTts(text) }
                    return@launch
                }

                val token = tokenResult.getOrThrow()
                addLog("百度 token 获取成功")

                // 语音合成
                val synthResult = baiduTtsClient.synthesize(text, token, voice = voice)
                if (synthResult.isFailure) {
                    addLog("百度 TTS 合成失败: ${synthResult.exceptionOrNull()?.message}")
                    _isPlayingOnlineTts.value = false
                    launch(Dispatchers.Main) { fallbackToSystemTts(text) }
                    return@launch
                }

                val audioData = synthResult.getOrThrow()
                addLog("百度 TTS 合成成功，音频大小: ${audioData.size} 字节")

                // 切回主线程播放
                withContext(Dispatchers.Main) {
                    playAudioBytes(audioData)
                    _isPlayingOnlineTts.value = false
                }
            } catch (e: Exception) {
                addLog("在线 TTS 异常: ${e.message}")
                _isPlayingOnlineTts.value = false
                // 回退到系统 TTS
                withContext(Dispatchers.Main) { fallbackToSystemTts(text) }
            }
        }
    }

    /** 播放字节音频（写入临时文件后使用 MediaPlayer）*/
    private var onlineTtsMediaPlayer: MediaPlayer? = null

    private fun playAudioBytes(audioData: ByteArray) {
        try {
            // 停止旧的播放器
            onlineTtsMediaPlayer?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
            }
            onlineTtsMediaPlayer = null

            // 写入临时文件
            val tempFile = File(getApplication<Application>().cacheDir, "online_tts_${System.currentTimeMillis()}.mp3")
            tempFile.writeBytes(audioData)
            tempFile.deleteOnExit()

            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnPreparedListener { mp ->
                    mp.start()
                    addLog("在线 TTS 开始播放")
                }
                setOnCompletionListener {
                    release()
                    tempFile.delete()
                    addLog("在线 TTS 播放完成")
                }
                setOnErrorListener { _, what, extra ->
                    addLog("在线 TTS 播放错误: what=$what extra=$extra")
                    tempFile.delete()
                    true
                }
                prepareAsync()
            }
            onlineTtsMediaPlayer = player
        } catch (e: Exception) {
            addLog("播放在线 TTS 音频失败: ${e.message}")
        }
    }

    /** 回退到系统 TTS */
    private fun fallbackToSystemTts(text: String) {
        addLog("回退到系统 TTS")
        if (isTtsReady) {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts?.setPitch(ttsPitch)
            tts?.setSpeechRate(ttsRate)
            applySelectedVoice()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "online_fallback")
        }
    }

    // 计时器相关操作
    fun startTimer(durationSeconds: Int) {
        if (durationSeconds <= 0) return
        timerJobInstance?.cancel()
        _timerRemainingSeconds.value = durationSeconds
        _isTimerRunning.value = true

        // 保存计时器结束时间戳，关 App 重新打开后恢复
        getApplication<Application>().getSharedPreferences("timer_state", Context.MODE_PRIVATE).edit {
            putLong("timer_end_millis", System.currentTimeMillis() + durationSeconds * 1000L)
        }

        // 启动 AlarmService 前台倒计时（服务端倒计时 + 响铃）
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "START_COUNTDOWN"
            putExtra("COUNTDOWN_TOTAL_SECONDS", durationSeconds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }

        // 本地协程仅用于 UI 更新
        timerJobInstance = viewModelScope.launch {
            while (_timerRemainingSeconds.value > 0) {
                delay(1000)
                _timerRemainingSeconds.value -= 1
            }
            // 倒计时结束 → 进入响铃状态，UI 显示停止响铃按钮
            _isTimerRunning.value = false
            _isTimerRinging.value = true
        }
    }

    fun stopTimer() {
        timerJobInstance?.cancel()
        timerJobInstance = null
        _isTimerRunning.value = false
        _isTimerRinging.value = false
        _timerRemainingSeconds.value = 0

        // 清除保存的计时器结束时间
        getApplication<Application>().getSharedPreferences("timer_state", Context.MODE_PRIVATE).edit {
            remove("timer_end_millis")
        }

        // 通知 AlarmService 停止倒计时
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "STOP_COUNTDOWN"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    fun dismissTimerRinging() {
        // 停止响铃并重置所有计时器状态
        timerJobInstance?.cancel()
        timerJobInstance = null
        _isTimerRunning.value = false
        _isTimerRinging.value = false
        _timerRemainingSeconds.value = 0

        // 清除保存的计时器结束时间
        getApplication<Application>().getSharedPreferences("timer_state", Context.MODE_PRIVATE).edit {
            remove("timer_end_millis")
        }

        // 不再发送 STOP_RINGING intent：AlarmActiveActivity 已通过 dismissAlarm()
        // 触发 stopRinging() 并销毁了服务。这里再发 startForegroundService()
        // 会导致新启动的服务没调 startForeground() 直接 stopSelf() 闪退
    }

    private fun onTimerFinished() {
        // 倒计时结束，拉起响铃（标记为计时器，界面区分）
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "START_RINGING"
            putExtra("ALARM_LABEL", "计时结束")
            putExtra("ALARM_VIBRATE", true)
            putExtra("IS_TIMER", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
    }

    // ══════════════════════════════════════════
    // 打卡任务
    // ══════════════════════════════════════════

    private suspend fun loadCheckInTasksForGroup(groupId: Long): List<CheckInTaskEntity> {
        return checkInDao.getTasksByGroup(groupId)
    }

    fun loadAllCheckInTasks() {
        viewModelScope.launch {
            _isLoadingCheckIn.value = true
            val allGroups = checkInDao.getAllGroups()
            val map = mutableMapOf<Long, List<CheckInTaskEntity>>()
            for (g in allGroups) {
                map[g.id] = checkInDao.getTasksByGroup(g.id)
            }
            _checkInTasksMap.value = map
            _isLoadingCheckIn.value = false
        }
    }

    private suspend fun addCheckInGroupSuspend(name: String, tasks: List<CheckInTaskInput>, isEnabled: Boolean = false) {
        withContext(Dispatchers.IO) {
            val groupId = checkInDao.insertGroup(
                CheckInGroupEntity(name = name, isEnabled = isEnabled)
            )
            val entities = tasks.mapIndexed { i, t ->
                CheckInTaskEntity(
                    groupId = groupId,
                    name = t.name,
                    hour = t.hour.toIntOrNull()?.coerceIn(0, 23) ?: 8,
                    minute = t.minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                    orderIndex = i,
                    ringtonePath = t.ringtonePath,
                    useTts = t.useTts
                )
            }
            checkInDao.insertTasks(entities)
            // 为新任务生成 TTS 语音缓存文件
            val appCtx = getApplication<android.app.Application>()
            for (t in entities) {
                try {
                    com.example.alarm.TtsTaskPlayer.generateSync(appCtx, t.name)
                } catch (_: Exception) { }
            }
            loadAllCheckInTasks()
        }
    }

    fun addCheckInGroup(name: String, tasks: List<CheckInTaskInput>) {
        viewModelScope.launch(Dispatchers.IO) {
            addCheckInGroupSuspend(name, tasks)
        }
    }

    fun updateCheckInGroup(group: CheckInGroupEntity, tasks: List<CheckInTaskInput>) {
        viewModelScope.launch(Dispatchers.IO) {
            checkInDao.updateGroup(group)
            checkInDao.deleteTasksByGroup(group.id)
            val entities = tasks.mapIndexed { i, t ->
                CheckInTaskEntity(
                    groupId = group.id,
                    name = t.name,
                    hour = t.hour.toIntOrNull()?.coerceIn(0, 23) ?: 8,
                    minute = t.minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                    orderIndex = i,
                    ringtonePath = t.ringtonePath,
                    useTts = t.useTts
                )
            }
            checkInDao.insertTasks(entities)
            // 如果组已启用，更新绑定的闹钟
            if (group.isEnabled && group.boundAlarmGroupId != -1L) {
                rebuildBoundAlarms(group, entities)
            }
            loadAllCheckInTasks()
        }
    }

    fun duplicateCheckInGroup(group: CheckInGroupEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val offsetHours = _duplicateOffsetHours.value
            val offsetMinutes = _duplicateOffsetMinutes.value
            val tasks = checkInDao.getTasksByGroup(group.id)
            // 创建副本组
            val newGroupId = checkInDao.insertGroup(
                group.copy(id = 0, isEnabled = false, boundAlarmGroupId = -1L,
                    createdAt = System.currentTimeMillis())
            )
            // 复制任务，时间加上偏移
            val newTasks = tasks.map { task ->
                var totalMin = task.hour * 60 + task.minute + offsetHours * 60 + offsetMinutes
                if (totalMin >= 24 * 60) totalMin -= 24 * 60
                task.copy(
                    id = 0,
                    groupId = newGroupId,
                    hour = totalMin / 60,
                    minute = totalMin % 60
                )
            }
            checkInDao.insertTasks(newTasks)
            loadAllCheckInTasks()
        }
    }

    /**
     * 复制打卡任务项：修改原任务名追加「X点第一次打卡」，
     * 新建 copies 个任务，时间依次偏移 intervalMinutes，名称追加「X点第N次打卡」
     * 时间转为中文数字（TTS友好）
     */
    fun duplicateCheckInTask(task: CheckInTaskEntity, group: CheckInGroupEntity, copies: Int, intervalMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val chineseNumerals = arrayOf(
                "零", "一", "二", "三", "四", "五", "六", "七", "八", "九",
                "十", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九",
                "二十", "二十一", "二十二", "二十三"
            )
            fun hourToChinese(hour: Int): String = if (hour in 0..23) chineseNumerals[hour] else hour.toString()
            fun minuteToChinese(minute: Int): String {
                if (minute < 10) return chineseNumerals[minute]
                if (minute < 20) return "十${if (minute > 10) chineseNumerals[minute - 10] else ""}"
                val tens = minute / 10
                val ones = minute % 10
                val tensStr = chineseNumerals[tens]
                return if (ones == 0) "${tensStr}十" else "${tensStr}十${chineseNumerals[ones]}"
            }

            // 原项改名：追加 "十点第一次打卡"
            val originalTimeStr = if (task.minute == 0) {
                "${hourToChinese(task.hour)}点"
            } else {
                "${hourToChinese(task.hour)}点${minuteToChinese(task.minute)}分"
            }
            val originalNewName = "${task.name}${originalTimeStr}第一次打卡"
            checkInDao.updateTask(task.copy(name = originalNewName))

            // 创建 copies 个新任务，时间依次偏移
            val newTasks = (1..copies).map { i ->
                var totalMin = task.hour * 60 + task.minute + i * intervalMinutes
                // 支持跨天取模
                totalMin = ((totalMin % (24 * 60)) + 24 * 60) % (24 * 60)
                val newHour = totalMin / 60
                val newMinute = totalMin % 60
                val timeStr = if (newMinute == 0) {
                    "${hourToChinese(newHour)}点"
                } else {
                    "${hourToChinese(newHour)}点${minuteToChinese(newMinute)}分"
                }
                val orderSuffix = when {
                    i < 10 -> "第${chineseNumerals[i]}次打卡"
                    else -> "第${i}次打卡"
                }
                CheckInTaskEntity(
                    id = 0,
                    groupId = task.groupId,
                    name = "${task.name}${timeStr}${orderSuffix}",
                    hour = newHour,
                    minute = newMinute,
                    orderIndex = task.orderIndex + i,
                    ringtonePath = task.ringtonePath,
                    useTts = task.useTts
                )
            }
            checkInDao.insertTasks(newTasks)
            loadAllCheckInTasks()
        }
    }

    /** 向已有的打卡组中批量添加任务项 */
    fun addCheckInTasks(groupId: Long, tasks: List<CheckInTaskEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingTasks = checkInDao.getTasksByGroup(groupId)
            val maxOrder = existingTasks.maxOfOrNull { it.orderIndex } ?: -1
            val newTasks = tasks.mapIndexed { index, task ->
                task.copy(id = 0, groupId = groupId, orderIndex = maxOrder + 1 + index)
            }
            checkInDao.insertTasks(newTasks)
            // 为新任务生成 TTS 语音缓存
            val appCtx = getApplication<android.app.Application>()
            for (t in newTasks) {
                try {
                    com.example.alarm.TtsTaskPlayer.generateSync(appCtx, t.name)
                } catch (_: Exception) { }
            }
            loadAllCheckInTasks()
        }
    }

    fun deleteCheckInGroup(group: CheckInGroupEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // 如果有绑定的闹钟组，一并删除
            if (group.boundAlarmGroupId != -1L) {
                val boundGroup = _groups.value.find { it.id == group.boundAlarmGroupId }
                if (boundGroup != null) {
                    repository.getAlarmsByGroup(boundGroup.id).forEach {
                        AlarmScheduler.cancelAlarm(getApplication(), it.id)
                    }
                    repository.deleteGroup(boundGroup)
                }
            }
            checkInDao.deleteGroup(group)
            loadAllCheckInTasks()
            refreshBackgroundMonitor()
        }
    }

    fun toggleCheckInGroup(group: CheckInGroupEntity, enable: Boolean, replaceExisting: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasks = checkInDao.getTasksByGroup(group.id)
            if (enable) {
                val groupName = group.name
                // 替换：删除全部已有闹钟组和闹钟
                if (replaceExisting) {
                    val allGroups = repository.getGroupList()
                    for (g in allGroups) {
                        repository.getAlarmsByGroup(g.id).forEach {
                            AlarmScheduler.cancelAlarm(getApplication(), it.id)
                        }
                        repository.deleteGroup(g)
                    }
                }
                // 新建一个 AlarmGroup
                val alarmGroupId = repository.insertGroup(
                    AlarmGroup(name = groupName, isEnabled = true)
                )
                // 为每个打卡任务创建闹钟（默认每天）
                for (task in tasks) {
                    val ringtone = task.ringtonePath ?: group.ringtonePath
                    val alarm = Alarm(
                        groupId = alarmGroupId,
                        hour = task.hour,
                        minute = task.minute,
                        daysOfWeek = "1,2,3,4,5,6,7",
                        isEnabled = true,
                        label = task.name,
                        ringtonePath = ringtone,
                        vibrate = true
                    )
                    val insertId = repository.insertAlarm(alarm)
                    val finalAlarm = alarm.copy(id = insertId)
                    val parentGroup = _groups.value.find { it.id == alarmGroupId }
                    AlarmScheduler.scheduleAlarm(getApplication(), finalAlarm, parentGroup)
                }
                // 更新打卡组，绑定闹钟组 ID
                checkInDao.updateGroup(group.copy(isEnabled = true, boundAlarmGroupId = alarmGroupId))
            } else {
                // 停用：删除绑定的闹钟组
                if (group.boundAlarmGroupId != -1L) {
                    val boundGroup = _groups.value.find { it.id == group.boundAlarmGroupId }
                    if (boundGroup != null) {
                        repository.getAlarmsByGroup(boundGroup.id).forEach {
                            AlarmScheduler.cancelAlarm(getApplication(), it.id)
                        }
                        repository.deleteGroup(boundGroup)
                    }
                }
                checkInDao.updateGroup(group.copy(isEnabled = false, boundAlarmGroupId = -1L))
            }
            loadAllCheckInTasks()
            refreshBackgroundMonitor()
        }
    }

    private suspend fun rebuildBoundAlarms(group: CheckInGroupEntity, tasks: List<CheckInTaskEntity>) {
        // 删除旧的闹钟
        repository.getAlarmsByGroup(group.boundAlarmGroupId).forEach {
            AlarmScheduler.cancelAlarm(getApplication(), it.id)
            repository.deleteAlarm(it)
        }
        // 重新创建
        for (task in tasks) {
            val ringtone = task.ringtonePath ?: group.ringtonePath
            val alarm = Alarm(
                groupId = group.boundAlarmGroupId,
                hour = task.hour,
                minute = task.minute,
                daysOfWeek = "1,2,3,4,5,6,7",
                isEnabled = true,
                label = task.name,
                ringtonePath = ringtone,
                vibrate = true
            )
            val insertId = repository.insertAlarm(alarm)
            val finalAlarm = alarm.copy(id = insertId)
            AlarmScheduler.scheduleAlarm(getApplication(), finalAlarm, null)
        }
    }

    // ═══════════════════════════════════════════════
    // 云端分享（Firebase）
    // ═══════════════════════════════════════════════

    private val _cloudShareCode = MutableStateFlow<String?>(null)
    val cloudShareCode: StateFlow<String?> = _cloudShareCode.asStateFlow()

    private val _cloudShareLoading = MutableStateFlow(false)
    val cloudShareLoading: StateFlow<Boolean> = _cloudShareLoading.asStateFlow()

    private val _cloudShareRecords = MutableStateFlow<List<CloudShareRecord>>(emptyList())
    val cloudShareRecords: StateFlow<List<CloudShareRecord>> = _cloudShareRecords.asStateFlow()

    private val _cloudImportResult = MutableStateFlow<String?>(null)
    val cloudImportResult: StateFlow<String?> = _cloudImportResult.asStateFlow()

    /** 将指定闹钟组及其闹钟上传到云端，返回分享码 */
    suspend fun shareAlarmGroupToCloud(group: AlarmGroup): String? {
        _cloudShareLoading.value = true
        _cloudShareCode.value = null
        return withContext(Dispatchers.IO) {
            try {
                val alarmsInGroup = repository.getAlarmsByGroup(group.id)
                val alarmsJson = JSONArray()
                alarmsInGroup.forEach { a ->
                    alarmsJson.put(JSONObject().apply {
                        put("hour", a.hour)
                        put("minute", a.minute)
                        put("daysOfWeek", a.daysOfWeek)
                        put("isEnabled", a.isEnabled)
                        put("label", a.label)
                        put("ringtonePath", a.ringtonePath ?: "")
                        put("vibrate", a.vibrate)
                    })
                }
                val jsonString = cloudService.buildAlarmConfigJson(group.name, alarmsJson)
                val rootWithEnabled = org.json.JSONObject(jsonString).apply {
                    put("isEnabled", group.isEnabled)
                }.toString()
                val code = cloudService.uploadConfig(rootWithEnabled)
                if (code != null) {
                    _cloudShareCode.value = code
                    addLog("云端分享成功：${group.name} → $code")
                    val alarmsInGroupAgain = repository.getAlarmsByGroup(group.id)
                    val record = CloudShareRecord(
                        shareCode = code,
                        groupName = group.name,
                        itemCount = alarmsInGroupAgain.size,
                        groupType = "alarm",
                        sourceGroupId = group.id
                    )
                    cloudShareDao.insertRecord(record)
                } else {
                    addLog("云端分享失败：上传返回空")
                }
                code
            } catch (e: Exception) {
                addLog("云端分享异常：${e.message}")
                null
            } finally {
                _cloudShareLoading.value = false
            }
        }
    }

    /** 从云端通过分享码导入闹钟组 */
    suspend fun importAlarmGroupFromCloud(shareCode: String): Boolean {
        _cloudShareLoading.value = true
        return withContext(Dispatchers.IO) {
            try {
                val jsonString = cloudService.downloadConfig(shareCode)
                if (jsonString == null) {
                    _cloudImportResult.value = "下载失败，请检查分享码是否正确"
                    addLog("云端导入失败：下载返回空 (code=$shareCode)")
                    return@withContext false
                }

                val root = JSONObject(jsonString)
                val exportType = root.optString("exportType", "")
                if (exportType != "alarm_group") {
                    _cloudImportResult.value = "数据格式不正确"
                    return@withContext false
                }

                val groupName = root.optString("groupName", "导入的闹钟组")
                val groupEnabled = root.optBoolean("isEnabled", true)
                android.util.Log.d("CloudSync", "importAlarm: group=$groupName isEnabled=$groupEnabled (raw=${root.opt("isEnabled")})")
                val alarmsArr = root.optJSONArray("alarms") ?: JSONArray()

                // 创建闹钟组
                val newGroupId = repository.insertGroup(
                    AlarmGroup(name = groupName, isEnabled = groupEnabled)
                )

                // 创建闹钟
                for (i in 0 until alarmsArr.length()) {
                    val a = alarmsArr.getJSONObject(i)
                    val alarm = Alarm(
                        groupId = newGroupId,
                        hour = a.getInt("hour"),
                        minute = a.getInt("minute"),
                        daysOfWeek = a.optString("daysOfWeek", "1,2,3,4,5,6,7"),
                        isEnabled = a.optBoolean("isEnabled", true),
                        label = a.optString("label", ""),
                        ringtonePath = a.optString("ringtonePath", "").ifEmpty { null },
                        vibrate = a.optBoolean("vibrate", true)
                    )
                    val insertId = repository.insertAlarm(alarm)
                    val finalAlarm = alarm.copy(id = insertId)
                    // 调度闹钟
                    val parentGroup = _groups.value.find { it.id == newGroupId }
                    AlarmScheduler.scheduleAlarm(getApplication(), finalAlarm, parentGroup)
                }

                _cloudImportResult.value = "成功导入「$groupName」（${alarmsArr.length()} 个闹钟）"
                addLog("云端导入成功：$groupName (${alarmsArr.length()} alarms, code=$shareCode)")
                true
            } catch (e: Exception) {
                _cloudImportResult.value = "导入失败：${e.message}"
                addLog("云端导入异常：${e.message}")
                false
            } finally {
                _cloudShareLoading.value = false
            }
        }
    }

    /** 从云端通过分享码导入打卡组 */
    suspend fun importCheckInGroupFromCloud(shareCode: String): Boolean {
        _cloudShareLoading.value = true
        return withContext(Dispatchers.IO) {
            try {
                val jsonString = cloudService.downloadConfig(shareCode)
                if (jsonString == null) {
                    _cloudImportResult.value = "下载失败，请检查分享码是否正确"
                    addLog("云端导入打卡组失败：下载返回空 (code=$shareCode)")
                    return@withContext false
                }

                val parsed = cloudService.parseCheckInConfig(jsonString)
                if (parsed == null) {
                    _cloudImportResult.value = "数据格式不正确（不是打卡组数据）"
                    return@withContext false
                }

                val (groupName, tasksArr) = parsed
                // 读取原始 JSON 中的 isEnabled 状态
                val isEnabled = try { JSONObject(jsonString).optBoolean("isEnabled", false) } catch (_: Exception) { false }
                val taskInputs = mutableListOf<CheckInTaskInput>()
                for (i in 0 until tasksArr.length()) {
                    val t = tasksArr.getJSONObject(i)
                    taskInputs.add(
                        CheckInTaskInput(
                            name = t.optString("name", "任务${i + 1}"),
                            hour = t.optInt("hour", 8).toString(),
                            minute = t.optInt("minute", 0).toString(),
                            ringtonePath = t.optString("ringtonePath", "").ifEmpty { null },
                            useTts = t.optBoolean("useTts", false)
                        )
                    )
                }

                addCheckInGroupSuspend(groupName, taskInputs, isEnabled)

                _cloudImportResult.value = "成功导入打卡组「$groupName」（${taskInputs.size} 个任务）"
                addLog("云端导入打卡组成功：$groupName (${taskInputs.size} tasks, code=$shareCode)")
                true
            } catch (e: Exception) {
                _cloudImportResult.value = "导入失败：${e.message}"
                addLog("云端导入打卡组异常：${e.message}")
                false
            } finally {
                _cloudShareLoading.value = false
            }
        }
    }

    /** 将指定打卡组及其任务上传到云端，返回分享码 */
    suspend fun shareCheckInGroupToCloud(group: CheckInGroupEntity, tasks: List<CheckInTaskEntity>): String? {
        _cloudShareLoading.value = true
        _cloudShareCode.value = null
        return withContext(Dispatchers.IO) {
            try {
                val tasksJson = JSONArray()
                tasks.forEach { t ->
                    tasksJson.put(JSONObject().apply {
                        put("name", t.name)
                        put("hour", t.hour)
                        put("minute", t.minute)
                        put("orderIndex", t.orderIndex)
                        put("ringtonePath", t.ringtonePath ?: "")
                        put("useTts", t.useTts)
                    })
                }
                val jsonString = cloudService.buildCheckInConfigJson(group.name, tasksJson)
                val rootWithEnabled = JSONObject(jsonString).apply {
                    put("isEnabled", group.isEnabled)
                }.toString()
                val code = cloudService.uploadConfig(rootWithEnabled)
                if (code != null) {
                    _cloudShareCode.value = code
                    val record = CloudShareRecord(
                        shareCode = code,
                        groupName = group.name,
                        itemCount = tasks.size,
                        groupType = "checkin",
                        sourceGroupId = group.id
                    )
                    cloudShareDao.insertRecord(record)
                    addLog("云端分享打卡组成功：${group.name} → $code")
                } else {
                    addLog("云端分享打卡组失败：上传返回空")
                }
                code
            } catch (e: Exception) {
                addLog("云端分享打卡组异常：${e.message}")
                null
            } finally {
                _cloudShareLoading.value = false
            }
        }
    }

    /** 清除云端导入结果提示 */
    fun clearCloudImportResult() {
        _cloudImportResult.value = null
    }

    /** 清除云端分享码 */
    fun clearCloudShareCode() {
        _cloudShareCode.value = null
    }

    /** 获取某个组的最近一条分享记录 */
    suspend fun getLastShareRecordForGroup(groupId: Long, groupType: String): CloudShareRecord? {
        return cloudShareDao.getLatestRecord(groupId, groupType)
    }

    /** 删除一条分享记录（仅本地） */
    suspend fun deleteCloudShareRecord(record: CloudShareRecord) {
        cloudShareDao.deleteRecord(record)
    }

    /** 删除分享记录，同时调用云端 API 删除对应的云数据 */
    suspend fun deleteCloudShareRecordWithCloud(record: CloudShareRecord, cloudSvc: CloudService) {
        withContext(Dispatchers.IO) {
            // 先删本地（不论云端结果如何）
            cloudShareDao.deleteRecord(record)
            try {
                cloudSvc.deleteConfig(record.shareCode)
            } catch (e: Exception) {
                Log.e("CloudShare", "云端删除失败，本地已删除", e)
            }
        }
    }
}
