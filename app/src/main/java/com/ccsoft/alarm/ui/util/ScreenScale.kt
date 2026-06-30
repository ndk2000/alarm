package com.ccsoft.alarm.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕自适应工具
 *
 * 根据屏幕宽度/高度动态计算组件的尺寸，
 * 确保不同屏幕大小的手机上显示效果比例一致。
 */
@Stable
class ScreenScaleData(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
) {
    /** 是否平板（宽度 ≥ 600dp） */
    val isTablet: Boolean get() = screenWidthDp >= 600

    /** 是否大屏手机（宽度 ≥ 420dp） */
    val isLargePhone: Boolean get() = screenWidthDp >= 420

    /** 小屏手机（宽度 < 420dp） */
    val isSmallPhone: Boolean get() = screenWidthDp < 420

    // ── 拨盘选择器 ──

    /** 拨盘宽度：屏幕宽度的 17%~20% */
    val pickerWidth: Dp
        get() = (screenWidthDp * when {
            isTablet -> 0.10f
            isLargePhone -> 0.17f
            else -> 0.20f
        }).dp.coerceIn(60.dp, 120.dp)

    /** 拨盘高度：屏幕高度的 20%~24% */
    val pickerHeight: Dp
        get() = (screenHeightDp * when {
            isTablet -> 0.18f
            isLargePhone -> 0.22f
            else -> 0.24f
        }).dp.coerceIn(140.dp, 260.dp)

    /** 拨盘选项行高：拨盘高度的 20% */
    val pickerItemHeight: Dp
        get() = (pickerHeight.value * 0.20f).dp

    /** 拨盘字体大小：根据宽度缩放，在 18sp~26sp 之间 */
    val pickerFontSize: Int
        get() = (screenWidthDp * when {
            isTablet -> 0.038f
            isLargePhone -> 0.050f
            else -> 0.055f
        }).toInt().coerceIn(18, 26)

    /** 拨盘渐变遮罩高度：拨盘高度的 28% */
    val pickerFadeHeight: Dp
        get() = (pickerHeight.value * 0.28f).dp

    /** 拨盘高亮条高度：拨盘高度的 22% */
    val pickerHighlightHeight: Dp
        get() = (pickerHeight.value * 0.22f).dp

    /** 拨盘列内边距(上下预留空间) */
    val pickerContentPadding: Dp
        get() = (pickerHeight.value * 0.38f).dp

    // ── 对话框 ──

    /** 对话框内容最大高度：屏幕高度的 75%，但不超过 700dp */
    val dialogContentMaxHeight: Dp
        get() = (screenHeightDp * 0.75f).dp.coerceAtMost(700.dp)

    /** 对话框宽度比例 */
    val dialogWidthFraction: Float
        get() = when {
            isTablet -> 0.70f
            else -> 0.92f
        }

    // ── 顶部栏 ──

    /** 顶部日期字号 */
    val topBarDateFontSize: Int = if (isTablet) 22 else if (isLargePhone) 18 else 16

    /** 顶部时钟字号 */
    val topBarClockFontSize: Int = if (isTablet) 18 else if (isLargePhone) 15 else 14

    // ── 通用间距 ──

    /** 列表水平边距 */
    val listHorizontalPadding: Dp = if (isTablet) 24.dp else 16.dp
}

/**
 * 记住当前屏幕尺寸数据，供所有组件按比例自适应。
 *
 * 用法：
 * ```kotlin
 * val scale = rememberScreenScale()
 * Text(fontSize = scale.pickerFontSize.sp)
 * ```
 */
@Composable
fun rememberScreenScale(): ScreenScaleData {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp) {
        ScreenScaleData(
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
        )
    }
}
