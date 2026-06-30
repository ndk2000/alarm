# AtomCode 工作规则

> 本文件 + `CHANGELOG.md` 是 AtomCode 的工作记忆系统。
> 新会话第一件事就是读这两个文件。

---

## 核心规则

### 1. 每次回复前必读
- 先读 `ATOMCODE_RULES.md`（本文件）
- 再读 `CHANGELOG.md` → 看 `## Agent工作记录` 区段了解之前改了什么

### 2. 修改代码前
- 先读目标文件的**最新内容**（不要依赖记忆）
- 确认无误后再改

### 3. 每次改动后
- 在 `CHANGELOG.md` 的 `## Agent工作记录` 下追加记录
- **格式（必须精确到行号）：**
  ```
  - `文件路径` L起始行-L结束行: 问题描述 → 改前 → 改后
  ```
- 如果改前改后对比太长，至少写清楚：**改了哪个函数/哪个条件/哪个参数值**
- 同一文件多个不相邻的改动，分行写

### 4. 编译验证（强制执行）
- 每次改动后必须编译验证
- 编译通过后**必须依次执行三步**，缺一不可：
  1. `adb install` 安装到设备
  2. `adb shell monkey -p com.ccsoft.alarm -c android.intent.category.LAUNCHER 1` 打开 App
  3. 向用户确认 App 已打开
- **不要说"搞定"，直到第 3 步完成并报告**

### 5. 与用户沟通
- 用户说"先读 RULES"或"先读 CHANGELOG"时，立即执行
- 用户没有主动提醒时，也要按规则 1 执行

---

## 项目信息

- 项目：DroidCloud IDE（Android 闹钟 App）
- 语言：Kotlin
- 构建：Gradle
- 平台：Android
