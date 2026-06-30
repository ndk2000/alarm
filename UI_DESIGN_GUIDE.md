# DroidCloud IDE UI 设计规范指南

> 基于 Ardot 设计稿生成的 Compose 实现指南
> 
> 设计文件：`DroidCloud-IDE-UI设计.ardot`

## 📋 目录

1. [快速开始](#快速开始)
2. [设计令牌](#设计令牌)
3. [组件规范](#组件规范)
4. [代码示例](#代码示例)
5. [迁移清单](#迁移清单)

---

## 快速开始

### 1. 激活新主题

在 `MainActivity.kt` 中，确保 `appTheme` 设置为 `3`：

```kotlin
// MainActivity.kt
setContent {
    val viewModel: AlarmViewModel = viewModel()
    val appTheme by viewModel.appTheme.collectAsState()
    
    Theme(appTheme = 3) {  // 🎨 使用设计主题
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainAppShell(viewModel)
        }
    }
}
```

### 2. 添加字体（可选）

设计稿使用 **Outfit** 字体。下载并放到 `res/font/` 目录：

```
res/font/
  ├── outfit_regular.ttf
  ├── outfit_medium.ttf
  ├── outfit_semibold.ttf
  └── outfit_bold.ttf
```

然后在 `Type.kt` 中配置：

```kotlin
// Type.kt
val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    )
    // ... 其他文本样式
)
```

---

## 设计令牌

### 颜色

```kotlin
// 来自 Color.kt 的 DesignDark* 系列

val DesignDarkBackground = Color(0xFF08090A)      // 背景
val DesignDarkSurface = Color(0xFF101113)         // 卡片/表面
val DesignDarkSurfaceVariant = Color(0xFF16181C)  // 三级层次
val DesignDarkPrimary = Color(0xFF5E6AD2)         // 主题紫
val DesignDarkOnPrimary = Color(0xFFF7F8F8)       // 主色上的文字
val DesignDarkOnBackground = Color(0xFFF7F8F8)    // 背景上的文字
val DesignDarkOnSurface = Color(0xFFF7F8F8)       // 表面上的文字
val DesignDarkOnSurfaceVariant = Color(0xFFB4B4B8) // 次要文字
val DesignDarkBorder = Color(0xFF1F2023)          // 边框
val DesignDarkSuccess = Color(0xFF10B981)          // 成功色
```

**使用方式**：

```kotlin
// ✅ 推荐：使用 MaterialTheme 颜色系统
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface // 自动使用 DesignDarkSurface
    )
) {
    Text(
        text = "Hello",
        color = MaterialTheme.colorScheme.onSurface // 自动使用 DesignDarkOnSurface
    )
}

// ⚠️ 或者：直接使用自定义颜色
Card(
    colors = CardDefaults.cardColors(
        containerColor = DesignDarkSurface
    )
) {
    Text(
        text = "Hello",
        color = DesignDarkOnSurface
    )
}
```

### 间距

```kotlin
// 基础单位：8px
val SpaceXXXSmall = 4.dp   // 0.5x
val SpaceXSmall = 8.dp     // 1x (基础)
val SpaceSmall = 12.dp     // 1.5x
val SpaceMedium = 16.dp    // 2x
val SpaceLarge = 24.dp     // 3x
val SpaceXLarge = 32.dp    // 4x
val SpaceXXLarge = 48.dp   // 6x
```

**使用方式**：

```kotlin
Column(
    verticalArrangement = Arrangement.spacedBy(16.dp) // SpaceMedium
) {
    // 内容
}
```

### 圆角

```kotlin
val RadiusSmall = 12.dp   // 小组件（按钮、输入框）
val RadiusMedium = 16.dp   // 中组件（按钮）
val RadiusLarge = 20.dp    // 大组件（卡片）
val RadiusXLarge = 36.dp   // 特殊组件（底部导航药丸）
val RadiusCircular = 100.dp // 圆形（胶囊按钮）
```

### 字体大小

```kotlin
val TextHero = 56.sp       // 大型标题（计时器显示）
val TextHeadline = 32.sp   // 页面标题
val TextTitle = 18.sp      // 列表项标题
val TextBody = 16.sp       // 正文
val TextCaption = 14.sp    // 副标题/说明文字
val TextSmall = 13.sp      // 小标签
val TextMini = 10.sp       // 最小标签（导航栏文字）
```

---

## 组件规范

### 1. 卡片 (Card)

**设计稿规范**：
- 背景：`#101113` (DesignDarkSurface)
- 圆角：`20dp` (RadiusLarge)
- 内边距：`20dp`
- 间距（卡片之间）：`16dp` (SpaceMedium)

**代码示例**：

```kotlin
Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
) {
    Column(
        modifier = Modifier.padding(20.dp)
    ) {
        // 卡片内容
    }
}
```

### 2. 列表项 (List Item)

**设计稿规范**：
- 高度：`80dp` (紧凑) 或 `100dp` (带大标题)
- 布局：水平排列，左文本 + 右操作
- 内边距：`16dp` 垂直，可滚动区域 `20dp` 水平

**代码示例**：

```kotlin
@Composable
fun AlarmListItem(
    time: String,
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：时间 + 标签
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = time,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // 右侧：开关
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}
```

### 3. 开关 (Switch)

**设计稿规范**：
- 尺寸：`52dp x 32dp`
- 圆角：`16dp`
- 激活状态：_track_ 为紫色 `#5E6AD2`，_thumb_ 为白色
- 非激活状态：_track_ 为 `#1F2023`，_thumb_ 为灰色

**代码示例**：

```kotlin
Switch(
    checked = isEnabled,
    onCheckedChange = { onToggle(it) },
    modifier = Modifier.size(width = 52.dp, height = 32.dp),
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFFF7F8F8),
        checkedTrackColor = Color(0xFF5E6AD2),
        uncheckedThumbColor = Color(0xFFB4B4B8),
        uncheckedTrackColor = Color(0xFF1F2023)
    ),
    // 自定义形状
    shape = RoundedCornerShape(16.dp)
)
```

### 4. 按钮 (Button)

**主要按钮**：
- 背景：紫色 `#5E6AD2`
- 圆角：`16dp` (RadiusMedium) 或 `100dp` (胶囊形)
- 文字颜色：白色 `#F7F8F8`
- 内边距：`16dp` 水平，`12dp` 垂直

**代码示例**：

```kotlin
// 主要按钮（胶囊形）
Button(
    onClick = { /* 动作 */ },
    modifier = Modifier.height(44.dp),
    shape = RoundedCornerShape(100.dp), // 胶囊形
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary
    ),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
) {
    Text(
        text = "添加",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
}

// 图标按钮（圆形）
IconButton(
    onClick = { /* 动作 */ },
    modifier = Modifier
        .size(44.dp)
        .background(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape
        )
) {
    Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "添加",
        tint = MaterialTheme.colorScheme.onPrimary
    )
}
```

### 5. 底部导航栏 (Bottom Navigation)

**设计稿规范**：
- 容器高度：`97dp` (含安全区域)
- 内边距：`12dp` 顶部，`21dp` 其他方向
- 药丸形外壳：
  - 高度：`62dp`
  - 圆角：`36dp`
  - 背景：`#101113`
  - 边框：`1dp` `#1F2023`
- Tab 项：
  - 内边距：`8dp`
  - 图标大小：`18dp`
  - 文字大小：`10dp`，大写，字间距 `0.5dp`
  - 激活状态：背景 `#5E6AD2`，文字白色
  - 非激活：背景透明，文字 `#B4B4B8`

**代码示例**：

```kotlin
@Composable
fun BottomNavBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("闹钟", "倒计时", "打卡", "设置")
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(97.dp)
            .padding(top = 12.dp, start = 21.dp, end = 21.dp, bottom = 21.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(36.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(36.dp)
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = currentTab == index
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = if (isSelected) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                Color.Transparent,
                            shape = RoundedCornerShape(26.dp)
                        )
                        .padding(vertical = 8.dp)
                        .clickable { onTabSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = when(index) {
                            0 -> "⏰"
                            1 -> "⏱"
                            2 -> "📋"
                            3 -> "⚙"
                            else -> ""
                        },
                        fontSize = 18.sp
                    )
                    Text(
                        text = tab,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) 
                            MaterialTheme.colorScheme.onPrimary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        textTransform = TextTransform.Uppercase,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
```

### 6. 进度环 (Progress Ring)

**设计稿规范**：
- 用于打卡完成度显示
- 尺寸：`60dp x 60dp`
- 圆环宽度：`4dp`
- 颜色：紫色 `#5E6AD2`

**代码示例**：

```kotlin
@Composable
fun ProgressRing(
    progress: Float, // 0.0 - 1.0
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 4.dp
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
```

---

## 代码示例

### 完整的闹钟列表项

```kotlin
@Composable
fun AlarmCard(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：时间 + 标签
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = alarm.time, // 例如 "07:30"
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = alarm.label, // 例如 "工作日"
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 右侧：开关
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFF7F8F8),
                    checkedTrackColor = Color(0xFF5E6AD2),
                    uncheckedThumbColor = Color(0xFFB4B4B8),
                    uncheckedTrackColor = Color(0xFF1F2023)
                )
            )
        }
    }
}
```

### 完整的倒计时显示器

```kotlin
@Composable
fun TimerDisplay(
    time: String, // "25:00"
    label: String, // "专注工作"
    isRunning: Boolean,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 时间显示
        Text(
            text = time,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        // 标签
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // 控制按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 重置按钮
            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 播放/暂停按钮
            IconButton(
                onClick = onStartPause,
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isRunning) 
                        Icons.Default.Pause 
                    else 
                        Icons.Default.PlayArrow,
                    contentDescription = if (isRunning) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            // 跳过按钮
            IconButton(
                onClick = onSkip,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "跳过",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

---

## 迁移清单

### ✅ 需要更新的文件

- [ ] `Color.kt` - ✅ 已完成
- [ ] `Theme.kt` - ✅ 已完成
- [ ] `Type.kt` - 添加 Outfit 字体
- [ ] `MainActivity.kt` - 设置 `appTheme = 3`
- [ ] `AlarmsTab.kt` - 更新卡片样式、开关样式
- [ ] `CountdownTab.kt` - 更新计时器显示、按钮样式
- [ ] `CheckInTab.kt` - 更新打卡列表、进度环
- [ ] `MainAppContent.kt` - 更新底部导航栏
- [ ] `TimerTab.kt` - 更新浮动计时器样式

### 🎨 关键修改点

1. **卡片圆角**：从 `12.dp` 改成 `20.dp`
2. **开关颜色**：更新激活/非激活状态的颜色
3. **按钮样式**：主要按钮使用胶囊形（`100.dp` 圆角）
4. **底部导航**：改用新的药丸形设计
5. **字体**：全局使用 Outfit 字体族

---

## 参考资料

- **设计文件**：`DroidCloud-IDE-UI设计.ardot`
- **Ardot 编辑器**：https://ardot.tencent.com/file/698574526010258
- **项目目录**：`D:\ai\droidcloud-ide`

---

**生成时间**：2026-06-29  
**设计助手**：WorkBuddy Intelligent Design Assistant
