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
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmDatabase
import com.ccsoft.alarm.alarm.AlarmScheduler
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class NextAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAllWidgets(context)
    }

    companion object {
        private const val TAG = "NextAlarmWidget"
        private val tickHandler = Handler(Looper.getMainLooper())
        private var tickRunnable: Runnable? = null
        private var isTickRunning = false
        private var appContext: Context? = null

        /**
         * 自驱动每秒倒计时刷新。
         * 不再依赖 AlarmService 的 monitorRunnable，widget 自身维护定时刷新。
         */
        private fun startSelfTick(context: Context) {
            appContext = context.applicationContext
            if (isTickRunning) return
            isTickRunning = true
            tickRunnable = object : Runnable {
                override fun run() {
                    appContext?.let { updateAllWidgets(it) }
                    tickHandler.postDelayed(this, 1000L)
                }
            }
            tickHandler.post(tickRunnable!!)
        }

        private fun stopSelfTick() {
            isTickRunning = false
            tickRunnable?.let { tickHandler.removeCallbacks(it) }
            tickRunnable = null
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, NextAlarmWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) {
                stopSelfTick()
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                val alarms = db.alarmDao().getAllAlarms()
                val groups = db.alarmDao().getAllGroups()
                val enabledGroupIds = groups.filter { it.isEnabled }.map { it.id }.toSet()
                
                val now = System.currentTimeMillis()
                val nextAlarm = alarms.filter { it.isEnabled && it.groupId in enabledGroupIds }
                    .map { it to AlarmScheduler.calculateNextAlarmTime(it) }
                    .filter { it.second > now }
                    .minByOrNull { it.second }

                // 如果有下一个闹钟且倒计时 > 0，启动自驱动 tick；否则停止
                val remainingSecs = nextAlarm?.let { (it.second - now + 500) / 1000 } ?: -1
                withContext(Dispatchers.Main) {
                    if (remainingSecs > 0) {
                        startSelfTick(context)
                    } else {
                        stopSelfTick()
                    }
                    for (appWidgetId in appWidgetIds) {
                        updateWidget(context, appWidgetManager, appWidgetId, nextAlarm)
                    }
                }
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, nextAlarm: Pair<Alarm, Long>?) {
            val views = RemoteViews(context.packageName, R.layout.layout_next_alarm_widget)
            
            // 获取样式设置 (实时同步)
            val timeColor = com.ccsoft.alarm.util.StatusBarState.naWidgetTimeColor.value
            val countdownColor = com.ccsoft.alarm.util.StatusBarState.naWidgetCountdownColor.value
            val labelColor = com.ccsoft.alarm.util.StatusBarState.naWidgetLabelColor.value
            val timeSize = com.ccsoft.alarm.util.StatusBarState.naWidgetTimeSize.value
            val countdownSize = com.ccsoft.alarm.util.StatusBarState.naWidgetCountdownSize.value
            val labelSize = com.ccsoft.alarm.util.StatusBarState.naWidgetLabelSize.value

            if (nextAlarm != null) {
                val (alarm, time) = nextAlarm
                val now = System.currentTimeMillis()
                
                // 1. 第一行：响铃时间
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                views.setTextViewText(R.id.widget_alarm_time, sdf.format(Date(time)))
                views.setTextColor(R.id.widget_alarm_time, timeColor)
                views.setTextViewTextSize(R.id.widget_alarm_time, android.util.TypedValue.COMPLEX_UNIT_DIP, timeSize)

                // 2. 第二行：倒计时 (还原为手动更新 TextView，彻底解决负号“-”显示问题)
                val remainingSecs = (time - now + 500) / 1000
                if (remainingSecs > 0) {
                    val h = remainingSecs / 3600
                    val m = (remainingSecs % 3600) / 60
                    val s = remainingSecs % 60
                    val countdownStr = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
                    views.setTextViewText(R.id.widget_alarm_countdown, countdownStr)
                    views.setViewVisibility(R.id.widget_alarm_countdown, android.view.View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_alarm_countdown, android.view.View.GONE)
                }
                views.setTextColor(R.id.widget_alarm_countdown, countdownColor)
                views.setTextViewTextSize(R.id.widget_alarm_countdown, android.util.TypedValue.COMPLEX_UNIT_DIP, countdownSize)

                // 3. 第三行：标签
                views.setTextViewText(R.id.widget_alarm_label, alarm.label.ifBlank { "闹钟" })
                views.setTextColor(R.id.widget_alarm_label, labelColor)
                views.setTextViewTextSize(R.id.widget_alarm_label, android.util.TypedValue.COMPLEX_UNIT_DIP, labelSize)
                
                // 自动适应插件宽高（基于用户设定的比例进行限制，防止溢出）
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                
                // 如果高度太小，隐藏部分内容以保命
                if (minHeight < 80) {
                    views.setViewVisibility(R.id.widget_alarm_countdown, android.view.View.GONE)
                }
                if (minHeight < 50) {
                    views.setViewVisibility(R.id.widget_alarm_label, android.view.View.GONE)
                }
            } else {
                views.setTextViewText(R.id.widget_alarm_time, "--:--")
                views.setViewVisibility(R.id.widget_alarm_countdown, android.view.View.GONE)
                views.setTextViewText(R.id.widget_alarm_label, "无活跃闹钟")
                
                views.setTextColor(R.id.widget_alarm_time, timeColor)
                views.setTextColor(R.id.widget_alarm_label, labelColor)
                views.setTextViewTextSize(R.id.widget_alarm_time, android.util.TypedValue.COMPLEX_UNIT_DIP, timeSize)
                views.setTextViewTextSize(R.id.widget_alarm_label, android.util.TypedValue.COMPLEX_UNIT_DIP, labelSize)
            }

            // 背景控制
            val widgetBgColor = com.ccsoft.alarm.util.StatusBarState.widgetBgColor.value
            if (widgetBgColor == 0) {
                views.setViewVisibility(R.id.widget_bg_image, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_bg_image, android.view.View.VISIBLE)
                // 核心修复：针对 ImageView 使用 setColorFilter 是安全的，不会导致载入错误
                views.setInt(R.id.widget_bg_image, "setColorFilter", widgetBgColor)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
