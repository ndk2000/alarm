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
import androidx.compose.ui.draw.shadow
import com.example.ui.util.rememberScreenScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 3D 立体循环拨盘选择器（无吸附版）
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
    val rangeSize = range.last - range.first + 1
    val repeatCount = 5
    val items = remember(range, rangeSize) {
        (0 until rangeSize * repeatCount).map { range.first + (it % rangeSize) }
    }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    val initialIndex = remember(rangeSize, value) {
        val middleBlockStart = rangeSize * (repeatCount / 2)
        middleBlockStart + (value - range.first)
    }

    LaunchedEffect(viewportHeightPx) {
        if (viewportHeightPx > 0) {
            listState.scrollToItem(initialIndex)
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
                            onValueChange(item)
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
                    )
                }
            }
        }

        val fadeHeight = scale.pickerFadeHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(colors = listOf(bgColor, bgColor.copy(alpha = 0.0f))))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(colors = listOf(bgColor.copy(alpha = 0.0f), bgColor)))
        )
    }
}