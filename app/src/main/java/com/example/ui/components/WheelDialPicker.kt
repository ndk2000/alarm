package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// 含有流畅滚动和边界弹停的 Jetpack Compose 拟真滚轮拨盘器 Component
@Composable
fun WheelDialPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    format: String = "%02d",
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = remember { range.toList() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    // 仅在首次布局完成后，滚动到当前值所在位置
    LaunchedEffect(viewportHeightPx) {
        if (viewportHeightPx == 0) return@LaunchedEffect
        val index = items.indexOf(value)
        if (index != -1) {
            listState.scrollToItem(index + 1)
        }
    }

    // 用户滑动释放后，自动吸附到距离中心最近的项，并动画滚动至居中
    LaunchedEffect(Unit) {
        var wasScrolling = false
        var isSnapping = false // ★ 防止动画结束后的循环触发
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                // ★ 吸附动画进行中，忽略所有滚动状态变化，避免循环
                if (isSnapping) return@collect

                if (!wasScrolling && !scrolling) {
                    // 初始状态未滚动，跳过
                    return@collect
                }
                wasScrolling = scrolling
                if (!scrolling) {
                    // 滚动刚停止，等待一小帧让 layout 稳定
                    kotlinx.coroutines.delay(50)
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return@collect
                    val center = layoutInfo.viewportEndOffset / 2
                    val closest = visibleItems.minByOrNull {
                        kotlin.math.abs((it.offset + it.size / 2) - center)
                    } ?: return@collect
                    val actualIndex = closest.index - 1 // 减去顶部占位
                    val snappedValue = items.getOrNull(actualIndex) ?: return@collect
                    if (snappedValue != value) {
                        onValueChange(snappedValue)
                    }
                    // ★ 标记正在吸附，阻止循环触发
                    isSnapping = true
                    wasScrolling = false
                    // 动画滚动到居中，实现视觉磁吸
                    scope.launch {
                        listState.animateScrollToItem(closest.index)
                        // ★ 动画完成后，解除标记，允许下一次用户滑动
                        isSnapping = false
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .height(130.dp)
            .width(80.dp)
            .onSizeChanged { viewportHeightPx = it.height },
        contentAlignment = Alignment.Center
    ) {
        // 卡放中间高亮聚焦视窗（采用质感高对比度的淡蓝色线条，在暗背景下极富科技感与美感）
        Column(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFFADC6FF).copy(alpha = 0.45f)))
            Box(modifier = Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFFADC6FF).copy(alpha = 0.45f)))
        }
        
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 44.dp), // 此处空留高度极为关键，保证首尾数值在中心完美卡槽对齐
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            // 在顶部增加一个空的占位符，让用户即使快速滑动也能有更好的边界缓冲感
            item {
                Box(modifier = Modifier.height(42.dp).fillMaxWidth())
            }

            itemsIndexed(items) { index, item ->
                val isSelected = item == value
                val scale = if (isSelected) 1.25f else 0.82f
                val alpha = if (isSelected) 1.0f else 0.35f
                val textColor = if (isSelected) Color(0xFFADC6FF) else Color(0xFF8E9099)
                val textWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                // 这里 index + 1 是因为顶部多了一个占位 item
                                listState.animateScrollToItem(index + 1)
                                onValueChange(item)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(format, item),
                        fontSize = 18.sp,
                        fontWeight = textWeight,
                        color = textColor,
                        modifier = Modifier
                            .scale(scale)
                            .alpha(alpha)
                    )
                }
            }
        }
    }
}
