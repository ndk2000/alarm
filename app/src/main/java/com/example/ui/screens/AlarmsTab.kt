package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.db.Alarm
import com.example.db.AlarmGroup
import com.example.ui.components.AlarmItem
import kotlinx.coroutines.launch

@Composable
fun AlarmsTab(
    groups: List<AlarmGroup>,
    alarms: List<Alarm>,
    onToggleGroup: (AlarmGroup, Boolean) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDeleteGroup: (AlarmGroup) -> Unit,
    onUpdateGroup: (AlarmGroup) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onDuplicateAlarm: (Alarm) -> Unit,
    onAddAlarmClick: (Long) -> Unit,
    onMoveAlarmToGroup: (Alarm, Long) -> Unit,
    onEditAlarm: (Alarm) -> Unit
) {
    var alarmToDelete by remember { mutableStateOf<Alarm?>(null) }
    var groupToDelete by remember { mutableStateOf<AlarmGroup?>(null) }
    var groupToEdit by remember { mutableStateOf<AlarmGroup?>(null) }
    var draggedAlarm by remember { mutableStateOf<Alarm?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartPoint by remember { mutableStateOf(Offset.Zero) }
    var originalAlarmPositionInParent by remember { mutableStateOf(Offset.Zero) }
    var itemGlobalPosition by remember { mutableStateOf(Offset.Zero) }
    
    val groupBounds = remember { mutableStateMapOf<Long, Rect>() }
    var parentOffset by remember { mutableStateOf(Offset.Zero) }

    val hoveredGroupId = if (draggedAlarm != null) {
        val currentPointerPos = itemGlobalPosition + dragStartPoint + dragOffset
        groupBounds.entries.find { it.value.contains(currentPointerPos) }?.key
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                parentOffset = coordinates.positionInWindow()
            }
    ) {
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AlarmOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.no_groups_text),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        stringResource(R.string.no_groups_hint),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Friendly User Tip
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Tips",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.drag_tip),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        var isExpanded by remember { mutableStateOf(false) }
                        val groupAlarms = alarms.filter { it.groupId == group.id }
                        val isHovered = hoveredGroupId == group.id
                        
                        val cardBorder = if (isHovered) {
                            BorderStroke(2.dp, Color(0xFFF095FF))
                        } else {
                            null
                        }

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isHovered) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }
                            ),
                            border = cardBorder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    groupBounds[group.id] = coordinates.boundsInWindow()
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                // Group Title Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { isExpanded = !isExpanded }) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Expand/Collapse"
                                        )
                                    }
                                    
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { groupToEdit = group }
                                    ) {
                                        Text(
                                            text = group.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = if (group.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = stringResource(R.string.group_alarms_count, groupAlarms.size),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Master Switch next to Group names to turn off/on all alarms immediately!
                                    Switch(
                                        checked = group.isEnabled,
                                        onCheckedChange = { onToggleGroup(group, it) }
                                    )

                                    IconButton(onClick = { groupToDelete = group }) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            tint = MaterialTheme.colorScheme.error,
                                            contentDescription = "Delete Group"
                                        )
                                    }
                                }

                                // Collapsible Alarms Inside Group
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        if (groupAlarms.isEmpty()) {
                                            Text(
                                                stringResource(R.string.no_alarms_in_group),
                                                color = MaterialTheme.colorScheme.outline,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
                                            )
                                        } else {
                                            groupAlarms.forEach { alarm ->
                                                var myGlobalPosition by remember { mutableStateOf(Offset.Zero) }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .onGloballyPositioned { coordinates ->
                                                            myGlobalPosition = coordinates.positionInWindow()
                                                        }
                                                        .graphicsLayer(alpha = if (draggedAlarm?.id == alarm.id) 0.3f else 1f)
                                                        .padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Draggable Handle Icon
                                                    Icon(
                                                        imageVector = Icons.Default.Menu,
                                                        contentDescription = "拖拽移动分组",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier
                                                            .padding(end = 4.dp)
                                                            .size(24.dp)
                                                            .pointerInput(alarm) {
                                                                detectDragGesturesAfterLongPress(
                                                                    onDragStart = { startOffset ->
                                                                        draggedAlarm = alarm
                                                                        dragStartPoint = startOffset
                                                                        dragOffset = Offset.Zero
                                                                        itemGlobalPosition = myGlobalPosition
                                                                        originalAlarmPositionInParent = myGlobalPosition - parentOffset
                                                                    },
                                                                    onDrag = { change, dragAmount ->
                                                                        change.consume()
                                                                        dragOffset += dragAmount
                                                                    },
                                                                    onDragEnd = {
                                                                        val globalPos = itemGlobalPosition + dragStartPoint + dragOffset
                                                                        val targetGroupId = groupBounds.entries.find { it.value.contains(globalPos) }?.key
                                                                        if (targetGroupId != null && targetGroupId != alarm.groupId) {
                                                                            onMoveAlarmToGroup(alarm, targetGroupId)
                                                                        }
                                                                        draggedAlarm = null
                                                                        dragOffset = Offset.Zero
                                                                    },
                                                                    onDragCancel = {
                                                                        draggedAlarm = null
                                                                        dragOffset = Offset.Zero
                                                                    }
                                                                )
                                                            }
                                                    )

                                                    Box(modifier = Modifier.weight(1f)) {
                                                        val dismissState = rememberSwipeToDismissBoxState(
                                                            confirmValueChange = {
                                                                if (it == SwipeToDismissBoxValue.StartToEnd) {
                                                                    alarmToDelete = alarm
                                                                    false
                                                                } else false
                                                            }
                                                        )

                                                        SwipeToDismissBox(
                                                            state = dismissState,
                                                            enableDismissFromStartToEnd = true,
                                                            enableDismissFromEndToStart = false,
                                                            backgroundContent = {
                                                                val color by animateColorAsState(
                                                                    when (dismissState.targetValue) {
                                                                        SwipeToDismissBoxValue.StartToEnd -> Color.Red.copy(alpha = 0.5f)
                                                                        else -> Color.Transparent
                                                                    }, label = ""
                                                                )
                                                                Box(
                                                                    Modifier
                                                                        .fillMaxSize()
                                                                        .clip(RoundedCornerShape(12.dp))
                                                                        .background(color)
                                                                        .padding(horizontal = 20.dp),
                                                                    contentAlignment = Alignment.CenterStart
                                                                ) {
                                                                    Icon(
                                                                        Icons.Default.Delete,
                                                                        contentDescription = "Delete",
                                                                        tint = Color.White
                                                                    )
                                                                }
                                                            }
                                                        ) {
                                                            AlarmItem(
                                                                alarm = alarm,
                                                                groupEnabled = group.isEnabled,
                                                                onToggle = onToggleAlarm,
                                                                onDelete = { alarmToDelete = it },
                                                                onDuplicate = onDuplicateAlarm,
                                                                onEdit = onEditAlarm
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // Add Alarm Button inside each Group Card
                                        Button(
                                            onClick = { onAddAlarmClick(group.id) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.AddAlarm, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.add_alarm_task), fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating drag preview render overlay
        if (draggedAlarm != null) {
            val alarm = draggedAlarm!!
            val timeStr = String.format("%02d:%02d", alarm.hour, alarm.minute)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (originalAlarmPositionInParent.x + dragOffset.x).toInt(),
                            y = (originalAlarmPositionInParent.y + dragOffset.y).toInt()
                        )
                    }
                    .width(300.dp)
                    .graphicsLayer(
                        scaleX = 1.05f,
                        scaleY = 1.05f,
                        alpha = 0.95f,
                        shadowElevation = 32f
                    )
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                Color(0xFFF095FF).copy(alpha = 0.2f)
                            )
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = timeStr,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = alarm.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = alarm.getActiveDaysDesc(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }

    if (alarmToDelete != null) {
        AlertDialog(
            onDismissRequest = { alarmToDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_title), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.confirm_delete_text), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAlarm(alarmToDelete!!)
                        alarmToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { alarmToDelete = null }) {
                    Text(stringResource(R.string.cancel), color = Color.Gray)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (groupToDelete != null) {
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("确认删除分组", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("确定要删除分组\"${groupToDelete!!.name}\"吗？分组内的所有闹钟也将被删除。", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(groupToDelete!!)
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("取消", color = Color.Gray)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (groupToEdit != null) {
        var newName by remember { mutableStateOf(groupToEdit!!.name) }
        AlertDialog(
            onDismissRequest = { groupToEdit = null },
            title = { Text("修改分组名称", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("分组名称", color = Color(0xFF8E9099)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onUpdateGroup(groupToEdit!!.copy(name = newName))
                            groupToEdit = null
                        }
                    }
                ) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToEdit = null }) {
                    Text("取消", color = Color.Gray)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
