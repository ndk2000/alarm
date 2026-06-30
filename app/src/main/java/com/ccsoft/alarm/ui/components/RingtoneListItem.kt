package com.ccsoft.alarm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.R

@Composable
fun RingtoneListItem(
    title: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onSelect: () -> Unit
) {
    // 精细化的对比度配置：剔除半透及未标定灰色，防止因自动亮暗主题造成"字白底白、看不清"的感觉
    val cardBg = if (isSelected) {
        // 选中态背景：浓重而高雅的深孔雀蓝
        Color(0xFF1F3B68)
    } else {
        // 未选中态背景：柔和沉稳的暗灰蓝色（彻底避免白色和高亮灰色产生穿透）
        Color(0xFF25272D)
    }
    
    val textAndIconColor = if (isSelected) {
        // 选中态字色：耀眼高雅的淡蓝色
        Color(0xFFADC6FF)
    } else {
        // 未选中态字色：清晰温和的浅灰白 (高对比度确保在任何暗色卡片上绝对清晰，杜绝看不见)
        Color(0xFFE3E2E6)
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = textAndIconColor,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textAndIconColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            IconButton(
                onClick = onPlayToggle,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = stringResource(R.string.preview_ringtone),
                    tint = if (isPlaying) Color(0xFFFF8B8B) else Color(0xFF8BAAFF),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
