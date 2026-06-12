package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.example.R
import com.example.cloud.QrCodeUtils
import com.example.db.CheckInGroupEntity
import com.example.ui.dialogs.digitsToChineseUpper
import com.example.db.CheckInTaskEntity

private val chineseNumerals = arrayOf(
    "零", "一", "二", "三", "四", "五", "六", "七", "八", "九",
    "十", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九",
    "二十", "二十一", "二十二", "二十三"
)

/** 将小时转为中文读法，如 10 → "十点"，10:30 → "十点三十分" */
fun hourMinuteToChinese(hour: Int, minute: Int): String {
    val hourStr = if (hour in 0..23) chineseNumerals[hour] else hour.toString()
    return if (minute == 0) {
        "${hourStr}点"
    } else {
        val minStr = minuteToChinese(minute)
        "${hourStr}点${minStr}分"
    }
}

/** 将分钟 (0-59) 转为中文，如 5→"五", 10→"十", 23→"二十三", 45→"四十五" */
private fun minuteToChinese(minute: Int): String {
    if (minute < 10) return chineseNumerals[minute]
    if (minute < 20) return "十${if (minute > 10) chineseNumerals[minute - 10] else ""}"
    val tens = minute / 10
    val ones = minute % 10
    val tensStr = chineseNumerals[tens]
    return if (ones == 0) "${tensStr}十" else "${tensStr}十${chineseNumerals[ones]}"
}

@Composable
fun CheckInTab(
    groups: List<CheckInGroupEntity>,
    tasksMap: Map<Long, List<CheckInTaskEntity>>,
    onAddGroup: () -> Unit,
    onEditGroup: (CheckInGroupEntity) -> Unit,
    onDeleteGroup: (CheckInGroupEntity) -> Unit,
    onToggleGroup: (CheckInGroupEntity, Boolean, Boolean) -> Unit, // group, enabled, replaceExisting
    onDuplicateGroup: (CheckInGroupEntity) -> Unit = {},
    onDuplicateTask: (CheckInTaskEntity, CheckInGroupEntity, Int, Int) -> Unit = { _, _, _, _ -> },
    onRuleGenerateTasks: (Long, List<CheckInTaskEntity>) -> Unit = { _, _ -> },
    onShareGroup: (CheckInGroupEntity) -> Unit = {},
    onImportGroup: () -> Unit = {},
    onCloudShareGroup: (CheckInGroupEntity) -> Unit = {},
    cloudShareCode: String? = null,
    cloudShareLoading: Boolean = false,
    offsetHours: Int = 0,
    offsetMinutes: Int = 10
) {
    var showApplyDialog by remember { mutableStateOf<CheckInGroupEntity?>(null) }
    var deleteConfirm by remember { mutableStateOf<CheckInGroupEntity?>(null) }
    var showQrDialog by remember { mutableStateOf<String?>(null) }
    var showUploadingDialog by remember { mutableStateOf(false) }
    var uploadStartTime by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    // 规则生成任务默认调用 ViewModel 保存
    val vm = androidx.lifecycle.viewmodel.compose.viewModel<com.example.ui.AlarmViewModel>()

    // 云端分享成功后自动弹出二维码
    LaunchedEffect(cloudShareCode) {
        cloudShareCode?.let { showQrDialog = it }
    }

    // 上传完成（成功或失败）时关闭上传中对话框，确保至少显示 2 秒
    LaunchedEffect(cloudShareLoading, cloudShareCode) {
        if (!cloudShareLoading && showUploadingDialog) {
            val elapsed = System.currentTimeMillis() - uploadStartTime
            if (elapsed < 2000) {
                delay(2000 - elapsed)
            }
            showUploadingDialog = false
            if (cloudShareCode == null) {
                android.widget.Toast.makeText(context, "上传失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            // 空状态
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.CheckCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.checkin_no_groups),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(groups, key = { it.id }) { group ->
                    val tasks = tasksMap[group.id] ?: emptyList()
                    CheckInGroupCard(
                        group = group,
                        tasks = tasks,
                        onToggle = { enabled ->
                            if (enabled) {
                                showApplyDialog = group
                            } else {
                                onToggleGroup(group, false, false)
                            }
                        },
                        onEdit = { onEditGroup(group) },
                        onDelete = { deleteConfirm = group },
                        onDuplicate = { onDuplicateGroup(group) },
                        onDuplicateTask = { task, copies, interval -> onDuplicateTask(task, group, copies, interval) },
                        onRuleGenerateTasks = { tasks ->
                            vm.addCheckInTasks(group.id, tasks)
                        },
                        onShare = { onShareGroup(group) },
                        onCloudShare = {
                            uploadStartTime = System.currentTimeMillis()
                            showUploadingDialog = true
                            onCloudShareGroup(group)
                        },
                        offsetHours = offsetHours,
                        offsetMinutes = offsetMinutes
                    )
                }
            }
        }

        // FAB + import
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 导入按钮
            FloatingActionButton(
                onClick = onImportGroup,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.FileOpen,
                    contentDescription = "Import",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            // 新建按钮
            FloatingActionButton(
                onClick = onAddGroup,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    // ── 启用确认对话框：新增还是替换 ──
    showApplyDialog?.let { group ->
        AlertDialog(
            onDismissRequest = { showApplyDialog = null },
            title = {
                Text(
                    stringResource(R.string.checkin_apply_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.checkin_apply_desc, group.name),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${tasksMap[group.id]?.size ?: 0} ${stringResource(R.string.checkin_task_unit)}",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            onToggleGroup(group, true, false)
                            showApplyDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0D3269),
                            contentColor = Color(0xFFADC6FF)
                        )
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.checkin_apply_add), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = {
                        onToggleGroup(group, true, true)
                        showApplyDialog = null
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.checkin_apply_replace), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // ── 删除确认 ──
    deleteConfirm?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = {
                Text(
                    stringResource(R.string.checkin_delete_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    stringResource(R.string.checkin_delete_desc, group.name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(group)
                        deleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // ── 上传中对话框 ──
    if (showUploadingDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.cloud_share)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.cloud_sharing),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // ── 云端分享二维码 ──
    showQrDialog?.let { code ->
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
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("share_code", code))
                        android.widget.Toast.makeText(context, context.getString(R.string.copy_share_code), android.widget.Toast.LENGTH_SHORT).show()
                        showQrDialog = null
                    }) {
                        Text(stringResource(R.string.copy_share_code))
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInGroupCard(
    group: CheckInGroupEntity,
    tasks: List<CheckInTaskEntity>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onDuplicateTask: (CheckInTaskEntity, Int, Int) -> Unit = { _, _, _ -> },
    onRuleGenerateTasks: (List<CheckInTaskEntity>) -> Unit = {},
    onShare: () -> Unit = {},
    onCloudShare: () -> Unit = {},
    offsetHours: Int = 0,
    offsetMinutes: Int = 10
) {
    var expanded by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    // ── 任务项复制对话框状态 ──
    var showTaskCopyDialog by remember { mutableStateOf(false) }
    var taskCopyTarget by remember { mutableStateOf<CheckInTaskEntity?>(null) }
    var taskCopyCopies by remember { mutableStateOf("1") }
    var taskCopyInterval by remember { mutableStateOf("10") }

    // ── 规则生成对话框状态 ──
    var showRuleDialog by remember { mutableStateOf(false) }
    var ruleNamePrefix by remember { mutableStateOf("") }
    var ruleNameTemplate by remember { mutableStateOf("{prefix}{time}") }

    // 时段段状态类
    class TimeSegmentState(
        startHour: String = "08",
        startMinute: String = "00",
        endHour: String = "12",
        endMinute: String = "00",
        intervalHour: String = "0",
        intervalMinute: String = "10"
    ) {
        var startHour by mutableStateOf(startHour)
        var startMinute by mutableStateOf(startMinute)
        var endHour by mutableStateOf(endHour)
        var endMinute by mutableStateOf(endMinute)
        var intervalHour by mutableStateOf(intervalHour)
        var intervalMinute by mutableStateOf(intervalMinute)
    }
    var ruleSegments by remember { mutableStateOf(mutableListOf(TimeSegmentState())) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            ,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (group.isEnabled) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 展开/折叠按钮 + 组名 + 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { expanded = ! expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${tasks.size} ${stringResource(R.string.checkin_task_unit)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (group.ringtonePath != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 启用开关
                Switch(
                    checked = group.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            // ── 操作按钮（始终显示） ──
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onCloudShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "云分享", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))

            // ── 展开后的任务列表 ──
            if (expanded) {
                tasks.forEachIndexed { index, task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit() }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 序号圆标
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${index + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            task.name,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 铃声图标
                        if (task.ringtonePath != null) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // TTS 图标
                        if (task.useTts) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = "TTS",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            String.format("%02d:%02d", task.hour, task.minute),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 复制图标
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                taskCopyTarget = task
                                taskCopyCopies = "1"
                                taskCopyInterval = "10"
                                showTaskCopyDialog = true
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制任务",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // ── 任务复制对话框 ──
        if (showTaskCopyDialog) {
            val target = taskCopyTarget
            if (target != null) {
                AlertDialog(
                    onDismissRequest = { showTaskCopyDialog = false },
                    title = {
                        Text(
                            "复制任务：${target.name}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = taskCopyCopies,
                                onValueChange = { taskCopyCopies = it },
                                label = { Text("复制数量") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = taskCopyInterval,
                                onValueChange = { taskCopyInterval = it },
                                label = { Text("间隔（分钟）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "原任务名会被追加「${hourMinuteToChinese(target.hour, target.minute)}第一次打卡」\n" +
                                        "复制项依次偏移时间并追加「第X次打卡」",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showTaskCopyDialog = false
                                target.let { t ->
                                    val copies = taskCopyCopies.toIntOrNull()?.coerceIn(1, 20) ?: 1
                                    val interval = taskCopyInterval.toIntOrNull()?.coerceIn(1, 1440) ?: 10
                                    onDuplicateTask(t, copies, interval)
                                }
                            }
                        ) {
                            Text("复制", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTaskCopyDialog = false }) {
                            Text("取消")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }

        // ── 规则生成对话框 ──
        if (showRuleDialog) {
            Dialog(
                onDismissRequest = { showRuleDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.85f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // 标题
                        Text(
                            "规则生成任务",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 可滚动内容
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 名称前缀
                            OutlinedTextField(
                                value = ruleNamePrefix,
                                onValueChange = { ruleNamePrefix = it },
                                label = { Text("任务名称前缀") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 名称模板
                            OutlinedTextField(
                                value = ruleNameTemplate,
                                onValueChange = { ruleNameTemplate = it },
                                label = { Text("任务名称模板") },
                                placeholder = { Text("{prefix}{time}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "可用变量：{prefix} 前缀  {time} HH:mm  {hour} 时  {minute} 分  {seq} 序号",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                "时段规则（从起始时间到结束时间，间隔N分钟生成一项）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 时段列表
                            ruleSegments.forEachIndexed { idx, seg ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "${idx + 1}.",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    OutlinedTextField(
                                        value = seg.startHour,
                                        onValueChange = { seg.startHour = it.take(2).filter { c -> c.isDigit() } },
                                        label = { Text("时") },
                                        singleLine = true,
                                        modifier = Modifier.width(52.dp)
                                    )
                                    Text(":", fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = seg.startMinute,
                                        onValueChange = { seg.startMinute = it.take(2).filter { c -> c.isDigit() } },
                                        label = { Text("分") },
                                        singleLine = true,
                                        modifier = Modifier.width(52.dp)
                                    )
                                    Text(" ～ ", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedTextField(
                                        value = seg.endHour,
                                        onValueChange = { seg.endHour = it.take(2).filter { c -> c.isDigit() } },
                                        label = { Text("时") },
                                        singleLine = true,
                                        modifier = Modifier.width(52.dp)
                                    )
                                    Text(":", fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = seg.endMinute,
                                        onValueChange = { seg.endMinute = it.take(2).filter { c -> c.isDigit() } },
                                        label = { Text("分") },
                                        singleLine = true,
                                        modifier = Modifier.width(52.dp)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp)
                                ) {
                                    Text("每", fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = seg.intervalHour,
                                        onValueChange = { seg.intervalHour = it.take(3).filter { c -> c.isDigit() } },
                                        label = { Text("小时") },
                                        singleLine = true,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    Text(" ", fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = seg.intervalMinute,
                                        onValueChange = { seg.intervalMinute = it.take(3).filter { c -> c.isDigit() } },
                                        label = { Text("分钟") },
                                        singleLine = true,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    Text("一次", fontSize = 13.sp)
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (ruleSegments.size > 1) {
                                        TextButton(onClick = { ruleSegments = mutableListOf<TimeSegmentState>().also { it.addAll(ruleSegments); it.removeAt(idx) } }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }

                            // 添加时段
                            TextButton(
                                onClick = {
                                    ruleSegments = mutableListOf<TimeSegmentState>().also {
                                        it.addAll(ruleSegments)
                                        it.add(TimeSegmentState())
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加时段")
                            }

                            Text(
                                "按规则批量生成任务项追加到组中，不影响已有任务。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 底部按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    val prefix = ruleNamePrefix.ifBlank { "打卡" }
                                    val generatedTasks = mutableListOf<CheckInTaskEntity>()
                                    ruleSegments.forEach { seg ->
                                        val sH = seg.startHour.toIntOrNull()?.coerceIn(0, 23) ?: return@forEach
                                        val sM = seg.startMinute.toIntOrNull()?.coerceIn(0, 59) ?: return@forEach
                                        val eH = seg.endHour.toIntOrNull()?.coerceIn(0, 23) ?: return@forEach
                                        val eM = seg.endMinute.toIntOrNull()?.coerceIn(0, 59) ?: return@forEach
                                        val interval = (seg.intervalHour.toIntOrNull()?.coerceIn(0, 24) ?: 0) * 60 +
                                                (seg.intervalMinute.toIntOrNull()?.coerceIn(0, 59) ?: 10)
                                        if (interval <= 0) return@forEach

                                        val startTotal = sH * 60 + sM
                                        val endTotal = eH * 60 + eM
                                        if (endTotal <= startTotal) return@forEach

                                        var total = startTotal
                                        var seq = 1
                                        while (total <= endTotal) {
                                            val h = total / 60
                                            val m = total % 60
                                            // 和加号功能一致的生成逻辑：数字转中文 + TTS 语音
                                            val rawText = "${prefix}现在是${h}点${m}分 第${seq}次打卡"
                                            val ttsText = digitsToChineseUpper(rawText)
                                            val cachePath = try {
                                                com.example.alarm.TtsTaskPlayer.generateSync(ctx, ttsText)
                                            } catch (_: Exception) { null }
                                            generatedTasks.add(
                                                CheckInTaskEntity(
                                                    id = 0,
                                                    groupId = 0,
                                                    name = ttsText,
                                                    hour = h,
                                                    minute = m,
                                                    orderIndex = 0,
                                                    ringtonePath = cachePath,
                                                    useTts = true
                                                )
                                            )
                                            total += interval
                                            seq++
                                        }
                                    }
                                    if (generatedTasks.isNotEmpty()) {
                                        onRuleGenerateTasks(generatedTasks)
                                        showRuleDialog = false
                                    }
                                }
                            ) {
                                Text("生成", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { showRuleDialog = false }) {
                                Text("取消")
                            }
                        }
                    }
                }
            }
        }
    }
}
