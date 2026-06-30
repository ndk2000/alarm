package com.ccsoft.alarm.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ccsoft.alarm.R
import com.ccsoft.alarm.alarm.TtsTaskPlayer
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.ui.components.WheelDialPicker
import com.ccsoft.alarm.ui.util.rememberScreenScale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddAlarmDialog(
    editingAlarm: Alarm? = null,
    customRingtones: List<String>,
    systemRingtones: List<Pair<String, String>> = emptyList(),
    localRecordings: List<Pair<String, String>> = emptyList(),
    customRecordingPath: String,
    isRecording: Boolean,
    recordingDuration: Int,
    onStartRecording: () -> Unit,
    onStopRecording: (String) -> String?,
    onCancelRecording: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, days: String, label: String, ringtonePath: String?, vibrate: Boolean, ringtoneDurationSecs: Int) -> Unit,
    onImportAudio: (Uri, String) -> String?
) {
    val context = LocalContext.current
    val scale = rememberScreenScale()
    var hour by remember { mutableStateOf(editingAlarm?.hour ?: 7) }
    var minute by remember { mutableStateOf(editingAlarm?.minute ?: 30) }
    var label by remember { mutableStateOf(editingAlarm?.label ?: "") }
    var selectedRingtonePath by remember { mutableStateOf<String?>(editingAlarm?.ringtonePath) }
    var vibrate by remember { mutableStateOf(editingAlarm?.vibrate ?: true) }
    var showRingtoneSelection by remember { mutableStateOf(false) }

    // 铃声时长：0=持续响铃, >0=秒数
    // 内部用 durationMode 和 durationValue 来构建 UI 选择
    val initialDurationSecs = editingAlarm?.ringtoneDurationSecs ?: 0
    var ringtoneDurationSecs by remember { mutableStateOf(initialDurationSecs) }
    var durationMode by remember { mutableStateOf(if (initialDurationSecs == 0) 0 else 1) } // 0=持续, 1=按次数, 2=按时间
    var showCustomDurationDialog by remember { mutableStateOf(false) }
    var customDurationInput by remember { mutableStateOf("") }

    // Represent weekdays. Mon=1 to Sun=7.
    val initialDays = if (editingAlarm != null) {
        editingAlarm.daysOfWeek.split(",")
            .filter { it.isNotEmpty() }
            .mapNotNull { it.trim().toIntOrNull() }
    } else {
        listOf(1, 2, 3, 4, 5, 6, 7)
    }
    val selectedDays = remember { mutableStateListOf<Int>().apply { addAll(initialDays) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .wrapContentHeight(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                if (editingAlarm != null) stringResource(R.string.edit_alarm_title) else stringResource(R.string.new_alarm_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            val listState = rememberLazyListState()
            val textFieldIndex = 2

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = scale.dialogContentMaxHeight) // 限制最大高度，防止在小屏或键盘弹起时顶出屏幕
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // 时间选择
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.time_picker_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.hours_unit), fontSize = 11.sp, color = Color(0xFF8E9099), modifier = Modifier.padding(bottom = 2.dp))
                                WheelDialPicker(value = hour, range = 0..23, onValueChange = { hour = it })
                            }
                            Text(
                                text = ":",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFADC6FF),
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.minutes_unit), fontSize = 11.sp, color = Color(0xFF8E9099), modifier = Modifier.padding(bottom = 2.dp))
                                WheelDialPicker(value = minute, range = 0..59, onValueChange = { minute = it })
                            }
                        }
                    }
                }
                item {
                    // 周期与周几选择
                    Column {
                        Text(stringResource(R.string.repeat_days), fontSize = 12.sp, color = Color(0xFF8E9099))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val activePreset = when {
                                selectedDays.size == 7 -> stringResource(R.string.preset_everyday)
                                selectedDays.size == 5 && selectedDays.containsAll(listOf(1,2,3,4,5)) && !selectedDays.contains(6) && !selectedDays.contains(7) -> stringResource(R.string.preset_weekdays)
                                selectedDays.size == 2 && selectedDays.containsAll(listOf(6,7)) && !selectedDays.contains(1) && !selectedDays.contains(2) && !selectedDays.contains(3) && !selectedDays.contains(4) && !selectedDays.contains(5) -> stringResource(R.string.preset_weekends)
                                selectedDays.isEmpty() -> stringResource(R.string.preset_once)
                                else -> stringResource(R.string.preset_custom)
                            }
                            val presets = listOf(
                                stringResource(R.string.preset_everyday),
                                stringResource(R.string.preset_weekdays),
                                stringResource(R.string.preset_weekends),
                                stringResource(R.string.preset_once)
                            )
                            presets.forEachIndexed { index, preset ->
                                val isSelected = activePreset == preset
                                val chipBg = if (isSelected) Color(0xFF0D3269) else Color(0xFF2D2F35)
                                val chipTc = if (isSelected) Color(0xFFADC6FF) else Color(0xFFCCCED6)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(chipBg)
                                        .clickable {
                                            selectedDays.clear()
                                            when (index) {
                                                0 -> selectedDays.addAll(listOf(1, 2, 3, 4, 5, 6, 7))
                                                1 -> selectedDays.addAll(listOf(1, 2, 3, 4, 5))
                                                2 -> selectedDays.addAll(listOf(6, 7))
                                                3 -> {}
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(preset, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = chipTc)
                                }
                            }
                        }
                        val weekNames = listOf(stringResource(R.string.mon), stringResource(R.string.tue), stringResource(R.string.wed), stringResource(R.string.thu), stringResource(R.string.fri), stringResource(R.string.sat), stringResource(R.string.sun))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (i in 1..7) {
                                val isDayActive = selectedDays.contains(i)
                                val isWeekend = i == 6 || i == 7
                                val bg = if (isDayActive) {
                                    if (isWeekend) Color(0xFFF095FF) else MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF2D2F35)
                                }
                                val tc = if (isDayActive) {
                                    if (isWeekend) Color(0xFF2E003D) else MaterialTheme.colorScheme.onPrimary
                                } else {
                                    Color(0xFFBFC2CC)
                                }
                                Box(
                                    modifier = Modifier.size(36.dp).clip(CircleShape).background(bg).clickable {
                                        if (isDayActive) selectedDays.remove(i) else selectedDays.add(i)
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(weekNames[i - 1], fontSize = 12.sp, color = tc, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                item {
                    // 闹铃文本标签 — 键盘弹起时自动滚动至此
                    var isFocused by remember { mutableStateOf(false) }
                    val isImeVisible = WindowInsets.isImeVisible
                    
                    LaunchedEffect(isFocused, isImeVisible) {
                        if (isFocused && isImeVisible) {
                            listState.animateScrollToItem(textFieldIndex)
                        }
                    }
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.alarm_label_label), color = Color(0xFF8E9099)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = Color(0xFFADC6FF),
                            unfocusedBorderColor = Color(0xFF43474E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                    )
                }
                item {
                    // 自定义铃声选择 + 语音合成
                    val rPath = selectedRingtonePath
                    val isTtsSelected = rPath == com.ccsoft.alarm.alarm.AlarmService.TTS_RINGTONE_MARKER
                    val ringtoneDisplay = when {
                        rPath == null -> stringResource(R.string.default_ringtone)
                        rPath.startsWith("content://") -> {
                            systemRingtones.find { it.second == rPath }?.first ?: stringResource(R.string.default_ringtone)
                        }
                        else -> {
                            val localRec = localRecordings.find { it.second == rPath }
                            if (localRec != null) context.getString(R.string.mic_recording_prefix, localRec.first) else rPath.substringAfterLast("/")
                        }
                    }
                    Column {
                        Text(stringResource(R.string.select_ringtone), fontSize = 12.sp, color = Color(0xFF8E9099))
                        Spacer(modifier = Modifier.height(6.dp))

                        // 语音合成选项
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTtsSelected) Color(0xFF1A3A5C) else Color(0xFF25272C))
                                .clickable {
                                    if (isTtsSelected) {
                                        selectedRingtonePath = null
                                    } else {
                                        selectedRingtonePath = com.ccsoft.alarm.alarm.AlarmService.TTS_RINGTONE_MARKER
                                        // 自动生成 TTS 缓存
                                        if (label.isNotBlank()) {
                                            TtsTaskPlayer.generateSync(context, label)
                                        }
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.RecordVoiceOver,
                                contentDescription = null, tint = Color(0xFFADC6FF), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("🔊 语音合成（朗读标签文字）",
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE3E2E6))
                        }

                        if (isTtsSelected) {
                            // 语音合成模式：显示文件信息 + 试听按钮
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF25272C)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val cacheFile = TtsTaskPlayer.getCacheFile(context, label)
                                        Text("朗读内容：$label",
                                            fontSize = 12.sp, color = Color(0xFFE3E2E6),
                                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(4.dp))
                                        Text("文件：${cacheFile?.name ?: "未知"}",
                                            fontSize = 10.sp, color = Color(0xFF8E9099))
                                    }
                                    IconButton(
                                        onClick = {
                                            TtsTaskPlayer.play(context, label)
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow,
                                            contentDescription = "试听", tint = Color(0xFFADC6FF))
                                    }
                                }
                            }
                        } else {
                            // 非语音合成模式：显示铃声选择框
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF25272C)).clickable { showRingtoneSelection = true }.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFADC6FF), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = ringtoneDisplay, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE3E2E6))
                                    }
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFFADC6FF))
                                }
                            }
                            if (showRingtoneSelection) {
                                RingtoneSelectionDialog(
                                    currentSelectedPath = selectedRingtonePath,
                                    customRingtones = customRingtones,
                                    systemRingtones = systemRingtones,
                                    localRecordings = localRecordings,
                                    customRecordingPath = customRecordingPath,
                                    isRecording = isRecording,
                                    recordingDuration = recordingDuration,
                                    onStartRecording = onStartRecording,
                                    onStopRecording = onStopRecording,
                                    onCancelRecording = onCancelRecording,
                                    onDismiss = { showRingtoneSelection = false },
                                    onSelect = { path -> selectedRingtonePath = path },
                                    onImportAudio = onImportAudio
                                )
                            }
                        }
                    }
                }
                item {
                    // 振动开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.vibrate_on_ring), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = vibrate,
                            onCheckedChange = { vibrate = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
                item {
                    // 铃声时长设置
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("铃声时长", fontSize = 12.sp, color = Color(0xFF8E9099))
                        Spacer(modifier = Modifier.height(6.dp))

                        // 模式选择: 持续 / 按次数 / 按时间
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("持续响铃" to 0, "按次数" to 1, "按时间" to 2).forEach { (label, mode) ->
                                val isSelected = durationMode == mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF0D3269) else Color(0xFF2D2F35))
                                        .clickable {
                                            durationMode = mode
                                            ringtoneDurationSecs = when (mode) {
                                                0 -> 0 // 持续
                                                1 -> 5 // 默认1次=5秒
                                                2 -> 60 // 默认1分钟=60秒
                                                else -> 0
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFFADC6FF) else Color(0xFFCCCED6))
                                }
                            }
                        }

                        if (durationMode == 1) {
                            // 按次数
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("1次" to 5, "10次" to 50, "自定义" to -1).forEach { (label, secs) ->
                                    val isSelected = if (secs > 0) ringtoneDurationSecs == secs else (ringtoneDurationSecs > 0 && ringtoneDurationSecs % 5 == 0 && ringtoneDurationSecs != 5 && ringtoneDurationSecs != 50)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Color(0xFF1A3A5C) else Color(0xFF25272C))
                                            .clickable {
                                                if (secs > 0) {
                                                    ringtoneDurationSecs = secs
                                                } else {
                                                    customDurationInput = ""
                                                    showCustomDurationDialog = true
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color(0xFFADC6FF) else Color(0xFFCCCED6))
                                    }
                                }
                            }
                            Text("${ringtoneDurationSecs / 5}次 × 约5秒/次 = ${ringtoneDurationSecs}秒",
                                fontSize = 10.sp, color = Color(0xFF707070), modifier = Modifier.padding(top = 4.dp))
                        }

                        if (durationMode == 2) {
                            // 按时间
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("1分钟" to 60, "10分钟" to 600, "自定义" to -1).forEach { (label, secs) ->
                                    val isSelected = if (secs > 0) ringtoneDurationSecs == secs else (ringtoneDurationSecs > 0 && ringtoneDurationSecs % 60 == 0 && ringtoneDurationSecs != 60 && ringtoneDurationSecs != 600)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Color(0xFF1A3A5C) else Color(0xFF25272C))
                                            .clickable {
                                                if (secs > 0) {
                                                    ringtoneDurationSecs = secs
                                                } else {
                                                    customDurationInput = ""
                                                    showCustomDurationDialog = true
                                                }
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color(0xFFADC6FF) else Color(0xFFCCCED6))
                                    }
                                }
                            }
                            Text("${ringtoneDurationSecs / 60}分钟 = ${ringtoneDurationSecs}秒",
                                fontSize = 10.sp, color = Color(0xFF707070), modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val daysCodeStr = selectedDays.sorted().joinToString(",")
                    onConfirm(hour, minute, daysCodeStr, label, selectedRingtonePath, vibrate, ringtoneDurationSecs)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0D3269),
                    contentColor = Color(0xFFADC6FF)
                )
            ) {
                Text(stringResource(R.string.confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_alarm), color = Color(0xFF8E9099), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )

    // 自定义时长输入弹窗
    if (showCustomDurationDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDurationDialog = false },
            title = { Text("自定义铃声时长", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(if (durationMode == 1) "请输入响铃次数（每次约5秒）" else "请输入响铃分钟数",
                        fontSize = 13.sp, color = Color(0xFF8E9099))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDurationInput,
                        onValueChange = { customDurationInput = it.filter { c -> c.isDigit() } },
                        label = { Text(if (durationMode == 1) "次数" else "分钟数") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val value = customDurationInput.toIntOrNull() ?: 0
                        if (value > 0) {
                            ringtoneDurationSecs = if (durationMode == 1) value * 5 else value * 60
                        }
                        showCustomDurationDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDurationDialog = false }) {
                    Text("取消", color = Color(0xFF8E9099))
                }
            }
        )
    }
}
