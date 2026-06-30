package com.ccsoft.alarm.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import com.ccsoft.alarm.MainActivity
import com.ccsoft.alarm.R
import java.text.SimpleDateFormat
import java.util.*

class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAllWidgets(context)
    }

    companion object {
        private const val TAG = "TimerWidgetProvider"
        private val tickHandler = Handler(Looper.getMainLooper())
        private var tickRunnable: Runnable? = null
        private var isTickRunning = false
        private var appContext: Context? = null

        /**
         * 自驱动每秒刷新当前系统时间。
         * TextClock 在 RemoteViews 中不稳定，改用 TextView + 每秒 setText。
         */
        private fun startSelfTick(context: Context) {
            appContext = context.applicationContext
            if (isTickRunning) return
            isTickRunning = true
            tickRunnable = object : Runnable {
                override fun run() {
                    appContext?.let { updateTimeOnly(it) }
                    // 对齐到下一整秒
                    val delayMs = 1000L - (System.currentTimeMillis() % 1000)
                    tickHandler.postDelayed(this, delayMs)
                }
            }
            tickHandler.post(tickRunnable!!)
        }

        private fun stopSelfTick() {
            isTickRunning = false
            tickRunnable?.let { tickHandler.removeCallbacks(it) }
            tickRunnable = null
        }

        /** 只更新时间文字，不重建 RemoteViews（避免闪烁和字号重算开销） */
        private fun updateTimeOnly(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) {
                stopSelfTick()
                return
            }

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeStr = sdf.format(Date())

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.layout_timer_widget)
                views.setTextViewText(R.id.widget_text_clock, timeStr)
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) {
                stopSelfTick()
                return
            }

            // 启动自驱动 tick
            startSelfTick(context)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = createRemoteViews(context, appWidgetManager, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createRemoteViews(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.layout_timer_widget)
            
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            
            Log.d(TAG, "Updating widget $appWidgetId: size=${minWidth}dp x ${minHeight}dp")

            // 智能缩放逻辑：
            // 1. 基于高度的字号（60% 高度，预留上下边距）
            val sizeByHeight = minHeight * 0.6f
            // 2. 基于宽度的字号（"00:00:00" 8个字符，等宽字体下考虑冒号较窄，取 5.2 倍率）
            val sizeByWidth = (minWidth * 0.85f) / 5.2f 
            
            val fontSize = minOf(sizeByHeight, sizeByWidth).coerceIn(12f, 200f)
            Log.d(TAG, "Calculated fontSize: $fontSize dp for widget $appWidgetId")
            
            // 使用 COMPLEX_UNIT_DIP 而非 SP，确保字号严格跟随插件框大小，不受系统字体缩放影响导致溢出
            views.setTextViewTextSize(R.id.widget_text_clock, android.util.TypedValue.COMPLEX_UNIT_DIP, fontSize)
            
            // 填入当前系统时间
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            views.setTextViewText(R.id.widget_text_clock, sdf.format(Date()))

            // 应用用户在"设置-个性化"中选定的颜色
            val widgetTextColor = com.ccsoft.alarm.util.StatusBarState.widgetTextColor.value
            val widgetBgColor = com.ccsoft.alarm.util.StatusBarState.widgetBgColor.value
            
            views.setTextColor(R.id.widget_text_clock, widgetTextColor)
            
            // 注意：直接 setBackgroundColor 会导致 bg_floating_window.xml 的圆角失效
            // 如果背景色是完全透明的，我们隐藏背景图层；否则我们给背景上色
            // 由于 RemoteViews 的局限性，这里暂时使用背景着色（Tint）
            if (widgetBgColor == 0) {
                // 透明模式：隐藏背景图片
                views.setViewVisibility(R.id.widget_bg_image, android.view.View.GONE)
            } else {
                // 有色模式：显示背景图片并对着色
                views.setViewVisibility(R.id.widget_bg_image, android.view.View.VISIBLE)
                // 核心修复：针对 ImageView 使用 setColorFilter 是安全的，不会导致崩溃
                views.setInt(R.id.widget_bg_image, "setColorFilter", widgetBgColor)
            }
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            
            return views
        }

        /** 由服务调用，更新所有插件的颜色（比如预警时变红） */
        fun updateWidgetStyle(context: Context, isWarning: Boolean, isFlashOn: Boolean) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, TimerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            for (appWidgetId in appWidgetIds) {
                // 重新生成 Views（这会重新计算字号和基础颜色）
                val views = createRemoteViews(context, appWidgetManager, appWidgetId)
                
                // 如果处于预警闪烁状态，覆盖文字颜色为红色
                if (isWarning && isFlashOn) {
                    views.setTextColor(R.id.widget_text_clock, 0xFFFF0000.toInt())
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
