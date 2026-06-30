package com.ccsoft.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccsoft.alarm.service.TimerService
import com.ccsoft.alarm.ui.theme.Theme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * 计时器结束关闭界面
 * 计时器响铃时由 TimerService 启动
 */
class TimerDoneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏/熄屏也能显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 用 Compose 渲染关闭界面
        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TimerDoneScreen(
                        onDismiss = { dismissTimer() }
                    )
                }
            }
        }
    }

    private fun dismissTimer() {
        // 停止 TimerService
        val intent = Intent(this, TimerService::class.java)
        stopService(intent)
        Log.i("TimerDoneActivity", "用户关闭计时器")
        
        // 跳转到主界面并打开计时 Tab（Tab 索引 3 = 计时 Tab）
        val mainIntent = Intent(this, com.ccsoft.alarm.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TARGET_TAB", 3) // 计时 Tab 的索引是 3
        }
        startActivity(mainIntent)
        finish()
    }
}

@Composable
fun TimerDoneScreen(onDismiss: () -> Unit) {
    var currentTime by remember { mutableStateOf("") }
    
    // 更新当前时间
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (true) {
            currentTime = timeFormat.format(Date())
            delay(1000)
        }
    }
    
    // 脉冲动画
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
    
    // 绿色系（计时器主题色）
    val accentColor = Color(0xFF4CAF50)
    val pulseColor = Color(0x224CAF50)
    val pulseColor2 = Color(0x334CAF50)
    val bgColors = listOf(Color(0xFF1A1A1A), Color(0xFF0D2B1A))
    
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
        
        // 顶部：图标 + 时间 + 标签
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 脉冲图标
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
                        imageVector = androidx.compose.material.icons.Icons.Default.Timer,
                        contentDescription = "计时结束",
                        tint = accentColor,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 当前时间
            Text(
                text = currentTime,
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E2E2),
                letterSpacing = 2.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 标签
            Text(
                text = "⏰ 计时结束",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF81C784)
            )
        }
        
        // 底部：关闭按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 50.dp)
        ) {
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
                    androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("关闭并继续计时", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                text = "按音量键也可关闭",
                fontSize = 11.sp,
                color = Color(0xFF707070),
                fontWeight = FontWeight.Light
            )
        }
    }
}
