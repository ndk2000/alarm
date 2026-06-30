package com.ccsoft.alarm.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.ui.theme.Theme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class AlarmActiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // 关键：在 super.onCreate 之前设置这些 flag 以确保冷启动时能穿透锁屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        super.onCreate(savedInstanceState)

        // 申请 DismissKeyguard
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

        val label = intent.getStringExtra("ALARM_LABEL") ?: "早晨时光"
        val isTimer = intent.getBooleanExtra("IS_TIMER", false)
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)
        val ringtone = intent.getStringExtra("ALARM_RINGTONE")
        val vibrate = intent.getBooleanExtra("ALARM_VIBRATE", true)
        val ringtoneDurationSecs = intent.getIntExtra("ALARM_DURATION_SECS", 0)

        // ★ 第三重保障：Activity 启动时也启动 Service 播放铃声
        // 防止 AlarmReceiver 里的 startForegroundService 失败导致不响
        val serviceIntent = Intent(this, AlarmService::class.java).apply {
            action = "START_RINGING"
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_LABEL", label)
            putExtra("ALARM_RINGTONE", ringtone)
            putExtra("ALARM_VIBRATE", vibrate)
            putExtra("ALARM_DURATION_SECS", ringtoneDurationSecs)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("AlarmActiveActivity", "onCreate: 已启动 AlarmService 播放铃声")
        } catch (e: Exception) {
            Log.e("AlarmActiveActivity", "启动 AlarmService 失败: ${e.message}")
        }

        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmRingingScreen(
                        label = label,
                        isTimer = isTimer,
                        onDismiss = {
                            dismissAlarm()
                        }
                    )
                }
            }
        }
    }

    /** 音量键关闭闹钟 */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            dismissAlarm()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dismissAlarm() {
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = "STOP_RINGING"
            putExtra("ALARM_ID", alarmId)
        }
        startService(stopIntent)
        finish()
    }
}

@Composable
fun AlarmRingingScreen(
    label: String,
    isTimer: Boolean = false,
    onDismiss: () -> Unit
) {
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    // 计时器用绿色系，闹钟用紫色系
    val accentColor = if (isTimer) Color(0xFF4CAF50) else Color(0xFFADC6FF)
    val pulseColor = if (isTimer) Color(0x224CAF50) else Color(0x11ADC6FF)
    val pulseColor2 = if (isTimer) Color(0x334CAF50) else Color(0x22ADC6FF)
    val labelColor = if (isTimer) Color(0xFF81C784) else Color(0xFFF095FF)

    // Dynamic clock updater
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(1000)
        }
    }

    // Glowing animation for background pulses
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // 计时器用暗色渐变，闹钟用紫色渐变
    val bgColors = if (isTimer) listOf(Color(0xFF1A1A1A), Color(0xFF0D2B1A)) else listOf(Color(0xFF0C101B), Color(0xFF1E112A))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(colors = bgColors)
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Large time display and label
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .scale(pulseScale)
                    .background(pulseColor, CircleShape)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(pulseColor2, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isTimer) Icons.Default.Timer else Icons.Default.Alarm,
                        contentDescription = if (isTimer) "Timer Finished" else "Ringing Alarm",
                        tint = accentColor,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = currentTime,
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E2E2),
                letterSpacing = 2.sp
            )

            Text(
                text = currentDate,
                fontSize = 18.sp,
                color = Color(0xFFB0B0B0),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = label,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor
            )
        }

        // Accidental tap protection - Slide to dismiss
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 50.dp)
        ) {
            // 大按钮点击关闭（备选，滑动不灵时用）
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = Color(0xFF0D0D1A)
                )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isTimer) "关闭计时器" else "关闭闹钟", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (isTimer) "或滑动右侧滑块关闭计时器" else "或滑动右侧滑块关闭",
                fontSize = 12.sp,
                color = Color(0xFF909090),
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            SlideToDismissSlider(
                onSuccess = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "按音量键也可关闭",
                fontSize = 11.sp,
                color = Color(0xFF707070),
                fontWeight = FontWeight.Light
            )
        }
    }
}

@Composable
fun SlideToDismissSlider(
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sliderWidthDp = 280.dp
    val sliderWidthPx = with(density) { sliderWidthDp.toPx() }
    val thumbSizeDp = 56.dp
    val thumbSizePx = with(density) { thumbSizeDp.toPx() }
    val maxOffset = sliderWidthPx - thumbSizePx

    var dragOffset by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "offset"
    )

    Box(
        modifier = modifier
            .width(sliderWidthDp)
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0x55121318))
            .padding(4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track hint text
        Text(
            text = "滑动关闭闹钟",
            color = Color(0xFFB0B0B0),
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.align(Alignment.Center)
        )

        // Draggable glowing thumb representation
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .size(thumbSizeDp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFF5BFFF), Color(0xFFADC6FF)),
                    )
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            if (dragOffset >= maxOffset * 0.9f) {
                                onSuccess()
                            } else {
                                dragOffset = 0f
                            }
                        },
                        onDrag = { _, dragAmount ->
                            dragOffset = (dragOffset + dragAmount.x).coerceIn(0f, maxOffset)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Slide Right",
                tint = Color(0xFF002E69),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
