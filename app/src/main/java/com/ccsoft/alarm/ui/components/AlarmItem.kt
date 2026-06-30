package com.ccsoft.alarm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.R
import com.ccsoft.alarm.db.Alarm

@Composable
fun AlarmItem(
    alarm: Alarm,
    groupEnabled: Boolean,
    onToggle: (Alarm, Boolean) -> Unit,
    onDelete: (Alarm) -> Unit,
    onDuplicate: (Alarm) -> Unit,
    onEdit: (Alarm) -> Unit
) {
    val enabled = alarm.isEnabled && groupEnabled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onEdit(alarm) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：备注标签（独占一行）
            Text(
                text = alarm.label.ifBlank { stringResource(R.string.no_label) },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )

            // 第二行：时间与控制按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = String.format("%02d:%02d", alarm.hour, alarm.minute),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray
                )
                
                Spacer(modifier = Modifier.weight(1f))

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggle(alarm, it) },
                    enabled = groupEnabled,
                    modifier = Modifier.scale(0.8f)
                )

                IconButton(onClick = { onDuplicate(alarm) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Duplicate",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 第三行：周期与铃声
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = alarm.getActiveDaysDesc(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.width(12.dp))

                val ringtoneName = alarm.ringtonePath?.substringAfterLast("/") ?: stringResource(R.string.default_ringtone)
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = ringtoneName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
