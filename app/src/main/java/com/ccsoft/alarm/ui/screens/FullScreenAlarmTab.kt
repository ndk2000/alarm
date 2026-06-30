package com.ccsoft.alarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmGroup
import com.ccsoft.alarm.util.PreferencesManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.associateBy

/**
 * 全屏闹钟显示内容。
 * 横屏显示最新三条闹钟 + 当前日期时间 + 倒计时。
 * 点击屏幕任意位置退出。
 */
@Composable
fun FullScreenAlarmTab(
    alarms: List<Alarm>,
    groups: List<AlarmGroup>,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val prefs = remember { PreferencesManager(context) }

    // 屏幕尺寸（dp），用于计算offset边界，防止元素移出屏幕
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val screenHeightDp = configuration.screenHeightDp.toFloat()
    // 边界：允许元素在屏幕内移动，最大偏移不超过屏幕的一半（保证元素不整体出界）
    val maxOffsetX = screenWidthDp / 2f
    val maxOffsetY = screenHeightDp / 2f
    val minOffsetX = -maxOffsetX
    val minOffsetY = -maxOffsetY

    // 当前时间（每秒刷新）
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            val delayMs = 1000L - (currentTime % 1000)
            delay(delayMs)
        }
    }

    // 锁定状态
    var isLocked by remember { mutableStateOf(true) }

    // 编辑窗口状态
    var showEditDialog by remember { mutableStateOf(false) }
    var editingElement by remember { mutableStateOf("") }
    // 编辑弹窗拖动偏移
    var dialogOffsetX by remember { mutableStateOf(0f) }
    var dialogOffsetY by remember { mutableStateOf(0f) }

    // 元素位置偏移（用于拖动调整位置）- 从Preferences读取
    var dateOffsetX by remember { mutableStateOf(prefs.getFsDateOffsetX()) }
    var dateOffsetY by remember { mutableStateOf(prefs.getFsDateOffsetY()) }
    var weekOffsetX by remember { mutableStateOf(prefs.getFsWeekOffsetX()) }
    var weekOffsetY by remember { mutableStateOf(prefs.getFsWeekOffsetY()) }
    var timeOffsetX by remember { mutableStateOf(prefs.getFsTimeOffsetX()) }
    var timeOffsetY by remember { mutableStateOf(prefs.getFsTimeOffsetY()) }
    // 闹钟项元素偏移
    var alarmTimeOffsetX by remember { mutableStateOf(prefs.getFsAlarmTimeOffsetX()) }
    var alarmTimeOffsetY by remember { mutableStateOf(prefs.getFsAlarmTimeOffsetY()) }
    var alarmLabelOffsetX by remember { mutableStateOf(prefs.getFsAlarmLabelOffsetX()) }
    var alarmLabelOffsetY by remember { mutableStateOf(prefs.getFsAlarmLabelOffsetY()) }
    var groupOffsetX by remember { mutableStateOf(prefs.getFsGroupOffsetX()) }
    var groupOffsetY by remember { mutableStateOf(prefs.getFsGroupOffsetY()) }
    var countdownOffsetX by remember { mutableStateOf(prefs.getFsCountdownOffsetX()) }
    var countdownOffsetY by remember { mutableStateOf(prefs.getFsCountdownOffsetY()) }

    // 对齐方式（0=左对齐，1=居中，2=右对齐）- 从Preferences读取
    var dateAlignment by remember { mutableStateOf(prefs.getFsDateAlignment()) }
    var weekAlignment by remember { mutableStateOf(prefs.getFsWeekAlignment()) }
    var timeAlignment by remember { mutableStateOf(prefs.getFsTimeAlignment()) }
    var alarmTimeAlignment by remember { mutableStateOf(prefs.getFsAlarmTimeAlignment()) }
    var alarmLabelAlignment by remember { mutableStateOf(prefs.getFsAlarmLabelAlignment()) }
    var groupAlignment by remember { mutableStateOf(prefs.getFsGroupAlignment()) }
    var countdownAlignment by remember { mutableStateOf(prefs.getFsCountdownAlignment()) }

    // 为每个元素单独设置字体大小和颜色 - 从Preferences读取
    // 标题栏
    var dateFontSize by remember { mutableStateOf(prefs.getFsDateFontSize().sp) }
    var dateColor by remember { mutableStateOf(Color(prefs.getFsDateColor())) }
    var weekFontSize by remember { mutableStateOf(prefs.getFsWeekFontSize().sp) }
    var weekColor by remember { mutableStateOf(Color(prefs.getFsWeekColor())) }
    var timeFontSize by remember { mutableStateOf(prefs.getFsTimeFontSize().sp) }
    var timeColor by remember { mutableStateOf(Color(prefs.getFsTimeColor())) }

    // 闹钟项
    var alarmTimeFontSize by remember { mutableStateOf(prefs.getFsAlarmTimeFontSize().sp) }
    var alarmTimeColor by remember { mutableStateOf(Color(prefs.getFsAlarmTimeColor())) }
    var alarmLabelFontSize by remember { mutableStateOf(prefs.getFsAlarmLabelFontSize().sp) }
    var alarmLabelColor by remember { mutableStateOf(Color(prefs.getFsAlarmLabelColor())) }
    var groupFontSize by remember { mutableStateOf(prefs.getFsGroupFontSize().sp) }
    var groupColor by remember { mutableStateOf(Color(prefs.getFsGroupColor())) }
    var countdownFontSize by remember { mutableStateOf(prefs.getFsCountdownFontSize().sp) }
    var countdownColor by remember { mutableStateOf(Color(prefs.getFsCountdownColor())) }

    // 筛选启用的闹钟，按时间排序，取前三条
    val enabledAlarms = remember(alarms) {
        alarms.filter { it.isEnabled }
            .sortedBy { it.hour * 60 + it.minute }
            .take(3)
    }

    val groupMap = remember(groups) { groups.associateBy { it.id } }

    val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460)
                    )
                )
            )
            .systemBarsPadding()
            .clickable(enabled = isLocked) { onExit() }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部标题栏：日期 星期 时间
            val cal = Calendar.getInstance().apply { timeInMillis = currentTime }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(currentTime))
            val weekDay = "星期${weekDays[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))

            // 顶部行：日期 星期 时间 + 右上角锁标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 中间：日期 星期 时间（支持对齐）
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 日期（支持对齐）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .offset(x = dateOffsetX.dp, y = dateOffsetY.dp)
                    ) {
                        Text(
                            text = dateStr,
                            color = dateColor,
                            fontSize = dateFontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = when (dateAlignment) {
                                0 -> TextAlign.Left
                                1 -> TextAlign.Center
                                2 -> TextAlign.Right
                                else -> TextAlign.Left
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLocked) {
                                    editingElement = "date"
                                    showEditDialog = true
                                }
                        )
                    }

                    // 星期（支持对齐）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .offset(x = weekOffsetX.dp, y = weekOffsetY.dp)
                    ) {
                        Text(
                            text = weekDay,
                            color = weekColor,
                            fontSize = weekFontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = when (weekAlignment) {
                                0 -> TextAlign.Left
                                1 -> TextAlign.Center
                                2 -> TextAlign.Right
                                else -> TextAlign.Left
                            },
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLocked) {
                                    editingElement = "week"
                                    showEditDialog = true
                                }
                        )
                    }

                    // 时间（支持对齐）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .offset(x = timeOffsetX.dp, y = timeOffsetY.dp)
                    ) {
                        Text(
                            text = timeStr,
                            color = timeColor,
                            fontSize = timeFontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = when (timeAlignment) {
                                0 -> TextAlign.Left
                                1 -> TextAlign.Center
                                2 -> TextAlign.Right
                                else -> TextAlign.Left
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLocked) {
                                    editingElement = "time"
                                    showEditDialog = true
                                }
                        )
                    }
                }

                // 右侧：锁定/解锁按钮（缩小放右上角）
                IconButton(
                    onClick = { isLocked = !isLocked },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(
                        text = if (isLocked) "🔒" else "🔓",
                        fontSize = 18.sp
                    )
                }
            }

            // 中间：最新三条闹钟
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (enabledAlarms.isEmpty()) {
                    Text(
                        text = "暂无启用闹钟",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 24.sp,
                        modifier = Modifier.padding(top = 32.dp)
                    )
                } else {
                    enabledAlarms.forEachIndexed { index, alarm ->
                        val group = groupMap[alarm.groupId]
                        FullScreenAlarmItem(
                            alarm = alarm,
                            groupName = group?.name ?: "未知分组",
                            currentTime = currentTime,
                            isEditable = !isLocked,
                            timeFontSize = alarmTimeFontSize,
                            timeColor = alarmTimeColor,
                            labelFontSize = alarmLabelFontSize,
                            labelColor = alarmLabelColor,
                            groupFontSize = groupFontSize,
                            groupColor = groupColor,
                            countdownFontSize = countdownFontSize,
                            countdownColor = countdownColor,
                            timeOffsetX = alarmTimeOffsetX,
                            timeOffsetY = alarmTimeOffsetY,
                            labelOffsetX = alarmLabelOffsetX,
                            labelOffsetY = alarmLabelOffsetY,
                            groupOffsetX = groupOffsetX,
                            groupOffsetY = groupOffsetY,
                            countdownOffsetX = countdownOffsetX,
                            countdownOffsetY = countdownOffsetY,
                            timeAlignment = alarmTimeAlignment,
                            labelAlignment = alarmLabelAlignment,
                            groupAlignment = groupAlignment,
                            countdownAlignment = countdownAlignment,
                            onEdit = { element ->
                                editingElement = element
                                showEditDialog = true
                            }
                        )
                    }
                }
            }
        }

        // 编辑弹窗（可拖动小卡片，支持滚动）
        if (showEditDialog && !isLocked) {
            val (currentFontSize, currentColor) = when (editingElement) {
                "date" -> dateFontSize.value to dateColor
                "week" -> weekFontSize.value to weekColor
                "time" -> timeFontSize.value to timeColor
                "alarmTime" -> alarmTimeFontSize.value to alarmTimeColor
                "alarmLabel" -> alarmLabelFontSize.value to alarmLabelColor
                "group" -> groupFontSize.value to groupColor
                "countdown" -> countdownFontSize.value to countdownColor
                else -> 32f to Color.White
            }

            Card(
                modifier = Modifier
                    .offset(x = dialogOffsetX.dp, y = dialogOffsetY.dp)
                    .width(220.dp)
                    .height(400.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newX = (dialogOffsetX + dragAmount.x / 4).coerceIn(minOffsetX, maxOffsetX)
                            val newY = (dialogOffsetY + dragAmount.y / 4).coerceIn(minOffsetY, maxOffsetY)
                            dialogOffsetX = newX
                            dialogOffsetY = newY
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2D2D2D).copy(alpha = 0.95f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "编辑 ${when (editingElement) {
                            "date" -> "日期"
                            "week" -> "星期"
                            "time" -> "时间"
                            "alarmTime" -> "闹钟时间"
                            "alarmLabel" -> "闹钟标签"
                            "group" -> "分组名称"
                            "countdown" -> "倒计时"
                            else -> "元素"
                        }}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // 对齐方式选择
                    Text(
                        text = "对齐方式",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    val currentAlignment = when (editingElement) {
                        "date" -> dateAlignment
                        "week" -> weekAlignment
                        "time" -> timeAlignment
                        "alarmTime" -> alarmTimeAlignment
                        "alarmLabel" -> alarmLabelAlignment
                        "group" -> groupAlignment
                        "countdown" -> countdownAlignment
                        else -> 0
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = {
                                when (editingElement) {
                                    "date" -> { dateAlignment = 0; prefs.setFsDateAlignment(0) }
                                    "week" -> { weekAlignment = 0; prefs.setFsWeekAlignment(0) }
                                    "time" -> { timeAlignment = 0; prefs.setFsTimeAlignment(0) }
                                    "alarmTime" -> { alarmTimeAlignment = 0; prefs.setFsAlarmTimeAlignment(0) }
                                    "alarmLabel" -> { alarmLabelAlignment = 0; prefs.setFsAlarmLabelAlignment(0) }
                                    "group" -> { groupAlignment = 0; prefs.setFsGroupAlignment(0) }
                                    "countdown" -> { countdownAlignment = 0; prefs.setFsCountdownAlignment(0) }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentAlignment == 0) MaterialTheme.colorScheme.primary else Color.Gray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("左", fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                when (editingElement) {
                                    "date" -> { dateAlignment = 1; prefs.setFsDateAlignment(1) }
                                    "week" -> { weekAlignment = 1; prefs.setFsWeekAlignment(1) }
                                    "time" -> { timeAlignment = 1; prefs.setFsTimeAlignment(1) }
                                    "alarmTime" -> { alarmTimeAlignment = 1; prefs.setFsAlarmTimeAlignment(1) }
                                    "alarmLabel" -> { alarmLabelAlignment = 1; prefs.setFsAlarmLabelAlignment(1) }
                                    "group" -> { groupAlignment = 1; prefs.setFsGroupAlignment(1) }
                                    "countdown" -> { countdownAlignment = 1; prefs.setFsCountdownAlignment(1) }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentAlignment == 1) MaterialTheme.colorScheme.primary else Color.Gray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("中", fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                when (editingElement) {
                                    "date" -> { dateAlignment = 2; prefs.setFsDateAlignment(2) }
                                    "week" -> { weekAlignment = 2; prefs.setFsWeekAlignment(2) }
                                    "time" -> { timeAlignment = 2; prefs.setFsTimeAlignment(2) }
                                    "alarmTime" -> { alarmTimeAlignment = 2; prefs.setFsAlarmTimeAlignment(2) }
                                    "alarmLabel" -> { alarmLabelAlignment = 2; prefs.setFsAlarmLabelAlignment(2) }
                                    "group" -> { groupAlignment = 2; prefs.setFsGroupAlignment(2) }
                                    "countdown" -> { countdownAlignment = 2; prefs.setFsCountdownAlignment(2) }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentAlignment == 2) MaterialTheme.colorScheme.primary else Color.Gray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("右", fontSize = 10.sp)
                        }
                    }

                    // 字体大小调整
                    Text(
                        text = "字号: ${currentFontSize.toInt()}sp",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    Slider(
                        value = currentFontSize,
                        onValueChange = { newSize ->
                            when (editingElement) {
                                "date" -> { dateFontSize = newSize.sp; prefs.setFsDateFontSize(newSize) }
                                "week" -> { weekFontSize = newSize.sp; prefs.setFsWeekFontSize(newSize) }
                                "time" -> { timeFontSize = newSize.sp; prefs.setFsTimeFontSize(newSize) }
                                "alarmTime" -> { alarmTimeFontSize = newSize.sp; prefs.setFsAlarmTimeFontSize(newSize) }
                                "alarmLabel" -> { alarmLabelFontSize = newSize.sp; prefs.setFsAlarmLabelFontSize(newSize) }
                                "group" -> { groupFontSize = newSize.sp; prefs.setFsGroupFontSize(newSize) }
                                "countdown" -> { countdownFontSize = newSize.sp; prefs.setFsCountdownFontSize(newSize) }
                            }
                        },
                        valueRange = 10f..100f,
                        modifier = Modifier.height(24.dp)
                    )

                    // 颜色选择（HSV滑动条）
                    Text(
                        text = "颜色",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    ColorBarPicker(
                        currentColor = currentColor,
                        onColorSelected = { color ->
                            when (editingElement) {
                                "date" -> { dateColor = color; prefs.setFsDateColor(color.toArgb()) }
                                "week" -> { weekColor = color; prefs.setFsWeekColor(color.toArgb()) }
                                "time" -> { timeColor = color; prefs.setFsTimeColor(color.toArgb()) }
                                "alarmTime" -> { alarmTimeColor = color; prefs.setFsAlarmTimeColor(color.toArgb()) }
                                "alarmLabel" -> { alarmLabelColor = color; prefs.setFsAlarmLabelColor(color.toArgb()) }
                                "group" -> { groupColor = color; prefs.setFsGroupColor(color.toArgb()) }
                                "countdown" -> { countdownColor = color; prefs.setFsCountdownColor(color.toArgb()) }
                            }
                        }
                    )

                    // 位置调整（X/Y偏移）
                    Text(
                        text = "位置",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    val actualOffsetX = when (editingElement) {
                        "date" -> dateOffsetX
                        "week" -> weekOffsetX
                        "time" -> timeOffsetX
                        "alarmTime" -> alarmTimeOffsetX
                        "alarmLabel" -> alarmLabelOffsetX
                        "group" -> groupOffsetX
                        "countdown" -> countdownOffsetX
                        else -> 0f
                    }
                    val actualOffsetY = when (editingElement) {
                        "date" -> dateOffsetY
                        "week" -> weekOffsetY
                        "time" -> timeOffsetY
                        "alarmTime" -> alarmTimeOffsetY
                        "alarmLabel" -> alarmLabelOffsetY
                        "group" -> groupOffsetY
                        "countdown" -> countdownOffsetY
                        else -> 0f
                    }
                    Text(
                        text = "X: ${actualOffsetX.toInt()}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                    Slider(
                        value = actualOffsetX,
                        onValueChange = { newX ->
                            when (editingElement) {
                                "date" -> { dateOffsetX = newX; prefs.setFsDateOffsetX(newX) }
                                "week" -> { weekOffsetX = newX; prefs.setFsWeekOffsetX(newX) }
                                "time" -> { timeOffsetX = newX; prefs.setFsTimeOffsetX(newX) }
                                "alarmTime" -> { alarmTimeOffsetX = newX; prefs.setFsAlarmTimeOffsetX(newX) }
                                "alarmLabel" -> { alarmLabelOffsetX = newX; prefs.setFsAlarmLabelOffsetX(newX) }
                                "group" -> { groupOffsetX = newX; prefs.setFsGroupOffsetX(newX) }
                                "countdown" -> { countdownOffsetX = newX; prefs.setFsCountdownOffsetX(newX) }
                            }
                        },
                        valueRange = minOffsetX..maxOffsetX,
                        modifier = Modifier.height(24.dp)
                    )
                    Text(
                        text = "Y: ${actualOffsetY.toInt()}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                    Slider(
                        value = actualOffsetY,
                        onValueChange = { newY ->
                            when (editingElement) {
                                "date" -> { dateOffsetY = newY; prefs.setFsDateOffsetY(newY) }
                                "week" -> { weekOffsetY = newY; prefs.setFsWeekOffsetY(newY) }
                                "time" -> { timeOffsetY = newY; prefs.setFsTimeOffsetY(newY) }
                                "alarmTime" -> { alarmTimeOffsetY = newY; prefs.setFsAlarmTimeOffsetY(newY) }
                                "alarmLabel" -> { alarmLabelOffsetY = newY; prefs.setFsAlarmLabelOffsetY(newY) }
                                "group" -> { groupOffsetY = newY; prefs.setFsGroupOffsetY(newY) }
                                "countdown" -> { countdownOffsetY = newY; prefs.setFsCountdownOffsetY(newY) }
                            }
                        },
                        valueRange = minOffsetY..maxOffsetY,
                        modifier = Modifier.height(24.dp)
                    )

                    // 完成按钮
                    Button(
                        onClick = { showEditDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("完成", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenAlarmItem(
    alarm: Alarm,
    groupName: String,
    currentTime: Long,
    isEditable: Boolean = false,
    timeFontSize: androidx.compose.ui.unit.TextUnit = 56.sp,
    timeColor: Color = Color.White,
    labelFontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    labelColor: Color = Color.White,
    groupFontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    groupColor: Color = Color(0xFF64B5F6),
    countdownFontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    countdownColor: Color = Color(0xFFFFD700),
    timeOffsetX: Float = 0f,
    timeOffsetY: Float = 0f,
    labelOffsetX: Float = 0f,
    labelOffsetY: Float = 0f,
    groupOffsetX: Float = 0f,
    groupOffsetY: Float = 0f,
    countdownOffsetX: Float = 0f,
    countdownOffsetY: Float = 0f,
    timeAlignment: Int = 0,
    labelAlignment: Int = 0,
    groupAlignment: Int = 0,
    countdownAlignment: Int = 0,
    onEdit: (String) -> Unit
) {
    // 计算倒计时
    val countdown = remember(alarm, currentTime) {
        calculateCountdown(alarm, currentTime)
    }

        // 布局：左侧时间（占用两行高度），右侧两行文字
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // 左侧：闹钟时间（支持对齐）
        Box(
            modifier = Modifier
                .width(200.dp)
                .offset(x = timeOffsetX.dp, y = timeOffsetY.dp)
        ) {
            Text(
                text = String.format("%02d:%02d", alarm.hour, alarm.minute),
                color = timeColor,
                fontSize = timeFontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = when (timeAlignment) {
                    0 -> TextAlign.Left
                    1 -> TextAlign.Center
                    2 -> TextAlign.Right
                    else -> TextAlign.Left
                },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isEditable) { onEdit("alarmTime") }
            )
        }

        // 右侧：两行文字
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            // 第一行：组名 + 倒计时
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 组名（支持对齐）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .offset(x = groupOffsetX.dp, y = groupOffsetY.dp)
                ) {
                    Text(
                        text = groupName,
                        color = groupColor,
                        fontSize = groupFontSize,
                        fontFamily = FontFamily.Monospace,
                        textAlign = when (groupAlignment) {
                            0 -> TextAlign.Left
                            1 -> TextAlign.Center
                            2 -> TextAlign.Right
                            else -> TextAlign.Left
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isEditable) { onEdit("group") }
                    )
                }
                // 倒计时（支持对齐）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .offset(x = countdownOffsetX.dp, y = countdownOffsetY.dp)
                ) {
                    Text(
                        text = countdown,
                        color = countdownColor,
                        fontSize = countdownFontSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = when (countdownAlignment) {
                            0 -> TextAlign.Left
                            1 -> TextAlign.Center
                            2 -> TextAlign.Right
                            else -> TextAlign.Left
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isEditable) { onEdit("countdown") }
                    )
                }
            }
            // 第二行：任务名（支持对齐）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = labelOffsetX.dp, y = labelOffsetY.dp)
            ) {
                Text(
                    text = alarm.label.ifBlank { "闹钟" },
                    color = labelColor,
                    fontSize = labelFontSize,
                    fontFamily = FontFamily.Monospace,
                    textAlign = when (labelAlignment) {
                        0 -> TextAlign.Left
                        1 -> TextAlign.Center
                        2 -> TextAlign.Right
                        else -> TextAlign.Left
                    },
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isEditable) { onEdit("alarmLabel") }
                )
            }
        }
    }
}

/**
 * 颜色选择器（HSV 色相滑动条 + 黑白快捷色块）
 */
@Composable
fun ColorBarPicker(
    currentColor: Color,
    onColorSelected: (Color) -> Unit
) {
    val androidColor = currentColor.toArgb()
    val hsv = remember(androidColor) {
        val arr = FloatArray(3)
        if (androidColor != 0) {
            AndroidColor.colorToHSV(androidColor, arr)
        } else {
            arr[0] = 0f; arr[1] = 1f; arr[2] = 1f
        }
        arr
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // 快捷色块：黑、白
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color.Black, CircleShape)
                .clickable { onColorSelected(Color.Black) }
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color.White, CircleShape)
                .clickable { onColorSelected(Color.White) }
        )

        // HSV 色相滑动条
        Slider(
            value = hsv[0],
            onValueChange = { h ->
                val newColor = Color(AndroidColor.HSVToColor(floatArrayOf(h, 1f, 1f)))
                onColorSelected(newColor)
            },
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))),
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            ),
            modifier = Modifier.weight(1f).height(32.dp)
        )
    }
}

/**
 * 计算闹钟距离当前时间的倒计时
 */
fun calculateCountdown(alarm: Alarm, currentTime: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
    val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
    val currentMinute = calendar.get(Calendar.MINUTE)
    val currentSecond = calendar.get(Calendar.SECOND)

    val alarmTimeInMinutes = alarm.hour * 60 + alarm.minute
    val currentTimeInMinutes = currentHour * 60 + currentMinute

    var diffInMinutes = alarmTimeInMinutes - currentTimeInMinutes

    // 如果今天的时间已经过了，假设是明天
    if (diffInMinutes < 0 || (diffInMinutes == 0 && currentSecond > 0)) {
        diffInMinutes += 24 * 60
    }

    // 转换为秒，考虑当前秒数
    val diffInSeconds = if (diffInMinutes == 0) {
        (60 - currentSecond).toLong()
    } else {
        diffInMinutes * 60L - currentSecond
    }

    val days = diffInSeconds / (24 * 3600)
    val hours = (diffInSeconds % (24 * 3600)) / 3600
    val minutes = (diffInSeconds % 3600) / 60
    val seconds = diffInSeconds % 60

    return when {
        days > 0 -> "${days}天${hours}时${minutes}分"
        hours > 0 -> "${hours}时${minutes}分${seconds}秒"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
