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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.AlarmViewModel
import java.net.Inet4Address
import java.net.NetworkInterface

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

                    // IP 地址输入
                    OutlinedTextField(
                        value = syncTargetIp,
                        onValueChange = {
                            var newVal = it
                            // 如果用户删光了，自动补全前缀
                            if (newVal.isEmpty()) {
                                newVal = ipPrefix
                            } else if (!newVal.startsWith(ipPrefix)) {
                                // 如果用户尝试修改前缀，我们也强制拉回来，除非他们是真的想改（比如换网段）
                                // 这里简单点，如果输入长度小于前缀长度且不匹配，补上前缀
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
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri
                        )
                    )

                    // 自动补齐前缀逻辑：当输入框聚焦且为空时，自动填入前缀
                    LaunchedEffect(Unit) {
                        if (syncTargetIp.isEmpty()) {
                            onSetSyncTargetIp(ipPrefix)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 同步按钮
                    Button(
                        onClick = { showImportModeDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        enabled = syncTargetIp.isNotBlank() && syncStatus !is AlarmViewModel.SyncStatus.Connecting
                    ) {
                        when (syncStatus) {
                            is AlarmViewModel.SyncStatus.Connecting -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
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

                    // 同步状态提示
                    when (val status = syncStatus) {
                        is AlarmViewModel.SyncStatus.Success -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = status.message,
                                    fontSize = 12.sp,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                        is AlarmViewModel.SyncStatus.Error -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null,
                                    tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = status.message,
                                    fontSize = 12.sp,
                                    color = Color(0xFFEF5350)
                                )
                            }
                        }
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.remote_sync_desc),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                        lineHeight = 14.sp
                    )
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
                        Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.local_backup), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.backup_export), fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onImport,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_restore), fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // --- 已存放的自定义铃声 ---
        item {
            Text(
                stringResource(R.string.uploaded_ringtones, customRingtones.size),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        if (customRingtones.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_uploaded_ringtones),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(customRingtones) { name ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, tint = Color(0xFFADC6FF))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { ringtoneToDelete = name }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 同步模式选择对话框
    if (showImportModeDialog) {
        AlertDialog(
            onDismissRequest = { showImportModeDialog = false },
            title = { Text(stringResource(R.string.sync_mode_title)) },
            text = { Text(stringResource(R.string.sync_mode_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showImportModeDialog = false
                    onSyncFromRemote(com.example.alarm.WifiSyncClient.ImportMode.CLEAR)
                }) {
                    Text(stringResource(R.string.sync_mode_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportModeDialog = false
                    onSyncFromRemote(com.example.alarm.WifiSyncClient.ImportMode.MERGE)
                }) {
                    Text(stringResource(R.string.sync_mode_merge))
                }
            }
        )
    }

    // 删除铃声确认对话框
    ringtoneToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { ringtoneToDelete = null },
            title = {
                Text(if (isZh) "删除铃声" else "Delete Ringtone")
            },
            text = {
                Text(if (isZh) "确定删除「$name」吗？此操作不可撤销。" else "Delete「$name」? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    ringtoneToDelete = null
                    onDeleteRingtone(name)
                }) {
                    Text(
                        if (isZh) "删除" else "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
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

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.isLoopback) {
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
