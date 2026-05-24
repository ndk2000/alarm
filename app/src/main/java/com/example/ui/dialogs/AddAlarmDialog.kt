package com.example.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.R
import com.example.db.Alarm
import com.example.ui.components.WheelDialPicker

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
    onConfirm: (hour: Int, minute: Int, days: String, label: String, ringtonePath: String?, vibrate: Boolean) -> Unit,
    onImportAudio: (Uri, String) -> String?
) {
    val context = LocalContext.current
    var hour by remember { mutableStateOf(editingAlarm?.hour ?: 7) }
    var minute by remember { mutableStateOf(editingAlarm?.minute ?: 30) }
    var label by remember { mutableStateOf(editingAlarm?.label ?: "") }
    var selectedRingtonePath by remember { mutableStateOf<String?>(editingAlarm?.ringtonePath) }
    var vibrate by remember { mutableStateOf(editingAlarm?.vibrate ?: true) }
    var showRingtoneSelection by remember { mutableStateOf(false) }

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
                    .heightIn(max = 400.dp) // 限制最大高度，防止在小屏或键盘弹起时顶出屏幕
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // 时间选择
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF25272C), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.time_picker_hint), fontSize = 12.sp, color = Color(0xFF8E9099))
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
                    LaunchedEffect(isFocused) {
                        if (isFocused) listState.animateScrollToItem(textFieldIndex)
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
                    // 自定义铃声选择
                    val rPath = selectedRingtonePath
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val daysCodeStr = selectedDays.sorted().joinToString(",")
                    onConfirm(hour, minute, daysCodeStr, label, selectedRingtonePath, vibrate)
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
}
