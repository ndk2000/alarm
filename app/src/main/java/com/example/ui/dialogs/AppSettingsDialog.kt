package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import androidx.compose.ui.draw.clip

@Composable
fun AppSettingsDialog(
    theme: Int,
    lang: String,
    offsetHours: Int,
    offsetMinutes: Int,
    recordingPath: String,
    autoUpdate: Boolean,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetOffsetHours: (Int) -> Unit,
    onSetOffsetMinutes: (Int) -> Unit,
    onSetRecordingPath: (String) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var tempPath by remember { mutableStateOf(recordingPath) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 语言
                Text(stringResource(R.string.language), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Row {
                    TextButton(onClick = { onSetLanguage("zh") }) { Text(stringResource(R.string.chinese), color = if(lang=="zh") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { onSetLanguage("en") }) { Text("English", color = if(lang=="en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 自动更新
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自动检测更新", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Switch(checked = autoUpdate, onCheckedChange = onSetAutoUpdate)
                }

                // 手动更新按钮
                Button(
                    onClick = onCheckUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("手动检查更新", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 录音路径
                Column {
                    Text("录音存放路径", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPath,
                        onValueChange = { tempPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    TextButton(
                        onClick = { onSetRecordingPath(tempPath) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("保存路径", fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 复制偏移
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.duplicate_delay), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        Text("+$offsetHours 小时 $offsetMinutes 分钟", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    
                    // 小时选择
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("小时", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        Slider(
                            value = offsetHours.toFloat(),
                            onValueChange = { onSetOffsetHours(it.toInt()) },
                            valueRange = 0f..24f,
                            modifier = Modifier.weight(1f).padding(start = 16.dp)
                        )
                    }
                    
                    // 分钟选择
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("分钟", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        Slider(
                            value = offsetMinutes.toFloat(),
                            onValueChange = { onSetOffsetMinutes(it.toInt()) },
                            valueRange = 0f..59f,
                            modifier = Modifier.weight(1f).padding(start = 16.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 主题
                Text(stringResource(R.string.themes), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val themeNames = listOf(
                        stringResource(R.string.theme_night),
                        stringResource(R.string.theme_forest),
                        stringResource(R.string.theme_sunset)
                    )
                    val colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        Color(0xFF2E7D4F),
                        Color(0xFFCC5422)
                    )
                    themeNames.forEachIndexed { i, name ->
                        val bgColor = if (theme == i) colors[i] else colors[i].copy(alpha = 0.25f)
                        val textColor = if (theme == i) Color.White else colors[i]
                        Box(
                            modifier = Modifier
                                .weight(1f).height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgColor)
                                .then(
                                    if (theme == i) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                    else Modifier.border(1.dp, colors[i].copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                )
                                .clickable { onSetTheme(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(name, color = textColor, fontSize = 12.sp, fontWeight = if(theme == i) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = MaterialTheme.colorScheme.primary) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
