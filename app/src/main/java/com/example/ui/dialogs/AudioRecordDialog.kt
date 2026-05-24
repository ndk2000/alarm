package com.example.ui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

private enum class MicState { CHECKING, READY, NO_PERMISSION }

@Composable
fun AudioRecordDialog(
    isRecording: Boolean,
    recordingDuration: Int,
    onStartRecording: () -> Unit,
    onStopRecording: (String) -> String?,
    onCancelRecording: () -> Unit,
    onDismiss: () -> Unit,
    onRecordSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var fileName by remember {
        val defaultName = "rec1"
        mutableStateOf(TextFieldValue(defaultName, selection = TextRange(0, defaultName.length)))
    }
    var micState by remember { mutableStateOf(MicState.CHECKING) }

    // 检测麦克风权限
    LaunchedEffect(Unit) {
        micState = if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MicState.READY
        } else {
            MicState.NO_PERMISSION
        }
    }

    // 对话框出现时自动弹键盘 + 全选文件名
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        fileName = fileName.copy(selection = TextRange(0, fileName.text.length))
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) {
                onCancelRecording()
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (isRecording) {
                Toast.makeText(context, context.getString(R.string.recording_save_failed), Toast.LENGTH_SHORT).show()
            } else {
                onDismiss()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFA5A5A), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.recording_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { if (!isRecording) fileName = it },
                    label = { Text(stringResource(R.string.recording_name_placeholder), color = Color(0xFF8E9099)) },
                    singleLine = true,
                    enabled = !isRecording,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedBorderColor = Color(0xFFADC6FF),
                        unfocusedBorderColor = Color(0xFF43474E)
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Color(0xFF25272C), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.2f,
                                animationSpec = infiniteRepeatable(
                                    animation = keyframes { durationMillis = 1000 },
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .graphicsLayer(alpha = alpha)
                                    .background(Color(0xFFFA5A5A), CircleShape)
                            )

                            val minutes = recordingDuration / 60
                            val seconds = recordingDuration % 60
                            val timeStr = String.format("%02d:%02d", minutes, seconds)
                            Text(
                                stringResource(R.string.is_recording, timeStr),
                                color = Color(0xFFFA5A5A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        val statusText = when (micState) {
                            MicState.CHECKING -> R.string.mic_checking
                            MicState.READY -> R.string.mic_ready
                            MicState.NO_PERMISSION -> R.string.mic_no_permission
                        }
                        val statusColor = when (micState) {
                            MicState.CHECKING -> Color(0xFFBFC2CC)
                            MicState.READY -> Color(0xFF6FCF97)
                            MicState.NO_PERMISSION -> Color(0xFFFA5A5A)
                        }
                        Text(
                            stringResource(statusText),
                            color = statusColor,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isRecording) {
                            val savedPath = onStopRecording(fileName.text)
                            if (savedPath != null) {
                                onRecordSaved(savedPath)
                            } else {
                                Toast.makeText(context, context.getString(R.string.recording_save_failed), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            onStartRecording()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFF8C1D18) else Color(0xFF1F3B68),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecording) stringResource(R.string.stop_and_select) else stringResource(R.string.start_recording),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = {
                    if (isRecording) {
                        onCancelRecording()
                    }
                    onDismiss()
                }
            ) {
                Text(
                    text = if (isRecording) stringResource(R.string.discard_and_back) else stringResource(R.string.recording_back),
                    color = Color(0xFF8E9099),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
