package com.example.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.filled.ChevronRight
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
import com.example.ui.theme.Theme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class AlarmActiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Wake screen and show on top of keyguard/lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

        val label = intent.getStringExtra("ALARM_LABEL") ?: "早晨时光"

        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmRingingScreen(
                        label = label,
                        onDismiss = {
                            dismissAlarm()
                        }
                    )
                }
            }
        }
    }

    private fun dismissAlarm() {
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = "STOP_RINGING"
        }
        startService(stopIntent)
        finish()
    }
}

@Composable
fun AlarmRingingScreen(
    label: String,
    onDismiss: () -> Unit
) {
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0C101B),
                        Color(0xFF1E112A)
                    )
                )
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
                    .background(Color(0x11ADC6FF), CircleShape)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(Color(0x22ADC6FF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "Ringing Alarm",
                        tint = Color(0xFFADC6FF),
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
                color = Color(0xFFF095FF)
            )
        }

        // Accidental tap protection - Slide to dismiss
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 50.dp)
        ) {
            Text(
                text = ">>> Slide right to stop >>>",
                fontSize = 13.sp,
                color = Color(0xFF909090),
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SlideToDismissSlider(
                onSuccess = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 24.dp)
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
