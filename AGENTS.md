# AGENTS.md — 给 AI 编码代理 / 工具的项目说明书

> 改动本仓库前先读本文件 + `docs/INDEX.md`。关键决策记录在 `docs/项目策略备忘录.md`，架构依据在 `docs/壳-DEX热加载架构设计.md`。

## 1. 项目是什么

车机「蓝牙旋钮配件」：硬件（ESP32 BLE HID 旋钮/按键）只管上报事件；车机端 App 负责按键→动作映射并执行车控（空调、座椅通风等）。车机端是 **壳（只签一次）+ DEX 热加载插件（可反复更新）** 架构。

目标车机实测环境：**Android 12（API 32）**。

## 2. 已验证的硬约束（改动设计前必须遵守）

| 约束 | 依据 |
|------|------|
| 无 root、无 adb；装 App 只能 U 盘 | 项目前提 |
| `BYDAUTO_AC_GET/SET`、`BYDAUTO_SETTING_GET/SET` 为 **signature 级**（protectionLevel=2），普通签名 App 拿不到 | `docs/验证结论.md`（原始日志未入库） |
| `BYDAUTO_AC_COMMON`、`BYDAUTO_SETTING_COMMON` 为 **dangerous 级**，可运行时申请并授予 ✅ | 同上 |
| 直接 HAL 车控被 signature 级权限挡死（`[setIntArray] permission deny` / `[getInt] permission deny`） | 同上 |
| 「包名白名单」是**推断、未验证**：只证实了签名这道门，服务端是否另按包名校验无证据。签名版装车时优先验这个 | `docs/验证结论.md` 关键推论 |
| 车控唯一路线：**用车机厂商认可的签名签壳**（壳进程 UID 继承权限，DEX 继承）。无障碍模拟点击兜底方案 **2026-08-28 已弃用**，项目全押官方签名 | `docs/验证结论.md`、`docs/ROADMAP.md` |
| 本项目的无障碍服务**只用于捕获蓝牙 HID 按键**，不用于模拟点击车机 UI | 同上 |
| 插件 DEX **不能打包 Android 资源、不能声明 Manifest 组件**（Activity/Service/Receiver） | `docs/壳-DEX热加载架构设计.md` |
| **W^X**：Android 10+ 且 `targetSdk>=29`（车机 Android 12 命中）不允许加载「本进程可写」的 dex，否则 `SecurityException: Writable dex file ... is not allowed`。落盘后必须 `setReadOnly()` 再加载，覆盖更新前必须先 `delete()` | 2026-08-28 实机复现（`PluginLoader.kt`） |
| 插件代码跑在壳进程（壳 UID），`api/` 下的契约类必须同时存在于壳与插件的编译 classpath | 同上 |
| 只用车机厂商官方 SDK 接口。一条非官方的 featureId 直写通路实测抛 `NoSuchMethodException`，**已从代码中移除** | `docs/验证结论.md` 结论 7 |

## 3. 架构与文件地图

### 壳 `app/`（Android 工程，applicationId `cn.icarus.knob`）

| 文件 | 职责 |
|------|------|
| `app/src/main/java/cn/icarus/knob/api/KnobPlugin.kt` | 壳↔DEX 契约接口（四功能域，见下） |
| `app/src/main/java/cn/icarus/knob/api/KnobHost.kt` | 插件反向调壳的能力：`log` / `pushToKnob` |
| `app/src/main/java/cn/icarus/knob/api/Ui.kt` | 纯代码构建 View 的工具（插件无资源，只能走代码 UI） |
| `app/src/main/java/cn/icarus/knob/plugin/PluginLoader.kt` | 从私有目录 `filesDir/knob/plugin.dex` 加载；`importDex()` 把用户选中的 dex 拷进私有目录 |
| `app/src/main/java/cn/icarus/knob/host/KnobHostImpl.kt` | `KnobHost` 实现（日志转 LogSink；`pushToKnob` 待接 BLE） |
| `app/src/main/java/cn/icarus/knob/service/KnobAccessibilityService.kt` | 无障碍服务：记录窗口/点击事件、转储控件树、计数 KeyEvent |
| `app/src/main/java/cn/icarus/knob/KnobActivity.kt` | 主界面：左列日志 + 三按钮（选择插件/清空日志/保存日志）；右列 `pluginContainer` 经 `ui.bind` 交给插件，`onDestroy` 发 `ui.unbind`；`dispatchKeyEvent` 把所有按键转发给插件 |
| `app/src/main/java/cn/icarus/knob/KnobApp.kt` | Application：进程启动时**同步**加载插件并 `init`（必然早于 Activity.onCreate，`ui.bind` 才能命中） |
| `app/src/main/java/cn/icarus/knob/util/LogSink.kt` / `CrashHandler.kt` | 日志（车机无 adb，App 内 TextView 即唯一观测手段）与崩溃兜底 |

### 插件 `plugin/`（纯 JVM + Kotlin，产物 `plugin/output/plugin.dex`）

| 文件 | 职责 |
|------|------|
| `src/main/kotlin/cn/icarus/knob/plugin/MainPlugin.kt` | `KnobPlugin` 实现：四功能域路由 + 自管页面栈 |
| `src/main/kotlin/cn/icarus/knob/plugin/CarControl.kt` | 车控引擎：BYDAUTO 设备初始化 / 空调（温度·开关·风量·风向·循环·分控）/ 座椅通风 |
| `src/main/kotlin/cn/icarus/knob/plugin/PluginSelfTest.kt` | 一键全量自检 |
| `src/main/kotlin/cn/icarus/knob/api/` | 与壳 `api/` 逐字节相同的三个契约文件（KnobPlugin / KnobHost / Ui） |

> 新增车控功能 = 只改插件（`CarControl.kt` 加方法 + `MainPlugin.kt` 加一条 `when` 分支）+ 重新 `pluginDex`，**壳不用动、不用重签**。

### KnobPlugin 契约（四功能域）

接口按【功能域】分方法而非一功能一方法，新增功能只加路由分支，接口本身不变 → 壳无需重签：

```kotlin
fun init(context: Context, host: KnobHost)
fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>?  // 写：init.all / seat.ventilating / ac.*
fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>?      // 读：perm.check / selfTest / seat.ventilating / ac.temperature
fun onEvent(event: String, params: Map<String, Any>): Boolean                // 事件：key / ui.bind / ui.unbind
fun onPush(type: String, data: Map<String, Any>)                             // 壳主动推：state / bluetooth / hardware / config
fun onDestroy()
```

`onCommand` / `onQuery` 统一返回 `{"success":Boolean, "code":Int?, "value":Any?, "message":String}`；未知命令返回 `null`。

**按键统一走 `onEvent("key", {"code": Int, "action": Int})`**：蓝牙 HID 旋钮/按键、车机物理键、系统返回键都从 `KnobActivity.dispatchKeyEvent` 这一个入口转发给插件，没有 `back.pressed` 之类的专用事件。用 `dispatchKeyEvent` 而非 `onKeyDown` 是因为它在 View 树分发之前拿到事件，不会被获得焦点的按钮抢走 DPAD/ENTER。插件消费某个键时**按下与抬起要成对返回 true**，只吃一半会让系统按键状态错乱（返回键的出栈逻辑见 `MainPlugin.handleKey`）。

**UI 绑定也走事件域**，接口不为它单开方法：
- `onEvent("ui.bind", {"container": ViewGroup})` —— `KnobActivity.onCreate` 把右半边容器交给插件，插件开始渲染并自管页面栈。
- `onEvent("ui.unbind", {})` —— `KnobActivity.onDestroy` 通知插件断开对该容器/View 树的持有。

⚠️ **壳进程常驻、Activity 反复创建销毁**，所以 `ui.unbind` 是必须的：插件（静态持有）若继续引用旧容器，会泄漏已销毁的 Activity，且下次 `ui.bind` 复用旧 View 时抛 `already has a parent`。Activity 销毁**只解绑 UI，绝不能 `PluginLoader.unload()`**（那会连插件状态和外设连接一起丢掉）。

## 4. 构建命令

```bash
# 壳（产物 app/app/build/outputs/apk/release/app-release-unsigned.apk）
cd app && ./gradlew assembleRelease

# 插件：pluginJar → d8 → plugin/output/plugin.dex（依赖本机 ANDROID_HOME；SDK 路径见 plugin/build.gradle.kts 的 fallback）
cd plugin && ./gradlew pluginDex
```

- 两个工程均为 Kotlin + JVM target 17，SDK 34（`minSdk 26`），Kotlin 2.1.10。
- 插件编译依赖 `plugin/libs/bydauto-openapi.jar`（车机厂商官方车控 SDK，`compileOnly`，运行时由车机系统提供）与 `android.jar`。
  **该 jar 不入库**（第三方专有组件），克隆后需自行放回 `plugin/libs/`，否则插件编译不过。
- 工程未配 `signingConfig`，`assembleRelease` 只产出 **unsigned** APK；装车前需用车机厂商签名单独签。
- 命令行构建需先设 `JAVA_HOME`（本机无系统级 JDK，可用 Gradle 已 provision 的 `~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`）；Android Studio 内构建会自带 JBR。
- `local.properties`、`build/`、`.gradle/` 不入库（见 `.gitignore`）；克隆后 Android Studio 会自动重建 `local.properties`。

## 5. 插件分发与加载

**不走外部存储路径**（避免 Android 11+ 的分区存储限制，也不需要存储权限）：

1. 编译出 `plugin/output/plugin.dex`，拷到 U 盘/车机任意可见位置。
2. 壳里点「📥 选择插件」→ SAF 文件选择器选中 dex。
3. `PluginLoader.importDex()` 先删旧文件（旧的是只读的，不删无法覆盖）→ 拷进私有目录 `filesDir/knob/plugin.dex` → `setReadOnly()`（W^X 要求）→ `DexClassLoader` 加载（parent = 壳 classLoader）、实例化 `cn.icarus.knob.plugin.MainPlugin`、调 `init`，随后 Activity 发 `ui.bind` 交出容器。
4. 之后每次启动由 `KnobApp` 自动从私有目录加载，无需再选。更新插件 = 重复步骤 1-2 覆盖。

### 进程生命周期

- 车机开机后**由无障碍服务把进程拉起**（系统绑定已启用的 AccessibilityService）→ `KnobApp.onCreate` 跑 → 插件加载。不需要 `RECEIVE_BOOT_COMPLETED`。
- 同一个绑定也让**进程常驻**：退出 Activity 不会销毁进程，BLE 等外设连接可以挂在壳里长期存活。
- 待实测：车机定制系统开机后是否自动恢复无障碍服务的启用状态。

## 6. 调试与验证流程（减少签名次数）

调试顺序刻意设计为 **先本地、后签名**：

1. **阶段 A（无签名）**：ESP32 BLE HID 键盘 → 车机识别 → 无障碍服务捕获按键 → 日志显示收到哪个键。
2. **阶段 B（无签名）**：按键 → 动作映射框架（先映射成日志/UI，不实际调车控）。
3. **阶段 C（需签名）**：按键 → 调 BYDAUTO 车控接口 —— 这一步才需要车机厂商签名版，最后一次性签名测试。

## 7. 约定

- 壳保持薄：**业务逻辑一律放插件**，不要往壳里加车控代码。
- 改 `api/` 下任何文件，**必须同步改另一份**（壳 `app/.../api/` 与插件 `plugin/.../api/` 内容需完全一致），否则运行时签名不匹配。
- 插件内避免使用壳已有的类名（类加载冲突）；不引用壳私有资源，UI 一律用 `Ui.kt` 纯代码构建。
- 所有操作/返回码要进 `LogSink` 日志（车机无 adb，日志即唯一观测手段；返回码含义见 `app/使用与验证指南.md`）。
- 本机环境备注：调研用的大体积参考资料（约 580MB）在本地工作区 `~/Documents/fcb`，不入库。
- **不入公开仓库的资料**（忽略规则见根 `.gitignore`，文件仍在本地）：
  `plugin/libs/`（车机厂商 SDK）、`docs/evidence/`（实车日志）、`docs/local/`（立项阶段调研笔记）。
  引用这些资料下结论时，请把结论本身写进 `docs/验证结论.md`，那份是入库的。

## 8. 已知问题 / 待办

| 问题 | 位置 | 说明 |
|------|------|------|
| 后台按键未转发给插件 | `KnobAccessibilityService.onKeyEvent` | Activity 在前台时按键已由 `dispatchKeyEvent` 转发；但无障碍服务这条**全局**通路（Knob 不在前台时捕获旋钮按键）仍只计数、未转发。插件 `handleKey` 里除返回键外的映射也还是 TODO（路线图阶段 4/5） |
| `api/` 三个类被打进 dex | `plugin/build.gradle.kts` 的 `pluginJar` | 双亲委派下运行时实际用壳的类，暂不致命；若将来把 parent 改成 null 会 `ClassCastException` |
| `WRITE_SECURE_SETTINGS` 拿不到 | `AndroidManifest.xml` | signature 级权限，探测阶段残留，当前代码未用 |
