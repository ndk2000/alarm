package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.db.CheckInTaskEntity
import com.example.db.CheckInGroupEntity

data class CheckInTaskInput(
    val name: String = "",
    val hour: String = "8",
    val minute: String = "0",
    val ringtonePath: String? = null,
    val useTts: Boolean = false
)

@Composable
fun AddCheckInGroupDialog(
    existingGroup: CheckInGroupEntity? = null,
    existingTasks: List<CheckInTaskEntity> = emptyList(),
    customRingtones: List<String> = emptyList(),
    systemRingtones: List<Pair<String, String>> = emptyList(),
    localRecordings: List<Pair<String, String>> = emptyList(),
    customRecordingPath: String = "",
    isRecording: Boolean = false,
    recordingDuration: Int = 0,
    onStartRecording: () -> Unit = {},
    onStopRecording: (String) -> String? = { null },
    onCancelRecording: () -> Unit = {},
    onImportAudio: (android.net.Uri, String) -> String? = { _, _ -> null },
    onDismiss: () -> Unit,
    onConfirm: (name: String, tasks: List<CheckInTaskInput>) -> Unit
) {
    var groupName by remember { mutableStateOf(existingGroup?.name ?: "") }
    var tasks by remember {
        mutableStateOf(
            if (existingTasks.isNotEmpty())
                existingTasks.map {
                    CheckInTaskInput(it.name, it.hour.toString(), it.minute.toString(), it.ringtonePath, it.useTts)
                }.toMutableList()
            else
                mutableListOf(CheckInTaskInput())
        )
    }
    // 哪个任务的铃声选择对话框正在打开（index）
    var taskRingtoneIndex by remember { mutableStateOf(-1) }

    val isEdit = existingGroup != null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                if (isEdit) stringResource(R.string.checkin_edit_group) else stringResource(R.string.checkin_new_group),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 组名称
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.checkin_group_name_label)) },
                    placeholder = { Text(stringResource(R.string.checkin_group_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 打卡事项列表
                Text(
                    stringResource(R.string.checkin_tasks),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                tasks.forEachIndexed { index, task ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${index + 1}.",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(28.dp)
                                )
                                OutlinedTextField(
                                    value = task.name,
                                    onValueChange = { newName ->
                                        tasks = tasks.toMutableList().also { it[index] = task.copy(name = newName) }
                                    },
                                    placeholder = { Text(stringResource(R.string.checkin_task_name_hint)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                    )
                                )
                                if (tasks.size > 1) {
                                    IconButton(
                                        onClick = { tasks = tasks.toMutableList().also { it.removeAt(index) } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // 时间选择
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 28.dp)
                            ) {
                                Text(
                                    stringResource(R.string.checkin_remind_at),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                // 小时
                                OutlinedTextField(
                                    value = task.hour,
                                    onValueChange = { v ->
                                        val filtered = v.filter { it.isDigit() }.take(2)
                                        if (filtered.isEmpty() || filtered.toInt() <= 23)
                                            tasks = tasks.toMutableList().also { it[index] = task.copy(hour = filtered) }
                                    },
                                    modifier = Modifier.width(56.dp),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                                )
                                Text(" : ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)

                                // 分钟
                                OutlinedTextField(
                                    value = task.minute,
                                    onValueChange = { v ->
                                        val filtered = v.filter { it.isDigit() }.take(2)
                                        if (filtered.isEmpty() || filtered.toInt() <= 59)
                                            tasks = tasks.toMutableList().also { it[index] = task.copy(minute = filtered) }
                                    },
                                    modifier = Modifier.width(56.dp),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // ── 铃声 + TTS ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 铃声选择
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { taskRingtoneIndex = index }
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (task.ringtonePath != null) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (task.ringtonePath != null) {
                                            val name = task.ringtonePath!!.substringAfterLast('/').substringAfterLast('\\')
                                            name.ifEmpty { "自定义" }
                                        } else stringResource(R.string.default_ringtone),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (task.ringtonePath != null) {
                                        IconButton(
                                            onClick = {
                                                tasks = tasks.toMutableList().also { it[index] = task.copy(ringtonePath = null) }
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Clear",
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // TTS 开关
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.RecordVoiceOver,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (task.useTts) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Switch(
                                        checked = task.useTts,
                                        onCheckedChange = { checked ->
                                            tasks = tasks.toMutableList().also { it[index] = task.copy(useTts = checked) }
                                        },
                                        modifier = Modifier.height(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 添加任务按钮
                TextButton(
                    onClick = { tasks = tasks.toMutableList().also { it.add(CheckInTaskInput()) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.checkin_add_task), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validTasks = tasks.filter { it.name.isNotBlank() }
                    if (groupName.isNotBlank() && validTasks.isNotEmpty()) {
                        onConfirm(groupName, validTasks)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0D3269),
                    contentColor = Color(0xFFADC6FF)
                ),
                enabled = groupName.isNotBlank() && tasks.any { it.name.isNotBlank() }
            ) {
                Text(
                    if (isEdit) stringResource(R.string.save) else stringResource(R.string.create),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color(0xFF8E9099))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )

    // 单个任务的铃声选择弹窗
    if (taskRingtoneIndex in tasks.indices) {
        val task = tasks[taskRingtoneIndex]
        RingtoneSelectionDialog(
            currentSelectedPath = task.ringtonePath,
            customRingtones = customRingtones,
            systemRingtones = systemRingtones,
            localRecordings = localRecordings,
            customRecordingPath = customRecordingPath,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onDismiss = { taskRingtoneIndex = -1 },
            onSelect = { path ->
                tasks = tasks.toMutableList().also { it[taskRingtoneIndex] = task.copy(ringtonePath = path) }
                taskRingtoneIndex = -1
            },
            onImportAudio = onImportAudio
        )
    }
}

