package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alarm.AlarmScheduler
import com.example.alarm.ChimeGenerator
import com.example.db.Alarm
import com.example.db.AlarmDatabase
import com.example.db.AlarmGroup
import com.example.db.AlarmRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CountdownTab(
    alarms: List<Alarm>,
    groups: List<AlarmGroup>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val enabledGroupIds = remember(groups) {
        groups.filter { it.isEnabled }.map { it.id }.toSet()
    }
    val enabledAlarms = remember(alarms, tick, enabledGroupIds) {
        alarms.filter { it.isEnabled && it.groupId in enabledGroupIds }
    }
    val todayDate = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // ────────── 数据库读取今天的记录 ──────────
    var records by remember { mutableStateOf<List<AlarmRecord>>(emptyList()) }
    // 当前查看的日期（用于历史翻页）
    var viewDate by remember { mutableStateOf(todayDate) }
    // 所有有记录的日期
    var allDates by remember { mutableStateOf<List<String>>(emptyList()) }

    fun loadRecords(date: String) {
        scope.launch(Dispatchers.IO) {
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
            val list = db.alarmRecordDao().getRecordsByDate(date)
            withContext(Dispatchers.Main) { records = list }
        }
    }
    fun loadAllDates() {
        scope.launch(Dispatchers.IO) {
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
            val list = db.alarmRecordDao().getAllDates()
            withContext(Dispatchers.Main) { allDates = list }
        }
    }

    LaunchedEffect(Unit) {
        // 先创建/更新今天的闹钟记录
        loadAllDates()
        loadRecords(todayDate)
    }
    LaunchedEffect(tick) {
        // 每秒检查是否有新的已过点闹钟需要入库
        val now = System.currentTimeMillis()
        val calNow = Calendar.getInstance()
        val todayWeekDay = when (calNow.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 7
        }

        val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
        for (alarm in enabledAlarms) {
            val days = alarm.daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            val isTodayActive = days.isEmpty() || todayWeekDay in days
            if (!isTodayActive) continue

            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val targetMillis = targetCal.timeInMillis
            if (targetMillis > now) continue // 还没到时间

            val existing = db.alarmRecordDao().getRecord(alarm.id, todayDate)
            if (existing == null) {
                db.alarmRecordDao().insert(AlarmRecord(
                    alarmId = alarm.id,
                    label = alarm.label.ifBlank { "闹钟" },
                    scheduledTime = targetMillis,
                    recordDate = todayDate
                ))
            }

            // 超过10分钟未关闭 → 失败
            val tenMinAgo = now - 600_000L
            val record = db.alarmRecordDao().getRecord(alarm.id, todayDate)
            if (record != null && record.status == "PENDING" && record.scheduledTime < tenMinAgo) {
                db.alarmRecordDao().updateStatus(record.id, "FAILED", null)
            }
        }
        loadRecords(viewDate)
        loadAllDates()
    }

    val countdowns = remember(enabledAlarms, tick) {
        val now = System.currentTimeMillis()
        val calNow = Calendar.getInstance()
        val todayWeekDay = when (calNow.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 7
        }
        enabledAlarms.mapNotNull { alarm ->
            val days = alarm.daysOfWeek.split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            val isTodayActive = days.isEmpty() || todayWeekDay in days
            if (!isTodayActive) return@mapNotNull null
            val todayTarget = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour); set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val targetMillis = todayTarget.timeInMillis
            val remainingSec = (targetMillis - now) / 1000
            AlarmCountdown(alarm, alarm.label.ifBlank { "闹钟" }, targetMillis, remainingSec, remainingSec <= 0)
        }.sortedBy { it.remainingSec }
    }

    // 日期导航索引
    var currentDateIndex by remember(viewDate, allDates) {
        val idx = allDates.indexOf(viewDate)
        mutableIntStateOf(if (idx >= 0) idx else 0)
    }

    // ────────── 不足2分钟全屏（仅今天） ──────────
    val nearestAlarm = if (viewDate == todayDate) {
        countdowns.filter { !it.isPast }.minByOrNull { it.remainingSec }
    } else null
    if (viewDate == todayDate && nearestAlarm != null && nearestAlarm.remainingSec <= 120) {
        FullScreenUrgentView(nearestAlarm, tick)
        return
    }

    // ────────── 主界面 ──────────
    Column(modifier = Modifier.fillMaxSize()) {
        // 日期导航栏
        if (allDates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (currentDateIndex < allDates.size - 1) {
                        currentDateIndex++
                        viewDate = allDates[currentDateIndex]
                        loadRecords(viewDate)
                    }
                }) { Icon(Icons.Default.ArrowBack, "前一天") }
                Text(text = viewDate, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    if (currentDateIndex > 0) {
                        currentDateIndex--
                        viewDate = allDates[currentDateIndex]
                        loadRecords(viewDate)
                    }
                }) { Icon(Icons.Default.ArrowForward, "后一天") }
            }
        }

        if (records.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                            .alarmRecordDao().deleteByDate(viewDate)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            loadRecords(viewDate)
                            loadAllDates()
                        }
                    }
                }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空当日", fontSize = 12.sp, color = Color(0xFFF44336))
                }
            }
        }

        if (countdowns.isEmpty() && records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏳", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("暂无记录", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 未过点的倒计时（仅今天显示）
            if (viewDate == todayDate) {
                val activeItems = countdowns.filter { !it.isPast }
                items(activeItems, key = { "cd_${it.alarm.id}" }) { cd ->
                    val isUrgent = cd.remainingSec <= 600
                    val isNearest = isUrgent && cd.alarm.id == nearestAlarm?.alarm?.id
                    CountdownCard(cd, isUrgent, isNearest)
                }
            }

            // 已过点的记录
            val pastItems = records.filter { it.recordDate == viewDate }
            items(pastItems, key = { "rec_${it.id}" }) { record ->
                PastRecordCard(record, context, onChanged = {
                    loadRecords(viewDate)
                    loadAllDates()
                })
            }
        }
    }
}
@Composable
private fun PastRecordCard(
    record: AlarmRecord,
    context: android.content.Context,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dismiss = { id: Long ->
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                .alarmRecordDao().deleteById(id)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onChanged() }
        }
    }

    val statusColor = when (record.status) {
        "COMPLETED" -> Color(0xFF4CAF50)
        "FAILED" -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
    val statusText = when (record.status) {
        "COMPLETED" -> "✅ 已完成"
        "FAILED" -> "❌ 任务失败"
        else -> "⏳ 待处理"
    }
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.scheduledTime))

    // 如果还在 PENDING 且 10 分钟窗口内，显示标记完成按钮
    val now = System.currentTimeMillis()
    val withinWindow = record.status == "PENDING" && (now - record.scheduledTime) < 600_000L && record.recordDate == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = statusColor.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, statusColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("⏰ $timeStr · $statusText", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.dismissTime != null) {
                    val dismissStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.dismissTime))
                    Text("关闭 $dismissStr", fontSize = 11.sp, color = statusColor.copy(alpha = 0.7f))
                }
            }

            if (withinWindow) {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                            db.alarmRecordDao().updateStatus(record.id, "COMPLETED", System.currentTimeMillis())
                            withContext(Dispatchers.Main) { onChanged() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("✅", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("完成", fontSize = 11.sp, color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
            }

            IconButton(onClick = { dismiss(record.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFF9E9E9E), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CountdownCard(cd: AlarmCountdown, isUrgent: Boolean, isNearestUrgent: Boolean) {
    val bgColor = when {
        isNearestUrgent -> Color(0xFFFF3333).copy(alpha = 0.15f)
        isUrgent -> Color(0xFFFF8800).copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    val borderColor = when {
        isNearestUrgent -> Color(0xFFFF3333)
        isUrgent -> Color(0xFFFF8800)
        else -> Color.Transparent
    }
    val textColor = when {
        isNearestUrgent -> Color(0xFFFF3333)
        isUrgent -> Color(0xFFFF8800)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cd.nextTimeMillis))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = if (borderColor != Color.Transparent) androidx.compose.foundation.BorderStroke(2.dp, borderColor) else null,
        shadowElevation = if (isUrgent) 8.dp else 2.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cd.label, fontSize = if (isUrgent) 20.sp else 16.sp, fontWeight = if (isUrgent) FontWeight.Black else FontWeight.Medium, color = textColor)
                Spacer(Modifier.height(4.dp))
                Text("响铃 $timeStr", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatCountdown(cd.remainingSec), fontSize = if (isUrgent) 36.sp else 28.sp, fontWeight = if (isUrgent) FontWeight.Black else FontWeight.Medium, color = textColor)
                Text(
                    when { cd.remainingSec < 60 -> "秒"; cd.remainingSec < 3600 -> "分钟"; else -> "小时" },
                    fontSize = 12.sp, color = textColor.copy(alpha = 0.6f)
                )
            }
            if (isUrgent) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(12.dp).background(if (cd.remainingSec <= 120) Color(0xFFFF3333) else Color(0xFFFF8800), shape = CircleShape))
            }
        }
    }
}

@Composable
private fun FullScreenUrgentView(nearest: AlarmCountdown, tick: Long) {
    val animatedColor by animateColorAsState(
        targetValue = if (tick % 2000 < 1000) Color(0xFFFF3333) else Color(0xFFFF8888),
        animationSpec = tween(500, easing = LinearEasing), label = "flash"
    )
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("⏰", fontSize = 64.sp)
        Spacer(Modifier.height(24.dp))
        Text(nearest.label, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(formatCountdown(nearest.remainingSec), fontSize = 72.sp, fontWeight = FontWeight.Black, color = animatedColor,
            modifier = Modifier.shadow(16.dp, RoundedCornerShape(16.dp)).background(Color(0x44000000), RoundedCornerShape(16.dp)).padding(horizontal = 32.dp, vertical = 16.dp))
        Spacer(Modifier.height(8.dp))
        Text("后响铃", fontSize = 18.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

private fun formatCountdown(remainingSec: Long): String {
    if (remainingSec <= 0) return "00:00"
    val h = remainingSec / 3600; val m = (remainingSec % 3600) / 60; val s = remainingSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}

private data class AlarmCountdown(
    val alarm: Alarm, val label: String, val nextTimeMillis: Long,
    val remainingSec: Long, val isPast: Boolean
)
