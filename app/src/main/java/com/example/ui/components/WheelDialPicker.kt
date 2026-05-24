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

    // 等布局完成后（viewportHeightPx > 0）再居中，避免初始 layoutInfo 未就绪导致偏移错位
    LaunchedEffect(value, viewportHeightPx) {
        if (viewportHeightPx == 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        val index = items.indexOf(value)
        if (index != -1) {
            // contentPadding(vertical=44dp) 已确保 items 自然居中，无需额外 offset
            listState.scrollToItem(index + 1)
        }
    }

    // 仅在用户手动滑动/惯性滑动释放后，自动磁吸到距离中心聚焦槽最近的项
    LaunchedEffect(Unit) {
        var skipped = false
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!skipped) { skipped = true; return@collect }
                if (!scrolling) {
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isNotEmpty()) {
                        val center = layoutInfo.viewportEndOffset / 2
                        val closest = visibleItems.minByOrNull {
                            kotlin.math.abs((it.offset + it.size / 2) - center)
                        }
                        closest?.let {
                            // 由于 index 0 是占位符，需减 1 获取 items 中的真实索引
                            val actualIndex = it.index - 1
                            val finalVal = items.getOrNull(actualIndex)
                            if (finalVal != null && finalVal != value) {
                                onValueChange(finalVal)
                            }
                        }
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
