# 更新日志 / Changelog

---

### 2026-06-30（全屏闹钟布局调整 + 颜色条选择器 + 编辑弹窗拖动）

- `FullScreenAlarmTab.kt` L340-L389: 调整全屏闹钟项布局
  - 改前：时间、组名、倒计时在同一行，任务名在第二行
  - 改后：左侧显示时间（占用两行高度），右侧分两行（第一行：组名+倒计时，第二行：任务名）
- `FullScreenAlarmTab.kt` L25-L77: 添加 ColorBarPicker 颜色条选择器函数
  - 用彩虹渐变颜色条替换原来的8个色块选择器
- `FullScreenAlarmTab.kt` L49-L51, L288-L298: 编辑弹窗支持拖动
  - 添加 dialogOffsetX/dialogOffsetY 状态变量
  - 用 Modifier.offset() + pointerInput(detectDragGestures) 实现拖动功能

---

### 2026-06-30（修复TTS重叠播放 + TTS预警音卡顿 + 闹钟关不掉 + 插件时间不同步 + Maven国内镜像）

- `settings.gradle.kts`: `pluginManagement` + `dependencyResolutionManagement` 增加阿里云 Maven 镜像
  - 问题：`mavenCentral()` 默认指向 `repo1.maven.org`（国外），国内下载很慢甚至超时；阿里源放在原始仓库前面，优先从国内下载

- `NextAlarmWidgetProvider.kt` L34-L97: 增加自驱动每秒倒计时刷新机制
  - 问题：插件第二行倒计时不更新，因为 `updatePeriodMillis="0"` 禁用了系统自动刷新，完全依赖 `AlarmService.monitorRunnable` 每秒调用，但 AlarmService 不一定在运行
  - 改后：在 `companion object` 中增加 `startSelfTick()` / `stopSelfTick()`，widget 自身维护 Handler 每秒刷新，不再依赖外部服务
- `layout_timer_widget.xml`: `TextClock` → `TextView`，移除 `timeZone="GMT+8"`、`format12Hour`、`format24Hour`
  - 问题：TextClock 在 RemoteViews 中不稳定，时间不同步；且硬编码 GMT+8 时区
  - 改后：改用 TextView，由 `TimerWidgetProvider` 自驱动每秒 setText 当前系统时间（`SimpleDateFormat("HH:mm:ss")` 跟随系统时区）
- `TimerWidgetProvider.kt`: 重写，增加自驱动每秒刷新机制
  - 新增 `startSelfTick()` / `stopSelfTick()` / `updateTimeOnly()`，用 `partiallyUpdateAppWidget` 每秒只更新时间文字，避免重建 RemoteViews 导致闪烁
  - `updateWidgetStyle()` 保持原有预警变色逻辑不变
- `AlarmScheduler.kt` L115-L130: 修复闹钟响铃后关不掉的死循环问题
  - 问题：`calculateNextAlarmTime` 有 60 秒容忍度，响铃后 `rescheduleSingleInline` 重新调度时计算出已过期的时间（仍在容忍范围内），`AlarmManager.setAlarmClock` 收到过期时间会立即再次触发，形成死循环，关都关不掉
  - 改后：在 `scheduleAlarm` 中增加保护：若 `triggerAtMillis <= now`，一次性闹钟直接 return（不应再调度），重复闹钟推进到明天同一时间
- `AlarmService.kt` L492-L530: 修复 TTS 模式 speakLooping 重叠播放
  - 问题：`speakLooping` 用固定 2 秒间隔调用 `speak()`，但 `speak()` 是异步的（TtsTaskPlayer 或原生 TTS），上一次还没播完下一次就开始了 → 声音重叠
  - 改后：`speak()` 增加 `onComplete` 回调；`speakLooping` 等上一次 `speak()` 真正播完后再调度下一次（间隔 1 秒），绝不重叠
- `TtsTaskPlayer.kt` L514-L583: 修复 TTS 预警音播放卡顿
  - 问题：`playFile()` 每次播放都 `new MediaPlayer()` + `prepareAsync()` + 播放完毕 `release()`，频繁创建/销毁 MediaPlayer 开销大，预警循环每秒播报时卡顿明显
  - 改后：新增 `pooledPlayer` 复用池，首次创建后不再销毁，后续播放只 `reset()` 后重新 `setDataSource()`，避免反复 new/release 开销
- `MainAppContent.kt` L363, `AlarmService.kt` L404: 同步播报文本与 `ChimeAudioPreloader` 预合成文本一致
  - 问题：`ChimeAudioPreloader` 合成用 `"$minute 分钟"` / `"$hour 点整"`，但实际播放用 `"距离闹钟响起还有 $minute 分钟"` / `"现在是北京时间$hour点整"`，文本不匹配导致预合成缓存从未命中

### 2026-06-28（修复 TTS 试听卡主线程 + 类型不匹配）

- `AddCheckInGroupDialog.kt` L255: 修复 `TtsTaskPlayer.play()` lambda 类型不匹配
  - 问题：`play()` 的第3个参数是 `onPlay: ((String) -> Unit)?`，但 trailing lambda `{ cachedPath -> ... }` 被编译器解析为第4个参数 `onComplete: (() -> Unit)?`
  - 改后：使用命名参数 `onPlay = { cachedPath -> ... }`
- `AddCheckInGroupDialog.kt` L254-L262: 修复 TTS 试听按钮卡主线程
  - 问题：`TtsTaskPlayer.play()` 内部在 Edge TTS 场景下会通过 `generateSync()` 同步阻塞等待网络回调，直接在 `onClick`（主线程）调用会卡死 UI
  - 改后：包裹在 `scope.launch(Dispatchers.IO) { ... }` 中异步执行

### 2026-06-14（语音文件无法播放问题修复）

### 2026-06-14（修复 WheelDialPicker 吸附回调多减1，导致23点变-1点 + 兼容处理）
- `WheelDialPicker.kt` L85: 修复拔盘滚动吸附回调多减了 1
  - 问题：`snappedValue` 已经是 range 内的具体值（如 0~23），但回调写成 `onValueChange(snappedValue - 1)`，选 23 传 22，选 0 传 -1
  - 改前：`onValueChange(snappedValue - 1)`
  - 改后：`onValueChange(snappedValue)`
- `WheelDialPicker.kt` L57-L61: initialIndex 加 `value.coerceIn(range.first, range.last)`，防止数据库已有脏数据（如 -1）导致拔盘出界
- `AddAlarmDialog.kt` L54-L55: 编辑时 hour/minute 加 coerceIn 纠正已有脏数据
- `AddAlarmDialog.kt` 保存逻辑: 加 coerceIn 兜底防止再次写入脏数据
- `CountdownTab.kt` L186-L189: 移除 CountdownTab 中独立的预警音效播放逻辑（包括状态变量、`stopAllWarningSounds()`、DisposableEffect、LaunchedEffect 播放块），统一由 MainAppContent 全局管理预警音效
  - 问题：MainAppContent 和 CountdownTab 各有一套完整的独立预警音效播放系统。当用户在其他 Tab 时 MainAppContent 已开始播放预警音，切到倒计时 Tab 后 CountdownTab 的 LaunchedEffect 再次触发播放，两套叠加导致重音
  - 改前：两套独立播放逻辑，切 Tab 时重音
  - 改后：CountdownTab 只负责 UI 展示（全屏倒计时、红点闪烁），预警音效全部由 MainAppContent 控制

### 2026-06-14（修复语音文件无法播放问题）
- `TtsTaskPlayer.kt` L486 (共486行): 重构 TTS 任务播放器底层逻辑，解决生成语音文件但无法播放的问题
  - 问题分析：调用 `generateSync()` 成功生成缓存文件，但播放时常报"文件不存在"或"文件为空"，原因包括：
    - 缓存文件路径无关校验：`generateSync()` 返回路径时未验证文件有效性
    - 缺少重试机制：TTS 合成失败（超时、空文件）后直接返回，不会自动重试
    - 缺少公开调用接口：外部模块无法直接操作缓存文件
    - 缺少批量清理功能：删除打卡组时只能删除单个文件，无法清理整个缓存目录
  - 改前：仅支持 TTS 合成和缓存播放，无有效性检查、重试和批量管理
  - 改后：
    - 缓存文件有效性检查：无论是在 `generateSync()` 返回前还是 `playFile()` 播放前，均检查 `exists() && length() > 0`
    - 合成失败自动重试：base 成功（最多 2 次，间隔 1 秒），降低偶发失败的影响
    - 新增公开 API：
      - `getCacheFile(context, text)`: 获取指定文本的缓存文件（ nullable 返回）
      - `play(context, text, onPlay?)`: 播放并生成（支持回调）
      - `deleteCache(context, text)`: 删除单个文件
      - `getCacheDir(context)`: 获取整个缓存目录
      - `cleanupUnused(context, Set<String>)`: 批量删除未使用的缓存文件
    - 播放函数重构：新增 `playFile(ctx, String)` 公开接口，内部 `playFile(ctx, File)` 检查文件有效性并播放
    - 改进的日志标签："开始生成语音"、"缓存已存在且有效"、"文件为空"、"清理未使用缓存"
- `MainAppContent.kt` L783, L808-L826: 修复缓存清理和重建逻辑
  - L783: 调用 `TtsTaskPlayer.cleanupUnused()` 清理冗余缓存
  - L808-L826: 调用公开 `getCacheFile()` 检查文件是否存在，只生成无效缓存
- `AddAlarmDialog.kt` L299-L304: 修复 UI 显示缓存文件名
  - 将私有 `cacheFile()` 改为公开 `getCacheFile()`，避免 NPE
- `backup_rules.xml` L3: 修复缺失 domain 属性
  - 问题：lint 报错 "Missing domain attribute"
  - 改前：`<exclude path="." />`
  - 改后：`<exclude domain="file" path="." />`

### 2026-06-15（新增 Edge TTS 支持，实现免配置神经网络语音）
- `EdgeTtsClient.kt` (新文件): 实现基于 WebSocket 的 Edge TTS 协议原生客户端
  - 特性：零 Python 依赖、零后端、零费用，支持 MP3 输出、语速音调调节及词级边界。
- `TtsTaskPlayer.kt` L37-L40, L67-L73, L148-L151, L184-L248, L375-L380: 深度集成 Edge TTS
  - L37-L40: 新增 `isEdgeTts` 属性，通过判断引擎名为 "Edge-TTS" 开启。
  - L67-L73: 适配 `ensure` 方法，Edge TTS 模式下跳过原生 `TextToSpeech` 实例创建，直接标记为就绪。
  - L148-L151: 适配 `cacheFile` 后缀逻辑，Edge TTS 使用 `.mp3`，系统 TTS 继续使用 `.wav`。
  - L184-L248: 在 `generateSyncOnce` 中拦截 Edge TTS 请求并调用 `EdgeTtsClient` 同步合成，新增 `formatEdgeParam` 转换语速音调。
  - L375-L380: `cleanupUnused` 增加对 `.mp3` 文件的清理支持。
- `AlarmViewModel.kt` L543-L565: 扩展 TTS 扫描逻辑
  - `scanTtsEngines`: 手动注入虚拟的 "Edge-TTS" 引擎信息。
  - `scanTtsVoices`: 为 "Edge-TTS" 引擎提供 7 种预置的微软神经网络语音（晓晓、云扬、Ava 等）。
- `AlarmService.kt` L75-L88, L540-L555: 适配 Service 层 TTS 逻辑
  - `onCreate`: 识别 "Edge-TTS" 设置，避免初始化原生引擎导致崩溃。
  - `speak`: 在 Edge TTS 模式下将语音朗读请求转发至 `TtsTaskPlayer` 统一处理。
- `ChimeAudioPreloader.kt` L40-L75: 适配整点报时预生成逻辑
  - `file`: 改为通过 `TtsTaskPlayer.getCacheFile` 获取路径，实现小时报时文件自动适配 `.mp3` 后缀。
  - `ensure`: 针对 Edge-TTS 模式新增专用生成流程，循环调用 `TtsTaskPlayer.generateSync`。

### 2026-06-15（补全本地同步与数据管理功能）
- `CloudShareDao.kt` L15: 新增 `getAllRecords()` 挂起函数，支持非 Flow 方式获取所有记录。
- `AlarmViewModel.kt` L18-L19: 导入 `NsdManager` 和 `NsdServiceInfo`。
- `AlarmViewModel.kt` L42-L43: 添加 `discoveryListener` 和 `foundDevicesMap` 用于局域网设备发现。
- `AlarmViewModel.kt` L565-L585: 补全 `syncFromRemote()` 逻辑，调用 `WifiSyncClient` 进行数据拉取。
- `AlarmViewModel.kt` L589-L650: 实现基于 NSD 的局域网设备自动发现逻辑 (`startDiscovery`, `stopDiscovery`)。
- `AlarmViewModel.kt` L750: 补全 `deleteRingtone()` 逻辑，支持物理删除自定义铃声文件。
- `AlarmViewModel.kt` L795-L815: 补全 `clearAllLocalData()` 逻辑，实现一键清空闹钟、打卡及分享记录。
- `AlarmViewModel.kt` L817-L820: 重写 `onCleared()` 确保在 ViewModel 销毁时停止 NSD 发现。

### 2026-06-15（数据安全与权限管理重构）
- `AlarmViewModel.kt` L230-L310: 实现了权限自动检测总线。支持“通知”、“精准闹钟”、“电池优化”、“所有文件访问”、“悬浮窗”五大核心权限的状态轮询与跳转请求。
- `AppSettingsDialog.kt` L1250-L1285: 重构“权限”Tab。改为状态列表显示，已授权权限按钮变灰，未授权权限亮起并支持一键跳转。
- `AppSettingsDialog.kt` L440-L480: 通用设置中新增“文件夹选择器”。对接 SAF 系统文件选择器，方便用户直观选择数据库及录音的存放公共目录。
- `AlarmViewModel.kt` L1505-L1515: 完善数据库与录音路径的持久化逻辑，确保自定义路径在 App 重启后依然生效。
- `MainAppContent.kt` L124, L598: 全链路对接权限列表状态，实现 UI 与 ViewModel 的实时同步。

### 2026-06-15（优化守护自检日志与即时同步）
- `AlarmGuardService.kt` L155-L175: 升级统计明细日志。现在不仅显示开启的闹钟数量，还会详细列出每个活跃闹钟的**标签、响铃时间及重复周期（星期）**。
- `AlarmGuardService.kt` L100-L105: 新增 `REFRESH_AND_REPORT` 指令支持。收到此指令后，服务会立即执行同步并打印最新统计报告。
- `AlarmViewModel.kt`: 在所有涉及闹钟、分组、打卡任务的数据变更操作（开关、删除、移动、导入等）之后，立即向 `AlarmGuardService` 发送刷新指令。
- 效果：用户在界面上的任何操作都会触发 Logcat 瞬间输出最新的调度统计，无需等待。

### 2026-06-15（优化批量生成体验）
- `AddCheckInGroupDialog.kt`: 改进批量生成任务时的进度展示。
  - 将原本位于滚动列表顶部的进度条移除。
  - 新增独立的居中进度对话框（Dialog），包含圆形和水平两种进度指示器，并实时显示百分比和具体任务数。
  - 确保生成过程中用户能直观看到进度，避免因列表滚动导致进度条不可见而产生“死机”错觉。

### 2026-06-15（补全批量生成的中断功能）
- `AddCheckInGroupDialog.kt`: 增强批量生成任务的交互体验。
  - 新增“中止生成”按钮。用户在批量合成语音的过程中，可以随时点击按钮立即停止后续合成，不再强制等待完成。
  - 优化进度展示逻辑。引入 `CoroutineScope` 配合 `Job` 管理生成任务，确保取消操作能够即时响应并清理中间状态。
  - 修复了 `digitsToChineseUpper` 的访问权限，确保其在其他组件（如打卡列表）中也能正常调用。
  - 修复了因引号转义导致的 UI 编译错误。

### 2026-06-15（桌面组件动态字号适配）
- `TimerWidgetProvider.kt`: 实现桌面插件（Widget）字号自动缩放逻辑。
  - 接入 `onAppWidgetOptionsChanged` 回调，实时监听插件尺寸变化。
  - 引入智能缩放算法：根据插件当前的宽度和高度，动态计算最合适的 SP 字号（优先填满高度，同时确保宽度不溢出）。
  - 完善颜色同步逻辑：确保插件能即时反映用户在“设置-个性化”中调整的文字和背景颜色。
  - 优化视觉观感：让时间显示在不同尺寸的网格下都能保持居中、美观且清晰。

### 2026-06-15（新增“最近闹钟”插件、报时总开关与增强预警音）
- **桌面组件增强**：
  - 新增“最近闹钟”桌面插件（Widget）。支持动态显示下一个即将响起的闹钟时间及标签。
  - 自动缩放字号以适配不同网格大小。
  - 接入个性化设置，自动同步用户选定的插件颜色与背景透明度。
- **报时功能优化**：
  - 在“全局设置”中新增“整点报时功能”总开关，支持一键禁用所有时段的报时。
  - 优化 `AlarmReceiver` 逻辑，严格核对全局总开关状态，防止误报。
- **预警音智能化**：
  - `ChimeAudioPreloader` 增加预生成“距离闹钟响起还有 X 分钟”语音文件（1-10 分钟）。
  - 重构预警播报逻辑。在 10 分钟窗口内，每分钟开始时，先播放用户自定义音色（录音/TTS 文字），随后自动追加剩余分钟播报。
  - 实现“不读秒”逻辑，报时更简洁、自然。
  - `TtsTaskPlayer` 支持顺序播放回调，确保多段语音无缝衔接。

### 2026-06-30（修复非 Activity Context 启动 Activity 崩溃 + 编译错误）

- `MainAppContent.kt` L597: 修复 `AndroidRuntimeException`
  - 问题：在 Compose 回调中使用非 Activity Context 启动 Activity 且未加 `FLAG_ACTIVITY_NEW_TASK` 导致崩溃。
  - 改后：为 Intent 添加 `FLAG_ACTIVITY_NEW_TASK` 标志。
- `MainActivity.kt` L215: 修复分享打卡配置时的潜在崩溃
  - 改后：为分享 Intent 添加 `FLAG_ACTIVITY_NEW_TASK`。
- `AlarmGuardService.kt`, `AlarmService.kt`, `TimerService.kt`: 修复 `ServiceStatusMonitor` 相关编译错误
  - 问题：缺少 `ServiceStatusMonitor` 及其常量的导入。
  - 改后：补全相关导入，并正确引用常量（如 `SERVICE_GUARD`, `SERVICE_WARNING`, `SERVICE_TIMER`）。

## Agent工作记录

### 2026-06-30（修复计时器 TTS 不使用用户引擎）
- `TimerTab.kt` L240-L249: 计时器 TTS 播放改为使用 TtsTaskPlayer
  - 问题：计时器结束时选择 TTS 模式，直接创建 `TextToSpeech` 实例，没有使用用户选择的 TTS 引擎，导致"只放语音不放 TTS"
  - 改前：`"tts" -> { TextToSpeech(context, ...) }` 使用系统默认引擎
  - 改后：先读取用户设置的引擎包名，调用 `TtsTaskPlayer.setEngine(enginePkg)`，再用 `TtsTaskPlayer.play(context, text)` 播放，确保使用用户选择的引擎
  - 移除不再需要的 `TextToSpeech` 导入

### 2026-06-30（修复预警音只报一次 + 整点报时使用系统TTS）
- `AlarmGuardService.kt` L372-L465: 预警循环体加 try-catch 防止协程被取消
  - 问题：数据库查询（更新最近闹钟秒数）没有 try-catch，一旦抛异常（独立进程中数据库访问可能不稳定），异常直冒到协程顶层 → `warningRepeatJob` 被取消 → 预警音只报一次就停了；用户选的TTS几分钟后也停了（同一原因）
  - 改前：`while (isActive) { ... }` 循环体没有任何外层异常捕获
  - 改后：整个循环体包一层 `try-catch`，任何异常都被捕获并记录日志，循环继续不中断；播放片段（when 块、播报剩余分钟）独立加 `try-catch` 确保 `CountDownLatch` 被 `countDown`，不阻塞循环；数据库查询块独立加 `try-catch`，失败时保留 `currentNearestSec` 当前值，循环继续
- `AlarmService.kt` L93-L103: AlarmService 创建时同步 TtsTaskPlayer 引擎设置
  - 问题：整点报时调用 `TtsTaskPlayer.play` 时，`TtsTaskPlayer` 可能还没有用正确的引擎初始化（引擎设置是后来才同步到 `TtsTaskPlayer.engineName` 的），导致使用系统默认引擎
  - 改前：`onCreate` 中只初始化 `AlarmService.tts`，没有同步 `TtsTaskPlayer.engineName`
  - 改后：在 `onCreate` 中调用 `TtsTaskPlayer.setEngine(enginePkg ?: "")` 同步引擎设置；在 `triggerHourlyChime` 的 TTS 模式分支中，调用 `TtsTaskPlayer.play` 之前再次同步引擎设置（双重保障）

### 2026-06-30（加固：App被杀后闹钟仍能正常报时）
- `AlarmGuardService.kt` L81: 返回 `START_REDELIVER_INTENT` 替代 `START_STICKY`
  - 问题：App 被杀后守护服务也被杀，`START_STICKY` 只保证重启但不重新传递 Intent，可能导致服务重启后没有正确初始化
  - 改前：`return START_STICKY`
  - 改后：`return START_REDELIVER_INTENT`，系统会重启服务并重新传递最后一个 Intent
- `AlarmReceiver.kt` L31-L33: `BOOT_COMPLETED` 时启动 `AlarmGuardService`
  - 问题：开机广播里只重新调度闹钟，但没有启动守护服务，导致开机后守护服务不存在
  - 改前：只调用 `rescheduleAll(context)`
  - 改后：先 `AlarmGuardService.start(context)`，再 `rescheduleAll(context)`
- `MainActivity.kt` L61-L62: `onCreate` 中确保守护服务启动（双重保障）
  - 问题：用户打开 App 时，如果守护服务没在运行，需要立即启动
  - 改后：每次 `onCreate` 都调用 `AlarmGuardService.start(this)`

### 2026-06-30（修复闹钟关屏时不能及时响）
- `AlarmReceiver.kt` L43-L75: 闹钟触发时直接启动 Activity，确保关屏时及时唤醒
  - 问题：关屏/Doze 模式下，`AlarmReceiver` 只启动 Service，再由 Service 启动 Activity，这一链条可能延迟，导致闹钟不能及时响或屏幕不能及时唤醒
  - 改前：只启动 `AlarmService`，由 Service 再启动 `AlarmActiveActivity`
  - 改后：同时直接启动 `AlarmActiveActivity`（用 `FLAG_ACTIVITY_NEW_TASK`）和 `AlarmService`，双重保障，关屏时也能及时唤醒屏幕

### 2026-06-30（全屏模式锁标缩小并移到右上角）
- `FullScreenAlarmTab.kt` L147-L165: 锁标从左上角移到右上角，缩小尺寸
  - 问题：锁标（🔒/🔓）尺寸太大（48dp，字体28sp），且位置在左上角，影响全屏视觉效果
  - 改前：锁标在 Row 左侧，IconButton 48dp，字体28sp
  - 改后：锁标改为 Box+Alignment.TopEnd 布局放右上角，IconButton 缩小为36dp，字体缩小为18sp

### 2026-06-30（修复状态栏开关不保存）
- `AlarmViewModel.kt` L101-L102: 修复状态栏开关状态不持久化
  - 问题：`isStatusBarClockEnabled` 初始化为 `MutableStateFlow(true)` 硬编码，没有从 `PreferencesManager` 读取已保存的值；且 `init {}` 块中恢复了 `isFloatingTimerEnabled` 却遗漏了 `isStatusBarClockEnabled`，导致每次 ViewModel 重建都重置为 `true`，开关状态无法保存
  - 改前：`val isStatusBarClockEnabled = MutableStateFlow(true)`（硬编码），`init` 块中无恢复逻辑
  - 改后：初始化时读取 `prefs.isStatusBarClockEnabled()`，并在 `init` 块中加 `isStatusBarClockEnabled.value = prefs.isStatusBarClockEnabled()` 与 `isFloatingTimerEnabled` 并列恢复

### 2026-06-30 (优化全屏闹钟布局与自定义功能)
- `app/src/main/java/com/ccsoft/alarm/ui/screens/FullScreenAlarmTab.kt` L50-L69: 删除rankFontSize和rankColor状态变量，去掉序号显示
- `app/src/main/java/com/ccsoft/alarm/ui/screens/FullScreenAlarmTab.kt` L164-L202: 缩小闹钟列表左边和上边空间（start=24.dp, top=8.dp），充分利用屏幕空间
- `app/src/main/java/com/ccsoft/alarm/ui/screens/FullScreenAlarmTab.kt` L316-L377: 调整FullScreenAlarmItem布局，第一行显示倒计时+组名，第二行显示任务名，保证倒计时完整显示
- `app/src/main/java/com/ccsoft/alarm/ui/screens/FullScreenAlarmTab.kt` L209-L320: 重构编辑弹窗，改为右侧居中半透明小卡片（宽度200dp），不遮挡主内容，修改实时可见

### 2026-06-28 (修复 Edge TTS 403 Forbidden 错误)
- `EdgeTtsClient.kt` L52-L53, L59: 修复 403 错误并对齐版本号
  - 问题：Chromium 版本号过新 (149) 导致微软接口拒绝请求；User-Agent 中的 Edg 版本未严格对齐。
  - 改前：`CHROMIUM_FULL_VERSION = "149.0.4022.98"`, `Edg/$CHROMIUM_MAJOR.0.0.0`
  - 改后：回退到稳定版本 `130.0.2849.68`，并将 Edg 版本对齐为 `$CHROMIUM_FULL_VERSION`。### 2026-06-30（修复非 Activity Context 启动 Activity 崩溃 + 编译错误）

- `MainAppContent.kt` L597: 修复 `AndroidRuntimeException`
  - 问题：在 Compose 回调中使用非 Activity Context 启动 Activity 且未加 `FLAG_ACTIVITY_NEW_TASK` 导致崩溃。
  - 改后：为 Intent 添加 `FLAG_ACTIVITY_NEW_TASK` 标志。
- `MainActivity.kt` L215: 修复分享打卡配置时的潜在崩溃
  - 改后：为分享 Intent 添加 `FLAG_ACTIVITY_NEW_TASK`。
- `AlarmGuardService.kt`, `AlarmService.kt`, `TimerService.kt`: 修复 `ServiceStatusMonitor` 相关编译错误
  - 问题：缺少 `ServiceStatusMonitor` 及其常量的导入。
  - 改后：补全相关导入，并正确引用常量（如 `SERVICE_GUARD`, `SERVICE_WARNING`, `SERVICE_TIMER`）。

## Agent工作记录

### 2026-06-28 (为最近闹钟插件增加带秒倒计时，并支持三行独立样式配置)
- `AppSettingsDialog.kt`:
  - **个性化设置终极整合**：实现了真正的“单一焦点”控制中心。
    - **全能复用**：将“标题秒钟”、“状态栏”、“悬浮窗”、“插件秒表”、“最近闹钟”的所有控制（启用开关、样式预览、位置微调、颜色调节、字号调节）全部整合到了一个统一的“样式个性化定制”调节塔中。
    - **开关动态集成**：切换调节目标时，对应的功能总开关会自动浮现。组件未开启时，预览图自动变灰并隐藏调节条，引导用户先开启功能。
    - **位置控制自动对准**：切换到状态栏或悬浮窗时，十字键手柄会自动出现，且无需手动选择调节目标，手柄会自动绑定当前选中的组件。
    - **分层交互逻辑**：针对“最近闹钟”，自动切换到 1:1 三行预览模式，并开启子级行切换逻辑（点击文字或按钮均可切换）。
- `PreferencesManager.kt` & `StatusBarState.kt`: 新增 6 组配置项，支持独立存储最近闹钟插件的“响铃时间”、“实时倒计时”、“闹钟标签”三行的颜色和字号。
- `layout_next_alarm_widget.xml`: 重新设计插件布局，新增 `widget_alarm_countdown` 显示行，并优化间距以支持三行内容。
- `NextAlarmWidgetProvider.kt` & `layout_next_alarm_widget.xml`:
  - **实现自动刷新倒计时**：参考秒表插件的逻辑，将倒计时控件由 `TextView` 改为 `Chronometer`。
  - **零开销同步**：利用系统原生的计时器机制，实现了倒计时秒数的自动、实时跳变，且与系统时间绝对同步，同时消除了每秒强制刷新插件带来的性能损耗和潜在延迟。
  - **背景渲染修复**：在 XML 中增加了专门的背景层（ImageView），通过 `setColorFilter` 进行安全着色。彻底解决了之前由于在布局容器上直接着色导致的“载入窗口小部件时出现问题”崩溃。
- `AlarmService.kt`: 移除每秒强制刷新桌面插件的逻辑，回归低功耗监控模式。
- `AppSettingsDialog.kt`: 在个性化设置中补全了三行独立配置的 UI（预览 + 颜色条 + 字号滑块）。
- `TimerWidgetProvider.kt`: 
  - 修复慢一秒问题：增加了强制重置 `TextClock` 状态的指令，踢走桌面缓存，确保秒表跳变瞬间对齐系统。

### 2026-06-28 (实现全局秒级精准对齐与同步)
- `MainAppContent.kt`, `StatusBarClockService.kt`, `FloatingTimerService.kt`, `AlarmService.kt`, `CountdownTab.kt`: 
  - 彻底重构了所有“每秒一次”的刷新逻辑。
  - 核心逻辑：不再使用固定 `delay(1000)` 或 `Timer` 间隔，而是改为实时计算 `1000L - (System.currentTimeMillis() % 1000)`。
  - 效果：所有显示秒数的位置（顶部时钟、状态栏悬浮秒表、全屏悬浮窗、通知栏倒计时、App内倒计时列表）现在都会在系统整秒边界时同步跳变，消除了由于启动时间不同导致的毫秒级漂移。
- `AlarmService.kt`: 
  - 移除 `CountDownTimer`，改用基于 `Handler` 的精准对齐循环，确保前台通知时间与系统时钟严丝合缝。
  - 优化插件更新频率：仅在预警状态或状态改变时调用 `updateAppWidget`。
  - **核心修复**：在计算倒计时剩余秒数时增加 `+500ms` 偏移进行四舍五入。
    - **问题原因**：Java 时间戳减法产生的毫秒级余数在直接转整数时会被截断（向下取整），导致计算出的秒数总是比肉眼感知的系统时间慢一秒。
    - **改后**：精准对齐系统视觉秒数。
- `TimerWidgetProvider.kt`: 在更新时强制重置 `TextClock` 格式字符串，触发视图层即时重绘，消除桌面启动器缓存带来的显示滞后。

### 2026-06-28 (修复桌面插件文字超出及最近闹钟多行适配)
- `TimerWidgetProvider.kt` & `NextAlarmWidgetProvider.kt`: 
  - 字号单位由 `SP` 变更为 `DIP`，防止受系统字体缩放影响导致溢出。
  - 调小了基础字号的高度与宽度缩放系数（高度占比由 70% 降至 60% / 35%）。
- `NextAlarmWidgetProvider.kt`:
  - 补全 `onAppWidgetOptionsChanged` 回调，使最近闹钟插件能实时响应拖拽缩放。
  - 同步缩放标签（Label）的字号。
- `layout_next_alarm_widget.xml`:
  - 移除冗余的“最近闹钟”静态标题以释放纵向空间。
  - 将闹钟标签的 `maxLines` 由 1 改为 2，并居中对齐，完美支持长标签多行完整显示。
- `layout_timer_widget.xml`: 增加 `includeFontPadding="false"` 压缩空白。

### 2026-06-28 (根据参考项目修复 Edge TTS 403 错误)
- `EdgeTtsClient.kt` 全文件: 根据 `D:\ai\tts-file-gen` 参考项目重构
  - 域名：切回 `speech.platform.bing.com`。
  - 认证：在 URL 中包含动态生成的 `Sec-MS-GEC` 参数。
  - 算法：更新 `generateSecMsGec` 计算逻辑，对齐参考项目的 ticks 处理方式。
  - Header：更新 `User-Agent` 为 143 版本，并增加 `Origin` 字段。

### 2026-06-28（项目全面优化：安全性 + 架构 + 代码质量）
- `CloudService.kt` L62-L65: P0 安全修复：移除硬编码云端凭据（Supabase URL/Key, Firebase ProjectID/APIKey）
  - 改前：`const val DEFAULT_SUPABASE_URL = "https://rfwcckmdjfhcahqddaok.supabase.co"` 等明文凭据
  - 改后：改为从 `BuildConfig` 读取，凭据通过 `.env` 文件 + secrets 插件注入，`.env` 已在 `.gitignore` 中
- `app/build.gradle.kts` L42: P0 安全修复：Release 构建启用 ProGuard 混淆（`isMinifyEnabled = true`）
- `app/build.gradle.kts` L24-L29: 新增 BuildConfig 字段 `SUPABASE_URL/SUPABASE_ANON_KEY/FIREBASE_PROJECT_ID/FIREBASE_API_KEY`
- `app/proguard-rules.pro` L1-L53: 新增完整 ProGuard 规则，保护 Room/Compose/Moshi/OkHttp/Supabase/ZXing/数据实体
- `.env.example` L1-L14: 更新为云端凭据模板文件
- `.env` (新文件): 凭据存储文件（不提交 Git）
- `PreferencesManager.kt` (新文件): 统一管理所有 SharedPreferences 读写，34+ 键名集中定义
  - 涵盖：颜色(10)、字体(2)、开关(4)、TTS(5)、通用(4)、倒计时/计时器(9)、路径(2)、报时(1)
- `AlarmViewModel.kt` L27, L41, L138-L246, L336-L411, L476-L640, L987-L1070, L1562-L1572: 全面替换为 PreferencesManager
  - 改前：33 处 `getSharedPreferences("app_settings", 0).edit().putXxx(...)` 散落各处
  - 改后：统一使用 `prefs.setXxx()` / `prefs.getXxx()`，键名不再散落
- `AlarmViewModel.kt` L320-L337: 新增 `withGuardRefresh` 和 `withGuardRefreshAndMonitor` 高阶函数
  - 消除 12+ 处 `viewModelScope.launch { ... notifyGuardToRefresh() }` 重复模板
- `MainAppContent.kt` L28, L205-L207, L445: chime_prefs 改为使用 PreferencesManager
- `AlarmReceiver.kt` L10, L108-L110, L131-L132: app_settings + chime_prefs 改为使用 PreferencesManager
- `AlarmService.kt` L20, L83-L84, L482-L483, L526-L528, L782-L783: 4 处 SharedPreferences 改为 PreferencesManager
- `TtsTaskPlayer.kt` L12, L241-L242: recording_path 读取改为 PreferencesManager
- `WifiSyncClient.kt` L13, L192-L194: recording_path 读取改为 PreferencesManager
- `AlarmDatabase.kt` L11, L39-L40, L224-L225: database_dir_path 读取改为 PreferencesManager，删除 DB_DIR_PREF 常量
- `CountdownTab.kt` L39-L40, L63-L70: 将 `while(true) { delay(1000) }` 轮询改为 `flow { emit(...); delay(1000) }.collectLatest`
- `CountdownTab.kt` L93: 缓存 `AlarmDatabase` 实例（`remember`），避免每次函数调用重复创建

### 2026-06-28（修复 3 个运行时 bug：关屏不响、TTS试听语音不对、闹钟只响一次）
- `AlarmReceiver.kt` L45-L98: 修复关屏/Doze下闹钟不响
  - 问题：onReceive 在协程中异步查数据库后才 startForegroundService，数据库初始化慢时 onReceive 返回后 CPU 休眠，Service 永远启动不了
  - 改后：立即启动 Service（不等数据库），数据库校验改为后台执行；若校验不通过则通知 Service 停止
- `AppSettingsDialog.kt` L1128-L1152: 修复 TTS 试听不走缓存、不读全局 TTS 设置
  - 问题：原代码直接 `tts.speak()` 实时朗读，没有缓存机制；切换 TTS 设置后文件名不变，仍播旧文件
  - 改后：改为走 `TtsTaskPlayer`，每次同步全局设置（引擎/语音/语速/音调）到 player 属性，`play()` 内部自动根据设置生成唯一缓存文件名，有缓存直接播，无缓存先合成
- `AppSettingsDialog.kt` L1196: 修复试听按钮出现两个三角图标
  - 问题：L1191 有 `Icons.Default.PlayArrow` 图标 + L1196 文本中又有 `"▶ 试听"`，导致重复出现两个三角
  - 改后：去掉文本中的 `▶`，只保留 `"试听"`
- `AlarmService.kt` L188-L198: 修复 TTS 模式下闹钟只响一次
  - 问题：TTS 模式走 `speak(label)` 只播一次，没有循环；非 TTS 模式 `isLooping=true` 正常
  - 改后：新增 `speakLooping()` 方法，每次播完等 2 秒重新播，直到闹钟关闭
- `AlarmService.kt` L365-L369: stopRinging 中增加 `ttsLoopHandler?.removeCallbacksAndMessages(null)` 取消循环

### 2026-06-15（修复 Edge TTS 语音选择失效问题）
- `TtsTaskPlayer.kt` L223-L238, L516-L525: 修复切换 Edge TTS 语音后仍然播放旧缓存的问题
  - 问题：缓存文件名仅由文本决定，导致切换发音人后系统认为缓存已存在而直接播放旧音频。
  - 改后：在 `cacheFile` 生成逻辑中，针对 Edge TTS 将 `voiceName` 作为文件名后缀；同步更新 `cleanupUnused` 匹配逻辑，防止误删带语音后缀的有效缓存。

### 2026-06-15（移除多余 TTS 语音选项）
- `AlarmViewModel.kt` L630-L635: 移除了两个外国发音人声音（Ava 和 Andrew）。
- `AppSettingsDialog.kt` L1273: 移除了语音选择下拉列表中的“默认”选项，使用户必须从具体的发音人中选择。

### 2026-06-15（优化调色板：增加黑白支持与对齐布局）
- `AppSettingsDialog.kt` L1460-L1570: 
  - **补全基础色**：在滑动调色板左侧新增了“纯黑”和“纯白”快捷选择点，解决了色相条无法选出黑白的问题。
  - **对齐布局**：优化了背景颜色设置行的排版，即便在“透明”模式下也保留了占位空间，确保 UI 整体不上下跳动，看起来更加整齐。
  - **高度精细化**：移除了预览框的垂直 Padding，使预览背景块的高度与文字高度完美契合，消除视觉上的多余空间。
  - **交互增强**：滑动条高度微调，操作更加顺手。

### 2026-06-15（重构色彩配置 UI：模块化与预览增强）
- `StatusBarState.kt`: 补全顶部标题栏秒钟的背景色状态（`topBarClockBgColor`）。
- `AppSettingsDialog.kt`:
  - **模块化设置**：移除独立的调色盘卡片，将色彩和字号设置直接集成到“标题栏”、“自由悬浮窗”、“状态栏秒表”各自的开关下方。只有功能开启时，对应的配置才会显示。
  - **实时预览**：为每个组件（秒钟、悬浮窗、状态栏）新增了独立的**效果预览框**，让用户在调整颜色和字号时能即时看到最终效果。
  - **统一调色逻辑**：每个组件现在都拥有标准的“文字颜色”选择条和“背景颜色（含透明开关）”选择条。
- `MainAppContent.kt`: 顶部标题栏秒钟现在应用用户选定的背景颜色，并支持在预警状态下正确切换闪烁色。
- `StatusBarClockService.kt`: 强制清除布局背景，确保高度完美契合字体。

### 2026-06-15（彻底修复“已关闭闹钟仍会响铃”问题）
- `AlarmGuardService.kt` L150-L162: 守护检查逻辑优化
  - 问题：之前守护服务只重设已开启的闹钟，对于已在数据库关闭但系统 AlarmManager 仍残留的任务没做清理。
  - 改后：无论开关状态均调用 `scheduleAlarm`，让其内部的 `cancelAlarm` 能够清理系统残留。
- `AlarmReceiver.kt` L31-L100: 响铃前最后核对
  - 问题：虽然发出了取消，但某些极端情况下系统广播可能已经发出。
  - 改后：在 `onReceive` 接收到 `TRIGGER_ALARM` 后，立即异步查询数据库，确认闹钟及其所属组确实为开启状态。如果已关闭，则拦截响铃。
- `AlarmViewModel.kt` L288-L380: 操作即时同步
  - 问题：之前开关、删除闹钟只改数据库，依赖 30 秒一次的守护服务同步系统。
  - 改后：在 `toggleAlarm`、`toggleGroup`、`deleteAlarm`、`deleteGroup`、`addAlarm` 等方法中，改完数据库后立即调用 `AlarmScheduler` 更新系统闹钟，消除延迟同步带来的隐患。

### 2026-06-15（滑动调色板与顶部秒钟控制）
- `StatusBarState.kt`: 新增 `topBarClockEnabled` 和 `topBarClockColor` 同步状态。
- `AlarmViewModel.kt`: 
  - 完善 `init` 逻辑，正确恢复所有颜色设置和秒钟显示状态。
  - 新增 `setTopBarClockEnabled` 和 `topBarClockColor` 的持久化控制。
- `AppSettingsDialog.kt`:
  - 重构颜色选择器：由固定色块升级为 **HSV 滑动调色板**，支持色相、饱和度、亮度全方位调节。
  - 新增“🔔 标题栏设置”：支持开关右上角秒钟，并独立设置其颜色。
  - 优化背景色设置：增加“透明背景”切换开关。
- `MainAppContent.kt`: 顶部标题栏秒钟现在支持显隐控制，并应用用户选定的自定义颜色。

### 2026-06-15（重构 TTS 生成与缓存逻辑）
- `TtsTaskPlayer.kt` L223-L238: 实现全参数缓存隔离。文件名后缀现在包含 `语音_语速_音调` 标识，确保不同设置生成的音频互不覆盖。
- `AlarmService.kt` L520-L535: 报时逻辑由“预生成”改为“按需生成”。移除 `bypassCache` 等复杂指令，统一使用 `TtsTaskPlayer.play()`。
- `MainAppContent.kt` L331-L341: 预警音 TTS 逻辑重构。移除手动 `MediaPlayer` 管理，统一调用 `TtsTaskPlayer.play()`，确保预警音也能享受全参数缓存隔离。
  - 效果：文件已存在则立即播放，不存在则后台生成并完成后自动播放。
- `AlarmViewModel.kt`: 移除冗余的 `rebuildCache` 逻辑，允许各设置下的缓存按需自然并存。

### 2026-06-15（进一步精简 TTS 引擎标签）
- `AlarmViewModel.kt` L614: 将虚拟引擎的 label 从 "本地推荐tts (微软神经网络语音)" 更改为纯净的 "本地推荐tts"。

### 2026-06-15（重命名 TTS 引擎显示名称）
- `AlarmViewModel.kt` L613-L626: 将虚拟引擎名称从 "Edge-TTS" 更改为 "本地推荐tts"。
- `TtsTaskPlayer.kt`, `AlarmService.kt`, `ChimeAudioPreloader.kt`: 同步更新所有引擎逻辑检查点，将硬编码的 "Edge-TTS" 替换为 "本地推荐tts"。

### 2026-06-15（精简 TTS 语音列表名称）
- `AppSettingsDialog.kt` L1255-L1262: 进一步精简 Edge-TTS 语音名称，移除“神经网络”字样。
  - 改前：晓晓 (神经网络-女)
  - 改后：晓晓 (女)

### 2026-06-15（TTS 语音列表汉化）
- `AppSettingsDialog.kt` L1250-L1285: 将 Edge-TTS 语音 ID（如 `zh-CN-XiaoxiaoNeural`）汉化为友好名称（如“晓晓 (神经网络-女)”）。
  - 改前：列表显示原始 ID 和 Locale，难以识别。
  - 改后：新增 `getVoiceLabel` 映射函数，在 UI 层显示中文名称，底层仍传递原始 ID 确保合成功能正常。

### 2026-06-15（修复 TTS 语音选择及参数调整失效问题）
- `TtsTaskPlayer.kt` L223-L238: 增强缓存隔离机制。现在所有引擎（包括系统 TTS）在指定语音时都会在缓存文件名中加入语音后缀，确保切换发音人后能生成新文件。
- `AlarmViewModel.kt` L630, L665, L675, L680: 强化缓存自动重建。在切换 TTS 引擎、语音、音调或语速时，自动调用 `ChimeAudioPreloader.rebuildCache()` 清理并重建报时语音缓存。
- `AlarmViewModel.kt` L668-L673: 修复 `AlarmService` 语音同步。切换语音时立即通知后台服务更新 TTS 实例。

### 2026-06-15（修复 Edge TTS 403 Forbidden 错误）
- `EdgeTtsClient.kt` L47-L48: 修复 WebSocket 连接返回 403 错误
  - 问题：Chromium 版本号过旧导致微软 Edge 接口拒绝请求（返回 403 Forbidden）。
  - 改前：`CHROMIUM_FULL_VERSION = "130.0.2849.68"`, `CHROMIUM_MAJOR = "130"`
  - 改后：`CHROMIUM_FULL_VERSION = "143.0.3650.75"`, `CHROMIUM_MAJOR = "143"` (参考 `D:\ai\ttsplay` 最新正常参数)

### 2026-06-15（紧急修复 ChimesTab 闪退与 TTS 测试音回退闹铃问题）
- `ChimesTab.kt` L152-L154: 修复展开设置时闪退的问题。移除了 `AnimatedVisibility` 内部 Column 的多余 `verticalScroll`，解决了 Compose 嵌套同向滚动导致的运行时崩溃。
- `AlarmService.kt` L457-L466: 修复 TTS 测试声音播放为系统闹铃的问题。优化了 `triggerHourlyChime` 逻辑，移除在初始化期间播报提示音的错误回退，改为统一使用 `speak(text)` 进行排队处理，确保引擎就绪后能正确朗读文本。

### 2026-06-15（统一云端分享 is_enabled 字段 & 修复上传异常）
- `SupabaseShareService.kt` L130-L150: 统一上传字段名为 `is_enabled` 并新增 `group_name` 字段，修复 `PGRST204` 异常并支持在云端直接记录组名。
- `SupabaseShareService.kt` L284-L305: `listConfigs` 增加 `group_name` 解析，优先使用组名作为列表预览文字。
- `AlarmService.kt` L740-L750: 修复计时器进度不更新问题。在倒计时循环中新增 `TIMER_PROGRESS_CHANGED` 广播发送逻辑，将秒级剩余时间同步至 UI 层。
- `AlarmViewModel.kt` L118-L160: 补全计时器状态同步与持久化。注册广播接收器实时更新 `timerRemainingSeconds`，并支持冷启动时从 `timer_end_millis` 恢复计时状态。
- `ChimeAudioPreloader.kt` L80-L140: 彻底重构并加固报时语音合成逻辑。
  - 新增**单时段 3 次自动重试**机制，解决部分引擎（如小米、三星）偶发性合成失败的问题。
  - 加入 **300ms I/O 等待休眠**，确保 TTS 引擎在回调 `onDone` 后有足够时间完成文件刷盘，彻底根治 0 字节文件问题。
  - 统一文件格式为 `.wav` 并将有效性检测阈值微调至 1KB（兼容极简播报文本）。
- `AlarmService.kt` L75-L95, L539-L560: 深度修复报时服务。将 TTS 配置来源统一为 `app_settings`，修正引擎包名空值处理。
- `AlarmViewModel.kt` L533-L575: 增强 `testTts` 函数，支持自定义测试文本并强制绕过本地缓存，实现即时听感反馈。
- `AlarmViewModel.kt` & `ChimeAudioPreloader.kt`: 实现 TTS 设置变更时的缓存自动失效机制。切换引擎或调整参数时，报时语音缓存标记会自动重置，确保下次报时使用新配置重新生成。
- `ChimesTab.kt` L75-L770: 优化报时页面布局，添加 `verticalScroll` 支持。修复了设置面板展开时底部“速测模式”按钮无法显示的问题。
- `AppSettingsDialog.kt` & `MainAppContent.kt`: 全链路同步 `onTestTts` 函数签名，修复编译错误并对接“语音合成测试”功能。
- `AlarmViewModel.kt` L111-L150, L294-L315, L519-L560: 补全并修复全局设置持久化逻辑。
  - 恢复了 TTS **引擎扫描**和**语音包扫描**功能，确保用户可以选择系统内安装的所有 TTS 引擎（如 Google/微软）。
  - 恢复了 **语速 (Rate)** 和 **语调 (Pitch)** 的设置及记忆功能。
  - 修复了倒计时预警（时长、音色、TTS文字）、主题、语言、自动更新配置在 App 重启后重置的问题。
- `SupabaseManager.kt` L37-L65: 优化登录状态恢复逻辑，改用监听 `sessionStatus` 流代替单次读取。解决 App 重启或冷启动时，因缓存加载延迟导致的登录状态暂时丢失（显示为“未登录”）的问题。
- `SupabaseShareService.kt` L118-L122: 在上传配置前强制检查登录状态，未登录将直接拦截并记录错误日志。
- `CloudShareTab.kt` L1049-L1075: 优化分享管理列表 UI，突出显示分享码（粗体、主标题位置），并增加闹钟/打卡类型标签，提升识别效率。
- `CloudService.kt` L31-L38: `CloudConfig` 数据类新增 `isEnabled: Boolean = true` 字段，实现全链路状态跟踪。
- `FirebaseShareService.kt` L197: 适配 `CloudConfig` 构造函数变更，默认填充 `true` 以保持兼容性。
- 确认建表 SQL 注释中已同步新增 `group_name TEXT` 字段。


### 2026-06-15（修复 Supabase 注册邮箱格式错误 & 汉化错误提示 & 云端数据隔离）
- `SupabaseManager.kt`: 修复 `email_address_invalid` 错误，使用 `@droidcloud.live` 后缀。
- `SupabaseManager.kt`: 汉化 Auth 错误提示，提供直观的中文反馈。
- `SupabaseShareService.kt` & `FirebaseShareService.kt`: 实现云端数据隔离。
  - 上传：自动记录当前登录用户的 `userId`。
  - 列表：查询时仅过滤并展示属于当前账号的分享记录，保护用户隐私。
  - 更新了建表 SQL 注释，方便后续数据库维护。
- `CloudShareTab.kt`: 优化登录框提示。
  - 改前：placeholder 为 "任意字符"。
  - 改后：placeholder 为 "用户名或邮箱"，明确输入要求。

### 2026-06-15（修复预警音和 TTS 播放断断续续的问题）
- `MicrosoftTtsClient.kt` L111-L117: 修正 SSML 格式，添加 `xmlns` 命名空间并对文本进行 XML 转义，确保 Azure 服务器正确识别神经网络语音。
- `MicrosoftTtsClient.kt` L37-L108: 为 Token 获取和语音合成添加重试机制（2 次尝试），并提升音频输出采样率为 24kHz (audio-24khz-160kbitrate-mono-mp3)。
- `ChimeGenerator.kt` L284-L318: 重构 `playTickTockContinuous()`，改用 `MODE_STREAM` 并复用 `AudioTrack`，解决每秒重建对象导致的滴答声断续问题。
- `MainAppContent.kt` L256-L343: 重构全局预警音逻辑，使用 `remember` 的全局 `TextToSpeech` 实例代替频繁初始化；增加 `isSpeaking` 状态检查，确保预警 TTS 读完再循环，不再时有时无。
- `MainAppContent.kt` L315-L330: 修复自定义预警音 `MediaPlayer` 泄露问题，将其生命周期绑定到协程中，确保切换音色时旧声音立即停止。
- `ChimeAudioPreloader.kt` L42: 提高文件有效性检查阈值至 5KB，过滤因网络异常生成的残缺音频文件。
- `WheelDialPicker.kt` L48-L148: 恢复拨盘自动吸附功能
  - 问题：之前的代码误删了吸附逻辑，导致拨盘在滑动后无法自动停留在中心项。
  - 改后：添加 `rememberSnapFlingBehavior` 到 `LazyColumn`；新增滚动监听 `LaunchedEffect`，在滚动停止时自动计算中心项并同步状态；点击选项时新增动画滚动到中心。

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

### 2026-06-30（修复标题栏/状态栏开关保存 + 全屏快捷入口）
- `AppSettingsDialog.kt` L551-L557: 修复标题栏/状态栏/悬浮窗开关保存失败
  - 问题：`onToggle` lambda 语法错误，没有正确接收 `Boolean` 参数，导致开关切换时回调没有正确传值，状态无法持久化
  - 改前：`val onToggle: (Boolean) -> Unit = { when(mainTarget) { ... } }`
  - 改后：`val onToggle: (Boolean) -> Unit = { value -> when(mainTarget) { ... } }`
- `MainAppContent.kt` L387-L389: 全屏模式入口优化，新增顶部栏快捷按钮
  - 问题：全屏入口在"设置→通用→打开全屏显示"，路径太深，用户操作不便
  - 改后：在 TopAppBar 的 actions 区新增 `Icons.Default.Fullscreen` 按钮，点击直接启动 `FullScreenAlarmActivity`，所有 Tab 均可见

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
