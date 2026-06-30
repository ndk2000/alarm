package com.ccsoft.alarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.ccsoft.alarm.R
import com.ccsoft.alarm.util.StatusBarState
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class FloatingTimerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showFloatingWindow()
    }

    private fun showFloatingWindow() {
        // 防止重复添加
        if (floatingView != null) return
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_timer, null)
        val timerText = floatingView?.findViewById<TextView>(R.id.floating_timer_text)
        
        // 确保清除背景，完全由动态颜色控制
        floatingView?.setBackground(null)
        
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 100

        windowManager.addView(floatingView, layoutParams)

        // 监听位置更新 (十字键控制)
        serviceScope.launch {
            StatusBarState.floatingX.collect { x ->
                layoutParams.x = x
                if (floatingView?.parent != null) windowManager.updateViewLayout(floatingView, layoutParams)
            }
        }
        serviceScope.launch {
            StatusBarState.floatingY.collect { y ->
                layoutParams.y = y
                if (floatingView?.parent != null) windowManager.updateViewLayout(floatingView, layoutParams)
            }
        }

        // 监听颜色更新
        serviceScope.launch {
            StatusBarState.floatTextColor.collect { timerText?.setTextColor(it) }
        }
        serviceScope.launch {
            StatusBarState.floatBgColor.collect { floatingView?.setBackgroundColor(it) }
        }
        // 监听字号更新
        serviceScope.launch {
            StatusBarState.floatFontSize.collect { timerText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, it) }
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        clockRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                timerText?.text = timeFormat.format(Date(now))
                
                // 核心对齐：精准计算下一秒触发时机
                val delayMs = 1000L - (now % 1000)
                serviceHandler.postDelayed(this, delayMs)
            }
        }
        serviceHandler.post(clockRunnable!!)

        // 拖动逻辑
        val contentLayout = floatingView?.findViewById<View>(R.id.content_layout)
        contentLayout?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private var savedWidth: Int = -1
            private var savedHeight: Int = -1
            private var savedTextSize: Float = -1f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        savedWidth = floatingView?.width ?: 240
                        savedHeight = floatingView?.height ?: 100
                        savedTextSize = timerText?.textSize ?: 40f
                        
                        layoutParams.width = 300
                        layoutParams.height = 120
                        timerText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 120 * 0.8f)
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        layoutParams.width = savedWidth
                        layoutParams.height = savedHeight
                        timerText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, savedTextSize)
                        if (layoutParams.y < 30) layoutParams.y = 30
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        // 同步最后位置给十字键状态
                        StatusBarState.updateFloatingPosition(layoutParams.x, layoutParams.y)
                        return true
                    }
                }
                return false
            }
        })

        // 缩放逻辑
        floatingView?.findViewById<View>(R.id.btn_resize_handle)?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                val newWidth = (event.rawX - layoutParams.x).toInt().coerceAtLeast(40)
                val newHeight = (event.rawY - layoutParams.y).toInt().coerceAtLeast(20)
                layoutParams.width = newWidth
                layoutParams.height = newHeight
                val newSize = minOf(newHeight * 0.9f, newWidth * 0.22f)
                timerText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, newSize)
                windowManager.updateViewLayout(floatingView, layoutParams)
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockRunnable?.let { serviceHandler.removeCallbacks(it) }
        serviceScope.cancel()
        if (floatingView != null) windowManager.removeView(floatingView)
    }
}
