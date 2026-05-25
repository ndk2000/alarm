package com.example.ui.dialogs

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
import com.example.R
import com.example.ui.components.RingtoneListItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneSelectionDialog(
    currentSelectedPath: String?,
    customRingtones: List<String>,
    systemRingtones: List<Pair<String, String>>,
    localRecordings: List<Pair<String, String>> = emptyList(),
    customRecordingPath: String,
    isRecording: Boolean,
    recordingDuration: Int,
    onStartRecording: () -> Unit,
    onStopRecording: (String) -> String?,
    onCancelRecording: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onImportAudio: (Uri, String) -> String?
) {
    var showRecordingStudio by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showRecordingStudio = true
        } else {
            Toast.makeText(context, context.getString(R.string.permission_mic_required), Toast.LENGTH_SHORT).show()
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    
    // 自动定位到当前铃声所在的 Tab
    var selectedTab by remember(currentSelectedPath) {
        val initialTab = when {
            currentSelectedPath == null || currentSelectedPath.startsWith("content://") -> 2
            currentSelectedPath.startsWith(customRecordingPath) -> 1
            else -> 0
        }
        mutableIntStateOf(initialTab)
    }
    
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentlyPlayingPath by remember { mutableStateOf<String?>(null) }
    
    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        }
    }
    
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

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .wrapContentHeight(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.select_ringtone_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1F3B68),
                            contentColor = Color(0xFFADC6FF)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.external_import), fontSize = 11.sp)
                    }

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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5E1717),
                            contentColor = Color(0xFFFFDAD7)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.record_yourself), fontSize = 11.sp)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
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
                
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFFADC6FF),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.tab_system_recording), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 0) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.tab_custom_sync), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 1) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.tab_builtin_sounds), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selectedTab == 2) Color(0xFFADC6FF) else Color(0xFF8E9099)) }
                    )
                }
                
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedTab == 0) {
                        val filteredRecordings = localRecordings.filter { it.first.contains(searchQuery, ignoreCase = true) }
                        if (filteredRecordings.isEmpty()) {
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
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredRecordings) { pair ->
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
                    } else if (selectedTab == 1) {
                        val filteredCustom = customRingtones.filter { it.contains(searchQuery, ignoreCase = true) }
                        if (filteredCustom.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.no_custom_ringtones), color = Color(0xFF8E9099), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filteredCustom) { name ->
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
                    } else {
                        val filteredSystem = systemRingtones.filter { it.first.contains(searchQuery, ignoreCase = true) }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                RingtoneListItem(
                                    title = stringResource(R.string.default_ringtone),
                                    isSelected = currentSelectedPath == null,
                                    isPlaying = currentlyPlayingPath == null,
                                    onPlayToggle = { playPreview(null) },
                                    onSelect = { onSelect(null) }
                                )
                            }
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done), fontWeight = FontWeight.Bold, color = Color(0xFFADC6FF))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )

    if (showRecordingStudio) {
        AudioRecordDialog(
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            onCancelRecording = onCancelRecording,
            onDismiss = { showRecordingStudio = false },
            onRecordSaved = { absolutePath ->
                onSelect(absolutePath)
                showRecordingStudio = false
            }
        )
    }
}
