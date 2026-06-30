package com.ccsoft.alarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.ccsoft.alarm.MainActivity
import com.ccsoft.alarm.R
import com.ccsoft.alarm.alarm.AlarmActiveActivity
import com.ccsoft.alarm.util.StatusBarState
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class StatusBarClockService : Service() {

    private lateinit var windowManager: WindowManager
    private var clockView: View? = null
    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isWarning = false
    private var isRinging = false
    private var currentAlarmId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showClock()
        registerReceivers()
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 60
    }

    private fun showClock() {
        clockView = LayoutInflater.from(this).inflate(R.layout.layout_floating_timer, null)
        val timerText = clockView?.findViewById<TextView>(R.id.floating_timer_text)
        clockView?.findViewById<View>(R.id.btn_resize_handle)?.visibility = View.GONE
        
        // 关键修复：移除布局自带的 XML 背景，改为动态控制，并使用 WRAP_CONTENT 保证高度随字体
        clockView?.setBackground(null)
        
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        layoutParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(clockView, layoutParams)

        // 监听位置
        serviceScope.launch {
            StatusBarState.xOffset.collect { x ->
                layoutParams.x = x
                if (clockView?.parent != null) windowManager.updateViewLayout(clockView, layoutParams)
            }
        }
        serviceScope.launch {
            StatusBarState.yOffset.collect { y ->
                layoutParams.y = y
                if (clockView?.parent != null) windowManager.updateViewLayout(clockView, layoutParams)
            }
        }

        // 监听颜色
        serviceScope.launch {
            StatusBarState.sbTextColor.collect { timerText?.setTextColor(it) }
        }
        serviceScope.launch {
            StatusBarState.sbBgColor.collect { clockView?.setBackgroundColor(it) }
        }
        // 监听字号
        serviceScope.launch {
            StatusBarState.sbFontSize.collect { timerText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, it) }
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        clockRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                timerText?.text = timeFormat.format(Date(now))
                
                // 核心对齐：计算距离下一整秒的延迟
                val delayMs = 1000L - (now % 1000)
                serviceHandler.postDelayed(this, delayMs)
            }
        }
        serviceHandler.post(clockRunnable!!)

        clockView?.setOnClickListener {
            val intent = if (isRinging) {
                Intent(this, AlarmActiveActivity::class.java).apply { putExtra("ALARM_ID", currentAlarmId) }
            } else {
                Intent(this, MainActivity::class.java).apply { putExtra("TARGET_TAB", 1) }
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
    }

    private fun registerReceivers() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "com.ccsoft.alarm.UPDATE_FLOATING_STYLE" -> {
                        isWarning = intent.getBooleanExtra("IS_WARNING", false)
                        val flashOn = intent.getBooleanExtra("FLASH_ON", false)
                        if (isWarning) {
                            val color = if (flashOn) 0xFFFF0000.toInt() else StatusBarState.sbTextColor.value
                            clockView?.findViewById<TextView>(R.id.floating_timer_text)?.setTextColor(color)
                        }
                    }
                    "com.ccsoft.alarm.ALARM_STATE_CHANGED" -> {
                        isRinging = intent.getBooleanExtra("IS_RINGING", false)
                        currentAlarmId = intent.getLongExtra("ALARM_ID", -1L)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.ccsoft.alarm.UPDATE_FLOATING_STYLE")
            addAction("com.ccsoft.alarm.ALARM_STATE_CHANGED")
        }
        registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockRunnable?.let { serviceHandler.removeCallbacks(it) }
        serviceScope.cancel()
        if (clockView != null) windowManager.removeView(clockView)
    }
}
