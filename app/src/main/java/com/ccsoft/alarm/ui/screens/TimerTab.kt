package com.ccsoft.alarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.R
import com.ccsoft.alarm.ui.components.WheelDialPicker
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PictureInPicture
import com.ccsoft.alarm.service.TimerService

@Composable
fun TimerTab(
    remainingSeconds: Int,
    isRunning: Boolean,
    isRinging: Boolean,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismissRinging: () -> Unit,
    hours: Int,
    minutes: Int,
    seconds: Int,
    onSetHours: (Int) -> Unit,
    onSetMinutes: (Int) -> Unit,
    onSetSeconds: (Int) -> Unit,
    // 预警音设置（与 CountdownTab 共用）
    warningSoundType: String = "tick_tock",
    warningCustomPath: String = "",
    warningTtsText: String = ""
) {
    // TimerService 由 AlarmService 负责启动，UI 不启动服务
    // 组件卸载时停止
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isRinging) {
            // ── 计时结束，响铃中 ──
            Text(
                stringResource(R.string.timer_done),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "00:00:00",
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onDismissRinging,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.timer_dismiss), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else if (!isRunning) {
            Text(stringResource(R.string.set_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            // 三列拨盘选择器：时 / 分 / 秒
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.hours_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = hours,
                        range = 0..23,
                        onValueChange = onSetHours
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.minutes_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = minutes,
                        range = 0..59,
                        onValueChange = onSetMinutes
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.seconds_unit), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WheelDialPicker(
                        value = seconds,
                        range = 0..59,
                        onValueChange = onSetSeconds
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 显示合计时间预览
            val totalSecs = hours * 3600 + minutes * 60 + seconds
            Text(
                text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onStart(totalSecs) },
                enabled = totalSecs > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.start_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            val h = remainingSeconds / 3600
            val m = (remainingSeconds % 3600) / 60
            val s = remainingSeconds % 60

            Text(
                text = String.format("%02d:%02d:%02d", h, m, s),
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.stop_timer), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


// ═══ 计时器响铃现在由 TimerService 处理 ═══
