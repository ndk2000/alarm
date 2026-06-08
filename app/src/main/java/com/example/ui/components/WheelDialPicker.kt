package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import com.example.ui.util.rememberScreenScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 3D 立体循环拨盘选择器
 *
 * 模拟 iOS/UIPickerView 风格：
 * - 选中项居中，放大加粗
 * - 上下文字逐渐缩小变淡，形成圆柱弯曲感
 * - 顶部/底部渐变遮罩增强立体深度
 * - 循环滚动无限
 */
@Composable
fun WheelDialPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    format: String = "%02d",
    modifier: Modifier = Modifier
) {
    val scale = rememberScreenScale()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rangeSize = range.last - range.first + 1
    val repeatCount = 11
    val items = remember(range, rangeSize) {
        (0 until rangeSize * repeatCount).map { range.first + (it % rangeSize) }
    }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    // 初始定位到中间段
    val initialIndex = remember(rangeSize, value) {
        val middleBlockStart = rangeSize * (repeatCount / 2)
        middleBlockStart + (value - range.first)
    }
    LaunchedEffect(viewportHeightPx) {
        if (viewportHeightPx > 0) {
            listState.scrollToItem(initialIndex)
        }
    }

    // ── 滚动停止后吸附到最近值 ──
    var lastSnapIndex by remember { mutableIntStateOf(initialIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return@collect
                    val center = layoutInfo.viewportEndOffset / 2
                    val closest = visibleItems.minByOrNull {
                        abs((it.offset + it.size / 2) - center)
                    } ?: return@collect

                    if (closest.index == lastSnapIndex) return@collect
                    lastSnapIndex = closest.index
                    val snappedValue = items.getOrNull(closest.index) ?: return@collect
                    if (snappedValue != value) onValueChange(snappedValue - 1)
                }
            }
    }

    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(scale.pickerHeight)
            .width(scale.pickerWidth)
            .background(bgColor, RoundedCornerShape(12.dp))
            .onSizeChanged { viewportHeightPx = it.height },
        contentAlignment = Alignment.Center
    ) {
        // 中间选中高亮条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(scale.pickerHighlightHeight)
                .background(primaryColor.copy(alpha = 0.20f), RoundedCornerShape(8.dp))
        )

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = scale.pickerContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items) { index, item ->
                val cyclicDist = minOf(
                    abs(item - value),
                    abs(item - value - rangeSize),
                    abs(item - value + rangeSize)
                )
                val normalizedPos = cyclicDist.toFloat() / (rangeSize.coerceAtMost(12)).toFloat()
                val isSelected = item == value

                val visualScale = when {
                    isSelected -> 1.30f
                    normalizedPos <= 0.2f -> 1.0f
                    normalizedPos <= 0.5f -> 0.80f
                    else -> (0.80f - 0.25f * ((normalizedPos - 0.5f) / 0.5f)).coerceAtMost(0.80f)
                }
                val alphaVal = when {
                    isSelected -> 1.0f
                    normalizedPos <= 0.2f -> 0.75f
                    normalizedPos <= 0.5f -> 0.50f
                    else -> (0.50f - 0.30f * ((normalizedPos - 0.5f) / 0.5f)).coerceAtLeast(0.10f)
                }
                // 投影：选中项 8dp，近处 4dp，最远无投影 → 制造立体景深感
                val shadowElevation = when {
                    isSelected -> 8.dp
                    normalizedPos <= 0.2f -> 4.dp
                    normalizedPos <= 0.5f -> 2.dp
                    else -> 0.dp
                }
                val textColor = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                val textWeight = if (isSelected) FontWeight.Black else FontWeight.Medium

                Box(
                    modifier = Modifier
                        .height(scale.pickerItemHeight)
                        .fillMaxWidth()
                        .shadow(shadowElevation, RoundedCornerShape(4.dp))
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(index)
                                onValueChange(item)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(format, item),
                        fontSize = scale.pickerFontSize.sp,
                        fontWeight = textWeight,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .scale(visualScale)
                            .alpha(alphaVal)
                    )
                }
            }
        }

        // ── 顶部/底部渐变遮罩：从背景色到透明，模拟圆柱弯曲 ──
        val fadeHeight = scale.pickerFadeHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(bgColor, bgColor.copy(alpha = 0.0f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(bgColor.copy(alpha = 0.0f), bgColor)
                    )
                )
        )
    }
}
