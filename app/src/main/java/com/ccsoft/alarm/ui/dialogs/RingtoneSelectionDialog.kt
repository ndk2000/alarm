/*
 * RingtoneSelectionDialog.kt — 铃声选择弹窗
 *
 * 这是闹钟应用的核心界面之一，用户在这里：
 * 1. 从系统内置铃声中选择
 * 2. 从本地存储的录音文件中选择
 * 3. 从外部导入音频文件（两种方式：文件选择器 / 文档选择器）
 * 4. 自己录制新录音
 *
 * 界面布局（从上到下）：
 * ┌──────────────────────────────────┐
 * │ 标题：选择铃声                     │
 * │ [📤外部导入] [🎵从录音机导入] [🎤自己录音] │
 * │ 🔍 搜索框                        │
 * │ [🎙️录音] [📂自定义] [🔔系统铃声]    │
 * │ 列表内容...                       │
 * │            [完成]                 │
 * └──────────────────────────────────┘
 */
package com.ccsoft.alarm.ui.dialogs

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.ccsoft.alarm.R
import com.ccsoft.alarm.ui.components.RingtoneListItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneSelectionDialog(
    // 当前已选中的铃声路径（null 表示使用系统默认铃声）
    currentSelectedPath: String?,
    // 用户自定义导入的铃声列表（只存文件名，路径由 customRecordingPath 拼接）
    customRingtones: List<String>,
    // 系统内置铃声列表，每项是 (显示名称, 文件路径) 对
    systemRingtones: List<Pair<String, String>>,
    // 本地扫描到的录音文件列表，每项是 (显示名称, 文件路径) 对
    localRecordings: List<Pair<String, String>> = emptyList(),
    // 自定义录音/导入文件的存储目录路径
    customRecordingPath: String,
    // 是否正在录音中
    isRecording: Boolean,
    // 当前录音时长（秒）
    recordingDuration: Int,
    // 开始录音的回调
    onStartRecording: () -> Unit,
    // 停止录音的回调，返回保存的文件路径
    onStopRecording: (String) -> String?,
    // 取消录音的回调
    onCancelRecording: () -> Unit,
    // 关闭弹窗的回调
    onDismiss: () -> Unit,
    // 用户选中某个铃声时的回调，传入文件路径或 null（系统默认）
    onSelect: (String?) -> Unit,
    // 导入外部音频文件的回调，传入 Uri 和文件名，返回保存后的路径
    onImportAudio: (Uri, String) -> String?
) {
    // ========== 状态变量 ==========

    // 控制是否显示录音对话框
    var showRecordingStudio by remember { mutableStateOf(false) }

    // 获取 Android 上下文，用于系统服务调用
    val context = LocalContext.current

    // ========== 麦克风权限申请 ==========
    // 点击"自己录音"按钮时触发权限申请
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，弹出录音对话框
            showRecordingStudio = true
        } else {
            // 权限被拒绝，提示用户
            Toast.makeText(context, context.getString(R.string.permission_mic_required), Toast.LENGTH_SHORT).show()
        }
    }

    // 搜索框中的用户输入文字
    var searchQuery by remember { mutableStateOf("") }

    // 自动定位到当前铃声所在的标签页
    // 规则：content:// 开头的路径→系统铃声标签；customRecordingPath 开头的→自定义标签；其他→本地录音标签
    var selectedTab by remember(currentSelectedPath) {
        val initialTab = when {
            currentSelectedPath == null || currentSelectedPath.startsWith("content://") -> 2
            currentSelectedPath.startsWith(customRecordingPath) -> 1
            else -> 0
        }
        mutableIntStateOf(initialTab)
    }
    
    // ========== 音频预览播放器 ==========
    // previewPlayer: 保存当前正在播放的 MediaPlayer 实例，用于控制暂停/停止
    // currentlyPlayingPath: 记录当前在播放哪个文件的路径，用于高亮显示
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentlyPlayingPath by remember { mutableStateOf<String?>(null) }
    
    // 弹窗关闭时，释放播放器资源，防止内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        }
    }
    
    // 播放/切换音频预览
    // 如果传入的路径和当前正在播放的路径一样，则表示"暂停/停止"（切换效果）
    fun playPreview(path: String?) {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
            
            if (currentlyPlayingPath == path) {
                currentlyPlayingPath = null
                return
            }
            
            val mPlayer = MediaPlayer()
            previewPlayer = mPlayer
            
            if (path.isNullOrEmpty()) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mPlayer.setDataSource(context, alarmUri)
            } else if (path.startsWith("content://")) {
                mPlayer.setDataSource(context, Uri.parse(path))
            } else {
                mPlayer.setDataSource(path)
            }
            
            mPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                     .setUsage(AudioAttributes.USAGE_ALARM)
                     .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                     .build()
            )
            mPlayer.prepare()
            mPlayer.start()
            currentlyPlayingPath = path
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.play_error), Toast.LENGTH_SHORT).show()
        }
    }
    
    // ========== 「外部导入」按钮的启动器 ==========
    // 使用 ACTION_GET_CONTENT 打开系统文件选择器，让用户从手机任意目录挑选音频文件
    // 导入后把文件复制到应用私有目录，并选中该文件
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                var displayName = "custom_ringtone_${System.currentTimeMillis()}.mp3"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
                val savedPath = onImportAudio(uri, displayName)
                if (savedPath != null) {
                    onSelect(savedPath)
                    Toast.makeText(context, context.getString(R.string.imported_and_selected, displayName), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========== 「从录音机导入」按钮的启动器 ==========
    // 使用 OpenDocument 打开系统文档选择器，让用户从录音机等应用中选择音频文件
    // 和外部导入的区别：OpenDocument 不指定 INITIAL_URI，让系统自己决定显示哪个文档列表
    val importRecorderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var displayName = "audio_${System.currentTimeMillis()}.mp3"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex)
                }
            }
            val savedPath = onImportAudio(uri, displayName)
            if (savedPath != null) {
                onSelect(savedPath)
                Toast.makeText(context, context.getString(R.string.imported_and_selected, displayName), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,  // 点击弹窗外部或返回键时关闭弹窗
        modifier = Modifier
            .fillMaxWidth(0.92f)       // 弹窗宽度占屏幕 92%
            .wrapContentHeight(),       // 高度根据内容自动撑开
        properties = DialogProperties(usePlatformDefaultWidth = false),  // 禁用默认宽度限制，让我们自定宽度
        title = {
            // 标题：只显示文字"选择铃声"，简洁清爽
            Text(
                stringResource(R.string.select_ringtone_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            // ========== 弹窗内容区域 ==========
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).imePadding()) {
                
                // ========== 顶栏操作按钮行：三个功能按钮 ==========
                // 图标按钮在上方（固定大小不变形），文字在按钮下方
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    // ===== 按钮1：外部导入 =====
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "audio/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        val recorderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ARecordings")
                                        putExtra("android.provider.extra.INITIAL_URI", recorderUri)
                                        putExtra("android.provider.extra.SHOW_ADVANCED", true)
                                        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                                    }
                                    audioPickerLauncher.launch(intent)
                                } catch (e: Exception) {
                                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "audio/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                    audioPickerLauncher.launch(fallbackIntent)
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFADC6FF)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFADC6FF)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.external_import),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }

                    // ===== 按钮2：从录音机导入 =====
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedButton(
                            onClick = {
                                importRecorderLauncher.launch(arrayOf("audio/*"))
                            },
                            modifier = Modifier.size(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFADC6FF)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFADC6FF)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.import_from_recorder),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }

                    // ===== 按钮3：自己录音（实心，深红色） =====
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Button(
                            onClick = {
                                val recordPermission = Manifest.permission.RECORD_AUDIO
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    recordPermission
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    showRecordingStudio = true
                                } else {
                                    recordPermissionLauncher.launch(recordPermission)
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5E1717),
                                contentColor = Color(0xFFFFDAD7)
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.record_yourself),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }

                // ========== 搜索框 ==========
                // 实时过滤下方列表的内容，支持按文件名搜索
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_placeholder), fontSize = 13.sp, color = Color(0xFF8E9099)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFADC6FF)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
                
                // ========== 三个分类标签页 ==========
                // [🎙️录音] [📂自定义] [🔔系统铃声]
                // 根据 selectedTab 的值切换显示不同类别的铃声列表
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFFADC6FF),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    // Tab 0：本地录音文件（从手机存储扫描到的录音）
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.tab_system_recording), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 0) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                    // Tab 1：自定义导入的铃声（用户从外部导入的音频文件）
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.tab_custom_sync), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 1) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                    // Tab 2：系统内置铃声（手机系统自带的提示音）
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.tab_builtin_sounds), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 2) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                }
                
                // ========== 列表内容区域 ==========
                // 根据当前选中的 Tab 显示不同类别的铃声列表
                // 支持搜索关键词过滤
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 如果当前是"录音"标签页（Tab 0）
                    if (selectedTab == 0) {
                        // 根据搜索关键词过滤录音列表
                        val filteredRecordings = localRecordings.filter { it.first.contains(searchQuery, ignoreCase = true) }
                        if (filteredRecordings.isEmpty()) {
                            // 没有匹配的录音文件时，显示空状态提示
                            Box(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.no_recordings_found),
                                    color = Color(0xFF8E9099),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            // 有录音文件，用 LazyColumn 显示可滚动的列表
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredRecordings) { pair ->
                                    // 每一项是一个环形列表条目，显示文件名、播放和选中功能
                                    RingtoneListItem(
                                        title = pair.first,
                                        isSelected = currentSelectedPath == pair.second,
                                        isPlaying = currentlyPlayingPath == pair.second,
                                        onPlayToggle = { playPreview(pair.second) },
                                        onSelect = { onSelect(pair.second) }
                                    )
                                }
                            }
                        }
                    // 如果当前是"自定义"标签页（Tab 1）
                    } else if (selectedTab == 1) {
                        // 根据搜索关键词过滤自定义列表
                        val filteredCustom = customRingtones.filter { it.contains(searchQuery, ignoreCase = true) }
                        if (filteredCustom.isEmpty()) {
                            // 没有自定义铃声时，显示空状态提示
                            Box(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.no_custom_ringtones), color = Color(0xFF8E9099), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            // 有自定义铃声，显示可滚动的列表
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredCustom) { name ->
                                    // 用目录路径+文件名拼接出完整路径
                                    val fullPath = File(customRecordingPath, name).absolutePath
                                    RingtoneListItem(
                                        title = name,
                                        isSelected = currentSelectedPath == fullPath,
                                        isPlaying = currentlyPlayingPath == fullPath,
                                        onPlayToggle = { playPreview(fullPath) },
                                        onSelect = { onSelect(fullPath) }
                                    )
                                }
                            }
                        }
                    // 如果当前是"系统铃声"标签页（Tab 2）
                    } else {
                        // 根据搜索关键词过滤系统铃声列表
                        val filteredSystem = systemRingtones.filter { it.first.contains(searchQuery, ignoreCase = true) }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // 第一项：系统默认铃声（null 路径表示默认）
                            item {
                                RingtoneListItem(
                                    title = stringResource(R.string.default_ringtone),
                                    isSelected = currentSelectedPath == null,
                                    isPlaying = currentlyPlayingPath == null,
                                    onPlayToggle = { playPreview(null) },
                                    onSelect = { onSelect(null) }
                                )
                            }
                            // 后续项：逐个显示系统内置铃声
                            items(filteredSystem) { pair ->
                                RingtoneListItem(
                                    title = pair.first,
                                    isSelected = currentSelectedPath == pair.second,
                                    isPlaying = currentlyPlayingPath == pair.second,
                                    onPlayToggle = { playPreview(pair.second) },
                                    onSelect = { onSelect(pair.second) }
                                )
                            }
                        }
                    }
                }
            }
        },
        // 底部确认按钮
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done), fontWeight = FontWeight.Bold, color = Color(0xFFADC6FF))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface  // 弹窗背景色跟随主题
    )

    // ========== 录音对话框 ==========
    // 当用户点击"自己录音"按钮并已获得麦克风权限时，弹出此对话框
    if (showRecordingStudio) {
        AudioRecordDialog(
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onDismiss = { showRecordingStudio = false },
            onRecordSaved = { absolutePath ->
                onSelect(absolutePath)  // 录音保存后自动选中该文件
                showRecordingStudio = false
            }
        )
    }
}
