# Knob — 车机蓝牙旋钮配件

> 目标车机车机的「蓝牙物理旋钮/按键配件」DIY 项目。
>
> **硬件端**：BLE HID 旋钮 + 按键（ESP32 系，只负责"上报按键/旋钮事件"）。
> **车机端**：Android App（壳 + DEX 热加载插件），接收蓝牙输入并翻译成车控动作（空调 / 座椅通风 / 自定义宏）。

## 核心思路

```
┌─────────────┐   BLE HID   ┌────────────────────────────────────┐
│  硬件配件     │ ─────────▶ │ 车机（车机）                          │
│ ESP32+编码器  │            │  壳 App（车机厂商签名，只签一次）         │
│ +按键         │            │   └─ DexClassLoader → plugin.dex    │
└─────────────┘            │      （全部车控/业务逻辑，可热更新）    │
                           └────────────────────────────────────┘
```

- **硬件 = 键盘/旋钮**，不知道也不关心自己在控制什么。
- **App = 大脑**，负责按键→动作映射，并执行车控。
- **壳只签名一次**，后续所有代码更新通过下发 `plugin.dex` 完成，避免反复"上传→签名→装车"。

## 目录结构

```
├── app/                 # 壳 App（Android 工程，cn.icarus.knob）
│   ├── 使用与验证指南.md  # App 用法 / 实车验证步骤说明书
│   └── app/src/main/java/cn/icarus/knob/
│       ├── api/          # 壳↔DEX 契约：KnobPlugin / KnobHost / Ui
│       ├── plugin/       # PluginLoader：发现并加载 plugin.dex
│       ├── host/         # KnobHostImpl：壳向插件提供的能力
│       ├── service/      # KnobAccessibilityService（捕获按键）
│       ├── util/         # 日志（LogSink）/ CrashHandler
│       └── KnobActivity / KnobApp
├── plugin/               # 插件工程（编译产出 output/plugin.dex，可反复更新）
│   └── src/main/kotlin/cn/icarus/knob/
│       ├── api/               # 与壳完全相同的契约副本（改动须两边同步）
│       └── plugin/
│           ├── MainPlugin.kt      # KnobPlugin 接口实现（入口 + 页面栈）
│           ├── CarControl.kt      # 车控引擎（BYDAUTO 调用）
│           └── PluginSelfTest.kt  # 一键全量自检
├── firmware/             # 旋钮固件（PlatformIO，ESP32-S3+GC9A01+EC11，最小硬件调试，暂不连车机/BLE）
├── store/                # 应用商店素材（图标 + 预览图）
└── docs/                 # 全部分析知识 / 方案 / 证据
    ├── INDEX.md          # ← 文档索引（先读这个）
    ├── 验证结论.md        # 实车验证事实清单（持续更新）
    └── ROADMAP.md        # 路线图与当前状态
```

## 快速开始

```bash
# 壳 App → app/app/build/outputs/apk/release/app-release-unsigned.apk
cd app && ./gradlew assembleRelease

# 插件 → plugin/output/plugin.dex（依赖本机 ANDROID_HOME 的 android-34 + build-tools）
cd plugin && ./gradlew pluginDex
```

装车流程：U 盘安装壳 APK（需车机厂商签名版）→ 打开壳点「📥 选择插件」，用文件选择器选中 `plugin.dex`（自动拷进 App 私有目录，之后每次启动自动加载）→ 右半边出现插件页面即成功。详见 `app/使用与验证指南.md`。

## 关键事实（已验证）

- 无 root、无 adb，只能 U 盘装 APK。
- `BYDAUTO_AC_GET/SET`、`BYDAUTO_SETTING_GET/SET` 是 **signature 级权限**（protectionLevel=2），普通签名 App 车机不授予 → 直接 HAL 车控被挡死（实测，见 `docs/验证结论.md`）。
- 能直接控车的应用都持有车机厂商签发的签名。本项目只用车机厂商官方 SDK 接口；一条非官方的 featureId 直写通路实测抛 `NoSuchMethodException`，已从代码中移除。
- ⚠️ 服务端是否**另有包名白名单**目前是推断、未验证 —— 若存在，拿到签名也可能被拒，是本方案最大前提风险。
- **无兜底路线**：无障碍模拟点击方案已弃用（2026-08-28），全押官方签名。本项目的无障碍服务只用于捕获蓝牙 HID 按键。

## 文档导航

所有知识文档的索引见 [`docs/INDEX.md`](docs/INDEX.md)；工具/代理请先读 [`AGENTS.md`](AGENTS.md)。

## 未入库的本地资产

以下内容**不入公开仓库**，只留在本地工作区（忽略规则见 `.gitignore`）：

- `plugin/libs/bydauto-openapi.jar` —— 车机厂商官方车控 SDK，第三方专有组件。**克隆后需自行放回该路径，插件工程才能编译。**
- `docs/evidence/` —— 实车日志，含车机指纹与已安装应用清单；结论已汇总到 `docs/验证结论.md`。
- `docs/local/` —— 立项阶段的调研笔记与参考资料。
- 调研用的大体积本地资产（约 580MB），留在本地工作区 `~/Documents/fcb`。
