# 更新日志 / Changelog

---

## Agent工作记录

### 2026-06-13（铃声时长设置 + 唤醒屏幕优化）
- `AlarmEntity.kt` L37: `Alarm` 数据类新增字段 `ringtoneDurationSecs: Int = 0`（0=持续响铃直到手动关闭）
  - 编码方式：秒数存储，UI 层将"按次数"(N*5s)和"按时间"(N*60s)统一转换为秒
- `AlarmDatabase.kt` L19, L163-L168, L197, L219: 数据库版本 9→10，新增 `MIGRATION_9_10`
  - 迁移内容：`ALTER TABLE alarms ADD COLUMN ringtoneDurationSecs INTEGER NOT NULL DEFAULT 0`
- `AlarmScheduler.kt` L108: `scheduleAlarm()` 向 intent 传递 `ALARM_DURATION_SECS`
- `AlarmReceiver.kt` L31, L41: 从 intent 读取 `ALARM_DURATION_SECS` 并转发给 AlarmService
- `AlarmService.kt` L94, L149, L165-L170: `startRingingForeground()` 新增 `ringtoneDurationSecs` 参数
  - 功能：响铃后若 >0，用 `Handler.postDelayed` 在指定秒数后自动 `stopRinging(alarmId)`
- `AddAlarmDialog.kt` L49, L61-L67, L375-L471: 新增"铃声时长"UI 选择区
  - 三种模式：持续响铃 / 按次数 / 按时间
  - 按次数预设：1次(5s)、10次(50s)、自定义（弹出数字输入框）
  - 按时间预设：1分钟(60s)、10分钟(600s)、自定义（弹出数字输入框）
  - 底部实时显示换算结果（如"5次 × 约5秒/次 = 25秒"）
- `AlarmViewModel.kt` L1151-L1184: `addAlarm()` 新增 `ringtoneDurationSecs` 参数
- `MainAppContent.kt` L69, L524-L527, L546-L556: `onAddAlarm` 回调签名新增 `ringtoneDurationSecs`
- `MainActivity.kt` L494-L496, L633-L634, L656: 传递新参数，预览代码同步更新
- `AlarmService.kt` L149-L151, L228-L243: 修复铃声唤醒逻辑——先唤醒屏幕再响铃
  - 问题：`startRingingForeground` 先播放铃声，`AlarmActiveActivity.onCreate()` 异步亮屏，导致黑屏响铃
  - 改前：`PARTIAL_WAKE_LOCK` 仅保 CPU，不亮屏；`startActivity(fullscreenIntent)` 异步等 activity 亮屏
  - 改后：新增 `wakeUpScreen()`，用 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 在播放铃声前同步亮屏
  - `playAlarmSound()` 移至方法最前面，与亮屏并行执行，保证铃声零延迟

### 2026-07-XX（倒计时预警配置：多音色 + 时长可选 + 唤醒屏幕 + TTS + 自定义录音）
- `AlarmViewModel.kt` L86-L100: 新增倒计时预警设置状态（预警秒数、音色类型、自定义路径、TTS 文字）
- `AlarmViewModel.kt` L357-L377: 新增 4 个 setter 方法，持久化到 SharedPreferences
- `AlarmViewModel.kt` L655-L659: init 中恢复倒计时预警设置
- `AppSettingsDialog.kt` L98-L106: 新增 8 个参数（4 个状态值 + 4 个 setter）
- `AppSettingsDialog.kt` L385-L476: 新增"倒计时预警"设置卡片 UI
  - 功能：6 档预警时长可选（30秒~10分钟）
  - 功能：7 种预警音色可选（滴答声/4种旋律钟声/自定义录音/TTS朗读文字）
  - 功能：选择自定义录音时显示路径输入框
  - 功能：选择 TTS 时显示文字输入框
- `MainAppContent.kt` L140-L148: 新增倒计时预警参数
- `MainAppContent.kt` L346-L353: CountdownTab 调用传入预警设置
- `MainAppContent.kt` L618-L627: AppSettingsDialog 调用传入预警设置
- `MainActivity.kt` L583-L591: 从 ViewModel 收集倒计时预警状态并传递给 MainAppContent
- `CountdownTab.kt` L51-L58: 新增 4 个预警参数
- `CountdownTab.kt` L186-L214: 预警音效逻辑全面改造
  - 改前：仅支持滴答声，硬编码 120 秒
  - 改后：根据 soundType 播放不同音色（tick_tock/chime_0~3/custom/tts）
  - 新增：预警触发时唤醒屏幕（`wakeUpScreen`）
  - 改前：全屏预警硬编码 120 秒
  - 改后：全屏预警使用 `warningSeconds` 可配置值
- `CountdownTab.kt` L296-L298: CountdownCard 红点阈值改为使用 `warningSec`
- `CountdownTab.kt` L471-L542: 新增辅助函数
  - `wakeUpScreen()`: 唤醒屏幕（PowerManager）
  - `playCustomSound()`: 播放自定义录音文件
  - `stopCustomSound()`: 停止自定义录音
  - `speakText()`: TTS 朗读指定文字
- `AlarmsTab.kt` L40-L52: 恢复闹钟转打卡组功能
  - 问题：`onConvertToCheckIn` 参数在整个代码库中消失，组卡片上没有 ✅ 转换按钮
  - 改前：AlarmsTab 没有转换参数，组卡片无转换入口
  - 改后：新增 `onConvertToCheckIn: (AlarmGroup) -> Unit` 参数，组卡片标题行 Delete 按钮后加绿色 ✅ 按钮
- `AlarmsTab.kt` L217-L223: 组卡片新增 ✅ 转换按钮
  - 图标：`Icons.Default.Check`，绿色 `Color(0xFF4CAF50)`
- `MainAppContent.kt` L137: 参数列表新增 `onConvertToCheckIn`
- `MainAppContent.kt` L328-L329: 传递给 AlarmsTab
- `MainActivity.kt` L568-L578: 实现转换逻辑（alarms → CheckInTaskInput → addCheckInGroup）
  - 修复编译错误：`CheckInTaskInput.hour`/`minute` 为 String 类型，需 `.toString()`
- `CountdownTab.kt` L175-L186: 恢复<2分钟滴答警告音
  - 问题：FullScreenUrgentView 只有UI闪烁，未调用 ChimeGenerator.playTickTockContinuous()
  - 改前：仅 UI 闪烁，无声
  - 改后：LaunchedEffect(nearestAlarm) 监控，<2分钟播放滴答声，离开停止；DisposableEffect 卸载时停止

### 2026-06-11（添加 TTS 调试日志）
- `TtsTaskPlayer.kt` L35-L62: voiceName setter 和 applyVoice() 添加详细 logcat 输出
  - 添加：设置 voiceName 时打印新旧值
  - 添加：applyVoice 时打印引擎名、匹配到的语音名和 locale、未匹配时列出前10个可用语音
  - 添加：voiceName 为空或 tts 未初始化时分别提示
- `TtsTaskPlayer.kt` L202-L222: onInit 中添加引擎信息、当前参数、可用语音列表前5个的日志
- `TtsTaskPlayer.kt` L134-L151: generateSync 中添加当前 voiceName 和实际语音的日志
- `TtsTaskPlayer.kt` L261: doSynthesize 中合成日志增加 voiceName 和实际语音信息

### 2026-06-12（修复 generateSync 在 TTS 未就绪时直接返回 null）
- `TtsTaskPlayer.kt` L25-L26: isReady 添加 @Volatile 注解确保跨线程可见性
- `TtsTaskPlayer.kt` L28-29: 新增 initLatch (CountDownLatch) 用于后台线程等待 TTS 初始化
- `TtsTaskPlayer.kt` L64-70: 新增 ensureInitialized(context) 公共方法，可提前预初始化 TTS
- `TtsTaskPlayer.kt` L210-217: ensure() 中创建 TTS 实例时同时创建新的 initLatch
- `TtsTaskPlayer.kt` L253, L263: onInit 成功/失败时均释放 initLatch，避免死等
- `TtsTaskPlayer.kt` L144-160: generateSync 中 !isReady 时不再直接返回 null
  - 问题：ensure() 在主线程创建 TTS 后 onInit 异步回调，isReady 仍为 false → 直接返回 null
  - 改前：Log.w + return null，导致所有任务都拿不到缓存语音
  - 改后：判断当前线程——后台线程通过 initLatch.await() 等待初始化完成；
    主线程加入 pendingQueue 并由 onInit 自动处理，日志清晰提示

### 2026-06-12（取消打卡组对话框时删除已生成的 TTS 缓存）
- `AddCheckInGroupDialog.kt` L133-L148: 新增 `generatedCachePaths` 列表跟踪本次对话框生成的 TTS 缓存文件 + `dismissWithCleanup` 包装函数
  - 问题：点"生成"按钮创建了 TTS 缓存文件，但点"取消"时文件残留不删除，只增不减
  - 改前：`onDismiss` 直接调用，不清理任何缓存
  - 改后：`dismissWithCleanup` 先删除 `generatedCachePaths` 中的所有文件，再调 `onDismiss`
- `AddCheckInGroupDialog.kt` L151: `onDismissRequest` 改为 `dismissWithCleanup`
- `AddCheckInGroupDialog.kt` L611: 取消按钮 `onClick` 改为 `dismissWithCleanup`
- `AddCheckInGroupDialog.kt` L564-L566: 批量生成时记录返回的缓存路径到 `generatedCachePaths`
- `AddCheckInGroupDialog.kt` L253: 试听按钮回调中也记录缓存路径到 `generatedCachePaths`

### 2026-06-12（修复第一次生成语音指派不上）
- `TtsTaskPlayer.kt` L282-L310: `ensure()` 重构，不在主线程时通过 Handler 切到主线程创建 TTS
  - 问题：`scope.launch(Dispatchers.IO)` 中调用 `generateSync` → `ensure()` 在 IO 线程创建 TextToSpeech，没有 Looper 导致 `onInit` 可能永远不回调 → `isReady` 始终 false → 返回 null
  - 改后：判断当前线程，非主线程则 `Handler(Looper.getMainLooper()).post` 到主线程创建，`CountDownLatch` 等待完成

### 2026-06-12（修复 TtsTaskPlayer 没有使用用户选择的 TTS 引擎）
- `TtsTaskPlayer.kt` L40-L48: 新增 `engineName` 属性，setter 中引擎改变时自动 shutdown 重建
- `TtsTaskPlayer.kt` L282-L291: `ensure()` 创建 TTS 时使用 `engineName` 传参（之前写死默认引擎）
  - 问题：`TextToSpeech(ctx, this)` 没传引擎包名，永远用系统默认引擎，用户选的 Google TTS 无效
  - 改后：`TextToSpeech(ctx, this, enginePkg)` 使用用户选择的引擎
- `AlarmViewModel.kt` L627: init 恢复引擎时同步到 `TtsTaskPlayer.engineName`
- `AlarmViewModel.kt` L2117-L2118: `setTtsEngine()` 中先同步引擎到 `TtsTaskPlayer`
- `TtsTaskPlayer.kt` L46-47: 引擎改变时调用 `shutdown()`，下次 `ensure()` 用新引擎重建实例

### 2026-06-12（修复 TtsTaskPlayer.voiceName 没有全局同步的问题）
- `AlarmViewModel.kt` L626-L627: init 恢复保存的语音后，同步设置 `TtsTaskPlayer.voiceName`
  - 问题：App 启动时只恢复了 `_selectedTtsVoiceName.value`，没同步到 `TtsTaskPlayer`，导致即使之前选过语音，`voiceName` 仍为空
- `AlarmViewModel.kt` L2154-L2155: `setTtsVoice()` 中将 `TtsTaskPlayer.voiceName` 设置移到 early return 之前
  - 问题：用户重新点击已选中的语音时，`voiceName == _selectedTtsVoiceName.value` 触发 early return，`TtsTaskPlayer.voiceName` 永远不会被设置

### 2026-06-10（屏幕适配 + 拖拽修复）
- `ScreenScale.kt` (新文件): 新增屏幕自适应工具 ScreenScaleData，根据屏幕宽度/高度动态计算各组件尺寸
  - 按屏幕宽度比例计算拨盘宽（60~120dp）、拨盘高（140~260dp）
  - 按比例计算拨盘字体（18~26sp）、选项行高（20%）、高亮条（22%）、渐隐遮罩（28%）、内容内边距（38%）
  - 对话框最大高度限制为屏幕高度75%（上限700dp）
  - 顶部栏字号根据屏幕尺寸分段（小屏16sp、大屏18sp、平板22sp）
  - 自动识别平板（sw≥600dp）、大屏手机（sw≥420dp）、小屏手机，分别使用不同比例系数
- `WheelDialPicker.kt` L47-L48, L95-L96, L106, L112, L149, L162, L175: 全部硬编码尺寸改为 `rememberScreenScale()` 动态计算
  - 改前：`.height(160.dp).width(72.dp)`、`.height(36.dp)` 高亮条、`32.dp` 选项行高、`20.sp` 字体、`44.dp` 渐隐遮罩、`60.dp` 内容内边距
  - 改后：全部改为 `scale.pickerHeight`/`pickerWidth`/`pickerHighlightHeight`/`pickerItemHeight`/`pickerFontSize.sp`/`pickerFadeHeight`/`pickerContentPadding`
- `AddAlarmDialog.kt` L52, L92: 对话框内容区最大高度从固定 `400.dp` 改为 `scale.dialogContentMaxHeight`（屏幕高度75%）
  - 改前：`.heightIn(max = 400.dp)`
  - 改后：`.heightIn(max = scale.dialogContentMaxHeight)`
- `MainAppContent.kt` L140, L230, L245: 顶部栏日期/时钟字号从固定 `18.sp`/`15.sp` 改为 `scale.topBarDateFontSize.sp`/`scale.topBarClockFontSize.sp`
  - 小屏手机 16sp/14sp，大屏 18sp/15sp，平板 22sp/18sp
- `MainAppContent.kt` L157: 顶部日期年份从四位数改为两位数
  - 改前：`yyyy-MM-dd`
  - 改后：`yy-MM-dd`
- `AlarmsTab.kt` L343-L351: 每个闹钟项左边新增三条杠拖拽手柄图标
- `AlarmsTab.kt` L351-L357: 拖拽落点 `groupBounds` 新增 `DisposableEffect` 自动清理脏条目
  - 改前：组卡片离组时 `groupBounds` 条目保留，拖拽到某位置可能命中其他组的过期 bounds
  - 改后：`DisposableEffect(group.id)` 在 `onDispose` 中 `groupBounds.remove(group.id)`

### 2026-06-10（Sync Tab 改造）
- `WifiSyncTab.kt`: 整体重构，新增内部「本地同步」「云端同步」两个子 Tab
  - 「本地同步」Tab：原有 WiFi 同步全部内容（开关 + Web地址 + 远程同步 + 导入导出）
  - 「云端同步」Tab：新增一次性上传/下载全部功能
    - 「上传全部到云端」按钮：遍历所有闹钟组+打卡组，检查 `cloudShareRecords` 是否已上传，未上传的自动上传
    - 「从云端同步全部到本地」按钮：调用 `cloudService.listConfigs()` 获取云端所有配置，检查是否已导入，未导入的自动下载导入
    - 展示云端连接状态、上传/下载进度条、结果信息
- `MainAppContent.kt` L372-L375: 给 WifiSyncTab 传递 `groups`、`checkInGroups`、`checkInTasksMap`、`cloudService` 参数
- `MainAppContent.kt` L310-L441: 底部导航栏改为 7 个 Tab（Alarms→Countdown→Chimes→Timer→WiFi→CheckIn→Cloud），新增 CountdownTab 和 CloudShareTab 内容


### 2026-06-08
  - 新增参数 `onConvertToCheckIn: (AlarmGroup) -> Unit`，在组卡片标题栏加 ✅ 按钮
- `MainAppContent.kt` L362-L374: 实现闹钟组→打卡组转换
  - 每个闹钟转为 CheckInTaskInput（label→name, hour/minute 不变）
  - 调用 checkInConfig.onAddGroup 创建打卡组，自动切换到打卡 Tab
- `strings.xml` / `values-zh/strings.xml`: 新增 `share_alarms` / `share_checkin` 字符串资源
- `CloudShareTab.kt` L43, L141-L198: 云端Tab改为4个Tab：「分享闹钟」「分享打卡」「导入」「分享记录」
  - 改前：3个Tab，「共享至云端」同时显示闹钟组和打卡组，拥挤
  - 改后：4个Tab，两组分开各自独立显示，不再拥挤
（以下为之前修复的 Bug）
- `CountdownTab.kt` L170-L177: Bug 1 - 浏览历史日期时 countdowns 不依赖 viewDate，显示今日倒计时混在历史记录中
  - 改前：`val nearestAlarm = countdowns.filter...` 无条件计算全屏紧急闹钟
  - 改后：`val nearestAlarm = if (viewDate == todayDate) { ... } else null`，只在今天计算
- `CountdownTab.kt` L243-L251: Bug 1 - 倒计时列表无条件显示
  - 改前：`val activeItems = countdowns.filter { !it.isPast }` 始终显示
  - 改后：包裹在 `if (viewDate == todayDate)` 内，只今天显示
- `AlarmActiveActivity.kt` L88-L94: Bug 2/3 - dismissAlarm 没传 alarmId
  - 改前：`Intent(...).apply { action = "STOP_RINGING" }`
  - 改后：读取 `intent.getLongExtra("ALARM_ID", -1L)` 并 `putExtra("ALARM_ID", alarmId)`
- `AlarmService.kt` L271-L290: Bug 2/3 - stopRinging 没更新 AlarmRecord 状态
  - 改前：只停铃声和通知，record 状态保持 PENDING，10 分钟后被 LaunchedEffect 自动标记 FAILED
  - 改后：alarmId>0 时查 AlarmRecord 并 `updateStatus(id, "COMPLETED", ...)`
- `AlarmService.kt` L286-L290: Bug 4 - 计时器关闭后发广播通知 UI 重置状态
  - 改前：alarmId == -1L 时无操作
  - 改后：`sendBroadcast(Intent("com.example.TIMER_DISMISSED"))` 让 ViewModel 重置 isTimerRinging
- `AlarmViewModel.kt` L2333-L2359: Bug 4 - dismissTimerRinging 重复发送 STOP_RINGING 导致闪退
  - 改前：发 `startForegroundService("STOP_RINGING")` → 服务已销毁 → 新实例没调 startForeground() → crash
  - 改后：仅本地重置状态，不发 intent
- `AlarmViewModel.kt` L673-L684: Bug 4 - 注册 `com.example.TIMER_DISMISSED` 广播接收器
  - 新增：监听 AlarmService 的计时器关闭广播，自动重置 `_isTimerRinging`、`_isTimerRunning`、`_timerRemainingSeconds`
- `AlarmViewModel.kt` L2292-L2295, L2327-L2330, L2351-L2354: Bug 5 - 关 App 计时消失，保存 timerEndMillis 到 SharedPreferences
  - 新增：startTimer 保存结束时间戳，stopTimer/dismissTimerRinging 清除
- `AlarmViewModel.kt` L595-L613: Bug 5 - 关 App 后重新打开时恢复计时器状态
  - 新增：init 检查 timer_end_millis，若尚未来到恢复剩余秒数并重新启动协程

### 2026-05-29
- `AlarmGuardService.kt` (新文件): 新增闹钟常驻守护前台服务
  - 每 30 秒重新调度所有启用闹钟（setAlarmClock 幂等覆盖）
  - 确保护整点报时链条不断
  - 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠延迟调度
  - 低优先级静默通知，无声音无弹窗
  - START_STICKY 被杀死后自动重启
  - 开机后在 MainActivity.onCreate 自动启动
- `AndroidManifest.xml` L102-L109: 注册 AlarmGuardService（foregroundServiceType="specialUse"）
- `MainActivity.kt` L112-L113: 启动闹钟守护服务
- `AlarmReceiver.kt` L33-L51: 移除直接的 startActivity（Android 12+ 拦截后台启动 Activity），改用 AlarmService 通知的 fullScreenIntent 弹出关闭界面
- `AlarmService.kt` L87-L93, L145-L158: startRingingForeground 新增 alarmId 参数，fullScreenIntent 传递 ALARM_ID
- `AlarmService.kt` L177-L187: 闹钟响铃通知添加 `setContentIntent(dismissPendingIntent)`，点击通知即可关闭闹钟
- `CountdownTab.kt` (新文件): 新增实时闹钟倒计时 Tab
  - 功能：显示所有已启用闹钟的实时倒计时（每秒刷新）
  - 不足10分钟：字体放大加粗、橙色高亮、红色边框分割、红色圆点指示
  - 不足2分钟：全屏只显示该闹钟，72sp 极大字体红白闪烁，播放老式机械闹钟滴答声
  - 无闹钟时显示空状态引导
- `ChimeGenerator.kt` L211-L297: 新增老式闹钟滴答声合成引擎
  - 新增 `generateTick(isTick)` 生成短促金属撞击声（滴1500Hz/答1200Hz）
  - 新增 `playTickTockContinuous()` 后台线程循环播放（滴答交替，每秒一次）
  - 新增 `stopTickTock()` 停止滴答声
- `MainAppContent.kt` L36, L136, L245-L249, L323-L326: 添加倒计时 Tab
  - 改前：Tab索引 0=Alarms, 1=Chimes, 2=Timer, 3=WiFi, 4=CheckIn, 5=Cloud
  - 改后：Tab索引 0=Alarms, 1=Countdown, 2=Chimes, 3=Timer, 4=WiFi, 5=CheckIn, 6=Cloud
  - 所有相关索引（FAB、when分支、onNavigateToGroup）同步更新
- `strings.xml` / `values-zh/strings.xml`: 新增 `nav_countdown` 字符串资源
- `CountdownTab.kt` L55-L56: 修复过滤条件用错字段导致所有闹钟都被滤掉
  - 问题：`it.id in enabledGroupIds` 比较的是闹钟自身 id 和分组 id，永远不相等
  - 改前：`alarms.filter { it.isEnabled && it.id in enabledGroupIds }`
  - 改后：`alarms.filter { it.isEnabled && it.groupId in enabledGroupIds }`

### 2026-05-28
- `AddCheckInGroupDialog.kt` L56-L58: 新增 `digitsToChineseUpper` 将字符串中数字转大写
  - 功能：前缀（如"1组"→"壹组"）中的数字也会转成大写数字，配合生成任务名称
  - 改前：前缀原样使用，数字不变
  - 改后：`batchNamePrefix` 和 `groupName` 中的阿拉伯数字全部转为大写中文数字
- `AddCheckInGroupDialog.kt` L56-L78: `digitsToChineseUpper` 改为按数值转换，而非逐个替换
  - 问题：纯替换"11"→"壹壹"、"111"→"壹壹壹"，读音错误
  - 改前：单个数字字符一对一映射 `map { c -> upperNumerals[c - '0'] }`
  - 改后：用 `Regex("\\d+")` 提取连续数字，`numberToChinese(n)` 按数值转换为中文读法，11→十一、111→一百一十一
- `AddCheckInGroupDialog.kt` L41: `upperNumerals` 数组改为简体数字
  - 问题：之前用繁体大写（壹贰叁），TTS 读起来不自然
  - 改前：`arrayOf("零","壹","贰","叁","肆","伍","陆","柒","捌","玖")`
  - 改后：`arrayOf("零","一","二","三","四","五","六","七","八","九")`
- `AddCheckInGroupDialog.kt` L152-L165, L445-L451: 输入框实时转换数字
  - 问题：只在生成按钮里单独对 prefix 转数字，框里仍然是阿拉伯数字
  - 改前：`onValueChange = { groupName = it }` / `{ batchNamePrefix = it }`，原样保存
  - 改后：`val converted = digitsToChineseUpper(it)`，输入时即时将数字转为中文并显示在框中
- `AddCheckInGroupDialog.kt` L99-L100, L509-L514: 间隔改为从设置偏移量读取，生成后自动保存
  - 问题：间隔硬编码为"0h10m"，且生成时 `?: 10` 导致即使清空框也加了10分钟
  - 改前：`batchIntervalHour = "0"`, `batchIntervalMin = "10"`, 生成 fallback `?: 10`
  - 改后：从 `offsetHours`/`offsetMinutes` 初始化；生成 fallback `?: 0`；生成后调用 `onSetOffsetHours`/`onSetOffsetMinutes` 保存
- `AddCheckInGroupDialog.kt` L459-L497: 6个时间输入框聚焦时清空
  - 问题：点击已有内容的输入框，光标定位在末尾，需手动删除
  - 改前：`onFocusChanged` 无处理
  - 改后：每个时间框 `onFocusChanged{ if(it.isFocused) field = "" }`，点一下直接输新数字
- `AddCheckInGroupDialog.kt` L122-L151: 组名输入时同步到 `batchNamePrefix`
  - 改前：只同步到 `tasks[0].name`
  - 改后：同时同步到 `batchNamePrefix`
- `AddCheckInGroupDialog.kt` L410-L416: 任务名称前缀输入时同步到 `groupName`
  - 改前：`onValueChange = { batchNamePrefix = it }`
  - 改后：新建模式下，同时 `groupName = it`
- `AddCheckInGroupDialog.kt` L113-L114: 键盘弹出内容被遮挡
  - 改前：`heightIn(max = 500.dp)` 限制高度
  - 改后：去掉 `heightIn`，改用 `imePadding()` + `verticalScroll`，键盘弹出可完整滚动
- `AddCheckInGroupDialog.kt` L110: 添加 `decorFitsSystemWindows = false`
  - 改前：`DialogProperties(usePlatformDefaultWidth = false)`
  - 改后：`DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`
- `MainAppContent.kt` L566-L569: 传递 `offsetHours`/`offsetMinutes` 到对话框
  - 改前：`AddCheckInGroupDialog` 未传偏移参数
  - 改后：传入 `duplicateOffsetHours`/`duplicateOffsetMinutes` + setter

### 2026-05-27
- `AddCheckInGroupDialog.kt` L120-L129: 组名与第一个任务名称双向同步（新建组时）
  - 问题：新建打卡组时，组名和第一个任务名称各自独立输入，用户需重复填写
  - 改前：`onValueChange = { groupName = it }` 仅更新组名；第一个任务 name 的 `onValueChange` 也不同步组名
  - 改后：组名 `onValueChange` 同步更新 `tasks[0].name`；第一个任务 `onValueChange` (index==0) 同步更新 `groupName`
- `AddCheckInGroupDialog.kt` L483-L501: 批量生成任务名称末尾追加大写数字+"次"
  - 改前：名称格式为 `${prefix}${hourMinuteToChineseUpper(h, m)}`，如"打卡捌点"
  - 改后：名称格式为 `${prefix}${hourMinuteToChineseUpper(h, m)}${numToUpper(seqIndex)}次`，如"打卡捌点壹次"，seqIndex 从1开始递增
- `AddCheckInGroupDialog.kt` L359-L369:
  - 问题：新建模式下 tasks 初始为空列表，用户先输入组名再点"添加任务"时，第一个任务名不会继承组名，双向绑定未生效
  - 改前：`onClick = { tasks = tasks.toMutableList().also { it.add(CheckInTaskInput()) } }`，添加空任务
  - 改后：新建模式且 tasks 为空且组名不为空时，第一个任务默认使用 `CheckInTaskInput(name = groupName)`

### 2026-05-27
- `CloudShareTab.kt` L965-L1004: 分享二维码改为生成带底部文字的合成图，确保微信也能看到编码和来源
  - 问题：`shareQrToWeChat` 仅分享裸二维码图，接收方看不到分享码文字
  - 改前：`QrCodeUtils.encodeToBitmap(shareCode, 512)` 纯二维码
  - 改后：`QrCodeUtils.encodeToBitmapWithCaption(shareCode, "由 Group Alarm 分享")` 二维码在上 + "分享码: xxx" + "由 Group Alarm 分享" 两行文字在下的合成图
- `QrCodeUtils.kt` L36-L92: 新增 `encodeToBitmapWithCaption` 函数
  - 功能：生成带底部说明文字的二维码合成图，二维码在上，两行文字（分享码 + 来源说明）在下

### 2026-05-26（续）
- `CheckInTab.kt` L220-L265: 新增 `showQrDialog` 状态 + LaunchedEffect(cloudShareCode) 自动弹出二维码对话框
  - 问题：打卡Tab点击云分享后没有反馈，需要手动切到CloudShareTab查看二维码
  - 改前：onCloudShareGroup 上传后无后续交互
- `CheckInTab.kt` L56-L60, L96-L120, L165-L190, L320-L340, L370-L395: 云分享按钮反馈流程优化
  - 改前：点击云分享瞬间上传→突然出码，用户无感
  - 改后：弹"正在上传到云端..."对话框，至少停留2秒，成功后自动弹出二维码
  - 新增 `showUploadingDialog`、`uploadStartTime` 状态，LaunchedEffect 控制最短展示时间
  - 去掉按钮上的小转圈，去掉 `CheckInGroupCard` 的 `cloudShareLoading` 参数
- `CloudShareRecord.kt` (新文件): 云端分享记录实体
- `CloudShareDao.kt` (新文件): 分享记录 DAO（查询、插入、删除）
- `AlarmDatabase.kt` L18, L25, L105-L120, L149, L170: 新增 CloudShareRecord Entity 和 DAO、DB版本 6→7 迁移
- `AlarmViewModel.kt` L57, L2097-L2098, L573, L593, L2309-L2316: 新增 `cloudShareRecords` 状态和 Flow
  - `shareAlarmGroupToCloud` / `shareCheckInGroupToCloud` 成功后自动保存记录到数据库
  - 新增 `getLastShareRecordForGroup` / `deleteCloudShareRecord` 辅助方法
  - 修复 `importConfig` 函数中因并行编辑丢失的 `db` 和 `root` 变量
- `CloudShareTab.kt` L43, L71, L163-L171, L780-L853: 新增"分享记录"Tab页
  - 点击底部导航"云端"→第三个Tab"分享记录"可看到所有历史上传记录
  - 每条记录显示：组名、分享码、项目数、分享时间
- `MainAppContent.kt` L134, L412: 接口传递 `cloudShareRecords` 到 CloudShareTab
- `MainActivity.kt` L568: 传递 `cloudShareRecords` 到 MainAppContent
- `strings.xml` / `values-zh/strings.xml`: 新增 `share_records`、`no_share_records` 字符串资源
- `AGENTS.md` L7-L12: 强化中文强制规则
  - 问题：之前的规则描述不够突出，导致 Agent 偶尔在思考或工具描述中使用英文
  - 改前：简单的“全程中用中文思考 中文说话！”
  - 改后：新增“核心规则 0”，明确规定思考过程、工具描述和沟通必须全程使用中文
- `SupabaseShareService.kt` L98: 修复 Supabase 上传报 `PGRST100` 解析错误
  - 问题：在 URL 中错误地传递了 `return=representation` 参数导致 PostgREST 解析失败
  - 改前：`val url = "$baseUrl/rest/v1/$TABLE?columns=...&return=representation"`
  - 改后：移除 URL 中的冗余参数，仅通过 Header 中的 `Prefer: return=representation` 处理返回
- `CloudService.kt` L54-L64: 统一管理云端内置账号凭据
  - 功能：将 Supabase 和 Firebase 的默认凭据全部移动到 `CloudConfigKeys` 中，方便后期维护
- `FirebaseShareService.kt` L18-L30: 移除重复定义的默认凭据
  - 改前：在类内部定义 `DEFAULT_PROJECT_ID` 等常量
  - 改后：直接引用 `CloudConfigKeys` 中的统一定义，保持单一数据源
- `SupabaseShareService.kt` L52-L65, L79, L102, L123, L142, L165: 修复 Supabase URL 缺少 scheme 导致的 `IllegalArgumentException`
  - 问题：直接使用用户输入的 URL 构造请求，若缺少 `http://` 或 `https://` 会导致 OkHttp 崩溃
  - 改前：`val url = "$supabaseUrl/..."`
  - 改后：增加 `baseUrl` 规范化逻辑，自动补全 `https://` 并去除尾部斜杠，同时在网络请求前增加 `configured` 检查
- `CloudService.kt` L20-L46: 增强云端服务接口，支持连接状态检测
  - 改前：仅有上传/下载等基础操作
  - 改后：新增 `ConnectionStatus` 密封类和 `checkConnection()` 挂起函数，定义 Checking, Connected, Error, NotConfigured 四种状态
- `SupabaseShareService.kt` L67-L93 & `FirebaseShareService.kt` L40-L65: 实现 `checkConnection()` 逻辑
  - 功能：通过 HEAD 请求检测服务连通性，并捕获 DNS 解析失败、超时等网络异常，返回详细的中文字符串提示
- `CloudShareTab.kt` L55, L61-L66, L102-L109, L118-L125, L279-L352: 云端 Tab UI 增强
  - 功能：新增 `ConnectionStatusBanner` 组件，在页面顶部实时显示云端连接状态，支持手动重试，并根据不同状态（成功/失败/检测中）切换背景色和图标

### 2025-07-17
- `AlarmViewModel.kt` L2113, L2114, L2136, L2196, L2203, L2256, L2257: 修复 "Unresolved reference 'buildAlarmConfigJson'" 编译错误
  - 问题：在 `AlarmViewModel` 中错误地将 `FirebaseShareService` 的实例方法作为静态方法调用
  - 改前：使用 `FirebaseShareService.buildAlarmConfigJson(...)` 等静态调用
  - 改后：改为使用已初始化的 `cloudService` 实例调用，并删除了多余的 `FirebaseShareService` 导入
- `AlarmViewModel.kt` L58, L226-L245: 修复云端上传服务切换不起作用的问题
  - 问题：`cloudService` 被定义为不可变 `val` 且仅在初始化时赋值，导致在设置中切换服务或更新凭据后，服务实例未同步更新
  - 改前：`private val cloudService: CloudService = getService(application)`
  - 改后：改为 `private var`，并在 `setCloudService`、`setSupabaseCredentials` 和 `setFirebaseCredentials` 方法中添加了重新实例化 `cloudService` 的逻辑，确保配置更改立即生效
- `MainAppContent.kt` L434: Scaffold lambda 闭合括号缺失导致编译失败（137 `{` vs 136 `}`）
  - 改前：Scaffold 内容 lambda 缺少 `}` 闭合
  - 改后：补充 `}`，括号平衡
- `RingtoneSelectionDialog.kt` L243-L365: 三个操作按钮布局重构
  - 改前：`OutlinedButton`/`Button` 内部 `Icon + Spacer + Text`，按钮 `weight(1f)` 等宽，`Row` 使用 `spacedBy(8.dp)`
  - 改后：外层包 `Column(weight(1f))`，按钮 `Modifier.size(48.dp)` 固定方形 + `contentPadding(PaddingValues(0.dp))`，
         图标 20dp，文字移到按钮下方 (`Spacer(4.dp)` + `Text(10.sp)`)，`Row` 改用 `SpaceEvenly`
  - 动机：按钮文字在长文本下会换行变形，改为图标固定方形 + 文字在下，视觉稳定
- `RingtoneSelectionDialog.kt` (中间某行): 修复 Button 3 Text 尾部 `}`→`)` 语法错误
- `AppSettingsDialog.kt` L59-L96 / 删 L330-365: 将「关于」从底部移到最顶部
  - 改前：关于段在电池优化底部，顺序：语言→自动更新→录音路径→复制偏移→主题→电池优化→关于
  - 改后：关于段放在 Column 第一项，顺序：关于→语言→自动更新→录音路径→复制偏移→主题→电池优化
- `ATOMCODE_RULES.md`: 创建工作规则文件，定义会话内必读流程
- `CHANGELOG.md`: 追加 Agent工作记录 区段

---

## v1.2.0 (2025-04-05)

### ✨ 新功能
- **打卡任务系统**：支持创建打卡组、自定义事项列表和提醒时间
- **批量闹钟生成**：一键将打卡事项转换为系统闹钟，支持「追加」或「替换」两种模式
- **电池优化白名单引导**：检测手机省电策略并引导用户关闭，提高闹钟可靠性
- **各品牌手机定制指引**：小米/华为/OPPO/vivo 等品牌厂商的省电设置指南
- **版本号与更新说明页面**：在设置页底部显示应用版本、构建日期和更新日志

### 🔧 改进
- 修复闹钟时间计算逻辑，避免延迟 20 分钟响铃问题
- 设置页面重构，增加「闹钟可靠性」专区
- 底部导航新增「打卡」Tab
- 优化 Android 权限声明与兼容性

---

## v1.1.0 (2025-04-04)

### ✨ 新功能
- 录音路径自定义
- 闹钟复制偏移时间设置
- WiFi 同步功能（支持局域网数据同步）
- 24 小时报时功能（TTS 语音 / 悦耳钟声）
- 多 TTS 引擎与语音选择

### 🔧 改进
- 夜间模式森林主题配色适配
- 录音文件自动传输优化
- 音频加载效率提升

---

## v1.0.0 (2025-04-01)

### ✨ 初始版本
- 基础闹钟功能（创建、编辑、删除、分组管理）
- 多组闹钟分类与拖拽排序
- 自定义铃声（系统铃声 + 本地录音）
- 振动开关
- 主题切换（暗夜 / 森林 / 暖阳）
- 中英文双语支持
- 数据导出/导入备份（JSON + 铃声文件）
