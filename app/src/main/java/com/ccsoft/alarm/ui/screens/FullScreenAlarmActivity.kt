package com.ccsoft.alarm.ui.screens

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmGroup
import com.ccsoft.alarm.ui.AlarmViewModel

/**
 * 全屏闹钟显示 Activity。
 * 横屏全屏显示最新三条闹钟 + 当前日期时间。
 * 点击屏幕任意位置退出。
 */
class FullScreenAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强制横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContent {
            MaterialTheme {
                val viewModel: AlarmViewModel = viewModel()
                val alarms by viewModel.alarms.collectAsState()
                val groups by viewModel.groups.collectAsState()

                FullScreenAlarmTab(
                    alarms = alarms,
                    groups = groups,
                    onExit = { finish() }
                )
            }
        }
    }
}
