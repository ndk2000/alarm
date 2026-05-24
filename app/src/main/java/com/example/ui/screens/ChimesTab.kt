package com.example.ui.screens

import android.speech.tts.TextToSpeech.EngineInfo
import android.speech.tts.Voice
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.alarm.AlarmScheduler
import com.example.alarm.ChimeAudioPreloader
import com.example.db.HourlyChime
import androidx.compose.ui.draw.clip

@Composable
fun ChimesTab(
    chimes: List<HourlyChime>,
    onToggleChime: (HourlyChime, Boolean) -> Unit,
    onUpdateChimeDetails: (Boolean, Boolean) -> Unit,
    onTestTts: (Int) -> Unit,
    onSetTtsPitch: (Float) -> Unit,
    onSetTtsRate: (Float) -> Unit,
    availableTtsEngines: List<EngineInfo>,
    selectedTtsEngine: String,
    onSetTtsEngine: (String) -> Unit,
    availableVoices: List<Voice>,
    selectedTtsVoice: String,
    onSetTtsVoice: (String) -> Unit,
    debugLogs: List<String>,
    chimeStyle: Int = 0,
    onChimeStyleChange: (Int) -> Unit = {}
) {
    var globalUseTts by remember { mutableStateOf(true) }
    var globalVibrate by remember { mutableStateOf(true) }
    var ttsPitch by remember { mutableStateOf(1.0f) }
    var ttsRate by remember { mutableStateOf(1.0f) }
    var showLogs by remember { mutableStateOf(false) }
    // 速测模式状态通过 AlarmScheduler 静态标志管理

    // Synchronize global configurations on first emission
    LaunchedEffect(chimes) {
        if (chimes.isNotEmpty()) {
            globalUseTts = chimes.first().useTts
            globalVibrate = chimes.first().vibrate
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Hourly Chime settings controls — compact
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.hourly_chime),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // 测试播放按钮
                    val testContext = LocalContext.current
                    TextButton(
                        onClick = { 
                            onTestTts(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                            Toast.makeText(testContext, testContext.getString(R.string.testing_tts), Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFADC6FF)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.test), fontSize = 12.sp)
                    }
                }

                // --- 缓存状态指示 ---
                val ctx = LocalContext.current
                val cacheCount = remember {
                    (0..23).count { h -> ChimeAudioPreloader.file(
                        ctx, h
                    ).exists() }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isReady = cacheCount == 24
                    val icon = if (isReady) Icons.Default.CheckCircle else Icons.Default.Storage
                    val color = if (isReady) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    val text = if (isReady) stringResource(R.string.cache_ready)
                               else stringResource(R.string.voice_cache_ready, cacheCount)
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text, fontSize = 11.sp, color = color)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // TTS switch
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.chime_style_tts), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Switch(
                        checked = globalUseTts,
                        onCheckedChange = {
                            globalUseTts = it
                            onUpdateChimeDetails(it, globalVibrate)
                        },
                        modifier = Modifier.height(20.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    // Vibrate switch
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.hourly_chime_vibrate), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Switch(
                        checked = globalVibrate,
                        onCheckedChange = {
                            globalVibrate = it
                            onUpdateChimeDetails(globalUseTts, it)
                        },
                        modifier = Modifier.height(20.dp)
                    )
                }

                // --- 报时风格选择器 ---
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.chime_style), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // TTS语音按钮
                        val isTts = chimeStyle == 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .border(
                                    width = if (isTts) 1.5.dp else 1.dp,
                                    color = if (isTts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onChimeStyleChange(0) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isTts) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.chime_style_tts),
                                    fontSize = 12.sp,
                                    fontWeight = if (isTts) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isTts) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 悦耳钟声按钮
                        val isBell = chimeStyle >= 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isBell) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .border(
                                    width = if (isBell) 1.5.dp else 1.dp,
                                    color = if (isBell) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onChimeStyleChange(1) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isBell) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.chime_style_bell),
                                    fontSize = 12.sp,
                                    fontWeight = if (isBell) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isBell) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                // 钟声模式时显示子样式选择器
                if (chimeStyle >= 1) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.bell_style), fontSize = 12.sp, color = Color(0xFF8E9099), modifier = Modifier.width(60.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val bellStyles = listOf(
                                1 to stringResource(R.string.bell_melody),
                                2 to stringResource(R.string.bell_westminster),
                                3 to stringResource(R.string.bell_clear),
                                4 to stringResource(R.string.bell_dreamy)
                            )
                            for ((style, label) in bellStyles) {
                                val isSelected = chimeStyle == style
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onChimeStyleChange(style) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )

                if (globalUseTts) {
                    // 语速调节
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.speed), fontSize = 12.sp, color = Color(0xFF8E9099), modifier = Modifier.width(36.dp))
                        Slider(
                            value = ttsRate,
                            onValueChange = {
                                ttsRate = it
                                onSetTtsRate(it)
                            },
                            valueRange = 0.1f..3.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFADC6FF),
                                activeTrackColor = Color(0xFFADC6FF)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${String.format("%.1f", ttsRate)}x", fontSize = 11.sp, color = Color(0xFFADC6FF), modifier = Modifier.width(36.dp))
                    }
                    // 音调调节
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.pitch), fontSize = 12.sp, color = Color(0xFF8E9099), modifier = Modifier.width(36.dp))
                        Slider(
                            value = ttsPitch,
                            onValueChange = {
                                ttsPitch = it
                                onSetTtsPitch(it)
                            },
                            valueRange = 0.1f..3.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFADC6FF),
                                activeTrackColor = Color(0xFFADC6FF)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${String.format("%.1f", ttsPitch)}x", fontSize = 11.sp, color = Color(0xFFADC6FF), modifier = Modifier.width(36.dp))
                    }

                    // --- TTS 引擎选择器 ---
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    var engineExpanded by remember { mutableStateOf(false) }
                    val currentEngineLabel = availableTtsEngines.find { it.name == selectedTtsEngine }?.label
                        ?: if (selectedTtsEngine.isNotEmpty()) selectedTtsEngine else stringResource(R.string.system_default)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { engineExpanded = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.tts_engine), fontSize = 12.sp, color = Color(0xFF8E9099), modifier = Modifier.width(60.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentEngineLabel.toString(),
                                fontSize = 13.sp,
                                color = Color(0xFFADC6FF)
                            )
                            DropdownMenu(
                                expanded = engineExpanded,
                                onDismissRequest = { engineExpanded = false }
                            ) {
                                DropdownMenuItem(
                                        text = { Text(stringResource(R.string.system_default)) },
                                    onClick = {
                                        onSetTtsEngine("")
                                        engineExpanded = false
                                    }
                                )
                                availableTtsEngines.forEach { engine ->
                                    DropdownMenuItem(
                                        text = { Text("${engine.label}") },
                                        onClick = {
                                            onSetTtsEngine(engine.name)
                                            engineExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF8E9099), modifier = Modifier.size(18.dp))
                    }
                    // --- TTS 语音选择器 ---
                    var voiceExpanded by remember { mutableStateOf(false) }
                    val currentVoiceLabel = availableVoices.find { it.name == selectedTtsVoice }?.let { v ->
                        "${v.name} (${v.locale})"
                    } ?: if (selectedTtsVoice.isNotEmpty()) selectedTtsVoice else stringResource(R.string.default_voice)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clickable { voiceExpanded = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.tts_voice), fontSize = 12.sp, color = Color(0xFF8E9099), modifier = Modifier.width(60.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentVoiceLabel,
                                fontSize = 13.sp,
                                color = Color(0xFFADC6FF)
                            )
                            DropdownMenu(
                                expanded = voiceExpanded,
                                onDismissRequest = { voiceExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.default_voice)) },
                                    onClick = {
                                        onSetTtsVoice("")
                                        voiceExpanded = false
                                    }
                                )
                                availableVoices.forEach { voice ->
                                    val qualityTag = when {
                                        voice.quality >= 300 -> stringResource(R.string.quality_hd)
                                        voice.quality >= 200 -> stringResource(R.string.quality_high)
                                        voice.quality >= 100 -> stringResource(R.string.quality_standard)
                                        else -> stringResource(R.string.quality_basic)
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${voice.name} (${voice.locale})", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                                if (voice.quality >= 200) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(qualityTag, fontSize = 9.sp, color = Color(0xFF4CAF50))
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSetTtsVoice(voice.name)
                                            voiceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF8E9099), modifier = Modifier.size(18.dp))
                    }
                }

                // --- 速测模式切换按钮 ---
                Spacer(modifier = Modifier.height(10.dp))
                val testModeCtx = LocalContext.current
                Button(
                    onClick = {
                        if (AlarmScheduler.testModeActive) {
                            AlarmScheduler.testModeActive = false
                            AlarmScheduler.cancelChimeAlarm(testModeCtx)
                            AlarmScheduler.scheduleNextHourlyChime(testModeCtx)
                            Toast.makeText(testModeCtx, testModeCtx.getString(R.string.test_mode_off), Toast.LENGTH_SHORT).show()
                        } else {
                            AlarmScheduler.testModeActive = true
                            AlarmScheduler.cancelChimeAlarm(testModeCtx)
                            AlarmScheduler.scheduleNextHourlyChime(testModeCtx)
                            Toast.makeText(testModeCtx, testModeCtx.getString(R.string.test_mode_on), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (AlarmScheduler.testModeActive) Color(0xFF2E7D32) else Color(0xFF424242),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.test_mode_btn),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            stringResource(R.string.select_active_chimes),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )

        // 24 Hour Selector Grid representation
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(chimes) { chime ->
                val isSelected = chime.isEnabled
                val cardColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

                Box(
                    modifier = Modifier
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardColor)
                        .clickable { onToggleChime(chime, !chime.isEnabled) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = String.format("%02d:00", chime.hour),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
