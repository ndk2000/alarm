package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
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
import com.example.db.*
import com.example.db.CheckInDao
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity
import com.example.ui.dialogs.CheckInTaskInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import java.io.File
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

class AlarmViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val repository: AlarmRepository
    private val checkInDao: CheckInDao
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

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

    // 录音存放路径配置（默认使用应用专属目录，兼容 Android 11+ 分区存储）
    private val _customRecordingPath = MutableStateFlow("")
    val customRecordingPath: StateFlow<String> = _customRecordingPath.asStateFlow()

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

    fun checkForUpdates() {
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
                    val json = org.json.JSONObject(response)
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
                    
                    if (tagName != currentVersion && downloadUrl.isNotEmpty()) {
                        _updateInfo.value = UpdateInfo(tagName, downloadUrl, body)
                        addLog("发现新版本: $tagName")
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

    fun setCustomRecordingPath(path: String) {
        _customRecordingPath.value = path
        val prefs = getApplication<Application>().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("recording_path", path).apply()
        loadCustomRingtones() // 路径改变，刷新列表

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

    fun syncFromRemote(context: Context, importMode: com.example.alarm.WifiSyncClient.ImportMode = com.example.alarm.WifiSyncClient.ImportMode.CLEAR) {
        val ip = _syncTargetIp.value.trim()
        if (ip.isEmpty()) {
            _syncStatus.value = SyncStatus.Error("请输入 IP 地址")
            return
        }
        _syncStatus.value = SyncStatus.Connecting
        viewModelScope.launch {
            val client = com.example.alarm.WifiSyncClient(context)
            val result = client.syncFromRemote(ip, importMode = importMode)
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
        // 恢复计时器上次拨盘值
        _timerHours.value = settings.getInt("timer_hours", 0)
        _timerMinutes.value = settings.getInt("timer_minutes", 0)
        _timerSeconds.value = settings.getInt("timer_seconds", 0)

        _autoUpdateEnabled.value = settings.getBoolean("auto_update", true)

        if (_autoUpdateEnabled.value) {
            checkForUpdates()
        }

        val prefs = getApplication<Application>().getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        val savedEngine = prefs.getString("tts_engine", "") ?: ""
        val savedVoice = prefs.getString("tts_voice", "") ?: ""
        _selectedTtsEngine.value = savedEngine
        _selectedTtsVoiceName.value = savedVoice
        addLog("恢复 TTS 引擎: $savedEngine, 语音: $savedVoice")
        
        // 扫描可用 TTS 引擎
        scanTtsEngines()
        
        // 如果保存了引擎包名，则使用该引擎创建 TTS 实例，否则使用默认引擎
        val enginePackage = if (savedEngine.isNotEmpty()) savedEngine else null
        tts = TextToSpeech(application, this, enginePackage)
        val defaultEngine = tts?.defaultEngine ?: "未知"
        addLog("系统默认 TTS 引擎: $defaultEngine")

        val db = AlarmDatabase.getDatabase(application, viewModelScope)
        val dao = db.alarmDao()
        repository = AlarmRepository(dao, db.checkinDao())
        checkInDao = db.checkinDao()

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
        loadCustomRingtones()
        loadSystemRingtones()
        loadLocalRecordings() // 在初始化时自动触发读取本地录音机保存的列表

        // 启动后台闹钟监控服务，在状态栏显示闹钟倒计时通知
        refreshBackgroundMonitor()
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
                File("/storage/emulated/0/sound_recorder")
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
        vibrate: Boolean
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
                vibrate = vibrate
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
                    
                    // Clear existing data (Room cascade delete will handle alarms)
                    val db = AlarmDatabase.getDatabase(getApplication(), viewModelScope)
                    val existingGroups = db.alarmDao().getAllGroups()
                    existingGroups.forEach { db.alarmDao().deleteGroup(it) }

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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            addLog("TTS 引擎初始化成功")
            // 尝试使用更精确的中国大陆区域设置
            val result = tts?.setLanguage(Locale.CHINA)
            addLog("TTS 语言设置结果: $result")
            
            // 配置音频属性
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                addLog("警告: 缺失中文语音包，尝试备用中文设置...")
                tts?.setLanguage(Locale.CHINESE)
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
        } else {
            addLog("错误: TTS 初始化失败，状态码: $status")
        }
    }

    fun testTts(hour: Int) {
        val text = "现在是北京时间${hour}点整"
        addLog("尝试播放测试语音: $text (音调:$ttsPitch, 语速:$ttsRate)")
        if (isTtsReady) {
            // 播放前再次强制设置参数，解决部分引擎在长时空闲后重置的问题
            tts?.setPitch(ttsPitch)
            tts?.setSpeechRate(ttsRate)
            
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test_chime")
            if (result == TextToSpeech.ERROR) {
                addLog("错误: speak 接口调用返回 ERROR")
            } else {
                addLog("播放指令已发送，等待系统发声...")
            }
        } else {
            addLog("警告: TTS 尚未就绪，尝试强制恢复播放")
            tts?.setLanguage(Locale.CHINA)
            tts?.setPitch(ttsPitch)
            tts?.setSpeechRate(ttsRate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "retry_test")
        }
    }

    fun setTtsPitch(pitch: Float) {
        ttsPitch = pitch
        val result = tts?.setPitch(pitch)
        addLog("调节音调为: $pitch, 结果: $result")
    }

    fun setTtsRate(rate: Float) {
        ttsRate = rate
        val result = tts?.setSpeechRate(rate)
        addLog("调节语速为: $rate, 结果: $result")
    }

    // 扫描系统中所有可用的 TTS 引擎
    fun scanTtsEngines() {
        try {
            // 修复：不要直接使用成员变量 tts（可能尚未初始化），而是创建一个临时实例来获取所有引擎
            val tempTts = TextToSpeech(getApplication(), null)
            val enginesList = tempTts.engines
            _availableTtsEngines.value = enginesList
            addLog("扫描到 ${enginesList.size} 个 TTS 引擎")
            enginesList.forEach { engine ->
                addLog("  TTS 引擎: ${engine.label} (${engine.name})")
            }
            tempTts.shutdown()
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
        if (engineName == _selectedTtsEngine.value) return
        addLog("切换 TTS 引擎至: $engineName")
        
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

    // 设置 TTS 语音：保存偏好、调用 setVoice
    fun setTtsVoice(voiceName: String) {
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

    // 计时器相关操作
    fun startTimer(durationSeconds: Int) {
        if (durationSeconds <= 0) return
        timerJobInstance?.cancel()
        _timerRemainingSeconds.value = durationSeconds
        _isTimerRunning.value = true

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

        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "STOP_RINGING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    private fun onTimerFinished() {
        // 倒计时结束，拉起响铃
        val intent = Intent(getApplication(), AlarmService::class.java).apply {
            action = "START_RINGING"
            putExtra("ALARM_LABEL", "计时结束")
            putExtra("ALARM_VIBRATE", true)
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

    fun addCheckInGroup(name: String, tasks: List<CheckInTaskInput>) {
        viewModelScope.launch(Dispatchers.IO) {
            val groupId = checkInDao.insertGroup(
                CheckInGroupEntity(name = name, isEnabled = false)
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
            loadAllCheckInTasks()
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
}
