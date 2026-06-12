package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.util.BatteryOptimizationGuide
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private data class SettingsTab(
    val icon: String,
    val label: String,
    val labelRes: Int? = null
)

private val tabs = listOf(
    SettingsTab("🌐", "通用"),
    SettingsTab("⏱", "偏移"),
    SettingsTab("🔋", "电池"),
    SettingsTab("📋", "关于", R.string.about_section),
    SettingsTab("🔐", "权限")
)

@Composable
fun AppSettingsDialog(
    theme: Int,
    lang: String,
    offsetHours: Int,
    offsetMinutes: Int,
    recordingPath: String,
    dbDirectoryPath: String,
    autoUpdate: Boolean,
    onSetTheme: (Int) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetOffsetHours: (Int) -> Unit,
    onSetOffsetMinutes: (Int) -> Unit,
    onSetRecordingPath: (String) -> Unit,
    onSetDatabaseDirectoryPath: (String) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var tempPath by remember { mutableStateOf(recordingPath) }
    var tempDbPath by remember { mutableStateOf(dbDirectoryPath) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var sidebarVisible by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ════ 左侧菜单 ════
                Column(
                    modifier = Modifier
                        .width(if (sidebarVisible) 130.dp else 44.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                ) {
                    // 折叠/展开按钮
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = { sidebarVisible = !sidebarVisible }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (sidebarVisible) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = if (sidebarVisible) "收起菜单" else "展开菜单",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                    // 标签列表
                    tabs.forEachIndexed { i, tab ->
                        val selected = selectedTab == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .then(
                                    if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                    else Modifier
                                )
                                .clickable { selectedTab = i }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tab.icon, fontSize = 16.sp)
                            if (sidebarVisible) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    tab.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 底部关闭按钮
                    if (sidebarVisible) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("关闭", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 分割线
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // ════ 右侧内容 ════
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // 顶部标题栏
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${tabs[selectedTab].icon}  ${tabs[selectedTab].label}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) {
                                Text("完成", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                    // 内容区（可滚动）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        when (selectedTab) {
                            0 -> GeneralSettingsContent(
                                lang = lang,
                                theme = theme,
                                autoUpdate = autoUpdate,
                                tempPath = tempPath,
                                tempDbPath = tempDbPath,
                                onSetLanguage = onSetLanguage,
                                onSetTheme = onSetTheme,
                                onSetAutoUpdate = onSetAutoUpdate,
                                onCheckUpdate = onCheckUpdate,
                                onTempPathChange = { tempPath = it },
                                onSavePath = { onSetRecordingPath(tempPath) },
                                onTempDbPathChange = { tempDbPath = it },
                                onSaveDbPath = { onSetDatabaseDirectoryPath(tempDbPath) }
                            )
                            1 -> OffsetContent(
                                offsetHours = offsetHours,
                                offsetMinutes = offsetMinutes,
                                onSetOffsetHours = onSetOffsetHours,
                                onSetOffsetMinutes = onSetOffsetMinutes
                            )
                            2 -> BatteryContent()
                            3 -> AboutContent()
                            4 -> PermissionContent(
                                context = context,
                                onRequestPermissions = { /* handled inside */ }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ════ 通用设置（语言 + 主题 + 更新 + 录音）════
@Composable
private fun GeneralSettingsContent(
    lang: String,
    theme: Int,
    autoUpdate: Boolean,
    tempPath: String,
    tempDbPath: String,
    onSetLanguage: (String) -> Unit,
    onSetTheme: (Int) -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onTempPathChange: (String) -> Unit,
    onSavePath: () -> Unit,
    onTempDbPathChange: (String) -> Unit,
    onSaveDbPath: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── 语言与主题 ──
        SettingsSectionCard("🌐 语言与主题") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.language), fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onSetLanguage("zh") }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(stringResource(R.string.chinese), fontSize = 12.sp, color = if(lang=="zh") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onSetLanguage("en") }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("English", fontSize = 12.sp, color = if(lang=="en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.themes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val themeNames = listOf(stringResource(R.string.theme_night), stringResource(R.string.theme_forest), stringResource(R.string.theme_sunset))
                val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFF2E7D4F), Color(0xFFCC5422))
                themeNames.forEachIndexed { i, name ->
                    val bgColor = if (theme == i) colors[i] else colors[i].copy(alpha = 0.25f)
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp)
                            .clip(RoundedCornerShape(8.dp)).background(bgColor)
                            .then(if (theme == i) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier.border(1.dp, colors[i].copy(alpha = 0.4f), RoundedCornerShape(8.dp)))
                            .clickable { onSetTheme(i) },
                        contentAlignment = Alignment.Center
                    ) { Text(name, fontSize = 11.sp, color = if (theme == i) Color.White else colors[i], fontWeight = if(theme == i) FontWeight.Bold else FontWeight.Normal) }
                }
            }
        }

        // ── 更新设置 ──
        SettingsSectionCard("📦 更新") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("自动检测更新", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = autoUpdate, onCheckedChange = onSetAutoUpdate)
            }
            Button(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Text("手动检查更新", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }

        // ── 数据库设置 ──
        SettingsSectionCard("🗄 数据库目录") {
            Text("公共目录路径（仅目录，不含文件名）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tempDbPath, onValueChange = onTempDbPathChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.primary)
            )
            Text(text = "例如 /storage/emulated/0/DroidCloudAlarm，需授予“所有文件访问权限”。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = onSaveDbPath, modifier = Modifier.align(Alignment.End)) { Text("保存目录", fontSize = 12.sp) }
        }

        // ── 录音设置 ──
        SettingsSectionCard("🎤 录音设置") {
            Text("录音存放路径", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tempPath, onValueChange = onTempPathChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface, focusedBorderColor = MaterialTheme.colorScheme.primary)
            )
            TextButton(onClick = onSavePath, modifier = Modifier.align(Alignment.End)) { Text("保存", fontSize = 12.sp) }
        }
    }
}

// ════ 复制偏移 ════
@Composable
private fun OffsetContent(
    offsetHours: Int,
    offsetMinutes: Int,
    onSetOffsetHours: (Int) -> Unit,
    onSetOffsetMinutes: (Int) -> Unit
) {
    SettingsSectionCard("⏱ 复制偏移") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("当前偏移", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+$offsetHours:$offsetMinutes", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("时", fontSize = 12.sp, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = offsetHours.toFloat(), onValueChange = { onSetOffsetHours(it.toInt()) }, valueRange = 0f..24f, modifier = Modifier.weight(1f))
            Text("${offsetHours}h", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分", fontSize = 12.sp, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = offsetMinutes.toFloat(), onValueChange = { onSetOffsetMinutes(it.toInt()) }, valueRange = 0f..59f, modifier = Modifier.weight(1f))
            Text("${offsetMinutes}m", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
    }
}

// ════ 电池优化 ════
@Composable
private fun BatteryContent() {
    val bCtx = LocalContext.current
    val batteryDisabled = remember { BatteryOptimizationGuide.isBatteryOptimizationDisabled(bCtx) }
    SettingsSectionCard(stringResource(R.string.alarm_reliability)) {
        Text(stringResource(R.string.battery_opt_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { BatteryOptimizationGuide.openBatteryOptimizationSettings(bCtx) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (batteryDisabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary,
                contentColor = if (batteryDisabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
            ), enabled = !batteryDisabled
        ) { Text(if (batteryDisabled) stringResource(R.string.battery_opt_already_disabled) else stringResource(R.string.disable_battery_opt), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.disable_battery_opt_tip), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { BatteryOptimizationGuide.openAutostartSettings(bCtx) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        ) { Text(stringResource(R.string.autostart_title), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.autostart_tip), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        var showManufacturerGuide by remember { mutableStateOf(false) }
        TextButton(onClick = { showManufacturerGuide = !showManufacturerGuide }) {
            Text(stringResource(R.string.manufacturer_guide_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (showManufacturerGuide) {
            val tip = remember { BatteryOptimizationGuide.getManufacturerTip() }
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(tip, fontSize = 10.sp, modifier = Modifier.padding(12.dp), lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ════ 关于 ════
@Composable
private fun AboutContent() {
    SettingsSectionCard(stringResource(R.string.about_section)) {
        Text("${stringResource(R.string.version_format, BuildConfig.VERSION_NAME)} (${stringResource(R.string.build_date_format, BuildConfig.BUILD_DATE)})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        var showChangelog by remember { mutableStateOf(false) }
        TextButton(onClick = { showChangelog = !showChangelog }) {
            Text(if (showChangelog) stringResource(R.string.hide_changelog) else stringResource(R.string.view_changelog), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (showChangelog) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Text(stringResource(R.string.changelog_content), fontSize = 10.sp, modifier = Modifier.padding(12.dp), lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("这是一个多功能闹钟应用，支持分组管理、WiFi同步、录音记录等功能", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("开发者: AtomGit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("版本 ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

// ════ 权限管理 ════
@Composable
private fun PermissionContent(
    context: android.content.Context,
    onRequestPermissions: () -> Unit
) {
    SettingsSectionCard("🔐 权限管理") {
        Log.d("AppSettings", "SDK_INT=${Build.VERSION.SDK_INT}, R=${Build.VERSION_CODES.R}")
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                Toast.makeText(context, "基本权限已获取", Toast.LENGTH_SHORT).show()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            } else Toast.makeText(context, "权限被拒绝: ${permissions.filter { !it.value }.keys.joinToString(", ")}", Toast.LENGTH_LONG).show()
        }
        Button(onClick = {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { list.add(Manifest.permission.POST_NOTIFICATIONS); list.add(Manifest.permission.READ_MEDIA_AUDIO) }
            else { list.add(Manifest.permission.READ_EXTERNAL_STORAGE); list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            launcher.launch(list.toTypedArray())
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("请求录音/通知权限", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            Log.d("AppSettings", "请求文件权限: SDK_INT=${Build.VERSION.SDK_INT}, R=${Build.VERSION_CODES.R}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val isManager = Environment.isExternalStorageManager(); Log.d("AppSettings", "isExternalStorageManager=$isManager")
                if (!isManager) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                else Toast.makeText(context, "文件访问权限已获取", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Android 10 不需要此权限，请使用上方按钮获取", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        ) { Text("请求文件访问权限", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

// ════ 辅助：卡片容器 ════
@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}
