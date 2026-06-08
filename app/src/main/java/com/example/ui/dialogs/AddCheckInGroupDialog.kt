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
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import com.example.R
import com.example.alarm.TtsTaskPlayer
import com.example.db.CheckInTaskEntity
import com.example.db.CheckInGroupEntity
data class CheckInTaskInput(
    val name: String = "",
    val hour: String = "8",
    val minute: String = "0",
    val ringtonePath: String? = null,
    val useTts: Boolean = false
)

private val upperNumerals = arrayOf("零","一","二","三","四","五","六","七","八","九")

private fun numToUpper(n: Int): String {
    if (n < 10) return upperNumerals[n]
    if (n < 20) return "拾${if (n > 10) upperNumerals[n - 10] else ""}"
    val tens = n / 10
    val ones = n % 10
    return "${upperNumerals[tens]}拾${if (ones > 0) upperNumerals[ones] else ""}"
}

private fun hourMinuteToChineseUpper(hour: Int, minute: Int): String {
    val h = if (hour in 0..23) numToUpper(hour) else hour.toString()
    return if (minute == 0) "${h}点" else "${h}点${numToUpper(minute)}分"
}

private fun digitsToChineseUpper(text: String): String {
    val cns = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    val units = arrayOf("", "十", "百", "千")
    fun numberToChinese(n: Int): String {
        if (n == 0) return "零"
        var num = n
        var result = ""
        var ui = 0
        var needZero = false
        while (num > 0) {
            val d = num % 10
            if (d == 0) {
                needZero = true
            } else {
                if (needZero) { result = "零$result"; needZero = false }
                result = "${cns[d]}${units[ui]}$result"
            }
            num /= 10
            ui++
        }
        return if (result.startsWith("一十")) result.substring(1) else result
    }
    return text.replace(Regex("\\d+")) { numberToChinese(it.value.toInt()) }
}

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
    offsetHours: Int = 0,
    offsetMinutes: Int = 10,
    onSetOffsetHours: (Int) -> Unit = {},
    onSetOffsetMinutes: (Int) -> Unit = {},
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
                mutableListOf<CheckInTaskInput>()
        )
    }
    // 哪个任务的铃声选择对话框正在打开（index）
    var taskRingtoneIndex by remember { mutableStateOf(-1) }
    // 待确认删除的任务索引
    var taskToDeleteIndex by remember { mutableStateOf(-1) }

    // ── 批量生成状态（单个时段） ──
    var showBatchGen by remember { mutableStateOf(false) }
    var batchStartHour by remember { mutableStateOf("08") }
    var batchStartMin by remember { mutableStateOf("00") }
    var batchEndHour by remember { mutableStateOf("12") }
    var batchEndMin by remember { mutableStateOf("00") }
    var batchIntervalHour by remember { mutableStateOf(offsetHours.toString()) }
    var batchIntervalMin by remember { mutableStateOf(offsetMinutes.toString()) }
    var batchNamePrefix by remember { mutableStateOf("") }

    val isEdit = existingGroup != null
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
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
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 组名称
                OutlinedTextField(
                    value = groupName,
                    onValueChange = {
                        groupName = it
                        // 新建时，修改组名同步到第一个任务名称
                        if (!isEdit && tasks.isNotEmpty()) {
                            tasks = tasks.toMutableList().also { list ->
                                list[0] = list[0].copy(name = it)
                            }
                        }
                        // 新建时，修改组名同步到批量生成的前缀
                        if (!isEdit) {
                            batchNamePrefix = it
                        }
                    },
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
                    val context = LocalContext.current
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
                                        // 改名时清理旧文本的 TTS 缓存文件
                                        if (newName != task.name && task.name.isNotBlank()) {
                                            TtsTaskPlayer.deleteCache(context, task.name)
                                        }
                                        tasks = tasks.toMutableList().also { it[index] = task.copy(name = newName) }
                                        // 新建时，第一个任务的名称自动同步到组名
                                        if (!isEdit && index == 0) {
                                            groupName = newName
                                        }
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
                                // TTS 试听按钮
                                IconButton(
                                    onClick = {
                                        // 试听并自动设为铃声
                                        TtsTaskPlayer.play(context, task.name) { cachedPath ->
                                            tasks = tasks.toMutableList().also {
                                                it[index] = task.copy(ringtonePath = cachedPath, useTts = true)
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.RecordVoiceOver,
                                        contentDescription = "试听",
                                        tint = if (task.name.isNotBlank())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (tasks.size > 1) {
                                    IconButton(
                                        onClick = { taskToDeleteIndex = index },
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
                                    modifier = Modifier.width(64.dp),
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
                                    modifier = Modifier.width(64.dp),
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
                    onClick = {
                        val newTask = if (!isEdit && tasks.isEmpty() && groupName.isNotBlank())
                            CheckInTaskInput(name = groupName)
                        else
                            CheckInTaskInput()
                        tasks = tasks.toMutableList().also { it.add(newTask) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.checkin_add_task), fontSize = 13.sp)
                }

                // ── 批量生成按钮 ──
                if (!showBatchGen) {
                    OutlinedButton(
                        onClick = { showBatchGen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("按规则批量生成", fontSize = 13.sp)
                    }
                }

                // ── 批量生成面板 ──
                if (showBatchGen) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("批量生成", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = { showBatchGen = false }) {
                                    Text("收起", fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            // 名称前缀
                            OutlinedTextField(
                                value = batchNamePrefix,
                                onValueChange = {
                                    batchNamePrefix = it
                                    // 新建时，修改前缀同步到组名
                                    if (!isEdit) {
                                        groupName = it
                                    }
                                },
                                label = { Text("任务名称前缀", fontSize = 11.sp) },
                                placeholder = { Text("默认使用组名", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.tertiary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // ── 单个时段设置 ──
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("时段", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("起始", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedTextField(value = batchStartHour, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            if (f.isEmpty() || f.toInt() <= 23) batchStartHour = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchStartHour = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        Text(":", fontSize = 11.sp)
                                        OutlinedTextField(value = batchStartMin, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            if (f.isEmpty() || f.toInt() <= 59) batchStartMin = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchStartMin = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("→", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("结束", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedTextField(value = batchEndHour, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            if (f.isEmpty() || f.toInt() <= 23) batchEndHour = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchEndHour = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        Text(":", fontSize = 11.sp)
                                        OutlinedTextField(value = batchEndMin, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            if (f.isEmpty() || f.toInt() <= 59) batchEndMin = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchEndMin = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("间隔", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedTextField(value = batchIntervalHour, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            batchIntervalHour = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchIntervalHour = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        Text("小时", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedTextField(value = batchIntervalMin, onValueChange = { v ->
                                            val f = v.filter { it.isDigit() }.take(2)
                                            batchIntervalMin = f
                                        }, modifier = Modifier.width(48.dp).onFocusChanged{ if(it.isFocused) batchIntervalMin = "" }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                        Text("分钟", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // 执行生成
                            Button(
                                onClick = {
                                    val prefix = digitsToChineseUpper(batchNamePrefix.ifBlank { groupName.ifBlank { "打卡" } })
                                    val newTasks = mutableListOf<CheckInTaskInput>()
                                    val sh = batchStartHour.toIntOrNull() ?: 8
                                    val sm = batchStartMin.toIntOrNull() ?: 0
                                    val eh = batchEndHour.toIntOrNull() ?: 12
                                    val em = batchEndMin.toIntOrNull() ?: 0
                                    val ih = batchIntervalHour.toIntOrNull() ?: 0
                                    val im = batchIntervalMin.toIntOrNull() ?: 0
                                    val totalInterval = ih * 60 + im
                                    if (totalInterval > 0) {
                                        val startTotal = sh * 60 + sm
                                        val endTotal = eh * 60 + em
                                        var cur = startTotal
                                        var seqIndex = 1
                                        while (cur <= endTotal) {
                                            val h = cur / 60
                                            val m = cur % 60
                                            val ttsText = "${prefix}现在是${hourMinuteToChineseUpper(h, m)} 第${numToUpper(seqIndex)}次打卡"
                                            // 同步生成 TTS 语音文件，等就绪后再加任务
                                            val cachedPath = TtsTaskPlayer.generateSync(context, ttsText)
                                            newTasks.add(CheckInTaskInput(
                                                name = ttsText,
                                                hour = h.toString(),
                                                minute = m.toString(),
                                                ringtonePath = cachedPath,
                                                useTts = true
                                            ))
                                            seqIndex++
                                            cur += totalInterval
                                        }
                                    }
                                    tasks = (tasks + newTasks).toMutableList()
                                    showBatchGen = false
                                    // 将间隔保存到设置的偏移里
                                    if (ih != offsetHours) onSetOffsetHours(ih)
                                    if (im != offsetMinutes) onSetOffsetMinutes(im)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("生成并添加", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validTasks = tasks.filter { it.name.isNotBlank() }
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName, validTasks)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0D3269),
                    contentColor = Color(0xFFADC6FF)
                ),
                enabled = groupName.isNotBlank()
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

    // 删除任务的确认弹窗
    if (taskToDeleteIndex in tasks.indices) {
            AlertDialog(
                onDismissRequest = { taskToDeleteIndex = -1 },
                title = { Text("确认删除", color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    val taskName = tasks[taskToDeleteIndex].name
                    Text(
                        if (taskName.isNotBlank()) "确定要删除\"$taskName\"吗？"
                        else "确定要删除第${taskToDeleteIndex + 1}项吗？",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            tasks = tasks.toMutableList().also { it.removeAt(taskToDeleteIndex) }
                            taskToDeleteIndex = -1
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("删除", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { taskToDeleteIndex = -1 }) {
                        Text("取消", color = Color.Gray)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
