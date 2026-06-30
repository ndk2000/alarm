package com.ccsoft.alarm.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局 UI 同步总线
 * 用于同步状态栏时钟、自由悬浮窗的坐标及所有组件的颜色
 */
object StatusBarState {
    // 状态栏时钟坐标
    private val _xOffset = MutableStateFlow(180)
    val xOffset = _xOffset.asStateFlow()

    private val _yOffset = MutableStateFlow(0)
    val yOffset = _yOffset.asStateFlow()

    // 自由悬浮窗坐标 (仅当使用十字键控制时同步)
    private val _floatingX = MutableStateFlow(100)
    val floatingX = _floatingX.asStateFlow()

    private val _floatingY = MutableStateFlow(100)
    val floatingY = _floatingY.asStateFlow()

    // 字号同步 (SP)
    private val _sbFontSize = MutableStateFlow(14f)
    val sbFontSize = _sbFontSize.asStateFlow()
    private val _floatFontSize = MutableStateFlow(16f)
    val floatFontSize = _floatFontSize.asStateFlow()

    // 颜色同步 (ARGB 整数)
    // 状态栏
    private val _sbTextColor = MutableStateFlow(0xFFFFD700.toInt())
    val sbTextColor = _sbTextColor.asStateFlow()
    private val _sbBgColor = MutableStateFlow(0x00000000.toInt()) // 默认透明
    val sbBgColor = _sbBgColor.asStateFlow()

    // 自由悬浮窗
    private val _floatTextColor = MutableStateFlow(0xFFFFD700.toInt())
    val floatTextColor = _floatTextColor.asStateFlow()
    private val _floatBgColor = MutableStateFlow(0xCC000000.toInt())
    val floatBgColor = _floatBgColor.asStateFlow()

    // 桌面插件
    private val _widgetTextColor = MutableStateFlow(0xFFFFD700.toInt())
    val widgetTextColor = _widgetTextColor.asStateFlow()
    private val _widgetBgColor = MutableStateFlow(0xCC000000.toInt())
    val widgetBgColor = _widgetBgColor.asStateFlow()

    // 桌面插件 - 最近闹钟 (三行独立)
    private val _naWidgetTimeColor = MutableStateFlow(0xFFFFD700.toInt())
    val naWidgetTimeColor = _naWidgetTimeColor.asStateFlow()
    private val _naWidgetCountdownColor = MutableStateFlow(0xFFFFFFFF.toInt())
    val naWidgetCountdownColor = _naWidgetCountdownColor.asStateFlow()
    private val _naWidgetLabelColor = MutableStateFlow(0xCCFFFFFF.toInt())
    val naWidgetLabelColor = _naWidgetLabelColor.asStateFlow()

    private val _naWidgetTimeSize = MutableStateFlow(24f)
    val naWidgetTimeSize = _naWidgetTimeSize.asStateFlow()
    private val _naWidgetCountdownSize = MutableStateFlow(16f)
    val naWidgetCountdownSize = _naWidgetCountdownSize.asStateFlow()
    private val _naWidgetLabelSize = MutableStateFlow(14f)
    val naWidgetLabelSize = _naWidgetLabelSize.asStateFlow()

    // 顶部标题栏秒钟
    private val _topBarClockEnabled = MutableStateFlow(true)
    val topBarClockEnabled = _topBarClockEnabled.asStateFlow()
    private val _topBarClockColor = MutableStateFlow(0xFFFFFFFF.toInt()) // 默认白色
    val topBarClockColor = _topBarClockColor.asStateFlow()
    private val _topBarClockBgColor = MutableStateFlow(0x00000000.toInt()) // 默认透明
    val topBarClockBgColor = _topBarClockBgColor.asStateFlow()

    fun updatePosition(x: Int, y: Int) {
        _xOffset.value = x
        _yOffset.value = y
    }

    fun updateFloatingPosition(x: Int, y: Int) {
        _floatingX.value = x
        _floatingY.value = y
    }

    fun updateColors(
        sbText: Int? = null, sbBg: Int? = null,
        floatText: Int? = null, floatBg: Int? = null,
        widgetText: Int? = null, widgetBg: Int? = null,
        topBarClock: Int? = null, topBarBg: Int? = null,
        naTime: Int? = null, naCountdown: Int? = null, naLabel: Int? = null
    ) {
        sbText?.let { _sbTextColor.value = it }
        sbBg?.let { _sbBgColor.value = it }
        floatText?.let { _floatTextColor.value = it }
        floatBg?.let { _floatBgColor.value = it }
        widgetText?.let { _widgetTextColor.value = it }
        widgetBg?.let { _widgetBgColor.value = it }
        topBarClock?.let { _topBarClockColor.value = it }
        topBarBg?.let { _topBarClockBgColor.value = it }
        naTime?.let { _naWidgetTimeColor.value = it }
        naCountdown?.let { _naWidgetCountdownColor.value = it }
        naLabel?.let { _naWidgetLabelColor.value = it }
    }

    fun setTopBarClockEnabled(enabled: Boolean) {
        _topBarClockEnabled.value = enabled
    }

    fun updateFontSize(
        sb: Float? = null, float: Float? = null,
        naTime: Float? = null, naCountdown: Float? = null, naLabel: Float? = null
    ) {
        sb?.let { _sbFontSize.value = it }
        float?.let { _floatFontSize.value = it }
        naTime?.let { _naWidgetTimeSize.value = it }
        naCountdown?.let { _naWidgetCountdownSize.value = it }
        naLabel?.let { _naWidgetLabelSize.value = it }
    }
}
