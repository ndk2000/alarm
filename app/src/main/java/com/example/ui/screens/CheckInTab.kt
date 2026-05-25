package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.db.CheckInGroupEntity
import com.example.db.CheckInTaskEntity

@Composable
fun CheckInTab(
    groups: List<CheckInGroupEntity>,
    tasksMap: Map<Long, List<CheckInTaskEntity>>,
    onAddGroup: () -> Unit,
    onEditGroup: (CheckInGroupEntity) -> Unit,
    onDeleteGroup: (CheckInGroupEntity) -> Unit,
    onToggleGroup: (CheckInGroupEntity, Boolean, Boolean) -> Unit, // group, enabled, replaceExisting
    onDuplicateGroup: (CheckInGroupEntity) -> Unit = {},
    onShareGroup: (CheckInGroupEntity) -> Unit = {},
    onImportGroup: () -> Unit = {}
) {
    var showApplyDialog by remember { mutableStateOf<CheckInGroupEntity?>(null) }
    var deleteConfirm by remember { mutableStateOf<CheckInGroupEntity?>(null) }

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
                        onShare = { onShareGroup(group) }
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
}

@Composable
private fun CheckInGroupCard(
    group: CheckInGroupEntity,
    tasks: List<CheckInTaskEntity>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onShare: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
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
            // 顶部：组名 + 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 展开按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                }

                // 编辑按钮
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                }
                // 复制按钮
                IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(16.dp))
                }
                // 分享按钮
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                // 删除按钮
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
                // 启用开关
                Switch(
                    checked = group.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            // 展开列表
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                tasks.forEachIndexed { index, task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                    }
                }
            }
        }
    }
}
