package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import com.example.R
import com.example.cloud.*
import com.example.db.AlarmGroup
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity
import com.example.db.CloudShareRecord
import com.example.ui.AlarmViewModel
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import android.util.Log
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudShareTab(
    cloudService: CloudService,
    groups: List<AlarmGroup>,
    checkInGroups: List<CheckInGroupEntity>,
    checkInTasksMap: Map<Long, List<CheckInTaskEntity>>,
    cloudShareCode: String?,
    cloudShareLoading: Boolean,
    cloudImportResult: String?,
    onShareAlarmGroup: (AlarmGroup) -> Unit,
    onShareCheckInGroup: (CheckInGroupEntity) -> Unit,
    onImportFromCloud: (String) -> Unit,
    onSelectService: (String) -> Unit,
    onSetSupabaseCredentials: (String, String) -> Unit,
    onSetFirebaseCredentials: (String, String) -> Unit,
    onClearCloudShareCode: () -> Unit,
    onClearCloudImportResult: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onNavigateToGroup: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showQrDialog by remember { mutableStateOf<String?>(null) }
    var showScannerDialog by remember { mutableStateOf(false) }
    var showManagePanel by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<CloudShareRecord?>(null) }
    var cloudConfigs by remember { mutableStateOf<List<CloudService.CloudConfig>>(emptyList()) }
    var manageLoading by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<CloudService.ConnectionStatus>(CloudService.ConnectionStatus.Checking) }

    val viewModel: AlarmViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val cloudShareRecords by viewModel.cloudShareRecords.collectAsState()

    val scope = rememberCoroutineScope()

    // 监听服务切换或凭据变化，更新连接状态
    LaunchedEffect(cloudService) {
        connectionStatus = CloudService.ConnectionStatus.Checking
        connectionStatus = cloudService.checkConnection()
    }

    val tabs = listOf(stringResource(R.string.share_alarms), stringResource(R.string.share_checkin), stringResource(R.string.cloud_import), stringResource(R.string.share_records))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_share)) },
                actions = {
                    val context = LocalContext.current
                    var currentService by remember { mutableStateOf(context.getSelectedService()) }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                val next = if (currentService == "firebase") "supabase" else "firebase"
                                currentService = next
                                onSelectService(next)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (currentService == "firebase") Icons.Default.Whatshot else Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (currentService == "firebase") "Firebase" else "Supabase",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = { showManagePanel = true }) {
                        Icon(Icons.Default.CloudQueue, contentDescription = stringResource(R.string.cloud_manage))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // Content
            when (selectedTab) {
                0 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                        ConnectionStatusBanner(connectionStatus, onRetry = {
                            scope.launch {
                                connectionStatus = CloudService.ConnectionStatus.Checking
                                connectionStatus = cloudService.checkConnection()
                            }
                        })
                        CloudUploadPanel(
                            cloudService = cloudService,
                            groups = groups,
                            checkInGroups = emptyList(),
                            cloudShareCode = cloudShareCode,
                            cloudShareLoading = cloudShareLoading,
                            onShareAlarmGroup = { group ->
                                scope.launch {
                                    val existing = viewModel.getLastShareRecordForGroup(group.id, "alarm")
                                    if (existing != null) {
                                        selectedTab = 3
                                        onShowSnackbar("该组已分享过，请不要重复分享同样的内容")
                                    } else {
                                        onShareAlarmGroup(group)
                                    }
                                }
                            },
                            onShareCheckInGroup = {},
                        onShowQr = { showQrDialog = it },
                        onClearShareCode = onClearCloudShareCode
                    )
                }
                1 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    ConnectionStatusBanner(connectionStatus, onRetry = {
                        scope.launch {
                            connectionStatus = CloudService.ConnectionStatus.Checking
                            connectionStatus = cloudService.checkConnection()
                        }
                    })
                    CloudUploadPanel(
                        cloudService = cloudService,
                        groups = emptyList(),
                        checkInGroups = checkInGroups,
                        cloudShareCode = cloudShareCode,
                        cloudShareLoading = cloudShareLoading,
                        onShareAlarmGroup = {},
                        onShareCheckInGroup = { group ->
                            scope.launch {
                                val existing = viewModel.getLastShareRecordForGroup(group.id, "checkin")
                                if (existing != null) {
                                    selectedTab = 3
                                    onShowSnackbar("该组已分享过，请不要重复分享同样的内容")
                                } else {
                                    onShareCheckInGroup(group)
                                }
                            }
                        },
                    onShowQr = { showQrDialog = it },
                    onClearShareCode = onClearCloudShareCode
                )
            }
            2 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                ConnectionStatusBanner(connectionStatus, onRetry = {
                    scope.launch {
                        connectionStatus = CloudService.ConnectionStatus.Checking
                        connectionStatus = cloudService.checkConnection()
                    }
                })
                CloudDownloadPanel(
                    onImportFromCloud = onImportFromCloud,
                    onShowScanner = { showScannerDialog = true },
                    cloudImportResult = cloudImportResult,
                    onClearResult = onClearCloudImportResult
                )
            }
            3 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                ConnectionStatusBanner(connectionStatus, onRetry = {
                    scope.launch {
                        connectionStatus = CloudService.ConnectionStatus.Checking
                        connectionStatus = cloudService.checkConnection()
                    }
                })
                ShareRecordsPanel(
                    shareRecords = cloudShareRecords,
                    groups = groups,
                    checkInGroups = checkInGroups,
                    onNavigateToGroup = onNavigateToGroup,
                    onShowQr = { showQrDialog = it },
                    onDeleteRecord = { record -> recordToDelete = record }
                )
            }
        }
        }
    }

    // QR Code dialog
    showQrDialog?.let { code ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showQrDialog = null },
            title = { Text(stringResource(R.string.cloud_share_code)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.cloud_share_desc),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    QrCodeUtils.encodeToBitmap(code, 256)?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = code,
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        showQrDialog = null
                        onShowSnackbar(code)
                    }) {
                        Text(stringResource(R.string.copy_share_code))
                    }
                    // 分享到微信
                    TextButton(onClick = {
                        shareQrToWeChat(context, code)
                    }) {
                        Text("分享到微信", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // 删除分享记录确认对话框
    recordToDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("删除分享记录") },
            text = {
                Text(
                    "删除此数据将不能再恢复\n\n确定删除「${record.groupName}」的分享记录及云端数据吗？",
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val r = record
                        recordToDelete = null
                        scope.launch {
                            viewModel.deleteCloudShareRecordWithCloud(r, cloudService)
                            onShowSnackbar("已删除")
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Scanner dialog
    if (showScannerDialog) {
        QrScannerDialog(
            onScanResult = { result ->
                showScannerDialog = false
                // 处理分享码：如果包含非字母数字字符（如URL），尝试提取6位字母数字串；
                // 否则直接使用处理后的原始结果
                val trimmedResult = result.trim()
                val code = if (trimmedResult.length > 6) {
                    // 尝试在字符串中寻找连续的6位字母数字
                    Regex("[A-Z0-9]{6}").find(trimmedResult.uppercase())?.value ?: trimmedResult
                } else {
                    trimmedResult
                }
                onImportFromCloud(code.uppercase())
            },
            onDismiss = { showScannerDialog = false }
        )
    }

    // Manage panel
    if (showManagePanel) {
        CloudManagePanel(
            cloudService = cloudService,
            cloudConfigs = cloudConfigs,
            onDismiss = { showManagePanel = false },
            onLoadConfigs = { cloudConfigs = it },
            onShowSnackbar = onShowSnackbar,
            manageLoading = manageLoading,
            onSetManageLoading = { manageLoading = it },
            onSetSupabaseCredentials = onSetSupabaseCredentials,
            onSetFirebaseCredentials = onSetFirebaseCredentials
        )
    }
}

@Composable
private fun ConnectionStatusBanner(
    status: CloudService.ConnectionStatus,
    onRetry: () -> Unit
) {
    val backgroundColor = when (status) {
        is CloudService.ConnectionStatus.Connected -> Color(0xFFE8F5E9)
        is CloudService.ConnectionStatus.Error -> Color(0xFFFFEBEE)
        is CloudService.ConnectionStatus.Checking -> Color(0xFFFFF3E0)
        is CloudService.ConnectionStatus.NotConfigured -> Color(0xFFF5F5F5)
    }
    
    val contentColor = when (status) {
        is CloudService.ConnectionStatus.Connected -> Color(0xFF2E7D32)
        is CloudService.ConnectionStatus.Error -> Color(0xFFC62828)
        is CloudService.ConnectionStatus.Checking -> Color(0xFFE65100)
        is CloudService.ConnectionStatus.NotConfigured -> Color(0xFF616161)
    }

    val text = when (status) {
        is CloudService.ConnectionStatus.Connected -> "云端服务已连接"
        is CloudService.ConnectionStatus.Error -> "云端连接失败: ${status.message}"
        is CloudService.ConnectionStatus.Checking -> "正在检测云端连接..."
        is CloudService.ConnectionStatus.NotConfigured -> "云端服务未配置"
    }

    val icon = when (status) {
        is CloudService.ConnectionStatus.Connected -> Icons.Default.CloudDone
        is CloudService.ConnectionStatus.Error -> Icons.Default.CloudOff
        is CloudService.ConnectionStatus.Checking -> Icons.Default.Sync
        is CloudService.ConnectionStatus.NotConfigured -> Icons.Default.CloudQueue
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = contentColor,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (status is CloudService.ConnectionStatus.Error) {
                Text(
                    text = "重试",
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun CloudUploadPanel(
    cloudService: CloudService,
    groups: List<AlarmGroup>,
    checkInGroups: List<CheckInGroupEntity>,
    cloudShareCode: String?,
    cloudShareLoading: Boolean,
    onShareAlarmGroup: (AlarmGroup) -> Unit,
    onShareCheckInGroup: (CheckInGroupEntity) -> Unit,
    onShowQr: (String) -> Unit,
    onClearShareCode: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (cloudShareLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                stringResource(R.string.cloud_sharing),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (cloudShareCode != null) {
            Text(
                stringResource(R.string.cloud_share_success),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.cloud_share_code_hint),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { onShowQr(cloudShareCode) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.view_qr_code))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onClearShareCode,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.close))
            }
        } else {
            // 统一一个 LazyColumn，上下排列不挤压
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Alarm groups header
                if (groups.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.alarm_groups),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(groups, key = { "alarm_${it.id}" }) { group ->
                        CloudShareItemRow(
                            name = group.name,
                            onShare = { onShareAlarmGroup(group) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // Check-in groups header
                if (checkInGroups.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.check_in_groups),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(checkInGroups, key = { "checkin_${it.id}" }) { group ->
                        CloudShareItemRow(
                            name = group.name,
                            onShare = { onShareCheckInGroup(group) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                // empty state
                if (groups.isEmpty() && checkInGroups.isEmpty()) {
                    item {
                        Text(
                            "暂无可用分组",
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudShareItemRow(
    name: String,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                name,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onShare) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cloud_share))
            }
        }
    }
}

@Composable
private fun CloudDownloadPanel(
    onImportFromCloud: (String) -> Unit,
    onShowScanner: () -> Unit,
    cloudImportResult: String?,
    onClearResult: () -> Unit
) {
    var inputCode by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(R.string.cloud_import_method),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Scanner button
        Button(
            onClick = onShowScanner,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.scan_qr_code))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Manual input
        OutlinedTextField(
            value = inputCode,
            onValueChange = { inputCode = it.uppercase() },
            label = { Text(stringResource(R.string.input_share_code)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { if (inputCode.isNotBlank()) onImportFromCloud(inputCode) },
            modifier = Modifier.fillMaxWidth(),
            enabled = inputCode.isNotBlank()
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.import_from_cloud))
        }

        // Import result
        cloudImportResult?.let { result ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (result.contains("✅")) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        result,
                        color = if (result.contains("✅")) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClearResult) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudManagePanel(
    cloudService: CloudService,
    cloudConfigs: List<CloudService.CloudConfig>,
    onDismiss: () -> Unit,
    onLoadConfigs: (List<CloudService.CloudConfig>) -> Unit,
    onShowSnackbar: (String) -> Unit,
    manageLoading: Boolean,
    onSetManageLoading: (Boolean) -> Unit,
    onSetSupabaseCredentials: (String, String) -> Unit,
    onSetFirebaseCredentials: (String, String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showCredentialsEdit by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        onSetManageLoading(true)
        try {
            val configs = cloudService.listConfigs()
            onLoadConfigs(configs)
        } catch (e: Exception) {
            onShowSnackbar("加载配置失败: ${e.message}")
        } finally {
            onSetManageLoading(false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cloud_manage))
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showCredentialsEdit = !showCredentialsEdit }) {
                    Icon(
                        imageVector = if (showCredentialsEdit) Icons.AutoMirrored.Filled.List else Icons.Default.Settings,
                        contentDescription = "Edit Credentials"
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showCredentialsEdit) {
                    val serviceType = context.getSelectedService()
                    if (serviceType == "supabase") {
                        var url by remember { mutableStateOf(context.getCloudPrefs().getString(CloudConfigKeys.PREF_SUPABASE_URL, "") ?: "") }
                        var key by remember { mutableStateOf(context.getCloudPrefs().getString(CloudConfigKeys.PREF_SUPABASE_ANON_KEY, "") ?: "") }

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Supabase URL") },
                            placeholder = { Text("留空使用内置") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("Supabase Anon Key") },
                            placeholder = { Text("留空使用内置") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            onSetSupabaseCredentials(url, key)
                            onShowSnackbar("Supabase 凭据已更新")
                            showCredentialsEdit = false
                            scope.launch {
                                onSetManageLoading(true)
                                try {
                                    val configs = cloudService.listConfigs()
                                    onLoadConfigs(configs)
                                } catch (e: Exception) {
                                    onShowSnackbar("加载配置失败: ${e.message}")
                                } finally {
                                    onSetManageLoading(false)
                                }
                            }
                        }) {
                            Text("保存凭据")
                        }
                    } else {
                        var projectId by remember { mutableStateOf(context.getCloudPrefs().getString(CloudConfigKeys.PREF_FIREBASE_PROJECT_ID, "") ?: "") }
                        var apiKey by remember { mutableStateOf(context.getCloudPrefs().getString(CloudConfigKeys.PREF_FIREBASE_API_KEY, "") ?: "") }

                        OutlinedTextField(
                            value = projectId,
                            onValueChange = { projectId = it },
                            label = { Text("Firebase Project ID") },
                            placeholder = { Text("留空使用内置") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("Firebase API Key") },
                            placeholder = { Text("留空使用内置") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            onSetFirebaseCredentials(projectId, apiKey)
                            onShowSnackbar("Firebase 凭据已更新")
                            showCredentialsEdit = false
                            scope.launch {
                                onSetManageLoading(true)
                                try {
                                    val configs = cloudService.listConfigs()
                                    onLoadConfigs(configs)
                                } catch (e: Exception) {
                                    onShowSnackbar("加载配置失败: ${e.message}")
                                } finally {
                                    onSetManageLoading(false)
                                }
                            }
                        }) {
                            Text("保存凭据")
                        }
                        Text(
                            "提示: 留空将自动使用应用内置的 Firebase 公共分享服务。",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    if (manageLoading) {
                        CircularProgressIndicator()
                    } else if (cloudService.configured.not()) {
                        Text(stringResource(R.string.cloud_not_configured))
                        Button(onClick = { showCredentialsEdit = true }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("立即配置")
                        }
                    } else {
                        Text(
                            stringResource(R.string.cloud_configs_list),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Start)
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(cloudConfigs) { config ->
                                CloudConfigItemRow(
                                    config = config,
                                    onDelete = { showDeleteConfirm = config.shareCode }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )

    showDeleteConfirm?.let { code ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.cloud_delete_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_delete_confirm, code)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = null
                    scope.launch {
                        onSetManageLoading(true)
                        try {
                            val success = cloudService.deleteConfig(code)
                            if (success) {
                                onShowSnackbar("已删除分享: $code")
                                val configs = cloudService.listConfigs()
                                onLoadConfigs(configs)
                            } else {
                                onShowSnackbar("删除失败")
                            }
                        } catch (e: Exception) {
                            onShowSnackbar("删除失败: ${e.message}")
                        } finally {
                            onSetManageLoading(false)
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CloudConfigItemRow(
    config: CloudService.CloudConfig,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = config.shareCode,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (config.type == com.example.cloud.ShareDataType.ALARM_GROUP) 
                            MaterialTheme.colorScheme.secondaryContainer 
                        else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (config.type == com.example.cloud.ShareDataType.ALARM_GROUP) "闹钟" else "打卡",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = config.preview,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
                Text(
                    text = "下载: ${config.downloadCount}",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ShareRecordsPanel(
    shareRecords: List<CloudShareRecord>,
    groups: List<AlarmGroup>,
    checkInGroups: List<CheckInGroupEntity>,
    onNavigateToGroup: (String) -> Unit,
    onShowQr: (String) -> Unit,
    onDeleteRecord: (CloudShareRecord) -> Unit
) {
    if (shareRecords.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.no_share_records),
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(shareRecords, key = { it.id }) { record ->
            val shareTimeFormatted = remember(record.shareTime) {
                try {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(record.shareTime))
                } catch (e: Exception) {
                    record.shareTime.toString()
                }
            }
            // 原始组是否还存在
            val sourceExists = remember(record) {
                if (record.groupType == "alarm") {
                    groups.any { it.id == record.sourceGroupId }
                } else {
                    checkInGroups.any { it.id == record.sourceGroupId }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = sourceExists) { onNavigateToGroup(record.groupType) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (record.groupType == "alarm") Icons.Default.Alarm else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (record.groupType == "alarm") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.groupName,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${record.shareCode} · ${record.itemCount}项 · ${shareTimeFormatted}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    // 二维码图标
                    IconButton(onClick = { onShowQr(record.shareCode) }) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "查看二维码",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 删除图标
                    IconButton(onClick = { onDeleteRecord(record) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
}

/** 分享二维码到微信（或系统分享面板） */
private fun shareQrToWeChat(context: android.content.Context, shareCode: String) {
    Log.d("CloudShareTab", "shareQrToWeChat: code=$shareCode")
    // 生成带底部文字的合成图（分享码 + 应用签名）
    val bitmap = QrCodeUtils.encodeToBitmapWithCaption(
        content = shareCode,
        caption = "由 Group Alarm 分享"
    )
    if (bitmap == null) {
        Log.w("CloudShareTab", "bitmap is null")
        android.widget.Toast.makeText(context, "生成二维码失败", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    Log.d("CloudShareTab", "bitmap generated, size=${bitmap.width}x${bitmap.height}")
    try {
        val file = File(context.cacheDir, "qr_${shareCode}.png")
        Log.d("CloudShareTab", "writing to ${file.absolutePath}")
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Log.d("CloudShareTab", "uri=$uri")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.d("CloudShareTab", "starting chooser")
        context.startActivity(Intent.createChooser(intent, "分享二维码到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.d("CloudShareTab", "startActivity returned")
    } catch (e: Exception) {
        Log.e("CloudShareTab", "share failed", e)
        android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
