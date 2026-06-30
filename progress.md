# 项目开发进度记录

## 已完成任务
1. **修复预览渲染**：通过将 Composable 重构为无状态组件，解决了 `AlarmViewModel` 实例化导致的预览崩溃。
2. **全量导出导入**：实现了包含数据库和物理铃声文件的 ZIP 打包与恢复功能，支持跨设备路径自动修正。
3. **增强 TTS 报时调节**：
    - 增加了语速和音调调节滑块。
    - 增加了测试报时效果按钮。

- **修复 Edge TTS 403 Forbidden 封禁问题**：
    - **核心逻辑回滚**：参考 `D:\ai\tts-file-gen` 将域名切回 `speech.platform.bing.com`。
    - **Token 算法修正**：重写了 `generateSecMsGec`，将 Ticks 计算与参考项目对齐（毫秒转纳秒并进行 300s 对齐）。
    - **认证参数对齐**：将 Token 放入 URL Query 参数而非 Header。
    - **关键代码段**：
      ```kotlin
      private fun generateSecMsGec(): String {
          var ticks = System.currentTimeMillis() / 1000.0
          ticks += 11644473600.0 // WIN_EPOCH
          ticks -= ticks % 300
          ticks *= 1e9 / 100
          val strToHash = String.format("%.0f", ticks) + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
          val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
          return digest.joinToString("") { "%02X".format(it) }
      }
      ```

- **优化桌面插件适配与防溢出逻辑**：
    - **单位重构**：将所有插件 RemoteViews 字号单位由 `SP` 强制改为 `DIP`，彻底解决用户开启系统大字体后插件“爆框”的问题。
    - **动态缩放**：实现了基于 `onAppWidgetOptionsChanged` 的实时字号计算，插件拉大拉小时文字会自动平滑缩放。
    - **布局精简**：移除最近闹钟插件的冗余标题，为核心内容（时间+标签）腾出 20% 空间。
    - **多行支持**：闹钟标签支持 2 行显示，配合 `includeFontPadding="false"` 确保在 2x1 等小格插件下也能完整呈现。

## 正在处理
- **排查 TTS 测试无声与调节失效问题**：
    - **发现关键问题**：Android 11+ (API 30+) 需要在清单文件中声明 `TTS_SERVICE` 查询权限。
    - **修复**：在 `AndroidManifest.xml` 中增加了 `<queries>` 标签。
    - **调节优化**：将音调和语速范围从 0.5-2.0 扩大到 `0.1f - 3.0f`，产生的听感差异将极其明显（如 0.1 会非常慢且低沉，3.0 会非常快且尖锐）。
    - **增强长驻后台报时能力**：
    - **休眠唤醒加固**：在 `AlarmService` 报时与**闹钟响铃**逻辑中引入了 `WAKE_LOCK` (唤醒锁)，确保设备在灭屏或深度睡眠（Doze 模式）下 CPU 能被强制唤醒完成语音报时和闹铃播放。
    - **状态持久化**：确保 `AlarmService` 返回 `START_STICKY`，在系统内存紧张被杀后能自动重启。
    - 优化了服务内的 TTS 初始化，增加了 `AudioAttributes` 通道绑定，确保应用关闭后的报时声音同样稳定。
    - 修复了报时服务中的语言回退逻辑（由英文回退改为通用中文回退）。
