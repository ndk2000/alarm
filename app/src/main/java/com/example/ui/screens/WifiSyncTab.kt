package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.cloud.*
import com.example.db.AlarmGroup
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity
import com.example.ui.AlarmViewModel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSyncTab(
    isOn: Boolean,
    customRingtones: List<String>,
    onToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRefreshMonitor: () -> Unit,
    syncStatus: AlarmViewModel.SyncStatus,
    syncTargetIp: String,
    onSetSyncTargetIp: (String) -> Unit,
    onSyncFromRemote: (com.example.alarm.WifiSyncClient.ImportMode) -> Unit,
    onClearSyncStatus: () -> Unit,
    appTheme: Int,
    appLanguage: String,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    discoveredDevices: List<Pair<String, String>> = emptyList(),
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    recordingPath: String = "",
    onDeleteRingtone: (String) -> Unit = {},
    groups: List<AlarmGroup> = emptyList(),
    checkInGroups: List<CheckInGroupEntity> = emptyList(),
    checkInTasksMap: Map<Long, List<CheckInTaskEntity>> = emptyMap(),
    cloudService: CloudService? = null
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("本地同步", "云端同步")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> LocalSyncContent(
                isOn = isOn,
                customRingtones = customRingtones,
                onToggle = onToggle,
                onExport = onExport,
                onImport = onImport,
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
                onStartDiscovery = onStartDiscovery,
                onStopDiscovery = onStopDiscovery,
                recordingPath = recordingPath,
                onDeleteRingtone = onDeleteRingtone
            )
            1 -> CloudSyncContent(
                groups = groups,
                checkInGroups = checkInGroups,
                checkInTasksMap = checkInTasksMap,
                cloudService = cloudService
            )
        }
    }
}

@Composable
private fun LocalSyncContent(
    isOn: Boolean,
    customRingtones: List<String>,
    onToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRefreshMonitor: () -> Unit,
    syncStatus: AlarmViewModel.SyncStatus,
    syncTargetIp: String,
    onSetSyncTargetIp: (String) -> Unit,
    onSyncFromRemote: (com.example.alarm.WifiSyncClient.ImportMode) -> Unit,
    onClearSyncStatus: () -> Unit,
    appTheme: Int,
    appLanguage: String,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    discoveredDevices: List<Pair<String, String>> = emptyList(),
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    recordingPath: String = "",
    onDeleteRingtone: (String) -> Unit = {}
) {
    val localIp = getLocalIpAddress() ?: ""
    val ipPrefix = if (localIp.isNotEmpty()) {
        localIp.substringBeforeLast(".") + "."
    } else {
        "192.168.1."
    }
    val ip = localIp.ifEmpty { stringResource(R.string.connected_wlan) }
    val webAddress = "http://$ip:8080"
    val isZh = appLanguage == "zh"

    var showImportModeDialog by remember { mutableStateOf(false) }
    var pendingImportMode by remember { mutableStateOf(com.example.alarm.WifiSyncClient.ImportMode.CLEAR) }
    var ringtoneToDelete by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- WiFi 同步开关与地址 ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.wifi_sync), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(stringResource(R.string.wifi_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                        Switch(
                            checked = isOn,
                            onCheckedChange = { onToggle(it) }
                        )
                    }

                    if (isOn) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.browser_url_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = webAddress,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFADC6FF),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.wifi_network_hint),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 远程同步：主动拉取另一台手机的数据 ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.remote_sync), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("点击下方发现的设备直接同步：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        discoveredDevices.forEach { (name, deviceIp) ->
                            Card(
                                onClick = {
                                    onSetSyncTargetIp(deviceIp)
                                    showImportModeDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(deviceIp, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = syncTargetIp,
                        onValueChange = {
                            var newVal = it
                            if (newVal.isEmpty()) {
                                newVal = ipPrefix
                            } else if (!newVal.startsWith(ipPrefix)) {
                                if (newVal.length < ipPrefix.length) {
                                    newVal = ipPrefix
                                }
                            }
                            onSetSyncTargetIp(newVal)
                            onClearSyncStatus()
                        },
                        label = { Text(stringResource(R.string.enter_ip_label)) },
                        placeholder = { Text(ipPrefix + "xxx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null) },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    LaunchedEffect(Unit) {
                        if (syncTargetIp.isEmpty()) {
                            onSetSyncTargetIp(ipPrefix)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showImportModeDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        enabled = syncTargetIp.isNotBlank() && syncStatus !is AlarmViewModel.SyncStatus.Connecting
                    ) {
                        when (syncStatus) {
                            is AlarmViewModel.SyncStatus.Connecting -> {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.syncing), fontSize = 14.sp)
                            }
                            else -> {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.pull_from_remote), fontSize = 13.sp)
                            }
                        }
                    }

                    when (val status = syncStatus) {
                        is AlarmViewModel.SyncStatus.Success -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = status.message, fontSize = 12.sp, color = Color(0xFF4CAF50))
                            }
                        }
                        is AlarmViewModel.SyncStatus.Error -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = status.message, fontSize = 12.sp, color = Color(0xFFEF5350))
                            }
                        }
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.remote_sync_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
                }
            }
        }

        // --- 备份与恢复 ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("备份与恢复", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onExport,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出配置", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入配置", fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("备份数据文件，可用于迁移到新手机", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // --- 铃声管理 ---
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("铃声管理", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("自定义铃声", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    customRingtones.forEach { path ->
                        val fileName = path.substringAfterLast("/")
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = fileName,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { ringtoneToDelete = path }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    if (customRingtones.isEmpty()) {
                        Text(stringResource(R.string.no_custom_ringtones), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    // ── 导入模式选择对话框 ──
    if (showImportModeDialog) {
        AlertDialog(
            onDismissRequest = { showImportModeDialog = false },
            title = { Text("选择导入模式") },
            text = { Text("选择从远程同步时如何处理现有数据") },
            confirmButton = {
                Button(onClick = {
                    onSyncFromRemote(pendingImportMode)
                    showImportModeDialog = false
                }) {
                    Text("清空并导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportModeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── 删除铃声确认对话框 ──
    ringtoneToDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { ringtoneToDelete = null },
            title = { Text("删除自定义铃声") },
            text = { Text("确认删除「${path.substringAfterLast("/")}」？") },
            confirmButton = {
                Button(
                    onClick = { onDeleteRingtone(path); ringtoneToDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { ringtoneToDelete = null }) {
                    Text(if (isZh) "取消" else "Cancel")
                }
            }
        )
    }
}

// ========== 云端同步内部 Tab ==========

@Composable
private fun CloudSyncContent(
    groups: List<AlarmGroup>,
    checkInGroups: List<CheckInGroupEntity>,
    checkInTasksMap: Map<Long, List<CheckInTaskEntity>>,
    cloudService: CloudService?
) {
    val viewModel: AlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val scope = rememberCoroutineScope()

    var connectionStatus by remember { mutableStateOf<CloudService.ConnectionStatus>(CloudService.ConnectionStatus.Checking) }
    var uploadProgress by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    LaunchedEffect(cloudService) {
        if (cloudService != null) {
            connectionStatus = CloudService.ConnectionStatus.Checking
            connectionStatus = cloudService.checkConnection()
        } else {
            connectionStatus = CloudService.ConnectionStatus.NotConfigured
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 连接状态 ──
        item {
            val bg = when (connectionStatus) {
                is CloudService.ConnectionStatus.Connected -> Color(0xFFE8F5E9)
                is CloudService.ConnectionStatus.Error -> Color(0xFFFFEBEE)
                is CloudService.ConnectionStatus.Checking -> Color(0xFFFFF3E0)
                is CloudService.ConnectionStatus.NotConfigured -> Color(0xFFF5F5F5)
            }
            val fc = when (connectionStatus) {
                is CloudService.ConnectionStatus.Connected -> Color(0xFF2E7D32)
                is CloudService.ConnectionStatus.Error -> Color(0xFFC62828)
                is CloudService.ConnectionStatus.Checking -> Color(0xFFE65100)
                is CloudService.ConnectionStatus.NotConfigured -> Color(0xFF616161)
            }
            val txt = when (connectionStatus) {
                is CloudService.ConnectionStatus.Connected -> "✅ 云端已连接"
                is CloudService.ConnectionStatus.Error -> "❌ 连接失败"
                is CloudService.ConnectionStatus.Checking -> "⏳ 检测中..."
                is CloudService.ConnectionStatus.NotConfigured -> "⚠️ 未配置"
            }
            Surface(Modifier.fillMaxWidth(), color = bg, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(txt, color = fc, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (connectionStatus is CloudService.ConnectionStatus.Error) {
                        TextButton(onClick = {
                            scope.launch {
                                connectionStatus = CloudService.ConnectionStatus.Checking
                                connectionStatus = cloudService?.checkConnection() ?: CloudService.ConnectionStatus.NotConfigured
                            }
                        }) { Text("重试", fontSize = 12.sp) }
                    }
                }
            }
        }

        // ── 结果信息 ──
        if (resultMessage.isNotBlank()) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                    color = if (resultMessage.contains("❌")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(resultMessage, modifier = Modifier.padding(12.dp), fontSize = 12.sp,
                        color = if (resultMessage.contains("❌")) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── 上传全部 ──
        item {
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("上传全部到云端", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("已上传的不再重复上传", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isUploading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(4.dp)); Text(uploadProgress, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    Button(onClick = {
                        scope.launch {
                            isUploading = true; resultMessage = ""; var ok = 0; var skip = 0
                            for (g in groups) {
                                val existing = viewModel.cloudShareRecords.value.find { it.sourceGroupId == g.id && it.groupType == "alarm" }
                                if (existing != null) {
                                    try {
                                        val cloudJson = cloudService?.downloadConfig(existing.shareCode)
                                        if (cloudJson != null) {
                                            val root = org.json.JSONObject(cloudJson)
                                            val cloudEnabled = root.optBoolean("isEnabled", false)
                                            val hasField = root.has("isEnabled")
                                            if (hasField && cloudEnabled == g.isEnabled) { skip++; continue }
                                        }
                                    } catch (_: Exception) { }
                                }
                                uploadProgress = "上传闹钟组: ${g.name}..."; try { viewModel.shareAlarmGroupToCloud(g); ok++ } catch (e: Exception) { resultMessage += "❌ ${g.name}: ${e.message}\n" }
                            }
                            for (g in checkInGroups) {
                                val existing = viewModel.cloudShareRecords.value.find { it.sourceGroupId == g.id && it.groupType == "checkin" }
                                if (existing != null) {
                                    try {
                                        val cloudJson = cloudService?.downloadConfig(existing.shareCode)
                                        if (cloudJson != null) {
                                            val root = org.json.JSONObject(cloudJson)
                                            val cloudEnabled = root.optBoolean("isEnabled", false)
                                            val hasField = root.has("isEnabled")
                                            if (hasField && cloudEnabled == g.isEnabled) { skip++; continue }
                                        }
                                    } catch (_: Exception) { }
                                }
                                uploadProgress = "上传打卡组: ${g.name}..."; try { viewModel.shareCheckInGroupToCloud(g, checkInTasksMap[g.id] ?: emptyList()); ok++ } catch (e: Exception) { resultMessage += "❌ ${g.name}: ${e.message}\n" }
                            }
                        }
                    },
                        enabled = !isUploading && cloudService != null && connectionStatus is CloudService.ConnectionStatus.Connected
                    ) {
                        if (isUploading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                        Text(if (isUploading) "上传中..." else "🔼 上传全部")
                    }
                }
            }
        }

        // ── 同步全部到本地 ──
        item {
            Card(shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("从云端同步全部到本地", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("下载所有云端数据并导入本地", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isDownloading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(4.dp)); Text(downloadProgress, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    Button(onClick = {
                        scope.launch {
                            isDownloading = true; resultMessage = ""; var ok = 0
                            try {
                                val configs = cloudService?.listConfigs() ?: emptyList()
                                resultMessage = "【v3 云端共${configs.size}个配置】\n"
                                for (c in configs) {
                                    resultMessage += "  type=${c.type.name} code=${c.shareCode} preview=${c.preview.take(20)}\n"
                                }
                                resultMessage += "\n"
                                if (configs.isEmpty()) { resultMessage = "云端没有任何配置数据"; isDownloading = false; return@launch }
                                for (c in configs) {
                                    downloadProgress = "下载: ${c.shareCode}..."
                                    // 下载JSON提取组名，查数据库判断是否已有
                                    val json = try { cloudService?.downloadConfig(c.shareCode) } catch (_: Exception) { null }
                                    val name = json?.let { try { org.json.JSONObject(it).optString("groupName", "") } catch (_: Exception) { "" } } ?: ""
                                    if (name.isNotBlank() && viewModel.checkGroupNameExists(name, c.type == ShareDataType.ALARM_GROUP)) {
                                        resultMessage += "⏭️ ${name} 本地已有，跳过\n"; continue
                                    }
                                    try {
                                        val ok2 = if (c.type == ShareDataType.ALARM_GROUP) viewModel.importAlarmGroupFromCloud(c.shareCode) else viewModel.importCheckInGroupFromCloud(c.shareCode)
                                        if (ok2) { ok++; resultMessage += "✅ ${c.shareCode} 导入成功\n" }
                                        else { resultMessage += "❌ ${c.shareCode} 导入失败\n" }
                                    } catch (e: Exception) { resultMessage += "❌ ${c.shareCode}: ${e.message}\n" }
                                }
                                resultMessage += "✅ 同步完成：成功导入${ok}项"
                            } catch (e: Exception) { resultMessage = "❌ 同步失败: ${e.message}" }
                            isDownloading = false
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        enabled = !isDownloading && cloudService != null && connectionStatus is CloudService.ConnectionStatus.Connected
                    ) {
                        if (isDownloading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                        Text(if (isDownloading) "下载中..." else "🔽 同步全部")
                    }
                }
            }
        }

        // ── 提示 ──
        item {
            var showConfirm by remember { mutableStateOf(false) }
            if (showConfirm) {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠️ 确定清除全部本地数据？", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Text("将删除所有本地闹钟、打卡和分享记录，此操作不可撤销！", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showConfirm = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                            Button(onClick = { showConfirm = false; viewModel.clearAllLocalData(); resultMessage = "✅ 已清除全部本地数据" },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) { Text("确认清除") }
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { showConfirm = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("清除全部本地数据", fontSize = 13.sp)
                }
            }
        }
    }
}

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
